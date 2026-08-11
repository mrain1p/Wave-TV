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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final String KEY_SLEEP_HOURS = "sleepHours";
    private static final String KEY_THEME_MODE = "themeMode";
    /** Whether the picker hides the now-playing panel for a one-line strip. */
    private static final String KEY_NP_MINIMAL = "npMinimal";
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


    private FrameLayout root;
    private WebView web;
    private LinearLayout stationsPanel;
    private ListView stationsListView;
    private StationAdapter stationsAdapter;
    private TextView nowPlayingText;
    private TextView npLabel;
    private TextView npArtist, npAlbum;
    /** The station name and "playing · <bitrate>" under the panel's transport. */
    private TextView npStation, npStatus;
    /** Show/DJ row — GONE unless the station's API actually names one. */
    private View npShowRow;
    private TextView npShowName;
    private android.widget.Button playButton;
    private android.widget.Button sleepChip;
    private android.widget.Button addChip;
    private android.widget.Button themeChip;
    private android.widget.Button npChip;
    private TextView titleView, noteView, emptyView, footHint, footVersion;
    private View accentRule, hairRule, npRule, npCaptionRule, npFootRule, stripRule;
    private ThemeGlyph themeGlyph;
    private MicGlyph micGlyph;
    private GradientDrawable npBgDrawable, artBgDrawable;

    /* --- the now-playing panel, and its minimal-mode counterpart ---------
     * Both exist at once and only one is ever visible (see applyNpMinimal).
     * They are separate view trees rather than one tree that moves, because a
     * View has a single parent: re-parenting the transport between two layouts
     * on every toggle would drop D-pad focus mid-press. Everything that has to
     * stay in step is updated through the small loops below. */
    private LinearLayout npPanel, minimalStrip, listColumn, bodyRow;
    private android.widget.ImageView stripArt;
    private TextView stripCaption, stripTrack;
    private android.widget.Button stripPlay;
    private GradientDrawable stripArtBg;
    private LevelMeter panelMeter, stripMeter;
    /** Every transport button, so the glyph and focus styling reach both. */
    private final ArrayList<android.widget.Button> playButtons = new ArrayList<>();
    private final java.util.Map<android.widget.Button, GradientDrawable> playBgs =
            new java.util.HashMap<>();
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
    /** Last known audio state, so the sleep timer arms on the edge — see syncPlaybackState. */
    private boolean audioLive = false;
    /** True between loadStation() and that navigation landing — see onPageFinished. */
    private boolean freshStationLoad = false;
    /** True when what's in the WebView is an error page rather than the player. */
    private boolean loadFailed = false;
    private String currentUrl = null;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * One pool for every station request.
     *
     * Each of the four call sites used to start a raw Thread: one per station
     * on every probe, and another every seven seconds for now-playing. Against
     * a station that hangs rather than refuses — which is the normal failure
     * for a LAN address after the television has moved networks — each of
     * those lives for the full connect-plus-read timeout, and they were being
     * created faster than they retired. A small pool bounds that; the work is
     * all short, blocking I/O, so a handful of threads is plenty and the queue
     * absorbs the rest.
     */
    private final java.util.concurrent.ExecutorService net =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "wavetv-net");
                t.setDaemon(true); // never hold the process open on our account
                return t;
            });

    /** Run a station request off the UI thread, unless we are on the way out. */
    private void offThread(Runnable r) {
        try {
            net.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutting down. The result would have nowhere to land anyway.
        }
    }



    private ArrayList<Station> stations = new ArrayList<>();

    private static final String THEME_DARK = "dark";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_STATION = "station";
    private Palette palette = Palette.DARK;
    private android.animation.ValueAnimator paletteAnim;

    /** Everything that survives the process: the list, the pointer, the passwords. */
    private StationStore store;

    /** The station to come back to; see StationStore for why it is a URL. */
    private String lastStationUrl() {
        return store.lastUrl(stations);
    }

    /** Where a station URL currently sits in the list, or -1 if it doesn't. */
    private int indexOfUrl(String url) {
        if (url == null) return -1;
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).url.equals(url)) return i;
        }
        return -1;
    }

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
        root.setBackgroundColor(Colors.BG);

        buildWebView();
        buildStationsPanel();

        setContentView(root);

        store = new StationStore(prefs());
        stations = store.load();
        showStations();
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
        web.setBackgroundColor(Colors.BG);
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
        if (helperJs == null) {
            // Without it there is no D-pad navigation, no keyboard gate and
            // none of the __swtv* hooks the shell calls — a mouse-driven page
            // on a device with no mouse. It failed silently before, which is
            // an unhelpful way to present a broken build.
            Toast.makeText(this,
                    "Wave TV's remote-control layer is missing from this build — "
                            + "the D-pad won't work in the player",
                    Toast.LENGTH_LONG).show();
        }
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
                if (freshStationLoad) {
                    freshStationLoad = false;
                    // Tuning a station is a fresh start, not a step in a
                    // browsing session. Each Retry from the error dialog
                    // otherwise left another history entry, and BACK's
                    // canGoBack() branch then walked back through the failed
                    // attempts one at a time instead of reaching the picker.
                    // Cleared only for the station's own load, so moving
                    // around within the station still has somewhere to go back
                    // to.
                    view.clearHistory();
                }
                if (helperJs != null) js(helperJs);
                // Picking a station is the "play" gesture — dismiss the player's
                // tap-to-tune gate rather than making the viewer find it.
                js("window.__swtvAutoTuneIn && __swtvAutoTuneIn()");
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
                resetPalettePoll(); // a new page is a new answer
                ui.postDelayed(palettePoll, 800);
            }

            /**
             * A network-level failure of the page itself.
             *
             * Everything arriving here is already the player's own failure
             * rather than a sub-resource's: on API 23+ the framework reaches
             * this through the WebResourceRequest overload, whose default
             * implementation forwards only when request.isForMainFrame(), and
             * below that it is only called for the main frame anyway.
             *
             * The old guard compared failingUrl against view.getUrl(), which
             * during a failed navigation is still the LAST COMMITTED page —
             * the previous station, or null on the first load of the session.
             * Whenever it didn't match, the error was dropped: loadFailed
             * stayed false, and onPageFinished then took the browser's error
             * page for a healthy player, injected the remote layer into it and
             * tried to auto-tune it. No dialog, and a station that looked live.
             */
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // Still worth one check, but against the station we are trying
                // to reach rather than against whatever is currently committed:
                // an error for some other host is not this station failing.
                if (failingUrl != null && currentUrl != null
                        && !isStationHost(StationStore.hostOf(failingUrl))) {
                    return;
                }
                loadFailed = true;
                showLoadError(description == null
                        ? "The station could not be reached." : description);
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

            /**
             * Refuse to load anything but the tuned station in the main frame.
             *
             * Everything this shell hands the page is scoped to the station it
             * was asked to load: the WaveTV bridge, the injected remote layer,
             * autoplay without a user gesture, cleartext HTTP, and a stored
             * password. A page that walked the main frame somewhere else would
             * inherit the lot. There is nothing to browse to on a television,
             * so off-origin main-frame navigation is simply declined.
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                    android.webkit.WebResourceRequest request) {
                if (request == null || !request.isForMainFrame()) return false;
                return blockOffStation(request.getUrl() == null ? null
                        : request.getUrl().toString());
            }

            /** API 22-23 has no request object here; those call this instead. */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockOffStation(url);
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view,
                                                  android.webkit.HttpAuthHandler handler,
                                                  String host, String realm) {
                // `host` is whoever is actually challenging, which is not
                // necessarily the station: any sub-resource can raise a 401.
                // Answering one with the station's credential would hand the
                // password to a third party, and prompting for a fresh one
                // under this app's own "STATION IS LOCKED" heading would be a
                // convincing place to phish it. Anything that isn't the
                // station we tuned is refused rather than answered.
                if (!isStationHost(host)) {
                    handler.cancel();
                    return;
                }
                // Reuse a stored credential silently; otherwise ask. useHttpAuthUsernamePassword
                // is false on a retry, which means the stored one was rejected.
                String saved = currentUrl == null ? null : store.savedAuth(currentUrl);
                if (saved != null && handler.useHttpAuthUsernamePassword()) {
                    try {
                        String[] parts = new String(android.util.Base64.decode(saved,
                                android.util.Base64.NO_WRAP), StandardCharsets.UTF_8).split(":", 2);
                        if (parts.length == 2) { handler.proceed(parts[0], parts[1]); return; }
                    } catch (Exception ignored) {}
                }
                if (saved != null) store.forgetAuth(currentUrl);
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
        stationsPanel.setBackgroundColor(Colors.BG);
        stationsPanel.setPadding(dp(60), dp(32), dp(60), dp(24));

        // Masthead: wordmark on the left, the chip rail on the right. The
        // OK/HOLD/MENU hint that used to sit under the wordmark is now in the
        // footer — it is a legend, and a legend belongs at the foot of the page
        // rather than competing with the masthead for the eye.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(this);
        titleView.setText("WAVE TV");
        titleView.setTextColor(Colors.INK);
        titleView.setTextSize(29);
        titleView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        titleView.setLetterSpacing(0.06f);
        header.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        addChip = buildChip("+ ADD STATION", v -> showStationDialog(null));
        header.addView(addChip, headerChipLp());

        themeChip = buildChip("", v -> cycleThemeMode());
        themeGlyph = new ThemeGlyph(dp(14));
        themeGlyph.tint(Colors.MUTED);
        themeChip.setCompoundDrawablesWithIntrinsicBounds(themeGlyph, null, null, null);
        themeChip.setPadding(dp(13), dp(7), dp(13), dp(7));
        header.addView(themeChip, headerChipLp());

        npChip = buildChip("NP", v -> toggleNpMinimal());
        header.addView(npChip, headerChipLp());

        sleepChip = buildChip("SLEEP 6H", v -> showSleepDialog());
        header.addView(sleepChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(16);
        stationsPanel.addView(header, headerLp);
        updateSleepChip();
        updateThemeChip();
        updateNpChip();

        // Masthead separator as a newspaper double rule — a heavy line, a gap,
        // then a hairline. One 2dp band of full-saturation accent was heavier
        // than anything it was separating.
        accentRule = new View(this);
        accentRule.setBackgroundColor(Colors.VERMILION);
        stationsPanel.addView(accentRule, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));

        hairRule = new View(this);
        LinearLayout.LayoutParams hairLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        hairLp.topMargin = dp(3);
        stationsPanel.addView(hairRule, hairLp);

        // The list and the now-playing panel share the width two to one. Both
        // live in this row so the panel is full height beside the list rather
        // than a card stranded under it.
        bodyRow = new LinearLayout(this);
        bodyRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(18);
        stationsPanel.addView(bodyRow, bodyLp);

        listColumn = new LinearLayout(this);
        listColumn.setOrientation(LinearLayout.VERTICAL);
        bodyRow.addView(listColumn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 2f));

        stationsListView = new ListView(this);
        stationsListView.setBackgroundColor(Colors.BG);
        stationsListView.setDivider(new android.graphics.drawable.ColorDrawable(Color.argb(28, 243, 239, 230)));
        stationsListView.setDividerHeight(dp(1));
        stationsListView.setSelector(new android.graphics.drawable.ColorDrawable(Color.argb(60, 197, 48, 42)));
        stationsAdapter = new StationAdapter();
        stationsListView.setAdapter(stationsAdapter);
        stationsListView.setOnItemClickListener((parent, view, pos, id) -> openStation(pos));
        stationsListView.setOnItemLongClickListener((parent, view, pos, id) -> {
            showStationOptions(pos);
            return true;
        });

        listColumn.addView(stationsListView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // First run lands here rather than on an open dialog with the keyboard
        // up: nothing has been read yet at that point, and a modal is a poor
        // first impression of a remote-driven app. The list swaps itself for
        // this line whenever it's empty, and focus goes to the Add chip so OK
        // still starts a station in one press.
        emptyView = new TextView(this);
        emptyView.setText("NO STATIONS\n\nPress  + ADD STATION  to begin");
        emptyView.setTextSize(13);
        emptyView.setLineSpacing(dp(3), 1f);
        emptyView.setTypeface(Typeface.MONOSPACE);
        emptyView.setLetterSpacing(0.14f);
        emptyView.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyView.setPadding(0, dp(52), 0, 0);
        listColumn.addView(emptyView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        stationsListView.setEmptyView(emptyView);

        // --- now-playing panel (right column) -----------------------------
        npPanel = new LinearLayout(this);
        npPanel.setOrientation(LinearLayout.VERTICAL);
        npBgDrawable = new GradientDrawable();
        npBgDrawable.setColor(Color.TRANSPARENT);
        npBgDrawable.setStroke(dp(1), Color.argb(45, 243, 239, 230));
        npPanel.setBackground(npBgDrawable);
        npPanel.setPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout.LayoutParams npPanelLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        npPanelLp.leftMargin = dp(32);
        bodyRow.addView(npPanel, npPanelLp);

        // 1. Caption row: the pulsing lamp beside NOW PLAYING, over a hairline.
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        labelRow.setPadding(0, 0, 0, dp(10));

        onAirDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Colors.VERMILION);
        onAirDot.setBackground(dot);
        onAirDot.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(5), dp(5));
        dotLp.rightMargin = dp(7);
        labelRow.addView(onAirDot, dotLp);

        onAirPulse = android.animation.ObjectAnimator.ofFloat(onAirDot, "alpha", 1f, 0.3f);
        onAirPulse.setDuration(1150);
        onAirPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        onAirPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);

        npLabel = new TextView(this);
        npLabel.setText("NOW PLAYING");
        npLabel.setTextColor(Colors.VERMILION);
        npLabel.setTextSize(8);
        npLabel.setTypeface(Typeface.MONOSPACE);
        npLabel.setLetterSpacing(0.32f);
        labelRow.addView(npLabel);
        npPanel.addView(labelRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        npCaptionRule = new View(this);
        npPanel.addView(npCaptionRule, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // 2. Show/DJ, when the station's API names one. GONE otherwise — see
        //    the poller; nothing here is scraped off the page to fill it in.
        LinearLayout showRow = new LinearLayout(this);
        showRow.setOrientation(LinearLayout.HORIZONTAL);
        showRow.setGravity(Gravity.CENTER_VERTICAL);
        showRow.setVisibility(View.GONE);
        npShowRow = showRow;

        android.widget.ImageView mic = new android.widget.ImageView(this);
        micGlyph = new MicGlyph(dp(12));
        mic.setImageDrawable(micGlyph);
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        micLp.rightMargin = dp(7);
        showRow.addView(mic, micLp);

        npShowName = new TextView(this);
        npShowName.setTextSize(8);
        npShowName.setTypeface(Typeface.MONOSPACE);
        npShowName.setLetterSpacing(0.18f);
        npShowName.setSingleLine(true);
        npShowName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        showRow.addView(npShowName);

        LinearLayout.LayoutParams showLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        showLp.topMargin = dp(12);
        npPanel.addView(showRow, showLp);

        // 3. Cover art: a centred square, empty until the first cover lands.
        npArt = new android.widget.ImageView(this);
        npArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        artBgDrawable = new GradientDrawable();
        artBgDrawable.setColor(Color.rgb(34, 30, 27));
        artBgDrawable.setStroke(dp(1), Color.argb(38, 243, 239, 230));
        npArt.setBackground(artBgDrawable);
        npArt.setClipToOutline(true);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(150), dp(150));
        artLp.gravity = Gravity.CENTER_HORIZONTAL;
        artLp.topMargin = dp(12);
        npPanel.addView(npArt, artLp);

        // 4. Title block.
        nowPlayingText = new TextView(this);
        nowPlayingText.setText("nothing tuned yet");
        nowPlayingText.setTextColor(Colors.INK);
        nowPlayingText.setTextSize(14);
        nowPlayingText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        nowPlayingText.setSingleLine(true);
        nowPlayingText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(11);
        npPanel.addView(nowPlayingText, titleLp);

        npArtist = new TextView(this);
        npArtist.setText("pick a station to tune in");
        npArtist.setTextColor(Colors.MUTED);
        npArtist.setTextSize(10);
        npArtist.setTypeface(Typeface.MONOSPACE);
        npArtist.setSingleLine(true);
        npArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        npArtist.setPadding(0, dp(3), 0, 0);
        npPanel.addView(npArtist);

        npAlbum = new TextView(this);
        npAlbum.setTextSize(8);
        npAlbum.setTypeface(Typeface.MONOSPACE);
        npAlbum.setSingleLine(true);
        npAlbum.setEllipsize(android.text.TextUtils.TruncateAt.END);
        npAlbum.setPadding(0, dp(3), 0, 0);
        npPanel.addView(npAlbum);

        // 5. Everything below is pinned to the bottom of the panel.
        View spacer = new View(this);
        npPanel.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        npFootRule = new View(this);
        npPanel.addView(npFootRule, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // 6. Foot: transport, what it is playing, and the level meter.
        LinearLayout footRow = new LinearLayout(this);
        footRow.setOrientation(LinearLayout.HORIZONTAL);
        footRow.setGravity(Gravity.CENTER_VERTICAL);
        footRow.setPadding(0, dp(13), 0, 0);

        playButton = buildPlayButton(dp(35), 14f);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(35), dp(35));
        pbLp.rightMargin = dp(12);
        footRow.addView(playButton, pbLp);

        LinearLayout footText = new LinearLayout(this);
        footText.setOrientation(LinearLayout.VERTICAL);

        npStation = new TextView(this);
        npStation.setTextSize(8);
        npStation.setTypeface(Typeface.MONOSPACE);
        npStation.setLetterSpacing(0.1f);
        npStation.setSingleLine(true);
        npStation.setEllipsize(android.text.TextUtils.TruncateAt.END);
        footText.addView(npStation);

        npStatus = new TextView(this);
        npStatus.setTextSize(8);
        npStatus.setTypeface(Typeface.MONOSPACE);
        npStatus.setSingleLine(true);
        npStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        footText.addView(npStatus);

        footRow.addView(footText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        panelMeter = new LevelMeter(this, dp(3), dp(3));
        footRow.addView(panelMeter,
                new LinearLayout.LayoutParams(panelMeter.intrinsicWidth(), dp(24)));

        npPanel.addView(footRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- minimal mode strip (replaces the panel when NP is off) --------
        stripRule = new View(this);
        LinearLayout.LayoutParams stripRuleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        stripRuleLp.topMargin = dp(14);
        stationsPanel.addView(stripRule, stripRuleLp);

        minimalStrip = new LinearLayout(this);
        minimalStrip.setOrientation(LinearLayout.HORIZONTAL);
        minimalStrip.setGravity(Gravity.CENTER_VERTICAL);
        minimalStrip.setPadding(0, dp(14), 0, 0);

        stripArt = new android.widget.ImageView(this);
        stripArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        stripArtBg = new GradientDrawable();
        stripArtBg.setColor(Color.rgb(34, 30, 27));
        stripArtBg.setStroke(dp(1), Color.argb(38, 243, 239, 230));
        stripArt.setBackground(stripArtBg);
        stripArt.setClipToOutline(true);
        LinearLayout.LayoutParams stripArtLp = new LinearLayout.LayoutParams(dp(68), dp(68));
        stripArtLp.rightMargin = dp(16);
        minimalStrip.addView(stripArt, stripArtLp);

        stripPlay = buildPlayButton(dp(46), 19f);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        spLp.rightMargin = dp(16);
        minimalStrip.addView(stripPlay, spLp);

        LinearLayout stripText = new LinearLayout(this);
        stripText.setOrientation(LinearLayout.VERTICAL);

        stripCaption = new TextView(this);
        stripCaption.setText("NOW PLAYING");
        stripCaption.setTextSize(9);
        stripCaption.setTypeface(Typeface.MONOSPACE);
        stripCaption.setLetterSpacing(0.28f);
        stripCaption.setSingleLine(true);
        stripCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        stripText.addView(stripCaption);

        stripTrack = new TextView(this);
        stripTrack.setText("nothing tuned yet");
        stripTrack.setTextSize(19);
        stripTrack.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        stripTrack.setSingleLine(true);
        stripTrack.setEllipsize(android.text.TextUtils.TruncateAt.END);
        stripTrack.setPadding(0, dp(2), 0, 0);
        stripText.addView(stripTrack);

        minimalStrip.addView(stripText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        stripMeter = new LevelMeter(this, dp(3), dp(3));
        LinearLayout.LayoutParams smLp =
                new LinearLayout.LayoutParams(stripMeter.intrinsicWidth(), dp(24));
        smLp.leftMargin = dp(16);
        minimalStrip.addView(stripMeter, smLp);

        stationsPanel.addView(minimalStrip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- footer -------------------------------------------------------
        npRule = new View(this);
        LinearLayout.LayoutParams npRuleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        npRuleLp.topMargin = dp(16);
        stationsPanel.addView(npRule, npRuleLp);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(9), 0, 0);

        noteView = footLabel("an unofficial community player for Subwave stations", 0f);
        footer.addView(noteView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // The legend the masthead used to carry. Centre it by giving the two
        // ends equal weight rather than by measuring anything.
        //
        // It no longer promises MENU. The Google TV Streamer's remote has no
        // menu button at all, so a legend naming it was instructions for a key
        // a viewer may not own — and everything that menu offers on this screen
        // is on a chip or on BACK anyway.
        footHint = footLabel("OK TUNE IN · HOLD OK FOR OPTIONS · BACK TWICE TO EXIT", 0.14f);
        footer.addView(footHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        footVersion = footLabel(appVersion(), 0f);
        footVersion.setGravity(Gravity.END);
        footer.addView(footVersion, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        stationsPanel.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        applyNpMinimal();
        stationsPanel.setVisibility(View.GONE);
        root.addView(stationsPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Paint once up front from the saved mode. Without this a remembered
        // Dark choice would only ever match because the views happen to be
        // constructed in those colours — an accident, not a guarantee.
        if (THEME_LIGHT.equals(themeMode())) palette = Palette.LIGHT;
        applyPalette();
        applyPaletteToRows();
    }

    /* ------------------------------------------------------------------ */
    /* Station colour scheme                                               */
    /* ------------------------------------------------------------------ */

    /**
     * A square transport button. There are two — one in the now-playing panel,
     * one in the minimal strip — and only ever one on screen, but both are
     * built up front so toggling minimal mode never has to re-parent a focused
     * view. The base glyph size rides along on the tag so the pause mark, which
     * is wider than the play mark, can step down without a table of sizes.
     */
    private android.widget.Button buildPlayButton(int sizePx, float glyphSp) {
        final android.widget.Button b = new android.widget.Button(this);
        b.setText("▶");
        b.setTextSize(glyphSp);
        b.setTag(glyphSp);
        b.setAllCaps(false);
        b.setTextColor(Colors.INK);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Colors.withAlpha(Colors.VERMILION, 56));
        bg.setStroke(dp(2), Colors.VERMILION);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> stylePlayButton(b, has));
        b.setOnClickListener(v -> togglePlayFromList());
        playButtons.add(b);
        playBgs.put(b, bg);
        return b;
    }

    private void stylePlayButton(android.widget.Button b, boolean focused) {
        GradientDrawable bg = playBgs.get(b);
        if (bg == null) return;
        bg.setColor(focused ? palette.accent : Colors.withAlpha(palette.accent, 56));
        bg.setStroke(dp(2), palette.accent);
        b.setTextColor(focused ? palette.onAccent : palette.ink);
    }

    private void stylePlayButtons() {
        for (android.widget.Button b : playButtons) stylePlayButton(b, b.hasFocus());
    }

    /** One of the three footer captions: small, quiet, monospace. */
    private TextView footLabel(String text, float letterSpacing) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(7);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(Colors.withAlpha(Colors.MUTED, 150));
        if (letterSpacing > 0) t.setLetterSpacing(letterSpacing);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /* ---- minimal mode ------------------------------------------------- */

    private boolean npMinimal() {
        return prefs().getBoolean(KEY_NP_MINIMAL, false);
    }

    private void toggleNpMinimal() {
        prefs().edit().putBoolean(KEY_NP_MINIMAL, !npMinimal()).apply();
        updateNpChip();
        applyNpMinimal();
    }

    private void updateNpChip() {
        if (npChip == null) return;
        boolean min = npMinimal();
        npChip.setContentDescription(min
                ? "Now playing: compact. Press to show the full panel"
                : "Now playing: full panel. Press to shrink it to a strip");
        // The chip reads as the state it is IN, not the state it would go to:
        // a control that labels its own future is the one people press twice.
        npChip.setAlpha(min ? 0.55f : 1f);
    }

    /**
     * Swap between the full right-hand panel and the one-line strip. Focus is
     * moved off whatever is about to be hidden — a focused View that goes GONE
     * hands focus back to the top of the window, which on this screen means the
     * viewer's place in the station list is lost.
     */
    private void applyNpMinimal() {
        if (npPanel == null) return;
        boolean min = npMinimal();
        if (min && (npPanel.hasFocus() || playButton.hasFocus())
                || !min && minimalStrip.hasFocus()) {
            // An empty ListView cannot take focus, so on a first run with no
            // stations this would otherwise leave the remote with nowhere to go.
            if (stations.isEmpty()) npChip.requestFocus();
            else stationsListView.requestFocus();
        }
        npPanel.setVisibility(min ? View.GONE : View.VISIBLE);
        minimalStrip.setVisibility(min ? View.VISIBLE : View.GONE);
        stripRule.setVisibility(min ? View.VISIBLE : View.GONE);
        // Nothing hidden should be animating.
        if (panelMeter != null) panelMeter.setRunning(!min && audioLive);
        if (stripMeter != null) stripMeter.setRunning(min && audioLive);
    }

    /** Start or stop whichever meter is currently on screen. */
    private void setMetersRunning(boolean running) {
        boolean min = npMinimal();
        if (panelMeter != null) panelMeter.setRunning(running && !min);
        if (stripMeter != null) stripMeter.setRunning(running && min);
    }

    /** The views of one list row, held so a rebind doesn't rebuild them. */
    private static class Row {
        final LinearLayout view;
        final TextView num, name, status;
        final View dot;
        Row(LinearLayout view, TextView num, TextView name, View dot, TextView status) {
            this.view = view;
            this.num = num;
            this.name = name;
            this.dot = dot;
            this.status = status;
        }
    }

    /** Everything about a row that doesn't change between stations. */
    private Row buildRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(13), dp(6), dp(13));

        TextView num = new TextView(this);
        num.setTextSize(10);
        num.setTypeface(Typeface.MONOSPACE);
        row.addView(num, new LinearLayout.LayoutParams(dp(36),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Status carries as little as it can: a live station is a green lamp
        // and nothing else, because "ON AIR" on every row of a list of on-air
        // stations is a column of noise. Only the one you are tuned to earns a
        // word, and only a station that ISN'T answering earns a sentence.
        // Caption first, lamp last, both hugging the right edge. The lamp is
        // the thing being scanned down the list from across a room, so it holds
        // one vertical line whether or not the row beside it says anything —
        // which a fixed-width status column with the lamp inside it would not.
        TextView status = new TextView(this);
        status.setTextSize(8);
        status.setTypeface(Typeface.MONOSPACE);
        status.setLetterSpacing(0.2f);
        status.setGravity(Gravity.END);
        status.setSingleLine(true);
        row.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ON_AIR_GREEN);
        dot.setBackground(d);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(5), dp(5));
        dotLp.leftMargin = dp(9);
        row.addView(dot, dotLp);

        Row r = new Row(row, num, name, dot, status);
        row.setTag(r);
        return r;
    }

    /**
     * The station list, read straight from `stations`.
     *
     * It used to be an ArrayAdapter over a parallel list of names, while
     * getView reached into `stations` for everything except the name — two
     * lists that had to be kept in step by hand, with an IndexOutOfBounds
     * waiting on any path that updated one and not the other. One source
     * removes the question. Rows are recycled rather than rebuilt, so holding
     * a direction on the D-pad no longer allocates six views per step.
     */
    private class StationAdapter extends android.widget.BaseAdapter {
        @Override public int getCount() { return stations.size(); }
        @Override public Station getItem(int position) { return stations.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row r = convertView != null ? (Row) convertView.getTag() : buildRow();
            Station st = stations.get(position);
            boolean playing = st.url.equals(currentUrl);
            boolean offline = unreachable.contains(st.url);

            r.num.setText(String.format(Locale.US, "%02d", position + 1));
            r.num.setTextColor(playing ? tunedInk() : Colors.withAlpha(palette.muted, 140));

            // Deliberately name-only: a private station's address is a secret
            // worth keeping, and this screen is the one most likely to end up
            // in a screenshot. The address stays reachable via hold-OK > Edit.
            r.name.setText(st.name);
            r.name.setTextColor(offline ? palette.muted : palette.ink);

            // INVISIBLE, not GONE: the lamp keeps its space so OFF AIR lands in
            // the same column as TUNED instead of jumping 14dp to the right.
            r.dot.setVisibility(offline ? View.INVISIBLE : View.VISIBLE);
            r.status.setText(offline ? "OFF AIR" : (playing ? "TUNED" : ""));
            // Off air stays at full `muted` rather than something dimmer: it is
            // the row someone is squinting at from ten feet away to work out
            // why their station is missing.
            r.status.setTextColor(offline ? palette.muted : tunedInk());
            return r.view;
        }
    }

    /**
     * The lamp on a station that is answering. A literal green, and the only
     * one in the app: it is the single piece of colour here that means a fact
     * about the world rather than a brand, and a station's accent — which this
     * would otherwise be drawn from — is just as likely to be red.
     */
    private static final int ON_AIR_GREEN = Color.rgb(63, 174, 106);

    /**
     * The accent as it reads on the tuned row: the legible accent walked most
     * of the way to the ink, so it says "this one" without competing with the
     * station names it sits beside.
     */
    private int tunedInk() {
        return Colors.blend(palette.accentText, palette.ink, 0.6f);
    }

    /**
     * The focused row: a solid accent rule down its leading edge with a tinted
     * panel beside it, instead of a full-bleed slab of accent.
     *
     * Built by stacking rather than insetting from the right, because that
     * would need the row's width: the accent fills the whole bounds and the
     * opaque panel is inset from the left, so the accent only shows in the
     * strip the panel doesn't cover. Per-corner radii keep it square where it
     * meets the rule and rounded at the open end.
     */
    private android.graphics.drawable.Drawable rowSelector() {
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(palette.accent);

        GradientDrawable panel = new GradientDrawable();
        // The spec's accent-at-alpha-36, but composited against the background
        // here rather than left translucent: this layer sits ON TOP of the
        // solid accent edge, so a see-through fill would let the full-strength
        // accent bleed across the whole row instead of just the rule.
        panel.setColor(Colors.blend(palette.bg, palette.accent, 36 / 255f));

        android.graphics.drawable.LayerDrawable sel =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{edge, panel});
        sel.setLayerInset(1, dp(3), 0, 0, 0);
        return sel;
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
        accentRule.setBackgroundColor(palette.accent);
        if (emptyView != null) emptyView.setTextColor(palette.muted);

        // Every hairline on the screen comes off the ink, never a literal —
        // hardcoding the dark scheme's near-white here is what used to make the
        // light theme's rules invisible.
        int hair = Colors.withAlpha(palette.ink, 45);
        if (hairRule != null) hairRule.setBackgroundColor(hair);
        if (npRule != null) npRule.setBackgroundColor(hair);
        if (stripRule != null) stripRule.setBackgroundColor(hair);
        if (npCaptionRule != null) {
            npCaptionRule.setBackgroundColor(Colors.withAlpha(palette.ink, 35));
        }
        if (npFootRule != null) npFootRule.setBackgroundColor(Colors.withAlpha(palette.ink, 35));
        int footInk = Colors.withAlpha(palette.muted, 150);
        if (noteView != null) noteView.setTextColor(footInk);
        if (footHint != null) footHint.setTextColor(footInk);
        if (footVersion != null) footVersion.setTextColor(footInk);
        if (themeGlyph != null) themeGlyph.tint(
                themeChip.hasFocus() ? palette.ink : palette.muted);

        // The panel is an outline over the page, not a filled card.
        npBgDrawable.setColor(Color.TRANSPARENT);
        npBgDrawable.setStroke(dp(1), Colors.withAlpha(palette.ink, 45));
        int artFill = palette.surface;
        int artStroke = Colors.withAlpha(palette.ink, 38);
        artBgDrawable.setColor(artFill);
        artBgDrawable.setStroke(dp(1), artStroke);
        if (stripArtBg != null) {
            stripArtBg.setColor(artFill);
            stripArtBg.setStroke(dp(1), artStroke);
        }

        npLabel.setTextColor(palette.accentText);
        nowPlayingText.setTextColor(currentUrl == null ? palette.muted : palette.ink);
        npArtist.setTextColor(palette.muted);
        npAlbum.setTextColor(Colors.withAlpha(palette.muted, 153)); // 60%
        npStation.setTextColor(Colors.blend(palette.ink, palette.muted, 0.5f));
        npStatus.setTextColor(palette.muted);
        if (npShowName != null) npShowName.setTextColor(tunedInk());
        if (micGlyph != null) micGlyph.tint(palette.accentText);
        if (stripCaption != null) stripCaption.setTextColor(palette.accentText);
        if (stripTrack != null) {
            stripTrack.setTextColor(currentUrl == null ? palette.muted : palette.ink);
        }
        if (panelMeter != null) panelMeter.tint(palette.accent);
        if (stripMeter != null) stripMeter.tint(palette.accent);
        ((GradientDrawable) onAirDot.getBackground()).setColor(palette.accent);

        stylePlayButtons();
        for (android.widget.Button c : chips) styleChip(c, c.hasFocus());
    }

    /** The row-level colours, applied once rather than per animation frame. */
    private void applyPaletteToRows() {
        stationsListView.setDivider(new android.graphics.drawable.ColorDrawable(
                Colors.withAlpha(palette.ink, 30)));
        stationsListView.setDividerHeight(dp(1));
        stationsListView.setSelector(rowSelector());
        stationsAdapter.notifyDataSetChanged();
    }

    /** Cross-fade to a new palette so the picker doesn't snap between schemes. */
    private void animateToPalette(final Palette target) {
        final Palette from = palette;
        if (from.matches(target)) return; // already wearing it
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
            // Interpolate the derived colours too rather than re-deriving them
            // each frame. The five-argument constructor runs ensureContrast
            // twice, and each of those can walk twenty blend-and-measure steps
            // at six pow() calls apiece — a few thousand per 420ms fade, on a
            // device with none to spare. Both endpoints are legible by
            // construction and the frames between them last 16ms.
            palette = new Palette(
                    (int) ev.evaluate(t, from.bg, target.bg),
                    (int) ev.evaluate(t, from.ink, target.ink),
                    (int) ev.evaluate(t, from.muted, target.muted),
                    (int) ev.evaluate(t, from.accent, target.accent),
                    (int) ev.evaluate(t, from.surface, target.surface),
                    (int) ev.evaluate(t, from.accentText, target.accentText),
                    (int) ev.evaluate(t, from.onAccent, target.onAccent));
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
        if (themeGlyph != null) {
            themeGlyph.set(THEME_DARK.equals(m) ? ThemeGlyph.MOON
                    : THEME_LIGHT.equals(m) ? ThemeGlyph.SUN : ThemeGlyph.STATION);
            themeGlyph.tint(themeChip.hasFocus() ? palette.ink : palette.muted);
        }
        themeChip.setContentDescription(THEME_DARK.equals(m) ? "Dark theme"
                : THEME_LIGHT.equals(m) ? "Light theme" : "Match the station's theme");
    }

    /** Repaint for whichever scheme is selected. */
    private void refreshPalette() {
        resetPalettePoll(); // an explicit ask deserves a prompt answer
        String m = themeMode();
        if (THEME_DARK.equals(m)) { animateToPalette(Palette.DARK); return; }
        if (THEME_LIGHT.equals(m)) { animateToPalette(Palette.LIGHT); return; }
        fetchStationPalette();
    }

    private static final long PALETTE_POLL_MS = 6000;
    private static final long PALETTE_POLL_MAX_MS = 30_000;
    /**
     * How long until the next colour reading. Six seconds while the answer is
     * still moving, easing out to thirty once it has settled.
     *
     * The poll can't simply stop: a station re-themes itself when the show
     * changes, which happens on its own schedule with no signal to us. But a
     * show runs for an hour, so re-asking every six seconds for the whole of it
     * is a JavaScript round trip into the WebView six hundred times to learn
     * nothing. Backing off keeps the same worst case — a theme change is picked
     * up within half a minute — for a fraction of the work.
     */
    private long palettePollMs = PALETTE_POLL_MS;

    /** Back to a fast cadence: something happened that could change the answer. */
    private void resetPalettePoll() {
        palettePollMs = PALETTE_POLL_MS;
    }

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
            ui.postDelayed(this, palettePollMs);
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
            if (palette != Palette.DARK) animateToPalette(Palette.DARK);
            return;
        }
        String probe =
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
        js(probe, r -> {
            Palette p = Palette.fromTokens(r);
            if (p == null) return;
            // Ease off while the answer keeps coming back the same, and snap
            // back to a fast cadence the moment it doesn't.
            if (p.matches(palette)) {
                palettePollMs = Math.min(palettePollMs * 2, PALETTE_POLL_MAX_MS);
            } else {
                resetPalettePoll();
            }
            animateToPalette(p);
        });
    }


    /* ------------------------------------------------------------------ */
    /* Now-playing poller (station list footer)                            */
    /* ------------------------------------------------------------------ */

    /**
     * What one now-playing reading contains. A small carrier so the parse can
     * happen off the UI thread and the painting can happen on it, without a
     * fistful of finals crossing the boundary.
     */
    private static class NowPlaying {
        /**
         * Whether the station named a track. A station that answers with no
         * track — which is what a changeover looks like from out here — is
         * still very much on the air, and must not be reported as silent.
         */
        boolean hasTrack;
        String title, artist, album, year, coverId, show, bitrate;
    }

    /**
     * Consecutive readings that got no answer at all. One miss is a blip and is
     * ridden out on the last good reading; it takes two in a row before the
     * panel will call a station silent.
     */
    private int npMisses = 0;
    /** The station whose track is currently on screen, so a gap can keep it. */
    private String npTrackForUrl = null;

    /**
     * How long until the next reading. Seven seconds while a station is
     * answering; doubling to a minute while it isn't.
     *
     * A station that has gone away is the case worth being careful about: it is
     * the one that costs a full connect timeout every time, and it is exactly
     * when nothing is changing. Backing off means a TV left on the picker
     * overnight against a switched-off LAN station makes about a hundred
     * requests instead of twelve thousand, and still recovers within a minute
     * of the station coming back.
     */
    private long npDelayMs = NOW_PLAYING_POLL_MS;
    private static final long NOW_PLAYING_POLL_MAX_MS = 60_000;

    private void npPollSucceeded() {
        npDelayMs = NOW_PLAYING_POLL_MS;
    }

    private void npPollFailed() {
        npDelayMs = Math.min(npDelayMs * 2, NOW_PLAYING_POLL_MAX_MS);
    }

    private final Runnable nowPlayingPoll = new Runnable() {
        @Override
        public void run() {
            if (!stationsVisible()) return;
            if (currentUrl == null) {
                showNothingTuned();
            } else {
                // Ask the page whether audio is actually rolling.
                syncPlaybackState();
                final String stationUrl = currentUrl;
                final String base = currentUrl.endsWith("/") ? currentUrl : currentUrl + "/";
                offThread(() -> {
                    NowPlaying np = readNowPlaying(base);
                    ui.post(() -> {
                        if (!stationsVisible()) return;
                        if (np != null) {
                            npMisses = 0;
                            npPollSucceeded();
                            // This station just answered us, so whatever the
                            // health probe decided when the picker opened is
                            // out of date. Without this the row went on saying
                            // OFF AIR next to a panel showing the track that
                            // was playing — the probe only runs on the way in
                            // and never got a second opinion.
                            markReachable(stationUrl, true);
                            if (np.hasTrack) showNowPlaying(base, np);
                            else showBetweenTracks();
                        } else {
                            // One miss rides on the last good reading. A track
                            // change is enough to make a station miss a beat,
                            // and blanking the panel for it — then backing the
                            // poll off to half a minute — is how a two-second
                            // gap became thirty seconds of "not responding".
                            npMisses++;
                            if (npMisses >= 2) {
                                npPollFailed();
                                markReachable(stationUrl, false);
                                showStationSilent();
                            }
                        }
                    });
                });
            }
            ui.postDelayed(this, npDelayMs);
        }
    };

    /**
     * One reading from a station's public now-playing endpoint, or null if it
     * didn't answer with one.
     *
     * Every field is optional and nothing is invented: a station that doesn't
     * publish a year, a bitrate or a show simply leaves those blank and the
     * rows that would carry them stay hidden. This is also the only place the
     * show/DJ line comes from — the page is never scraped for it.
     */
    private NowPlaying readNowPlaying(String base) {
        HttpURLConnection c = null;
        try {
            c = Http.open(base, "api/now-playing", 4000, store.savedAuth(base));
            String body;
            try (InputStream in = c.getInputStream()) {
                body = Http.readTextCapped(in, Http.MAX_JSON_CHARS);
            }
            JSONObject o = new JSONObject(body);
            JSONObject track = o.optJSONObject("nowPlaying");
            NowPlaying np = new NowPlaying();
            // Answering at all is the thing being reported here. A body with no
            // track in it still proves the station is up, so it comes back as a
            // reading with hasTrack false rather than as a failure.
            if (track == null) return np;
            np.hasTrack = true;
            np.title = track.optString("title", "—");
            np.artist = track.optString("artist", "");
            np.album = track.optString("album", "");
            np.year = optText(track, "year");
            np.coverId = track.optString("subsonic_id", null);
            np.bitrate = kbps(optText(track, "bitrate"));
            if (np.bitrate.isEmpty()) np.bitrate = kbps(optText(o, "bitrate"));
            JSONObject dj = o.optJSONObject("dj");
            JSONObject show = o.optJSONObject("show");
            if (dj != null) np.show = dj.optString("name", "");
            if ((np.show == null || np.show.isEmpty()) && show != null) {
                np.show = show.optString("name", "");
            }
            return np;
        } catch (Exception ignored) {
            return null;
        } finally { Http.close(c); }
    }

    /**
     * A bitrate in kbps, whichever unit the station published it in. Some
     * report 320, some report 320000, and the label says "kbps" either way —
     * so the one thing not to do is print the number unread.
     */
    private static String kbps(String raw) {
        if (raw.isEmpty()) return "";
        try {
            long v = Long.parseLong(raw.replaceAll("[^0-9]", ""));
            if (v >= 1000) v /= 1000;
            return v > 0 ? String.valueOf(v) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** A JSON field as trimmed text whether the station sent it as a string or a number. */
    private static String optText(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        String s = String.valueOf(v).trim();
        return "null".equals(s) ? "" : s;
    }

    /**
     * Record whether a station answered, and repaint its row if that changed.
     *
     * Two things write here: the health probe on the way into the picker, and
     * every successful now-playing reading afterwards. The probe alone was a
     * single opinion formed once and never revisited, so one slow moment on the
     * way in left a row reading OFF AIR for as long as the picker stayed open —
     * beside a panel cheerfully showing what that station was playing.
     */
    private void markReachable(String url, boolean reachable) {
        if (url == null) return;
        boolean changed = reachable ? unreachable.remove(url) : unreachable.add(url);
        if (changed && stationsVisible()) stationsAdapter.notifyDataSetChanged();
    }

    /**
     * The station answered but named no track — a changeover.
     *
     * The last track stays on screen. Replacing it with "nothing playing" for
     * the couple of seconds between songs is precisely the flicker this exists
     * to stop; only a station we have never seen playing anything gets the
     * neutral text.
     */
    private void showBetweenTracks() {
        if (currentUrl != null && currentUrl.equals(npTrackForUrl)) return;
        npLabel.setText("ON AIR");
        nowPlayingText.setText("between tracks");
        nowPlayingText.setTextColor(palette.muted);
        npArtist.setText("");
        npAlbum.setText("");
        npShowRow.setVisibility(View.GONE);
        npStation.setText(currentStationName());
        npStatus.setText("");
        stripCaption.setText("ON AIR");
        stripTrack.setText("between tracks");
        stripTrack.setTextColor(palette.muted);
        setCover(null);
    }

    /** Nothing picked yet: the panel says so rather than sitting blank. */
    private void showNothingTuned() {
        npMisses = 0;
        npTrackForUrl = null;
        npLabel.setText("NOT TUNED");
        nowPlayingText.setText("nothing tuned yet");
        nowPlayingText.setTextColor(palette.muted);
        npArtist.setText("pick a station to tune in");
        npAlbum.setText("");
        npShowRow.setVisibility(View.GONE);
        npStation.setText("");
        npStatus.setText("");
        stripCaption.setText("NOW PLAYING");
        stripTrack.setText("nothing tuned yet");
        stripTrack.setTextColor(palette.muted);
        setPlayGlyph(false);
        setOnAir(false);
        setCover(null);
    }

    /** Tuned, and the station answered. */
    private void showNowPlaying(String base, NowPlaying np) {
        npTrackForUrl = currentUrl;   // a gap after this keeps it on screen
        npLabel.setText("NOW PLAYING");
        nowPlayingText.setText(np.title);
        nowPlayingText.setTextColor(palette.ink);
        npArtist.setText(np.artist);

        StringBuilder album = new StringBuilder(np.album);
        if (!np.year.isEmpty()) {
            if (album.length() > 0) album.append("  ·  ");
            album.append(np.year);
        }
        npAlbum.setText(album.toString());

        boolean hasShow = np.show != null && !np.show.isEmpty();
        npShowRow.setVisibility(hasShow ? View.VISIBLE : View.GONE);
        if (hasShow) npShowName.setText(np.show.toUpperCase(Locale.US));

        String station = currentStationName();
        npStation.setText(station);
        // Only claims a bitrate the station actually published — and says
        // nothing at all until the player has reported a state, rather than
        // calling a station "paused" on the strength of not having asked yet.
        npStatus.setText(!pageLoaded ? ""
                : audioLive
                        ? (np.bitrate.isEmpty() ? "playing" : "playing  ·  " + np.bitrate + " kbps")
                        : "paused");

        stripCaption.setText(station == null || station.isEmpty()
                ? "NOW PLAYING" : "NOW PLAYING  ·  " + station.toUpperCase(Locale.US));
        stripTrack.setText(np.artist.isEmpty() ? np.title : np.title + " — " + np.artist);
        stripTrack.setTextColor(palette.ink);

        loadCover(base, np.coverId);
    }

    /** Tuned, but the station isn't answering its API. */
    private void showStationSilent() {
        npTrackForUrl = null;
        npLabel.setText("OFF AIR");
        nowPlayingText.setText("station not responding");
        nowPlayingText.setTextColor(palette.muted);
        npArtist.setText("");
        npAlbum.setText("");
        npShowRow.setVisibility(View.GONE);
        // Name, not address — same screenshot reasoning as the station rows.
        npStation.setText(currentStationName());
        npStatus.setText("");
        stripCaption.setText("OFF AIR");
        stripTrack.setText(currentStationName());
        stripTrack.setTextColor(palette.muted);
        setOnAir(false);
        setCover(null);
    }

    /**
     * The on-air lamp: a slow alpha breath, only while audio is actually live.
     * The level meter starts and stops on the same signal — both are claims
     * that sound is coming out, so neither may outlive it.
     */
    private void setOnAir(boolean live) {
        if (live) {
            onAirDot.setVisibility(View.VISIBLE);
            if (!onAirPulse.isStarted()) onAirPulse.start();
        } else {
            if (onAirPulse.isStarted()) onAirPulse.cancel();
            onAirDot.setAlpha(1f);
            onAirDot.setVisibility(View.INVISIBLE);
        }
        setMetersRunning(live);
    }

    private void setCover(android.graphics.Bitmap bmp) {
        if (bmp == null) {
            shownCoverId = null;
            npArt.setImageDrawable(null);
            if (stripArt != null) stripArt.setImageDrawable(null);
        } else {
            npArt.setImageBitmap(bmp);
            if (stripArt != null) stripArt.setImageBitmap(bmp);
        }
    }

    /** Pull album art through the station's cover proxy; only on a track change. */
    private void loadCover(final String base, final String subsonicId) {
        if (subsonicId == null || subsonicId.isEmpty()) { setCover(null); return; }
        if (subsonicId.equals(shownCoverId)) return;
        shownCoverId = subsonicId;
        // Decoded for the LARGER of the two squares that may show it — the
        // panel's 150dp and the minimal strip's 68dp share one bitmap, and
        // sizing for the strip would leave the panel's art soft.
        final int artPx = dp(150);
        offThread(() -> {
            android.graphics.Bitmap bmp = null;
            HttpURLConnection c = null;
            try {
                c = Http.open(base, "api/cover/"
                        + java.net.URLEncoder.encode(subsonicId, "UTF-8"),
                        5000, store.savedAuth(base));
                byte[] data;
                try (InputStream in = c.getInputStream()) {
                    data = Http.readBytesCapped(in, Http.MAX_COVER_BYTES);
                }
                if (data != null) bmp = Http.decodeCover(data, artPx);
            } catch (Exception ignored) {
            } finally { Http.close(c); }
            final android.graphics.Bitmap out = bmp;
            ui.post(() -> {
                if (!subsonicId.equals(shownCoverId)) return; // a later track won
                npArt.setImageBitmap(out);            // null clears it
                if (stripArt != null) stripArt.setImageBitmap(out);
            });
        });
    }

    /* ------------------------------------------------------------------ */
    /* Password-protected stations (HTTP basic auth)                       */
    /* ------------------------------------------------------------------ */


    /** Whether `host` is the station currently tuned — see StationStore.sameHost. */
    private boolean isStationHost(String host) {
        return StationStore.sameHost(host, currentUrl);
    }

    /**
     * True when a navigation must be swallowed because it leaves the station.
     * Before a station is tuned there is nothing to compare against, so the
     * initial load is always allowed through.
     */
    private boolean blockOffStation(String url) {
        if (url == null || currentUrl == null) return false;
        if (isStationHost(StationStore.hostOf(url))) return false;
        ui.post(() -> Toast.makeText(this,
                "That link leaves the station — Wave TV stays on the one you tuned",
                Toast.LENGTH_SHORT).show());
        return true;
    }


    /**
     * A station behind HTTP basic auth. Ask once, optionally remember, and hand
     * the credential to the WebView; the same value is reused for the
     * now-playing and cover-art calls.
     */
    private void promptForCredentials(final android.webkit.HttpAuthHandler handler, final String url) {
        // The challenge can land after the viewer has already walked away.
        // Cancel rather than abandon the handler, and never try to raise a
        // dialog on a window that has gone.
        if (gone()) { handler.cancel(); return; }
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
        // Name the station rather than leaving "this station" to the viewer's
        // assumption — the prompt is worth a lot to anyone who can provoke it.
        // The name, not the address: same screenshot reasoning as the picker.
        String who = currentStationName();
        blurb.setText((who.isEmpty() ? "This station" : who)
                + " asks for a username and password.");
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
                        store.saveAuth(url, enc);
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
        for (android.widget.Button b : playButtons) {
            b.setText(playing ? "❚❚" : "▶");
            // The pause mark is two glyphs wide where play is one, so it steps
            // down from whatever size that button was built at.
            float base = b.getTag() instanceof Float ? (Float) b.getTag() : 14f;
            b.setTextSize(playing ? base * 0.75f : base);
        }
    }

    /** Play/pause from the station list — tunes the highlighted station if idle. */
    private void togglePlayFromList() {
        if (currentUrl == null || !pageLoaded) {
            int sel = stationsListView.getSelectedItemPosition();
            if (sel < 0 || sel >= stations.size()) sel = indexOfUrl(lastStationUrl());
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
        js("window.__swtvTuned ? __swtvTuned() : false", r -> {
            boolean playing = r != null && r.contains("true");
            setPlayGlyph(playing);
            setOnAir(playing);
            // Arm on the edge, not on every reading. The now-playing poll calls
            // this every 7 seconds while the picker is on screen, and
            // armSleepTimer() begins by cancelling the pending one — so sitting
            // on the station list with a station playing reset the countdown
            // before it could ever elapse, and the sleep timer never fired at
            // all. Leaving the app on the picker is exactly what someone
            // falling asleep to the radio does.
            if (playing && !audioLive) armSleepTimer();
            else if (!playing) cancelSleepTimer();
            audioLive = playing;
        });
    }

    /* ------------------------------------------------------------------ */
    /* Station list state                                                  */
    /* ------------------------------------------------------------------ */

    private void refreshStationList() {
        stationsAdapter.notifyDataSetChanged();
    }

    private boolean stationsVisible() {
        return stationsPanel.getVisibility() == View.VISIBLE;
    }

    private void showStations() {
        refreshStationList();
        stationsPanel.setVisibility(View.VISIBLE);
        // An empty ListView takes no focus, which would leave the D-pad with
        // nowhere to go on first run; hand it to Add station instead.
        if (stations.isEmpty()) addChip.requestFocus();
        else stationsListView.requestFocus();
        int last = indexOfUrl(lastStationUrl());
        if (last >= 0) stationsListView.setSelection(last);
        ui.removeCallbacks(nowPlayingPoll);
        npDelayMs = NOW_PLAYING_POLL_MS; // a fresh look asks straight away
        npMisses = 0;
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
            offThread(() -> {
                boolean ok = false;
                HttpURLConnection c = null;
                try {
                    String base = url.endsWith("/") ? url : url + "/";
                    c = Http.open(base, "api/health", 3000, store.savedAuth(base));
                    int code = c.getResponseCode();
                    ok = code >= 200 && code < 500; // a 401 still means it's there
                } catch (Exception ignored) {
                } finally { Http.close(c); }
                final boolean reachable = ok;
                ui.post(() -> markReachable(url, reachable));
            });
        }
    }

    private void openStation(int index) {
        if (index < 0 || index >= stations.size()) return;
        Station st = stations.get(index);
        store.rememberLast(st.url);
        stationsPanel.setVisibility(View.GONE);
        ui.removeCallbacks(nowPlayingPoll);
        boolean wasLoaded = pageLoaded; // captured before the reload clears it
        pageLoaded = false;
        if (!st.url.equals(currentUrl) || loadFailed) {
            // Reload on a different station, and also when what's currently
            // sitting in the WebView is an error page — otherwise re-picking
            // the station just re-showed the stale failure and the only way
            // back was Menu > Reload, even once the server had recovered.
            currentUrl = st.url;
            loadStation(st.url);
        } else {
            // Returning to a station that is already up: keep it playing. Only
            // claim it is loaded if it genuinely finished — asserting that for
            // a page still on its way had BACK and the transport evaluating
            // hooks against a half-built document that had never run the
            // helper, so neither did anything and both looked broken.
            pageLoaded = wasLoaded;
        }
        if (web != null) web.requestFocus();
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
        if (web == null) return;
        pageLoaded = false;
        loadFailed = false;
        shownCoverId = null;
        freshStationLoad = true;
        web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.loadUrl(url);
        // Back to normal caching once this navigation is under way, so ordinary
        // browsing still benefits from the cache.
        ui.postDelayed(() -> {
            if (web != null) web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        }, 5000);
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
        bg.setColor(Colors.blend(palette.bg, palette.ink, 0.06f));
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), Colors.withAlpha(palette.ink, 90));
        return bg;
    }

    /** Accent outline while the view holds D-pad focus. */
    private void strokeOnFocus(View v, final GradientDrawable bg) {
        v.setOnFocusChangeListener((view, has) ->
                bg.setStroke(dp(has ? 2 : 1), has ? palette.accent : Colors.withAlpha(palette.ink, 90)));
    }

    private EditText dlgField(String hintText) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hintText);
        e.setTextColor(palette.ink);
        e.setHintTextColor(Colors.withAlpha(palette.muted, 130));
        e.setTextSize(16);
        e.setTypeface(Typeface.MONOSPACE);
        GradientDrawable bg = fieldBg();
        e.setBackground(bg);
        // Focus styling is set below rather than through strokeOnFocus(): this
        // field needs the same listener to re-arm the keyboard gate.
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        // Landing on the field with the D-pad shows no keyboard; pressing OK is
        // what opens it.
        e.setShowSoftInputOnFocus(false);
        e.setOnClickListener(v -> activateField(e));
        // The click listener alone was not enough on Fire TV. A focused
        // EditText consumes DPAD_CENTER and ENTER as key events of its own and
        // never calls performClick(), so OK did nothing at all: no keyboard, no
        // way to type, and no clue why. Opening on the key press is what the
        // remote actually sends.
        e.setOnKeyListener((v, keyCode, ev) -> {
            if (ev == null || ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A;
            if (!ok) return false;
            activateField(e);
            return true;
        });
        // Leaving the field re-arms the gate. Otherwise the first OK press
        // turned auto-show on for good, and every later pass through the field
        // on the way to the buttons threw the keyboard back up.
        e.setOnFocusChangeListener((v, has) -> {
            bg.setStroke(dp(has ? 2 : 1), has ? palette.accent : Colors.withAlpha(palette.ink, 90));
            if (!has) e.setShowSoftInputOnFocus(false);
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


    /** A null station means add new; otherwise edit that one. */
    private void showStationDialog(final Station editing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(palette.bg);
        box.setPadding(dp(28), dp(20), dp(28), dp(14));

        TextView heading = new TextView(this);
        heading.setText(editing == null ? "ADD STATION" : "EDIT STATION");
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

        // The form runs in the order the answers are actually decided:
        // protocol, then the address, then the name — which is optional and can
        // be left to the station to supply. Asking for the name first meant the
        // one field nobody has to fill in was the one blocking the way to the
        // two that matter.
        //
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

        final int schemeOff = Colors.blend(palette.bg, palette.ink, 0.06f);
        final int schemeOn = Colors.withAlpha(palette.accent, 70);
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

        // --- name -------------------------------------------------------
        // No mic buttons here: the TV's own keyboard already offers
        // press-and-hold-to-speak once a field is opened.
        box.addView(dlgCaption("NAME  (OPTIONAL)"));
        final EditText nameIn = dlgField("leave blank to use the station's own name");
        box.addView(nameIn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (editing != null) {
            nameIn.setText(editing.name);
            useHttps[0] = editing.url.startsWith("https://");
            hostIn.setText(editing.url.replaceFirst("^https?://", ""));
        }
        styleScheme.run();

        // Done on the keyboard should save, exactly as the Save button does —
        // having to dismiss the IME and hunt for a button is a poor way to end
        // a form on a remote. NEXT moves address -> name without closing it.
        hostIn.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        nameIn.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(box)
                // Deliberately null: the builder's own listener dismisses
                // unconditionally, which would throw away the entry when the
                // address is empty. The real handler is attached in onShow.
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

        final Runnable commit = () -> {
            String host = hostIn.getText().toString().trim().replaceFirst("^https?://", "");
            String problem = StationStore.addressProblem(host);
            if (problem != null) {
                Toast.makeText(this, problem, Toast.LENGTH_SHORT).show();
                return;
            }
            // Drop the IME while its own field still exists; once the dialog
            // window is gone there is no token to hide it from, and it sits
            // over the picker swallowing every remote key.
            hideKeyboardFrom(hostIn);
            saveStation(editing, nameIn.getText().toString().trim(), host, useHttps[0]);
            dlg.dismiss();
        };

        hostIn.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                activateField(nameIn);
                return true;
            }
            return false;
        });
        nameIn.setOnEditorActionListener((v, actionId, ev) -> {
            boolean done = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    // Some TV keyboards send a bare ENTER rather than an action id.
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && ev.getAction() == KeyEvent.ACTION_DOWN);
            if (done) { commit.run(); return true; }
            return false;
        });

        dlg.setOnShowListener(d ->
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> commit.run()));

        // However the dialog ends — saved, cancelled or Back — the picker has
        // to be left usable. The list may have only just come into existence
        // (it replaces the empty view on the very first station), so nothing
        // holds focus unless it is given explicitly, and the remote does
        // nothing at all until the app is restarted.
        dlg.setOnDismissListener(d -> stationsPanel.post(() -> {
            hideKeyboardFrom(stationsPanel);
            if (!stationsVisible()) return;
            if (stations.isEmpty()) addChip.requestFocus();
            else stationsListView.requestFocus();
        }));

        dlg.show();
    }

    /** Open a field for typing — the explicit OK press the keyboard gate wants. */
    private void activateField(EditText e) {
        e.requestFocus();
        e.setShowSoftInputOnFocus(true);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboardFrom(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && v != null && v.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    /** The Save half of the add/edit dialog. A null station means add new. */
    private void saveStation(Station editing, String name, String host, boolean useHttps) {
        String url = (useHttps ? "https://" : "http://") + host;
        boolean autoName = name.isEmpty();
        if (autoName) name = host; // placeholder until the station answers
        if (editing == null) {
            stations.add(new Station(name, url));
        } else {
            // Identity, not the position captured when the dialog opened: the
            // list can have been reordered or emptied underneath it, and
            // writing back by index would edit somebody else.
            if (!stations.contains(editing)) return;
            String oldUrl = editing.url;
            editing.name = name;
            editing.url = url;
            if (!oldUrl.equals(url)) {
                if (oldUrl.equals(lastStationUrl())) store.rememberLast(url);
                unreachable.remove(oldUrl); // the old address is nobody's now
                // Carry any saved password to the new address, and make
                // sure a station being edited while on air reloads rather
                // than leaving the card polling the old host.
                store.moveAuth(oldUrl, url);
                if (oldUrl.equals(currentUrl)) {
                    currentUrl = url;
                    loadStation(url); // same path as tuning it, cache-bust and all
                }
            }
        }
        store.save(stations);
        refreshStationList();
        // A station added to an empty list also needs the pollers that
        // showStations() would normally have started, and a liveness probe.
        probeStations();
        ui.removeCallbacks(nowPlayingPoll);
        npDelayMs = NOW_PLAYING_POLL_MS; // a fresh look asks straight away
        npMisses = 0;
        ui.post(nowPlayingPoll);
        if (autoName) fetchStationName(url);
    }

    /**
     * The hold-OK menu. Everything past this point works on the Station object
     * rather than the position it was opened at: the Remove path in particular
     * puts a second dialog between the choice and the deletion, and by the time
     * that is answered the captured index may belong to a different station —
     * or to none, which threw.
     */
    private void showStationOptions(final int index) {
        if (index < 0 || index >= stations.size()) return;
        final Station st = stations.get(index);
        // "Forget saved password" only appears for a station that has one.
        final boolean hasSaved = store.savedAuth(st.url) != null;
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
                        openStation(stations.indexOf(st));
                    } else if (choice.equals("Edit")) {
                        showStationDialog(st);
                    } else if (choice.equals("Move up")) {
                        moveStation(st, -1);
                    } else if (choice.equals("Move down")) {
                        moveStation(st, 1);
                    } else if (choice.equals("Forget saved password")) {
                        store.forgetAuth(st.url);
                        Toast.makeText(this, "Saved password forgotten", Toast.LENGTH_SHORT).show();
                    } else {
                        new AlertDialog.Builder(this)
                                .setMessage("Remove “" + st.name + "”?")
                                .setPositiveButton("Remove", (d2, w2) -> removeStation(st))
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .show();
    }

    private void removeStation(Station st) {
        if (!stations.remove(st)) return; // already gone
        store.forgetAuth(st.url);
        unreachable.remove(st.url);
        store.save(stations);
        refreshStationList();
    }

    /**
     * Shift a station one place. The "last played" pointer is a URL now, so
     * reordering no longer has to be kept in step with it by hand.
     */
    private void moveStation(Station st, int delta) {
        int from = stations.indexOf(st);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= stations.size()) return;
        stations.remove(from);
        stations.add(to, st);
        store.save(stations);
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
        offThread(() -> {
            String found = null;
            HttpURLConnection c = null;
            try {
                c = Http.open(base, "api/now-playing", 5000, store.savedAuth(base));
                String body;
                try (InputStream in = c.getInputStream()) {
                    body = Http.readTextCapped(in, Http.MAX_JSON_CHARS);
                }
                JSONObject dj = new JSONObject(body).optJSONObject("dj");
                if (dj != null) {
                    String s = dj.optString("station", "");
                    if (!s.isEmpty()) found = s;
                }
            } catch (Exception ignored) {
            } finally { Http.close(c); }
            final String name = found;
            if (name == null) return;
            ui.post(() -> {
                for (Station st : stations) {
                    if (st.url.equals(url)) {
                        st.name = name;
                        store.save(stations);
                        refreshStationList();
                        return;
                    }
                }
            });
        });
    }


    /* ------------------------------------------------------------------ */
    /* Remote keys                                                         */
    /* ------------------------------------------------------------------ */

    private boolean swallowCenterUp = false;
    /** When OK went down, and whether its long press has already fired. */
    private long centerDownAt = 0;
    private boolean centerLongFired = false;

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
            // Hold OK in the player for the menu.
            //
            // The Google TV Streamer's remote has no MENU button, which left
            // Reload player and Voice request with no route to them at all on
            // that device. The picker has held OK for options since v1, so this
            // is the same gesture in the other half of the app.
            //
            // Detected here rather than through onKeyLongPress: the WebView has
            // focus in the player and consumes the key down, so the Activity's
            // own key callbacks — and the framework's long-press tracking with
            // them — never run. Deliberately built so that a remote which does
            // NOT auto-repeat simply behaves as it always did: the first press
            // is never swallowed, and nothing is intercepted unless a genuine
            // long press is seen.
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && !stationsVisible() && !editableFocused) {
                if (event.getRepeatCount() == 0) {
                    centerDownAt = event.getEventTime();
                    centerLongFired = false;
                } else if (!centerLongFired && centerDownAt > 0
                        && event.getEventTime() - centerDownAt
                                >= android.view.ViewConfiguration.getLongPressTimeout()) {
                    centerLongFired = true;
                    swallowCenterUp = true;
                    showMenu();
                    return true;
                }
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                centerDownAt = 0;
                centerLongFired = false;
                if (swallowCenterUp) {
                    swallowCenterUp = false;
                    return true;
                }
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
        js("window.__swtvActivateField ? __swtvActivateField() : false", r -> {
            if (r == null || !r.contains("true")) {
                // Focus moved on before the press landed — clear the stale flag so
                // the next OK reaches the page instead of being swallowed again.
                editableFocused = false;
                return;
            }
            fieldActivated = true;
            keyboardOpenedAt = System.currentTimeMillis();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm == null || web == null) return;
            web.requestFocus();
            imm.restartInput(web); // re-read the field now that it accepts input
            imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void handleBackFromPlayer() {
        if (!pageLoaded) { showStations(); return; }
        js("window.__swtvBack ? __swtvBack() : false", result -> {
            if (result != null && result.contains("true")) return; // a drawer was closed
            if (web != null && web.canGoBack()) {
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
                // Back to what is actually playing, which is what this branch
                // is guarded on — no need to consult the remembered pointer.
                openStation(indexOfUrl(currentUrl));
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
                        case 0: showStationDialog(null); break;
                        case 1: showSleepDialog(); break;
                        case 2: finish(); break;
                    }
                })
                .show();
    }

    /* ------------------------------------------------------------------ */
    /* Sleep timer                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * A masthead chip. Square rather than a pill, in small letterspaced caps:
     * the rounded pills were the one soft shape on a screen whose every other
     * edge — rules, panel, art, transport — is a right angle.
     */
    private android.widget.Button buildChip(String label, View.OnClickListener onClick) {
        final android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setAllCaps(true);
        b.setTextSize(10);
        b.setTypeface(Typeface.MONOSPACE);
        b.setLetterSpacing(0.1f);
        b.setTextColor(Colors.MUTED);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(13), dp(7), dp(13), dp(7));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(0);
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
        bg.setColor(focused ? Colors.withAlpha(palette.accent, 46) : Color.TRANSPARENT);
        bg.setStroke(dp(focused ? 2 : 1),
                focused ? palette.accent : Colors.withAlpha(palette.ink, 75));
        b.setTextColor(focused ? palette.ink : palette.muted);
        // The drawn glyph has no text colour to inherit.
        if (b == themeChip && themeGlyph != null) {
            themeGlyph.tint(focused ? palette.ink : palette.muted);
        }
    }

    /** Keep the masthead chip in step with the stored setting. */
    private void updateSleepChip() {
        if (sleepChip != null) sleepChip.setText("SLEEP " + sleepHours() + "H");
    }

    private int sleepHours() {
        int h = prefs().getInt(KEY_SLEEP_HOURS, SLEEP_HOURS_DEFAULT);
        return h < 1 || h > SLEEP_HOURS_MAX ? SLEEP_HOURS_DEFAULT : h;
    }

    /** Stops the stream after the chosen run-time; play again to restart it. */
    private final Runnable sleepTimer = new Runnable() {
        @Override
        public void run() {
            js("window.__swtvStop ? __swtvStop() : false", r -> {
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
        if (gone() || (errorDialog != null && errorDialog.isShowing())) return;
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
        // EXTRA_LANGUAGE wants a BCP-47 tag. A Locale is Serializable, so
        // passing one compiled and looked right, and then getStringExtra on
        // the far side quietly returned null — the hint has never applied.
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
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
        js("window.__swtvInsertText ? __swtvInsertText(" + jsString(text) + ") !== false : false",
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
                    // Through the same checked door as every other dismissal.
                    // This one is posted from the bridge's own thread, so it
                    // can land after onDestroy has detached and nulled the
                    // WebView — reaching for web.getWindowToken() directly was
                    // the one asynchronous caller still doing so unguarded.
                    hideKeyboardFrom(web);
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
        js(code, null);
    }

    /**
     * Evaluate in the player, if there is still a player to evaluate in.
     *
     * Every one of these is reached from something asynchronous — a poller, a
     * page callback, a dictation result, the sleep timer — and onDestroy tears
     * the WebView down underneath all of them. Funnelling them through one
     * null-checked door is what makes that safe, rather than hoping each
     * caller was cancelled in time.
     */
    private void js(String code, android.webkit.ValueCallback<String> callback) {
        if (web == null) return;
        web.evaluateJavascript(code, callback);
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

    /**
     * Whether this activity's window has gone. Dialogs here are raised from
     * network and WebView callbacks, which can arrive well after the viewer
     * pressed Exit; AlertDialog.show() on a finishing activity throws
     * BadTokenException and takes the app down with it.
     */
    private boolean gone() {
        return isFinishing() || isDestroyed();
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
        // Everything posted to `ui` has to go, not just the three named
        // pollers. loadStation's cache-mode reset (5s out), onPageFinished's
        // playback sync (3.5s), startVoiceRequest's dictation kick (700ms) and
        // every ui.post from the network threads all outlive this method, and
        // several of them reach for a WebView that is about to stop existing.
        ui.removeCallbacksAndMessages(null);
        net.shutdownNow(); // in-flight polls have nothing left to report to
        cancelSleepTimer();
        if (onAirPulse != null) onAirPulse.cancel();
        if (paletteAnim != null) paletteAnim.cancel();
        if (web != null) {
            // Detach first: destroying a WebView that is still in the view
            // hierarchy is unsupported and crashes on some WebView builds.
            if (web.getParent() instanceof ViewGroup) {
                ((ViewGroup) web.getParent()).removeView(web);
            }
            web.destroy();
            web = null; // js() and the loadStation callbacks read this
        }
        super.onDestroy();
    }
}
