# Bridge only

**Languages:** [Русский](../ru/BRIDGE_ONLY.md) · [Deutsch](../de/BRIDGE_ONLY.md) · [English](BRIDGE_ONLY.md)

**Model:** one component runs on the client's machine — the **Firmspace Bridge**. It reads
files, emails, database rows or HTTP responses from the client's existing software and
delivers them to the Firmspace workspace; it can write job results back. The agent engine,
OCR and categories are not part of this delivery. The Bridge opens no inbound ports: it
connects outbound over HTTPS and keeps a local queue.

![Document path from folder to ELSTER](../../images/document-path.svg)

*Figure 2. The Bridge is step 2: it picks the document up at the client and hands it on.
Steps 3–5 run in Runtime or Copilot.*

---

## 1. What this is and who it is for

“Bridge only” fits where a working system already exists — 1C, DATEV, Lexware, SAP B1, a
custom application, a file share, a mailbox or an Oracle/Firebird database — and cannot or
should not be changed. The Bridge maps the data to the shared model “document / invoice /
contact / task” and delivers it to the Firmspace workspace. It does not recognise scans, assign
categories or run agents: it is transport and normalisation. If the expectation after
installation is that “the document sorts itself into categories”, the wrong model was chosen —
see [Robots Runtime](RUNTIME.md) or [Copilot as a service](COPILOT_SAAS.md).

---

## 2. What you need

### 2.1 Machine and software

| Item | Requirement | Check |
|---|---|---|
| OS | Linux x86_64/arm64, macOS or Windows 10/11 with Docker | `uname -a` / `winver` |
| Docker Engine | 24 or newer | `docker version --format '{{.Server.Version}}'` |
| Architecture | `amd64` or `arm64` | `uname -m` |
| RAM | 512 MB plus headroom for files | `docker info` |
| Disk | 2 GB plus queue space | `df -h` |
| Clock | NTP, drift below 5 minutes | `timedatectl status` |
| Network | **outbound** HTTPS 443 to `agents.firmspace.de` | `curl -I https://agents.firmspace.de` |
| Folders | UID/GID 1000 inside the container must read (and write, if needed) | `ls -ln <folder>` |

For the non-Docker variant you also need **Node.js 22 LTS**: `node -v` must show `v22.x`.
RPA and UNC shares require the Windows variant, not a Linux container.

### 2.2 What to prepare in the cabinet

| Item | Provided by | Check |
|---|---|---|
| Bridge token and `bridge.config.json` | robot cabinet | file downloaded, token inside |
| Bridge name | owner | e.g. “Accounting — office” |
| Adapter parameters | client IT | paths, mailbox, read-only database |
| Test files | owner | 2–3 non-confidential documents |

### 2.3 Base URL and token

**The Bridge base URL is the robot cabinet address:** `https://agents.firmspace.de`. The
Bridge connects there outbound over HTTPS; there is no separate “Bridge API” exposed to the
internet.

**The Bridge token** is issued once when the bridge is created:

1. Open `https://agents.firmspace.de` and sign in with the enterprise owner account.
2. Open **Enterprises** and select the enterprise.
3. Open **Connections**.
4. Click **Add bridge**.
5. Enter the name and the non-secret adapter parameters: folder, mail, database, HTTP,
   print, CSV/XML. Do **not** enter passwords or keys in the cabinet; they stay on the
   client's machine.
6. Save and **download** `bridge.config.json` immediately. The token is inside the file and
   is shown only once.
7. If the file is lost: revoke the bridge in the cabinet, create a new one and download the
   new file. The old token cannot be restored.

`[SCREENSHOT]` The “Connections → Add bridge” screen and the `bridge.config.json` download
dialog — capture them in the cabinet version the client actually runs.

> **Do not confuse tokens.** The Bridge token is not a Copilot API token and not a Runtime
> admin token. It works only with the robot cabinet and lives in `.env` as `BRIDGE_TOKEN`.

---

## 3. Installing the Docker variant

