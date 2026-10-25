# Homelab companion

A small Android TV app that goes next to the CleanYourTV launcher and talks to the services on your own
network. The launcher itself has no network permission and keeps it that way; everything that needs
the network lives here instead.

![Homelab on a Formovie projector: the Homepage dashboard, Search across Radarr and Sonarr, a film's detail screen, Coming up and Settings](docs/homelab.jpg)

| Part | What it does |
|---|---|
| **Immich screensaver** (`PhotoDream`) | Recent photos from Immich in the manner of Google Photos' ambient mode: crossfades and a slow zoom, two portraits side by side, a blurred copy of the photo instead of black bars, the album, date, place and people bottom left and the clock bottom right. Albums and tags can be hidden, or a specific set of albums can be shown exclusively. Needs Immich 3.2 or later. |
| **Homelab** tile (`MainActivity`) | One launcher tile, with tabs across the top and a clock. Moving along the tabs switches pages; Down goes into a page, Up from its top and Back return to the tabs. |
| **Dashboard** tab (`DashboardPage`) | Your [Homepage](https://gethomepage.dev) dashboard in a card under the tabs. Up/Down scroll it, Menu reloads, links to other hosts are blocked. A loading screen, an error screen that says what failed with Try again and Settings, a reload when you come back after a while, and optionally the screen kept on for a wall display. |
| **Search** tab (`SearchPage`, `DetailActivity`) | One field for films and shows; Radarr and Sonarr are asked at once as you type, and the results are one grid of posters, best match first, with a tick on what you already have. OK on a poster shows its backdrop, ratings and overview with **Add and search** focused, so adding is a single press; quality and (for a show) which episodes to monitor sit next to it. Something already in the library offers **Search again**. Voice search when the TV has a recogniser. |
| **Downloads** tab (`DownloadsPage`) | qBittorrent's torrents named the way Sonarr and Radarr know them, with speed, time left and size, refreshed every few seconds while on screen; OK stops or starts one. Downloads the apps track elsewhere (another client, Usenet) are listed too. |
| **Coming up** tab (`UpcomingPage`) | The next two weeks of both calendars, what is missing, free disk space and health warnings. |
| **Settings** tab (`SettingsPage`) | Every setting, edited with the remote: the services down the left with a green dot once set up, their settings as cards on the right. OK edits a row in a dialog, flips a switch, or picks albums, tags, quality profiles and root folders from lists your servers return; each server has a Test connection row. Changes save at once and the other tabs use them straight away. Also the gear next to the screensaver in the TV's own settings. |

A tab whose service is not set up says so, with a button straight to its settings.

No runtime dependencies, about 80 KB installed. Plain Java like the launcher, minSdk 26.

## Permissions and data

- `INTERNET`, for the URLs in your `config.json`, and for the posters and backdrops the Sonarr and
  Radarr lookups point at, which come from the public image hosts they use (`image.tmdb.org`,
  `artworks.thetvdb.com`) without any key or account. No analytics, nothing else. The Homepage page can
  itself load icons from public CDNs if your Homepage config uses them.
- `WRITE_SECURE_SETTINGS`, granted over ADB by `build-and-install.sh`, used for one thing only: the
  Use as the screensaver button writes `screensaver_components` and `screensaver_enabled`. It is a
  broad permission (any secure setting), so the app writes nothing else; skip the grant with
  `pm revoke io.github.epochcoder.homelab android.permission.WRITE_SECURE_SETTINGS` and the button
  falls back to opening the TV's screensaver settings.
- A package-visibility query for voice input (`RECOGNIZE_SPEECH`), so the Media screen shows its mic
  button only when something on the TV can take voice. With Google's apps disabled there may be none;
  the on-screen keyboard always works.
- Settings, API keys included, stay in the app's private storage (`files/config.json`). The Settings
  screen writes it, or `push-config.sh` through a provider that only the adb shell can use (it needs
  `WRITE_SECURE_SETTINGS`) and that is write-only.
- Backups are off.

## Set up

Install once, from the repo root, with `tools/venv` set up as in the main README and the TV reachable
over ADB:

```bash
TV_HOST=192.168.1.50 SCREENSAVER=1 companion/build-and-install.sh
```

Then open **Homelab** on the TV and its **Settings** tab, fill in each server's address and API key,
press Test connection, and pick what to hide and where to add. Changes save as you make them; the tabs
use them at once and the screensaver the next time it starts. Nothing needs a rebuild. Long API keys are easiest to type from a phone: the Google TV app's
remote keyboard types into the TV's text fields.

Or write everything at once from the computer, which is also the only way to set `ca_pem`:

```bash
cp companion/config.example.json companion/config.json      # ignored by git: it holds your API keys
$EDITOR companion/config.json
TV_HOST=192.168.1.50 companion/push-config.sh companion/config.json
```

A push replaces the whole file, so settings changed on the TV since are replaced too.

To make it the screensaver, use Settings > Immich photos > **Use as the screensaver**. Android keeps
that choice in a secure setting, which an app may only write once ADB has granted it
`WRITE_SECURE_SETTINGS`; `build-and-install.sh` does that on every install, so the button works. On
a TV that refuses the grant, the button opens the TV's own screensaver settings instead.
`SCREENSAVER=1` on `build-and-install.sh` still selects it from the computer.

Try the screensaver without waiting for it (or use Preview in Settings):

```bash
TV_HOST=192.168.1.50 tools/tv.py 'am start -n io.github.epochcoder.homelab/.SlideshowActivity'
TV_HOST=192.168.1.50 tools/tv.py 'logcat -d -s Homelab.Slideshow Homelab.Tls Homelab.Dashboard'
```

On Google TV, the stock screensaver is Google's Ambient mode (`com.google.android.apps.tv.dreamx`),
which also shows recommendations. Disable it with the rest of the debloat list so it cannot take the
screensaver back.

### API keys

- **Immich:** Account Settings > API Keys > New. It needs only these four permissions, which Settings
  also lists under the key:

  | Permission | Used for |
  |---|---|
  | `asset.read` | the list of recent photos, with their date, place and people |
  | `asset.view` | the photo itself (the preview size) |
  | `album.read` | the album caption, and the Hide albums picker |
  | `tag.read` | the Hide tags picker |

  Test connection makes each of those requests once and names any permission the key is missing.
- **Sonarr / Radarr:** Settings > General > API Key.
- **qBittorrent:** the WebUI user and password. Or leave the user empty and tick *Bypass authentication
  for clients in whitelisted IP subnets* in qBittorrent's WebUI options, with your LAN's subnet. Behind
  Caddy, qBittorrent's Host header validation needs the name you reach it by in its *Server domains*.
  Written against qBittorrent 5.1 (stop/start); 4.x's pause/resume is used when 5's calls are missing.

## Behind Caddy

- **Public certificates** (Let's Encrypt, including DNS challenge for internal names): nothing to do.
- **`tls internal`:** the TV does not trust Caddy's local CA, so give the companion Caddy's root
  certificate once. Copy it out of the Caddy container (on a QNAP, over SSH, or from Container
  Station's terminal; the container name may differ) and push it:

  ```bash
  docker cp caddy:/data/caddy/pki/authorities/local/root.crt .
  TV_HOST=192.168.1.50 companion/push-config.sh --ca root.crt
  ```

  The script prints the certificate's name, expiry and SHA-256 fingerprint, and refuses anything that
  is not a CA, including Caddy's intermediate, which Caddy replaces every few days. The root lasts ten
  years. It goes into its own file, so settings made on the TV stay as they are, and the app trusts it
  on top of the system CAs while still checking hostnames. `--reset-ca` removes it. (A `ca_pem` string
  in config.json works too, and both are trusted.)
- **A login in front (`forward_auth`, Authelia, Authentik):** the API calls carry an API key, not a
  browser session, so a login redirect breaks them. The app never follows a redirect with a key or a
  session cookie on it (that would hand them to whatever host the redirect names); you get an error on
  screen naming where it pointed, or "got a web page instead of JSON". Exempt `/api/*` on the Immich,
  Sonarr, Radarr and qBittorrent sites, or point the companion at the services' LAN ports.
- **Local DNS names** (`*.home.example` from your router, Pi-hole, AdGuard Home): leave the TV's
  Private DNS off, or set it to your own resolver. The ad-blocking Private DNS the main prompt offers
  sends every lookup to a public resolver, which does not know your internal names.

## config.json

What the Settings screen edits, key by key. Every section is optional; a part whose section is
missing shows "not set up" with a way into Settings.

| Key | Default | Meaning |
|---|---|---|
| `ca_pem` | `""` | Extra CA certificate(s) in PEM, on top of any pushed with `--ca`. |
| `immich.url`, `immich.api_key` | | Server and key. The URL is the address you open Immich at, without `/api`. |
| `immich.recent_days` | 90 | Photos taken in this many days. |
| `immich.max_photos` | 500 | At most this many per round, newest first. Max 5000. |
| `immich.include_albums` | `[]` | Album names or ids to show exclusively. If set, `exclude_albums` is ignored. |
| `immich.exclude_albums` | `[]` | Album names (case-insensitive) or ids. |
| `immich.exclude_tags` | `[]` | Tag names, full tag paths (`People/Work`) or ids. |
| `immich.shuffle` | `true` | Random order; `false` shows newest first. |
| `immich.interval_seconds` | 20 | Seconds per photo, 5 to 3600. |
| `immich.crop` | `false` | Fill the screen with every landscape photo. Off, only photos within about 20% of the screen's shape (a 3:2 camera shot on 16:9) fill; the rest fit over a blurred copy of themselves. Portraits never crop. |
| `immich.show_clock` | `true` | Clock and date bottom right. |
| `immich.details` | `true` | Album (or place), date, place, the photo's description and named people bottom left. |
| `immich.motion` | `true` | A slow zoom across each photo. |
| `immich.pair_portraits` | `true` | Two portrait photos side by side. |
| `homepage.url` | | Your Homepage address. |
| `homepage.text_zoom` | 100 | Text size in percent, 50 to 300. |
| `homepage.keep_awake` | `false` | Keep the screen on while the dashboard is open. |
| `sonarr.*`, `radarr.*` | | `url`, `api_key`; `quality_profile` (name) and `root_folder` (path) to add with. Empty takes the first one the server lists. |
| `sonarr.monitor` | `all` | Episodes a new show monitors: `all`, `future`, `missing`, `firstSeason`, `latestSeason`, `pilot`, `none`. Can be changed per show on its detail screen. |
| `qbittorrent.url`, `.username`, `.password` | | qBittorrent's WebUI. No user means no login (whitelisted subnet). |

Archived, hidden and locked photos are never shown, and videos are skipped. Hidden albums and tags are
filtered out by Immich itself. The photo list is fetched again each time the slideshow has gone round,
so new photos show up overnight. The next photo is fetched while the current one is on screen, and the
album name is one small request per photo shown.

## Build and test

```bash
cd companion && ./gradlew spotlessCheck checkstyle testReleaseUnitTest lintRelease assembleRelease
cd companion && ./gradlew spotlessApply      # format with google-java-format (AOSP style)
```

[Error Prone](https://errorprone.info) checks every compile, main and test code alike, and its warnings
fail the build (`-Werror`), so a finding is fixed or, where it is deliberate, suppressed at that line
with a reason. The code is formatted by [google-java-format](https://github.com/google/google-java-format)
in its AOSP style (4-space indent, 100 columns) through Spotless; `spotlessCheck` fails CI on any drift,
and `spotlessApply` fixes it. Error Prone 2.36 is the newest that runs on JDK 17, which CI uses.
Neither of them asks for braces, so Checkstyle adds one rule
([`config/checkstyle/checkstyle.xml`](config/checkstyle/checkstyle.xml)): every `if`, `else`, `for`,
`while` and `do` body is a braced block, one-line statements included.

The unit tests cover config parsing, captions, when a photo fills the screen, the Settings pickers'
lists, and the parsing of every server's answers. The clients themselves run against a scripted HTTP
server (`FakeServer`): the API key never follows a redirect, qBittorrent's login and retry (403 and
401, renamed session cookies, 5.x stop/start and 4.x pause/resume), Immich's cursor paging and
server-side hiding, and what Sonarr and Radarr are sent on add and search. TLS runs against a local
HTTPS server with a Caddy-shaped chain (fixtures in `app/src/test/resources/tls`). The tabs, the
empty states and the Settings dialogs have run on a TV (a Formovie Laser 4K projector, Android TV 14),
not yet against real servers.
