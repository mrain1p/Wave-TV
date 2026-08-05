package com.wave.tv;

import android.graphics.Color;

/**
 * Wave TV's own ink, and the colour arithmetic the picker needs to wear a
 * station's palette without anything becoming unreadable.
 *
 * Kept apart from the Activity because none of it touches Android beyond the
 * Color packing helpers: it is arithmetic, it is where the contrast rules
 * live, and it is the part of the theming most worth being able to read in
 * one sitting.
 */
final class Colors {

    private Colors() {}

    /* Wave TV's own scheme — the newsprint palette the app falls back to. */
    static final int INK = Color.rgb(243, 239, 230);
    static final int MUTED = Color.rgb(150, 145, 135);
    static final int BG = Color.rgb(16, 14, 12);
    static final int VERMILION = Color.rgb(197, 48, 42);

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static int blend(int a, int b, float ratio) {
        return Color.rgb(
                Math.round(Color.red(a) * (1 - ratio) + Color.red(b) * ratio),
                Math.round(Color.green(a) * (1 - ratio) + Color.green(b) * ratio),
                Math.round(Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio));
    }

    static double relativeLuminance(int c) {
        return channelLum(Color.red(c)) * 0.2126
                + channelLum(Color.green(c)) * 0.7152
                + channelLum(Color.blue(c)) * 0.0722;
    }

    private static double channelLum(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static double contrastRatio(int a, int b) {
        double la = relativeLuminance(a) + 0.05;
        double lb = relativeLuminance(b) + 0.05;
        return la > lb ? la / lb : lb / la;
    }

    /** Nudges fg toward white/black (whichever side bg sits farther from) until it's legible. */
    static int ensureContrast(int fg, int bg, double minRatio) {
        if (contrastRatio(fg, bg) >= minRatio) return fg;
        int extreme = relativeLuminance(bg) < 0.5 ? Color.WHITE : Color.BLACK;
        int result = fg;
        for (int step = 1; step <= 20; step++) {
            result = blend(fg, extreme, step / 20f);
            if (contrastRatio(result, bg) >= minRatio) break;
        }
        return result;
    }

    /** Parses the rgb()/rgba() form a browser always normalises colours into. */
    static Integer parseCssRgb(String v) {
        if (v == null) return null;
        int open = v.indexOf('(');
        int close = v.lastIndexOf(')');
        if (open < 0 || close <= open) return null;
        String[] nums = v.substring(open + 1, close).split("[,/\\s]+");
        if (nums.length < 3) return null;
        try {
            int r = Math.round(Float.parseFloat(nums[0].trim()));
            int g = Math.round(Float.parseFloat(nums[1].trim()));
            int b = Math.round(Float.parseFloat(nums[2].trim()));
            return Color.rgb(clamp(r), clamp(g), clamp(b));
        } catch (Exception e) {
            return null;
        }
    }

    static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
