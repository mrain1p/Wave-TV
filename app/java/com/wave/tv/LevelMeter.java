package com.wave.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The five-bar level meter that sits beside the transport.
 *
 * Deliberately NOT a real level. The audio plays inside a WebView that hands us
 * no samples, so this is a motion cue that says "sound is coming out of this" —
 * the same job the mock's CSS keyframes do. It runs only while playback is
 * actually live, so it can never claim a station is on air when it isn't; the
 * moment it stops it settles to a flat low row rather than freezing mid-wave,
 * which would read as a stalled stream.
 *
 * One animator drives all five bars rather than one animator each. Every bar
 * reads a sine at its own frequency and phase, which gives the same wandering
 * look for a fifth of the timers — worth having on a box that is already
 * spending its frames decoding audio in a WebView.
 */
class LevelMeter extends View {

    private static final int BARS = 5;
    /** Seconds for one cycle of each bar. Spread, so they never march in step. */
    private static final float[] PERIOD = {0.80f, 1.10f, 0.90f, 1.05f, 0.85f};
    private static final float[] PHASE = {0f, 0.35f, 0.70f, 0.15f, 0.55f};
    private static final float MIN = 0.25f, MAX = 0.95f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int barW, gap;
    private ValueAnimator anim;
    /** Where in the cycle we are, in seconds; frozen when not running. */
    private float clock = 0f;

    LevelMeter(Context ctx, int barWidthPx, int gapPx) {
        super(ctx);
        this.barW = barWidthPx;
        this.gap = gapPx;
        paint.setStyle(Paint.Style.FILL);
    }

    void tint(int color) {
        paint.setColor(color);
        invalidate();
    }

    /** Width this needs: five bars and the four gaps between them. */
    int intrinsicWidth() {
        return BARS * barW + (BARS - 1) * gap;
    }

    /**
     * Whether it SHOULD be running, which is not the same as whether it is.
     * The picker stays in the view tree while a station is open — hidden, not
     * detached — so a meter that only stopped on detach would keep invalidating
     * behind the player for as long as the app was running.
     */
    private boolean want;

    void setRunning(boolean run) {
        want = run;
        applyRunning();
    }

    private void applyRunning() {
        setAnimating(want && isShown());
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        applyRunning();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        applyRunning();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyRunning();
    }

    private void setAnimating(boolean run) {
        if (run == (anim != null)) return;
        if (run) {
            // A long cycle rather than a short one on repeat: the bars are read
            // off a clock, so restarting it every second would visibly reset
            // every bar to its phase origin at the same instant.
            anim = ValueAnimator.ofFloat(0f, 60f);
            anim.setDuration(60_000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(null); // linear: this is a clock, not an ease
            anim.addUpdateListener(a -> {
                clock = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        } else {
            anim.cancel();
            anim = null;
            clock = 0f;
            invalidate();
        }
    }

    /** How tall bar `i` stands right now, 0..1. Flat and low when stopped. */
    private float level(int i) {
        if (anim == null) return MIN * 0.6f;
        double t = (clock / PERIOD[i] + PHASE[i]) * 2 * Math.PI;
        float wave = (float) ((Math.sin(t) + 1) / 2); // 0..1
        return MIN + wave * (MAX - MIN);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(
                resolveSize(intrinsicWidth(), widthSpec),
                resolveSize(getSuggestedMinimumHeight(), heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int h = getHeight();
        if (h <= 0) return;
        int x = 0;
        for (int i = 0; i < BARS; i++) {
            float barH = Math.max(1f, h * level(i));
            canvas.drawRect(x, h - barH, x + barW, h, paint);
            x += barW + gap;
        }
    }

    /**
     * Stop the animator when the view leaves the window, without forgetting
     * that it was meant to be running — coming back re-attaches and
     * applyRunning() picks it up again.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setAnimating(false);
    }
}
