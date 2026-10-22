package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * YouTube trending videos (YouTube Data API v3, chart=mostPopular) and live Twitch streams (Helix /streams with an app
 * access token from client_credentials): the "YouTube" and "Twitch" tabs, and one of each mixed into Explore. Official
 * APIs only; with no keys in config.properties both stay empty and their tabs are hidden. Images go through Recs.
 */
final class Streams {
    static final String YOUTUBE = "youtube", TWITCH = "twitch";
    private static final String[] YT_PKGS = {"com.google.android.youtube.tv", "com.google.android.youtube"};
    private static final String[] TW_PKGS = {"tv.twitch.android.app", "tv.twitch.android.viewer"};
    private static final long YT_EVERY = 3600_000L, YT_SUBS_EVERY = 30 * 60_000L, DAY = 24 * 3600_000L, TW_EVERY = 5 * 60_000L, RETRY = 10 * 60_000L;

    private static Streams instance;

    static synchronized Streams get(Context c) {
        if (instance == null) instance = new Streams(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile List<Recs.Pick> youtube = new ArrayList<>(), twitch = new ArrayList<>();
    private volatile long ytAt, twAt, ytFail, twFail;
    private volatile String ytLang = "", twLang = "";
    private volatile boolean busy;
    private String token;
    private long tokenUntil;

    private Streams(Context c) {
        context = c;
        cache = c.getSharedPreferences("streams", Context.MODE_PRIVATE);
        // The last lists, so the tabs are there at once after a restart (refreshed in the background when stale).
        youtube = load("youtube");
        twitch = load("twitch");
        ytAt = cache.getLong("youtubeAt", 0);
        twAt = cache.getLong("twitchAt", 0);
        ytLang = cache.getString("youtubeLang", "");
        twLang = cache.getString("twitchLang", "");
        if (!youtubeConfigured()) youtube = new ArrayList<>();
        if (!twitchConfigured()) twitch = new ArrayList<>();
    }

    private List<Recs.Pick> load(String name) {
        List<Recs.Pick> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(cache.getString(name, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Recs.Pick p = new Recs.Pick();
                p.kind = o.optString("kind");
                p.id = o.optInt("id");
                p.title = o.optString("title");
                p.backdrop = o.optString("backdrop");
                p.meta = o.optString("meta");
                p.url = o.optString("url");
                p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
                p.live = o.optBoolean("live");
                p.detailed = true;
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void store(String name, List<Recs.Pick> list, long at, String lang) {
        try {
            JSONArray a = new JSONArray();
            for (Recs.Pick p : list) {
                a.put(new JSONObject().put("kind", p.kind).put("id", p.id).put("title", p.title).put("backdrop", p.backdrop)
                        .put("meta", p.meta).put("url", p.url).put("pkg", p.pkg == null ? "" : p.pkg).put("live", p.live));
            }
            cache.edit().putString(name, a.toString()).putLong(name + "At", at).putString(name + "Lang", lang).apply();
        } catch (Exception ignored) {
        }
    }

    static boolean youtubeConfigured() { return !Config.YOUTUBE_KEY.trim().isEmpty() || YouTubeAuth.configured(); }

    private volatile long linkStartAt;
    private volatile String linkCode;

    /** While YouTube isn't linked: the code to type at google.com/device, as a pick (no image, no link). */
    private Recs.Pick linkPick() {
        if (!YouTubeAuth.configured() || YouTubeAuth.linked(context)) return null;
        JSONObject p = YouTubeAuth.pending(context);
        if (p == null) return null;
        Recs.Pick k = new Recs.Pick();
        k.kind = YOUTUBE;
        k.id = p.optString("user_code").hashCode();
        k.title = p.optString("user_code");
        k.meta = Ui.tr("Vá em google.com/device e digite o código ", "Go to google.com/device and enter the code ") + p.optString("user_code");
        k.detailed = true;
        return k;
    }

    static boolean isLinkPick(Recs.Pick p) { return p != null && YOUTUBE.equals(p.kind) && p.url == null; }

    static boolean twitchConfigured() {
        return !Config.TWITCH_CLIENT_ID.trim().isEmpty() && !Config.TWITCH_CLIENT_SECRET.trim().isEmpty();
    }

    /** A tab is shown only with a configured source that returned something. */
    boolean has(String kind) { return !list(kind).isEmpty() || (YOUTUBE.equals(kind) && linkPick() != null); }

    private List<Recs.Pick> list(String kind) { return YOUTUBE.equals(kind) ? youtube : TWITCH.equals(kind) ? twitch : new ArrayList<>(); }

    /** Three picks for a tab: YouTube rotates hourly through the trending list; Twitch shows the top three live now. */
    List<Recs.Pick> now(String kind) {
        List<Recs.Pick> all = list(kind), out = new ArrayList<>();
        if (YOUTUBE.equals(kind)) {
            Recs.Pick link = linkPick();
            if (link != null) out.add(link);
        }
        if (all.isEmpty()) return out;
        // Subscriptions: newest first; trending: rotates hourly.
        int start = YOUTUBE.equals(kind) && !YouTubeAuth.linked(context) ? (int) ((System.currentTimeMillis() / 3600_000L * 3) % all.size()) : 0;
        for (int i = 0; i < all.size() && out.size() < 3; i++) out.add(all.get((start + i) % all.size()));
        return out;
    }

    /** A tab's carousel: the link code (if any) first, then the whole list (YouTube trending rotated hourly). */
    List<Recs.Pick> deck(String kind, int max) {
        List<Recs.Pick> all = list(kind), out = new ArrayList<>();
        if (YOUTUBE.equals(kind)) {
            Recs.Pick link = linkPick();
            if (link != null) out.add(link);
        }
        if (all.isEmpty()) return out;
        int start = YOUTUBE.equals(kind) && !YouTubeAuth.linked(context) ? (int) ((System.currentTimeMillis() / 3600_000L * 3) % all.size()) : 0;
        for (int i = 0; i < all.size() && out.size() < max; i++) out.add(all.get((start + i) % all.size()));
        return out;
    }

    /** One pick (the first of now(kind)), for Explore; or null. */
    Recs.Pick one(String kind) {
        for (Recs.Pick p : now(kind)) if (!isLinkPick(p)) return p;
        return null;
    }

    /** Called every 15 s by the home screen: YouTube about hourly, Twitch every 5 minutes. */
    void refreshIfStale(Runnable done) {
        if (busy) return;
        long now = System.currentTimeMillis();
        String lang = Ui.language;
        boolean linked = YouTubeAuth.linked(context);
        String mode = (linked ? "subs3/" : "trend3/") + lang;   // bump to refetch after a filter change
        boolean auth = YouTubeAuth.configured() && !linked;
        boolean yt = youtubeConfigured() && (linked || !Config.YOUTUBE_KEY.trim().isEmpty())
                && (youtube.isEmpty() || now - ytAt > (linked ? YT_SUBS_EVERY : YT_EVERY) || now < ytAt || !mode.equals(ytLang))
                && now - ytFail > RETRY;
        boolean tw = twitchConfigured() && (twitch.isEmpty() || now - twAt > TW_EVERY || now < twAt || !lang.equals(twLang))
                && now - twFail > RETRY;
        if (!yt && !tw && !auth) return;
        busy = true;
        io.execute(() -> {
            boolean justLinked = false;
            try {
                if (auth) {
                    // Not linked: keep a code on screen (a new one when it expires) and poll until the user approves.
                    try {
                        if (YouTubeAuth.pending(context) == null) {
                            if (System.currentTimeMillis() - linkStartAt > 60_000L) {
                                linkStartAt = System.currentTimeMillis();
                                YouTubeAuth.start(context);
                            }
                        } else if (YouTubeAuth.poll(context)) {
                            justLinked = true;
                            ytAt = 0;
                            ytLang = "";
                        }
                    } catch (Exception e) {
                        AppLog.w("youtube link: " + e.getMessage());
                    }
                }
                if (yt) {
                    try {
                        youtube = linked ? fetchSubscriptions() : fetchYouTube();
                        ytAt = System.currentTimeMillis();
                        ytLang = mode;
                        ytFail = 0;
                        store("youtube", youtube, ytAt, mode);
                        AppLog.w("youtube: " + youtube.size() + " trending videos");
                    } catch (Exception e) {
                        ytFail = System.currentTimeMillis();
                        AppLog.w("youtube: " + e.getMessage());
                    }
                }
                if (tw) {
                    try {
                        twitch = fetchTwitch();
                        twAt = System.currentTimeMillis();
                        twLang = lang;
                        twFail = 0;
                        store("twitch", twitch, twAt, lang);
                        AppLog.w("twitch: " + twitch.size() + " live streams");
                    } catch (Exception e) {
                        twFail = System.currentTimeMillis();
                        if (twFail - twAt > 30 * 60_000L) twitch = new ArrayList<>();   // stale live list: show none, not old streams
                        AppLog.w("twitch: " + e.getMessage());
                    }
                }
            } finally {
                busy = false;
                if (done != null) done.run();
                if (justLinked) refreshIfStale(done);   // the subscriptions right away
            }
        });
    }

    // MARK: - YouTube Data API v3

    private List<Recs.Pick> fetchYouTube() throws Exception {
        String region = Config.TMDB_REGION.trim().isEmpty() ? "IT" : Config.TMDB_REGION.trim().toUpperCase(java.util.Locale.ROOT);
        String hl = Ui.en ? "en" : Ui.language;
        String url = "https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics,contentDetails,player&maxWidth=1280&chart=mostPopular&maxResults=50&videoCategoryId=28"
                + "&regionCode=" + region + "&hl=" + hl + "&key=" + enc(Config.YOUTUBE_KEY.trim());
        JSONArray items = new JSONObject(http("GET", url, null, null)).optJSONArray("items");
        String pkg = installed(YT_PKGS);
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject v = items.getJSONObject(i), s = v.optJSONObject("snippet");
            if (s == null || isShort(v) || !"none".equals(s.optString("liveBroadcastContent", "none"))) continue;
            JSONObject loc = s.optJSONObject("localized");
            Recs.Pick p = new Recs.Pick();
            p.kind = YOUTUBE;
            String id = v.optString("id");
            p.id = id.hashCode();
            p.title = loc != null && !loc.optString("title").isEmpty() ? loc.optString("title") : s.optString("title");
            p.backdrop = thumb(s.optJSONObject("thumbnails"));
            if (p.backdrop == null) continue;
            String views = "";
            JSONObject st = v.optJSONObject("statistics");
            if (st != null && st.has("viewCount")) views = " · " + count(st.optLong("viewCount")) + Ui.tr(" visualizações", " views");
            p.meta = s.optString("channelTitle") + views;
            p.url = "https://www.youtube.com/watch?v=" + id;
            p.pkg = pkg;
            p.detailed = true;
            out.add(p);
        }
        return out;
    }

    // MARK: - YouTube subscriptions (OAuth, youtube.readonly)

    private static final int CHANNELS_PER_REFRESH = 60;

    /**
     * Latest uploads from the user's subscriptions, newest first, without Shorts (< 70 s). Quota-conscious: the
     * subscriptions and each channel's uploads playlist are cached for a day; each refresh reads the uploads of the 40
     * channels checked longest ago (1 unit each), and their videos' details in batches of 50.
     */
    private List<Recs.Pick> fetchSubscriptions() throws Exception {
        String token = YouTubeAuth.accessToken(context);
        if (token == null) return fetchYouTube();
        JSONObject state;
        try { state = new JSONObject(cache.getString("subsState", "{}")); } catch (Exception e) { state = new JSONObject(); }
        long now = System.currentTimeMillis();
        JSONObject uploads = state.optJSONObject("uploads");     // channel id → uploads playlist id
        JSONObject checked = state.optJSONObject("checked");     // channel id → last read (ms)
        JSONObject videos = state.optJSONObject("videos");       // video id → {published, channel}
        if (checked == null) checked = new JSONObject();
        if (videos == null) videos = new JSONObject();
        if (uploads == null || now - state.optLong("uploadsAt") > DAY) {
            List<String> channels = new ArrayList<>();
            String page = "";
            for (int i = 0; i < 20; i++) {   // up to 1000 subscriptions
                JSONObject r = new JSONObject(get("https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=50"
                        + (page.isEmpty() ? "" : "&pageToken=" + enc(page)), token));
                JSONArray items = r.optJSONArray("items");
                for (int j = 0; items != null && j < items.length(); j++) {
                    JSONObject rid = items.getJSONObject(j).getJSONObject("snippet").optJSONObject("resourceId");
                    if (rid != null && !rid.optString("channelId").isEmpty()) channels.add(rid.optString("channelId"));
                }
                page = r.optString("nextPageToken");
                if (page.isEmpty()) break;
            }
            uploads = new JSONObject();
            for (int i = 0; i < channels.size(); i += 50) {
                String ids = android.text.TextUtils.join(",", channels.subList(i, Math.min(channels.size(), i + 50)));
                JSONArray items = new JSONObject(get("https://www.googleapis.com/youtube/v3/channels?part=contentDetails&maxResults=50&id="
                        + enc(ids), token)).optJSONArray("items");
                for (int j = 0; items != null && j < items.length(); j++) {
                    JSONObject ch = items.getJSONObject(j);
                    JSONObject rel = ch.getJSONObject("contentDetails").optJSONObject("relatedPlaylists");
                    if (rel != null && !rel.optString("uploads").isEmpty()) uploads.put(ch.getString("id"), rel.getString("uploads"));
                }
            }
            state.put("uploads", uploads).put("uploadsAt", now);
            AppLog.w("youtube: " + uploads.length() + " subscribed channels");
        }
        // The channels checked longest ago (never-checked first).
        List<String> order = new ArrayList<>();
        for (java.util.Iterator<String> it = uploads.keys(); it.hasNext(); ) order.add(it.next());
        final JSONObject seen = checked;
        java.util.Collections.sort(order, (a, b) -> Long.compare(seen.optLong(a), seen.optLong(b)));
        List<String> fresh = new ArrayList<>();
        for (int i = 0; i < order.size() && i < CHANNELS_PER_REFRESH; i++) {
            String ch = order.get(i);
            try {
                JSONArray items = new JSONObject(get("https://www.googleapis.com/youtube/v3/playlistItems?part=contentDetails&maxResults=3&playlistId="
                        + enc(uploads.getString(ch)), token)).optJSONArray("items");
                for (int j = 0; items != null && j < items.length(); j++) {
                    JSONObject cd = items.getJSONObject(j).getJSONObject("contentDetails");
                    String id = cd.optString("videoId");
                    if (!id.isEmpty() && !videos.has(id)) fresh.add(id);
                    if (!id.isEmpty() && !videos.has(id)) videos.put(id, new JSONObject().put("published", cd.optString("videoPublishedAt")).put("channel", ch));
                }
            } catch (IOException e) {
                AppLog.w("youtube uploads " + ch + ": " + e.getMessage());   // a deleted channel or playlist: skip it
            }
            checked.put(ch, now);
        }
        // Details of new videos (duration for the Shorts, title, thumbnails); the 60 newest kept.
        JSONObject details;
        try { details = new JSONObject(cache.getString("subsDetails", "{}")); } catch (Exception e) { details = new JSONObject(); }
        if (!cache.getBoolean("shortsFilter3", false)) {   // details saved before the Shorts filter: look them up again
            details = new JSONObject();
            videos = new JSONObject();
            checked = new JSONObject();
            cache.edit().putBoolean("shortsFilter3", true).apply();
        }
        for (int i = 0; i < fresh.size(); i += 50) {
            String ids = android.text.TextUtils.join(",", fresh.subList(i, Math.min(fresh.size(), i + 50)));
            JSONArray items = new JSONObject(get("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails,statistics,player&maxWidth=1280&hl="
                    + (Ui.en ? "en" : Ui.language) + "&id=" + enc(ids), token)).optJSONArray("items");
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject v = items.getJSONObject(j), s = v.getJSONObject("snippet");
                if (isShort(v)) continue;                                        // Shorts (and vertical videos)
                if (!"none".equals(s.optString("liveBroadcastContent", "none"))) continue;   // live / upcoming premieres
                JSONObject loc = s.optJSONObject("localized");
                JSONObject st = v.optJSONObject("statistics");
                details.put(v.getString("id"), new JSONObject()
                        .put("title", loc != null && !loc.optString("title").isEmpty() ? loc.optString("title") : s.optString("title"))
                        .put("channel", s.optString("channelTitle"))
                        .put("thumb", thumb(s.optJSONObject("thumbnails")))
                        .put("views", st == null ? -1 : st.optLong("viewCount", -1))
                        .put("published", s.optString("publishedAt")));
            }
        }
        List<String> ids = new ArrayList<>();
        for (java.util.Iterator<String> it = details.keys(); it.hasNext(); ) ids.add(it.next());
        final JSONObject d = details;
        java.util.Collections.sort(ids, (a, b) -> d.optJSONObject(b).optString("published").compareTo(d.optJSONObject(a).optString("published")));
        JSONObject keep = new JSONObject(), keepVideos = new JSONObject();
        String pkg = installed(YT_PKGS);
        List<Recs.Pick> out = new ArrayList<>();
        for (String id : ids) {
            if (out.size() >= 60) break;
            JSONObject o = details.getJSONObject(id);
            keep.put(id, o);
            if (o.optString("thumb").isEmpty() || "null".equals(o.optString("thumb"))) continue;
            Recs.Pick p = new Recs.Pick();
            p.kind = YOUTUBE;
            p.id = id.hashCode();
            p.title = o.optString("title");
            p.backdrop = o.optString("thumb");
            long views = o.optLong("views", -1);
            p.meta = o.optString("channel") + " · " + ago(o.optString("published"))
                    + (views >= 0 ? " · " + count(views) + Ui.tr(" visualizações", " views") : "");
            p.url = "https://www.youtube.com/watch?v=" + id;
            p.pkg = pkg;
            p.detailed = true;
            out.add(p);
        }
        // Forget videos older than the kept ones (so the state stays small) but remember what was already looked up.
        for (java.util.Iterator<String> it = videos.keys(); it.hasNext(); ) {
            String id = it.next();
            if (keep.has(id) || fresh.contains(id)) keepVideos.put(id, videos.get(id));
        }
        state.put("checked", checked).put("videos", keepVideos);
        cache.edit().putString("subsState", state.toString()).putString("subsDetails", keep.toString()).apply();
        if (out.isEmpty()) return fetchYouTube();
        return out;
    }

    private JSONObject verdicts;

    /**
     * A Short or a vertical video (official data only): "#shorts" in the title or description, or up to 3 minutes long
     * and taller than wide in the player embed (videos?part=player&maxWidth returns embedWidth/embedHeight), or any
     * vertical embed. The verdict is cached per video id.
     */
    private synchronized boolean isShort(JSONObject v) {
        if (verdicts == null) {
            try { verdicts = new JSONObject(cache.getString("shortVerdicts", "{}")); } catch (Exception e) { verdicts = new JSONObject(); }
        }
        String id = v.optString("id");
        if (verdicts.has(id)) return verdicts.optBoolean(id);
        JSONObject s = v.optJSONObject("snippet");
        String text = s == null ? "" : (s.optString("title") + " " + s.optString("description")).toLowerCase(java.util.Locale.ROOT);
        long sec = seconds(v.optJSONObject("contentDetails") == null ? "" : v.optJSONObject("contentDetails").optString("duration"));
        JSONObject player = v.optJSONObject("player");
        long w = player == null ? 0 : player.optLong("embedWidth"), h = player == null ? 0 : player.optLong("embedHeight");
        boolean vertical = w > 0 && h > w;
        boolean verdict = text.contains("#shorts") || text.contains("#short ") || vertical || (sec > 0 && sec <= 60);
        try {
            if (verdicts.length() > 3000) verdicts = new JSONObject();
            verdicts.put(id, verdict);
            cache.edit().putString("shortVerdicts", verdicts.toString()).apply();
        } catch (Exception ignored) {
        }
        return verdict;
    }

    /** "PT1H2M3S" → 3723. */
    private static long seconds(String iso) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(iso == null ? "" : iso);
        if (!m.matches()) return -1;
        long s = 0;
        int[] mult = {86400, 3600, 60, 1};
        for (int i = 0; i < 4; i++) if (m.group(i + 1) != null) s += Long.parseLong(m.group(i + 1)) * mult[i];
        return s;
    }

    /** "3 h fa" from an RFC 3339 time. */
    private static String ago(String rfc) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long minutes = Math.max(1, (System.currentTimeMillis() - f.parse(rfc.substring(0, 19)).getTime()) / 60_000L);
            if (minutes < 60) return Ui.tr(minutes + " min atrás", minutes + " min ago");
            if (minutes < 48 * 60) return Ui.tr((minutes / 60) + " h atrás", (minutes / 60) + " h ago");
            return Ui.tr((minutes / 1440) + " dias atrás", (minutes / 1440) + " days ago");
        } catch (Exception e) {
            return "";
        }
    }

