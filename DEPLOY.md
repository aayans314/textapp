# Deploying TextApp to your Oracle VPS

Your setup:

| Thing | Value |
|---|---|
| Server IP | `132.145.214.1` |
| App port | `9090` |
| How you connect | VS Code **Remote - SSH** extension |
| Transport | Direct: the app is built to always call `http://132.145.214.1:9090` |

Read the whole thing once before starting. Most of it is copy-paste into the VS Code remote terminal.

> **Security note:** message content and media stay end-to-end encrypted, but the
> server address is part of the network traffic — friends can't see it in the app,
> but it's not "hidden" from the network itself. If you later want the IP
> unobservable, get a domain + Cloudflare Tunnel and rebuild (step 7).

---

## 1. Connect with VS Code Remote - SSH

1. Install the **Remote - SSH** extension (ID: `ms-vscode-remote.remote-ssh`).
2. Press `Ctrl+Shift+P` → **Remote-SSH: Connect to Host…** → `ubuntu@132.145.214.1`.
   - If your image is Oracle Linux, use `opc@132.145.214.1` instead of `ubuntu`.
   - If you use an SSH key, configure it once: `Ctrl+Shift+P` → **Remote-SSH: Open SSH Configuration File…** → add:
     ```text
     Host oracle
       HostName 132.145.214.1
       User ubuntu
       IdentityFile C:\Users\YOU\.ssh\oracle-key.pem
     ```
     Then connect to **oracle**. (On Linux/macOS the path would be `~/.ssh/oracle-key.pem`.)
   - If you log in with a password, just type it when prompted. Make sure the `.pem` key is not world-readable.
3. When VS Code reopens the window, open a terminal: **Terminal → New Terminal**. Verify:
   ```bash
   whoami
   node --version
   ```
   If `node` isn't found, install it (step 2).

> Tip: the VS Code terminal below is now **on the server**, not your PC.

