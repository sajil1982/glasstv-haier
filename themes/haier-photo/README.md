# haier-photo

The Haier Google TV setup from glasstv's own testing: one row of monochrome app tiles, a 12-hour clock with the date,
and a **photo wallpaper that follows the time of day** (dawn, day, dusk, night, switched at your local sunrise
and sunset).

No photo ships with this theme — use your own (a wide sunset or golden-hour shot works best: the script treats
it as the dusk picture and grades the other three from it):

```sh
pip install numpy pillow
python3 tools/wallpaper-generators/photo_timeofday.py my-photo.jpg themes/haier-photo/wallpapers
```

That writes `dawn-clear.jpg`, `day-clear.jpg`, `dusk-clear.jpg` and `night-clear.jpg`. Then set your
location in `launcher/app/src/main/res/values/config.xml` (`latitude` / `longitude`, used for sunrise and
sunset) and push the theme:

```sh
tools/theme.sh themes/haier-photo          # or tools\theme.ps1 themes\haier-photo on Windows
```

`weather_wallpaper` is on by default; with only the `-clear` pictures the launcher uses them in every weather.

## Haier extras in this theme

- HaiCast and Media Player use the monochrome screen-with-cast-waves and camera tiles.
- The stock Haier launcher and TV centre are hidden from the app list; `live_tv_package` keeps the Live TV button working.
