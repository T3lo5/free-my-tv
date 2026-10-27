package io.freemytv;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Tuning a channel in the TV's IPTV app (OpenTV), by name. The games know the broadcaster ("Premiere, SporTV ou
 * Globo", "ESPN2"), not which of them this IPTV carries, so all the names go in one request and OpenTV plays the
 * first one it has.
 */
final class Iptv {
    private Iptv() {}

    static final String OPEN_TV = "app.opentv";
    /** The first OpenTV version that tunes a channel asked by name (0.13); older ones would ignore it. */
    private static final int BY_NAME = 28;
    private static final String EXTRA = "opentv.play_channel_name";

    /** Whether the OpenTV on this TV understands a channel asked by name. */
    static boolean canTune(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(OPEN_TV, 0);
            return info.versionCode >= BY_NAME;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Opens OpenTV on the first of the names it has; false when it could not be asked at all. */
    static boolean tune(Context c, String channel) {
        List<String> names = candidates(channel);
        if (names.isEmpty()) return false;
        StringBuilder joined = new StringBuilder();
        for (String name : names) joined.append(joined.length() == 0 ? "" : "|").append(name);
        Intent intent = new Intent("app.opentv.action.PLAY")
                .setComponent(new ComponentName(OPEN_TV, OPEN_TV + ".MainActivity"))
                .putExtra(EXTRA, joined.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("IP-TV channel " + channel + ": " + e.getMessage());
            return false;
        }
    }

    /** "Premiere, SporTV ou Globo" → the three names, in order (the first one OpenTV finds is the one that plays). */
    static List<String> candidates(String channel) {
        List<String> out = new ArrayList<>();
        if (channel == null) return out;
        for (String part : channel.split("[,/]|\\bou\\b|\\be\\b")) {
            String name = part.trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }
}
