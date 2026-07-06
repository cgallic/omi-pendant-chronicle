from __future__ import annotations

import asyncio
import logging
import platform
import socket
import time
from dataclasses import dataclass

import httpx
from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice
from bleak.exc import BleakError

from . import uuids
from .uploader import AgentUploader

LOG = logging.getLogger("pendant.collector")

CMD_LIST_FILES = 0x10
CMD_READ_FILE = 0x11
CMD_DELETE_FILE = 0x12

STORAGE_OK = 0
INVALID_COMMAND = 6
FILE_NOT_FOUND = 7
FILE_INDEX_OUT_OF_RANGE = 8
STORAGE_NOT_READY = 9
STORAGE_DONE = 100

STORAGE_TIMESTAMP_BYTES = 4
STORAGE_BLOCK_BYTES = 440
STORAGE_BLOCK_USABLE_BYTES = STORAGE_BLOCK_BYTES - 1
MAX_STORAGE_OPUS_PAYLOAD = 127
PROGRESS_LOG_BYTES = 1_048_576
CONNECT_SERVICES = [
    uuids.AUDIO_SERVICE,
    uuids.SETTINGS_SERVICE,
    uuids.STORAGE_SERVICE,
]
WINRT_ARGS = {
    "address_type": "random",
    "use_cached_services": False,
}

# A hard BLE disconnect mid-LIST/READ used to kill the whole sync() call and
# abandon whatever file was in flight - the next scheduled run (10 min later)
# would then find that file gone from the pendant's own storage (live
# evidence, 2026-07-06: two files disappeared this way, never uploaded, never
# explicitly deleted by us). Reconnecting immediately, within the same
# invocation, is the only way to have a shot at catching the file before
# whatever the pendant does with an abandoned in-flight read.
MAX_RECONNECT_ATTEMPTS = 3
RECONNECT_BACKOFF_S = 2.0


@dataclass(frozen=True)
class StorageFile:
    index: int
    timestamp: int
    size: int

    @property
    def key(self) -> str:
        return f"{self.timestamp}:{self.size}"

    @property
    def started_at_ms(self) -> int | None:
        if 1_500_000_000 <= self.timestamp <= 4_102_444_800:
            return self.timestamp * 1000
        return None


class StorageProtocolError(RuntimeError):
    pass


