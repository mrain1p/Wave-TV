# Google Play listing — copy-paste fields

## App name (max 30 chars)

    Wave TV: SUB/WAVE client

(24 chars. Plain "Wave TV" also fits but is easier to collide with an
existing listing; the suffix says what it is. "client" rather than "player"
because the app is the screen in front of somebody's own server, and that is
the distinction store review cares about.)

## Short description (max 80 chars)

    Remote-friendly client for your own SUB/WAVE internet radio server.

(67 chars.)

## Full description (max 4000 chars)

Wave TV is a television client for SUB/WAVE, an open-source internet radio
server that people run on their own hardware. If you operate a SUB/WAVE
station, or listen to one you have been given the address of, this app puts it
on your TV and makes it work from the remote.

It is a client in the same sense as a Plex, Jellyfin or Subsonic app: the
server belongs to whoever runs it, and this is the screen in front of it.

WHAT IS AND IS NOT INCLUDED
• No content ships with this app. There is no catalogue, no directory, no search, and no browse or recommendation surface
• No station list is downloaded from anywhere. The list is empty on first launch and only ever holds server addresses entered on the device
• No download capability of any kind: one permission (INTERNET), no storage access, and no handoff to an external download tool
• Once a station is tuned, the player declines to navigate to any host other than that station's own
• What a SUB/WAVE station broadcasts, and the rights to broadcast it, are the responsibility of whoever operates that station

THE PICKER
• Add the SUB/WAVE servers you listen to; a station's name is adopted automatically if you leave it blank
• Now-playing at a glance: cover art, track, artist, album, and the show or DJ on air
• A level meter that moves only while sound is actually playing, so you can see a stalled stream
• Light, Dark, or Station theme — the last repaints the screen in the colours of whatever is on air
• A green lamp marks each station that is answering

IN THE PLAYER
• Full D-pad navigation across the station's real controls, with a visible focus ring — decorative targets are skipped, so focus only lands where OK does something
• Song requests from the remote, including voice dictation via the TV's own keyboard
• Sleep timer, one to six hours
• Hold OK for the menu — nothing requires a Menu button, because not every remote has one

PRIVATE SERVERS AND PRIVACY
• Works with private stations: both the members-only gate and HTTP basic auth
• http:// and https:// both permitted, so a server on your LAN works without a certificate
• One permission: INTERNET. No analytics, no telemetry, no ads, no accounts. Nothing is transmitted anywhere except to the servers you add

Wave TV is an unofficial, open-source community client for the SUB/WAVE
platform. It is not affiliated with or endorsed by the SUB/WAVE project. It
ships with no stations; you add the server you listen to. Source code:
https://github.com/mrain1p/Wave-TV

## Other listing fields

| Field | Value |
|---|---|
| Category | Music & Audio |
| Tags | radio, music streaming |
| Contains ads | No |
| In-app purchases | No |
| Privacy policy URL | https://github.com/mrain1p/Wave-TV/blob/main/PRIVACY.md |
| Contact email | dev.yosemite@gmail.com |

## Graphics (files in this folder)

| Slot | File |
|---|---|
| App icon 512×512 | `icon-512.png` |
| Feature graphic 1024×500 | `feature-graphic-1024x500.png` |
| TV banner 1280×720 | `tv-banner-1280x720.png` |
| TV screenshots (16:9) | `screenshots/` — pending re-capture, see `docs/screenshots.md` |

## App access → "All or some functionality is restricted" → instructions

The app ships with no stations preloaded — the user adds the station(s) they
listen to, like a podcast client. To review:

1. Open the app and select "+ Add station".
2. Enter this public demo station address: radio.yosemite.my
   (leave the name blank; the app adopts the station's own name).
3. Press OK on the station to tune in. Audio plays; the D-pad navigates the
   player. Hold OK for the in-player menu (sleep timer, switch station, etc.).

No account or password is required for the demo station.

## Data safety questionnaire

- Does your app collect or share any of the required user data types? → **No**
- Is all of the user data collected by your app encrypted in transit? → (not asked once "No" above)
- Do you provide a way for users to request that their data is deleted? → (not asked)

The app has no analytics/telemetry/ads SDKs; the station list and any saved
station password live only in app-private storage on the device and are sent
only to the station the user added. That is a direct user→website connection
(like a browser), not developer collection.

## Content rating questionnaire (IARC) — expected answers

- Category: Utility / entertainment app
- Violence, sexuality, profanity, drugs, gambling: No
- Does the app allow users to interact or exchange content? No
- Does the app share user's location? No
- Does the app allow unrestricted internet access (e.g. a browser)? **Yes**
  (the user can enter any station URL, which loads in a WebView)

Expect the "Unrestricted Internet" interactive element note on the rating.
Answer truthfully — misdeclaring this gets ratings revoked later.
