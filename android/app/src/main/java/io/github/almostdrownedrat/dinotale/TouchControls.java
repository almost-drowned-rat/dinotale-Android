// TouchControls - puts the touch buttons (TouchOverlay), their settings icon and the touch menu (SideMenu) over
// SDL's surface and routes the activity's input to them. They live in their own panel window above the activity
// (TYPE_APPLICATION_PANEL): on the Odin 2 Portal the DK64 activity window's own views never reached the screen after
// a cold start (only SDL's SurfaceView did, until the app went to the background and back), a separate window
// always shows. The panel takes no touches while the game runs - the activity hands them over (onTouch): a touch
// on a button or the icon goes to the controls, everything else to SDL (touch as a mouse). With the menu or the
// layout editor open the panel takes touches itself.
// Android's Back (key or gesture) opens the menu while the game runs - the same in every port, and fully usable
// with a controller (D-pad / stick, A, B). With the game's own menu open, Back goes to the game and closes it. The
// small icon at the top centre opens the same menu by touch: shown with the buttons; with the buttons off or
// hidden for a controller, any touch shows it for a few seconds.
// The menu: Back to the game, Game settings and controller (the game's own menu, where the controller is set up),
// Shader (ports with RetroArch shaders: ShaderLibrary's packs and presets),
// Touch controls (as in Orta Recompiled for Android: Touch buttons Automatic / Always / Off, Move and resize, Button
// size, Show / hide single buttons, Opacity, Stick sensitivity, Reset layout), Exit game (right away; the activity
// is finished so SDL tells the game to quit and it can write its saves; the process ends a few seconds later).
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.almostdrownedrat.dinotale;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

public final class TouchControls {
    /** What the game says: is it running (not its launcher), is its own menu open (polled on the UI thread); and
     *  opening its own menu (settings, controller set-up). */
    public interface GameState { boolean running(); boolean menuOpen(); void openGameMenu(); }

    /** A port whose shaders are browsed in their own pages (ShaderLibrary): the current one's name and the page. */
    interface ShaderMenu { String current(); SideMenu.Page page(); SideMenu.Page paramsPage(); }

    private static final long POLL_MS = 200, ICON_SHOW_MS = 4000;
    private final TouchOverlay overlay;
    private final SideMenu menu;
    private final Progress95 progress;
    private final Icon icon;
    private final GameState game;
    private final Activity activity;
    private ShaderMenu shaderMenu;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long iconUntil;
    private boolean lastRunning, lastMenu;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean running = false, menuOpen = false;
            try { running = game.running(); menuOpen = game.menuOpen(); } catch (UnsatisfiedLinkError ignored) { }
            if (running != lastRunning || menuOpen != lastMenu) {
                android.util.Log.i("TouchControls", "game running=" + running + " menu open=" + menuOpen
                    + " mode=" + overlay.getMode());
                lastRunning = running; lastMenu = menuOpen;
            }
            overlay.setGameRunning(running);
            overlay.setGameMenuOpen(menuOpen);
            updateIcon();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final android.view.WindowManager wm;
    private final FrameLayout root;
    private final android.view.WindowManager.LayoutParams wlp;
    private final View anchor;
    private boolean attached, touchable;
    private static final int OWNER_NONE = 0, OWNER_BUTTONS = 1, OWNER_ICON = 2, OWNER_GAME = 3;
    private int owner = OWNER_NONE;
    private boolean backDownOurs;

