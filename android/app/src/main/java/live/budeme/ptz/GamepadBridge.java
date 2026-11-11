package live.budeme.ptz;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.WebView;

/**
 * Reads game controllers through Android's own input events and hands their state to the page
 * (window.ptzNativePad) in the standard Gamepad layout, because WebView does not reliably expose
 * the browser Gamepad API. Button order: A B X Y LB RB LT RT Select Start LS RS Up Down Left Right Home.
 */
final class GamepadBridge {
    private final WebView web;
    private final float[] axes = new float[4];
    private final float[] buttons = new float[17];
    private float hatX = 0, hatY = 0;
    private String name = "Gamepad";
    private String lastSent = "";

    GamepadBridge(WebView web) { this.web = web; }

    private static boolean isPad(InputDevice d, int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            || (d != null && (d.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);
    }

    private static int buttonIndex(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return 0;
            case KeyEvent.KEYCODE_BUTTON_B: return 1;
            case KeyEvent.KEYCODE_BUTTON_X: return 2;
            case KeyEvent.KEYCODE_BUTTON_Y: return 3;
            case KeyEvent.KEYCODE_BUTTON_L1: return 4;
            case KeyEvent.KEYCODE_BUTTON_R1: return 5;
            case KeyEvent.KEYCODE_BUTTON_L2: return 6;
            case KeyEvent.KEYCODE_BUTTON_R2: return 7;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 8;
            case KeyEvent.KEYCODE_BUTTON_START: return 9;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 10;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 11;
            case KeyEvent.KEYCODE_DPAD_UP: return 12;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 13;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 14;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 15;
            case KeyEvent.KEYCODE_BUTTON_MODE: return 16;
            default: return -1;
        }
    }

    /** @return true when the event came from a gamepad and was handled. */
    boolean onKey(KeyEvent e) {
        int i = buttonIndex(e.getKeyCode());
        if (i < 0 || !isPad(e.getDevice(), e.getSource())) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) buttons[i] = 1;
        else if (e.getAction() == KeyEvent.ACTION_UP) buttons[i] = 0;
        else return true;
        // analog triggers arrive as axes; only take the key when the axis did not set it
        remember(e.getDevice());
        push();
        return true;
    }

    /** @return true when the event came from a gamepad stick/trigger and was handled. */
    boolean onMotion(MotionEvent e) {
        if (!isPad(e.getDevice(), e.getSource()) || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        axes[0] = e.getAxisValue(MotionEvent.AXIS_X);
        axes[1] = e.getAxisValue(MotionEvent.AXIS_Y);
        axes[2] = e.getAxisValue(MotionEvent.AXIS_Z);
        axes[3] = e.getAxisValue(MotionEvent.AXIS_RZ);
        buttons[6] = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        buttons[7] = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (hx != hatX || hy != hatY) { // D-pad reported as a hat on many controllers
            hatX = hx; hatY = hy;
            buttons[14] = hx < -0.5f ? 1 : 0; buttons[15] = hx > 0.5f ? 1 : 0;
            buttons[12] = hy < -0.5f ? 1 : 0; buttons[13] = hy > 0.5f ? 1 : 0;
        }
        remember(e.getDevice());
        push();
        return true;
    }

    private void remember(InputDevice d) { if (d != null && d.getName() != null) name = d.getName(); }

    private void push() {
        StringBuilder b = new StringBuilder("{\"name\":").append(Tally.Json.str(name)).append(",\"axes\":[");
        for (int i = 0; i < axes.length; i++) { if (i > 0) b.append(','); b.append(round(axes[i])); }
        b.append("],\"buttons\":[");
        for (int i = 0; i < buttons.length; i++) { if (i > 0) b.append(','); b.append(round(buttons[i])); }
        b.append("]}");
        String json = b.toString();
        if (json.equals(lastSent)) return;
        lastSent = json;
        web.evaluateJavascript("window.ptzNativePad&&window.ptzNativePad(" + json + ")", null);
    }

    private static String round(float v) { return String.valueOf(Math.round(v * 100) / 100f); }
}
