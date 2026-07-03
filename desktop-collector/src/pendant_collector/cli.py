from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys

from .collector import OmiDesktopCollector


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="pendant-collector")
    parser.add_argument("--log-level", default=os.environ.get("PENDANT_COLLECTOR_LOG", "INFO"))
    sub = parser.add_subparsers(dest="cmd", required=True)

    scan = sub.add_parser("scan", help="scan for Omi/Friend pendants")
    scan.add_argument("--timeout", type=float, default=20.0)

    services = sub.add_parser("services", help="connect and dump GATT services/characteristics")
    services.add_argument("--address", default=os.environ.get("PENDANT_ADDRESS"))
    services.add_argument("--scan-timeout", type=float, default=20.0)
    services.add_argument("--connect-timeout", type=float, default=45.0)

    sync = sub.add_parser("sync", help="sync pendant offline storage")
    sync.add_argument("--agent-url", default=os.environ.get("PENDANT_AGENT_URL", "http://127.0.0.1:8773/raw"))
    sync.add_argument("--secret", default=os.environ.get("PENDANT_SECRET"))
    sync.add_argument("--address", default=os.environ.get("PENDANT_ADDRESS"))
    sync.add_argument("--scan-timeout", type=float, default=20.0)
    sync.add_argument("--connect-timeout", type=float, default=45.0)
    sync.add_argument("--stall-timeout", type=float, default=30.0)
    sync.add_argument("--storage-ready-timeout", type=float, default=90.0,
                      help="how long to wait for the SD/LittleFS boot scan (large backlog needs more)")
    sync.add_argument("--once", action="store_true", help="sync one file then exit")
    sync.add_argument("--max-files", type=int, default=None)
    sync.add_argument("--dry-run-delete", action="store_true", help="upload but do not delete pendant files")
    sync.add_argument("--lease-holder", default=os.environ.get("PENDANT_LEASE_HOLDER"),
                      help="drain-lease holder id (default: desktop-<hostname>)")
    sync.add_argument("--no-lease", action="store_true",
                      help="skip the server-side drain coordination lease")

    watch = sub.add_parser("watch", help="periodically scan and sync when the pendant is nearby")
    watch.add_argument("--agent-url", default=os.environ.get("PENDANT_AGENT_URL", "http://127.0.0.1:8773/raw"))
    watch.add_argument("--secret", default=os.environ.get("PENDANT_SECRET"))
    watch.add_argument("--address", default=os.environ.get("PENDANT_ADDRESS"))
    watch.add_argument("--scan-timeout", type=float, default=20.0)
    watch.add_argument("--connect-timeout", type=float, default=45.0)
    watch.add_argument("--stall-timeout", type=float, default=30.0)
    watch.add_argument("--interval", type=float, default=120.0)
    watch.add_argument("--max-files-per-pass", type=int, default=2)
    watch.add_argument("--dry-run-delete", action="store_true")
    return parser


async def async_main(argv: list[str]) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=getattr(logging, str(args.log_level).upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    if args.cmd == "scan":
        collector = OmiDesktopCollector(agent_url="http://127.0.0.1/raw", secret="unused", scan_timeout_s=args.timeout)
        devices = await collector.scan()
        if not devices:
            print("No Omi/Friend pendants found")
            return 1
        for device in devices:
            print(f"{device.address}\t{device.name or ''}")
        return 0

    if args.cmd == "services":
        collector = OmiDesktopCollector(
            agent_url="http://127.0.0.1/raw",
            secret="unused",
            address=args.address,
            scan_timeout_s=args.scan_timeout,
            connect_timeout_s=args.connect_timeout,
        )
        await collector.inspect_services()
        return 0

    if args.cmd == "sync":
        if not args.secret:
            parser.error("--secret or PENDANT_SECRET is required for sync")
        collector = OmiDesktopCollector(
            agent_url=args.agent_url,
            secret=args.secret,
            address=args.address,
            scan_timeout_s=args.scan_timeout,
            connect_timeout_s=args.connect_timeout,
            stall_timeout_s=args.stall_timeout,
            storage_ready_timeout_s=args.storage_ready_timeout,
            dry_run_delete=args.dry_run_delete,
            lease_holder=args.lease_holder,
            use_lease=not args.no_lease,
        )
        await collector.sync(once=args.once, max_files=args.max_files)
        return 0

    if args.cmd == "watch":
        if not args.secret:
            parser.error("--secret or PENDANT_SECRET is required for watch")
        log = logging.getLogger("pendant.watch")
        while True:
            try:
                collector = OmiDesktopCollector(
                    agent_url=args.agent_url,
                    secret=args.secret,
                    address=args.address,
                    scan_timeout_s=args.scan_timeout,
                    connect_timeout_s=args.connect_timeout,
                    stall_timeout_s=args.stall_timeout,
                    dry_run_delete=args.dry_run_delete,
                )
                await collector.sync(max_files=args.max_files_per_pass)
            except Exception as exc:
                log.info("watch pass ended: %r", exc)
            await asyncio.sleep(args.interval)

    parser.error(f"unknown command {args.cmd}")
    return 2


def main() -> None:
    try:
        raise SystemExit(asyncio.run(async_main(sys.argv[1:])))
    except KeyboardInterrupt:
        raise SystemExit(130)


if __name__ == "__main__":
    main()
