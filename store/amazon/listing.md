# Amazon Appstore (Fire TV) listing — copy-paste fields

## App title

    Wave TV: SUB/WAVE player

## Short description (max 1200 chars, shown on device)

Wave TV puts SUB/WAVE internet radio stations on your Fire TV. Add any
station by address — public or on your own network — and get a native TV
interface: a station picker built for the remote, a now-playing panel with
cover art and the show on air, and full D-pad navigation of the station's own
web player. Song requests from the remote, voice dictation, a sleep timer,
and private-station sign-in are all supported. One permission (INTERNET), no
ads, no analytics, no accounts.

## Long description

Wave TV puts SUB/WAVE internet radio stations on your television.

Add any station by address — a public one on the internet or a private one on
your own network — and Wave TV gives it a native TV interface: a station
picker built for the remote, a full-height now-playing panel, and a D-pad
navigation layer that makes the station's own web player fully operable from
the couch.

The picker: add any number of stations (a station's name is adopted
automatically if you leave it blank); now-playing at a glance with cover art,
track, artist, album, and the show or DJ on air; a level meter that moves
only while sound is actually playing; Light, Dark, or Station theme, the last
repainting the screen in the colours of whatever is on air.

In the player: full D-pad navigation across the station's real controls with
a visible focus ring; song requests from the remote, including voice
dictation via the TV's own keyboard; a sleep timer from one to six hours;
hold OK (or press Menu) for the menu, so nothing requires a button your
remote might not have.

Private stations work too — both the members-only gate and HTTP basic auth —
and http:// as well as https:// addresses are accepted, so a station on your
own LAN works without a certificate.

One permission: INTERNET. No analytics, no telemetry, no ads, no accounts.
Nothing is transmitted anywhere except to the stations you add.

Wave TV is an unofficial, open-source community player for stations built on
the SUB/WAVE platform. It is not affiliated with or endorsed by the SUB/WAVE
project. It ships with no stations; you add the ones you listen to. Source
code: https://github.com/mrain1p/Wave-TV

## Product feature bullets

- Native station picker driven entirely by the Fire TV remote
- Now-playing panel: cover art, track, artist, album, and the show or DJ on air
- Full D-pad navigation of each station's own web player, with a visible focus ring
- Song requests by remote, including voice dictation
- Sleep timer, one to six hours
- Works with private stations (members gate and basic auth) and LAN addresses
- One permission (INTERNET); no ads, no analytics, no accounts

## Other fields

| Field | Value |
|---|---|
| Category | Music & Audio (or Entertainment) |
| Privacy policy URL | https://github.com/mrain1p/Wave-TV/blob/main/PRIVACY.md |
| Contains ads | No |
| In-app purchases | No |
| Uses Amazon device permissions beyond INTERNET | No |
| Export compliance / encryption | Uses only standard encryption (HTTPS/TLS via the platform) — qualifies for the standard exemption |
| Device support | Fire TV devices (all generations; minSdk 22 covers Fire OS 5+). Untick tablets/phones — this is a TV app |

## Graphics (files in this folder)

| Slot | File |
|---|---|
| Small icon 114×114 | `icon-114.png` |
| Large icon 512×512 | `icon-512.png` |
| Fire TV icon 1280×720 | `firetv-icon-1280x720.png` |
| Background image 1920×1080 | `background-1920x1080.png` |
| Screenshots 1920×1080 (3–10) | `screenshots/*.png` (six) |

## Testing instructions (for the review team)

The app ships with no stations preloaded — the user adds the station(s) they
listen to, like a podcast client. To test:

1. Open the app and select "+ Add station".
2. Enter this public demo station address: radio.yosemite.my
   (leave the name blank; the app adopts the station's own name).
3. Press Select on the station to tune in. Audio plays; the D-pad navigates
   the player. Hold Select for the in-player menu (sleep timer, switch
   station, reload, exit).

No account or password is required for the demo station.
