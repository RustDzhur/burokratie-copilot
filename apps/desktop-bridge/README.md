# Desktop Bridge

A workstation with a scanner collects work from Bürokratie-Copilot and returns the scans (spec §8.2).

**Why this program exists.** A browser cannot talk to a scanner. That is not a gap in the platform — no browser
can — so the part that touches the device is this small program and the platform exposes the receiving end.

## What it does

1. asks the platform for the next queued scan job (`POST /api/v1/device-agent/claim`);
2. runs `scanimage` (SANE) for that job;
3. uploads the result (`POST /api/v1/device-agent/jobs/{id}/result`), or reports why there is none.

**When there is no scanner, the diagnosis is the deliverable.** "Scanning failed" tells the person standing at
the machine nothing. Every message names what is missing, how it was looked for, and what to do next.

## Install

```bash
cd apps/desktop-bridge
npm install          # no dependencies; this only validates the package
```

Requires Node 20+ and, for scanning, SANE (`scanimage`): `brew install sane-backends` on macOS,
`apt install sane-utils` on Debian/Ubuntu.

## Configure

| Variable | Meaning |
|---|---|
| `BC_API_URL` | Base address of the platform, e.g. `https://app.example.com` |
| `BC_DEVICE_KEY` | The key shown once when the device was registered (Devices screen) |

The key is a credential. It is read from the environment rather than from a file in the repository, and it is
never written to the log.

## Use

```bash
export BC_API_URL=https://app.example.com
export BC_DEVICE_KEY=…
node bridge.mjs diagnose    # says whether this machine can scan, and what to do if not
node bridge.mjs run         # polls for work and returns scans until stopped
node bridge.mjs once        # a single cycle, for a cron job or a scheduled task
```

## What is not implemented, and why

**TWAIN and WIA are not implemented and are named as such.** They need a Windows host and a driver model this
program does not have. On Windows, point the scanner's own software at a folder and use the folder import; a
menu entry that fails would be worse than saying so.

**The scan is uploaded whole, not streamed.** A sheet is a few megabytes; streaming would add a failure mode
without making anything faster.
