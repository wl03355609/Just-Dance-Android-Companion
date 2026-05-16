# Just Dance Remote for Android

This is the Android companion project for the Just Dance request bot. Current
bot builds expose phone companion access directly on the same Wi-Fi network by
default, so the separate bridge is only needed for older bot versions or custom
local-only setups.

## How It Works

```text
Android phone  ->  http://computer-ip:3000  ->  Just Dance request bot
```

When using an older local-only bot, the optional bridge forwards:

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

## Direct Bot Link

Start the bot or desktop app first. Leave **Phone companion access** enabled in
the desktop app, or set this for CLI use:

```env
PHONE_COMPANION_ACCESS=true
```

The Android app can scan for the bot on the same Wi-Fi network. For manual
linking, type the computer's LAN IPv4 address, such as `192.168.1.23`, or paste
the phone companion URL shown in the desktop app. The app tries port `3000`
first.

After the app links, click **Pair Phone** in the desktop app, then tap **Update
Token** in the Android app and enter the 6-digit code. The Android app stores
the dashboard token automatically after pairing, so you do not need to copy the
raw token.

## Optional Legacy Bridge

Use this only if the bot is configured to stay local-only. Start the bot or
desktop app first. In another terminal:

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
can auto-detect the bridge on the same Wi-Fi network.

If Windows Firewall asks about Node.js, allow it on private networks. The bridge
uses the selected port for both TCP app API traffic and UDP auto-discovery.

## Dashboard Token

Current bot builds should use the 6-digit pairing code above. For older builds
or manual fallback, copy the token from the dashboard URL printed by the bot:

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
- saved server URL and dashboard token

Android must be on the same Wi-Fi network as the computer running the bot or
optional bridge.

## Disclaimer

This is an unofficial, fan-made companion app. It is not affiliated with,
endorsed by, or sponsored by Ubisoft Entertainment.

*Just Dance*, *Just Dance Unlimited*, *Just Dance+*, all related logos, song
titles, artwork, and other identifiers are trademarks and/or copyrighted
material of [Ubisoft Entertainment](https://www.ubisoft.com/) and their
respective owners. They are referenced here only to display the song catalog
maintained by the request bot — no game files, music, video, or artwork is
bundled with this project.

If you are a rights holder and would like content removed, open an issue on
this repository.
