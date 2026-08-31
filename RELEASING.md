# Releasing Wave TV to the stores

Two stores, two artifacts, one codebase:

| Store | Artifact | Built by | Signed by |
|---|---|---|---|
| Google Play (Android TV) | `WaveTV.aab` | `build-aab.ps1` | `wave-tv.jks` as the **upload key**; Google re-signs for devices (Play App Signing) |
| Amazon Appstore (Fire TV) | `WaveTV.apk` | `build.ps1` | `wave-tv.jks`, then **Amazon re-signs** with its own certificate on publication |

Everything either console asks for is already in this repository:

- Listing text, questionnaire answers, reviewer instructions: `store/play/listing.md`, `store/amazon/listing.md`
- Icons, feature graphic, banner, background, 1920×1080 screenshots: `store/play/`, `store/amazon/`
- Privacy policy (both consoles require a URL): `PRIVACY.md` — push it, then use
  `https://github.com/mrain1p/Wave-TV/blob/main/PRIVACY.md`

## Before either submission

- [ ] **Verify the demo station.** The reviewer instructions point at
  `radio.yosemite.my`. Confirm it is up, public (no password), and playing —
  a reviewer who lands on a dead address will reject for "app does not
  function". If you'd rather not use your own station, substitute any public
  SUB/WAVE station and update both listing files.
- [ ] **Push `PRIVACY.md`** to GitHub so the policy URL resolves publicly.
- [ ] **Back up `wave-tv.jks` and its password** somewhere other than this
  machine. For Play it becomes the upload key (recoverable via support if
  lost, since Google holds the real signing key); for Amazon and for
  sideloaders it is still the identity of every future update.
- [ ] Build both artifacts at the release version (currently 1.4.0 / code 30 —
  bump both in `app/AndroidManifest.xml` for every store upload; Play refuses
  a reused versionCode):

  ```powershell
  powershell -ExecutionPolicy Bypass -File build.ps1
  powershell -ExecutionPolicy Bypass -File build-aab.ps1
  ```

## Google Play

**Account.** A Play Console developer account ($25 one-time,
https://play.google.com/console) with identity verification completed.
If the account is a **personal** account created after November 2023, Play
requires a closed test with **at least 12 testers opted in for 14 consecutive
days** before you can apply for production access — budget two-plus weeks of
calendar time for this. Organization accounts skip it.

**Create the app**, then work through the dashboard:

- [ ] *App access* → "All or some functionality is restricted" → paste the
  instructions from `store/play/listing.md`.
- [ ] *Ads* → No ads.
- [ ] *Content rating* → IARC questionnaire; expected answers in
  `store/play/listing.md`. Answer "unrestricted internet access: Yes".
- [ ] *Target audience* → 18 and over (simplest; avoids the Families policy
  track — the app is general-audience but nothing in it is child-directed).
- [ ] *News app* → No. *COVID-19 app* → No. *Data safety* → collects nothing,
  shares nothing (rationale in the listing file). *Government app* → No.
  *Financial features* → None. *Health* → None.
- [ ] *Privacy policy* → the GitHub `PRIVACY.md` URL.
- [ ] *Store listing* → name, short/full description, icon, feature graphic
  from `store/play/`; **Android TV section** → banner + the six TV
  screenshots.
- [ ] *Release → Advanced settings → Form factors* → confirm **Android TV**
  appears. The manifest (`leanback` required, `LEANBACK_LAUNCHER`, banner,
  no touchscreen requirement) already satisfies the TV checklist; because the
  app declares TV-only, the Play TV target-API rule applies (API 34 — already
  met) rather than the phone rule (API 36).
- [ ] *Release* → upload `WaveTV.aab`. Accept enrollment in **Play App
  Signing** when prompted (mandatory for new apps; your `wave-tv.jks` is
  registered as the upload key).
- [ ] Google runs an additional **TV quality review** for the TV form factor;
  turnaround is typically days, occasionally a couple of weeks.

**Likely review friction, and the answer to it.** A WebView-based app can be
flagged under "minimum functionality / webview spam". If that happens, appeal
with: Wave TV is a *client for a platform* (SUB/WAVE), not a wrapper for one
website — it ships pointing at no site, the user adds any number of stations
(including LAN-only ones no browser bookmark could replace on a TV), and the
native layer provides the station manager, now-playing metadata panel, audio
level meter, D-pad spatial-navigation engine, sleep timer, voice requests,
and private-station authentication. None of that exists on the sites it
loads.

## Amazon Appstore

**Account.** Free — https://developer.amazon.com, any Amazon account.

- [ ] *Add a New App → Android*.
- [ ] *App details / availability* → title, category, descriptions and
  feature bullets from `store/amazon/listing.md`; pricing: Free, all
  countries (or your pick).
- [ ] *Upload the APK* (`WaveTV.apk`). Amazon accepts APKs directly — no
  bundle needed. Let Amazon apply **DRM/re-signing** (default). Consequence
  worth knowing: the store copy is signed by Amazon, so a television that
  already has a *sideloaded* copy must uninstall it before the store copy
  will install (the station list does not carry over — it lives in
  app-private storage).
- [ ] *Device support* → Fire TV devices only; untick tablets and phones.
  minSdk 22 covers every Fire TV back to Fire OS 5.
- [ ] *Content rating* → questionnaire (no objectionable content; app can
  access the open internet). *Export compliance* → standard encryption only
  (platform TLS).
- [ ] *Privacy policy URL* → the GitHub `PRIVACY.md` URL.
- [ ] *Images* → from `store/amazon/`: 114 + 512 icons, 1280×720 Fire TV
  icon, 1920×1080 background, the six screenshots.
- [ ] *Testing instructions* → paste from `store/amazon/listing.md`.
- [ ] Submit. Amazon review is usually 1–3 days. If possible, smoke-test the
  APK on a real Fire TV stick first (`adb install WaveTV.apk`) — Amazon's
  automated tests exercise launch, D-pad focus, and Back-button behaviour,
  all of which this app handles, but a two-minute check beats a rejection
  cycle.

## After launch

- [ ] Update `README.md`'s Install section — it currently says the app is
  not on any store.
- [ ] Tag the release (`git tag v1.4.0`) and attach the sideload
  `WaveTV.apk` + SHA256 to a GitHub release, as before. Sideloading remains
  supported alongside the stores.
- [ ] For every subsequent release: bump `versionCode`/`versionName`, rebuild
  both artifacts, upload the `.aab` to Play and the `.apk` to Amazon. The
  stores update independently; neither touches sideloaded installs.

## Store-rule facts this repo relies on (as of August 2026)

- Play requires new apps to upload **App Bundles**, not APKs — hence
  `build-aab.ps1`.
- Play's target-API floor for **TV-form-factor apps is API 34**; the
  API 36 deadline of 31 Aug 2026 applies to phone/tablet apps. Amazon's
  floor for new Fire TV submissions is likewise API 34; Fire OS 16 devices
  will eventually want 36, so expect one targetSdk bump in the app's future.
- `uses-feature android.software.leanback required="true"` is Play *store
  filtering*, not an install gate — `adb install` on a phone still works, so
  the README's sideloading story is unchanged.
