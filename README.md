# Wave TV

**An unofficial community player for [Subwave](https://github.com/perminder-klair/subwave) stations on Android TV and Fire TV.**

My family kept asking how they could listen to my station on the TV, so I put
this together. It's a small APK — about 50 KB — that is essentially a **station
picker wrapped around your station's own web player**. You add your stations
once, pick one with the remote, and the station's real web player loads
fullscreen: your skin, your theme, your artwork, exactly as they look in a
browser, just driven by a D-pad instead of a mouse.

No stations are bundled. It ships empty and plays whatever you point it at.

---

## Screenshots

![Wave TV station picker on a TV](docs/station-picker.jpg)

> **The only screen Wave TV draws itself.** A native station list showing the
> now-playing track, cover art and a play/pause control, polled from the
> station's public API. Station addresses are deliberately never displayed
> here. The theme toggle (☀/☾) and the sleep-timer chip sit in the masthead.
> *Station name blurred — it's my own station.*

![A Subwave web player running fullscreen on a TV](docs/player.jpg)

> **Everything past the picker is Subwave's interface, not mine.** This is the
> station's unmodified web player rendered fullscreen on the TV — the masthead,
> typography, waveform, DJ commentary line and side rail are all Subwave's
> design and the station operator's chosen theme. Wave TV only adds D-pad focus
> navigation on top of it. *Station branding blurred.*

![Making a song request with the remote](docs/request.jpg)

> **Song requests from the couch.** Subwave's own request drawer, opened with
> the remote. Type with the on-screen keyboard, or press Play/Pause while the
> box is focused to dictate the request by voice. *Station branding blurred.*

---

## What it does

- **Add your own stations** and listen through their real web player, custom
  themes and all. Nothing is provided by default.
- **Song requests by remote**, including voice — either your remote's keyboard
  or its voice input.
- **Fully navigable with a TV remote.** D-pad spatial navigation, a visible
  focus ring, and decorative things (cover art, scrub bars, volume sliders)
  skipped so focus only lands where it's useful.
- **Sleep timer, 1–6 hours.** Defaults to 6 and stops the stream when it
  expires, so a forgotten TV doesn't stream all night.
- **Light / Dark / Station theme** for the picker screen — "Station" tints it
  with the colours of whatever is currently on air.
- **Works with LAN stations.** Both `https://` and `http://` addresses are
  allowed, so a station on your own network works without a certificate.
- **Handles private stations** — both Subwave's in-page "members only" gate and
  HTTP basic auth.

## Install

Wave TV is not on any app store. You sideload it.

1. **Download `WaveTV.apk`** from the
   [Releases](../../releases) page and check its SHA256 against the one listed
   there before you install it.
2. **Enable sideloading on the TV:**
   - *Google TV / Android TV:* Settings → System → About → tap Build 7 times,
     then Settings → System → Developer options → **USB debugging / Network
     debugging**.
   - *Fire TV:* Settings → My Fire TV → Developer options → **Apps from Unknown
     Sources**.
3. **Get the file onto the TV.** Any of these work:
   - `adb connect <tv-ip>` then `adb install WaveTV.apk`
   - the **Send Files to TV** app (what I use — no PC needed)
   - the **Downloader** app on Fire TV, pointed at any URL serving the APK
4. Open **Wave TV** from the launcher and add your first station.

## Adding a station

Press **+ Add station** (or Menu → Add). Enter the address — a LAN address like
`192.168.1.50:7700` or a public one like `radio.example.com`. The name is
optional; leave it blank and Wave TV asks the station what it's called.

The TV has to be on the same network as any station you add by LAN address.

## Remote controls

| Button | Action |
|---|---|
| D-pad | Move focus between the player's controls |
| OK / Select | Activate the focused control (tune in, open drawers, chips…) |
| Back | Close an open drawer; press twice for the station list (audio keeps playing) |
| Play/Pause | Toggle tune-in — or, with a text field focused, **start voice dictation** |
| Stop | Mute / unmute |
| Menu (☰) | Options: Voice request · Switch station · Reload · Exit |
| Search / mic | Voice request, if your remote routes it to apps |

On the station list: **OK** tunes in, **long-press OK** offers Edit / Remove /
Forget saved password, and **Menu** opens settings.

## Privacy and permissions

- **One permission: `INTERNET`.** That's the entire list. No storage, no
  microphone permission of its own (voice dictation is handed to the system
  recogniser, which prompts you itself), no location, no accounts.
- **No analytics, no telemetry, no crash reporting, no ads.** Nothing is sent
  anywhere except to the station you chose.
- **No cloud backup.** `allowBackup` is off, so your station list is never
  copied off the device.
- **Everything stays local.** Your stations live in the app's private storage
  and are gone when you uninstall it.

## Please read before you install

**On passwords and your home network.** Wave TV can open stations that are
private or sit behind HTTP basic auth, which means you may end up typing a
password into it with your remote. Be aware of what that involves:

- If you tick **"Remember on this device"** at a basic-auth prompt, the
  username and password are Base64-encoded and written to the app's private
  storage on the TV. Base64 is *encoding, not encryption* — it is not a secret
  from anyone with real access to the device. Leave the box unticked if that
  matters to you, and use **long-press OK → Forget saved password** to clear it.
- Subwave's own "members only" gate stores its password in the web player's
  `localStorage`, exactly as it would in a desktop browser. Wave TV doesn't
  touch it.
- **`http://` is permitted** so LAN stations work. Traffic to an `http://`
  station — including a password typed into its login form — is unencrypted on
  your network. That's fine for a box on your own LAN and a bad idea over the
  open internet. Use `https://` for anything reachable from outside your house.
- Don't reuse an important password on a hobby radio station.

**On how this was made.** This was a solution to a problem I had, not a
professional product. I'm not a developer — this was built largely with AI
assistance, with some sensible checks along the way. It is a WebView shell
around someone else's web player, and it is only as trustworthy as that makes
it sound. The source is all here, it's small enough to read in a sitting, and
you can build it yourself rather than trusting my binary.

**Only install it if you're comfortable sideloading an APK from a stranger on
the internet.** That's a real judgement call and I'd rather you made it with
your eyes open.

**This is not an official Subwave app** and is not affiliated with or endorsed
by the Subwave project. It's a community player built by a user of it. All
station branding, theming and player interface shown in the screenshots belong
to Subwave and the station operator.

## Building from source

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

Needs JDK 17 (Eclipse Adoptium) and an Android SDK at `C:\Android` with
`build-tools` and `platforms;android-35`. There's no Gradle — the script drives
`aapt2 → javac → d8 → zipalign → apksigner` directly and prints the SHA256 of
the result.

On the first run it generates a signing keystore, `wave-tv.jks`. Keep it and
back it up: without it you can never ship an update that installs over an
existing copy. The password is read from the `WAVETV_KEYSTORE_PASS` environment
variable, or prompted for — it is deliberately not stored in this repo, and
neither the keystore nor the built APK is ever committed.

## How it works

- `app/AndroidManifest.xml` — leanback (TV) manifest, `INTERNET` only, cleartext
  HTTP allowed for LAN stations
- `app/java/com/wave/tv/MainActivity.java` — the whole app: station list,
  WebView shell, remote key handling, now-playing polling, speech-to-text
- `app/assets/tvhelper.js` — injected into the page to add D-pad spatial
  navigation and the JS ↔ native bridge
- `app/res/` — launcher icon, TV banner, theme

Two things worth knowing if you read the source. The WebView is forced to a
1280 px CSS viewport, because TV pixel density otherwise makes every Subwave
skin fall back to its cramped mobile breakpoints. And the on-screen keyboard is
suppressed until you actually press OK on a field — focus alone never opens it,
which is what makes D-pad navigation bearable.

The Android package id is `com.wave.tv`. Earlier private builds used
`com.subwave.tv`; if you have one of those installed, uninstall it first — the
two are separate apps to Android and your old station list won't carry over.

## Licence

No licence chosen yet — treat it as "look, learn, and build your own copy."
Open an issue if you want something more permissive.