class OmiDesktopCollector:
    def __init__(
        self,
        *,
        agent_url: str,
        secret: str,
        address: str | None = None,
        scan_timeout_s: float = 20.0,
        connect_timeout_s: float = 45.0,
        stall_timeout_s: float = 30.0,
        storage_ready_timeout_s: float = 90.0,
        dry_run_delete: bool = False,
        lease_holder: str | None = None,
        use_lease: bool = True,
    ) -> None:
        self.agent_url = agent_url
        self.secret = secret
        self.address = address
        self.scan_timeout_s = scan_timeout_s
        self.connect_timeout_s = connect_timeout_s
        self.stall_timeout_s = stall_timeout_s
        self.storage_ready_timeout_s = storage_ready_timeout_s
        self.dry_run_delete = dry_run_delete
        self.use_lease = use_lease
        self.lease_holder = lease_holder or f"desktop-{socket.gethostname()}"
        self.lease_ttl_s = 600.0
        self._notify_queue: asyncio.Queue[bytes] = asyncio.Queue()
        self._seq = 0
        self.uploader = AgentUploader(agent_url, secret)

    async def scan(self) -> list[BLEDevice]:
        devices = await BleakScanner.discover(timeout=self.scan_timeout_s, return_adv=True)
        found: list[BLEDevice] = []
        for device, adv in devices.values():
            name = device.name or adv.local_name or ""
            service_uuids = {str(service_uuid).lower() for service_uuid in (adv.service_uuids or [])}
            is_omi = name in uuids.ADVERTISED_NAMES or uuids.AUDIO_SERVICE in service_uuids
            if is_omi:
                found.append(device)
                LOG.info(
                    "found pendant name=%s address=%s rssi=%s services=%s",
                    name,
                    device.address,
                    adv.rssi,
                    ",".join(sorted(service_uuids)),
                )
        return found

    async def scan_first(self, wanted_address: str | None = None) -> BLEDevice:
        loop = asyncio.get_running_loop()
        found: asyncio.Future[BLEDevice] = loop.create_future()
        wanted = wanted_address.lower() if wanted_address else None

        def on_advertisement(device: BLEDevice, adv) -> None:
            if found.done():
                return
            name = device.name or adv.local_name or ""
            service_uuids = {str(service_uuid).lower() for service_uuid in (adv.service_uuids or [])}
            is_omi = name in uuids.ADVERTISED_NAMES or uuids.AUDIO_SERVICE in service_uuids
            if not is_omi:
                return
            if wanted and device.address.lower() != wanted:
                return
            LOG.info(
                "found pendant name=%s address=%s rssi=%s services=%s",
                name,
                device.address,
                adv.rssi,
                ",".join(sorted(service_uuids)),
            )
            found.set_result(device)

        scanner = BleakScanner(on_advertisement)
        await scanner.start()
        try:
            return await asyncio.wait_for(found, timeout=self.scan_timeout_s)
        except asyncio.TimeoutError as exc:
            raise RuntimeError("No Omi/Friend pendant found") from exc
        finally:
            await scanner.stop()

    def _lease_base(self) -> str:
        u = self.agent_url.rstrip("/")
        return u[:-4] if u.endswith("/raw") else u

    async def _lease_acquire(self) -> bool:
        """Acquire/renew the drain lease so only one client drains at a time.

        Returns True to proceed (granted, or the sink has no lease endpoint / is
        unreachable — best-effort backward compatibility), False only when another
        client explicitly holds the lease.
        """
        if not self.use_lease:
            return True
        url = self._lease_base() + "/lease/acquire"
        try:
            async with httpx.AsyncClient(timeout=8.0) as c:
                r = await c.post(
                    url,
                    params={"holder": self.lease_holder, "ttl": self.lease_ttl_s},
                    headers={"X-Pendant-Secret": self.secret},
                )
            if r.status_code == 404:
                return True  # older sink without the lease endpoint → proceed
            if r.status_code >= 300:
                LOG.info("lease acquire http %s; proceeding best-effort", r.status_code)
                return True
            data = r.json()
            if data.get("granted"):
                return True
            LOG.info(
                "another client holds the drain lease (%s, %ss left); skipping this pass",
                data.get("holder"), data.get("expires_in"),
            )
            return False
        except Exception as exc:
            LOG.info("lease endpoint unreachable (%s); proceeding best-effort", exc)
            return True

    async def _lease_release(self) -> None:
        if not self.use_lease:
            return
        try:
            async with httpx.AsyncClient(timeout=8.0) as c:
                await c.post(
                    self._lease_base() + "/lease/release",
                    params={"holder": self.lease_holder},
                    headers={"X-Pendant-Secret": self.secret},
                )
        except Exception:
            pass

    async def sync(self, *, once: bool = False, max_files: int | None = None) -> None:
        # Hold the shared drain lease so the desktop and the phone don't drain the
        # pendant at the same time (and re-upload each other's files).
        if not await self._lease_acquire():
            return
        try:
            await self._drain(once=once, max_files=max_files)
        finally:
            await self._lease_release()

    async def _open_client(self, connect_target: BLEDevice | str) -> BleakClient:
        client = BleakClient(
            connect_target,
            timeout=self.connect_timeout_s,
            services=CONNECT_SERVICES,
            winrt=WINRT_ARGS,
        )
        await client.connect()
        if not client.is_connected:
            raise RuntimeError("BLE connection failed")
        await self._best_effort_setup(client)
        await self._ensure_storage_available(client)
        await client.start_notify(uuids.STORAGE_WRITE_CHAR, self._on_storage_notify)
        return client

    async def _reconnect(self, client: BleakClient, connect_target: BLEDevice | str) -> BleakClient:
        try:
            if client.is_connected:
                await client.disconnect()
        except Exception:
            pass
        return await self._open_client(connect_target)

    async def _drain(self, *, once: bool = False, max_files: int | None = None) -> None:
        device = await self._resolve_device()
        connect_target = self._connect_target(device)
        device_label = device.address if isinstance(device, BLEDevice) else device
        device_name = device.name if isinstance(device, BLEDevice) else "Omi"
        LOG.info("connecting address=%s name=%s", device_label, device_name)

        client = await self._open_client(connect_target)
        try:
            synced = 0
            while max_files is None or synced < max_files:
                # One LIST per batch, not one per file. LIST is a firmware
                # directory scan that can trigger a 10-50s full-filesystem
                # traversal when LittleFS's allocation window is exhausted
                # (sd_card.c:68-76); re-listing before every file paid that
                # stall repeatedly and is what made sync feel like it "reads
                # all the files before it starts".
                files, client = await self._list_files_resilient(client, connect_target)
                if files is None:
                    LOG.error(
                        "giving up on this connection after repeated BLE drops during LIST; "
                        "next scheduled run will retry"
                    )
                    return
                if not files:
                    LOG.info("storage empty; sync complete")
                    return

                LOG.info(
                    "listed %d files in batch; draining oldest-first without re-listing (first size=%s ts=%s)",
                    len(files),
                    files[0].size,
                    files[0].timestamp,
                )
                for target in files:
                    # After each delete the pendant re-indexes, so the oldest
                    # undrained file is always index 0. Walk our cached
                    # metadata for timestamps but read/delete index 0. In
                    # dry-run we never delete, so nothing shifts and we must
                    # read each file at its original index instead.
                    read_index = target.index if self.dry_run_delete else 0

                    # A hard BLE disconnect mid-read (observed live: "Not
                    # connected" from bleak) used to propagate straight out of
                    # sync() and abandon the file entirely. Live evidence
                    # (2026-07-06) shows the pendant does not reliably keep an
                    # interrupted file around for the *next* scheduled run 10
                    # minutes later - it can be gone by the time we reconnect.
                    # So the retry has to happen NOW, inside this same
                    # invocation, not by waiting for the next cron tick.
                    raw_bytes, client = await self._read_file_resilient(client, connect_target, target, read_index)
                    if raw_bytes is None:
                        LOG.error(
                            "FILE LIKELY LOST: gave up on index=%s size=%s ts=%s after repeated BLE "
                            "disconnects during read - it may no longer be retrievable from the pendant",
                            read_index, target.size, target.timestamp,
                        )
                        return

                    frames = self.build_storage_frames(raw_bytes)
                    if not frames and target.size > 0:
                        raise StorageProtocolError(
                            f"file produced no frames index={read_index} size={target.size}"
                        )

                    LOG.info("file safe index=%s size=%s frames=%s", read_index, target.size, len(frames))
                    result = await self.uploader.post_frames(frames, started_at_ms=target.started_at_ms)
                    if not result.ok:
                        raise StorageProtocolError(
                            f"upload failed after {result.frames} frames status={result.status_code} error={result.error}"
                        )

                    if self.dry_run_delete:
                        LOG.warning("dry-run delete: leaving pendant file index=%s in place", target.index)
                    else:
                        LOG.info("uploaded frames=%s; deleting pendant index 0", result.frames)
                        # The upload already succeeded at this point, so a
                        # failed delete is NOT a data-loss risk (worst case:
                        # the file gets re-read and re-uploaded next cycle,
                        # a harmless duplicate) - but it's still worth
                        # reconnecting to finish the cleanup immediately
                        # rather than crashing the whole run and waiting for
                        # the next scheduled tick just to retry a DELETE.
                        client = await self._delete_file_resilient(client, connect_target, target)
                    synced += 1
                    await self._lease_acquire()  # renew the lease TTL between files
                    if once or (max_files is not None and synced >= max_files):
                        return

                if self.dry_run_delete:
                    # Nothing was deleted, so a re-LIST would return the same
                    # batch forever. One pass is all dry-run can do.
                    LOG.info("dry-run: drained one batch of %d files; stopping", len(files))
                    return
        finally:
            # Don't let a cleanup call mask the real failure: if the pendant
            # dropped the link (e.g. its storage worker jammed in an allocator
            # traversal and starved the BLE stack), stop_notify raises
            # "Not connected" and hides the underlying disconnect.
            try:
                if client.is_connected:
                    await client.stop_notify(uuids.STORAGE_WRITE_CHAR)
            except Exception as exc:
                LOG.info("stop_notify skipped (link already down): %s", exc)
            try:
                if client.is_connected:
                    await client.disconnect()
            except Exception:
                pass

    async def _list_files_resilient(
        self, client: BleakClient, connect_target: BLEDevice | str
    ) -> tuple[list[StorageFile] | None, BleakClient]:
        for attempt in range(1, MAX_RECONNECT_ATTEMPTS + 1):
            try:
                return await self.list_files(client), client
            except BleakError as exc:
                LOG.warning(
                    "BLE disconnected during LIST (attempt %d/%d): %s", attempt, MAX_RECONNECT_ATTEMPTS, exc
                )
                try:
                    client = await self._reconnect(client, connect_target)
                except Exception as reconnect_exc:
                    LOG.warning("reconnect attempt %d failed: %s", attempt, reconnect_exc)
                    await asyncio.sleep(RECONNECT_BACKOFF_S)
        return None, client

    async def _read_file_resilient(
        self,
        client: BleakClient,
        connect_target: BLEDevice | str,
        target: StorageFile,
        read_index: int,
    ) -> tuple[bytes | None, BleakClient]:
        for attempt in range(1, MAX_RECONNECT_ATTEMPTS + 1):
            try:
                return await self.read_file(client, target, index=read_index), client
            except BleakError as exc:
                LOG.warning(
                    "BLE disconnected mid-read (index=%s attempt=%d/%d): %s",
                    read_index, attempt, MAX_RECONNECT_ATTEMPTS, exc,
                )
                try:
                    client = await self._reconnect(client, connect_target)
                except Exception as reconnect_exc:
                    LOG.warning("reconnect attempt %d failed: %s", attempt, reconnect_exc)
                    await asyncio.sleep(RECONNECT_BACKOFF_S)
        return None, client

    async def _delete_file_resilient(
        self, client: BleakClient, connect_target: BLEDevice | str, target: StorageFile
    ) -> BleakClient:
        for attempt in range(1, MAX_RECONNECT_ATTEMPTS + 1):
            try:
                await self.delete_file(client, target, index=0)
                return client
            except BleakError as exc:
                LOG.warning(
                    "BLE disconnected mid-delete (key=%s attempt=%d/%d): %s",
                    target.key, attempt, MAX_RECONNECT_ATTEMPTS, exc,
                )
                try:
                    client = await self._reconnect(client, connect_target)
                except Exception as reconnect_exc:
                    LOG.warning("reconnect attempt %d failed: %s", attempt, reconnect_exc)
                    await asyncio.sleep(RECONNECT_BACKOFF_S)
        LOG.warning(
            "could not confirm delete of key=%s after repeated disconnects; "
            "it will be re-read (and safely re-uploaded, since upload already succeeded) next cycle",
            target.key,
        )
        return client

    async def _resolve_device(self) -> BLEDevice | str:
        return await self.scan_first(self.address)

    async def _best_effort_setup(self, client: BleakClient) -> None:
        try:
            await client.write_gatt_char(uuids.SETTINGS_MIC_GAIN_CHAR, bytes([8]), response=True)
            LOG.info("mic gain requested level=8")
        except Exception as exc:
            LOG.info("mic gain skipped: %s", exc)
        try:
            codec = await client.read_gatt_char(uuids.AUDIO_CODEC_CHAR)
            LOG.info("codec raw=%s", bytes(codec).hex())
        except Exception as exc:
            LOG.info("codec read skipped: %s", exc)

    async def inspect_services(self) -> None:
        device = await self._resolve_device()
        connect_target = self._connect_target(device)
        device_label = device.address if isinstance(device, BLEDevice) else device
        device_name = device.name if isinstance(device, BLEDevice) else "Omi"
        LOG.info("connecting for service dump address=%s name=%s", device_label, device_name)
        async with BleakClient(
            connect_target,
            timeout=self.connect_timeout_s,
            services=CONNECT_SERVICES,
            winrt=WINRT_ARGS,
        ) as client:
            if not client.is_connected:
                raise RuntimeError("BLE connection failed")
            await self._best_effort_setup(client)
            self._log_services(client)
            if self._has_characteristic(client, uuids.STORAGE_WRITE_CHAR):
                LOG.info("storage write/notify characteristic is present")
            else:
                LOG.warning("storage write/notify characteristic is missing")

    async def _ensure_storage_available(self, client: BleakClient) -> None:
        if self._has_characteristic(client, uuids.STORAGE_WRITE_CHAR):
            return
        self._log_services(client)
        raise StorageProtocolError(
            f"storage characteristic {uuids.STORAGE_WRITE_CHAR} was not found on connected device"
        )

    def _connect_target(self, device: BLEDevice | str) -> BLEDevice | str:
        if isinstance(device, BLEDevice) and platform.system() == "Windows":
            return device.address
        return device

    def _has_characteristic(self, client: BleakClient, uuid: str) -> bool:
        wanted = uuid.lower()
        for service in client.services:
            for char in service.characteristics:
                if str(char.uuid).lower() == wanted:
                    return True
        return False

    def _log_services(self, client: BleakClient) -> None:
        for service in client.services:
            LOG.info("service %s", service.uuid)
            for char in service.characteristics:
                props = ",".join(char.properties)
                LOG.info("  char %s props=%s", char.uuid, props)

    def _on_storage_notify(self, _sender: object, data: bytearray) -> None:
        self._notify_queue.put_nowait(bytes(data))

    async def _write_storage_command(self, client: BleakClient, data: bytes) -> None:
        await client.write_gatt_char(uuids.STORAGE_WRITE_CHAR, data, response=True)

    async def _next_notify(self, timeout_s: float) -> bytes:
        return await asyncio.wait_for(self._notify_queue.get(), timeout=timeout_s)

    async def list_files(self, client: BleakClient) -> list[StorageFile]:
        # The pendant returns STORAGE_NOT_READY(9) while its SD/LittleFS is still
        # coming up after boot/wake — the firmware maps -EBUSY/-EAGAIN/-ETIMEDOUT
        # to code 9 (storage.c storage_status_from_error) while the boot file-cache
        # scan is running. That is transient, so re-issue LIST with backoff until
        # the SD is ready instead of treating it as a fatal error.
        deadline = time.monotonic() + self.storage_ready_timeout_s
        attempt = 0
        while True:
            self._drain_notifications()
            await self._write_storage_command(client, bytes([CMD_LIST_FILES]))
            not_ready = False
            stalled = False
            while True:
                # Unlike read_file's inner loop, this used to have no
                # try/except around the notify-wait at all - a plain stall
                # (no NOT_READY status, just no response) raised an uncaught
                # asyncio.TimeoutError straight out of sync(), identical
                # end result to the BLE-disconnect bug (whole run dies,
                # rc=1) but via a different path. Live-observed 2026-07-06.
                try:
                    data = await self._next_notify(self.stall_timeout_s)
                except asyncio.TimeoutError:
                    stalled = True
                    break
                if not data:
                    continue
                if len(data) == 1:
                    status = data[0]
                    if status == STORAGE_OK:
                        # LIST returns exactly one notification (the command acks
                        # with 0xFF, not 0 — storage.c parse_storage_command), so a
                        # lone 0 is the firmware's empty-storage response, not an
                        # ack to keep waiting on. Storage is drained → done.
                        return []
                    if status == STORAGE_NOT_READY:
                        not_ready = True
                        break
                    raise StorageProtocolError(f"list status {status_label(status)}")
                count = data[0]
                files: list[StorageFile] = []
                offset = 1
                for index in range(count):
                    if offset + 8 > len(data):
                        break
                    timestamp = int.from_bytes(data[offset:offset + 4], "big")
                    size = int.from_bytes(data[offset + 4:offset + 8], "big")
                    files.append(StorageFile(index=index, timestamp=timestamp, size=size))
                    offset += 8
                return estimate_timestamps(files)

            if not_ready or stalled:
                if time.monotonic() >= deadline:
                    reason = "storage not ready" if not_ready else "LIST stalled (no response)"
                    raise StorageProtocolError(
                        f"{reason} after {self.storage_ready_timeout_s:.0f}s "
                        "(SD still initializing / firmware unresponsive — retry once the pendant has settled)"
                    )
                attempt += 1
                wait = min(3.0, 1.0 + 0.5 * attempt)
                LOG.info(
                    "LIST %s; retrying in %.1fs (attempt %d)",
                    "not ready (SD initializing)" if not_ready else "stalled (no notification)",
                    wait,
                    attempt,
                )
                await asyncio.sleep(wait)

    async def read_file(self, client: BleakClient, file: StorageFile, *, index: int | None = None) -> bytes:
        read_index = file.index if index is None else index
        # READ resumes from the current byte offset across retries. The firmware's
        # single SD worker can return STORAGE_NOT_READY(9) or briefly stall mid-file
        # when it gets busy (a metadata/allocator write), and BLE can hiccup. Rather
        # than re-reading the whole file, we re-issue READ from len(buf): the firmware
        # seeks there (setup_file_transfer start_offset) and streams the remaining
        # bytes, so partial progress is never thrown away. The long NOT_READY backoff
        # clears the worker's read_in_flight latch (sd_card.c:1837) before retrying.
        deadline = time.monotonic() + self.storage_ready_timeout_s
        buf = bytearray()
        last_log = 0
        started = time.monotonic()
        ready_attempt = 0
        stall_attempt = 0
        LOG.info("reading index=%s ts=%s size=%s", read_index, file.timestamp, file.size)
        while True:
            self._drain_notifications()
            cmd = bytes([CMD_READ_FILE, read_index]) + len(buf).to_bytes(4, "big")
            await self._write_storage_command(client, cmd)
            pause = 0.0  # seconds to wait before re-issuing READ from the current offset
            while True:
                try:
                    data = await self._next_notify(self.stall_timeout_s)
                except asyncio.TimeoutError as exc:
                    if buf and stall_attempt < 5:
                        stall_attempt += 1
                        LOG.info(
                            "read stalled at %s/%s bytes; resuming from offset (attempt %d)",
                            len(buf), file.size, stall_attempt,
                        )
                        pause = 2.0
                        break
                    raise StorageProtocolError(
                        f"read stalled index={read_index} bytes={len(buf)}/{file.size}"
                    ) from exc

                if len(data) == 1:
                    status = data[0]
                    if status == STORAGE_OK:
                        continue
                    if status == STORAGE_DONE:
                        elapsed = max(time.monotonic() - started, 0.001)
                        LOG.info(
                            "read complete index=%s bytes=%s/%s rate=%.1f KB/s",
                            read_index,
                            len(buf),
                            file.size,
                            (len(buf) / 1024.0) / elapsed,
                        )
                        return bytes(buf)
                    if status == STORAGE_NOT_READY:
                        ready_attempt += 1
                        pause = min(45.0, 15.0 + 5.0 * ready_attempt)
                        LOG.info(
                            "read not ready (SD busy) at %s/%s; retrying from offset in %.0fs (attempt %d)",
                            len(buf), file.size, pause, ready_attempt,
                        )
                        break
                    raise StorageProtocolError(f"read status {status_label(status)} index={read_index}")

                if len(data) <= STORAGE_TIMESTAMP_BYTES:
                    continue
                buf.extend(data[STORAGE_TIMESTAMP_BYTES:])
                stall_attempt = 0  # made progress → reset the consecutive-stall counter
                if len(buf) - last_log >= PROGRESS_LOG_BYTES or len(buf) >= file.size:
                    last_log = len(buf)
                    LOG.info("read progress index=%s bytes=%s/%s", read_index, len(buf), file.size)

            if time.monotonic() >= deadline:
                raise StorageProtocolError(
                    f"read did not complete index={read_index} bytes={len(buf)}/{file.size} "
                    f"after {self.storage_ready_timeout_s:.0f}s"
                )
            if pause:
                await asyncio.sleep(pause)

    async def delete_file(self, client: BleakClient, file: StorageFile, *, index: int | None = None) -> None:
        del_index = file.index if index is None else index
        # DELETE does a metadata write (lfs_remove + cache refresh) that can hit the
        # transient allocator-busy state, same as LIST/READ — retry NOT_READY(9).
        deadline = time.monotonic() + self.storage_ready_timeout_s
        attempt = 0
        while True:
            self._drain_notifications()
            await self._write_storage_command(client, bytes([CMD_DELETE_FILE, del_index]))
            not_ready = False
            stalled = False
            while True:
                try:
                    data = await self._next_notify(self.stall_timeout_s)
                except asyncio.TimeoutError:
                    stalled = True
                    break
                if len(data) != 1:
                    continue
                status = data[0]
                if status in (STORAGE_OK, FILE_NOT_FOUND):
                    LOG.info("deleted index=%s status=%s", del_index, status_label(status))
                    return
                if status == STORAGE_NOT_READY:
                    not_ready = True
                    break
                raise StorageProtocolError(f"delete status {status_label(status)} index={del_index}")
            if not_ready or stalled:
                if time.monotonic() >= deadline:
                    reason = "storage not ready" if not_ready else "DELETE stalled (no response)"
                    raise StorageProtocolError(
                        f"{reason} for delete index={del_index} after "
                        f"{self.storage_ready_timeout_s:.0f}s"
                    )
                attempt += 1
                wait = min(10.0, 2.0 + 1.0 * attempt)
                LOG.info(
                    "DELETE %s; retrying in %.0fs (attempt %d)",
                    "not ready (SD busy)" if not_ready else "stalled (no notification)",
                    wait, attempt,
                )
                await asyncio.sleep(wait)

    def build_storage_frames(self, data: bytes) -> list[bytes]:
        frames: list[bytes] = []
        block_start = 0
        while block_start < len(data):
            block_end = min(block_start + STORAGE_BLOCK_BYTES, len(data))
            usable_end = min(block_start + STORAGE_BLOCK_USABLE_BYTES, block_end)
            offset = block_start
            while offset < usable_end:
                payload_len = data[offset]
                if payload_len == 0:
                    break
                packet_end = offset + 1 + payload_len
                if payload_len > MAX_STORAGE_OPUS_PAYLOAD or packet_end > usable_end:
                    break
                seq = self._seq & 0xFFFF
                frame = bytes([seq & 0xFF, (seq >> 8) & 0xFF, 0]) + data[offset + 1:packet_end]
                frames.append(frame)
                self._seq += 1
                offset = packet_end
            block_start += STORAGE_BLOCK_BYTES
        return frames

    def _drain_notifications(self) -> None:
        while True:
            try:
                self._notify_queue.get_nowait()
            except asyncio.QueueEmpty:
                return