    private static String get(String url, String token) throws IOException {
        return http("GET", url, null, null, token);
    }

    private static String thumb(JSONObject t) {
        if (t == null) return null;
        for (String k : new String[] {"maxres", "standard", "high", "medium", "default"}) {
            JSONObject o = t.optJSONObject(k);
            if (o != null && !o.optString("url").isEmpty()) return o.optString("url");
        }
        return null;
    }

    // MARK: - Twitch Helix

    private synchronized String twitchToken() throws Exception {
        if (token != null && System.currentTimeMillis() < tokenUntil) return token;
        String body = "client_id=" + enc(Config.TWITCH_CLIENT_ID.trim()) + "&client_secret=" + enc(Config.TWITCH_CLIENT_SECRET.trim())
                + "&grant_type=client_credentials";
        JSONObject o = new JSONObject(http("POST", "https://id.twitch.tv/oauth2/token", body, null));
        token = o.getString("access_token");
        tokenUntil = System.currentTimeMillis() + Math.max(60, o.optLong("expires_in", 3600) - 300) * 1000L;
        return token;
    }

    private List<Recs.Pick> fetchTwitch() throws Exception {
        String lang = Ui.en ? "en" : Ui.language;
        List<Recs.Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        addStreams(out, seen, "https://api.twitch.tv/helix/streams?first=20&language=" + enc(lang));   // most watched first
        if (out.size() < 3) addStreams(out, seen, "https://api.twitch.tv/helix/streams?first=20");   // overall top
        return out;
    }

