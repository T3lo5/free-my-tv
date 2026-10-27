package io.freemytv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The TMDB and API-Football keys, kept in the app's own preferences.
 *
 * A build made on this machine carries the keys from config.properties, and the first run copies them into the
 * preferences — so a later release, built in CI without keys, keeps working on that TV. A keyless build on a TV with
 * no keys yet reads them from a file dropped for it, which is how the keys come back after a reset without typing two
 * hundred characters with a remote.
 */
final class Keys {
    /** A file like config.properties, dropped where the app can read it: its own folder, or the TV's Downloads. */
    private static final String FILE = "freemytv-keys.txt";
    private static boolean loaded;

    private Keys() {}

    static String tmdb(Context c) {
        load(c);
        return Prefs.tmdbKey();
    }

    static String football(Context c) {
        load(c);
        return Prefs.footballKey();
    }

    private static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        Context app = c.getApplicationContext();
        // A build from this machine baked them in: keep them here, so the next release (keyless) still has them.
        if (Prefs.tmdbKey().isEmpty() && !Config.TMDB_KEY.trim().isEmpty()) Prefs.setTmdbKey(Config.TMDB_KEY.trim());
        if (Prefs.footballKey().isEmpty() && !Config.API_FOOTBALL_KEY.trim().isEmpty()) {
            Prefs.setFootballKey(Config.API_FOOTBALL_KEY.trim());
        }
        if (!Prefs.tmdbKey().isEmpty()) return;
        for (File file : new File[] {new File(app.getExternalFilesDir(null), FILE),
                new File("/sdcard/Download/" + FILE)}) {
            if (file.isFile() && importFile(file)) break;
        }
    }

    /** Reads `tmdb_key=` and `api_football_key=` from the file and then deletes it: the keys have a home now. */
    private static boolean importFile(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            boolean found = false;
            for (String line : new String(data, 0, read, StandardCharsets.UTF_8).split("\n")) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#") || !text.contains("=")) continue;
                String name = text.substring(0, text.indexOf('=')).trim().toLowerCase(java.util.Locale.ROOT);
                String value = text.substring(text.indexOf('=') + 1).trim();
                if (value.isEmpty()) continue;
                if (name.equals("tmdb_key")) {
                    Prefs.setTmdbKey(value);
                    found = true;
                } else if (name.equals("api_football_key")) {
                    Prefs.setFootballKey(value);
                    found = true;
                }
            }
            if (found) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                AppLog.w("keys: read from " + file.getName());
            }
            return found;
        } catch (IOException e) {
            AppLog.w("keys: " + e.getMessage());
            return false;
        }
    }
}
