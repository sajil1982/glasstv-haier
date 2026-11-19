package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys), or that cannot be
 * disabled at all, and a short press of Home opens this launcher even when the stock home is already on screen.
 *
 * With power_on_home set, a Live TV window (live_tv_package) that the firmware opens by itself at power-on is sent
 * back here too, until the first key press after boot or after the screen turns on (Changhong / Homwee boards).
 * With power_on_home_always set, Home comes up at every power-on whatever is in front, for TVs that resume their
 * last source: a Philips back on an HDMI input sends no window event until the input has a picture.
 *
 * With home_long_press_home set, it takes every Home press, so one held past the long-press timeout opens this launcher
 * instead of Android's ALL_APPS action, which nothing answers once the stock launcher is disabled (Philips TPM191E).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** consume_volume_keys: Volume Up/Down go to VolumeKeys instead of on to the firmware. */
    private boolean consumeVolumeKeys;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /** stock_home_redirect, the stock launcher's package. */
    private String stockHomePackage = "";
    /** power_on_home: the Live TV package to send back to Home, empty when off; armed until this uptime or a key. */
    private String powerOnLiveTv = "";
    private long powerOnUntil;
    /** home_long_press_home: this service takes every Home press, held ones included. */
    private boolean homeLongPressHome;
    /** app_keys "front:" entries, and the package of the activity in front, from window changes while there are any. */
    private Map<Integer, String> frontAppKeys = Collections.emptyMap();
    private Map<Integer, String> frontAppScancodes = Collections.emptyMap();
    private String frontPackage = "";
    /** power_on_home_always: Home at every power-on, not only over Live TV. */
    private boolean powerOnAlways;
    /** power_on_home: re-arms after the screen turns on; null unless the key is on. */
    private BroadcastReceiver screenOnReceiver;
    /** Stops key filtering while a game controller is connected; null unless gamepad_passthrough and key_filter, or before the unlock. */
    private GamepadPassthrough gamepads;
    /** Screen-off receiver while guard_off_in_standby is set; null otherwise. */
    private BroadcastReceiver standbyExit;
    /** disableSelf is how this service leaves for standby, not the user turning it off. */
    private boolean steppingOut;
    /** The bound helper, so the opt-in broadcast can arm it without waiting for a new connection. */
    private static HomeKeyService running;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    /** The opt-in broadcast: arm this binding now, or drop the receiver if the opt-in was turned off. */
    static void applyStandbyOptIn() {
        HomeKeyService service = running;
        if (service == null) return;
        if (GuardKeep.offInStandby(service)) service.armStandbyExit();
        else service.disarmStandbyExit();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        RemoteKeys.release(this);
        stopWaitingForUnlock();
        if (screenOnReceiver != null) unregisterReceiver(screenOnReceiver);
        if (standbyExit != null) {
            try {
                unregisterReceiver(standbyExit);
            } catch (RuntimeException ignored) {
            }
            standbyExit = null;
        }
        if (gamepads != null) gamepads.stop();
        if (consumeVolumeKeys) VolumeKeys.stop();
        super.onDestroy();
    }

    /** Some TCL boards never wake from the remote while this helper stays bound with the screen off. */
    private void armStandbyExit() {
        if (standbyExit != null || !GuardKeep.offInStandby(this)) return;
        standbyExit = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                leaveForStandby();
            }
        };
        registerReceiver(standbyExit, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) leaveForStandby();
    }

    private void disarmStandbyExit() {
        if (standbyExit == null) return;
        try {
            unregisterReceiver(standbyExit);
        } catch (RuntimeException ignored) {
        }
        standbyExit = null;
    }

    private void leaveForStandby() {
        steppingOut = true;
        GuardKeep.stepOut(this);
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        consumeVolumeKeys = config.consumeVolumeKeys;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        homeLongPressHome = config.homeLongPressHome;
        frontAppKeys = RemoteKeys.parseFrontAppKeys(config.appKeys);
        frontAppScancodes = RemoteKeys.parseFrontAppScancodes(config.appKeys);
        boolean watchFront = !frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty();
        stockHome.clear();
        stockHomePackage = config.stockHomeRedirect;
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
        }
        // power_on_home, off by default. Not with boot_to_live_tv, or with Live TV picked as the Open on start app:
        // those open Live TV on purpose at boot.
        String openOnStart = config.openOnStartChoice
                ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(OpenOnStart.KEY, "") : "";
        powerOnLiveTv = config.powerOnHome && !config.bootToLiveTv && !config.liveTvPackage.equals(openOnStart)
                ? config.liveTvPackage : "";
        powerOnAlways = config.powerOnHomeAlways && !config.bootToLiveTv && openOnStart.isEmpty();
        if (!powerOnLiveTv.isEmpty() || powerOnAlways) {
            armPowerOnHome();
        } else if (screenOnReceiver != null) {
            unregisterReceiver(screenOnReceiver);
            screenOnReceiver = null;
            powerOnUntil = 0;
        }
        // home_key_service.xml subscribes to no events, so the redirects ask for window changes only when one is on.
        AccessibilityServiceInfo info = getServiceInfo();
        landscapeApps.clear();
        // config.xml gives the defaults; a theme's config.json can replace either list without a rebuild.
        landscapeApps.addAll(Config.themeList(this, "force_landscape_apps",
                java.util.Arrays.asList(getResources().getStringArray(R.array.force_landscape_apps))));
        pointerApps.clear();
        pointerApps.addAll(Config.themeList(this, "pointer_apps",
                java.util.Arrays.asList(getResources().getStringArray(R.array.pointer_apps))));
        if (info != null && (!stockHome.isEmpty() || !powerOnLiveTv.isEmpty() || watchFront || !landscapeApps.isEmpty() || !pointerApps.isEmpty())) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(info);
        }
        // stock_home_redirect (Fire TV): this helper is the only way Home comes back, so GuardRevival puts it back on
        // the accessibility list after a force stop (Fire OS memory cleanup, Settings > Applications > Force stop).
        GuardKeep.setKeepAlways(this, !stockHome.isEmpty());
        if (!stockHome.isEmpty() || !powerOnLiveTv.isEmpty()) probeAtBoot();
        armStandbyExit();
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        // gamepad_passthrough, off by default. Only with key_filter on: off, the service never filters keys.
        if (config.gamepadPassthrough && config.keyFilter && gamepads == null) {
            gamepads = new GamepadPassthrough(this);
            gamepads.start();
        }
    }

    /** A tap's target: the "front:" entry while the button's own app is already in front, else the usual one. */
    private String inFront(Map<Integer, String> keys, Map<Integer, String> front, int code) {
        String target = keys.get(code);
        String alt = front.get(code);
        return alt != null && RemoteKeys.inFront(target, frontPackage) ? alt : target;
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // power_on_home: the first key after power-on hands the TV to the user (power and wake keys don't count).
        if (powerOnUntil != 0 && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_POWER && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) {
            powerOnUntil = 0;
        }
        if (pointerKey(event)) return true;
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, inFront(appScancodes, frontAppScancodes, event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (RemoteKeys.handle(this, event, inFront(appKeys, frontAppKeys, event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        // home_long_press_home: Android never sees the Home key, so a held press cannot turn into ALL_APPS.
        if (homeLongPressHome && event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHome.isEmpty()) {
            redirectHomeKey(event);
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        RemoteKeys.release(this);
        if (repairRecord && !steppingOut) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    /** force_landscape_apps: phone apps locked to portrait (sideways on a TV) are held in landscape by a 1 px overlay. */
    private final Set<String> landscapeApps = new HashSet<>();
    private View landscapeOverlay;

    /** pointer_apps: touch-only apps get an on-screen pointer: arrows move it, OK taps, Play/Pause switches it off and on. */
    private final Set<String> pointerApps = new HashSet<>();
    private boolean pointerApp, pointerOn;
    private View pointerView;
    private android.view.WindowManager.LayoutParams pointerLp;
    /** Pointer tip in screen pixels, and the screen size it moves in. */
    private float px, py;
    private int sw, sh;
    private float pad;
    private long okDownAt, lastScrollAt;

    private void setPointer(boolean on) {
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            if (on && pointerView == null) {
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(m);
                sw = m.widthPixels;
                sh = m.heightPixels;
                if (px == 0 && py == 0) { px = sw / 2f; py = sh / 2f; }
                final float d = m.density;
                pad = 3 * d;
                // A small window that moves, not a full-screen layer redrawn on every step: far less work for the GPU.
                pointerView = new View(this) {
                    final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    final android.graphics.Path a = new android.graphics.Path();
                    {
                        fill.setColor(0xE6FFFFFF);
                        ring.setColor(0xFF000000); ring.setStyle(android.graphics.Paint.Style.STROKE); ring.setStrokeWidth(2.5f * d);
                        float x = pad, y = pad;
                        a.moveTo(x, y); a.lineTo(x, y + 22 * d); a.lineTo(x + 6 * d, y + 16.5f * d);
                        a.lineTo(x + 10.5f * d, y + 26 * d); a.lineTo(x + 14 * d, y + 24.2f * d);
                        a.lineTo(x + 9.5f * d, y + 15 * d); a.lineTo(x + 16.5f * d, y + 15 * d); a.close();
                    }
                    @Override protected void onDraw(android.graphics.Canvas c) {
                        c.drawPath(a, fill); c.drawPath(a, ring);
                    }
                };
                int size = Math.round(20 * d + 2 * pad), tall = Math.round(28 * d + 2 * pad);
                pointerLp = new android.view.WindowManager.LayoutParams(size, tall,
                        android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT);
                pointerLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                pointerLp.windowAnimations = 0;
                pointerLp.x = Math.round(px - pad);
                pointerLp.y = Math.round(py - pad);
                wm.addView(pointerView, pointerLp);
            } else if (!on && pointerView != null) {
                stopPointerGlide();
                wm.removeView(pointerView);
                pointerView = null;
            }
        } catch (RuntimeException e) {
            pointerView = null;
        }
        pointerOn = pointerView != null;
    }

    /** The screen size, read again on each key: an app can open in portrait and turn landscape (force_landscape_apps)
     *  after the pointer appeared, and a size read only once kept the pointer in the left part of the screen. */
    private void refreshScreen() {
        try {
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
            if (m.widthPixels > 0 && m.heightPixels > 0) { sw = m.widthPixels; sh = m.heightPixels; }
        } catch (RuntimeException ignored) { }
        px = Math.max(2, Math.min(sw - 2, px));
        py = Math.max(2, Math.min(sh - 2, py));
    }

    private void placePointer() {
        if (pointerView == null || pointerLp == null) return;
        int x = Math.round(px - pad), y = Math.round(py - pad);
        if (x == pointerLp.x && y == pointerLp.y) return;
        pointerLp.x = x;
        pointerLp.y = y;
        try {
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).updateViewLayout(pointerView, pointerLp);
        } catch (RuntimeException ignored) { }
    }

    /** Arrows held right now; two at once move diagonally. */
    private boolean holdL, holdR, holdU, holdD;
    private long glideStart;
    private final android.os.Handler glideHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** One frame of a held arrow: the pointer glides at a speed that starts gentle and builds up, instead of
     *  jumping on the remote's key repeats (which only begin after about half a second). */
    private final Runnable glide = new Runnable() {
        @Override public void run() {
            if (pointerView == null) return;
            int dx = (holdR ? 1 : 0) - (holdL ? 1 : 0), dy = (holdD ? 1 : 0) - (holdU ? 1 : 0);
            if (dx == 0 && dy == 0) return;
            long held = SystemClock.uptimeMillis() - glideStart;
            // Screen widths per second: 0.45 at first, up to 1.5 after about 0.8 s.
            float t = Math.min(1f, Math.max(0f, (held - 120) / 700f));
            float speed = sw * (0.45f + 1.05f * t * t) / 60f;
            float nx = px + dx * speed, ny = py + dy * speed;
            long now = SystemClock.uptimeMillis();
            // Pushing against an edge scrolls the app that way.
            if ((ny < 4 || ny > sh - 4 || nx < 4 || nx > sw - 4) && held > 300 && now - lastScrollAt > 350) {
                lastScrollAt = now;
                float cx = sw / 2f, cy = sh / 2f;
                int ex = nx < 4 ? -1 : nx > sw - 4 ? 1 : 0, ey = ny < 4 ? -1 : ny > sh - 4 ? 1 : 0;
                gesture(cx + ex * sw * 0.2f, cy + ey * sh * 0.2f, cx - ex * sw * 0.2f, cy - ey * sh * 0.2f, 250);
            }
            px = Math.max(2, Math.min(sw - 2, nx));
            py = Math.max(2, Math.min(sh - 2, ny));
            placePointer();
            glideHandler.postDelayed(this, 16);
        }
    };

    private void stopPointerGlide() {
        holdL = holdR = holdU = holdD = false;
        glideHandler.removeCallbacks(glide);
    }

    private void gesture(float x0, float y0, float x1, float y1, long ms) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x0, y0);
        if (x0 != x1 || y0 != y1) path.lineTo(x1, y1);
        try {
            dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, ms)).build(), null, null);
        } catch (RuntimeException ignored) { }
    }

    /** True when the pointer took the key. */
    private boolean pointerKey(KeyEvent e) {
        if (!pointerApp) return false;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                setPointer(!pointerOn);
                Toast.makeText(this, pointerOn ? "Pointer on" : "Pointer off", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (!pointerOn || pointerView == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) refreshScreen();
        if (sw == 0 || sh == 0) return false;
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) okDownAt = SystemClock.uptimeMillis();
            } else if (!e.isCanceled()) {
                boolean held = SystemClock.uptimeMillis() - okDownAt > 550;
                gesture(px, py, px, py, held ? 800 : 60);
            }
            return true;
        }
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean wind = code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND;
        if (!arrow && !wind) return false;
        long now = SystemClock.uptimeMillis();
        if (wind) {   // Rewind / Fast forward scroll the page up / down under the pointer
            if (e.getAction() == KeyEvent.ACTION_DOWN && now - lastScrollAt > 350) {
                lastScrollAt = now;
                float span = sh * 0.35f * (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? -1 : 1);
                gesture(px, sh / 2f - span / 2, px, sh / 2f + span / 2, 250);
            }
            return true;
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() > 0) return true;   // the glide is already running
        boolean wasIdle = !(holdL || holdR || holdU || holdD);
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) holdL = down;
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) holdR = down;
        else if (code == KeyEvent.KEYCODE_DPAD_UP) holdU = down;
        else holdD = down;
        if (down) {
            // A single press nudges the pointer at once (about 1/80 of the screen) for fine aiming.
            int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
            float nudge = sw / 80f;
            px = Math.max(2, Math.min(sw - 2, px + dx * nudge));
            py = Math.max(2, Math.min(sh - 2, py + dy * nudge));
            placePointer();
            if (wasIdle) {
                glideStart = now;
                glideHandler.removeCallbacks(glide);
                glideHandler.postDelayed(glide, 120);
            }
        } else if (!(holdL || holdR || holdU || holdD)) {
            glideHandler.removeCallbacks(glide);
        }
        return true;
    }

    private void holdLandscape(AccessibilityEvent event) {
        if ((landscapeApps.isEmpty() && pointerApps.isEmpty()) || event.getPackageName() == null || event.getClassName() == null) return;
        String pkg = event.getPackageName().toString();
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, event.getClassName().toString()), 0);
        } catch (PackageManager.NameNotFoundException notAnActivity) {
            return;   // a dialog, toast or panel: the app under it is still in front
        }
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        boolean nowPointer = pointerApps.contains(pkg);
        if (nowPointer != pointerApp) {
            pointerApp = nowPointer;
            setPointer(nowPointer);
        }
        boolean want = landscapeApps.contains(pkg);
        try {
            if (want && landscapeOverlay == null) {
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                landscapeOverlay = new View(this);
                wm.addView(landscapeOverlay, lp);
            } else if (!want && landscapeOverlay != null) {
                wm.removeView(landscapeOverlay);
                landscapeOverlay = null;
            }
        } catch (RuntimeException e) {
            landscapeOverlay = null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        holdLandscape(event);
        // app_keys "front:" entries: only activities count, so a toast or the volume bar doesn't change the app in front.
        if ((!frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty())
                && event.getPackageName() != null && event.getClassName() != null) {
            try {
                ComponentName window = new ComponentName(event.getPackageName().toString(), event.getClassName().toString());
                getPackageManager().getActivityInfo(window, 0);
                frontPackage = window.getPackageName();
            } catch (PackageManager.NameNotFoundException notAnActivity) {
                // a dialog, panel or overlay: the app under it is still in front
            }
        }
        // power_on_home: the firmware opened Live TV by itself at power-on, before anyone pressed a key.
        if (powerOnRedirect(powerOnLiveTv, event.getPackageName(), SystemClock.elapsedRealtime(), powerOnUntil)) {
            openHome();
            return;
        }
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        CharSequence cls = event.getClassName();
        if (!stockHome.isEmpty() && cls != null && stockHome.contains(cls.toString())) openHome();
    }

    /** How long power_on_home stays armed after the screen turns on without a reboot. */
    static final long SCREEN_ON_WINDOW_MS = 60 * 1000L;

    /** power_on_home: armed for the boot window (if this bind is part of the boot) and after every screen-on. */
    private void armPowerOnHome() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            powerOnUntil = BOOT_WINDOW_MS;
            if (powerOnAlways) handler.postDelayed(this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
        }
        if (screenOnReceiver != null) return;
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                powerOnUntil = SystemClock.elapsedRealtime() + SCREEN_ON_WINDOW_MS;
                if (powerOnAlways) handler.postDelayed(HomeKeyService.this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** power_on_home_always: after the firmware has brought its last source back (Philips: within 0.5 s). */
    static final long POWER_ON_ALWAYS_DELAY_MS = 800L;

    /** power_on_home_always: Home, unless a key was pressed since the screen came on (the user has the TV). */
    private void homeAtPowerOn() {
        if (stillArmed(SystemClock.elapsedRealtime(), powerOnUntil)) openHome();
    }

    static boolean stillArmed(long now, long armedUntil) {
        return now < armedUntil;
    }

    /** The settings menu offers Restart only while this helper is bound: it opens the system power menu. */
    static boolean canOpenPowerMenu() {
        return running != null;
    }

    /** The system power menu (Restart, and Power off where the firmware has it); the TV asks before it acts. */
    static void openPowerMenu() {
        HomeKeyService service = running;
        if (service != null) service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    static boolean powerOnRedirect(String liveTv, CharSequence pkg, long now, long armedUntil) {
        return !liveTv.isEmpty() && pkg != null && liveTv.contentEquals(pkg) && now < armedUntil;
    }

    /** How long after boot the first bind still counts as "at boot" for probeAtBoot. */
    static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    /** stock_home_redirect at boot: the stock home is often on screen before Android binds this service, so no
     *  window event for it arrives (#1116). Once per boot, open ProbeActivity: when it closes, whatever was in
     *  front comes back and sends a window event, and onAccessibilityEvent covers it only if it is the stock home.
     *  An HDMI input or an app that the TV resumed at boot is left alone. */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (!firstBindAfterBoot(boot, prefs.getInt("probe_boot", -1), SystemClock.elapsedRealtime())) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    static boolean firstBindAfterBoot(int boot, int lastBoot, long uptimeMs) {
        return boot >= 0 && boot != lastBoot && uptimeMs < BOOT_WINDOW_MS;
    }

    /** Lets the system see the key, so a long press still does what the firmware gives it. */
    private static final long HOME_KEY_DELAY_MS = 250;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** stock_home_redirect on the Home key: when the stock home is already on screen (it came up at boot, before
     *  Android bound this service), Home brings it up again with no window change, so the redirect above never
     *  fires and the TV stays on the stock home until another app opens (#1116). A short press opens this
     *  launcher shortly after the system has handled the key; a long press (Google TV's quick settings) is left
     *  alone. Held time, not repeats: key repeats are made after accessibility filtering, so none arrive here. */
    private void redirectHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && isShortPress(event.getDownTime(), event.getEventTime(), ViewConfiguration.getLongPressTimeout())) {
            handler.postDelayed(this::openHome, HOME_KEY_DELAY_MS);
        }
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    private void openHome() {
        Intent open = stockHomeStays() ? new Intent(this, MainActivity.class)
                : new Intent(Intent.ACTION_MAIN).setPackage(getPackageName());
        open.addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // Fire OS 7 (Android 9) keeps the stock home, and Android holds any activity start for 5 s after a Home press.
        // An alarm's PendingIntent is sent by the system, which that hold does not apply to; it fires at once when
        // `settings put global alarm_manager_constants min_futurity=0` is set, and within Android's 5 s otherwise.
        if (!stockHome.isEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class).addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                ((android.app.AlarmManager) getSystemService(ALARM_SERVICE)).setExact(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime(), pi);
            } catch (RuntimeException ignored) {
                // the plain start below still opens Home once Android's hold ends
            }
        }
        startActivity(open);
    }

    /** stock_home_redirect while Android's Home is still the stock launcher (launcherx on Google TV outranks this
     *  one). A Home intent would then put this launcher in the home task and make it the home process, and from
     *  Android 10 the stock launcher, no longer exempt from the limits on background activity starts, can't open
     *  apps for the remote's YouTube and Netflix buttons (#1274). openHome starts it like an app instead:
     *  CATEGORY_HOME without ACTION_MAIN is not a Home intent, but still sends MainActivity back to the first tile.
     *  A MainActivity already in the home task, as the install scripts' first start leaves it, is reused as it is
     *  until that task goes (a restart or a reinstall). With this launcher or Android's chooser as Home, openHome
     *  keeps the Home intent. */
    private boolean stockHomeStays() {
        if (stockHome.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && stockHomePackage.equals(home.activityInfo.packageName);
    }

    @Override
    public void onInterrupt() {
    }
}
