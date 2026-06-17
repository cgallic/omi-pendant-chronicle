# Omi Pendant Chronicle

Open-source companion code for running an [Omi](https://github.com/BasedHardware/omi)
pendant as a self-hosted "second brain" — **without** the Omi cloud.

The pendant captures audio over BLE. Instead of streaming to Omi's hosted
backend, both clients in this repo talk directly to a backend *you* run: it
ingests raw Opus frames, transcribes, builds memories/action items, and exposes
a dashboard API. This repo is the **capture half** — two clients that get bytes
off the pendant and into your own pipeline. The backend is yours (see
[Backend](#backend-not-included)).

```
┌──────────────┐   BLE    ┌──────────────────────┐   HTTP     ┌──────────────────────┐
│  Omi pendant │ ───────► │  capture client       │ ────────► │  self-hosted backend  │
│  (Opus over  │  frames  │  • android-app        │  /raw      │  (raw sink :8773,     │
│   GATT)      │          │  • desktop-collector  │  /raw-batch│   API :8772, ws :8774)│
└──────────────┘          └──────────────────────┘            └──────────────────────┘
```

---

## Why this exists (dev → dev)

The Omi hardware is great and the official app is fine. But the official app is
built around **Omi's cloud**: your audio goes to their backend, your memories
live in their database, and the app is the front door to *their* pipeline. If
you want to own the whole loop — run your own transcription, your own LLM, your
own memory store, your own retention policy — the official app fights you,
because the cloud is load-bearing.

We wanted the opposite default: **the pendant is a sensor, and everything after
the radio is mine.** Capture the raw frames, ship them to a box I control, and
do whatever I want downstream. No vendor account in the hot path, no "trust us
with your always-on mic," no waiting on someone else's roadmap to add an
endpoint.

That goal drove three concrete decisions:

1. **Talk to the pendant directly over BLE.** Subscribe to the GATT
   characteristics, drain frames, and POST them somewhere *we* choose. The cloud
   is never in the loop.
2. **Treat raw frames as the source of truth.** We upload Omi-framed Opus
   (3-byte header + payload, no Ogg wrap) exactly as the radio emits it, and
   keep a local copy. Transcription/memory is a *downstream* concern, not
   something baked into capture.
3. **Make capture resilient and boring.** A phone in your pocket and a desktop
   on your desk should both be able to reliably get bytes off the pendant —
   including the pendant's **offline storage** — without babysitting.

If you've ever wanted to point an Omi pendant at your own backend, this is the
plumbing.

## How it's different from the official Omi app

| | Official Omi app | This project |
|---|---|---|
| **Backend** | Omi cloud | Your self-hosted backend (`:8772/:8773/:8774`) |
| **Where audio goes** | Omi's servers | A box on your LAN/tailnet that you own |
| **Frame format on the wire** | Handled internally | You receive **raw Omi-framed Opus** at `/raw` and own decode/transcribe |
| **Offline storage drain** | App-managed | Explicit, scriptable: the desktop CLI lists → reads → uploads → deletes one file at a time |
| **On-device buffer** | — | Android keeps a **3-day hot ring buffer** of raw frames on the phone, independent of upload |
| **Power model** | Always-on | Android defaults to **low-power push-to-sync**; live streaming is an explicit mode (`Start Live`) with a wake lock |
| **Desktop capture** | — | A standalone Python CLI (`scan` / `sync` / `watch`) for always-nearby computers |
| **UI** | Omi's product | Native programmatic Android cockpit bound directly to *your* backend's `/pendant/api/v3/*` schema (memories, action items, approvals, health, translation, chat) |
| **Secrets** | Vendor account | A single shared `X-Pendant-Secret` you generate and control |
| **License / control** | Vendor terms | MIT, fork it, change anything |

The short version: the official app is a **product** with the cloud built in.
This is **infrastructure** — the minimum honest plumbing to make an Omi pendant
feed a backend you control, plus a real native client that proves the loop end
to end.

## What's not different

We didn't reinvent the hardware or the wire format. BLE GATT UUIDs and the Opus
framing follow [Omi](https://github.com/BasedHardware/omi)'s format — that's
deliberate, so this stays compatible with real Omi pendants and you can read
their firmware to understand the bytes. The Android app's BLE/Doze hardening
started life as patches against a fork of the Omi Android app.

---

## What's in here

| Directory | What it is |
|---|---|
| [`android-app/`](android-app/) | Native Kotlin Android app ("Chronicle"). Pairs with the pendant over BLE, keeps a 3-day on-phone ring buffer of raw frames, uploads to the backend, and renders memories / action items / approvals / health / translation / chat as a native cockpit. Not a WebView — programmatic Material views. |
| [`desktop-collector/`](desktop-collector/) | Cross-platform Python CLI that drains the pendant's **offline storage** over BLE and uploads it to the same backend. The safe building block for running on nearby computers as a scheduled collector. |

Both clients speak the same contract: raw Omi-framed Opus frames posted to the
backend's `/raw` / `/raw-batch` sink with an `X-Pendant-Secret` header.

## Quick start

**Android app** — needs an Android-toolchain build host:

```bash
cp android-app/local.properties.example android-app/local.properties
# edit agent.url / audio.out.url / pendant.secret, then build (see android-app/README.md)
```

**Desktop collector** — Python 3.10+:

```bash
cd desktop-collector
python -m venv .venv && .venv/bin/pip install -e .
export PENDANT_AGENT_URL="http://YOUR_AGENT_HOST:8773/raw"
export PENDANT_SECRET="your-32-hex-shared-secret"
pendant-collector scan          # find the pendant
pendant-collector watch         # drain offline storage on an interval
```

## Configuration & secrets

Nothing in this repo ships real credentials. Every client reads its backend host
and shared secret from local config you provide:

- **android-app** → copy `android-app/local.properties.example` to
  `android-app/local.properties` (gitignored) and fill in `agent.url`,
  `audio.out.url`, and `pendant.secret`.
- **desktop-collector** → set `PENDANT_AGENT_URL` and `PENDANT_SECRET`, or pass
  `--agent-url` / `--secret`.

`pendant.secret` / `PENDANT_SECRET` is a shared secret (e.g. `openssl rand -hex 16`)
that must match what your backend's raw sink validates.

## Backend (not included)

This repo is the capture half. The backend that ingests frames, transcribes, and
serves the dashboard API is self-hosted and intentionally **not** part of this
repo — it's the part everyone will want to do differently. The clients only
assume:

- `POST /raw` and `POST /raw-batch` accept Omi-framed Opus frames + an
  `X-Pendant-Secret` header (raw ingest, port 8773).
- A dashboard/API on port 8772 exposing the `/pendant/api/v3/*` endpoints the
  Android app reads (see [`android-app/README.md`](android-app/README.md) for the
  full endpoint table and schema notes).
- An audio-out WebSocket on port 8774 for live/translate mode.

Build that however you like — any framework, any transcription, any LLM, any
store. The contract above is the only thing the clients care about.

## Relationship to Omi

The pendant hardware and firmware are [Omi](https://github.com/BasedHardware/omi)
(MIT). This project is independent companion software that drives an Omi pendant
against a self-hosted backend. It is not affiliated with or endorsed by Omi /
Based Hardware.

## License

MIT — see [LICENSE](LICENSE). Fork it, change anything.
