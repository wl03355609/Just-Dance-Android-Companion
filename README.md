# Just Dance Remote for Android

This is a separate companion project for the Just Dance request bot. It does not
change the bot. The bot currently listens on `127.0.0.1`, which is only reachable
from the computer running it, so the phone app uses a tiny LAN bridge that
forwards the existing bot API to your Android phone.

## How It Works

```text
Android phone  ->  http://computer-ip:3000 or :3001  ->  http://127.0.0.1:3000
                 LAN bridge                       existing bot
```

The bridge only forwards:

- `GET /api/queue`
- `GET /api/songs`
- `GET /api/search`
- `GET /events`
- `POST /api/request`
- `POST /api/skip`
- `POST /api/remove`
- `POST /api/clear`
- `POST /api/filters`
- `POST /api/theme`
- `POST /api/pick`
- `POST /api/promote`

Mutating actions still require the bot dashboard token.

## Run The Bridge

Start the bot or desktop app first. In another terminal:

```bash
cd android-companion/bridge
npm start
```

By default the bridge tries to expose itself on port `3000` for your Wi-Fi
network. If port `3000` is already occupied, such as by the bot itself, it
automatically falls back to port `3001`. The bridge reads the bot from
`127.0.0.1:3000` unless configured otherwise.

Optional configuration:

```bash
BOT_PORT=3000 BRIDGE_PORT=3001 npm start
```

The bridge prints LAN URLs such as `http://192.168.1.23:3000`. The Android app
can auto-detect the bridge on the same Wi-Fi network. For manual linking, you can
paste the full URL or just type the Windows IPv4 address, such as
`192.168.1.23`; the app will try port `3000` first, then `3001`.

If Windows Firewall asks about Node.js, allow it on private networks. The bridge
uses the selected port for both TCP app API traffic and UDP auto-discovery.

## Dashboard Token

For queue controls, copy the token from the dashboard URL printed by the bot:

```text
http://localhost:3000/dashboard?token=YOUR_TOKEN_HERE
```

Paste only `YOUR_TOKEN_HERE` into the Android app token field. If you set a fixed
`ADMIN_TOKEN` in the bot environment, use that value instead.

## Android App

Open `android-companion/android` in Android Studio, let it sync, then run the
`app` configuration on your phone.

The app supports:

- live queue updates through the bot's existing `/events` stream
- adding song requests
- song autocomplete from `/api/songs`
- pick, remove, next, and clear controls
- dark/light overlay theme switching
- enabled game filters
- saved server URL, requester name, and dashboard token

Android must be on the same Wi-Fi network as the computer running the bridge.
