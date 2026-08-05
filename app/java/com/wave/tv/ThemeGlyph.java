package com.wave.tv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The theme toggle's sun / moon / broadcast marks, drawn flat and tinted from
 * the palette. They were system emoji, which arrive full-colour in someone
 * else's drawing style and were the only such object on a screen of monochrome
 * type.
 */
class ThemeGlyph extends Drawable {
    static final int SUN = 0, MOON = 1, STATION = 2;
    private final Paint p =
            new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private int kind;

    ThemeGlyph(int sizePx) {
        size = sizePx;
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(int k) { kind = k; invalidateSelf(); }
    void tint(int c) { p.setColor(c); invalidateSelf(); }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float u = Math.min(b.width(), b.height()) / 2f;
        if (kind == SUN) {
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, u * 0.42f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, u * 0.15f));
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 4.0;
                float sx = (float) (cx + Math.cos(a) * u * 0.66f);
                float sy = (float) (cy + Math.sin(a) * u * 0.66f);
                float ex = (float) (cx + Math.cos(a) * u * 0.95f);
                float ey = (float) (cy + Math.sin(a) * u * 0.95f);
                c.drawLine(sx, sy, ex, ey, p);
            }
        } else if (kind == MOON) {
            // Crescent as the difference of two discs, so it stays a solid
            // shape at any tint rather than needing a matching backdrop.
            Path full = new Path();
            full.addCircle(cx, cy, u * 0.82f, Path.Direction.CW);
            Path bite = new Path();
            bite.addCircle(cx + u * 0.42f, cy - u * 0.24f, u * 0.72f,
                    Path.Direction.CW);
            full.op(bite, Path.Op.DIFFERENCE);
            p.setStyle(Paint.Style.FILL);
            c.drawPath(full, p);
        } else {
            // Broadcast: a mast dot with two waves off it.
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, u * 0.2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, u * 0.15f));
            for (int i = 1; i <= 2; i++) {
                float rr = u * (0.2f + 0.34f * i);
                RectF oval =
                        new RectF(cx - rr, cy - rr, cx + rr, cy + rr);
                c.drawArc(oval, -125, 70, false, p);
                c.drawArc(oval, 55, 70, false, p);
            }
        }
    }
}
