from __future__ import annotations

import asyncio
import struct
from dataclasses import dataclass
from typing import Iterable

import httpx


@dataclass
class UploadResult:
    ok: bool
    frames: int
    status_code: int | None = None
    error: str | None = None


class AgentUploader:
    def __init__(self, agent_url: str, secret: str, timeout_s: float = 10.0) -> None:
        self.raw_url = agent_url.rstrip("/")
        self.batch_url = (
            self.raw_url[:-4] + "/raw-batch"
            if self.raw_url.endswith("/raw")
            else self.raw_url.rstrip("/") + "/raw-batch"
        )
        self.secret = secret
        self.timeout_s = timeout_s

    async def post_frames(
        self,
        frames: list[bytes],
        *,
        started_at_ms: int | None,
        max_batch_frames: int = 128,
        max_batch_bytes: int = 256 * 1024,
    ) -> UploadResult:
        if not frames:
            return UploadResult(ok=True, frames=0)

        async with httpx.AsyncClient(timeout=self.timeout_s) as client:
            sent = 0
            batch: list[bytes] = []
            batch_bytes = 0
            for frame in frames:
                would_overflow = batch and (
                    len(batch) >= max_batch_frames
                    or batch_bytes + len(frame) > max_batch_bytes
                )
                if would_overflow:
                    ok, status, error = await self._post_batch(client, batch, started_at_ms)
                    if not ok:
                        return UploadResult(False, sent, status, error)
                    sent += len(batch)
                    batch = []
                    batch_bytes = 0

                batch.append(frame)
                batch_bytes += len(frame)

            if batch:
                ok, status, error = await self._post_batch(client, batch, started_at_ms)
                if not ok:
                    return UploadResult(False, sent, status, error)
                sent += len(batch)

        return UploadResult(ok=True, frames=sent)

    async def _post_batch(
        self,
        client: httpx.AsyncClient,
        frames: list[bytes],
        started_at_ms: int | None,
    ) -> tuple[bool, int | None, str | None]:
        headers = {
            "X-Pendant-Secret": self.secret,
            "X-Pendant-Batch": "length-prefixed-v1",
            "Content-Type": "application/octet-stream",
        }
        if started_at_ms is not None:
            headers["X-Pendant-Session-Start"] = str(started_at_ms)

        body = encode_batch(frames)
        last_exc: Exception | None = None
        for attempt in range(3):
            try:
                resp = await client.post(self.batch_url, headers=headers, content=body)
            except (httpx.RemoteProtocolError, httpx.ConnectError, httpx.ReadError) as exc:
                # Pooled keep-alive connection can go stale between slow BLE-paced
                # batches; a fresh attempt clears it without losing the batch.
                last_exc = exc
                await asyncio.sleep(0.5 * (attempt + 1))
                continue
            except Exception as exc:
                return False, None, f"{type(exc).__name__}: {exc}"
            if 200 <= resp.status_code < 300:
                return True, resp.status_code, None
            return False, resp.status_code, resp.text[:300]
        return False, None, f"{type(last_exc).__name__}: {last_exc}"


def encode_batch(frames: Iterable[bytes]) -> bytes:
    frames = list(frames)
    parts = [struct.pack(">I", len(frames))]
    for frame in frames:
        parts.append(struct.pack(">I", len(frame)))
        parts.append(frame)
    return b"".join(parts)

