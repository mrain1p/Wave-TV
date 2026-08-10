package com.wave.tv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The little studio mic that marks the show/DJ line.
 *
 * Drawn rather than set as an emoji for the same reason as {@link ThemeGlyph}:
 * a system emoji arrives full-colour in someone else's drawing style, and would
 * be the only such object on a screen of flat monochrome type. Tinted from the
 * palette like everything else.
 */
class MicGlyph extends Drawable {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;

    MicGlyph(int sizePx) {
        size = sizePx;
        p.setStrokeCap(Paint.Cap.ROUND);
    }

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
        float stroke = Math.max(1f, u * 0.17f);

        // Capsule head.
        p.setStyle(Paint.Style.FILL);
        float hw = u * 0.34f, top = cy - u * 0.85f, bot = cy + u * 0.1f;
        c.drawRoundRect(new RectF(cx - hw, top, cx + hw, bot), hw, hw, p);

        // Cradle arc under it, then the stem down to a foot.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        float rr = u * 0.58f;
        c.drawArc(new RectF(cx - rr, cy - rr * 0.55f, cx + rr, cy + rr * 1.05f),
                10, 160, false, p);
        c.drawLine(cx, cy + u * 0.62f, cx, cy + u * 0.86f, p);
        c.drawLine(cx - u * 0.3f, cy + u * 0.88f, cx + u * 0.3f, cy + u * 0.88f, p);
    }
}
