// Progress95 - a progress dialog over the game in the look of Orta Recompiled's setup window (launcher/setup_ui_sdl.c):
// a Windows 95 style dialog - grey #C0C0C0 face, two-pixel 3D bevels, a navy title bar with white bold text, the
// status text, a blocky navy progress bar in a sunken groove. Only the style; no Microsoft graphics or fonts.
// A fraction below 0 is "unknown": a short run of blocks moves through the groove (shader compiling).
// Shown by TouchControls in its panel window (TouchControls.showProgress / hideProgress).
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.almostdrownedrat.dinotale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

final class Progress95 extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private String title = "", status = "";
    private float fraction = -1f;
    private long started;
    private boolean opaque;

    /** Black behind the dialog instead of the dimmed game (a setup screen, as in Orta's launcher). */
    void setOpaque(boolean o) { opaque = o; invalidate(); }

    Progress95(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
    }

    void set(String title, String status, float fraction) {
        if (getVisibility() != VISIBLE) started = System.currentTimeMillis();
        this.title = title;
        this.status = status;
        this.fraction = fraction;
        setVisibility(VISIBLE);
        invalidate();
    }

    void hide() { setVisibility(GONE); }

    private void fill(Canvas c, float x, float y, float w, float h, int grey) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000 | (grey << 16) | (grey << 8) | grey);
        c.drawRect(x, y, x + w, y + h, paint);
    }

    /** the classic two-pixel bevel: raised (the window) or sunken (the groove); u = one "pixel" */
    private void bevel(Canvas c, float x, float y, float w, float h, boolean raised, float u) {
        int otl = raised ? 255 : 128, obr = raised ? 0 : 255, itl = raised ? 192 : 0, ibr = raised ? 128 : 192;
        fill(c, x, y, w, u, otl);
        fill(c, x, y, u, h, otl);
        fill(c, x, y + h - u, w, u, obr);
        fill(c, x + w - u, y, u, h, obr);
        fill(c, x + u, y + u, w - 2 * u, u, itl);
        fill(c, x + u, y + u, u, h - 2 * u, itl);
        fill(c, x + u, y + h - 2 * u, w - 2 * u, u, ibr);
        fill(c, x + w - 2 * u, y + u, u, h - 2 * u, ibr);
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(opaque ? 0xFF000000 : 0x99000000);  // black (setup) or the game dimmed behind the dialog
        c.drawRect(0, 0, W, H, paint);

        // the dialog: Orta's 400 x 118 units, ~60 % of the width
        float s = Math.min(W * 0.60f / 400f, H * 0.80f / 118f);
        float u = Math.max(1f, Math.round(s));           // one bevel "pixel"
        float dw = 400 * s, dh = 118 * s, x = (W - dw) / 2, y = (H - dh) / 2;
        fill(c, x, y, dw, dh, 192);
        bevel(c, x, y, dw, dh, true, u);
        // title bar
        float th = 18 * s;
        paint.setColor(0xFF000080);
        c.drawRect(x + 3 * s, y + 3 * s, x + dw - 3 * s, y + 3 * s + th, paint);
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(10.5f * s);
        c.drawText(title, x + 8 * s, y + 3 * s + th * 0.72f, paint);
        // status (up to two lines)
        paint.setColor(0xFF000000);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(9.5f * s);
        float ty = y + 3 * s + th + 16 * s;
        for (String line : wrap(status, dw - 28 * s, 2)) {
            c.drawText(line, x + 14 * s, ty, paint);
            ty += 12 * s;
        }
        // the groove and its blocks
        float gx = x + 14 * s, gw = dw - 28 * s, gh = 20 * s, gy = y + dh - gh - 14 * s;
        bevel(c, gx, gy, gw, gh, false, u);
        float innerL = gx + 3 * s, innerW = gw - 6 * s, gap = 2 * s;
        int slots = Math.max(1, (int) ((innerW + gap) / (10 * s + gap)));
        paint.setColor(0xFF000080);
        if (fraction >= 0) {
            int blocks = Math.round(slots * Math.min(1f, fraction));
            for (int k = 0; k < blocks; k++) block(c, innerL, innerW, gap, slots, k, gy + 3 * s, gh - 6 * s);
        } else {
            int run = 5, pos = (int) (((System.currentTimeMillis() - started) / 90) % (slots + run)) - run;
            for (int k = pos; k < pos + run; k++) if (k >= 0 && k < slots) block(c, innerL, innerW, gap, slots, k, gy + 3 * s, gh - 6 * s);
            postInvalidateOnAnimation();
        }
    }

    private void block(Canvas c, float innerL, float innerW, float gap, int slots, int k, float top, float h) {
        float l = innerL + k * (innerW + gap) / slots, r = innerL + (k + 1) * (innerW + gap) / slots - gap;
        c.drawRect(l, top, r, top + h, paint);
    }

    private java.util.List<String> wrap(String text, float width, int maxLines) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String rest = text == null ? "" : text;
        while (!rest.isEmpty() && lines.size() < maxLines) {
            int n = paint.breakText(rest, true, width, null);
            if (n < rest.length()) {
                int sp = rest.lastIndexOf(' ', n);
                if (sp > 0) n = sp;
            }
            lines.add(rest.substring(0, n).trim());
            rest = rest.substring(n).trim();
        }
        return lines;
    }
}
