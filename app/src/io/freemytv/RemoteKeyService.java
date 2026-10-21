package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.IntentFilter;
import android.view.KeyEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest.
 *
 * The TV's own window manager takes the shortcut keys (the app buttons) before any app sees them, and says in a
 * broadcast which one was pressed: the receiver (RemoteKeyReceiver) opens the app the person chose for it.
 */
public class RemoteKeyService extends AccessibilityService {
    private RemoteKeyReceiver receiver;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        // The TV's broadcast for the shortcut keys: it is sent by the system, so the receiver has to be exported.
        // (A receiver in the manifest would not get it: implicit broadcasts are not allowed there since Android 8.)
        try {
            receiver = new RemoteKeyReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.tcl.TCLkeyevent");
            filter.addAction("tcl.sys.intent.action.USERSTATE_KEYDOWN");
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            AppLog.w("remote buttons: listening to the TV's shortcut keys");
        } catch (Exception e) {
            AppLog.w("remote buttons service: " + e.getMessage());
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(android.content.Intent intent) {
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }
        return super.onUnbind(intent);
    }
}