def estimate_timestamps(files: list[StorageFile]) -> list[StorageFile]:
    result = list(files)
    now_ms = int(time.time() * 1000)
    i = len(result) - 1
    while i >= 0:
        if not is_reasonable_timestamp(result[i].timestamp):
            anchor_ms = now_ms
            anchor = i + 1
            while anchor < len(result):
                if is_reasonable_timestamp(result[anchor].timestamp):
                    anchor_ms = result[anchor].timestamp * 1000
                    break
                anchor += 1
            current_end = anchor_ms
            j = anchor - 1
            while j >= i:
                f = result[j]
                if not is_reasonable_timestamp(f.timestamp):
                    duration_ms = (f.size * 20) // 90
                    estimated_start = current_end - duration_ms
                    result[j] = StorageFile(f.index, estimated_start // 1000, f.size)
                    current_end = estimated_start - 1000
                    j -= 1
                    continue
                break
            i = j
        else:
            i -= 1
    return result


def is_reasonable_timestamp(timestamp: int) -> bool:
    return 1_500_000_000 <= timestamp <= 4_102_444_800


def status_label(status: int) -> str:
    return {
        STORAGE_OK: "OK(0)",
        INVALID_COMMAND: "INVALID_COMMAND(6)",
        FILE_NOT_FOUND: "FILE_NOT_FOUND(7)",
        FILE_INDEX_OUT_OF_RANGE: "FILE_INDEX_OUT_OF_RANGE(8)",
        STORAGE_NOT_READY: "STORAGE_NOT_READY(9)",
        STORAGE_DONE: "DONE(100)",
    }.get(status, f"UNKNOWN({status})")
