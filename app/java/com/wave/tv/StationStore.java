package com.wave.tv;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the picker keeps on disk: the station list itself, which station
 * to come back to, and any saved basic-auth credentials.
 *
 * Split out of the Activity because none of it is about drawing. It is also
 * the part where a mistake is durable rather than momentary — a wrong key or a
 * missed migration outlives the process — so it is worth being able to read
 * without a thousand lines of view construction around it.
 */
class StationStore {

    private static final String KEY_STATIONS = "stations";
    /** Superseded by KEY_LAST_URL; still read once to migrate an old install. */
    private static final String KEY_LAST_INDEX = "lastStation";
    private static final String KEY_LAST_URL = "lastStationUrl";

    private final SharedPreferences prefs;

    StationStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /* ---- the list ---------------------------------------------------- */

    ArrayList<Station> load() {
        ArrayList<Station> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_STATIONS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Station(o.optString("name", "Station"), o.getString("url")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    void save(List<Station> stations) {
        try {
            JSONArray arr = new JSONArray();
            for (Station st : stations) {
                JSONObject o = new JSONObject();
                o.put("name", st.name);
                o.put("url", st.url);
                arr.put(o);
            }
            prefs.edit().putString(KEY_STATIONS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /* ---- which station to come back to -------------------------------- */

    /**
     * Held as a URL rather than a list position. As an index it went stale the
     * moment the list changed: removing a station left the pointer aimed at
     * whichever one slid into that slot.
     */
    String lastUrl(List<Station> stations) {
        String url = prefs.getString(KEY_LAST_URL, null);
        if (url != null) return url;
        int i = prefs.getInt(KEY_LAST_INDEX, -1); // migrate an older install
        return i >= 0 && i < stations.size() ? stations.get(i).url : null;
    }

    void rememberLast(String url) {
        prefs.edit().putString(KEY_LAST_URL, url).remove(KEY_LAST_INDEX).apply();
    }

    /* ---- saved credentials -------------------------------------------- */

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

    String savedAuth(String url) {
        return prefs.getString(authKey(url), null);
    }

    void saveAuth(String url, String base64Credential) {
        prefs.edit().putString(authKey(url), base64Credential).apply();
    }

    void forgetAuth(String url) {
        prefs.edit().remove(authKey(url)).apply();
    }

    /** Carry a saved credential to a station's new address, if it had one. */
    void moveAuth(String fromUrl, String toUrl) {
        String cred = prefs.getString(authKey(fromUrl), null);
        SharedPreferences.Editor e = prefs.edit().remove(authKey(fromUrl));
        if (cred != null) e.putString(authKey(toUrl), cred);
        e.apply();
    }

    /* ---- addresses ----------------------------------------------------- */

    /** Bare hostname of a URL, lowercased, or null if it won't parse. */
    static String hostOf(String url) {
        if (url == null) return null;
        try {
            String h = new URL(url).getHost();
            return h == null || h.isEmpty() ? null : h.toLowerCase(Locale.US);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether two URLs address the same station. Deliberately compares the host
     * alone: a station may legitimately redirect itself between ports, or
     * upgrade http to https, and both stay under the operator's control. A
     * different HOST is the thing worth refusing. Either argument may arrive as
     * a bare "name" or as "name:port".
     */
    static boolean sameHost(String host, String stationUrl) {
        if (host == null) return false;
        String station = hostOf(stationUrl);
        if (station == null) return false;
        return withoutWww(station)
                .equals(withoutWww(host.split(":", 2)[0].toLowerCase(Locale.US)));
    }

    /**
     * "www.name" and "name" are the same station.
     *
     * Typing the bare host is the natural thing to do, and a great many servers
     * answer it with a permanent redirect to the www form — getsubwave.com does
     * exactly that. Compared strictly, the station's own redirect looks like a
     * different host, so the player refused to follow it and the viewer got
     * "That link leaves the station" on the address they had just been given.
     * The two names are the same registrable domain under the same operator,
     * which is the thing sameHost is actually asking about.
     */
    private static String withoutWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /**
     * What is wrong with a typed address, or null if nothing is.
     *
     * A station URL is concatenated with "api/…" in four places, so anything
     * carrying a query or fragment quietly produces a nonsense endpoint —
     * "host/#x" becomes "http://host/#x/api/now-playing", which is a request
     * to the root. A path is fine and deliberately allowed; a station may well
     * live at example.com/radio. Userinfo is refused outright: to a reader
     * "station.local@evil.com" is the station, and to a URL parser it is not.
     */
    static String addressProblem(String host) {
        if (host.isEmpty()) return "A station needs an address";
        if (host.indexOf('@') >= 0) return "An address can't contain “@”";
        if (host.indexOf('?') >= 0 || host.indexOf('#') >= 0) {
            return "An address can't contain “?” or “#”";
        }
        if (host.indexOf(' ') >= 0 || host.indexOf('\t') >= 0) {
            return "An address can't contain spaces";
        }
        if (hostOf("http://" + host) == null) return "That doesn't look like an address";
        return null;
    }
}