### 3.1 Where the image archive comes from

There is no public image registry. The archive is handed over with the delivery: a download
link, a file exchange or physical media. The file name contains the version and architecture,
for example `firmspace-bridge-<version>-amd64.tar.gz` or
`firmspace-bridge-<version>-arm64.tar.gz`. Firmspace provides the exact name and checksum;
do not download images from arbitrary sources.

`[SCREENSHOT]` The archive download page in the cabinet or the email with the link, if that
channel is used.

Verify the file before loading it:

```bash
ls -lh firmspace-bridge-*.tar.gz
shasum -a 256 firmspace-bridge-*.tar.gz      # macOS
sha256sum firmspace-bridge-*.tar.gz          # Linux
```

Compare the checksum with the one in the acceptance report or email. If none is provided,
ask for it.

### 3.2 Determine the architecture

```bash
uname -m
# x86_64        -> amd64
# aarch64/arm64 -> arm64
```

### 3.3 Load the image

```bash
docker load -i firmspace-bridge-<version>-<architecture>.tar.gz
```

Expected output: a line `Loaded image: firmspace-bridge:<version>`.

Confirm the right architecture was loaded:

```bash
docker image inspect firmspace-bridge:0.1.0 \
  --format '{{.Os}}/{{.Architecture}}'
# linux/amd64  or  linux/arm64
```

If the architecture does not match `uname -m`, the image will not start. The file name is
not proof; `docker image inspect` is.

### 3.4 Lay out the files

```bash
sudo mkdir -p /opt/firmspace-bridge/config /opt/firmspace-bridge/data
sudo chown -R 1000:1000 /opt/firmspace-bridge
cd /opt/firmspace-bridge
```

Copy the downloaded `bridge.config.json` into `config/`:

```bash
cp ~/Downloads/bridge.config.json ./config/bridge.config.download.json
ls -l ./config/
```

### 3.5 Import the configuration

The import converts the cabinet file to the Bridge format, moves the token into `.env` and
leaves only `${BRIDGE_TOKEN}` in the configuration:

```bash
docker run --rm -it \
  -v "$PWD/config:/data" \
  firmspace-bridge:0.1.0 \
  import --from /data/bridge.config.download.json \
         --out /data/bridge.config.json \
         --force
```

Expected: an import message and a list of items to clarify manually (SQL query, RPA
scenario, column mapping). Then check:

```bash
ls -l config/bridge.config.json config/.env
chmod 600 config/.env
grep -c 'BRIDGE_TOKEN' config/.env
```

`config/.env` must contain `BRIDGE_TOKEN=...`. `config/bridge.config.json` must not contain a
real token, only `${BRIDGE_TOKEN}`. If a real token is still there, remove it and repeat the
import.

Other secrets (mail password, database, legacy API token) remain placeholders. Fill them in
`config/.env`:

```bash
sudo editor config/.env
chmod 600 config/.env
```

### 3.6 Check before starting

```bash
docker run --rm -it \
  -v "$PWD/config:/data" \
  -v "$PWD/data:/data/data" \
  firmspace-bridge:0.1.0 \
  test --config /data/bridge.config.json --details
```

Expected: `ok` for every required adapter, and a clear reason when `ok=false`. Exit code `0`
means success, `1` a failure.

Environment diagnostics:

```bash
docker run --rm -it \
  -v "$PWD/config:/data" \
  firmspace-bridge:0.1.0 \
  doctor --config /data/bridge.config.json
```

`doctor` checks Node, configuration, token, TLS, permissions, disk and adapters. It is the
first command for any problem.

### 3.7 Start the Bridge

```bash
docker run -d --name firmspace-bridge \
  --restart unless-stopped \
  -v "$PWD/config:/data" \
  -v firmspace-bridge-state:/data/.bridge \
  -v /srv/legacy/incoming:/data/data/incoming \
  -v /srv/legacy/outgoing:/data/data/outgoing \
  --env-file config/.env \
  firmspace-bridge:0.1.0
```