    private void addStreams(List<Recs.Pick> out, java.util.Set<String> seen, String url) throws Exception {
        String auth;
        try {
            auth = twitchToken();
        } catch (Exception e) {
            throw new IOException("Twitch token: " + e.getMessage());
        }
        String json;
        try {
            json = http("GET", url, null, auth);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("401")) {   // expired token: once more with a new one
                synchronized (this) { token = null; }
                json = http("GET", url, null, twitchToken());
            } else {
                throw e;
            }
        }
        JSONArray data = new JSONObject(json).optJSONArray("data");
        String pkg = installed(TW_PKGS);
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject s = data.getJSONObject(i);
            String login = s.optString("user_login");
            if (login.isEmpty() || !seen.add(login)) continue;
            Recs.Pick p = new Recs.Pick();
            p.kind = TWITCH;
            p.id = login.hashCode();
            String name = s.optString("user_name");
            p.title = name.isEmpty() ? login : name;   // the streamer, as on Twitch's own home
            // 640×360 is enough for a live frame on the cards (smaller, so it arrives fast); a new URL every 5 minutes, so the live thumbnail is fresh (Twitch caches
            // the same address).
            p.backdrop = s.optString("thumbnail_url").replace("{width}", "640").replace("{height}", "360")
                    + "?t=" + (System.currentTimeMillis() / TW_EVERY);
            String game = s.optString("game_name");
            p.meta = (game.isEmpty() ? "" : game + " · ") + count(s.optLong("viewer_count")) + Ui.tr(" espectadores", " viewers") + " · LIVE";
            // The TV app (13.x, "Starshot") handles twitch://stream/<login> but not https links (those go to a browser
            // stub on Google TV), so the app scheme whenever a Twitch app is installed.
            p.url = pkg != null ? "twitch://stream/" + login : "https://www.twitch.tv/" + login;
            p.pkg = pkg;
            p.live = true;
            p.detailed = true;
            out.add(p);
        }
    }

    // MARK: - Helpers

    private String installed(String[] pkgs) {
        for (String p : pkgs) if (HomeApps.launchIntent(context.getPackageManager(), p) != null) return p;
        return null;
    }

    private static String count(long n) {
        return NumberFormat.getIntegerInstance(Ui.locale()).format(n);
    }

    private static String enc(String s) throws IOException { return URLEncoder.encode(s, "UTF-8"); }

    private static String http(String method, String url, String form, String bearer) throws IOException {
        return http(method, url, form, bearer, null);
    }

    /** `google`: a Google OAuth access token (no Twitch Client-Id). */
    private static String http(String method, String url, String form, String bearer, String google) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (google != null) c.setRequestProperty("Authorization", "Bearer " + google);
        if (bearer != null) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
            c.setRequestProperty("Client-Id", Config.TWITCH_CLIENT_ID.trim());
        }
        try {
            if (form != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream o = c.getOutputStream()) { o.write(form.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
