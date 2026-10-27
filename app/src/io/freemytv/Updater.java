package io.freemytv;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Updating the app from the TV itself: it asks the project's GitHub releases for the newest version, downloads the
 * APK into a folder this app's own ADB (running as the shell user) can read, refuses anything not signed with the
 * key already installed, and installs it. A release signed with another key would be rejected by Android anyway —
 * and would leave the person with an app they cannot update.
 */
final class Updater {
    private static final String API = "https://api.github.com/repos/T3lo5/free-my-tv/releases/latest";
    /** Once a day is enough; the check runs when the home screen comes back. */
    private static final long EVERY = 24 * 3600_000L;

    private Updater() {}

    /** Where the check is, for the settings screen ("Procurando…", "Já está atualizado"). */
    interface Status { void on(String message); }

    /** Once a day, when the home comes back: the quiet path. */
    static void maybeUpdate(Context c) {
        check(c, false, null);
    }

    /**
     * Checks (at most once a day unless `force`) and installs, in the background. The install runs detached: it
     * replaces this very process, so the command has to outlive us.
     */
    static void check(Context c, boolean force, Status status) {
        final Context app = c.getApplicationContext();
        if (!force && System.currentTimeMillis() - Prefs.lastUpdateCheck() < EVERY) return;
        Prefs.setLastUpdateCheck(System.currentTimeMillis());
        new Thread(() -> {
            try {
                report(status, "Procurando…");
                Release release = latest();
                if (release == null || !release.newerThan(installedVersion(app))) {
                    report(status, "Já está atualizado (" + installedVersion(app) + ").");
                    return;
                }
                report(status, "Baixando " + release.tag + "…");
                File apk = download(app, release);
                String problem = signatureProblem(app, apk);
                if (problem != null) {
                    AppLog.w("update " + release.tag + " refused: " + problem);
                    report(status, "Recusado: " + problem);
                    return;
                }
                report(status, "Instalando " + release.tag + "…");
                install(app, apk);
            } catch (Exception e) {
                AppLog.w("update: " + e.getMessage());
                report(status, "Não deu: " + e.getMessage());
            }
        }, "update").start();
    }

    private static void report(Status status, String message) {
        AppLog.w("update: " + message);
        if (status != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> status.on(message));
    }

    private static final class Release {
        String tag, apkUrl;

        /** "v1.3.1" against "1.3.0": version names, since a release carries its tag. */
        boolean newerThan(String installed) {
            int[] a = parse(tag), b = parse(installed);
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
                if (x != y) return x > y;
            }
            return false;
        }

        private static int[] parse(String version) {
            String clean = version == null ? "" : version.replaceAll("[^0-9.]", "");
            String[] parts = clean.split("\\.");
            int[] out = new int[Math.min(parts.length, 3)];
            for (int i = 0; i < out.length; i++) {
                try {
                    out[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    out[i] = 0;
                }
            }
            return out;
        }
    }

    private static Release latest() throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(API).openConnection();
        http.setConnectTimeout(10_000);
        http.setReadTimeout(15_000);
        http.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = http.getInputStream()) {
            String body = read(in);
            JSONObject o;
            try {
                o = new JSONObject(body);
            } catch (org.json.JSONException e) {
                throw new IOException("GitHub sent something that is not JSON: " + e.getMessage());
            }
            Release release = new Release();
            release.tag = o.optString("tag_name");
            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                String url = asset == null ? "" : asset.optString("browser_download_url");
                // The workflow names it freemytv-<tag>.apk, next to a stable FreeMyTV.apk copy.
                if (url.endsWith(".apk") && url.contains(release.tag)) {
                    release.apkUrl = url;
                    break;
                }
            }
            return release.apkUrl == null || release.tag.isEmpty() ? null : release;
        } finally {
            http.disconnect();
        }
    }

    private static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static File download(Context c, Release release) throws IOException {
        // The app's external files folder: the shell user can read it, which is what `pm install` needs.
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        File out = new File(dir, "update-" + release.tag + ".apk");
        HttpURLConnection http = (HttpURLConnection) new URL(release.apkUrl).openConnection();
        http.setConnectTimeout(15_000);
        http.setReadTimeout(60_000);
        http.setInstanceFollowRedirects(true);
        try (InputStream in = http.getInputStream(); FileOutputStream file = new FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) file.write(buffer, 0, read);
        } finally {
            http.disconnect();
        }
        return out;
    }

    /** Null when the APK is signed with this app's own key; otherwise what is wrong (never installed then). */
    private static String signatureProblem(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo installed = pm.getPackageInfo(c.getPackageName(), flags);
            PackageInfo downloaded = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (downloaded == null) return "the file is not an APK";
            String ours = fingerprint(installed), theirs = fingerprint(downloaded);
            if (ours.isEmpty() || theirs.isEmpty()) return "could not read the certificates";
            return ours.equals(theirs) ? null : "signed with another key";
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static String fingerprint(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) return "";
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void install(Context c, File apk) {
        String path = apk.getAbsolutePath();
        // The package installer reads the APK as the system user, which cannot read /sdcard (SELinux); it can read
        // /data/local/tmp, and the shell user is the one who can put it there. The whole thing runs detached: the
        // install replaces this very process, and with it the ADB session that ordered it.
        String command = "cp \"" + path + "\" /data/local/tmp/freemytv-update.apk"
                + " && pm install -r /data/local/tmp/freemytv-update.apk";
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            String out = adb.shell("setsid sh -c '" + command.replace("'", "'\\''") + "' </dev/null >/dev/null 2>&1 &");
            AppLog.w("update " + apk.getName() + ": " + out.trim());
        } catch (IOException e) {
            AppLog.w("update install: " + e.getMessage());
        }
    }

    private static String read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toString("UTF-8");
    }
}
