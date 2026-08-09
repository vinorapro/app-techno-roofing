# Design source files

Original artwork for the app. Drop the logo PNG in this folder.

This folder sits outside the Gradle build on purpose:

- it is **never packaged into the APK**, unlike `app/src/main/res/` or `app/src/main/assets/`
- `gradlew clean` only wipes `app/build/`, so nothing here is ever deleted by a build

Keep the highest-resolution original you have. Everything Android needs is generated
from it, and generation is lossy — you cannot go back up from a small file.

## Ideal source

| | |
|---|---|
| Format | PNG with transparency |
| Size | 1024 x 1024 (512 minimum) |
| Shape | Square canvas, logo centred |
| Padding | Keep the mark inside the middle ~66%, see below |

Anything smaller still works; it just limits the densities that can be generated
cleanly. Drop in whatever you have and it can be assessed from there.

## Why the padding matters

Android 8.0+ launcher icons are **adaptive**: the system crops them to whatever mask
the device uses (circle, squircle, rounded square) and animates them. Only the centre
~66% of the canvas is guaranteed to stay visible. A logo drawn edge-to-edge will have
its corners and edges clipped on most phones.

## What replaces what

Generating the launcher icon overwrites the default Android template art currently in
the project:

- `app/src/main/res/mipmap-*/ic_launcher.webp` and `ic_launcher_round.webp` — five
  densities, mdpi through xxxhdpi
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` —
  the adaptive icon definitions
- `app/src/main/res/drawable/ic_launcher_foreground.xml` and `ic_launcher_background.xml`

## Not the notification icon

`app/src/main/res/drawable/ic_notification.xml` is deliberately a flat white silhouette
and should **not** be replaced with the colour logo. Android masks notification small
icons to a single-colour shape, so a full-colour image renders as a grey blob in the
status bar. If the logo has a distinctive silhouette, a white-on-transparent version of
that shape can be made separately.
