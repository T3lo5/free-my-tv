package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * The remote's app buttons on this TV: the system's own window manager takes them before any app sees them (the
 * remote says it is a gamepad, and the firmware grabs the shortcut keys), and tells the TV's system app which app
 * to open. It says so in a broadcast, and we listen to it: the app the person chose opens instead.
 */
public class RemoteKeyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        Bundle b = intent.getExtras();
        if (b == null) return;
        int code = b.getInt("keycode", b.getInt("keyCode", b.getInt("key_code", 0)));
        if (code == 0) return;
        RemoteKeys.onCode(c, code);
    }
}
