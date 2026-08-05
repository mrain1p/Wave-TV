package com.wave.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Wave TV — a thin Android TV / Fire TV shell around Subwave web players.
 * Named distinctly from Subwave itself: this is an unofficial community
 * client, not an official app (see the note on the station picker).
 * Opens on a native station list (add as many stations as you like); picking
 * one loads its web player in a fullscreen WebView with D-pad navigation and
 * voice dictation layered on top. No station is baked in.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "wavetv";
    private static final String KEY_STATIONS = "stations";
    private static final String KEY_LAST = "lastStation";
    private static final String KEY_SLEEP_HOURS = "sleepHours";
    private static final String KEY_THEME_MODE = "themeMode";
    private static final int SLEEP_HOURS_DEFAULT = 6;
    private static final int SLEEP_HOURS_MAX = 6;
    private static final int REQ_VOICE = 61;
    private static final long BACK_WINDOW_MS = 2500;
    /**
     * CSS viewport HEIGHT the web player renders at. The skins gate their roomy
     * layout on a 760px min-height media query — below it the DJ line clamps to
     * two lines, the waveform halves, and the centre stage bottom-aligns. A
     * width-targeted 1280 viewport landed at 720px tall on a 16:9 panel and lost
     * all three. Targeting height clears the breakpoint on any aspect ratio.
     */
    private static final int TARGET_CSS_HEIGHT = 768;
    private static final long NOW_PLAYING_POLL_MS = 7000;

    private static final int INK = Color.rgb(243, 239, 230);
    private static final int MUTED = Color.rgb(150, 145, 135);
    private static final int BG = Color.rgb(16, 14, 12);
    private static final int VERMILION = Color.rgb(197, 48, 42);

    private FrameLayout root;
    private WebView web;
    private LinearLayout stationsPanel;
    private ListView stationsListView;
    private ArrayAdapter<String> stationsAdapter;
    private final ArrayList<String> stationLabels = new ArrayList<>();
    private TextView nowPlayingText;
    private TextView npLabel;
    private TextView npMeta;
    private android.widget.Button playButton;
    private android.widget.Button sleepChip;
    private android.widget.Button addChip;
    private android.widget.Button themeChip;
    private TextView titleView, subView, noteView;
    private View accentRule;
    private GradientDrawable npBgDrawable, artBgDrawable, playBgDrawable;
    private final ArrayList<android.widget.Button> chips = new ArrayList<>();
    private final java.util.Map<android.widget.Button, GradientDrawable> chipBgs =
            new java.util.HashMap<>();
    private android.widget.ImageView npArt;
    private View onAirDot;
    private android.animation.ObjectAnimator onAirPulse;
    /** subsonic id of the artwork currently shown, so it only reloads on change. */
    private String shownCoverId = null;
    /** URLs that failed their last reachability probe — shown greyed as "offline". */
    private final java.util.Set<String> unreachable =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private String helperJs;
    private volatile boolean editableFocused = false;
    /** Which page text field currently holds focus (0 = none); see Bridge. */
    private int fieldToken = 0;
    /** True once OK has unlocked the focused field for typing. */
    private boolean fieldActivated = false;
    /** When the keyboard was last raised, to ignore the blur its resize causes. */
    private long keyboardOpenedAt = 0;
    private static final long KEYBOARD_SETTLE_MS = 1500;
    private long lastBackPress = 0;
    private boolean pageLoaded = false;
    /** True when what's in the WebView is an error page rather than the player. */
    private boolean loadFailed = false;
    private String currentUrl = null;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * The station picker's colours. Defaults to the app's own dark scheme and
     * shifts to whatever the on-air station is wearing, so the picker feels like
     * part of that station rather than a separate app bolted on top.
     */
    private static class Palette {
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
            this.accentText = ensureContrast(accent, bg, 4.5);
            this.onAccent = ensureContrast(bg, accent, 4.5);
        }
    }

    private static final Palette DEFAULT_PALETTE =
            new Palette(BG, INK, MUTED, VERMILION, Color.rgb(24, 21, 19));
    /** Paper-white counterpart, in the same family as Subwave's own light themes. */
    private static final Palette LIGHT_PALETTE = new Palette(
            Color.rgb(243, 239, 230), Color.rgb(26, 22, 19), Color.rgb(122, 114, 104),
            VERMILION, Color.rgb(251, 249, 244));

    private static final String THEME_DARK = "dark";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_STATION = "station";
    private Palette palette = DEFAULT_PALETTE;
    private android.animation.ValueAnimator paletteAnim;

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static class Station {
        String name;
        String url;
        Station(String name, String url) { this.name = name; this.url = url; }
    }

    private ArrayList<Station> stations = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Never let the framework open or close the keyboard on its own: the
        // ALWAYS_HIDDEN mode re-hides it whenever the window regains focus,
        // which is precisely what happens as the IME opens (it flickered shut).
        // Suppression is handled explicitly instead — see openKeyboard/Bridge.
        // ADJUST_NOTHING keeps the player at full size and lets the keyboard sit
        // over it, instead of squeezing the whole page into the strip above the
        // keys — which made a song request nearly unreadable.
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                        | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        buildWebView();
        buildStationsPanel();

        setContentView(root);

        stations = loadStations();
        showStations();
        if (stations.isEmpty()) {
            showStationDialog(-1); // first run: go straight to "add station"
        }
    }

    /* ------------------------------------------------------------------ */
    /* Views                                                               */
    /* ------------------------------------------------------------------ */

    private void buildWebView() {
        // TVs report a phone-ish density, so the player would see a cramped CSS
        // viewport and render its compact layout. Give the WebView a context
        // whose density puts the viewport just past the skins' 760px min-height
        // breakpoint — they then use their roomier desktop layout, themes
        // untouched. On 16:9 this works out to about 1366x768.
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int wideDpi = Math.max(120, Math.round(160f * dm.heightPixels / TARGET_CSS_HEIGHT));
        Configuration cfg = new Configuration(getResources().getConfiguration());
        cfg.densityDpi = wideDpi;
        Context webCtx = createConfigurationContext(cfg);

        web = new WebView(webCtx);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Stations are arbitrary user-entered URLs, so treat every page as
        // untrusted: it gets no route to the device's filesystem or to other
        // apps' content providers. Nothing here needs any of that — the player
        // is pure http(s) — so switching them off costs no functionality.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);

        helperJs = readAsset("tvhelper.js");
        web.addJavascriptInterface(new Bridge(), "WaveTV");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                loadFailed = false; // a fresh navigation gets a clean slate
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                if (loadFailed) {
                    // This is the server's error page, not the player. Don't
                    // inject the remote layer or try to auto-tune into it.
                    return;
                }
                if (helperJs != null) web.evaluateJavascript(helperJs, null);
                // Picking a station is the "play" gesture — dismiss the player's
                // tap-to-tune gate rather than making the viewer find it.
                web.evaluateJavascript("window.__swtvAutoTuneIn && __swtvAutoTuneIn()", null);
                // Then pick up the resulting state so the sleep countdown starts
                // from when sound actually begins.
                ui.postDelayed(MainActivity.this::syncPlaybackState, 3500);
                // Adopt this station's colours for the picker. A per-show theme
                // override can take longer than any fixed delay to actually land
                // (it's fetched over the network and diffed against whichever
                // show is on air), so rather than guess a wait, keep re-sampling
                // in the background for as long as this station is loaded —
                // startPalettePoll reschedules itself and simply stops mattering
                // once the reading is settled.
                ui.removeCallbacks(palettePoll);
                ui.postDelayed(palettePoll, 800);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.equals(view.getUrl())) {
                    loadFailed = true;
                    showLoadError(description);
                }
            }

            /**
             * A reachable server that answers with an error status — 502 while
             * the station is down behind a reverse proxy, 503, 404, and so on.
             * onReceivedError above never fires for these: the request
             * succeeded at the network level, so without this the shell would
             * treat a gateway error page as a perfectly good player.
             */
            @Override
            public void onReceivedHttpError(WebView view,
                                            android.webkit.WebResourceRequest request,
                                            android.webkit.WebResourceResponse errorResponse) {
                if (request == null || !request.isForMainFrame()) return; // ignore sub-resources
                loadFailed = true;
                int code = errorResponse != null ? errorResponse.getStatusCode() : 0;
                showLoadError(code == 502 || code == 503
                        ? "The station's server answered " + code + " — it's likely still starting up."
                        : "The station's server answered HTTP " + code + ".");
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view,
                                                  android.webkit.HttpAuthHandler handler,
                                                  String host, String realm) {
                // Reuse a stored credential silently; otherwise ask. useHttpAuthUsernamePassword
                // is false on a retry, which means the stored one was rejected.
                String saved = currentUrl == null ? null : savedAuth(currentUrl);
                if (saved != null && handler.useHttpAuthUsernamePassword()) {
                    try {
                        String[] parts = new String(android.util.Base64.decode(saved,
                                android.util.Base64.NO_WRAP), StandardCharsets.UTF_8).split(":", 2);
                        if (parts.length == 2) { handler.proceed(parts[0], parts[1]); return; }
                    } catch (Exception ignored) {}
                }
                if (saved != null) prefs().edit().remove(authKey(currentUrl)).apply();
                promptForCredentials(handler, currentUrl);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Fresh LayoutParams per call — header chips need their own instance, not a shared one. */
    private LinearLayout.LayoutParams headerChipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        return lp;
    }

    private void buildStationsPanel() {
        stationsPanel = new LinearLayout(this);
        stationsPanel.setOrientation(LinearLayout.VERTICAL);
        stationsPanel.setBackgroundColor(BG);
        stationsPanel.setPadding(dp(64), dp(32), dp(64), dp(20));

        // Masthead: wordmark and hints on the left, sleep-timer chip on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout headings = new LinearLayout(this);
        headings.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(this);
        titleView.setText("WAVE TV");
        titleView.setTextColor(INK);
        titleView.setTextSize(30);
        titleView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        headings.addView(titleView);

        subView = new TextView(this);
        subView.setText("STATIONS  ·  OK tune in  ·  hold OK for options  ·  MENU for settings");
        subView.setTextColor(MUTED);
        subView.setTextSize(13);
        subView.setPadding(0, dp(4), 0, 0);
        headings.addView(subView);

        header.addView(headings, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        addChip = buildChip("+  Add station", v -> showStationDialog(-1));
        header.addView(addChip, headerChipLp());

        themeChip = buildChip("", v -> cycleThemeMode());
        themeChip.setTextSize(17);          // emoji-only, so give the glyph room
        themeChip.setPadding(dp(15), dp(7), dp(15), dp(8));
        header.addView(themeChip, headerChipLp());

        sleepChip = buildChip("☾  6h", v -> showSleepDialog());
        header.addView(sleepChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(16);
        stationsPanel.addView(header, headerLp);
        updateSleepChip();
        updateThemeChip();

        accentRule = new View(this);
        accentRule.setBackgroundColor(VERMILION);
        stationsPanel.addView(accentRule, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));

        stationsListView = new ListView(this);
        stationsListView.setBackgroundColor(BG);
        stationsListView.setDivider(new android.graphics.drawable.ColorDrawable(Color.argb(28, 243, 239, 230)));
        stationsListView.setDividerHeight(dp(1));
        stationsListView.setSelector(new android.graphics.drawable.ColorDrawable(Color.argb(60, 197, 48, 42)));
        stationsAdapter = new ArrayAdapter<String>(this, 0, stationLabels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                // Deliberately name-only: a private station's address is a
                // secret worth keeping, and this screen is the one most likely
                // to end up in a screenshot. The address stays reachable via
                // hold-OK > Edit.
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(14), dp(16), dp(14), dp(16));
                Station st = stations.get(position);
                boolean playing = st.url.equals(currentUrl);
                boolean offline = unreachable.contains(st.url);
                TextView name = new TextView(MainActivity.this);
                name.setText(String.format(Locale.US, "%02d  %s%s", position + 1, st.name,
                        playing ? "   · ON AIR" : (offline ? "   · offline" : "")));
                name.setTextColor(playing ? palette.accentText : (offline ? palette.muted : palette.ink));
                name.setTextSize(20);
                name.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                row.addView(name);
                return row;
            }
        };
        stationsListView.setAdapter(stationsAdapter);
        stationsListView.setOnItemClickListener((parent, view, pos, id) -> openStation(pos));
        stationsListView.setOnItemLongClickListener((parent, view, pos, id) -> {
            showStationOptions(pos);
            return true;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        stationsPanel.addView(stationsListView, lp);

        // --- now-playing card -------------------------------------------
        LinearLayout npCard = new LinearLayout(this);
        npCard.setOrientation(LinearLayout.HORIZONTAL);
        npCard.setGravity(Gravity.CENTER_VERTICAL);
        npBgDrawable = new GradientDrawable();
        npBgDrawable.setColor(Color.rgb(24, 21, 19));
        npBgDrawable.setCornerRadius(dp(8));
        npBgDrawable.setStroke(dp(1), Color.argb(70, 243, 239, 230));
        npCard.setBackground(npBgDrawable);
        npCard.setPadding(dp(14), dp(12), dp(18), dp(12));

        // Album art — a placeholder square until the first cover lands.
        npArt = new android.widget.ImageView(this);
        npArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        artBgDrawable = new GradientDrawable();
        artBgDrawable.setColor(Color.rgb(34, 30, 27));
        artBgDrawable.setCornerRadius(dp(4));
        artBgDrawable.setStroke(dp(1), Color.argb(60, 243, 239, 230));
        npArt.setBackground(artBgDrawable);
        npArt.setClipToOutline(true);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(62), dp(62));
        artLp.rightMargin = dp(16);
        npCard.addView(npArt, artLp);

        playButton = new android.widget.Button(this);
        playButton.setText("▶");
        playButton.setTextSize(20);
        playButton.setAllCaps(false);
        playButton.setTextColor(INK);
        playButton.setMinWidth(0);
        playButton.setMinimumWidth(0);
        playButton.setMinHeight(0);
        playButton.setMinimumHeight(0);
        playButton.setPadding(dp(18), dp(6), dp(18), dp(8));
        playBgDrawable = new GradientDrawable();
        playBgDrawable.setColor(Color.argb(80, 197, 48, 42));
        playBgDrawable.setCornerRadius(dp(28));
        playBgDrawable.setStroke(dp(1), VERMILION);
        playButton.setBackground(playBgDrawable);
        playButton.setOnFocusChangeListener((v, has) -> stylePlayButton(has));
        playButton.setOnClickListener(v -> togglePlayFromList());
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.rightMargin = dp(18);
        npCard.addView(playButton, pbLp);

        LinearLayout npText = new LinearLayout(this);
        npText.setOrientation(LinearLayout.VERTICAL);

        // Label row: a softly pulsing on-air lamp beside the status caption.
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);

        onAirDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(VERMILION);
        onAirDot.setBackground(dot);
        onAirDot.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(7);
        labelRow.addView(onAirDot, dotLp);

        onAirPulse = android.animation.ObjectAnimator.ofFloat(onAirDot, "alpha", 1f, 0.3f);
        onAirPulse.setDuration(1150);
        onAirPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        onAirPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);

        npLabel = new TextView(this);
        npLabel.setText("NOW PLAYING");
        npLabel.setTextColor(VERMILION);
        npLabel.setTextSize(9);
        npLabel.setTypeface(Typeface.MONOSPACE);
        npLabel.setLetterSpacing(0.3f);
        labelRow.addView(npLabel);
        npText.addView(labelRow);

        nowPlayingText = new TextView(this);
        nowPlayingText.setText("nothing tuned yet");
        nowPlayingText.setTextColor(INK);
        nowPlayingText.setTextSize(18);
        nowPlayingText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        nowPlayingText.setSingleLine(true);
        nowPlayingText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlayingText.setPadding(0, dp(2), 0, 0);
        npText.addView(nowPlayingText);

        npMeta = new TextView(this);
        npMeta.setText("pick a station above to tune in");
        npMeta.setTextColor(MUTED);
        npMeta.setTextSize(12);
        npMeta.setTypeface(Typeface.MONOSPACE);
        npMeta.setSingleLine(true);
        npMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        npMeta.setPadding(0, dp(3), 0, 0);
        npText.addView(npMeta);

        npCard.addView(npText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(14);
        stationsPanel.addView(npCard, cardLp);

        noteView = new TextView(this);
        noteView.setText("an unofficial community player for Subwave stations   ·   " + appVersion());
        noteView.setTextColor(Color.argb(115, 150, 145, 135));
        noteView.setTextSize(10);
        noteView.setTypeface(Typeface.MONOSPACE);
        noteView.setGravity(Gravity.CENTER_HORIZONTAL);
        noteView.setPadding(0, dp(10), 0, 0);
        stationsPanel.addView(noteView);

        stationsPanel.setVisibility(View.GONE);
        root.addView(stationsPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Paint once up front from the saved mode. Without this a remembered
        // Dark choice would only ever match because the views happen to be
        // constructed in those colours — an accident, not a guarantee.
        if (THEME_LIGHT.equals(themeMode())) palette = LIGHT_PALETTE;
        applyPalette();
        applyPaletteToRows();
    }

    /* ------------------------------------------------------------------ */
    /* Station colour scheme                                               */
    /* ------------------------------------------------------------------ */

    private void stylePlayButton(boolean focused) {
        if (playBgDrawable == null) return;
        playBgDrawable.setColor(focused ? palette.accent : withAlpha(palette.accent, 80));
        playBgDrawable.setStroke(dp(1), palette.accent);
        playButton.setTextColor(focused ? palette.onAccent : palette.ink);
    }

    /**
     * Paint the picker from the current palette. Called every frame of the
     * cross-fade, so it deliberately leaves the list rows alone — rebuilding
     * them at 60fps would drop the viewer's D-pad selection mid-animation.
     * applyPaletteToRows() handles those once the fade settles.
     */
    private void applyPalette() {
        stationsPanel.setBackgroundColor(palette.bg);
        stationsListView.setBackgroundColor(palette.bg);

        titleView.setTextColor(palette.ink);
        subView.setTextColor(palette.muted);
        accentRule.setBackgroundColor(palette.accent);
        noteView.setTextColor(withAlpha(palette.muted, 150));

        npBgDrawable.setColor(palette.surface);
        npBgDrawable.setStroke(dp(1), withAlpha(palette.ink, 70));
        artBgDrawable.setColor(withAlpha(palette.ink, 26));
        artBgDrawable.setStroke(dp(1), withAlpha(palette.ink, 60));

        npLabel.setTextColor(palette.accentText);
        nowPlayingText.setTextColor(currentUrl == null ? palette.muted : palette.ink);
        npMeta.setTextColor(palette.muted);
        ((GradientDrawable) onAirDot.getBackground()).setColor(palette.accent);

        stylePlayButton(playButton.hasFocus());
        for (android.widget.Button c : chips) styleChip(c, c.hasFocus());
    }

    /** The row-level colours, applied once rather than per animation frame. */
    private void applyPaletteToRows() {
        stationsListView.setDivider(new android.graphics.drawable.ColorDrawable(
                withAlpha(palette.ink, 28)));
        stationsListView.setDividerHeight(dp(1));
        stationsListView.setSelector(new android.graphics.drawable.ColorDrawable(
                withAlpha(palette.accent, 60)));
        stationsAdapter.notifyDataSetChanged();
    }

    /** Cross-fade to a new palette so the picker doesn't snap between schemes. */
    private void animateToPalette(final Palette target) {
        final Palette from = palette;
        if (from.bg == target.bg && from.ink == target.ink && from.muted == target.muted
                && from.accent == target.accent && from.surface == target.surface) {
            return; // already wearing it
        }
        if (!stationsVisible()) {
            // Nothing on screen to animate — this is the background poll
            // catching up. Jump straight there instead of ticking an invisible
            // 60fps fade.
            if (paletteAnim != null) paletteAnim.cancel();
            palette = target;
            applyPalette();
            applyPaletteToRows();
            return;
        }
        if (paletteAnim != null) paletteAnim.cancel();
        final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        paletteAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        paletteAnim.setDuration(420);
        paletteAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            palette = new Palette(
                    (int) ev.evaluate(t, from.bg, target.bg),
                    (int) ev.evaluate(t, from.ink, target.ink),
                    (int) ev.evaluate(t, from.muted, target.muted),
                    (int) ev.evaluate(t, from.accent, target.accent),
                    (int) ev.evaluate(t, from.surface, target.surface));
            applyPalette();
        });
        paletteAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                palette = target;
                applyPalette();
                applyPaletteToRows();
            }
        });
        paletteAnim.start();
    }

    private String themeMode() {
        return prefs().getString(KEY_THEME_MODE, THEME_STATION);
    }

    /** Cycles dark → light → station, so one button covers all three. */
    private void cycleThemeMode() {
        String m = themeMode();
        String next = THEME_DARK.equals(m) ? THEME_LIGHT
                : THEME_LIGHT.equals(m) ? THEME_STATION
                : THEME_DARK;
        prefs().edit().putString(KEY_THEME_MODE, next).apply();
        updateThemeChip();
        refreshPalette();
    }

    private void updateThemeChip() {
        if (themeChip == null) return;
        String m = themeMode();
        themeChip.setText(THEME_DARK.equals(m) ? "🌙" : THEME_LIGHT.equals(m) ? "☀️" : "📡");
        themeChip.setContentDescription(THEME_DARK.equals(m) ? "Dark theme"
                : THEME_LIGHT.equals(m) ? "Light theme" : "Match the station's theme");
    }

    /** Repaint for whichever scheme is selected. */
    private void refreshPalette() {
        String m = themeMode();
        if (THEME_DARK.equals(m)) { animateToPalette(DEFAULT_PALETTE); return; }
        if (THEME_LIGHT.equals(m)) { animateToPalette(LIGHT_PALETTE); return; }
        fetchStationPalette();
    }

    private static final long PALETTE_POLL_MS = 6000;

    /**
     * Keeps re-sampling the loaded page's colours for as long as it's on
     * screen. There's no single moment a theme is guaranteed to have settled
     * (a per-show override applies after an async fetch+diff against whatever
     * show is on air), so instead of picking a delay, this just keeps
     * checking — animateToPalette's own early-return makes a correct reading
     * a no-op, so this costs nothing once the picker matches the page.
     */
    private final Runnable palettePoll = new Runnable() {
        @Override
        public void run() {
            if (pageLoaded && currentUrl != null && THEME_STATION.equals(themeMode())) {
                fetchStationPalette();
            }
            ui.postDelayed(this, PALETTE_POLL_MS);
        }
    };

    /**
     * Ask the loaded player for its resolved theme colours. The tokens are
     * authored in oklch()/color-mix(), which nothing here could parse — so a
     * 1x1 canvas resolves them for us: canvas fillStyle accepts any valid CSS
     * colour syntax and getImageData always reads back plain 0-255 sRGB bytes,
     * regardless of what colour function produced them.
     *
     * An earlier version asked getComputedStyle for the resolved `color` of a
     * throwaway element instead — but this WebView's Chromium reports that
     * computed value back in the SAME oklch()/etc. form it was declared in
     * rather than downgrading it to rgb(). The rgb()-only parser then read
     * oklch()'s three numbers (0-1, 0-0.4, 0-360) as if they were red/green/
     * blue bytes, which is why every hex-declared token (bg, ink) came through
     * fine while oklch()-declared accents came out as whatever that
     * misinterpretation happened to produce (a cyberpunk theme's
     * oklch(0.72 0.28 340) pink read as rgb(1,0,255) — pure blue).
     */
    private void fetchStationPalette() {
        if (!pageLoaded || currentUrl == null) {
            if (palette != DEFAULT_PALETTE) animateToPalette(DEFAULT_PALETTE);
            return;
        }
        String js =
                "(function(){try{" +
                "var cs=getComputedStyle(document.documentElement);" +
                "var cv=document.createElement('canvas');cv.width=1;cv.height=1;" +
                "var ctx=cv.getContext('2d');" +
                "function r(v){if(!v)return '';try{" +
                "ctx.fillStyle='#000';ctx.fillStyle=v;ctx.fillRect(0,0,1,1);" +
                "var d=ctx.getImageData(0,0,1,1).data;" +
                "return 'rgb('+d[0]+','+d[1]+','+d[2]+')';" +
                "}catch(e){return '';}}" +
                "var k=['--bg','--ink','--muted','--accent','--surface'],o=[];" +
                "for(var i=0;i<k.length;i++)o.push(r((cs.getPropertyValue(k[i])||'').trim()));" +
                "return o.join('|');}catch(e){return '';}})()";
        web.evaluateJavascript(js, r -> {
            Palette p = parsePalette(r);
            if (p != null) animateToPalette(p);
        });
    }

    /** "\"rgb(1, 2, 3)|rgb(…)|…\"" from evaluateJavascript → a Palette. */
    private Palette parsePalette(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("\"")) s = s.substring(1);
        if (s.endsWith("\"")) s = s.substring(0, s.length() - 1);
        s = s.replace("\\\"", "\"");
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\|");
        if (parts.length < 5) return null;
        int[] c = new int[5];
        int[] fallback = {DEFAULT_PALETTE.bg, DEFAULT_PALETTE.ink, DEFAULT_PALETTE.muted,
                DEFAULT_PALETTE.accent, DEFAULT_PALETTE.surface};
        for (int i = 0; i < 5; i++) {
            Integer v = parseCssRgb(parts[i]);
            if (v == null) return null; // a partial palette would look broken
            c[i] = v;
        }
        // The surface token is often near-identical to the background; nudge it
        // toward the ink so cards stay visible against the page.
        if (Math.abs(Color.red(c[4]) - Color.red(c[0])) < 6
                && Math.abs(Color.green(c[4]) - Color.green(c[0])) < 6
                && Math.abs(Color.blue(c[4]) - Color.blue(c[0])) < 6) {
            c[4] = blend(c[0], c[1], 0.07f);
        }
        // Some station themes pick an --ink/--muted that reads fine on their own
        // page (against art, gradients, etc.) but is too close in luminance to
        // their flat --bg to read as plain text here. Push those toward
        // whichever extreme (white/black) contrasts with the background.
        c[1] = ensureContrast(c[1], c[0], 4.5);
        c[2] = ensureContrast(c[2], c[0], 3.0);
        return new Palette(c[0], c[1], c[2], c[3], c[4]);
    }

    private static double relativeLuminance(int c) {
        return channelLum(Color.red(c)) * 0.2126
                + channelLum(Color.green(c)) * 0.7152
                + channelLum(Color.blue(c)) * 0.0722;
    }

    private static double channelLum(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double contrastRatio(int a, int b) {
        double la = relativeLuminance(a) + 0.05;
        double lb = relativeLuminance(b) + 0.05;
        return la > lb ? la / lb : lb / la;
    }

    /** Nudges fg toward white/black (whichever side bg sits farther from) until it's legible. */
    private static int ensureContrast(int fg, int bg, double minRatio) {
        if (contrastRatio(fg, bg) >= minRatio) return fg;
        int extreme = relativeLuminance(bg) < 0.5 ? Color.WHITE : Color.BLACK;
        int result = fg;
        for (int step = 1; step <= 20; step++) {
            result = blend(fg, extreme, step / 20f);
            if (contrastRatio(result, bg) >= minRatio) break;
        }
        return result;
    }

    private static int blend(int a, int b, float ratio) {
        return Color.rgb(
                Math.round(Color.red(a) * (1 - ratio) + Color.red(b) * ratio),
                Math.round(Color.green(a) * (1 - ratio) + Color.green(b) * ratio),
                Math.round(Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio));
    }

    /** Parses the rgb()/rgba() form a browser always normalises colours into. */
    private static Integer parseCssRgb(String v) {
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

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /* ------------------------------------------------------------------ */
    /* Now-playing poller (station list footer)                            */
    /* ------------------------------------------------------------------ */

    private final Runnable nowPlayingPoll = new Runnable() {
        @Override
        public void run() {
            if (!stationsVisible()) return;
            if (currentUrl == null) {
                npLabel.setText("NOT TUNED");
                nowPlayingText.setText("nothing tuned yet");
                nowPlayingText.setTextColor(palette.muted);
                npMeta.setText("pick a station above to tune in");
                setPlayGlyph(false);
                setOnAir(false);
                setCover(null);
            } else {
                // Ask the page whether audio is actually rolling.
                syncPlaybackState();
                final String base = currentUrl.endsWith("/") ? currentUrl : currentUrl + "/";
                new Thread(() -> {
                    String title = null, meta = null, coverId = null;
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(base + "api/now-playing").openConnection();
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(4000);
                        applyAuth(c, base);
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(
                                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                            String l;
                            while ((l = r.readLine()) != null) sb.append(l);
                        }
                        JSONObject o = new JSONObject(sb.toString());
                        JSONObject np = o.optJSONObject("nowPlaying");
                        JSONObject dj = o.optJSONObject("dj");
                        int listeners = o.optInt("listeners", -1);
                        if (np != null) {
                            title = np.optString("title", "—");
                            coverId = np.optString("subsonic_id", null);
                            StringBuilder m = new StringBuilder(np.optString("artist", ""));
                            String album = np.optString("album", "");
                            if (!album.isEmpty()) m.append("  ·  ").append(album);
                            if (dj != null) {
                                String djName = dj.optString("name", "");
                                if (!djName.isEmpty()) m.append("  ·  ").append(djName);
                            }
                            if (listeners >= 0) m.append("  ·  ").append(listeners)
                                    .append(listeners == 1 ? " listener" : " listeners");
                            meta = m.toString();
                        }
                    } catch (Exception ignored) {}
                    final String fTitle = title, fMeta = meta, fCover = coverId;
                    ui.post(() -> {
                        if (!stationsVisible()) return;
                        if (fTitle != null) {
                            npLabel.setText("NOW PLAYING");
                            nowPlayingText.setText(fTitle);
                            nowPlayingText.setTextColor(palette.ink);
                            npMeta.setText(fMeta);
                            loadCover(base, fCover);
                        } else {
                            npLabel.setText("OFF AIR");
                            nowPlayingText.setText("station not responding");
                            nowPlayingText.setTextColor(palette.muted);
                            // Name, not address — same screenshot reasoning as
                            // the station rows above.
                            npMeta.setText(currentStationName());
                            setOnAir(false);
                            setCover(null);
                        }
                    });
                }).start();
            }
            ui.postDelayed(this, NOW_PLAYING_POLL_MS);
        }
    };

    /** The on-air lamp: a slow alpha breath, only while audio is actually live. */
    private void setOnAir(boolean live) {
        if (live) {
            onAirDot.setVisibility(View.VISIBLE);
            if (!onAirPulse.isStarted()) onAirPulse.start();
        } else {
            if (onAirPulse.isStarted()) onAirPulse.cancel();
            onAirDot.setAlpha(1f);
            onAirDot.setVisibility(View.INVISIBLE);
        }
    }

    private void setCover(android.graphics.Bitmap bmp) {
        if (bmp == null) {
            shownCoverId = null;
            npArt.setImageDrawable(null);
        } else {
            npArt.setImageBitmap(bmp);
        }
    }

    /** Pull album art through the station's cover proxy; only on a track change. */
    private void loadCover(final String base, final String subsonicId) {
        if (subsonicId == null || subsonicId.isEmpty()) { setCover(null); return; }
        if (subsonicId.equals(shownCoverId)) return;
        shownCoverId = subsonicId;
        new Thread(() -> {
            android.graphics.Bitmap bmp = null;
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL(base + "api/cover/" + java.net.URLEncoder.encode(subsonicId, "UTF-8"))
                                .openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                applyAuth(c, base);
                try (InputStream in = c.getInputStream()) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 2; // the card renders it small
                    bmp = android.graphics.BitmapFactory.decodeStream(in, null, o);
                }
            } catch (Exception ignored) {}
            final android.graphics.Bitmap out = bmp;
            ui.post(() -> {
                if (out != null && subsonicId.equals(shownCoverId)) npArt.setImageBitmap(out);
                else if (out == null && subsonicId.equals(shownCoverId)) npArt.setImageDrawable(null);
            });
        }).start();
    }

    /* ------------------------------------------------------------------ */
    /* Password-protected stations (HTTP basic auth)                       */
    /* ------------------------------------------------------------------ */

    /** Credentials are keyed by origin so every station keeps its own. */
    private static String authKey(String url) {
        try {
            URL u = new URL(url);
            return "auth:" + u.getProtocol() + "://" + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return "auth:" + url;
        }
    }

    private String savedAuth(String url) {
        return prefs().getString(authKey(url), null);
    }

    /** Attach a stored Basic credential, if this station has one. */
    private void applyAuth(HttpURLConnection c, String url) {
        String cred = savedAuth(url);
        if (cred != null) c.setRequestProperty("Authorization", "Basic " + cred);
    }

    /**
     * A station behind HTTP basic auth. Ask once, optionally remember, and hand
     * the credential to the WebView; the same value is reused for the
     * now-playing and cover-art calls.
     */
    private void promptForCredentials(final android.webkit.HttpAuthHandler handler, final String url) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(palette.bg);
        box.setPadding(dp(28), dp(20), dp(28), dp(10));

        TextView heading = new TextView(this);
        heading.setText("STATION IS LOCKED");
        heading.setTextColor(palette.ink);
        heading.setTextSize(18);
        heading.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        heading.setLetterSpacing(0.2f);
        box.addView(heading);

        TextView blurb = new TextView(this);
        blurb.setText("This station asks for a username and password.");
        blurb.setTextColor(palette.muted);
        blurb.setTextSize(12);
        blurb.setTypeface(Typeface.MONOSPACE);
        blurb.setPadding(0, dp(6), 0, 0);
        box.addView(blurb);

        box.addView(dlgCaption("USERNAME"));
        final EditText userIn = dlgField("username");
        box.addView(userIn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(dlgCaption("PASSWORD"));
        final EditText passIn = dlgField("password");
        passIn.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(passIn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final android.widget.CheckBox remember = new android.widget.CheckBox(this);
        remember.setText("Remember on this device");
        remember.setTextColor(palette.muted);
        remember.setTextSize(12);
        remember.setChecked(true);
        remember.setPadding(0, dp(12), 0, 0);
        box.addView(remember);

        new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("Sign in", (d, w) -> {
                    String u = userIn.getText().toString();
                    String p = passIn.getText().toString();
                    if (remember.isChecked()) {
                        String enc = android.util.Base64.encodeToString(
                                (u + ":" + p).getBytes(StandardCharsets.UTF_8),
                                android.util.Base64.NO_WRAP);
                        prefs().edit().putString(authKey(url), enc).apply();
                    }
                    handler.proceed(u, p);
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    handler.cancel();
                    showStations();
                })
                .setOnCancelListener(d -> { handler.cancel(); showStations(); })
                .show();
    }

    private void setPlayGlyph(boolean playing) {
        playButton.setText(playing ? "❚❚" : "▶");
        playButton.setTextSize(playing ? 15 : 20);
    }

    /** Play/pause from the station list — tunes the highlighted station if idle. */
    private void togglePlayFromList() {
        if (currentUrl == null || !pageLoaded) {
            int sel = stationsListView.getSelectedItemPosition();
            if (sel < 0 || sel >= stations.size()) sel = prefs().getInt(KEY_LAST, 0);
            if (sel >= 0 && sel < stations.size()) openStation(sel);
            return;
        }
        js("window.__swtvKey && __swtvKey('k')");
        ui.postDelayed(this::syncPlaybackState, 400);
    }

    /**
     * Read the player's real audio state and mirror it: the transport glyph, the
     * on-air lamp, and the sleep countdown (which only runs while sound is
     * actually coming out).
     */
    private void syncPlaybackState() {
        if (!pageLoaded) return;
        web.evaluateJavascript("window.__swtvTuned ? __swtvTuned() : false", r -> {
            boolean playing = r != null && r.contains("true");
            setPlayGlyph(playing);
            setOnAir(playing);
            if (playing) armSleepTimer();
            else cancelSleepTimer();
        });
    }

    /* ------------------------------------------------------------------ */
    /* Station list state                                                  */
    /* ------------------------------------------------------------------ */

    private void refreshStationList() {
        stationLabels.clear();
        for (Station st : stations) stationLabels.add(st.name);
        stationsAdapter.notifyDataSetChanged();
    }

    private boolean stationsVisible() {
        return stationsPanel.getVisibility() == View.VISIBLE;
    }

    private void showStations() {
        refreshStationList();
        stationsPanel.setVisibility(View.VISIBLE);
        stationsListView.requestFocus();
        int last = prefs().getInt(KEY_LAST, 0);
        if (last >= 0 && last < stations.size()) stationsListView.setSelection(last);
        ui.removeCallbacks(nowPlayingPoll);
        ui.post(nowPlayingPoll);
        probeStations();
        refreshPalette();
    }

    /**
     * Ping every station's liveness endpoint so the list can grey out the ones
     * that aren't answering — useful when a LAN station is off or the TV moved
     * networks. Cheap: /health returns as soon as the controller is up.
     */
    private void probeStations() {
        final ArrayList<String> urls = new ArrayList<>();
        for (Station st : stations) urls.add(st.url);
        for (final String url : urls) {
            new Thread(() -> {
                boolean ok = false;
                try {
                    String base = url.endsWith("/") ? url : url + "/";
                    HttpURLConnection c = (HttpURLConnection) new URL(base + "api/health").openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(3000);
                    applyAuth(c, base);
                    int code = c.getResponseCode();
                    ok = code >= 200 && code < 500; // a 401 still means it's there
                    c.disconnect();
                } catch (Exception ignored) {}
                final boolean reachable = ok;
                ui.post(() -> {
                    boolean changed = reachable ? unreachable.remove(url) : unreachable.add(url);
                    if (changed && stationsVisible()) stationsAdapter.notifyDataSetChanged();
                });
            }).start();
        }
    }

    private void openStation(int index) {
        if (index < 0 || index >= stations.size()) return;
        Station st = stations.get(index);
        prefs().edit().putInt(KEY_LAST, index).apply();
        stationsPanel.setVisibility(View.GONE);
        ui.removeCallbacks(nowPlayingPoll);
        pageLoaded = false;
        if (!st.url.equals(currentUrl) || loadFailed) {
            // Reload on a different station, and also when what's currently
            // sitting in the WebView is an error page — otherwise re-picking
            // the station just re-showed the stale failure and the only way
            // back was Menu > Reload, even once the server had recovered.
            currentUrl = st.url;
            loadStation(st.url);
        } else {
            pageLoaded = true; // returning to a healthy station: keep it playing
        }
        web.requestFocus();
    }

    /** The tuned station's display name, for UI that must not show its address. */
    private String currentStationName() {
        if (currentUrl == null) return "";
        for (Station st : stations) {
            if (st.url.equals(currentUrl)) return st.name;
        }
        return "";
    }

    /**
     * Load a station, bypassing the HTTP cache. A proxy's 502 page can carry
     * caching headers, so a plain reload risks re-serving the stored failure
     * rather than asking the recovered server.
     */
    private void loadStation(String url) {
        pageLoaded = false;
        loadFailed = false;
        shownCoverId = null;
        web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.loadUrl(url);
        // Back to normal caching once this navigation is under way, so ordinary
        // browsing still benefits from the cache.
        ui.postDelayed(() -> web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT), 5000);
    }

    /* ------------------------------------------------------------------ */
    /* Add / edit / delete stations                                        */
    /* ------------------------------------------------------------------ */

    /* --- small styled-widget helpers for the station dialog ------------- */

    private TextView dlgCaption(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(palette.muted);
        t.setTextSize(11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLetterSpacing(0.25f);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private GradientDrawable fieldBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(blend(palette.bg, palette.ink, 0.06f));
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), withAlpha(palette.ink, 90));
        return bg;
    }

    /** Accent outline while the view holds D-pad focus. */
    private void strokeOnFocus(View v, final GradientDrawable bg) {
        v.setOnFocusChangeListener((view, has) ->
                bg.setStroke(dp(has ? 2 : 1), has ? palette.accent : withAlpha(palette.ink, 90)));
    }

    private EditText dlgField(String hintText) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hintText);
        e.setTextColor(palette.ink);
        e.setHintTextColor(withAlpha(palette.muted, 130));
        e.setTextSize(16);
        e.setTypeface(Typeface.MONOSPACE);
        GradientDrawable bg = fieldBg();
        e.setBackground(bg);
        strokeOnFocus(e, bg);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        // Landing on the field with the D-pad shows no keyboard; pressing OK
        // (which fires onClick) is what opens it.
        e.setShowSoftInputOnFocus(false);
        e.setOnClickListener(v -> {
            e.setShowSoftInputOnFocus(true);
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
        });
        return e;
    }

    private android.widget.Button dlgButton(String label) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.MONOSPACE);
        b.setTextColor(palette.muted);
        GradientDrawable bg = fieldBg();
        b.setBackground(bg);
        strokeOnFocus(b, bg);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    /** index -1 = add new; otherwise edit that station. */
    private void showStationDialog(final int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(palette.bg);
        box.setPadding(dp(28), dp(20), dp(28), dp(14));

        TextView heading = new TextView(this);
        heading.setText(index < 0 ? "ADD STATION" : "EDIT STATION");
        heading.setTextColor(palette.ink);
        heading.setTextSize(19);
        heading.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        heading.setLetterSpacing(0.2f);
        box.addView(heading);

        View rule = new View(this);
        rule.setBackgroundColor(palette.accent);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(dp(56), dp(2));
        ruleLp.topMargin = dp(6);
        box.addView(rule, ruleLp);

        // --- name -------------------------------------------------------
        // No mic buttons here: the TV's own keyboard already offers
        // press-and-hold-to-speak once a field is opened.
        box.addView(dlgCaption("NAME  (OPTIONAL)"));
        final EditText nameIn = dlgField("leave blank to use the station's own name");
        box.addView(nameIn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- address ----------------------------------------------------
        box.addView(dlgCaption("ADDRESS"));
        final boolean[] useHttps = {false};
        LinearLayout schemeRow = new LinearLayout(this);
        schemeRow.setOrientation(LinearLayout.HORIZONTAL);
        final android.widget.Button httpBtn = dlgButton("http://");
        final android.widget.Button httpsBtn = dlgButton("https://");
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.rightMargin = dp(8);
        schemeRow.addView(httpBtn, sLp);
        schemeRow.addView(httpsBtn, sLp);
        box.addView(schemeRow);

        final int schemeOff = blend(palette.bg, palette.ink, 0.06f);
        final int schemeOn = withAlpha(palette.accent, 70);
        final Runnable styleScheme = () -> {
            httpBtn.setTextColor(useHttps[0] ? palette.muted : palette.ink);
            ((GradientDrawable) httpBtn.getBackground())
                    .setColor(useHttps[0] ? schemeOff : schemeOn);
            httpsBtn.setTextColor(useHttps[0] ? palette.ink : palette.muted);
            ((GradientDrawable) httpsBtn.getBackground())
                    .setColor(useHttps[0] ? schemeOn : schemeOff);
        };
        httpBtn.setOnClickListener(v -> { useHttps[0] = false; styleScheme.run(); });
        httpsBtn.setOnClickListener(v -> { useHttps[0] = true; styleScheme.run(); });

        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlLp.topMargin = dp(8);
        final EditText hostIn = dlgField("192.168.1.x:7700  or  radio.example.com");
        box.addView(hostIn, urlLp);

        if (index >= 0) {
            Station st = stations.get(index);
            nameIn.setText(st.name);
            useHttps[0] = st.url.startsWith("https://");
            hostIn.setText(st.url.replaceFirst("^https?://", ""));
        }
        styleScheme.run();

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String name = nameIn.getText().toString().trim();
                    String host = hostIn.getText().toString().trim()
                            .replaceFirst("^https?://", "");
                    if (host.isEmpty()) {
                        Toast.makeText(this, "A station needs an address", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String url = (useHttps[0] ? "https://" : "http://") + host;
                    boolean autoName = name.isEmpty();
                    if (autoName) name = host; // placeholder until the station answers
                    if (index < 0) {
                        stations.add(new Station(name, url));
                    } else {
                        Station st = stations.get(index);
                        String oldUrl = st.url;
                        st.name = name;
                        st.url = url;
                        if (!oldUrl.equals(url)) {
                            // Carry any saved password to the new address, and make
                            // sure a station being edited while on air reloads rather
                            // than leaving the card polling the old host.
                            String cred = prefs().getString(authKey(oldUrl), null);
                            SharedPreferences.Editor e = prefs().edit().remove(authKey(oldUrl));
                            if (cred != null) e.putString(authKey(url), cred);
                            e.apply();
                            if (oldUrl.equals(currentUrl)) {
                                currentUrl = url;
                                pageLoaded = false;
                                shownCoverId = null;
                                web.loadUrl(url);
                            }
                        }
                    }
                    saveStations();
                    refreshStationList();
                    if (autoName) fetchStationName(url);
                })
                .setNegativeButton("Cancel", null)
                .create();

        dlg.show();
    }

    private void showStationOptions(final int index) {
        final Station st = stations.get(index);
        // "Forget saved password" only appears for a station that has one.
        final boolean hasSaved = savedAuth(st.url) != null;
        final ArrayList<String> items = new ArrayList<>();
        items.add("Tune in");
        items.add("Edit");
        if (index > 0) items.add("Move up");
        if (index < stations.size() - 1) items.add("Move down");
        if (hasSaved) items.add("Forget saved password");
        items.add("Remove");

        new AlertDialog.Builder(this)
                .setTitle(st.name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String choice = items.get(which);
                    if (choice.equals("Tune in")) {
                        openStation(index);
                    } else if (choice.equals("Edit")) {
                        showStationDialog(index);
                    } else if (choice.equals("Move up")) {
                        moveStation(index, index - 1);
                    } else if (choice.equals("Move down")) {
                        moveStation(index, index + 1);
                    } else if (choice.equals("Forget saved password")) {
                        prefs().edit().remove(authKey(st.url)).apply();
                        Toast.makeText(this, "Saved password forgotten", Toast.LENGTH_SHORT).show();
                    } else {
                        new AlertDialog.Builder(this)
                                .setMessage("Remove “" + st.name + "”?")
                                .setPositiveButton("Remove", (d2, w2) -> {
                                    prefs().edit().remove(authKey(st.url)).apply();
                                    stations.remove(index);
                                    saveStations();
                                    refreshStationList();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .show();
    }

    /** Reorder the list, keeping the "last played" pointer on the same station. */
    private void moveStation(int from, int to) {
        if (to < 0 || to >= stations.size()) return;
        Station moved = stations.remove(from);
        stations.add(to, moved);
        int last = prefs().getInt(KEY_LAST, 0);
        if (last == from) prefs().edit().putInt(KEY_LAST, to).apply();
        else if (last > from && last <= to) prefs().edit().putInt(KEY_LAST, last - 1).apply();
        else if (last < from && last >= to) prefs().edit().putInt(KEY_LAST, last + 1).apply();
        saveStations();
        refreshStationList();
        stationsListView.setSelection(to);
    }

    /**
     * Ask the station what it calls itself and adopt that as the list name.
     * Every Subwave station publishes it, so there's no reason to make the
     * viewer type one. Silently leaves the host placeholder if unreachable
     * (or password-gated, where the name isn't public until you're in).
     */
    private void fetchStationName(final String url) {
        final String base = url.endsWith("/") ? url : url + "/";
        new Thread(() -> {
            String found = null;
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL(base + "api/now-playing").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                applyAuth(c, base);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                }
                JSONObject dj = new JSONObject(sb.toString()).optJSONObject("dj");
                if (dj != null) {
                    String s = dj.optString("station", "");
                    if (!s.isEmpty()) found = s;
                }
            } catch (Exception ignored) {}
            final String name = found;
            if (name == null) return;
            ui.post(() -> {
                for (Station st : stations) {
                    if (st.url.equals(url)) {
                        st.name = name;
                        saveStations();
                        refreshStationList();
                        return;
                    }
                }
            });
        }).start();
    }

    private ArrayList<Station> loadStations() {
        ArrayList<Station> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString(KEY_STATIONS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Station(o.optString("name", "Station"), o.getString("url")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void saveStations() {
        try {
            JSONArray arr = new JSONArray();
            for (Station st : stations) {
                JSONObject o = new JSONObject();
                o.put("name", st.name);
                o.put("url", st.url);
                arr.put(o);
            }
            prefs().edit().putString(KEY_STATIONS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /* ------------------------------------------------------------------ */
    /* Remote keys                                                         */
    /* ------------------------------------------------------------------ */

    private boolean swallowCenterUp = false;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // OK on a focused text field opens the (held-back) keyboard. This must
        // run before the WebView sees the key, or it would type Enter — which
        // submits the request textarea. The matching key-up is swallowed too.
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && !stationsVisible() && editableFocused && !fieldActivated) {
                openKeyboard();
                swallowCenterUp = true;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && swallowCenterUp) {
                swallowCenterUp = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (stationsVisible()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                    handleBackFromStations();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    showListMenu();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    togglePlayFromList(); // the tuned station keeps playing behind the list
                    return true;
                default:
                    return super.onKeyDown(keyCode, event); // ListView handles D-pad
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                handleBackFromPlayer();
                return true;
            case KeyEvent.KEYCODE_MENU:
                showMenu();
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                startVoiceRequest();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (editableFocused) {
                    startVoice();
                } else {
                    js("window.__swtvKey && __swtvKey('k')"); // page shortcut: toggle tune-in
                    ui.postDelayed(this::syncPlaybackState, 400);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                js("window.__swtvKey && __swtvKey('m')"); // mute
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /**
     * Unlock the focused page field, then raise the keyboard against it. The
     * unlock happens in the page first (it clears the field's read-only flag),
     * so by the time the IME opens there is a real editable target behind it —
     * otherwise the keyboard appears but keystrokes go nowhere.
     */
    private void openKeyboard() {
        web.evaluateJavascript("window.__swtvActivateField ? __swtvActivateField() : false", r -> {
            if (r == null || !r.contains("true")) {
                // Focus moved on before the press landed — clear the stale flag so
                // the next OK reaches the page instead of being swallowed again.
                editableFocused = false;
                return;
            }
            fieldActivated = true;
            keyboardOpenedAt = System.currentTimeMillis();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm == null) return;
            web.requestFocus();
            imm.restartInput(web); // re-read the field now that it accepts input
            imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void handleBackFromPlayer() {
        if (!pageLoaded) { showStations(); return; }
        web.evaluateJavascript("window.__swtvBack ? __swtvBack() : false", result -> {
            if (result != null && result.contains("true")) return; // a drawer was closed
            if (web.canGoBack()) {
                // The click navigated the WebView to a real new page (an
                // external link, not an in-app drawer) rather than opening a
                // dialog — return to the exact previous page instead of
                // jumping out to the station list.
                web.goBack();
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastBackPress < BACK_WINDOW_MS) {
                showStations(); // audio keeps playing while browsing the list
            } else {
                lastBackPress = now;
                Toast.makeText(this, "Press BACK again for the station list", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleBackFromStations() {
        long now = System.currentTimeMillis();
        if (currentUrl != null && pageLoaded) {
            // A station is playing — BACK returns to it, double-BACK exits.
            if (now - lastBackPress < BACK_WINDOW_MS) {
                finish();
            } else {
                lastBackPress = now;
                openStation(prefs().getInt(KEY_LAST, 0));
            }
        } else {
            if (now - lastBackPress < BACK_WINDOW_MS) {
                finish();
            } else {
                lastBackPress = now;
                Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Player menu                                                         */
    /* ------------------------------------------------------------------ */

    private void showMenu() {
        String[] items = {
                "🎙 Voice request",
                "Sleep timer · " + sleepHours() + "h",
                "Switch station",
                "Reload player",
                "Exit",
        };
        new AlertDialog.Builder(this)
                .setTitle("Wave TV")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: startVoiceRequest(); break;
                        case 1: showSleepDialog(); break;
                        case 2: showStations(); break;
                        case 3:
                            if (currentUrl != null) loadStation(currentUrl);
                            break;
                        case 4: finish(); break;
                    }
                })
                .show();
    }

    /** MENU on the station list — the settings that aren't tied to a playing station. */
    private void showListMenu() {
        String[] items = {
                "Add a station",
                "Sleep timer · " + sleepHours() + "h",
                "Exit",
        };
        new AlertDialog.Builder(this)
                .setTitle("Wave TV  ·  " + appVersion())
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showStationDialog(-1); break;
                        case 1: showSleepDialog(); break;
                        case 2: finish(); break;
                    }
                })
                .show();
    }

    /* ------------------------------------------------------------------ */
    /* Sleep timer                                                         */
    /* ------------------------------------------------------------------ */

    /** A pill button for the masthead, registered so palette changes reach it. */
    private android.widget.Button buildChip(String label, View.OnClickListener onClick) {
        final android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.MONOSPACE);
        b.setTextColor(MUTED);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(16), dp(8), dp(16), dp(9));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> styleChip(b, has));
        b.setOnClickListener(onClick);
        chips.add(b);
        chipBgs.put(b, bg);
        styleChip(b, false);
        return b;
    }

    private void styleChip(android.widget.Button b, boolean focused) {
        GradientDrawable bg = chipBgs.get(b);
        if (bg == null) return;
        bg.setColor(focused ? withAlpha(palette.accent, 46) : palette.surface);
        bg.setStroke(dp(focused ? 2 : 1),
                focused ? palette.accent : withAlpha(palette.ink, 90));
        b.setTextColor(focused ? palette.ink : palette.muted);
    }

    /** Keep the masthead chip in step with the stored setting. */
    private void updateSleepChip() {
        if (sleepChip != null) sleepChip.setText("☾  " + sleepHours() + "h");
    }

    private int sleepHours() {
        int h = prefs().getInt(KEY_SLEEP_HOURS, SLEEP_HOURS_DEFAULT);
        return h < 1 || h > SLEEP_HOURS_MAX ? SLEEP_HOURS_DEFAULT : h;
    }

    /** Stops the stream after the chosen run-time; play again to restart it. */
    private final Runnable sleepTimer = new Runnable() {
        @Override
        public void run() {
            web.evaluateJavascript("window.__swtvStop ? __swtvStop() : false", r -> {
                setPlayGlyph(false);
                setOnAir(false);
                Toast.makeText(MainActivity.this,
                        "Sleep timer — stream stopped after " + sleepHours() + "h. Press play to resume.",
                        Toast.LENGTH_LONG).show();
            });
        }
    };

    /** (Re)start the countdown — called whenever playback begins. */
    private void armSleepTimer() {
        ui.removeCallbacks(sleepTimer);
        ui.postDelayed(sleepTimer, sleepHours() * 60L * 60L * 1000L);
    }

    private void cancelSleepTimer() {
        ui.removeCallbacks(sleepTimer);
    }

    private void showSleepDialog() {
        final String[] labels = new String[SLEEP_HOURS_MAX];
        for (int i = 0; i < SLEEP_HOURS_MAX; i++) {
            labels[i] = (i + 1) + (i == 0 ? " hour" : " hours")
                    + (i + 1 == SLEEP_HOURS_DEFAULT ? "  (default)" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Stop the stream after…")
                .setSingleChoiceItems(labels, sleepHours() - 1, (d, which) -> {
                    prefs().edit().putInt(KEY_SLEEP_HOURS, which + 1).apply();
                    armSleepTimer(); // the new length starts counting now
                    updateSleepChip();
                    d.dismiss();
                    Toast.makeText(this, "Sleep timer set to " + (which + 1) + "h",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private AlertDialog errorDialog;

    private void showLoadError(String description) {
        // A failed page can report several errors in a row; one dialog is enough.
        if (errorDialog != null && errorDialog.isShowing()) return;
        errorDialog = new AlertDialog.Builder(this)
                .setTitle("Can't reach the station")
                .setMessage("Couldn't load " + currentStationName() + "\n\n" + description
                        + "\n\nIf the station was just switched on, give it a moment and retry.")
                .setPositiveButton("Retry", (d, w) -> {
                    if (currentUrl != null) loadStation(currentUrl);
                })
                .setNegativeButton("Station list", (d, w) -> showStations())
                .show();
    }

    /* ------------------------------------------------------------------ */
    /* Voice input                                                         */
    /* ------------------------------------------------------------------ */

    /** Open the request drawer, focus the textarea, then listen. */
    private void startVoiceRequest() {
        js("window.__swtvOpenRequest && __swtvOpenRequest()");
        ui.postDelayed(this::startVoice, 700);
    }

    /** Launch the system speech recognizer; the result lands in the focused field. */
    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your request to the DJ");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this,
                    "No voice recognizer on this device — use the on-screen keyboard instead",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) return;
        String text = results.get(0);
        web.evaluateJavascript(
                "window.__swtvInsertText ? __swtvInsertText(" + jsString(text) + ") !== false : false",
                r -> {
                    // Dictation leaves the field unlocked, so OK now sends the
                    // request rather than opening a keyboard over it.
                    if (r != null && r.contains("true")) fieldActivated = true;
                    Toast.makeText(this, "“" + text + "” — press OK to send",
                            Toast.LENGTH_LONG).show();
                });
    }

    /* ------------------------------------------------------------------ */
    /* JS bridge                                                           */
    /* ------------------------------------------------------------------ */

    private class Bridge {
        /**
         * @param focused whether a text field currently holds focus
         * @param token   identifies *which* field; a new token means focus moved
         *                to a different field, so the keyboard re-locks. The same
         *                token arriving again is a transient re-focus and must
         *                leave an open keyboard alone.
         */
        @JavascriptInterface
        public void editableFocused(final boolean focused, final int token) {
            editableFocused = focused;
            ui.post(() -> {
                if (!focused) {
                    // Never pull the keyboard down in the moments right after
                    // opening it: the resize it causes can look like focus loss,
                    // and dismissing here left the viewer unable to type at all.
                    if (System.currentTimeMillis() - keyboardOpenedAt < KEYBOARD_SETTLE_MS) return;
                    fieldToken = 0;
                    fieldActivated = false;
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
                } else if (token != fieldToken) {
                    fieldToken = token;   // a different field — OK must unlock it again
                    fieldActivated = false;
                }
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private void js(String code) {
        web.evaluateJavascript(code, null);
    }

    private static String jsString(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default:
                    // U+2028/U+2029 are line terminators to a JS parser even
                    // though they're printable, so a dictated phrase containing
                    // one would otherwise break out of the string literal.
                    // (compared numerically: a literal would be expanded by
                    // javac before parsing and break this source line itself)
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Read from the manifest so the on-screen version can never drift from the build. */
    private String appVersion() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Flush web storage now so a station unlock (kept in the page's own
        // localStorage) survives the app being killed rather than backgrounded.
        try {
            android.webkit.CookieManager.getInstance().flush();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(nowPlayingPoll);
        ui.removeCallbacks(palettePoll);
        cancelSleepTimer();
        if (onAirPulse != null) onAirPulse.cancel();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
