package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};
    /** How many buttons the remote can have (the known ones plus the ones it pressed). */
    private static final int MAX_CODES = 16;

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    /** Writes without a settings change: for what the home screen remembers for itself (the last title opened). */
    private static void putQuiet(String key, String value) {
        sp().edit().putString(key, value).apply();
    }

    private static void putQuiet(String key, long value) {
        sp().edit().putLong(key, value).apply();
    }

    private static void put(String key, int value) {
        sp().edit().putInt(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt" or "en". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City (from config.properties)

    static String city() { return seed("city", Config.CITY).trim(); }

    static String latitude() { return seed("latitude", Config.LATITUDE); }

    static String longitude() { return seed("longitude", Config.LONGITUDE); }

    /** The country whose stores count, for TMDB ("BR", "US"). */
    static String region() { return seed("region", Config.TMDB_REGION).trim().toUpperCase(Locale.ROOT); }

    /** The money the prices are shown in ("BRL", "USD"). */
    static String currency() { return seed("currency", Config.CURRENCY).trim().toUpperCase(Locale.ROOT); }

    /** A value read from the keys file (see Keys): kept as-is, under the name the file used. */
    static void importSetting(String key, String value) { putQuiet(key, value); }

    /**
     * A setting that came from config.properties on the first run and lives here afterwards: a build made on this
     * machine seeds it, and a release from CI (which carries no settings) keeps it.
     */
    private static String seed(String key, String fallback) {
        String stored = sp().getString(key, "");
        if (!stored.isEmpty()) return stored;
        String value = fallback == null ? "" : fallback.trim();
        if (!value.isEmpty()) putQuiet(key, value);
        return value;
    }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** How many titles the home screen shows in each tab (3, 5 or 8). */
    static final int[] PICK_COUNTS = {3, 5, 8};

    static int picks() {
        int v = sp().getInt("picks", 3);
        for (int count : PICK_COUNTS) if (count == v) return v;
        return 3;
    }

    static void setPicks(int value) { put("picks", value); }

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    /** When the app last asked GitHub for a newer release (once a day is enough). */
    static long lastUpdateCheck() { return sp().getLong("updateAt", 0); }
    static void setLastUpdateCheck(long at) { putQuiet("updateAt", at); }

    // MARK: - Keys

    /** The TMDB read token: from config.properties on the first run, kept here afterwards. */
    static String tmdbKey() { return sp().getString("tmdbKey", ""); }
    static void setTmdbKey(String key) { put("tmdbKey", key); }

    /** The API-Football key, same idea. */
    static String footballKey() { return sp().getString("footballKey", ""); }
    static void setFootballKey(String key) { put("footballKey", key); }

    // MARK: - Favourite teams

    /** The teams the person follows, by key ("flamengo"): their games come first on the home screen. */
    static Set<String> favorites() {
        Set<String> out = new HashSet<>();
        for (String key : sp().getString("favorites", "").split("\n")) if (!key.isEmpty()) out.add(key);
        return out;
    }

    static void setFavorites(Set<String> teams) {
        StringBuilder sb = new StringBuilder();
        for (String key : teams) sb.append(sb.length() == 0 ? "" : "\n").append(key);
        put("favorites", sb.toString());
    }

    /** A team's name as it is kept: trimmed and lowercase ("Flamengo" -> "flamengo"). */
    static String teamKey(String team) {
        return team == null ? "" : team.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isFavorite(String team) {
        return favorites().contains(teamKey(team));
    }

    // MARK: - Continue / last focus

    /** The last Nuvio deeplink opened (a pick's title), to offer "Continuar" in the drawer. */
    static String lastDeeplink() { return sp().getString("lastDeeplink", ""); }
    static void setLastDeeplink(String deeplink) { putQuiet("lastDeeplink", deeplink); }

    /** The key of the last pick opened ("movie:123"), to restore the focus when coming back to the home screen. */
    static String lastPickKey() { return sp().getString("lastPickKey", ""); }
    static void setLastPickKey(String key) { putQuiet("lastPickKey", key); }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) { return sp().getString("key" + keyCode, defaultApp(keyCode)); }
    static void setKeyApp(int keyCode, String pkg) { put("key" + keyCode, pkg); }

    /**
     * The buttons the settings screen shows: the ones we know, then the ones the remote has pressed (the codes
     * other TVs use). The code is what tells them apart.
     */
    static int[] buttonCodes() {
        List<Integer> codes = new ArrayList<>();
        for (int code : KEYS) codes.add(code);
        for (String s : sp().getString("codes", "").split(",")) {
            int code;
            try {
                code = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (!codes.contains(code)) codes.add(code);
        }
        int[] out = new int[codes.size()];
        for (int i = 0; i < out.length; i++) out[i] = codes.get(i);
        return out;
    }

    /** Remembers a button the remote pressed, so the settings screen can offer it. */
    static void rememberCode(int keyCode) {
        List<String> list = new ArrayList<>();
        for (String s : sp().getString("codes", "").split(",")) if (!s.trim().isEmpty()) list.add(s.trim());
        String code = String.valueOf(keyCode);
        if (list.contains(code)) return;
        list.add(code);
        while (list.size() > MAX_CODES) list.remove(0);
        put("codes", String.join(",", list));
    }
}