What the parameters mean:

| Parameter | Purpose |
|---|---|
| `-d` | run in the background |
| `--restart unless-stopped` | restart after a reboot |
| `-v "$PWD/config:/data"` | configuration and `.env` inside the container |
| `-v firmspace-bridge-state:/data/.bridge` | queue, journal and state survive recreating the container |
| `-v /srv/legacy/incoming:/data/data/incoming` | client folder; the **container** path must match the configuration |
| `--env-file config/.env` | `BRIDGE_TOKEN` and adapter secrets |

Check that the container is alive and the Bridge can see the cabinet:

```bash
docker ps --filter name=firmspace-bridge
docker logs --tail=50 firmspace-bridge
docker exec firmspace-bridge \
  node dist/cli/index.js status --config /data/bridge.config.json --json
```

`status` must show `lastHeartbeatOk=true`, a rising `uptime` and an empty or small
`queue.total`. In the cabinet, the bridge appears in the connection list as “online” with the
time of its last heartbeat.

`[SCREENSHOT]` The bridge card in the cabinet: status, heartbeat, queue.

---

## 4. Where the files go

| Item | On the host | In the container |
|---|---|---|
| Configuration | `/opt/firmspace-bridge/config/bridge.config.json` | `/data/bridge.config.json` |
| Secrets | `/opt/firmspace-bridge/config/.env` (600) | `/data/.env` |
| Queue, journal, lock | volume `firmspace-bridge-state` | `/data/.bridge` |
| Client incoming folder | `/srv/legacy/incoming` | `/data/data/incoming` |
| Client outgoing folder | `/srv/legacy/outgoing` | `/data/data/outgoing` |
| Print spool (if used) | `/srv/legacy/spool` | `/data/data/spool` |

Rule: **the configuration contains container paths**, not host paths. If the cabinet used
`/srv/legacy/incoming`, after the import check that `bridge.config.json` says
`data/incoming` or `/data/data/incoming`.

Permissions: the container runs as `node`, UID/GID 1000. If the folder belongs to another
user, the Bridge cannot read it:

```bash
ls -ln /srv/legacy/incoming
sudo chown -R 1000:1000 /srv/legacy/incoming
```

---

## 5. What appears in the cabinet

1. The bridge in the enterprise connection list: name, version, platform, last heartbeat.
2. Adapters with state `running` / `error` and the time of the last event.
3. Queue and jobs: `ingest`, `export`, `run-agent`, `test-connection`.
4. Event and result journal; errors appear in the cabinet and in the local
   `.bridge/journal/YYYY-MM-DD.jsonl`.
5. Jobs the bridge fetches from the cabinet and results it sends back.

If the bridge is not visible after 2–3 minutes, start with §7.

---

## 6. Updates and backup

### 6.1 Update

```bash
# 1. Download the new archive and compare the checksum
docker load -i firmspace-bridge-<new-version>-<architecture>.tar.gz

# 2. Stop and remove the old container; keep the state volume
docker stop firmspace-bridge
docker rm firmspace-bridge

# 3. Start the new image with the same volumes
docker run -d --name firmspace-bridge \
  --restart unless-stopped \
  -v /opt/firmspace-bridge/config:/data \
  -v firmspace-bridge-state:/data/.bridge \
  -v /srv/legacy/incoming:/data/data/incoming \
  -v /srv/legacy/outgoing:/data/data/outgoing \
  --env-file /opt/firmspace-bridge/config/.env \
  firmspace-bridge:<new-version>
```

Read the release notes first: the configuration format may change. If `bridge test` fails
with `CONFIG_INVALID` after the update, roll back to the previous image and contact support.

### 6.2 Backup

```bash
cd /opt/firmspace-bridge
tar czf "bridge-backup-$(date +%F).tgz" \
  config/bridge.config.json config/.env
docker run --rm -v firmspace-bridge-state:/src -v "$PWD:/backup" \
  alpine tar czf /backup/bridge-state-$(date +%F).tgz -C /src .
```

