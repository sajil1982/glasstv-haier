package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Weather wallpapers: picks wallpapers/&lt;period&gt;-&lt;weather&gt;.jpg from the theme's collection, where period is
 * dawn, day, dusk or night (from the sun at config.xml latitude/longitude) and weather is clear, cloudy, fog, rain or
 * storm (Open-Meteo's current weather code for the same coordinates, no key, fetched at most every 30 minutes while
 * Home is up). A collection without such names, or config.xml weather_wallpaper false, keeps the normal rotation.
 */
final class WeatherWallpaper {
    static final long REFRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final long STALE_MS = 3 * 60 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;

    private static volatile String condition;
    private static volatile long fetchedAt;
    private static volatile long attemptedAt;
    private static volatile boolean fetching;
    private static boolean loaded;

    private WeatherWallpaper() {}

    /** True when the collection holds at least one weather picture (day-clear.jpg and the like). */
    static boolean applies(List<File> files) {
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.matches("(dawn|day|dusk|night)-(clear|cloudy|fog|rain|storm)\\.(jpe?g|png|webp)")) return true;
        }
        return false;
    }

    static String period(int minute, int sunrise, int sunset) {
        if (minute >= sunrise - 40 && minute < sunrise + 75) return "dawn";
        if (minute >= sunrise + 75 && minute < sunset - 75) return "day";
        if (minute >= sunset - 75 && minute < sunset + 40) return "dusk";
        return "night";
    }

    /** WMO weather code to the five pictures. Snow, rare where this was made, shows as cloudy. */
    static String fromCode(int code) {
        if (code <= 1) return "clear";
        if (code <= 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if (code >= 95) return "storm";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "rain";
        return "cloudy";
    }

    /** The weather to show now, or null before the first answer (and when the last one is hours old). */
    static synchronized String condition(Context context, String latitude, String longitude) {
        SharedPreferences prefs = context.getSharedPreferences("weather_wallpaper", Context.MODE_PRIVATE);
        if (!loaded) {
            loaded = true;
            condition = prefs.getString("condition", null);
            fetchedAt = prefs.getLong("fetched", 0);
        }
        long now = System.currentTimeMillis();
        if (!fetching && now - fetchedAt > REFRESH_MS && now - attemptedAt > RETRY_MS
                && !latitude.trim().isEmpty() && !longitude.trim().isEmpty()) {
            attemptedAt = now;
            fetching = true;
            fetch(prefs, latitude.trim(), longitude.trim());
        }
        return now - fetchedAt < STALE_MS ? condition : null;
    }

    private static void fetch(SharedPreferences prefs, String latitude, String longitude) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + latitude
                        + "&longitude=" + longitude + "&current=weather_code&timezone=auto");
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", "CleanYourTV-launcher");
                if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) > 0 && out.size() < 65536; ) out.write(buf, 0, n);
                }
                int code = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8))
                        .getJSONObject("current").getInt("weather_code");
                String cond = fromCode(code);
                long now = System.currentTimeMillis();
                condition = cond;
                fetchedAt = now;
                prefs.edit().putString("condition", cond).putLong("fetched", now).putInt("code", code).apply();
                Log.i("TvHome.Weather", "weather code " + code + " -> " + cond);
            } catch (Exception e) {
                Log.w("TvHome.Weather", "weather fetch failed: " + e);
            } finally {
                if (c != null) c.disconnect();
                fetching = false;
            }
        }, "weather-wallpaper").start();
    }

    /** The picture for this period and weather, falling back to cloudy, then clear; null when the theme has neither. */
    static File pick(List<File> files, String period, String cond) {
        String[] tries = {period + "-" + (cond != null ? cond : "clear"), period + "-cloudy", period + "-clear"};
        for (String want : tries) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                int dot = n.lastIndexOf('.');
                if ((dot > 0 ? n.substring(0, dot) : n).equals(want)) return f;
            }
        }
        return null;
    }
}
