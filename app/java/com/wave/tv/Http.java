package com.wave.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Talking to a station's public API.
 *
 * A station is an arbitrary host typed in by hand, so nothing here trusts what
 * comes back: bodies are read with a ceiling, artwork is decoded at the size it
 * will actually be drawn, and a request carrying a credential refuses to follow
 * a redirect that would hand it somewhere else.
 */
final class Http {

    private Http() {}

    static final int MAX_JSON_CHARS = 256 * 1024;
    static final int MAX_COVER_BYTES = 4 * 1024 * 1024;

    /**
     * Open a station API call with the timeouts, credential and redirect policy
     * every caller here wants.
     *
     * Attaching a credential also pins the request to the host being addressed:
     * HttpURLConnection replays request headers onto redirect targets, so a
     * station answering /api/health with a 302 elsewhere would be handed the
     * password. Redirects are only refused when there is a credential to
     * protect, so stations without one behave exactly as they always did.
     */
    static HttpURLConnection open(String base, String path, int timeoutMs, String credential)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        if (credential != null) {
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Authorization", "Basic " + credential);
        }
        return c;
    }

    /**
     * Finish with a connection. Draining the error body matters as much as
     * disconnecting does: an undrained 401 or 502 keeps its socket out of the
     * keep-alive pool, so a station that was failing made every later poll pay
     * for a fresh connection.
     */
    static void close(HttpURLConnection c) {
        if (c == null) return;
        try (InputStream err = c.getErrorStream()) {
            if (err != null) {
                byte[] sink = new byte[4096];
                while (err.read(sink) > 0) { /* drain */ }
            }
        } catch (Exception ignored) {}
        c.disconnect();
    }

    /** Read at most `limit` characters of a response body. */
    static String readTextCapped(InputStream in, int limit) throws IOException {
        InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while (sb.length() < limit
                && (n = r.read(buf, 0, Math.min(buf.length, limit - sb.length()))) > 0) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /** Read a whole body, or null if it runs past `limit` — a part image is no use. */
    static byte[] readBytesCapped(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > limit) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Decode a cover at roughly the size the card draws it, whatever dimensions
     * the station actually sent. A fixed inSampleSize of 2 meant a 10000px
     * master still allocated a 5000px bitmap to fill a 62dp square.
     */
    static Bitmap decodeCover(byte[] data, int targetPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetPx && o.outHeight / (sample * 2) >= targetPx) {
            sample *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
