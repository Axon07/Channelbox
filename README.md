<div align="center">

<img src="docs/header.png" alt="Channelbox" width="760">

**A free, open-source live TV player for Android TV – built to feel like a cable box.**

Turn on the TV, and you're watching TV. Channel up/down, a real programme guide, recordings.
Bring your own IPTV subscription (M3U or Xtream Codes).

[![Build](https://github.com/Axon07/Channelbox/actions/workflows/build.yml/badge.svg)](https://github.com/Axon07/Channelbox/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Axon07/Channelbox?include_prereleases&label=download)](https://github.com/Axon07/Channelbox/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose%20for%20TV-7F52FF?logo=kotlin&logoColor=white)

[Download](#download) · [Features](#features-v01) · [Remote control](#remote-control) · [Cable-box setup](#making-it-act-like-a-cable-box) · [Building](#building)

</div>

> [!NOTE]
> Channelbox is **only a player**. It doesn't include, sell or link to any channels. You need your own IPTV service.
> Not affiliated with any IPTV provider.

## Why

Paid IPTV players work well, but the good ones are closed source and subscription-based. Channelbox aims to be a
free alternative that's simple enough for anyone – especially people who just want their TV to behave like the cable
box they're used to.

## Download

Grab the latest `channelbox-vX.Y.Z.apk` from **[Releases](https://github.com/Axon07/Channelbox/releases)**, or the
newest test build from the **[Actions](https://github.com/Axon07/Channelbox/actions)** tab (*channelbox-apks* artifact).

Install it on the box with the **Downloader** app, a USB stick, or `adb install channelbox.apk`.

**Updates:** Channelbox checks for a new release when it starts and offers to install it (turn that off in Settings).
You can also use **Settings → Check for updates**. The first time, Android asks you to allow Channelbox to install apps.

## Features (v0.1)

| | |
|---|---|
| **Sources** | M3U / M3U8 playlist URL (incl. `.gz`), Xtream Codes login |
| **TV guide** | XMLTV (incl. `.xml.gz`) from every guide the playlist lists (`url-tvg`), from Xtream, plus your own URLs. Channels are matched by `tvg-id`, then a loose id match, then by name (ignoring "US:", "HD", "(1080p)" and similar) |
| **Guide screen** | Cable-style grid (channels × time) with a live video preview in the corner, programme details and a "now" line. ● marks programmes set to record |
| **Groups menu** | Back from the guide: channel groups, with Recordings and ⚙ Settings at the bottom |
| **Playback** | Media3/ExoPlayer: HLS, MPEG-TS, DASH (`.mpd`, also `.mpd/…` links), Smooth Streaming, RTSP, RTMP and UDP multicast (`udp://@239.…`). Unknown links are tried as TS → HLS → DASH. A broken channel shows a message and retries – it never crashes the app |
| **Stream options** | Kodi-style `url|User-Agent=…&Referer=…`, `#EXTVLCOPT:http-user-agent/http-referrer`, `#EXTHTTP:{…}`, `#KODIPROP:inputstream.adaptive.stream_headers` |
| **DRM** | `#KODIPROP` `license_type`/`license_key`, `drm_legacy` and `drm` (JSON). ClearKey with keys in the playlist (`kid:key`, JSON, or key only) – also on streams whose manifest only lists PlayReady/Widevine. Widevine and PlayReady license servers with headers (`url|Header=Value|…`); PlayReady falls back to Widevine on boxes without it, and Widevine retries at security level L3 when L1 fails |
| **Catch-up / archive** | Watch programmes that already aired, straight from the guide (↺). M3U `catchup` = `default` / `append` / `shift` / `flussonic` / `xc` with `catchup-source` templates (`${start}`, `{utc}`, `{duration:60}`, `{Y}-{m}-{d}` …), and Xtream Codes archives (server time zone aware). Also "watch from the start" for shows that are on now |
| **Audio, quality & subtitles** | While watching (hold OK, Menu, or tap → Audio & quality): pick the quality, audio language/track and subtitles or closed captions; CC button toggles captions. Picture size: fit, zoom or stretch. Optional maximum quality for slow connections |
| **Audio formats** | Bundled FFmpeg decoder plays Dolby Digital (AC3), Dolby Digital+ (E-AC3), DTS and TrueHD on boxes that can't – no more silent sports channels. Optional "decode all audio in software" setting |
| **Network & VPN** | Built-in **WireGuard VPN** (paste or load a config from any VPN provider; only Channelbox or the whole box), **HTTP / SOCKS5 proxy**, **encrypted DNS** (DNS-over-HTTPS: Cloudflare, Google, Quad9, AdGuard or custom) and a **user agent** setting – ways around ISP blocking |
| **Multi-view** | Watch 2 or 4 channels at once (Groups menu → Multi-view, or the green button). Sound follows the highlighted tile |
| **Mini player** | Picture-in-picture when you press Home (optional) or from the on-screen buttons |
| **Stream info** | The info banner shows resolution, frame rate, codec, audio format, bitrate, available caption languages and VPN status |
| **Recording** | Record what's on now (● / red button, or tap → Record) or schedule a programme from the guide. TS and HLS (incl. AES-128) channels; DRM and DASH channels can't be recorded. Recordings screen to play (pause/seek), stop and delete. Save to this box, a USB drive / SD card, a network share (SMB – NAS, Windows, Samba) or WebDAV (Nextcloud, Synology…) |
| **Zapping** | Channel ▲/▼, number entry, previous channel, now/next info banner with a progress bar |
| **Channel list** | Press OK while watching: channels by group with what's on now |
| **Favourites** | Hold OK on a channel in the list or guide |
| **Set-top box mode** | Optional start on boot, optional "use as home screen", resumes the last channel. Back goes TV → Guide → Groups → "Exit?"; the last step can also be set to never exit, or exit straight away |
| **Touch & mouse** | Tap the picture for on-screen buttons (Channels, Guide, CH±, Favourite, Settings, Exit); swipe up/down to change channel; tap anything in the list or guide |
| **Background refresh** | Playlist and guide reload every 6/12/24/48 hours, or only when you choose |

## Remote control

| Button | Watching TV | Channel list | Guide |
|---|---|---|---|
| ▲ / ▼, CH+ / CH− | Change channel | Move | Move (CH± = page) |
| ◀ | Open channel list | Previous group | Earlier |
| ▶ / Info | Show/hide info banner | Next group | Later |
| OK | Open channel list | Watch | On now: watch · finished: catch-up · future: record menu |
| Hold OK | Audio, subtitles & picture size | ★ favourite | Programme menu (watch / from start / record / favourite) |
| ● Record / red | Record what's on (again = stop) | – | Record / cancel the selected show |
| 0–9 | Go to channel number | Go to channel number | Jump to channel |
| Guide | Open guide | Open guide | Back to TV |
| Menu | Audio, subtitles & picture size | Open guide | Groups menu |
| Back | Open guide | Close | Groups menu → Back again: "Exit?" |
| Last / Recall | Previous channel | Previous channel | – |
| CC / Subtitles | Captions on/off | – | – |
| Green | Multi-view | – | – |

**Groups menu:** ▲▼ choose, OK select, ▶ or Back-to-guide. **Recordings** and **⚙ Settings** are at the bottom.
**Playing a recording or catch-up:** OK / ⏯ pause, ◀ ▶ (or ⏪ ⏩) skip 10 s back / 30 s ahead, Back returns to the list.

### Where recordings are saved

`Android/data/io.github.axon07.channelbox/files/recordings/` on the box's storage (`.ts`, or `.mp4` for fMP4 HLS). You can copy them off with a file manager. They're removed if the app is uninstalled. Scheduled recordings only happen if the box is on (not unplugged); a box in deep sleep may start late.

### Touch screen / mouse

Tap the picture to show the on-screen buttons. Swipe up for the next channel, down for the previous one. In the channel list and guide, tap to watch and press-and-hold to mark a favourite. In the guide, **◀ Now ▶** move through time, and tapping the small video goes back to full screen.

## Building

Requires Android Studio (Ladybug or newer) or JDK 17 plus the Android SDK.

```bash
git clone https://github.com/Axon07/Channelbox.git
cd Channelbox
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # parser tests
```

Every push also builds APKs on GitHub Actions (see the *Actions* tab → *channelbox-apks* artifact).

### Installing on a TV box

```bash
adb connect <box-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or upload the APK somewhere and use the *Downloader* app on the box.

## Releasing

Releases are built by GitHub Actions and signed with a permanent key, so the in-app updater can install each new
version over the old one. One-time setup (Windows PowerShell, JDK 17 installed):

```powershell
$env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-17*" | Select-Object -First 1).FullName
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore "$env:USERPROFILE\channelbox-release.jks" -alias channelbox -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Channelbox"
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\channelbox-release.jks")) | Set-Clipboard
```

Then in the repo: **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | paste (the keystore, copied to the clipboard by the last line) |
| `SIGNING_STORE_PASSWORD` | the password you typed for the keystore |
| `SIGNING_KEY_ALIAS` | `channelbox` |
| `SIGNING_KEY_PASSWORD` | the same password |

Keep a backup of `channelbox-release.jks` and its password somewhere safe – if it's lost, existing installs can't be
updated and everyone has to uninstall and reinstall.

To release: **Releases → Draft a new release → new tag `v0.2.0` → Publish**. The workflow sets the app version from the
tag, builds the signed APK and attaches it; installed apps will offer the update.

## Making it act like a cable box

1. **Settings → Start when the box turns on** (off by default).
   Also consider **Settings → Back button while watching TV → Open the guide (never exit)** so nobody exits by accident.
2. On Android 10 and newer, Android only lets an app open itself after boot if it has the *Display over other apps* permission. Use **Settings → Permission for start on boot**. Some TV firmware hides that screen; grant it over adb instead:
   ```bash
   adb shell appops set io.github.axon07.channelbox SYSTEM_ALERT_WINDOW allow
   ```
3. Optional: **Settings → Use as home screen**, then press Home and choose Channelbox → *Always*. The box then boots, and Home returns, straight into live TV.
   Google TV (Chromecast with Google TV, newer Onn/Walmart boxes) ignores third-party home screens unless the stock launcher is disabled over adb. That step is at your own risk:
   ```bash
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
   ```
   Undo with `pm enable`.

## Project layout

```
app/src/main/java/io/github/axon07/channelbox/
├── App.kt                 dependency container
├── MainActivity.kt
├── data/                  M3U, Xtream, XMLTV parsers, Room DB, settings, repository
├── player/TvPlayer.kt     ExoPlayer wrapper with auto-reconnect
├── system/                boot receiver, background refresh, home-screen/permission helpers
└── ui/                    Compose for TV screens: player overlays, guide, settings, setup
```

Stack: Kotlin, Jetpack Compose for TV, Media3 ExoPlayer, Room, DataStore, WorkManager, OkHttp, Coil. Min SDK 21 (Android 5.0).

## Roadmap

- [ ] Pause live TV (timeshift buffer)
- [ ] Record DASH channels; record to USB drive / NAS; series recording
- [ ] Reminders for upcoming programmes
- [ ] Multiple playlists, hide/rename/reorder channels and groups
- [ ] Parental PIN
- [ ] EPG search
- [ ] Enter playlist URL from a phone (QR code + local web page) instead of typing it with the remote
- [ ] VOD / series sections for Xtream
- [ ] Translations

## License

[GPL-3.0](LICENSE). You can use, change and share Channelbox freely, but anything you distribute that's built from it must stay open source under the same license.
