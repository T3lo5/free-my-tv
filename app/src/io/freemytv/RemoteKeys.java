package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 *
 * The IR receiver of this TV says it is a gamepad ("KEYBOARD | DPAD | GAMEPAD"), so the source of the key says
 * nothing about it: only the controller's own buttons are left alone.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** The key codes are TCL's: on other brands they can be other buttons, so nothing here runs there. */
    static boolean supported() {
        return "TCL".equalsIgnoreCase(Build.MANUFACTURER) || "TCL".equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        if (isGameButton(e.getKeyCode())) return false;   // a controller's button, not the remote's
        boolean ours = false;
        for (int key : Prefs.buttonCodes()) ours |= key == e.getKeyCode();
        if (!ours) {
            // A button we don't know yet: remember it, so the settings screen can offer it.
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && isButton(e.getKeyCode())) {
                Prefs.rememberCode(e.getKeyCode());
                AppLog.w("remote key " + e.getKeyCode() + " (new button)");
            }
            return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) onCode(c, e.getKeyCode());
        return true;
    }

    /** The remote's button (by its key code): opens the app the person chose for it. */
    static void onCode(Context c, int code) {
        boolean ours = false;
        for (int key : Prefs.buttonCodes()) ours |= key == code;
        if (!ours) {
            Prefs.rememberCode(code);
            AppLog.w("remote key " + code + " (new button)");
            return;
        }
        String pkg = Prefs.keyApp(code);
        if (pkg.isEmpty()) return;   // "Nothing": the key goes on as if we weren't here
        if (pkg.startsWith("action:")) {   // not an app: something the home screen does
            runAction(c, pkg);
            return;
        }
        Intent intent = HomeApps.launchIntent(c.getPackageManager(), pkg);
        AppLog.w("remote key " + code + " -> " + pkg + (intent == null ? " (not installed)" : ""));
        if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** The actions a remote button can run instead of opening an app. */
    static final String ACTION_HOME = "action:home", ACTION_SHUFFLE = "action:shuffle",
            ACTION_DRAWER = "action:drawer";

    private static void runAction(Context c, String action) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(android.content.ComponentName.unflattenFromString(HomeMode.HOME_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (ACTION_SHUFFLE.equals(action)) intent.putExtra(HomeView.EXTRA_SHUFFLE, true);
        if (ACTION_DRAWER.equals(action)) intent.putExtra(HomeView.EXTRA_DRAWER, true);
        try {
            c.startActivity(intent);
            AppLog.w("remote key -> " + action);
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            AppLog.w("remote key " + action + ": " + e.getMessage());
        }
    }

    /** The buttons of a game controller: a gamepad's, not the remote's. */
    private static boolean isGameButton(int code) {
        return code >= KeyEvent.KEYCODE_BUTTON_A && code <= KeyEvent.KEYCODE_BUTTON_MODE;
    }

    /** The keys that are not one of the remote's buttons: the way around, the volume, the media keys. */
    private static boolean isButton(int code) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return false;   // the numbers
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return false;   // the letters
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_NOTIFICATION:
            case KeyEvent.KEYCODE_CAPTIONS:
            case KeyEvent.KEYCODE_HELP:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_TV:
            case KeyEvent.KEYCODE_TV_INPUT:
            case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_TV_CONTENTS_MENU:
            case KeyEvent.KEYCODE_STB_POWER:
            case KeyEvent.KEYCODE_STB_INPUT:
            case KeyEvent.KEYCODE_AVR_POWER:
            case KeyEvent.KEYCODE_AVR_INPUT:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
                return false;
            default:
                return true;
        }
    }
}
