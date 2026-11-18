# glasstv-haier

> **Tuned for Haier Google TV (CVTE / MediaTek Google TV sets).** The launcher works on any Android TV 8.0+, but this repo's
> debloat lists and notes target this device family. For other devices see the
> upstream project, [CleanYourTV](https://github.com/Gathin23/cleanyourtv).

A clean, glass-styled home screen for Android TV, Google TV and Fire TV — plus the
tooling to strip the ads and bloatware off the stock system first. Point it at a TV
or stick over ADB, remove the manufacturer's telemetry and ad services, and replace
the busy stock launcher with a quiet home screen that looks like an Apple TV.

> **glasstv is a fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv)** (MIT),
> with the launcher restyled after [CleanFrame](https://github.com/nthnclrk/CleanFrame)
> and extended to work on Fire TV. See [CREDITS.md](CREDITS.md). No root required.

## What the home screen looks like

- A full-screen photo or paper-cut background, or pitch black — your choice.
- A frosted-glass dock of the apps you pick; everything else sits in a grid below.
- App tiles are **white monochrome marks with the name underneath**, all sized to
  match, so a page of mismatched logos becomes one calm row.
- Frosted pill / circle buttons for Inputs, Wi-Fi and Settings; a large thin clock,
  the date and a greeting.
- Built for a 1 GB stick: no blur video, no network polling, light on memory.

## What glasstv adds over CleanYourTV

- **CleanFrame look** — Manrope type, glass dock, white tiles, the top bar above.
- **Monochrome tiles for any app** — from bundled marks (Netflix, YouTube, Plex,
  Prime Video, Apple TV, Play Store…) or by desaturating an app's own icon to white;
  per-app icon cropping (`mono_icon_crops`) to drop a wordmark and keep just the logo.
- **Dynamic wallpapers** that follow the time of day and local weather (via the free
  Open-Meteo API), with three generators in `tools/wallpaper-generators/`:
  a weather-lake scene, layered paper-cut scenery, and a grader that turns one photo
  into dawn / day / dusk / night.
- **Fire TV support**, where the stock home can't be replaced:
  - a Home-button redirect (accessibility service) so the remote's Home lands here;
  - a landscape hold for phone apps that only run portrait;
  - an on-screen **pointer** (arrows move it, OK taps) for touch-only apps;
  - top-bar buttons that can open apps with no launcher entry (e.g. the Live guide).

## Requirements

- A TV, stick or box running **Android 8.0+ (API 26)** — Fire OS 7/8, Google TV, etc.
  (Fire OS 5 / Android 5 devices are below minSdk and cannot run the launcher.)
- `adb` on your computer, with ADB debugging enabled on the device.
- To build the launcher yourself: **JDK 17** and the **Android SDK (API 34)**.

## Quick start

```bash
# 1. Debloat (optional): disable manufacturer telemetry / ad services. Reversible.
#    See tools/ and examples/<your-device>/ for tested package lists.
tools/disable.sh examples/haier-matrixtv-de/debloat.txt

# 2. Build and install the launcher, set it as Home:
cd launcher && ./build-and-install.sh        # or build-and-install.ps1 on Windows
```

On **Fire TV**, after installing, enable the "Home button" accessibility service so the
Home key opens glasstv (Settings → Accessibility), then set it as the default home.
Full per-device notes are in [`docs/`](docs) and [`examples/`](examples).

## Configuring the look

The home screen reads a theme — dock apps, columns, wallpaper, colours — from
`theme/config.json` or the defaults in
`launcher/app/src/main/res/values/config.xml`. Highlights:

| Key | What it does |
|---|---|
| `dock_apps` | Package names in the dock, left to right |
| `columns` | Dock and grid columns (4–7) |
| `mono_app_icons` | Apps drawn as white marks; `*` means every app |
| `mono_icon_crops` | Keep only part of an icon (drop a wordmark) |
| `latitude` / `longitude` | Location for sunrise/sunset + weather (blank = off) |
| `weather_wallpaper` | Pick `wallpapers/<period>-<weather>.jpg` by time + weather |
| `force_landscape_apps` | Hold the screen landscape for portrait-only apps (Fire TV) |
| `pointer_apps` | Show the remote pointer inside touch-only apps (Fire TV) |

`force_landscape_apps` and `pointer_apps` can also go in the theme's `config.json`, so an app can be
added without rebuilding.

See [`themes/README.md`](themes/README.md) for every key, and
[`tools/wallpaper-generators/`](tools/wallpaper-generators) to make wallpaper sets.

## License

MIT — see [LICENSE](LICENSE). glasstv keeps CleanYourTV's MIT license and copyright,
and credits the fonts, icons and CleanFrame design in [CREDITS.md](CREDITS.md).
Brand logos are trademarks of their owners and are used only to label installed apps.
