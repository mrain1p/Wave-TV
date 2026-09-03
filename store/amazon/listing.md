# Amazon Appstore (Fire TV) listing — copy-paste fields

## App title

    Wave TV: SUB/WAVE client

## Short description (max 1200 chars, shown on device)

Wave TV is a remote-friendly client for SUB/WAVE, the open-source self-hosted
internet radio server. Enter the address of a station you run yourself, or a
public non-commercial one you have the address of, and Wave TV gives it a
native Fire TV interface: a station picker built for the remote, a now-playing
panel with cover art and the show on air, and full D-pad navigation of that
station's own player. Song requests from the remote, voice dictation, a sleep
timer and private-station sign-in are supported. No catalogue and no stations
are included — the app starts empty. One permission (INTERNET), no ads, no
analytics, no accounts. This is a third-party community app with no
affiliation with SUB/WAVE.

## Long description

Wave TV is a television client for SUB/WAVE, an open-source internet radio
server that people run on their own hardware. If you operate a SUB/WAVE
station of your own, or listen to a public non-commercial one you have the
address of, this app puts it on a Fire TV and makes it work from the remote.

It is a client, in the same sense as a Plex, Jellyfin or Subsonic app: the
server belongs to whoever runs it, and this is the screen in front of it.

WHAT IS AND IS NOT INCLUDED

No content ships with this app. There is no catalogue, no directory, no
search, no browse or recommendation surface, and no station list downloaded
from anywhere. The station list is empty on first launch and only ever holds
server addresses entered on the device. What a SUB/WAVE station broadcasts,
and the rights to broadcast it, are the responsibility of whoever operates
that station.

The app has no ability to download or store media. It declares one Android
permission, INTERNET. There is no storage permission, no download code, and
no handoff to any external download tool. Once a station is tuned, the player
refuses to navigate to any host other than that station's own, so it cannot
be used to reach third-party sites.

THE PICKER

Add the SUB/WAVE servers you listen to (a station's name is adopted
automatically if you leave it blank); now-playing at a glance with cover art,
track, artist, album, and the show or DJ on air; a level meter that moves only
while sound is actually playing; Light, Dark, or Station theme, the last
repainting the screen in the colours of whatever is on air.

IN THE PLAYER

Full D-pad navigation across the station's real controls with a visible focus
ring; song requests from the remote, including voice dictation via the TV's
own keyboard; a sleep timer from one to six hours; hold OK (or press Menu) for
the menu, so nothing requires a button your remote might not have.

Private stations work too — both the members-only gate and HTTP basic auth —
and http:// as well as https:// addresses are accepted, so a SUB/WAVE server
on your own LAN works without a certificate.

One permission: INTERNET. No analytics, no telemetry, no ads, no accounts.
Nothing is transmitted anywhere except to the servers you add.

CONTENT AND RIGHTS

Wave TV supplies no content and no way to obtain any. It has no catalogue, no
directory, no search and no download capability. It is meant for a station you
run yourself, or one whose operator has given you access, playing material you
own, have licensed, or that is free to broadcast. Whoever operates a station is
responsible for what it broadcasts and for holding the rights to broadcast it.

This project does not condone, support or assist copyright infringement, and
the app is not built in a way that would help: nothing to search, nothing to
download or record, and no way to reach any host other than the station you
tuned.

Wave TV is an unofficial, open-source community client for the SUB/WAVE
platform. It is not affiliated with or endorsed by the SUB/WAVE project. It
ships with no stations; you add the server you listen to. Source code:
https://github.com/mrain1p/Wave-TV

## Product feature bullets

- Client for SUB/WAVE, the open-source self-hosted internet radio server
- Ships empty: no catalogue, no directory, no search, no included stations
- Native station picker driven entirely by the Fire TV remote
- Now-playing panel: cover art, track, artist, album, and the show or DJ on air
- Full D-pad navigation of your server's own player, with a visible focus ring
- Song requests by remote, including voice dictation; sleep timer one to six hours
- Works with private servers (members gate and basic auth) and LAN addresses
- One permission (INTERNET); no downloads, no ads, no analytics, no accounts

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

## Release notes

Wave TV 1.5.0

New artwork
- Redrawn app icon and launcher tile, including an adaptive icon for launchers
  that mask or animate it.
- New Fire TV home-screen banner and new store graphics throughout.

Listing
- Rewritten to describe the app as what it is: a client for a SUB/WAVE radio
  server you run yourself, or a public non-commercial station you have the
  address of. It ships with no stations, no catalogue and no search.

No functional changes: the picker, the player and the remote handling are
unchanged from 1.4.1.

## Testing instructions (for the review team)

Enter this address at "+ Add station" — leave the name blank:

    live.yosemite.my

That is a SUB/WAVE station operated by this developer, provided so the app
can be reviewed without setting a server up. Press Select to tune in; audio
plays and the D-pad navigates the player. Hold Select for the in-player menu.
No account or password is needed.

(Enter the host exactly as above, with no path and no scheme prefix typed
into the field - use the https:// button beside it.)

Three things this makes checkable, against the previous rejection:

- **The app ships empty.** On first launch the station list is blank. There is
  no catalogue, directory, search or browse surface, and no station list is
  fetched from anywhere — nothing can play until an address is typed in.
- **The address field is not a general-purpose URL bar.** It accepts a host
  and an optional path only, and rejects query strings, fragments, userinfo
  and spaces, because the value is concatenated with SUB/WAVE API paths such
  as "/api/now-playing". An arbitrary URL cannot be expressed in it.
- **It cannot download anything.** One Android permission, INTERNET — the
  whole of `app/AndroidManifest.xml`. No storage permission, no download or
  recording code, no torrent library, no handoff to any downloader.

After a station is tuned, selecting a link that points off that station's host
is declined and shows "That link leaves the station". The app cannot be
navigated to a third-party site.

Open source under MIT: https://github.com/mrain1p/Wave-TV