`config/.env` contains the token and passwords. Encrypt the copy before it leaves the
machine; do not put it on a shared drive or attach it to a ticket.

---

## 7. If it does not work: 10 common failures

Always start with these three commands:

```bash
docker ps -a --filter name=firmspace-bridge
docker logs --tail=100 firmspace-bridge
docker exec firmspace-bridge node dist/cli/index.js doctor --config /data/bridge.config.json
```

### 7.1 `docker load`: file not found or “invalid tar header”

**Cause:** wrong file, corrupt archive, or not a `docker save` archive.
**Diagnosis:**

```bash
file firmspace-bridge-*.tar.gz
shasum -a 256 firmspace-bridge-*.tar.gz
docker load -i firmspace-bridge-*.tar.gz
```

**What to do:** download again and compare the checksum. Do not unpack the archive manually
and do not rename `.tar` to `.tar.gz`.

### 7.2 The container exits immediately (`Exited (1)`)

**Cause:** missing configuration, wrong path or invalid JSON.
**Diagnosis:**

```bash
docker logs firmspace-bridge
docker run --rm -it -v /opt/firmspace-bridge/config:/data \
  firmspace-bridge:0.1.0 test --config /data/bridge.config.json --details
```

**What to do:** check that `/data/bridge.config.json` exists and is valid JSON. The
`CONFIG_INVALID` message points to the field.

### 7.3 `CONFIG_INVALID`

**Cause:** typo in a key, wrong type, unknown field (the schema rejects extra keys), or an
unfilled placeholder.
**Diagnosis:**

```bash
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
```

**What to do:** fix the named field in `bridge.config.json`. Do not add keys “just in case”;
the zod schema rejects them. Then `docker restart firmspace-bridge`.

### 7.4 `ALREADY_RUNNING`

**Cause:** another process uses the same `.bridge`, or a lock remains from a killed process.
**Diagnosis:**

```bash
docker ps -a --filter name=firmspace-bridge
docker exec firmspace-bridge ls -l /data/.bridge/bridge.lock
ps aux | grep -i bridge
```

**What to do:** stop the second instance. If no process exists and only the lock remains,
remove only the lock:

```bash
docker exec firmspace-bridge rm -f /data/.bridge/bridge.lock
docker restart firmspace-bridge
```

Do not delete the whole `.bridge` directory: it contains the queue and journal.

### 7.5 `CONSOLE_AUTH_FAILED` (HTTP 401)

**Cause:** token wrong, revoked, or `.env` still contains a placeholder.
**Diagnosis:**

```bash
docker exec firmspace-bridge sh -c 'grep -c "^BRIDGE_TOKEN=." /data/.env'
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
docker exec firmspace-bridge node dist/cli/index.js \
  doctor --config /data/bridge.config.json
```

`grep -c` must return `1`. If `doctor`/`test` show `CONSOLE_AUTH_FAILED`, the token is not
accepted.
**What to do:** revoke the bridge in the cabinet, create a new one, download the new
`bridge.config.json`, repeat the import, restart the container. The old token cannot be
restored.

### 7.6 `CONSOLE_UNREACHABLE`

**Cause:** DNS, proxy, firewall or a wrong `console.url`.
**Diagnosis:**

```bash
docker exec firmspace-bridge sh -c 'getent hosts agents.firmspace.de || nslookup agents.firmspace.de'
curl -vI https://agents.firmspace.de
docker exec firmspace-bridge node dist/cli/index.js \
  status --config /data/bridge.config.json --json
```

**What to do:** check outbound port 443, proxy settings (`HTTPS_PROXY`) and `console.url`.
The Bridge keeps working with the local queue when the cabinet is unreachable, but receives
no new jobs until the connection is back.

### 7.7 `CONSOLE_TLS_ERROR`

**Cause:** the corporate CA is not trusted in the container, the certificate expired, or the
name does not match.
**Diagnosis:**

