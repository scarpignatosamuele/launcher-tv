package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Music on the home screen: the music apps' carousel (square album tiles) with its chips: the user's own YouTube
 * library (liked songs, playlists; the account linked in Settings) first, the Italian Apple Music charts last.
 * (#98: no mini-player: YouTube Music on Google TV stops playing when Home comes up, so there is nothing to control.)
 */
final class Music {
    static final String YTM = "com.google.android.youtube.tvmusic";
    static final String YOUTUBE = "com.google.android.youtube.tv";
    static final ComponentName YTM_MAIN = new ComponentName(YTM, "com.google.android.apps.youtube.tvmusic.activity.MainActivity");
    /** Music apps: their carousel is album covers (square tiles). */
    private static final Set<String> APPS = new HashSet<>(Arrays.asList(
            YTM,
            "com.spotify.tv.android", "com.spotify.music",
            "com.amazon.music.tv", "com.amazon.mp3",
            "deezer.android.tv", "deezer.android.app",
            "com.apple.android.music", "com.apple.android.music.tv",
            "com.aspiro.tidal", "com.tidal.android.tv",
            "com.soundcloud.android", "com.qobuz.music", "com.pandora.android.atv", "tunein.player"));
    private static final String CHARTS = "https://rss.applemarketingtools.com/api/v2/it/music/most-played/25/songs.json";
    private static final long CHARTS_EVERY = 6 * 3600_000L;
    /** The user's YouTube library (liked songs, playlists): read every ~30 min with the linked account. */
    private static final long LIBRARY_EVERY = 30 * 60_000L;
    static final String LIKED = "liked", PLAYLISTS = "playlists", TOP = "top";

    static boolean isMusic(String pkg) {
        return pkg != null && APPS.contains(pkg);
    }

    interface Listener { void onMusic(); }

    private static Music instance;

    static synchronized Music get(Context c) {
        if (instance == null) instance = new Music(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "music-io");
        t.setDaemon(true);
        return t;
    });
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Recs.Pick> charts = new ArrayList<>();
    private boolean chartsFetching;
    private final List<Recs.Pick> liked = new ArrayList<>(), playlists = new ArrayList<>();
    private boolean libraryFetching;
    private long libraryAt;
    /** Section shown per music app (key: LIKED / PLAYLISTS / TOP). */
    private final java.util.Map<String, String> section = new java.util.HashMap<>();

    private Music(Context c) {
        context = c;
        loadCharts();
        loadLibrary();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Refreshes what is stale (charts every 6 h, the library every 30 min); listeners hear when it lands. */
    void start() {
        refreshCharts();
        refreshLibrary();
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onMusic();
    }

    static Intent ytmIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(YTM_MAIN);
    }

    // MARK: - Charts (Apple Music, Italy)

    /**
     * The chips above a music app's carousel, as {key, label}: the user's own YouTube library first (YouTube Music
     * only, with the account linked), the public Italian charts last (or alone without the account).
     */
    List<String[]> sections(String app) {
        List<String[]> out = new ArrayList<>();
        if (YTM.equals(app)) {
            if (!liked.isEmpty()) out.add(new String[] {LIKED, Ui.tr("Músicas curtidas", "Liked songs")});
            if (!playlists.isEmpty()) out.add(new String[] {PLAYLISTS, Ui.tr("Suas playlists", "Your playlists")});
        }
        out.add(new String[] {TOP, Ui.tr("Top Itália", "Top Italy")});
        return out;
    }

    /** The chip shown for this app (0 = the first: the user's own songs when there are any). */
    int sectionIndex(String app) {
        List<String[]> all = sections(app);
        String key = section.get(app);
        for (int i = 0; i < all.size(); i++) if (all.get(i)[0].equals(key)) return i;
        return 0;
    }

    void selectSection(String app, int index) {
        List<String[]> all = sections(app);
        section.put(app, all.get(Math.max(0, Math.min(all.size() - 1, index)))[0]);
    }

    /** The carousel for a music app: the chosen chip's songs or playlists. */
    List<Recs.Pick> picks(String app, int max) {
        refreshCharts();
        refreshLibrary();
        List<Recs.Pick> out = new ArrayList<>();
        String key = sections(app).get(sectionIndex(app))[0];
        if (!TOP.equals(key)) {
            for (Recs.Pick c : LIKED.equals(key) ? liked : playlists) {
                if (out.size() >= max) break;
                out.add(c);
            }
            return out;
        }
        for (Recs.Pick c : charts) {
            if (out.size() >= max) break;
            Recs.Pick p = new Recs.Pick();
            p.kind = c.kind;
            p.id = c.id;
            p.title = c.title;
            p.meta = c.meta;
            p.backdrop = c.backdrop;
            p.pkg = app;
            p.url = "playsearch:" + c.url;
            p.detailed = true;
            out.add(p);
        }
        return out;
    }

    private SharedPreferences sp() {
        return context.getSharedPreferences("music", Context.MODE_PRIVATE);
    }

    private void loadCharts() {
        String json = sp().getString("charts", null);
        if (json != null) parseCharts(json);
    }

    private void parseCharts(String json) {
        List<Recs.Pick> list = new ArrayList<>();
        try {
            JSONArray results = new JSONObject(json).getJSONObject("feed").getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                JSONObject o = results.getJSONObject(i);
                Recs.Pick p = new Recs.Pick();
                p.kind = "song";
                p.title = o.optString("name");
                p.meta = o.optString("artistName");
                p.id = (p.meta + "|" + p.title).hashCode();
                String art = o.optString("artworkUrl100", "");
                if (art.isEmpty() || p.title.isEmpty()) continue;
                p.backdrop = art.replace("100x100bb", "600x600bb");
                p.url = p.meta + " " + p.title;
                list.add(p);
            }
        } catch (Exception e) {
            AppLog.w("music: charts " + e.getMessage());
            return;
        }
        synchronized (charts) {
            charts.clear();
            charts.addAll(list);
        }
    }

    private void refreshCharts() {
        long at = sp().getLong("chartsAt", 0);
        if (chartsFetching || (!charts.isEmpty() && System.currentTimeMillis() - at < CHARTS_EVERY)) return;
        chartsFetching = true;
        io.execute(() -> {
            try {
                String json = new String(fetch(CHARTS), StandardCharsets.UTF_8);
                main.post(() -> {
                    chartsFetching = false;
                    parseCharts(json);
                    sp().edit().putString("charts", json).putLong("chartsAt", System.currentTimeMillis()).apply();
                    notifyListeners();
                });
            } catch (Exception e) {
                AppLog.w("music: charts " + e.getMessage());
                main.postDelayed(() -> chartsFetching = false, 600_000);
            }
        });
    }

    // MARK: - The user's YouTube library (YouTube Data API v3, scope youtube.readonly, the account linked in Settings)

    private void loadLibrary() {
        parseLibrary(sp().getString("library", null));
    }

    private void parseLibrary(String json) {
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            liked.clear();
            playlists.clear();
            readPicks(o.optJSONArray("liked"), liked);
            readPicks(o.optJSONArray("playlists"), playlists);
        } catch (Exception e) {
            AppLog.w("music: library " + e.getMessage());
        }
    }

    private static void readPicks(JSONArray a, List<Recs.Pick> out) throws org.json.JSONException {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Recs.Pick p = new Recs.Pick();
            p.kind = o.getString("kind");
            p.title = o.getString("title");
            p.meta = o.optString("meta");
            p.backdrop = o.getString("art");
            p.url = o.getString("url");
            p.id = p.url.hashCode();
            p.pkg = YTM;
            p.detailed = true;
            out.add(p);
        }
    }

    /** Liked songs (the LL playlist, music videos only) and the user's playlists; no account: nothing (public chips only). */
    private void refreshLibrary() {
        if (!YouTubeAuth.linked(context) || libraryFetching) return;
        long now = System.currentTimeMillis();
        if (libraryAt == 0) libraryAt = sp().getLong("libraryAt", 0);
        if (now - libraryAt < LIBRARY_EVERY && now >= libraryAt) return;
        libraryFetching = true;
        io.execute(() -> {
            String json = null;
            try {
                String token = YouTubeAuth.accessToken(context);
                if (token != null) json = fetchLibrary(token).toString();
            } catch (Exception e) {
                AppLog.w("music: library " + e.getMessage());
            }
            String done = json;
            main.post(() -> {
                libraryFetching = false;
                libraryAt = System.currentTimeMillis();
                if (done == null) return;
                sp().edit().putString("library", done).putLong("libraryAt", libraryAt).apply();
                parseLibrary(done);
                AppLog.w("music: library " + liked.size() + " liked songs, " + playlists.size() + " playlists");
                notifyListeners();
            });
        });
    }

    private static final String API = "https://www.googleapis.com/youtube/v3/";

    private JSONObject fetchLibrary(String token) throws Exception {
        // Liked videos (LL), newest first; only the Music category (10) is kept: that's what YouTube Music likes are.
        JSONArray items = get(API + "playlistItems?part=snippet&maxResults=50&playlistId=LL", token).getJSONArray("items");
        List<String> ids = new ArrayList<>();
        java.util.Map<String, JSONObject> snippets = new java.util.HashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject sn = items.getJSONObject(i).getJSONObject("snippet");
            String id = sn.optJSONObject("resourceId") == null ? "" : sn.getJSONObject("resourceId").optString("videoId");
            if (id.isEmpty()) continue;
            ids.add(id);
            snippets.put(id, sn);
        }
        JSONArray liked = new JSONArray();
        if (!ids.isEmpty()) {
            JSONArray v = get(API + "videos?part=snippet&maxResults=50&id=" + String.join(",", ids), token).getJSONArray("items");
            Set<String> music = new HashSet<>();
            for (int i = 0; i < v.length(); i++) {
                JSONObject o = v.getJSONObject(i);
                if ("10".equals(o.getJSONObject("snippet").optString("categoryId"))) music.add(o.getString("id"));
            }
            for (String id : ids) {
                if (!music.contains(id)) continue;
                JSONObject sn = snippets.get(id);
                String art = thumb(sn.optJSONObject("thumbnails"));
                if (art == null) continue;
                liked.put(new JSONObject().put("kind", "ytsong").put("title", sn.optString("title"))
                        .put("meta", artist(sn.optString("videoOwnerChannelTitle"))).put("art", art)
                        .put("url", "ytmwatch:" + id + "|LL"));
            }
        }
        // The user's own playlists.
        JSONArray lists = new JSONArray();
        JSONArray pl = get(API + "playlists?part=snippet,contentDetails&mine=true&maxResults=50", token).getJSONArray("items");
        for (int i = 0; i < pl.length(); i++) {
            JSONObject o = pl.getJSONObject(i), sn = o.getJSONObject("snippet");
            int count = o.optJSONObject("contentDetails") == null ? 0 : o.getJSONObject("contentDetails").optInt("itemCount");
            String art = thumb(sn.optJSONObject("thumbnails"));
            if (art == null || count == 0) continue;
            lists.put(new JSONObject().put("kind", "ytlist").put("title", sn.optString("title"))
                    .put("meta", count + " " + Ui.tr("músicas", "songs")).put("art", art)
                    .put("url", "ytmlist:" + o.getString("id")));
        }
        return new JSONObject().put("liked", liked).put("playlists", lists);
    }

    /** "Artist - Topic" (YouTube's auto-generated music channels) → "Artist". */
    private static String artist(String channel) {
        return channel == null ? "" : channel.replaceAll("\\s+-\\s+Topic$", "");
    }

    /** 480×360 is enough for a 320 px tile (the album art sits square in the middle; the bars are cropped). */
    private static String thumb(JSONObject t) {
        if (t == null) return null;
        for (String k : new String[] {"high", "medium", "standard", "default"}) {
            JSONObject o = t.optJSONObject(k);
            if (o != null && !o.optString("url").isEmpty()) return o.optString("url");
        }
        return null;
    }

    private static JSONObject get(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code + " " + url.replaceAll("\\?.*", ""));
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * A chart song (artist + title, no YouTube id): one YouTube search (Music category; the linked account, or the API
     * key) for its video, then the same link as a liked song. Nothing found: YouTube Music opens on its home.
     */
    void playSearch(android.app.Activity a, String query) {
        io.execute(() -> {
            String id = null;
            try {
                String token = YouTubeAuth.linked(context) ? YouTubeAuth.accessToken(context) : null;
                String key = Config.YOUTUBE_KEY.trim();
                if (token != null || !key.isEmpty()) {
                    String url = API + "search?part=id&type=video&videoCategoryId=10&maxResults=1&q="
                            + java.net.URLEncoder.encode(query, "UTF-8") + (token == null ? "&key=" + key : "");
                    JSONArray items = get(url, token).getJSONArray("items");
                    if (items.length() > 0) id = items.getJSONObject(0).getJSONObject("id").optString("videoId", null);
                }
            } catch (Exception e) {
                AppLog.w("music: search " + e.getMessage());
            }
            Intent intent = id != null ? HomeApps.ytmLink(context, "ytmwatch:" + id) : ytmIntent();
            main.post(() -> {
                try {
                    a.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    AppLog.w("music: open " + e.getMessage());
                }
            });
        });
    }

    private static byte[] fetch(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
