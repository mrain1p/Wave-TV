# Wave TV — Privacy Policy

*Effective 31 August 2026*

Wave TV is a television client for SUB/WAVE, an open-source internet radio
server that people run on their own hardware. This policy covers the Wave TV
application itself (`com.wave.tv`), as distributed on Google Play, the Amazon
Appstore, and as a sideloaded APK. It does not cover any SUB/WAVE server you
connect it to, which is operated by somebody else.

## What we collect

**Nothing.** Wave TV has no analytics, no telemetry, no crash reporting, no
advertising, and no account system. The application transmits nothing to the
developer and contains no service operated by the developer. Its only Android
permission is `INTERNET`.

## What stays on your device

- **Your station list** — the names and addresses of stations you add — is
  stored in the application's private storage on the television. It never
  leaves the device. Platform backup (`allowBackup`) is disabled, so the
  operating system does not copy it off the device either. Uninstalling the
  app deletes it.
- **A saved station password**, if you tick *Remember on this device* on a
  private station's sign-in prompt, is stored Base64-encoded in the same
  private storage. It is sent only to the station it belongs to, and can be
  removed at any time with *long-press OK → Forget saved password*.
- **A station's web player** may use its own browser storage (for example the
  members-only gate keeps its password in the player's `localStorage`),
  exactly as it would in a desktop browser. Wave TV does not read, copy or
  transmit that storage.

## What is sent to the stations you add

When you tune a station, Wave TV connects directly to that station's address
to load its web player, stream its audio, and poll its public now-playing
API. Those connections carry what any browser visit carries (your IP address,
standard HTTP headers) and go **only to the station you selected** — never to
the developer or to any third party of ours. Each station's operator is
responsible for their own service and any policy of theirs.

If you add a station by `http://` (useful on a private LAN), that traffic —
including any password typed into that station's login form — is unencrypted
in transit. Use `https://` for stations reached across the internet.

## Voice input

Song-request dictation is delegated to the television's own system speech
recogniser, which asks for its own consent and is governed by the device
manufacturer's privacy policy (Google or Amazon). Wave TV has no microphone
permission and receives only the finished text.

## Children

Wave TV is a general-audience utility. It has no accounts, no social
features, no advertising, and collects no data from anyone, including
children.

## Changes

If a future version changes any of the above, this document will be updated
and the change noted in the release notes. Since the app collects nothing,
there is very little that could change.

## Contact

Questions about this policy: open an issue at
<https://github.com/mrain1p/Wave-TV/issues>, or email
<dev.yosemite@gmail.com>.
