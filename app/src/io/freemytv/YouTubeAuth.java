package io.freemytv;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Google OAuth device flow ("TVs and Limited Input devices" client) for YouTube (youtube.readonly). State lives in two
 * small files, so the settings process (which may start a link) and the home process (which polls and uses it) agree:
 * youtube-token.json (refresh token) and youtube-link.json (the pending device code).
 */
final class YouTubeAuth {
    private YouTubeAuth() {}

    private static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    private static String access;
    private static long accessUntil, nextPoll;

    static boolean configured() {
        return !Config.GOOGLE_CLIENT_ID.trim().isEmpty() && !Config.GOOGLE_CLIENT_SECRET.trim().isEmpty();
    }

    private static File tokenFile(Context c) { return new File(c.getFilesDir(), "youtube-token.json"); }
    private static File linkFile(Context c) { return new File(c.getFilesDir(), "youtube-link.json"); }

    static boolean linked(Context c) { return configured() && tokenFile(c).exists(); }

    /** The pending code ({user_code, verification_url, …}) while it is still valid; else null. */
    static JSONObject pending(Context c) {
        JSONObject o = readJson(linkFile(c));
        if (o == null || System.currentTimeMillis() > o.optLong("expires_at")) return null;
        return o;
    }

    /** Asks Google for a new device code (network: call off the main thread). */
    static synchronized JSONObject start(Context c) throws IOException {
        String body = "client_id=" + enc(Config.GOOGLE_CLIENT_ID.trim()) + "&scope=" + enc(SCOPE);
        JSONObject o = post("https://oauth2.googleapis.com/device/code", body);
        if (!o.has("device_code")) throw new IOException("device code: " + o.optString("error", o.toString()));
        try {
            o.put("expires_at", System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000L);
            if (!o.has("verification_url")) o.put("verification_url", o.optString("verification_uri", "https://www.google.com/device"));
        } catch (Exception ignored) {
        }
        writeJson(linkFile(c), o);
        nextPoll = 0;
        AppLog.w("youtube link: code " + o.optString("user_code"));
        return o;
    }

    /** One poll of the pending code; true when the user just approved (the token is saved). */
    static synchronized boolean poll(Context c) {
        JSONObject p = pending(c);
        if (p == null || System.currentTimeMillis() < nextPoll) return false;
        long interval = Math.max(5, p.optLong("interval", 5)) * 1000L;
        nextPoll = System.currentTimeMillis() + interval;
        try {
            JSONObject o = post("https://oauth2.googleapis.com/token", "client_id=" + enc(Config.GOOGLE_CLIENT_ID.trim())
                    + "&client_secret=" + enc(Config.GOOGLE_CLIENT_SECRET.trim()) + "&device_code=" + enc(p.optString("device_code"))
                    + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"));
            if (o.has("refresh_token")) {
                writeJson(tokenFile(c), new JSONObject().put("refresh_token", o.getString("refresh_token")));
                access = o.optString("access_token", null);
                accessUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 120) * 1000L;
                //noinspection ResultOfMethodCallIgnored
                linkFile(c).delete();
                AppLog.w("youtube link: approved");
                return true;
            }
            String error = o.optString("error");
            if (error.equals("slow_down")) nextPoll += 5000;
            else if (!error.equals("authorization_pending")) {
                AppLog.w("youtube link: " + error);
                //noinspection ResultOfMethodCallIgnored
                linkFile(c).delete();   // denied or expired: a new code next time
            }
        } catch (Exception e) {
            AppLog.w("youtube link poll: " + e.getMessage());
        }
        return false;
    }

    /** A valid access token (refreshed when needed), or null if not linked / revoked. */
    static synchronized String accessToken(Context c) throws IOException {
        if (access != null && System.currentTimeMillis() < accessUntil) return access;
        JSONObject t = readJson(tokenFile(c));
        if (t == null) return null;
        JSONObject o = post("https://oauth2.googleapis.com/token", "client_id=" + enc(Config.GOOGLE_CLIENT_ID.trim())
                + "&client_secret=" + enc(Config.GOOGLE_CLIENT_SECRET.trim()) + "&refresh_token=" + enc(t.optString("refresh_token"))
                + "&grant_type=refresh_token");
        if (!o.has("access_token")) {
            if ("invalid_grant".equals(o.optString("error"))) {
                AppLog.w("youtube: access revoked, unlinking");
                unlink(c);
                return null;
            }
            throw new IOException("token refresh: " + o.optString("error", o.toString()));
        }
        access = o.optString("access_token");
        accessUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 120) * 1000L;
        return access;
    }

    static synchronized void unlink(Context c) {
        access = null;
        //noinspection ResultOfMethodCallIgnored
        tokenFile(c).delete();
        //noinspection ResultOfMethodCallIgnored
        linkFile(c).delete();
    }

    // MARK: - Helpers

    private static String enc(String s) throws IOException { return URLEncoder.encode(s, "UTF-8"); }

    /** POST a form; the JSON answer, error bodies (400/401/428) included. */
    private static JSONObject post(String url, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            try (OutputStream o = c.getOutputStream()) { o.write(form.getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
            try {
                return new JSONObject(out.toString("UTF-8"));
            } catch (Exception e) {
                throw new IOException("HTTP " + code);
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject readJson(File f) {
        try {
            if (!f.exists()) return null;
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeJson(File f, JSONObject o) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }
}