    public TouchControls(Activity a, View anchor, TouchOverlay.Sink sink, GameState game) {
        this.game = game;
        this.activity = a;
        this.anchor = anchor;
        overlay = new TouchOverlay(a, sink);
        icon = new Icon(a);
        menu = new SideMenu(a);
        root = new FrameLayout(a);
        root.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        float dp = a.getResources().getDisplayMetrics().density;
        int s = (int) (44 * dp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s, android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        lp.topMargin = (int) (8 * dp);
        root.addView(icon, lp);
        root.addView(menu, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        progress = new Progress95(a);
        root.addView(progress, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        icon.setOnClickListener(v -> iconTapped());
        overlay.setOnVisibility(this::updateIcon);
        menu.setOnClosed(() -> { setTouchable(overlay.isEditing()); updateIcon(); });

        wm = a.getWindowManager();
        wlp = new android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN | android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT);
        wlp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        wlp.setTitle("TouchControls");
        if (android.os.Build.VERSION.SDK_INT >= 28)
            wlp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (android.os.Build.VERSION.SDK_INT >= 30) wlp.setFitInsetsTypes(0);
        attach();
        updateIcon();
    }

    /** The panel needs the activity window's token, which exists once that window is attached. */
    private void attach() {
        if (attached) return;
        if (anchor.getWindowToken() == null) { anchor.post(this::attach); return; }
        wlp.token = anchor.getWindowToken();
        try {
            wm.addView(root, wlp);
            attached = true;
        } catch (RuntimeException ex) {
            android.util.Log.w("TouchControls", "panel window: " + ex);
            anchor.postDelayed(this::attach, 200);
        }
    }

    /** Removes the panel window (the activity's onDestroy). */
    public void destroy() {
        handler.removeCallbacks(poll);
        if (attached) { try { wm.removeViewImmediate(root); } catch (RuntimeException ignored) { } attached = false; }
    }

    /** The panel takes touches itself only while the menu or the layout editor is open. */
    private void setTouchable(boolean t) {
        if (touchable == t) return;
        touchable = t;
        int f = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wlp.flags = t ? (wlp.flags & ~f) : (wlp.flags | f);
        if (attached) wm.updateViewLayout(root, wlp);
    }

    private void iconTapped() {
        if (overlay.isEditing()) { overlay.setEditing(false); setTouchable(false); updateIcon(); return; }
        openMenu();
    }

    public void onResume() { handler.removeCallbacks(poll); handler.post(poll); }
    public void onPause() { handler.removeCallbacks(poll); overlay.release(); }

    private void openMenu() {
        overlay.release();
        setTouchable(true);
        menu.open(rootPage());
        updateIcon();
    }

    public boolean isMenuOpen() { return menu.isOpen(); }

    /** Adds "Shader ›" with its own pages to the menu (ports with ShaderLibrary). */
    void setShaderMenu(ShaderMenu m) { shaderMenu = m; }

    /** A progress dialog over the game (Progress95, Orta's setup look); fraction < 0: unknown. UI thread. */
    void showProgress(String title, String status, float fraction) { progress.bringToFront(); progress.set(title, status, fraction); }
    void hideProgress() { progress.hide(); }

    /** For ShaderMenu pages: open a sub-page, rebuild the current one (values changed). */
    void pushPage(SideMenu.Page p) { menu.push(p); }
    void refreshPage() { if (menu.isOpen()) menu.refresh(); }

    private void updateIcon() {
        boolean show = overlay.isGameRunning() && !menu.isOpen() && !overlay.isGameMenuOpen()
            && (overlay.isEditing() || overlay.shown() || System.currentTimeMillis() < iconUntil);
        icon.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        icon.setAlpha(overlay.isEditing() ? 1f : 0.35f + 0.65f * overlay.getOpacity() / 100f);
        icon.editing = overlay.isEditing();
        icon.invalidate();
    }

    private static boolean fromGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** From the activity's dispatchKeyEvent. True = consumed (the open menu takes every key but Back). */
    public boolean onKey(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // decided on the press, acted on the release; a Back the game got on its press stays the game's
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) backDownOurs = backIsOurs();
                return backDownOurs;
            }
            if (e.getAction() == KeyEvent.ACTION_UP && backDownOurs) { backDownOurs = false; onBack(); return true; }
            return false;
        }
        if (menu.isOpen()) return menu.onKey(e);
        if (fromGamepad(e.getSource()) && e.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            if (overlay.shown() && overlay.getMode() == TouchOverlay.MODE_AUTO) android.util.Log.i("TouchControls", "controller key " + e.getKeyCode() + " from "
                + (e.getDevice() != null ? e.getDevice().getName() : "?") + ": buttons hidden");
            overlay.onGamepadInput();
        }
        return false;
    }

    /** Whether Back is for the menu here: not in the game's launcher, not while the game's own menu is open. */
    private boolean backIsOurs() {
        if (menu.isOpen() || overlay.isEditing()) return true;
        boolean running = false, gameMenu = false;
        try { running = game.running(); gameMenu = game.menuOpen(); } catch (UnsatisfiedLinkError ignored) { }
        return running && !gameMenu;
    }

    /** Android's Back (from onKey, or the activity's OnBackInvokedCallback for the back gesture). True = handled:
     *  the menu opens, goes back a level or closes. False = it is the game's (its menu is open, Back closes it)
     *  or the system's (the launcher). */
    public boolean onBack() {
        if (menu.isOpen()) { menu.back(); return true; }
        if (overlay.isEditing()) { overlay.setEditing(false); setTouchable(false); updateIcon(); return true; }
        if (!backIsOurs()) return false;
        openMenu();
        return true;
    }

    /** From dispatchGenericMotionEvent. True = consumed. */
    public boolean onMotion(MotionEvent e) {
        if (menu.isOpen() && fromGamepad(e.getSource())) return menu.onMotion(e);
        if (fromGamepad(e.getSource())) {
            // ignore a resting stick's noise: only a real deflection counts as "controller used"
            float m = Math.max(Math.abs(e.getAxisValue(MotionEvent.AXIS_X)), Math.abs(e.getAxisValue(MotionEvent.AXIS_Y)));
            m = Math.max(m, Math.max(Math.abs(e.getAxisValue(MotionEvent.AXIS_HAT_X)), Math.abs(e.getAxisValue(MotionEvent.AXIS_HAT_Y))));
            m = Math.max(m, Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_RTRIGGER)));
            if (m > 0.5f) {
                if (overlay.shown() && overlay.getMode() == TouchOverlay.MODE_AUTO) android.util.Log.i("TouchControls", "controller axis " + m + " from "
                    + (e.getDevice() != null ? e.getDevice().getName() : "?") + ": buttons hidden");
                overlay.onGamepadInput();
            }
        }
        return false;
    }

    /** From the activity's dispatchTouchEvent (the panel itself takes no touches while the game runs). Returns
     *  true when the touch belongs to the controls - the activity then keeps it from SDL. A gesture stays with the
     *  side that got its first finger. A touch while the buttons are hidden (Automatic) brings them and the icon back. */
    public boolean onTouch(MotionEvent e) {
        int a = e.getActionMasked();
        if (touchable) return false;                          // menu / editor: the panel window has the touch
        if (a == MotionEvent.ACTION_DOWN) {
            owner = OWNER_GAME;
            if (overlay.isGameRunning() && !overlay.isGameMenuOpen()) {
                if (!overlay.shown()) {
                    overlay.onTouchWhileHidden();
                    iconUntil = System.currentTimeMillis() + ICON_SHOW_MS;
                    updateIcon();
                    handler.postDelayed(this::updateIcon, ICON_SHOW_MS + 50);
                }
                float x = e.getX(), y = e.getY();
                if (icon.getVisibility() == View.VISIBLE && x >= icon.getLeft() && x < icon.getRight()
                        && y >= icon.getTop() && y < icon.getBottom()) {
                    owner = OWNER_ICON;
                } else if (overlay.shown() && overlay.takesTouch(x, y)) {
                    owner = OWNER_BUTTONS;
                }
            }
        }
        boolean ours = owner == OWNER_BUTTONS || owner == OWNER_ICON;
        if (owner == OWNER_BUTTONS) overlay.onTouchEvent(e);
        if (owner == OWNER_ICON && a == MotionEvent.ACTION_UP) iconTapped();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) owner = OWNER_NONE;
        return ours;
    }

    // ---------------------------------------------------------------- the menu

    private static String pct(float f) { return Math.round(f * 100) + " %"; }

    private SideMenu.Page rootPage() {
        return new SideMenu.Page() {
            public String title() { return "Menu"; }
            public List<SideMenu.Item> items() {
                List<SideMenu.Item> l = new ArrayList<>();
                l.add(new SideMenu.Item("Back to the game") { @Override void run() { menu.close(); } });
                l.add(new SideMenu.Item("Game settings and controller") {
                    @Override void run() {
                        menu.close();
                        try { game.openGameMenu(); } catch (UnsatisfiedLinkError ignored) { }
                    }
                });
                if (shaderMenu != null) {
                    l.add(new SideMenu.Item("Shader:  " + shaderMenu.current() + "  ›") {
                        @Override void run() { menu.push(shaderMenu.page()); }
                    });
                    l.add(new SideMenu.Item("Shader parameters  ›") {
                        @Override void run() { menu.push(shaderMenu.paramsPage()); }
                    });
                }
                l.add(new SideMenu.Item("Touch controls  ›") { @Override void run() { menu.push(touchPage()); } });
                l.add(new SideMenu.Item("Exit game") { @Override void run() { exitGame(); } });
                return l;
            }
        };
    }

    /** Ends the game: finishing the activity makes SDL send the game its quit event (saves are written on the way
     *  out); the process is ended after a grace period, so the next start is a fresh one. */
    private void exitGame() {
        android.util.Log.i("TouchControls", "exit game");
        menu.close();
        destroy();
        activity.finishAndRemoveTask();
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> android.os.Process.killProcess(android.os.Process.myPid()), 4000);
    }

    private SideMenu.Page touchPage() {
        final String[] modes = { "Automatic", "Always", "Off" };
        return new SideMenu.Page() {
            public String title() { return "Touch controls"; }
            public List<SideMenu.Item> items() {
                List<SideMenu.Item> l = new ArrayList<>();
                l.add(new SideMenu.Item("Touch buttons") {     // Automatic = hidden while a controller is used
                    @Override String value() { return modes[overlay.getMode()]; }
                    @Override void step(int d) { overlay.setMode((overlay.getMode() + d + 3) % 3); }
                });
                l.add(new SideMenu.Item("Move and resize buttons") {
                    @Override void run() { menu.close(); overlay.setEditing(true); setTouchable(true); updateIcon(); }
                });
                l.add(new SideMenu.Item("Button size") {       // all buttons, 50-150 %
                    @Override String value() { return pct(overlay.getScale()); }
                    @Override void step(int d) { overlay.setScale(Math.max(0.5f, Math.min(1.5f, overlay.getScale() + 0.25f * d))); }
                });
                l.add(new SideMenu.Item("Show / hide single buttons") { @Override void run() { menu.push(hidePage()); } });
                l.add(new SideMenu.Item("Opacity") {           // 25-100 %
                    @Override String value() { return overlay.getOpacity() + " %"; }
                    @Override void step(int d) { overlay.setOpacity(Math.max(25, Math.min(100, overlay.getOpacity() + 25 * d))); }
                });
                l.add(new SideMenu.Item("Stick sensitivity") {
                    @Override String value() { return pct(overlay.getSensitivity()); }
                    @Override void step(int d) { overlay.setSensitivity(Math.max(1f, Math.min(3f, overlay.getSensitivity() + 0.5f * d))); }
                });
                l.add(new SideMenu.Item("Reset touch layout") { @Override void run() { overlay.resetLayout(); menu.refresh(); } });
                return l;
            }
        };
    }

    private SideMenu.Page hidePage() {
        return new SideMenu.Page() {
            public String title() { return "Show / hide single buttons"; }
            public List<SideMenu.Item> items() {
                List<SideMenu.Item> l = new ArrayList<>();
                for (int i = 0; i < TouchOverlay.N; i++) {
                    final int b = i;
                    l.add(new SideMenu.Item(TouchOverlay.NAMES[b]) {
                        @Override String value() { return overlay.isHidden(b) ? "hidden" : "shown"; }
                        @Override void step(int d) { overlay.setHidden(b, !overlay.isHidden(b)); }
                    });
                }
                return l;
            }
        };
    }

    /** The settings icon: a small controller; while editing the layout it shows a tick ("done"). */
    private static final class Icon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean editing;
        Icon(Context c) { super(c); setClickable(true); setContentDescription("Touch controls"); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(editing ? 0xE02FA84F : 0xA0202020);
            c.drawCircle(w / 2, h / 2, s / 2 - 1, p);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.06f);
            p.setStrokeCap(Paint.Cap.ROUND);
            if (editing) {                                    // a tick
                c.drawLine(w * 0.30f, h * 0.52f, w * 0.44f, h * 0.66f, p);
                c.drawLine(w * 0.44f, h * 0.66f, w * 0.72f, h * 0.36f, p);
                return;
            }
            RectF body = new RectF(w * 0.20f, h * 0.33f, w * 0.80f, h * 0.67f);   // a controller
            c.drawRoundRect(body, s * 0.15f, s * 0.15f, p);
            c.drawLine(w * 0.31f, h * 0.50f, w * 0.43f, h * 0.50f, p);           // D-pad
            c.drawLine(w * 0.37f, h * 0.44f, w * 0.37f, h * 0.56f, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(w * 0.60f, h * 0.46f, s * 0.035f, p);                     // buttons
            c.drawCircle(w * 0.68f, h * 0.54f, s * 0.035f, p);
        }
    }
}
