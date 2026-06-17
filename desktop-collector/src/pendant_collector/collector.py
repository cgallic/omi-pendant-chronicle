from __future__ import annotations

import asyncio
import logging
import platform
import time
from dataclasses import dataclass

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

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
        dry_run_delete: bool = False,
    ) -> None:
        self.agent_url = agent_url
        self.secret = secret
        self.address = address
        self.scan_timeout_s = scan_timeout_s
        self.connect_timeout_s = connect_timeout_s
        self.stall_timeout_s = stall_timeout_s
        self.dry_run_delete = dry_run_delete
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

    async def sync(self, *, once: bool = False, max_files: int | None = None) -> None:
        device = await self._resolve_device()
        connect_target = self._connect_target(device)
        device_label = device.address if isinstance(device, BLEDevice) else device
        device_name = device.name if isinstance(device, BLEDevice) else "Omi"
        LOG.info("connecting address=%s name=%s", device_label, device_name)
        async with BleakClient(
            connect_target,
            timeout=self.connect_timeout_s,
            services=CONNECT_SERVICES,
            winrt=WINRT_ARGS,
        ) as client:
            if not client.is_connected:
                raise RuntimeError("BLE connection failed")

            await self._best_effort_setup(client)
            await self._ensure_storage_available(client)
            await client.start_notify(uuids.STORAGE_WRITE_CHAR, self._on_storage_notify)
            try:
                synced = 0
                while max_files is None or synced < max_files:
                    files = await self.list_files(client)
                    if not files:
                        LOG.info("storage empty; sync complete")
                        return

                    LOG.info("listed %d files; first size=%s ts=%s", len(files), files[0].size, files[0].timestamp)
                    target = files[0]
                    raw_bytes = await self.read_file(client, target)
                    frames = self.build_storage_frames(raw_bytes)
                    if not frames and target.size > 0:
                        raise StorageProtocolError(f"file produced no frames index={target.index} size={target.size}")

                    LOG.info("file safe index=%s size=%s frames=%s", target.index, target.size, len(frames))
                    result = await self.uploader.post_frames(frames, started_at_ms=target.started_at_ms)
                    if not result.ok:
                        raise StorageProtocolError(
                            f"upload failed after {result.frames} frames status={result.status_code} error={result.error}"
                        )

                    LOG.info("uploaded index=%s frames=%s; deleting pendant file", target.index, result.frames)
                    if self.dry_run_delete:
                        LOG.warning("dry-run delete: leaving pendant file index=%s in place", target.index)
                    else:
                        await self.delete_file(client, target)
                    synced += 1
                    if once:
                        return
            finally:
                await client.stop_notify(uuids.STORAGE_WRITE_CHAR)

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
        self._drain_notifications()
        await self._write_storage_command(client, bytes([CMD_LIST_FILES]))
        while True:
            data = await self._next_notify(self.stall_timeout_s)
            if not data:
                continue
            if len(data) == 1:
                status = data[0]
                if status == STORAGE_OK:
                    continue
                if status == 0:
                    return []
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

    async def read_file(self, client: BleakClient, file: StorageFile) -> bytes:
        self._drain_notifications()
        cmd = bytes([CMD_READ_FILE, file.index, 0, 0, 0, 0])
        await self._write_storage_command(client, cmd)

        buf = bytearray()
        last_log = 0
        started = time.monotonic()
        LOG.info("reading index=%s ts=%s size=%s", file.index, file.timestamp, file.size)
        while True:
            try:
                data = await self._next_notify(self.stall_timeout_s)
            except asyncio.TimeoutError as exc:
                raise StorageProtocolError(
                    f"read stalled index={file.index} bytes={len(buf)}/{file.size}"
                ) from exc

            if len(data) == 1:
                status = data[0]
                if status == STORAGE_OK:
                    continue
                if status == STORAGE_DONE:
                    elapsed = max(time.monotonic() - started, 0.001)
                    LOG.info(
                        "read complete index=%s bytes=%s/%s rate=%.1f KB/s",
                        file.index,
                        len(buf),
                        file.size,
                        (len(buf) / 1024.0) / elapsed,
                    )
                    return bytes(buf)
                raise StorageProtocolError(f"read status {status_label(status)} index={file.index}")

            if len(data) <= STORAGE_TIMESTAMP_BYTES:
                continue
            buf.extend(data[STORAGE_TIMESTAMP_BYTES:])
            if len(buf) - last_log >= PROGRESS_LOG_BYTES or len(buf) >= file.size:
                last_log = len(buf)
                LOG.info("read progress index=%s bytes=%s/%s", file.index, len(buf), file.size)

    async def delete_file(self, client: BleakClient, file: StorageFile) -> None:
        self._drain_notifications()
        await self._write_storage_command(client, bytes([CMD_DELETE_FILE, file.index]))
        while True:
            data = await self._next_notify(self.stall_timeout_s)
            if len(data) != 1:
                continue
            status = data[0]
            if status in (STORAGE_OK, FILE_NOT_FOUND):
                LOG.info("deleted index=%s status=%s", file.index, status_label(status))
                return
            raise StorageProtocolError(f"delete status {status_label(status)} index={file.index}")

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
