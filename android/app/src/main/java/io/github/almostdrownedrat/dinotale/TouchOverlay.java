// TouchOverlay - on-screen N64 buttons drawn over SDL's surface (Canvas only, nothing in the game's render path).
// Modelled on Orta Recompiled for Android's touch controls (almost-drowned-rat, TouchOverlay.java, 2026-11-11):
// a floating stick (it centres where the thumb lands in the left part of the screen), A / B and the four C buttons
// right, Z / L / R at the top, START bottom centre; the D-pad exists but is hidden until shown in the touch menu.
// Mode AUTOMATIC hides the buttons while a real controller is used and a touch brings them back, ALWAYS keeps
// them, OFF never shows them. Layout, size, hidden buttons and opacity are per device (SharedPreferences
// "touch"); TouchControls' menu edits them. Touches outside the buttons fall through to SDL (touch as a mouse).
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.almostdrownedrat.dinotale;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public class TouchOverlay extends View {
    /** Bit numbers of the buttons; the native side maps them (A..D-RIGHT = the N64 buttons). */
    public static final int A = 0, B = 1, Z = 2, L = 3, R = 4, START = 5, CU = 6, CD = 7, CL = 8, CR = 9,
                            DU = 10, DD = 11, DL = 12, DR = 13, STICK = 14, N = 15;
    static final String[] NAMES = { "A", "B", "Z", "L", "R", "START", "C-UP", "C-DOWN", "C-LEFT", "C-RIGHT",
                                    "D-PAD UP", "D-PAD DOWN", "D-PAD LEFT", "D-PAD RIGHT", "STICK" };
    private static final String[] LABELS = { "A", "B", "Z", "L", "R", "START", "▲", "▼", "◀", "▶",
                                             "▲", "▼", "◀", "▶", "" };
    static final int MODE_AUTO = 0, MODE_ALWAYS = 1, MODE_OFF = 2;

    /** Where the state goes: buttons = one bit per control above, the stick in -1..1 (y down = +). */
    public interface Sink { void send(int buttons, float x, float y); }

    private static boolean hiddenByDefault(int i) { return i >= DU && i <= DR; }
    private static boolean centred(int i) { return i == START; }               // x 0 = the screen's centre
    private static boolean wide(int i) { return i == Z || i == L || i == R || i == START; }
    /** N64 colours: A blue, B green, C yellow, START red, the rest grey */
    private static int tint(int i) {
        if (i == A) return 0xFF3F6FE0;
        if (i == B) return 0xFF2FA84F;
        if (i >= CU && i <= CR) return 0xFFE8C030;
        if (i == START) return 0xFFD03A3A;
        return 0xFFFFFFFF;
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
                        text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final SharedPreferences prefs;
    private final Sink sink;
    // control centres in dp from an anchor corner: x >= 0 from the left, x < 0 from the right; same for y (bottom)
    private final float[] cx = new float[N], cy = new float[N], r = new float[N], sc = new float[N];
    private final boolean[] hidden = new boolean[N];
    private float gscale = 1f, sens = 1f;
    private int mode = MODE_AUTO, opacity = 50;
    private boolean padActive, editing, gameRunning, gameMenuOpen;
    private final int[] owner = new int[N];
    private float stickOx, stickOy, stickX, stickY;
    private int dragControl = -1;
    private float dragDx, dragDy, pinchStart, pinchScale;
    private Runnable onVisibility;

    public TouchOverlay(Context ctx, Sink sink) {
        super(ctx);
        this.sink = sink;
        dp = ctx.getResources().getDisplayMetrics().density;
        prefs = ctx.getSharedPreferences("touch", Context.MODE_PRIVATE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        line.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < N; i++) owner[i] = -1;
        load();
    }

    private void defaults() {
        float[][] d = {                      // x, y (dp; negative = from the right / bottom), radius
            { -150, -75, 36 },   /* A */      { -225, -135, 32 },  /* B */
            { 75, 48, 34 },      /* Z */      { 200, 48, 30 },     /* L */     { -75, 48, 34 },   /* R */
            { 0, -36, 26 },      /* START (x 0 = centre) */
            { -85, -240, 23 },   /* C-UP */   { -85, -140, 23 },   /* C-DOWN */
            { -135, -190, 23 },  /* C-LEFT */ { -35, -190, 23 },   /* C-RIGHT */
            { 300, -190, 22 },   /* D-UP */   { 300, -90, 22 },    /* D-DOWN */
            { 250, -140, 22 },   /* D-LEFT */ { 350, -140, 22 },   /* D-RIGHT */
            { 150, -150, 70 },   /* STICK: the rest place of the floating stick */
        };
        for (int i = 0; i < N; i++) { cx[i] = d[i][0]; cy[i] = d[i][1]; r[i] = d[i][2]; }
    }

    private void load() {
        defaults();
        for (int i = 0; i < N; i++) {
            cx[i] = prefs.getFloat("x" + i, cx[i]);
            cy[i] = prefs.getFloat("y" + i, cy[i]);
            hidden[i] = prefs.getBoolean("h" + i, hiddenByDefault(i));
            sc[i] = prefs.getFloat("s" + i, 1f);
        }
        gscale = prefs.getFloat("gscale", 1f);
        sens = prefs.getFloat("sens", 1f);
        mode = prefs.getInt("mode", MODE_AUTO);
        opacity = prefs.getInt("opacity", 50);
        updateVisibility();
    }

    void save() {
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < N; i++) {
            e.putFloat("x" + i, cx[i]).putFloat("y" + i, cy[i]).putBoolean("h" + i, hidden[i]).putFloat("s" + i, sc[i]);
        }
        e.putFloat("gscale", gscale).putFloat("sens", sens).putInt("mode", mode).putInt("opacity", opacity).apply();
    }

    void resetLayout() {
        defaults();
        gscale = 1f;
        for (int i = 0; i < N; i++) { hidden[i] = hiddenByDefault(i); sc[i] = 1f; }
        save();
        invalidate();
    }

    int getMode() { return mode; }
    void setMode(int m) { mode = m; if (m != MODE_AUTO) padActive = false; save(); release(); updateVisibility(); }
    int getOpacity() { return opacity; }
    void setOpacity(int o) { opacity = o; save(); invalidate(); }
    float getScale() { return gscale; }
    void setScale(float s) { gscale = s; save(); invalidate(); }
    float getSensitivity() { return sens; }
    void setSensitivity(float s) { sens = s; save(); }
    boolean isHidden(int i) { return hidden[i]; }
    void setHidden(int i, boolean h) { hidden[i] = h; save(); invalidate(); }
    void setEditing(boolean e) { editing = e; release(); updateVisibility(); invalidate(); }
    boolean isEditing() { return editing; }
    void setOnVisibility(Runnable r) { onVisibility = r; }

    /** A real controller was used: AUTOMATIC hides the buttons (and releases everything). */
    void onGamepadInput() {
        if (padActive || mode != MODE_AUTO || editing) return;
        padActive = true;
        release();
        updateVisibility();
    }

    /** A touch while the buttons are hidden by AUTOMATIC: they come back. */
    void onTouchWhileHidden() {
        if (mode == MODE_AUTO && padActive) { padActive = false; updateVisibility(); }
    }

    /** The game itself is running (not its launcher screen): the buttons may show. */
    void setGameRunning(boolean on) {
        if (gameRunning == on) return;
        gameRunning = on;
        if (on) {
            // a controller already connected (the Odin 2 Portal's own controls): AUTOMATIC starts hidden
            for (int id : android.view.InputDevice.getDeviceIds()) {
                android.view.InputDevice dv = android.view.InputDevice.getDevice(id);
                if (dv != null && !dv.isVirtual() && (dv.getSources() & android.view.InputDevice.SOURCE_GAMEPAD)
                        == android.view.InputDevice.SOURCE_GAMEPAD) padActive = true;
            }
        } else {
            release();
        }
        updateVisibility();
    }
    boolean isGameRunning() { return gameRunning; }

    /** The game's own menu is open: the buttons step aside so touches reach the menu. */
    void setGameMenuOpen(boolean on) {
        if (gameMenuOpen == on) return;
        gameMenuOpen = on;
        if (on) release();
        updateVisibility();
    }

    boolean isGameMenuOpen() { return gameMenuOpen; }
    boolean shown() { return getVisibility() == VISIBLE; }

    private void updateVisibility() {
        boolean show = gameRunning && (editing || (!gameMenuOpen && (mode == MODE_ALWAYS || (mode == MODE_AUTO && !padActive))));
        int v = show ? VISIBLE : INVISIBLE;
        if (getVisibility() != v) {
            setVisibility(v);
            android.util.Log.i("TouchControls", "buttons " + (show ? "shown" : "hidden") + " (" + getWidth() + "x" + getHeight() + ")");
        }
        if (onVisibility != null) onVisibility.run();
    }

    private float px(int i) { float w = getWidth(); return centred(i) ? w / 2 + cx[i] * dp : cx[i] >= 0 ? cx[i] * dp : w + cx[i] * dp; }
    private float py(int i) { float h = getHeight(); return cy[i] >= 0 ? cy[i] * dp : h + cy[i] * dp; }
    private float rad(int i) { return r[i] * sc[i] * gscale * dp; }

    /** Whether a finger landing here belongs to the controls (a button, or the floating stick's zone). */
    boolean takesTouch(float x, float y) { return hit(x, y) >= 0; }

    private int hit(float x, float y) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {                       // the nearest button whose (slightly larger) area is hit
            if (i == STICK || (hidden[i] && !editing)) continue;
            float dx = x - px(i), dy = y - py(i), rr = rad(i) * (wide(i) ? 1.4f : 1f) + 10 * dp, d = dx * dx + dy * dy;
            if (d <= rr * rr && d < bestD) { best = i; bestD = d; }
        }
        if (best >= 0) return best;
        if (editing) {
            float dx = x - px(STICK), dy = y - py(STICK), rr = rad(STICK);
            return dx * dx + dy * dy <= rr * rr ? STICK : -1;
        }
        // the floating stick's zone: the left 40 % of the screen, its lower 75 %
        if (!hidden[STICK] && x < getWidth() * 0.4f && y > getHeight() * 0.25f) return STICK;
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked(), idx = e.getActionIndex(), id = e.getPointerId(idx);
        if (editing) return editTouch(e, a, idx);
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int c = hit(e.getX(idx), e.getY(idx));
                if (c < 0) return a != MotionEvent.ACTION_DOWN;    // not ours: SDL gets the gesture
                owner[c] = id;
                if (c == STICK) { stickOx = e.getX(idx); stickOy = e.getY(idx); stickX = 0; stickY = 0; }
                else performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int p = 0; p < e.getPointerCount(); p++) {
                    if (owner[STICK] != e.getPointerId(p)) continue;
                    float max = rad(STICK) / sens;            // higher sensitivity: full deflection sooner
                    float dx = (e.getX(p) - stickOx) / max, dy = (e.getY(p) - stickOy) / max;
                    float len = (float) Math.sqrt(dx * dx + dy * dy);
                    if (len > 1) { dx /= len; dy /= len; }
                    stickX = dx; stickY = dy;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < N; i++) {
                    if (owner[i] == id || a == MotionEvent.ACTION_CANCEL) {
                        owner[i] = -1;
                        if (i == STICK) { stickX = 0; stickY = 0; }
                    }
                }
                break;
            default:
                break;
        }
        send();
        invalidate();
        return true;
    }

    private boolean editTouch(MotionEvent e, int a, int idx) {
        float x = e.getX(idx), y = e.getY(idx);
        if (a == MotionEvent.ACTION_DOWN) {
            dragControl = hit(x, y);
            pinchStart = 0;
            if (dragControl >= 0) { dragDx = px(dragControl) - x; dragDy = py(dragControl) - y; }
        } else if (a == MotionEvent.ACTION_POINTER_DOWN && dragControl >= 0 && e.getPointerCount() == 2) {
            pinchStart = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));   // second finger: resize
            pinchScale = sc[dragControl];
        } else if (a == MotionEvent.ACTION_MOVE && dragControl >= 0 && pinchStart > 0 && e.getPointerCount() >= 2) {
            float d = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
            sc[dragControl] = Math.max(0.5f, Math.min(2.5f, pinchScale * d / pinchStart));
            invalidate();
        } else if (a == MotionEvent.ACTION_MOVE && dragControl >= 0 && pinchStart == 0) {
            float nx = e.getX() + dragDx, ny = e.getY() + dragDy, w = getWidth(), h = getHeight();
            int c = dragControl;
            if (centred(c)) cx[c] = (nx - w / 2) / dp;
            else cx[c] = nx < w / 2 ? nx / dp : (nx - w) / dp;
            cy[c] = ny < h / 2 ? ny / dp : (ny - h) / dp;
            invalidate();
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            dragControl = -1;
            save();
        }
        return true;
    }

    /** Lets go of everything (menu opening, controller used, game menu up): no button stays held. */
    void release() {
        for (int i = 0; i < N; i++) owner[i] = -1;
        stickX = stickY = 0;
        send();
        invalidate();
    }

    private void send() {
        int b = 0;
        for (int i = 0; i < N; i++) if (owner[i] >= 0 && i != STICK) b |= 1 << i;
        try { sink.send(b, stickX, stickY); } catch (UnsatisfiedLinkError ignored) { /* native library not ready */ }
    }

    @Override
    protected void onDraw(Canvas c) {
        int al = editing ? 200 : opacity * 255 / 100;
        line.setStrokeWidth(2 * dp);
        for (int i = 0; i < N; i++) {
            if (hidden[i] && !editing) continue;
            float x = px(i), y = py(i), rr = rad(i);
            int al2 = hidden[i] ? al / 3 : al;                // hidden ones show faintly in the editor
            if (i == STICK) {
                float ox = owner[STICK] >= 0 ? stickOx : x, oy = owner[STICK] >= 0 ? stickOy : y;
                fill.setColor(Color.argb(al2 / 3, 255, 255, 255));
                c.drawCircle(ox, oy, rr, fill);
                line.setColor(Color.argb(al2, 255, 255, 255));
                c.drawCircle(ox, oy, rr, line);
                fill.setColor(Color.argb(al2, 255, 255, 255));
                c.drawCircle(ox + stickX * rr, oy + stickY * rr, rr * 0.45f, fill);
                continue;
            }
            boolean down = owner[i] >= 0;
            int t = tint(i);
            fill.setColor(down ? Color.argb(al2, Color.red(t), Color.green(t), Color.blue(t)) : Color.argb(al2 / 2, 30, 30, 30));
            line.setColor(Color.argb(al2, Color.red(t), Color.green(t), Color.blue(t)));
            if (wide(i)) {
                RectF rc = new RectF(x - rr * 1.4f, y - rr * 0.7f, x + rr * 1.4f, y + rr * 0.7f);
                c.drawRoundRect(rc, rr * 0.5f, rr * 0.5f, fill);
                c.drawRoundRect(rc, rr * 0.5f, rr * 0.5f, line);
            } else {
                c.drawCircle(x, y, rr, fill);
                c.drawCircle(x, y, rr, line);
            }
            text.setTextSize((i >= CU ? 13 : 15) * dp * Math.min(1.3f, sc[i] * gscale));
            text.setColor(down ? Color.argb(al2, 0, 0, 0) : Color.argb(al2, 255, 255, 255));
            c.drawText(LABELS[i], x, y - (text.ascent() + text.descent()) / 2, text);
        }
        if (editing) {
            text.setColor(Color.WHITE);
            text.setTextSize(16 * dp);
            c.drawText("Drag a button to move it, pinch it with two fingers to resize - tap the icon when done",
                       getWidth() / 2f, 80 * dp, text);
        }
    }
}
