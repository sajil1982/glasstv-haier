# Wallpaper generators

Each script renders a set of 1920×1080 wallpapers named `<period>-<weather>.jpg`
(period = dawn/day/dusk/night; weather = clear/cloudy/fog/rain/storm) that the
launcher picks from by time of day and the local weather. Drop the output into a
theme's `wallpapers/` folder and set `weather_wallpaper` true with a `latitude` /
`longitude`.

Needs Python 3 with `numpy` and `Pillow` (`pip install numpy pillow`).

- `weather_lake.py <out_dir>` — a Sahyadri-style lake scene, 4 lights × 5 weathers.
- `papercut.py <out_dir>` — layered paper-cut scenery (mountains, river, forest).
- `photo_timeofday.py <your-photo.jpg> <out_dir>` — grades ONE photo you supply into
  dawn / day / dusk / night (no weather variants, no bundled image).

Example:

```bash
python3 papercut.py ../../themes/papercut/wallpapers
```
