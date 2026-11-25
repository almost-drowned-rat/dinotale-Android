// TouchControls - puts the touch buttons (TouchOverlay), their settings icon and the touch menu (SideMenu) over
// SDL's surface and routes the activity's input to them. They live in their own panel window above the activity
// (TYPE_APPLICATION_PANEL): on the Odin 2 Portal the DK64 activity window's own views never reached the screen after
// a cold start (only SDL's SurfaceView did, until the app went to the background and back), a separate window
// always shows. The panel takes no touches while the game runs - the activity hands them over (onTouch): a touch
// on a button or the icon goes to the controls, everything else to SDL (touch as a mouse). With the menu or the
// layout editor open the panel takes touches itself.
// Android's Back (key or gesture) opens the menu while the game runs - the same in every port, and fully usable
// with a controller (D-pad / stick, A, B). With the game's own menu open, Back closes that (GameState.openGameMenu
// toggles it; any Back key, also a handheld's own, which arrives as a controller button). The
// small icon at the top centre opens the same menu by touch: shown with the buttons; with the buttons off or
// hidden for a controller, any touch shows it for a few seconds.
// The menu: Back to the game, Touch controls (as in
// Orta Recompiled for Android: Touch buttons Automatic / Always / Off, Move and resize, Button size, Show / hide
// single buttons, Opacity, Stick sensitivity, Reset layout), Settings (the game's own menu, where the controller is set
// up), Shaders (the GLES ports: ShaderLibrary's category page),
// FPS (frame rate and frame time), Exit game (right away; the activity is finished so SDL tells the game to quit and it can write
// its saves; the process ends a few seconds later).
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
    public interface GameState {
        boolean running();
        boolean menuOpen();
        void openGameMenu();   // toggles the game's own menu
        /** fps, average and longest frame time (ms) of the last half second; null: not measured by this port. */
        default float[] frameStats() { return null; }
    }

    /** A port whose shaders are browsed in their own pages (ShaderLibrary): the current one's name and the page. */
    interface ShaderMenu {
        String current();
        SideMenu.Page page();
        SideMenu.Page paramsPage();
        /** One Shader page with everything (the GLES ports); null: the two rows of before. */
        default SideMenu.Page categoryPage() { return null; }
        /** Every poll: whether the game runs and the engine's own menu is open. */
        default void gameState(boolean running, boolean menuOpen) { }
    }

    private static final long POLL_MS = 200, ICON_SHOW_MS = 4000;
    private final TouchOverlay overlay;
    private final SideMenu menu;
    private final Progress95 progress;
    private final FpsView fps;
    private boolean fpsOn;
    private final Icon icon;
    private final GameState game;
    private final Activity activity;
    private ShaderMenu shaderMenu;
    private boolean shaderRows = true;
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
            if (shaderMenu != null) shaderMenu.gameState(running, menuOpen);
            updateFps(running && !menuOpen);   // not over the menu (its title sits there)
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
    private final java.util.Set<Integer> menuKeys = new java.util.HashSet<>();   // keys pressed while the menu was open

    public TouchControls(Activity a, View anchor, TouchOverlay.Sink sink, GameState game) {
        this.game = game;
        this.activity = a;
        bridgeOwner = this;
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
        fps = new FpsView(a);
        FrameLayout.LayoutParams fpsLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.START);
        fpsLp.leftMargin = fpsLp.topMargin = (int) (8 * dp);
        root.addView(fps, fpsLp);
        fpsOn = a.getSharedPreferences("touch", Context.MODE_PRIVATE).getBoolean("fps", false);
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

    private long fpsShown;

    /** The FPS readout while it is on and the game runs, refreshed every half second (from the poll). */
    private void updateFps(boolean running) {
        float[] s = null;
        if (fpsOn && running) {
            try { s = game.frameStats(); } catch (Throwable ignored) { }
        }
        if (s == null) { fps.setVisibility(View.GONE); return; }
        fps.setVisibility(View.VISIBLE);
        long now = System.currentTimeMillis();
        if (now - fpsShown >= 450) { fpsShown = now; fps.set(s[0], s[1], s[2]); }
    }

    private boolean fpsMeasured() {
        try { return game.frameStats() != null; } catch (Throwable t) { return false; }
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

    /** A port whose own (native) menu shows this menu's pages (the N64 ports' card menu): Back then opens and
     *  closes only the game's menu - this side menu is not used. */
    void setCardMenu(boolean on) { cardMenu = on; }
    private boolean cardMenu;

    /** Adds "Shader ›" with its own pages to the menu (ports with ShaderLibrary). */
    void setShaderMenu(ShaderMenu m) { setShaderMenu(m, true); }

    /** inBackMenu false: the shader pages are opened from the game's own settings (openPage), not from rows here. */
    void setShaderMenu(ShaderMenu m, boolean inBackMenu) { shaderMenu = m; shaderRows = inBackMenu; }

    /** Opens the menu straight at a page (e.g. the shader pages from the game's settings). UI thread. */
    void openPage(SideMenu.Page p) {
        overlay.release();
        setTouchable(true);
        menu.open(p);
        updateIcon();
    }

    /** A progress dialog over the game (Progress95, Orta's setup look); fraction < 0: unknown. UI thread. */
    void showProgress(String title, String status, float fraction) { progress.bringToFront(); progress.set(title, status, fraction); }
    void hideProgress() { progress.hide(); }

    /** For ShaderMenu pages: open a sub-page, rebuild the current one (values changed). */
    void pushPage(SideMenu.Page p) {
        if (bridgeCapturing) { bridgePushed = bridgeRegister(p); return; }   // a page of the card menu
        menu.push(p);
    }
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
        if (menu.isOpen()) {
            // A key pressed before the menu opened (e.g. the game menu's button that opened it) is released into the
            // game: swallowing that release left the game seeing the key held, and repeating it.
            if (e.getAction() == KeyEvent.ACTION_DOWN) menuKeys.add(e.getKeyCode());
            else if (e.getAction() == KeyEvent.ACTION_UP && !menuKeys.remove(e.getKeyCode())) return false;
            return menu.onKey(e);
        }
        if (e.getAction() == KeyEvent.ACTION_UP && menuKeys.remove(e.getKeyCode())) return true;   // pressed in the menu
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
        boolean running = false;
        try { running = game.running(); } catch (UnsatisfiedLinkError ignored) { }
        return running;
    }

    /** Android's Back (from onKey, or the activity's OnBackInvokedCallback for the back gesture). True = handled:
     *  the menu opens, or closes as a whole (from any sub-page). False = it is the game's (its menu is open, Back closes it)
     *  or the system's (the launcher). */
    public boolean onBack() {
        if (menu.isOpen()) { menu.close(); return true; }   // Back closes the whole menu (B goes back one level)
        if (overlay.isEditing()) { overlay.setEditing(false); setTouchable(false); updateIcon(); return true; }
        if (!backIsOurs()) return false;
        // With the game's own menu open, Back closes it - also for a handheld's own Back key, which arrives as a
        // controller button the game's menu doesn't close on (SDL makes it the pad's Select / Back).
        boolean gameMenu = false;
        try { gameMenu = game.menuOpen(); } catch (UnsatisfiedLinkError ignored) { }
        if (gameMenu || cardMenu) {
            // toggles: closes it (the card menu goes up one level first, in the game), or opens the card menu
            try { game.openGameMenu(); } catch (UnsatisfiedLinkError ignored) { }
            return true;
        }
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
                l.add(new SideMenu.Item("Touch controls  ›") { @Override void run() { menu.push(touchPage()); } });
                l.add(new SideMenu.Item("Settings") {   // the game's own menu (also where the controller is set up)
                    @Override void run() {
                        menu.close();
                        try { game.openGameMenu(); } catch (UnsatisfiedLinkError ignored) { }
                    }
                });
                if (shaderMenu != null && shaderRows && shaderMenu.categoryPage() != null) {
                    l.add(new SideMenu.Item("Shaders  ›") {
                        @Override void run() { menu.push(shaderMenu.categoryPage()); }
                    });
                } else if (shaderMenu != null && shaderRows) {
                    l.add(new SideMenu.Item("Shader:  " + shaderMenu.current() + "  ›") {
                        @Override void run() { menu.push(shaderMenu.page()); }
                    });
                    l.add(new SideMenu.Item("Shader parameters  ›") {
                        @Override void run() { menu.push(shaderMenu.paramsPage()); }
                    });
                }
                if (fpsMeasured()) l.add(fpsItem());
                l.add(new SideMenu.Item("Exit game") { @Override void run() { exitGame(); } });
                return l;
            }
        };
    }

    private SideMenu.Item fpsItem() {
        return new SideMenu.Item("FPS") {
            @Override String value() { return fpsOn ? "On" : "Off"; }
            @Override boolean isSwitch() { return true; }
            @Override void step(int d) {
                fpsOn = !fpsOn;
                activity.getSharedPreferences("touch", Context.MODE_PRIVATE).edit().putBoolean("fps", fpsOn).apply();
            }
            @Override boolean reset() { if (fpsOn) step(1); return true; }
        };
    }

    // ---------------------------------------------------------------- the card menu's view of these pages
    // The N64 ports' native card menu (MenuCards.cpp) shows this menu's pages as cards: it reads them through these
    // static calls from its own (game) thread and acts on them through the UI thread. Pages: "touch" (Touch
    // controls), "shader" (ShaderLibrary's page), "display" (FPS); a page an item opens (pushPage) is registered
    // under a new id and handed over by bridgeTakePushed().
    private static volatile TouchControls bridgeOwner;
    private final java.util.Map<String, SideMenu.Page> bridgePages = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String bridgePushed;
    private volatile boolean bridgeClose;
    private boolean bridgeCapturing;
    private int bridgeSeq;

    private String bridgeRegister(SideMenu.Page p) {
        String id = "p" + (++bridgeSeq);
        bridgePages.put(id, p);
        return id;
    }

    private SideMenu.Page bridgePage(String id) {
        switch (id) {
            case "touch": return touchPage();
            case "display": return new SideMenu.Page() {
                public String title() { return "Display"; }
                public List<SideMenu.Item> items() {
                    List<SideMenu.Item> l = new ArrayList<>();
                    if (fpsMeasured()) l.add(fpsItem());
                    return l;
                }
            };
            case "game": return new SideMenu.Page() {   // the card menu's General: Exit game
                public String title() { return "Game"; }
                public List<SideMenu.Item> items() {
                    List<SideMenu.Item> l = new ArrayList<>();
                    l.add(new SideMenu.Item("Exit game") { @Override void run() { exitGame(); } });
                    return l;
                }
            };
            case "shader":
                if (shaderMenu == null) return null;
                return shaderMenu.categoryPage() != null ? shaderMenu.categoryPage() : shaderMenu.page();
            default: return bridgePages.get(id);
        }
    }

    /** A page for the card menu: { title, then per item: kind ("v" a value, "s" a value shown as a switch, "a" an action), title, value }; an
     *  empty array if there is no such page. Any thread. */
    static String[] bridgeItems(String id) {
        TouchControls t = bridgeOwner;
        SideMenu.Page p = t != null ? t.bridgePage(id) : null;
        if (p == null) return new String[0];
        List<SideMenu.Item> items;
        try { items = p.items(); } catch (RuntimeException e) { return new String[0]; }
        String[] out = new String[1 + 3 * items.size()];
        out[0] = p.title();
        for (int i = 0; i < items.size(); i++) {
            SideMenu.Item it = items.get(i);
            String v = it.value();
            out[1 + 3 * i] = v == null ? "a" : it.isSwitch() ? "s" : "v";   // "s": a value shown as a switch
            out[2 + 3 * i] = it.title;
            out[3 + 3 * i] = v != null ? v : "";
        }
        return out;
    }

    /** op: -1 / +1 step a value, 0 run an action, 2 its default (Y). Done on the UI thread. */
    static void bridgeAct(String id, int index, int op) {
        TouchControls t = bridgeOwner;
        if (t == null) return;
        t.handler.post(() -> {
            SideMenu.Page p = t.bridgePage(id);
            if (p == null) return;
            List<SideMenu.Item> items = p.items();
            if (index < 0 || index >= items.size()) return;
            SideMenu.Item it = items.get(index);
            boolean wasEditing = t.overlay.isEditing();
            t.bridgeCapturing = true;
            try {
                if (op == 0) it.run(); else if (op == 2) it.reset(); else it.step(op);
            } finally {
                t.bridgeCapturing = false;
            }
            if (t.menu.isOpen()) t.menu.close();                 // an item that opened the side menu's page
            if (!wasEditing && t.overlay.isEditing()) t.bridgeClose = true;   // "Move and resize": to the game
        });
    }

    /** The page an action opened (a new id), or null. Read once. */
    static String bridgeTakePushed() { TouchControls t = bridgeOwner; if (t == null) return null; String s = t.bridgePushed; t.bridgePushed = null; return s; }

    /** Whether the card menu should close now (the layout editor started). Read once. */
    static boolean bridgeTakeClose() { TouchControls t = bridgeOwner; if (t == null) return false; boolean b = t.bridgeClose; t.bridgeClose = false; return b; }

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
                    @Override boolean reset() { overlay.setMode(TouchOverlay.MODE_AUTO); return true; }
                });
                l.add(new SideMenu.Item("Move and resize buttons") {
                    @Override void run() { menu.close(); overlay.setEditing(true); setTouchable(true); updateIcon(); }
                });
                l.add(new SideMenu.Item("Button size") {       // all buttons, 50-150 %
                    @Override String value() { return pct(overlay.getScale()); }
                    @Override void step(int d) { overlay.setScale(Math.max(0.5f, Math.min(1.5f, overlay.getScale() + 0.25f * d))); }
                    @Override boolean reset() { overlay.setScale(1f); return true; }
                });
                l.add(new SideMenu.Item("Show / hide single buttons  ›") { @Override void run() { pushPage(hidePage()); } });
                l.add(new SideMenu.Item("Opacity") {           // 25-100 %
                    @Override String value() { return overlay.getOpacity() + " %"; }
                    @Override void step(int d) { overlay.setOpacity(Math.max(25, Math.min(100, overlay.getOpacity() + 25 * d))); }
                    @Override boolean reset() { overlay.setOpacity(50); return true; }
                });
                l.add(new SideMenu.Item("Stick sensitivity") {
                    @Override String value() { return pct(overlay.getSensitivity()); }
                    @Override void step(int d) { overlay.setSensitivity(Math.max(1f, Math.min(3f, overlay.getSensitivity() + 0.5f * d))); }
                    @Override boolean reset() { overlay.setSensitivity(1f); return true; }
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
                        @Override boolean isSwitch() { return true; }
                        @Override void step(int d) { overlay.setHidden(b, !overlay.isHidden(b)); }
                        @Override boolean reset() { overlay.setHidden(b, TouchOverlay.hiddenByDefault(b)); return true; }
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