## 2. Install Node.js 22

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs
node --version   # want >= 22.13
```

## 3. Put the `server/` folder on the VPS

Create the app directory and take ownership of it:

```bash
sudo mkdir -p /opt/textapp/data
sudo chown -R ubuntu:ubuntu /opt/textapp   # your SSH user is ubuntu (ubuntu@testingvnic)
```

Now copy the backend over. Three easy options:

**Option A — drag & drop (recommended with VS Code)**
In the VS Code explorer (which now shows the remote filesystem), navigate into `/opt/textapp` and drag these from your local Windows Explorer:

- `package.json`
- `package-lock.json`
- the whole `src/` folder
- `.env.example`
- `.env` (contains your Resend key and `SECRET` — gitignored, so only copy it directly)

(Skip `node_modules` if you have one locally; `test/` is optional, it's only for development.)

**Option B — scp from your PC**
Open a local PowerShell (not the VS Code remote terminal) and run:

```powershell
scp -i C:\Users\YOU\.ssh\oracle-key.pem -r server\package.json server\package-lock.json server\src server\.env.example server\.env ubuntu@132.145.214.1:/opt/textapp/
```

**Option C — git clone**
If you ever push this repo to GitHub, this is the cleanest path:

```bash
cd /opt/textapp
git clone <your-repo-url> server-tmp && mv server-tmp/server/* . && rm -rf server-tmp
```

Then install dependencies (pure JS, no compilation — takes seconds):

```bash
cd /opt/textapp
npm install --omit=dev
```

If you used the isolated Node 22 install in `/opt/node22` (so the system Node is
left untouched for other processes), run:

```bash
cd /opt/textapp
/opt/node22/bin/npm install --omit=dev
```

Sanity-check the copy before installing:

```bash
ls -la /opt/textapp   # must show package.json, package-lock.json, src/, .env, .env.example
```

## 4. Configure the environment

```bash
cd /opt/textapp
nano .env
# If .env is missing (e.g. you deployed via git clone), create it first:
#   cp .env.example .env
```

Edit at minimum:

```bash
PORT=9090
# 0.0.0.0 because the app connects to this IP:9090 directly
HOST=0.0.0.0
# generate with: openssl rand -hex 32 (real value lives in /opt/textapp/.env)
SECRET=<long-random-hex-string>

# Email verification with Resend (recommended)
# create a "Sending access" key at resend.com/api-keys (real value lives in /opt/textapp/.env)
RESEND_API_KEY=re_xxxxxxxxxxxxxx
RESEND_FROM="TextApp <noreply@aayanshah.me>"
#   RESEND_FROM must be an address on a domain you verified at resend.com/domains.
#   Until then, leave RESEND_FROM empty and the app falls back to
#   onboarding@resend.dev (delivers only to your own Resend account inbox).

# Alternative: plain SMTP (uncomment these and leave RESEND_API_KEY empty)
# SMTP_HOST=smtp.gmail.com
# SMTP_PORT=587
# SMTP_USER=you@gmail.com
# SMTP_PASS=<app password>
# SMTP_FROM="TextApp <you@gmail.com>"

# Push notifications (optional — see docs/ANDROID_BUILD.md)
FIREBASE_PROJECT_ID=
FIREBASE_CLIENT_EMAIL=
FIREBASE_PRIVATE_KEY=
```

> **No inline comments in `.env`.** systemd's `EnvironmentFile` keeps everything
> after `#` as part of the value — `HOST=0.0.0.0 # comment` becomes the hostname
> and the service crashes at boot. Keep comments on their own lines, as shown above.

Save with `Ctrl+O`, exit with `Ctrl+X`.

**Verify your Resend sending domain** (one-time, so your friends can receive codes):

1. Open the [Resend dashboard → Domains](https://resend.com/domains) and click **Add Domain**.
2. Enter `aayanshah.me` and add the DNS records it shows (SPF / DKIM / DMARC)
   wherever that domain's DNS is managed (registrar / DNS provider).
   If an SPF record already exists (e.g. from a mail provider), merge Resend's
   include into it instead of adding a second SPF record.
3. Wait until the domain shows **Verified** (usually a few minutes).
4. Set `RESEND_FROM` to any address on that verified domain, e.g.
   `TextApp <noreply@aayanshah.me>`, then `sudo systemctl restart textapp`.

Until the domain is verified, codes only reach the email on your Resend account
(sent from `onboarding@resend.dev`) — enough to test registration once with your own inbox.

> `.env` holds secrets (`RESEND_API_KEY`, `SECRET`) and is gitignored, so it never
> lands in the repo. If you deploy via git, create it on the server with
> `cp .env.example .env` and edit it there.

## 5. Run it as a service (auto-starts on boot)

Create the unit file at `/etc/systemd/system/textapp.service`:

```ini
[Unit]
Description=TextApp messenger server
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/textapp
Environment=NODE_ENV=production
EnvironmentFile=/opt/textapp/.env
ExecStart=/usr/bin/node --disable-warning=ExperimentalWarning src/index.js
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true
ReadWritePaths=/opt/textapp/data

[Install]
WantedBy=multi-user.target
```

If you're running Node 22 from `/opt/node22` instead of replacing the system
Node, use this line instead:
`ExecStart=/opt/node22/bin/node --disable-warning=ExperimentalWarning src/index.js`

(A copy also ships in this repo at `deploy/textapp.service` — upload that instead if you prefer.)

Start it:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now textapp
sudo systemctl status textapp
```

Check the health endpoint (run from the server):

```bash
curl http://127.0.0.1:9090/api/health
# {"ok":true,"name":"TextApp","time":...}
```

Logs if something's wrong: `sudo journalctl -u textapp -f`

### Docker alternative (optional)

```bash
cd /opt/textapp
# set HOST=0.0.0.0 in .env when running inside Docker (the compose file already
# binds only 127.0.0.1 on the host)
docker compose -f ../deploy/docker-compose.yml up -d
```

## 6. Open port 9090 (required — the app talks to this IP directly)

Because the APK is baked to `http://132.145.214.1:9090`, that port must be reachable from phones:

1. **Oracle Cloud Console** → Networking → Virtual Cloud Networks → your VCN → Security List → **Add Ingress Rules**:
   - Source: `0.0.0.0/0`
   - IP Protocol: TCP
   - Destination Port Range: `9090`
2. On the server, allow it in the firewall too:
   ```bash
   sudo iptables -I INPUT -p tcp --dport 9090 -j ACCEPT
   sudo netfilter-persistent save
   ```
3. Verify from your PC:
   ```bash
   curl http://132.145.214.1:9090/api/health
   ```

## 7. Optional later: Cloudflare Tunnel + your domain

The app currently points at the IP directly. If you ever get a domain and want
the IP unobservable:

1. Set `HOST=127.0.0.1` in `.env` and restart.
2. Install `cloudflared`, create a tunnel, and route your domain to `http://localhost:9090` (details below).
3. Rebuild the APK against the domain instead of the IP.

On the server:

```bash
sudo apt-get install -y cloudflared
cloudflared tunnel login            # opens a browser URL, log in to Cloudflare
cloudflared tunnel create textapp
cloudflared tunnel route dns textapp chat.yourdomain.com
```

Edit the config (`sudo nano /etc/cloudflared/config.yml`), replacing the tunnel ID with yours from `cloudflared tunnel list`:

```yaml
tunnel: textapp
credentials-file: /home/ubuntu/.cloudflared/<your-tunnel-id>.json

ingress:
  - hostname: chat.yourdomain.com
    service: http://localhost:9090
  - service: http_status:404
```

Install it as a service:

```bash
sudo cloudflared service install
sudo systemctl enable --now cloudflared
sudo systemctl status cloudflared
```

Test: on your PC browser open `https://chat.yourdomain.com/api/health` — you should see the JSON.

That's it. Your friends' APKs point at `https://chat.yourdomain.com`, traffic flows through Cloudflare, and `132.145.214.1` is never exposed — the VPS makes only an outbound connection to Cloudflare.

## 8. End-to-end check with a real phone

1. Install the domain-based APK (see [docs/ANDROID_BUILD.md](docs/ANDROID_BUILD.md)).
2. Register a test account — the 6-digit code must arrive by email.
3. Add a second account, become friends, send text, a photo, and a video.
4. Close the app and verify the notification arrives with the screen off.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `curl http://127.0.0.1:9090/api/health` fails | `sudo systemctl status textapp`; `sudo journalctl -u textapp -f`; confirm `PORT=9090` is in `.env` and the service was restarted |
| Email code never arrives | Resend: confirm your domain is **Verified** at resend.com/domains and `RESEND_FROM` uses it (before that, codes only reach your own Resend inbox); check `sudo journalctl -u textapp -f` for mailer errors. SMTP alternative: check `SMTP_*`; port 587 may be blocked → try 465 with `SMTP_SECURE=1` |
| Tunnel site shows 502/404 | `sudo systemctl status cloudflared`; `cloudflared tunnel list`; confirm `service: http://localhost:9090` matches the app port |
| Friends can't install the APK | They must allow "install unknown apps" for the messenger they receive it from |
| Push notifications don't arrive | Firebase isn't configured (everything else works) — see [docs/ANDROID_BUILD.md](docs/ANDROID_BUILD.md) |
| Friends can't reach the server | `HOST=0.0.0.0` in `.env` + restart, Oracle Security List needs TCP 9090, iptables must allow it, then `curl http://132.145.214.1:9090/api/health` from your PC |

## Backups

Everything you need is in `/opt/textapp/data` (SQLite DB + media blobs, all ciphertext):

```bash
sudo tar czf ~/textapp-backup-$(date +%F).tgz /opt/textapp/data
```

Restore = un-tar it back into `/opt/textapp/data` and `sudo systemctl restart textapp`.
