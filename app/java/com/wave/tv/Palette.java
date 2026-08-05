package com.wave.tv;

import android.graphics.Color;

/**
 * The station picker's colours. Defaults to the app's own dark scheme and
 * shifts to whatever the on-air station is wearing, so the picker feels like
 * part of that station rather than a separate app bolted on top.
 */
final class Palette {

    final int bg, ink, muted, accent, surface;
    /** Accent, nudged for legibility as flat text over `bg` — see below. */
    final int accentText;
    /** A readable glyph color for text/icons sitting on a SOLID accent fill. */
    final int onAccent;

    Palette(int bg, int ink, int muted, int accent, int surface) {
        this.bg = bg; this.ink = ink; this.muted = muted;
        this.accent = accent; this.surface = surface;
        // A station's accent is a saturated brand color tuned to sit over
        // art, gradients, blur — nothing guarantees it reads as plain text
        // on our flat background (this is what made the on-air station's
        // name and the "NOW PLAYING" caption disappear under some station
        // themes). Keep raw `accent` for fills/dividers/borders, where it
        // carries its own visual weight, and use these guaranteed-legible
        // variants only where accent is rendered as, or sits behind, text.
        this.accentText = Colors.ensureContrast(accent, bg, 4.5);
        this.onAccent = Colors.ensureContrast(bg, accent, 4.5);
    }

    /** Straight from components, for the cross-fade — see animateToPalette. */
    Palette(int bg, int ink, int muted, int accent, int surface,
            int accentText, int onAccent) {
        this.bg = bg; this.ink = ink; this.muted = muted;
        this.accent = accent; this.surface = surface;
        this.accentText = accentText; this.onAccent = onAccent;
    }

    static final Palette DARK = new Palette(
            Colors.BG, Colors.INK, Colors.MUTED, Colors.VERMILION, Color.rgb(24, 21, 19));

    /** Paper-white counterpart, in the same family as Subwave's own light themes. */
    static final Palette LIGHT = new Palette(
            Color.rgb(243, 239, 230), Color.rgb(26, 22, 19), Color.rgb(122, 114, 104),
            Colors.VERMILION, Color.rgb(251, 249, 244));

    /** Whether this is wearing the same scheme as another — see animateToPalette. */
    boolean matches(Palette other) {
        return bg == other.bg && ink == other.ink && muted == other.muted
                && accent == other.accent && surface == other.surface;
    }

    /**
     * Read a station's five theme tokens back into a Palette, or null if the
     * page didn't give a complete set — a partial palette would look broken.
     *
     * The tokens arrive already resolved to rgb() by the page-side probe; see
     * fetchStationPalette for why that resolution can't happen here.
     */
    static Palette fromTokens(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("\"")) s = s.substring(1);
        if (s.endsWith("\"")) s = s.substring(0, s.length() - 1);
        s = s.replace("\\\"", "\"");
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\|");
        if (parts.length < 5) return null;
        int[] c = new int[5];
        for (int i = 0; i < 5; i++) {
            Integer v = Colors.parseCssRgb(parts[i]);
            if (v == null) return null;
            c[i] = v;
        }
        // The surface token is often near-identical to the background; nudge it
        // toward the ink so cards stay visible against the page.
        if (Math.abs(Color.red(c[4]) - Color.red(c[0])) < 6
                && Math.abs(Color.green(c[4]) - Color.green(c[0])) < 6
                && Math.abs(Color.blue(c[4]) - Color.blue(c[0])) < 6) {
            c[4] = Colors.blend(c[0], c[1], 0.07f);
        }
        // Some station themes pick an --ink/--muted that reads fine on their own
        // page (against art, gradients, etc.) but is too close in luminance to
        // their flat --bg to read as plain text here. Push those toward
        // whichever extreme (white/black) contrasts with the background.
        c[1] = Colors.ensureContrast(c[1], c[0], 4.5);
        c[2] = Colors.ensureContrast(c[2], c[0], 3.0);
        return new Palette(c[0], c[1], c[2], c[3], c[4]);
    }
}
