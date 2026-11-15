# torrmedia-android

The Android TV client of the torrmedia bundle. It is based on
[TorrServe](https://github.com/YouROK/TorrServe) (the MatriX line) and keeps
everything TorrServe does — instant streaming of torrents through
[TorrServer](https://github.com/YouROK/TorrServer) — and adds a second backend:
a library of downloaded items served by
[torrmedia](https://github.com/yuri-nei/torrmedia), a small server in front of
qBittorrent.

torrmedia-android is an independent project, not affiliated with or endorsed by
the authors of TorrServe, TorrServer or qBittorrent.

## What it adds

- **One list for both backends.** TorrServer torrents and torrmedia items sit
  side by side on the main screen; a row says in words what an item is doing
  ("Stopped · 6 of 8 ready · 3 watched").
- **Download instead of stream.** A magnet opened from another app can go to
  TorrServer, as before, or to the server's qBittorrent. Adding goes through a
  draft: the file list arrives first, you tick the episodes you want and see the
  free space on the server before confirming.
- **An item screen with episodes.** Downloaded episodes play from torrmedia over
  HTTP with Range, through the same player intent as a TorrServer stream. An
  episode can be deleted on its own, watched episodes in one go, and a deleted
  or skipped one can be downloaded again. Long press sets the download priority.
- **Watch marks** come from the server, which tracks what the player actually
  read; they can be set and cleared by hand.
- **Live state.** The client keeps a server-sent event stream open and shows
  what the server reports, including the phase of a running operation; it never
  guesses state on its own.
- **TV-first UI.** Material dark and light themes, steady D-pad focus in lists,
  search moved into the side menu, a download-server block in the menu header
  with the server's free space.

It installs next to TorrServe: the application id is `io.github.yurinei.torrmedia`.

## Setup

The bundle has two servers, and the app talks to both:

1. **TorrServer** streams torrents as they download. Either let the app install
   a local one on the device (**Update** in the side menu, then **TorrServer
   update**) or run [TorrServer](https://github.com/YouROK/TorrServer) on a
   machine of your own and enter its address in **Settings → Set TorrServer
   URL**, as in TorrServe. It has to be a **MatriX** release (tested with
   MatriX.150). Silo, the from-scratch rewrite of TorrServer and TorrServe
   started in November 2026, has a different API and is not supported; its
   authors do not consider it ready for use either.
2. **torrmedia** serves what qBittorrent has downloaded. Run
   [torrmedia](https://github.com/yuri-nei/torrmedia) next to qBittorrent
   (Linux or macOS), then enter its address in **Settings → torrmedia** (for
   example `http://192.0.2.10:8091`) and the token if the server has one.
   **Check connection** tests TCP and the API. Use the server's IP address, a
   `.local` name, or a name listed in the server's `TORRMEDIA_HOSTS`.

The client never needs qBittorrent's address or credentials. Client and
torrmedia ship together: if their contract numbers differ, the client says so
instead of half-working.

## Building

Requirements: JDK 21 and the Android SDK (`sdk.dir` in `local.properties`).

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The APK lands in `app/build/outputs/apk/` with the build time in its name.
Release builds are signed when `keystore.properties` in the project root (keys
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or the environment
variables `TORRMEDIA_KEYSTORE_FILE`, `TORRMEDIA_KEYSTORE_PASSWORD`, `TORRMEDIA_KEY_ALIAS`,
`TORRMEDIA_KEY_PASSWORD` are set; otherwise the release build is left unsigned.
Keys never go into the repository.

## Translations

The added screens are translated into English and Russian. In other languages
inherited from TorrServe they fall back to English.

## License

GPL-3.0, see [LICENSE](LICENSE). Based on TorrServe by YouROK and its
contributors.
