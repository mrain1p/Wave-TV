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
    private TextView npMeta;
    private android.widget.Button playButton;
    private android.widget.Button sleepChip;
    private android.widget.Button addChip;
    private android.widget.Button themeChip;
    private TextView titleView, subView, noteView, emptyView;
    private TextView colNum, colName, colStatus;
    private View accentRule, hairRule, headRule, npRule;
    private ThemeGlyph themeGlyph;
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
        stationsPanel.setPadding(dp(64), dp(32), dp(64), dp(20));

        // Masthead: wordmark and hints on the left, sleep-timer chip on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout headings = new LinearLayout(this);
        headings.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(this);
        titleView.setText("WAVE TV");
        titleView.setTextColor(Colors.INK);
        titleView.setTextSize(30);
        titleView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        headings.addView(titleView);

        subView = new TextView(this);
        subView.setText("OK TUNE IN  ·  HOLD OK FOR OPTIONS  ·  MENU FOR SETTINGS");
        subView.setTextColor(Colors.MUTED);
        subView.setTextSize(10);
        // Was the one line on this screen in the system sans, directly under a
        // monospace wordmark. A family mismatch between adjacent lines is the
        // loudest thing a layout can get wrong.
        subView.setTypeface(Typeface.MONOSPACE);
        subView.setLetterSpacing(0.16f);
        subView.setPadding(0, dp(6), 0, 0);
        headings.addView(subView);

        header.addView(headings, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        addChip = buildChip("+  Add station", v -> showStationDialog(null));
        header.addView(addChip, headerChipLp());

        themeChip = buildChip("", v -> cycleThemeMode());
        themeGlyph = new ThemeGlyph(dp(17));
        themeGlyph.tint(Colors.MUTED);
        themeChip.setCompoundDrawablesWithIntrinsicBounds(themeGlyph, null, null, null);
        themeChip.setPadding(dp(15), dp(9), dp(15), dp(9));
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

        // Column header: the list reads as a table rather than a stack of
        // slabs, and the number/status columns get somewhere to belong.
        LinearLayout colHead = new LinearLayout(this);
        colHead.setOrientation(LinearLayout.HORIZONTAL);
        colHead.setPadding(dp(14), dp(12), dp(14), dp(10));
        colNum = columnLabel("NO.");
        colName = columnLabel("STATION");
        colStatus = columnLabel("STATUS");
        colStatus.setGravity(Gravity.END);
        colHead.addView(colNum, new LinearLayout.LayoutParams(dp(56),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        colHead.addView(colName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        colHead.addView(colStatus, new LinearLayout.LayoutParams(dp(140),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        stationsPanel.addView(colHead, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        headRule = new View(this);
        stationsPanel.addView(headRule, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

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

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        stationsPanel.addView(stationsListView, lp);

        // First run lands here rather than on an open dialog with the keyboard
        // up: nothing has been read yet at that point, and a modal is a poor
        // first impression of a remote-driven app. The list swaps itself for
        // this line whenever it's empty, and focus goes to the Add chip so OK
        // still starts a station in one press.
        emptyView = new TextView(this);
        emptyView.setText("NO STATIONS\n\nPress  +  Add station  to begin");
        emptyView.setTextSize(13);
        emptyView.setLineSpacing(dp(3), 1f);
        emptyView.setTypeface(Typeface.MONOSPACE);
        emptyView.setLetterSpacing(0.14f);
        emptyView.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyView.setPadding(0, dp(52), 0, 0);
        LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        emptyLp.topMargin = dp(8);
        stationsPanel.addView(emptyView, emptyLp);
        stationsListView.setEmptyView(emptyView);

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
        playButton.setTextColor(Colors.INK);
        playButton.setMinWidth(0);
        playButton.setMinimumWidth(0);
        playButton.setMinHeight(0);
        playButton.setMinimumHeight(0);
        playButton.setPadding(dp(18), dp(6), dp(18), dp(8));
        playBgDrawable = new GradientDrawable();
        playBgDrawable.setColor(Color.argb(80, 197, 48, 42));
        playBgDrawable.setCornerRadius(dp(28));
        playBgDrawable.setStroke(dp(1), Colors.VERMILION);
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
        dot.setColor(Colors.VERMILION);
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
        npLabel.setTextColor(Colors.VERMILION);
        npLabel.setTextSize(9);
        npLabel.setTypeface(Typeface.MONOSPACE);
        npLabel.setLetterSpacing(0.3f);
        labelRow.addView(npLabel);
        npText.addView(labelRow);

        nowPlayingText = new TextView(this);
        nowPlayingText.setText("nothing tuned yet");
        nowPlayingText.setTextColor(Colors.INK);
        nowPlayingText.setTextSize(18);
        nowPlayingText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        nowPlayingText.setSingleLine(true);
        nowPlayingText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlayingText.setPadding(0, dp(2), 0, 0);
        npText.addView(nowPlayingText);

        npMeta = new TextView(this);
        npMeta.setText("pick a station above to tune in");
        npMeta.setTextColor(Colors.MUTED);
        npMeta.setTextSize(12);
        npMeta.setTypeface(Typeface.MONOSPACE);
        npMeta.setSingleLine(true);
        npMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        npMeta.setPadding(0, dp(3), 0, 0);
        npText.addView(npMeta);

        npCard.addView(npText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        npRule = new View(this);
        LinearLayout.LayoutParams npRuleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        npRuleLp.topMargin = dp(10);
        stationsPanel.addView(npRule, npRuleLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(4);
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
        if (THEME_LIGHT.equals(themeMode())) palette = Palette.LIGHT;
        applyPalette();
        applyPaletteToRows();
    }

    /* ------------------------------------------------------------------ */
    /* Station colour scheme                                               */
    /* ------------------------------------------------------------------ */

    private void stylePlayButton(boolean focused) {
        if (playBgDrawable == null) return;
        playBgDrawable.setColor(focused ? palette.accent : Colors.withAlpha(palette.accent, 80));
        playBgDrawable.setStroke(dp(1), palette.accent);
        playButton.setTextColor(focused ? palette.onAccent : palette.ink);
    }

    /** The views of one list row, held so a rebind doesn't rebuild them. */
    private static class Row {
        final LinearLayout view;
        final TextView num, name, status;
        Row(LinearLayout view, TextView num, TextView name, TextView status) {
            this.view = view;
            this.num = num;
            this.name = name;
            this.status = status;
        }
    }

    /** Everything about a row that doesn't change between stations. */
    private Row buildRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(15), dp(14), dp(15));

        TextView num = new TextView(this);
        num.setTextSize(15);
        num.setTypeface(Typeface.MONOSPACE);
        row.addView(num, new LinearLayout.LayoutParams(dp(56),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setTextSize(20);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Status as its own column in small letterspaced caps, rather than
        // appended to the name at the same weight.
        TextView status = new TextView(this);
        status.setTextSize(10);
        status.setTypeface(Typeface.MONOSPACE);
        status.setLetterSpacing(0.22f);
        status.setGravity(Gravity.END);
        row.addView(status, new LinearLayout.LayoutParams(dp(140),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Row r = new Row(row, num, name, status);
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
            r.num.setTextColor(Colors.withAlpha(palette.muted, 190));

            // Deliberately name-only: a private station's address is a secret
            // worth keeping, and this screen is the one most likely to end up
            // in a screenshot. The address stays reachable via hold-OK > Edit.
            r.name.setText(st.name);
            r.name.setTextColor(offline ? palette.muted : palette.ink);

            r.status.setText(playing ? "ON AIR" : (offline ? "NOT RESPONDING" : ""));
            r.status.setTextColor(playing ? palette.accentText : palette.muted);
            return r.view;
        }
    }

    /** A column heading: small, letterspaced, quiet. */
    private TextView columnLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Colors.MUTED);
        t.setTextSize(9);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLetterSpacing(0.3f);
        return t;
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
        panel.setColor(Colors.blend(palette.bg, palette.accent, 0.17f));
        float r = dp(3);
        panel.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});

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
        subView.setTextColor(Colors.withAlpha(palette.muted, 200));
        accentRule.setBackgroundColor(palette.accent);
        noteView.setTextColor(Colors.withAlpha(palette.muted, 150));
        if (emptyView != null) emptyView.setTextColor(palette.muted);
        if (hairRule != null) hairRule.setBackgroundColor(Colors.withAlpha(palette.ink, 45));
        if (headRule != null) headRule.setBackgroundColor(Colors.withAlpha(palette.ink, 45));
        if (npRule != null) npRule.setBackgroundColor(Colors.withAlpha(palette.ink, 45));
        int colInk = Colors.withAlpha(palette.muted, 175);
        if (colNum != null) colNum.setTextColor(colInk);
        if (colName != null) colName.setTextColor(colInk);
        if (colStatus != null) colStatus.setTextColor(colInk);
        if (themeGlyph != null) themeGlyph.tint(
                themeChip.hasFocus() ? palette.ink : palette.muted);

        // A footer section under a rule, not a floating bordered box: the
        // stroke competed with the list's own rules for the same job.
        npBgDrawable.setColor(Color.TRANSPARENT);
        npBgDrawable.setStroke(0, Color.TRANSPARENT);
        artBgDrawable.setColor(Colors.withAlpha(palette.ink, 26));
        artBgDrawable.setStroke(dp(1), Colors.withAlpha(palette.ink, 60));

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
                Colors.withAlpha(palette.ink, 22)));
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
        String m = themeMode();
        if (THEME_DARK.equals(m)) { animateToPalette(Palette.DARK); return; }
        if (THEME_LIGHT.equals(m)) { animateToPalette(Palette.LIGHT); return; }
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
            if (p != null) animateToPalette(p);
        });
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
                offThread(() -> {
                    String title = null, meta = null, coverId = null;
                    HttpURLConnection c = null;
                    try {
                        c = Http.open(base, "api/now-playing", 4000, store.savedAuth(base));
                        String body;
                        try (InputStream in = c.getInputStream()) {
                            body = Http.readTextCapped(in, Http.MAX_JSON_CHARS);
                        }
                        JSONObject o = new JSONObject(body);
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
                    } catch (Exception ignored) {
                    } finally { Http.close(c); }
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
                });
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
        final int artPx = dp(62); // the square the card draws it into
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
                if (out != null && subsonicId.equals(shownCoverId)) npArt.setImageBitmap(out);
                else if (out == null && subsonicId.equals(shownCoverId)) npArt.setImageDrawable(null);
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
        playButton.setText(playing ? "❚❚" : "▶");
        playButton.setTextSize(playing ? 15 : 20);
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
                ui.post(() -> {
                    boolean changed = reachable ? unreachable.remove(url) : unreachable.add(url);
                    if (changed && stationsVisible()) stationsAdapter.notifyDataSetChanged();
                });
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

        if (editing != null) {
            nameIn.setText(editing.name);
            useHttps[0] = editing.url.startsWith("https://");
            hostIn.setText(editing.url.replaceFirst("^https?://", ""));
        }
        styleScheme.run();

        // Done on the keyboard should save, exactly as the Save button does —
        // having to dismiss the IME and hunt for a button is a poor way to end
        // a form on a remote. NEXT moves name -> address without closing it.
        nameIn.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        hostIn.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);

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

        nameIn.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                activateField(hostIn);
                return true;
            }
            return false;
        });
        hostIn.setOnEditorActionListener((v, actionId, ev) -> {
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

    /** A pill button for the masthead, registered so palette changes reach it. */
    private android.widget.Button buildChip(String label, View.OnClickListener onClick) {
        final android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.MONOSPACE);
        b.setTextColor(Colors.MUTED);
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
        bg.setColor(focused ? Colors.withAlpha(palette.accent, 46) : palette.surface);
        bg.setStroke(dp(focused ? 2 : 1),
                focused ? palette.accent : Colors.withAlpha(palette.ink, 90));
        b.setTextColor(focused ? palette.ink : palette.muted);
        // The drawn glyph has no text colour to inherit.
        if (b == themeChip && themeGlyph != null) {
            themeGlyph.tint(focused ? palette.ink : palette.muted);
        }
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
