// FpsView - the small FPS / frame time readout in a corner over the game (Back menu > FPS / frame time). The port
// measures every frame it shows (TouchControls.GameState.frameStats); this only draws the numbers.
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.almostdrownedrat.dinotale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

final class FpsView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private String line = "";

    FpsView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(13 * dp);
        setVisibility(GONE);
    }

    /** fps, average and longest frame time (ms) of the last half second. */
    void set(float fps, float avgMs, float maxMs) {
        line = fps <= 0 ? "-- FPS" : String.format(java.util.Locale.ROOT, "%.0f FPS  %.1f ms  max %.1f", fps, avgMs, maxMs);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = 6 * dp, w = paint.measureText(line) + 2 * pad, h = paint.getTextSize() + 2 * pad;
        paint.setColor(0x99000000);
        c.drawRoundRect(0, 0, w, h, 6 * dp, 6 * dp, paint);
        paint.setColor(0xFFE8F5E0);
        c.drawText(line, pad, pad + paint.getTextSize() * 0.85f, paint);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float pad = 6 * dp;
        setMeasuredDimension((int) (paint.measureText("000 FPS  000.0 ms  max 000.0") + 2 * pad + 1),
                             (int) (paint.getTextSize() + 2 * pad + 1));
    }
}
