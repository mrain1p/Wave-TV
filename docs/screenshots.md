# Capturing the screenshots

The six frames all showed a commercially released single: its cover, its title,
its artist and album, its genre tags — and, in the player frames, the page tint
that the Station theme samples out of that cover. That reproduced artwork this
repository has no licence to, and presented the app as a way to hear commercial
music. Amazon's content review read it exactly that way.

**Current state.** The three player frames (`player`, `request`, `keyboard` /
`04`–`06`) are withheld. The three picker frames (`01`–`03`) are still the old
captures, kept so the README and the store listing are not bare — Amazon's
minimum is three. They are *not* clean: each still shows the same cover as a
small thumbnail with the track title beside it. Replace all six together.

## The rule

Shoot against a station playing material its operator holds the rights to:
original programming, a talk or mix show, or explicitly licensed or
public-domain music. `art/covers/` has original artwork, MIT-licensed with
this repository, for whatever the now-playing panel ends up displaying.

If a frame would show a record sleeve you did not make and cannot license, it
is the wrong frame.

## The six frames

Capture at **1920×1080**, one per row below, on a real television.

| File | What it shows |
| --- | --- |
| `01-picker-light.png` | The picker in its light theme, a station tuned, now-playing panel filled |
| `02-picker-dark.png` | The same picker in dark theme |
| `03-picker-minimal.png` | Minimal mode — `NP` pressed, panel folded to a strip |
| `04-player.png` | The station's player, D-pad focus ring visible |
| `05-request.png` | The request drawer, opened by remote |
| `06-keyboard.png` | The on-screen keyboard over the request slip |

## Where they go

- `store/amazon/screenshots/` and `store/play/screenshots/` — the same six
  PNGs; both stores took an identical set last time.
- `docs/` — the same frames as JPG (`picker-light.jpg`, `picker-dark.jpg`,
  `picker-minimal.jpg`, `player.jpg`, `request.jpg`, `keyboard.jpg`) for the
  README table, which is commented out until they exist.

Screenshots must be genuine captures. Do not composite, retouch a cover into a
frame, or mock the interface up — a store listing is a representation that the
app looks like that.