```bash
openssl s_client -connect agents.firmspace.de:443 \
  -servername agents.firmspace.de -showcerts </dev/null
```

**What to do:** put the corporate CA in a file and reference it in the configuration:

```jsonc
"console": {
  "url": "https://agents.firmspace.de",
  "tls": { "rejectUnauthorized": true, "caFile": "/data/ca.pem" }
}
```

Mount the file into the container and restart. Do not disable `rejectUnauthorized` in
production.

### 7.8 `PATH_NOT_ALLOWED`, or files are not picked up

**Cause:** a job path is outside `allowedRoots`, the host path is not mounted, UID 1000 lacks
permission, or the file is still being written (`stabilityMs`).
**Diagnosis:**

```bash
docker exec firmspace-bridge ls -ln /data/data/incoming
docker inspect firmspace-bridge --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
```

**What to do:** add the root to `security.allowedRoots`, check that the configuration path is
a container path, and give UID 1000 read/write access:

```bash
sudo chown -R 1000:1000 /srv/legacy/incoming
```

### 7.9 `EMAIL_AUTH_FAILED` / `EMAIL_CONNECTION_FAILED`

**Cause:** normal password instead of an app password, 2FA, wrong port/TLS, or an expired
OAuth2 token.
**Diagnosis:**

```bash
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --adapter <id> --details
openssl s_client -connect imap.example.de:993 -servername imap.example.de </dev/null
```

**What to do:** for 993 use `secure: true`; for 587 use `secure: false` and
`requireTls: true`; for Gmail use an app password or OAuth2. Verify webmail access and that
IMAP is enabled.

### 7.10 `DB_CONNECT_FAILED` / `ODBC_DRIVER_MISSING`

**Cause:** no network path to the database, wrong credentials, missing driver, or the
read-guard rejected the query.
**Diagnosis:**

```bash
docker exec firmspace-bridge node -e '
const net = require("net");
const s = net.connect(5432, "db.example.de", () => { console.log("open"); s.end(); });
s.on("error", (e) => console.log("ERR", e.code));
'
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --adapter <id> --details
```

Expected `open`; `ERR ETIMEDOUT` or `ECONNREFUSED` means network/firewall. For Oracle/MSSQL
check the ODBC driver; PostgreSQL/MySQL drivers ship in the image.
**What to do:** open the database port for the Bridge machine, use a **read-only** user,
remove DML/DDL from the query (read-guard), and check `odbcinst -q -d` on the host.

> If the error is not in this list: run `doctor` and `status --json`, save the last 24 hours
> of `.bridge/journal`, and send them to support. Do not send `.env` or `bridge.config.json`
> with secrets.

---

## 8. Security and limits

- The Bridge opens no inbound ports. All connections are outbound HTTPS 443.
- The cabinet stores only the SHA-256 of the Bridge token; the token itself is in `.env`
  with mode 600.
- Adapter passwords stay on the client's machine; the cabinet holds non-secret parameters
  only.
- Read-only by default: background writes are forbidden; writing is possible only through
  jobs and only with `security.allowJobWrites=true`.
- `allowedRoots` prevents a job from leaving the allowed directories; symlinks do not bypass
  the allowlist.
- Delivery is **at-least-once**. Adapters that write outward must be idempotent.
- The Bridge does no OCR, categories, agents, UI or ELSTER export. That is not its job.

---

## 9. Acceptance checklist

- [ ] Image architecture matches `uname -m` (`docker image inspect`).
- [ ] `bridge.config.json` imported; no real token inside.
- [ ] `.env` exists, mode `600`, `BRIDGE_TOKEN` filled.
- [ ] `bridge test --details` — all required adapters `ok`.
- [ ] `bridge doctor` — no critical errors.
- [ ] Container runs with `--restart unless-stopped`.
- [ ] Bridge shows “online” in the cabinet, heartbeat fresh.
- [ ] A test file completed the path: folder → event → cabinet.
- [ ] Configuration and `.env` backed up and encrypted.
- [ ] Owner and update procedure recorded.
