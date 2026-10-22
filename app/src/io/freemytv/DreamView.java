package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver, in the same style as the home screen:
 *  - the recommendations' photos in full screen, one every 30 s, with a slow zoom and pan (the "Ken Burns" effect of
 *    the Apple TV photos) and a crossfade between them; each photo is loaded 10% larger than the screen, so the zoom
 *    never upscales it;
 *  - top right, like the iPhone lock screen: the date with the weather, the big Liquid Glass clock (the photo shows
 *    through the digits, bent at the edges; Shaders.GLASS_TEXT) and the compact prices;
 *  - bottom left, the photo's caption: the time-of-day label ("TONIGHT"), the title, and genre · runtime · service.
 * Dark scrims only where there is text, stronger where the photo is bright. Any key exits (as on Apple TV).
 * Without photos (no TMDB key, or before the first fetch): the big clock over the night aurora.
 */
final class DreamView extends View implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    private static final long SHOW_MS = 30_000, FADE_MS = 3_000, FIRST_MS = 1_600;
    private static final float ZOOM = 0.1f;
    /** Photos 10% larger than the screen (the zoom goes up to 1.1 without upscaling). */
    private static final int PW = 2112, PH = 1188;
    private static final float MARGIN = 80, RIGHT = 1840, TITLE_W = 900, META_BASE = 1008;
    private static final float DATE_BASE = 92, CLOCK_TOP = 120, CLOCK_SIZE = 200, QUOTES_GAP = 52;
    private static final double DRIFT_PERIOD_S = 600;
    /** Where the corner text sits, as fractions of the photo (the photo spans the screen; the slow pan moves it <5%). */
    // The two text blocks, as fractions of the screen (#92): A = date, weather and clock (top right), B = label, title
    // and details (bottom left). Each picks its own ink from the new photo as it is shown there; within a block every
    // element shares it.
    private static final float[] REGION_A = {0.66f, 0.035f, 0.965f, 0.27f};
    private static final float[] REGION_B = {0.035f, 0.69f, 0.52f, 0.955f};
    private static final int DARK_INK = 0x1C1C1E;
    private static final float DARK_INK_LUM = 0.0103f;   // its relative luminance (WCAG)
    /** Minimum contrast ratio for a block; below it, a soft scrim (dark for white ink, light for dark ink) is added. */
    private static final float MIN_CONTRAST = 3.5f;
    /** The almost invisible blur behind each block (#94): downscale factor and how much of it shows (0 = off). */
    static float BLUR_SCALE = 4, BLUR_STRENGTH = 0.6f;
    private int inkRecorded = -1;   // the corner ink the chrome was recorded with

    /** One photo on screen: the image, its motion (zooming in or out, toward a corner) and its caption. */
    private static final class Slide {
        final Recs.Pick pick;
        final Bitmap image, frost;
        final BitmapShader shader;    // the frosted version, for the clock's glass
        final float lightCaption, lightClock;
        boolean darkInk;              // the corner's text (clock, date, weather) in near-black: the photo is light there
        boolean captionDark;          // the caption's text (label, title, details) in near-black
        final boolean zoomIn;
        final float panX, panY;
        final Recording caption = Recording.create("caption");
        long startedAt;

        Slide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock, int n) {
            this.pick = pick;
            this.image = image;
            this.frost = frost;
            shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Frost.filter(shader, BitmapShader.FILTER_MODE_LINEAR);
            this.lightCaption = lightCaption;
            this.lightClock = lightClock;
            zoomIn = n % 2 == 0;
            // Toward one of the corners, alternating, without repeating the previous one.
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }
    }

    /** The glass clock's digits, rendered once a minute in the background. */
    private static final class Glass {
        final String text;
        final Bitmap slope, cover, shadow;
        final int x, y;
        final float inkTop, inkHeight;

        Glass(String text, Bitmap slope, Bitmap cover, Bitmap shadow, int x, int y, float inkTop, float inkHeight) {
            this.text = text;
            this.slope = slope;
            this.cover = cover;
            this.shadow = shadow;
            this.x = x;
            this.y = y;
            this.inkTop = inkTop;
            this.inkHeight = inkHeight;
        }
    }

    private final Scene scene = new Scene(false);
    private final HomeData data;
    private final Recs recs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Recording content = Recording.create("clock");     // without photos: the clock over the aurora
    private final Recording chrome = Recording.create("corner");     // date, weather, prices and the clock's shadow
    private final float timeOffset = (float) (Math.random() * 300);
    private final Matrix matrix = new Matrix();
    private final float[] placeA = new float[3], placeB = new float[3];
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint black = new Paint();
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glassPaint = new Paint();
    private final Set<String> failed = new HashSet<>();     // Recs.key: a movie and a series can have the same ID
    private RuntimeShader glassShader;
    private boolean running, dirty = true, chromeDirty = true;
    private boolean fallback;       // the aurora with the clock (no photos)
    private boolean decoding;
    private int generation, slides;
    private Slide current, incoming, prepared;
    private Glass glass;
    private String glassFor = "";
    private long startedAt, firstAt, lastFrame, lastSky;
    private int w, h;
    private float k = 1;
    private long minute = -1;
    private String clock = "", date = "";
    private Paint datePaint, clockPaint, weatherPaint, weatherDim, quotePaint, stalePaint;
    private Paint cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, metaPaint;
    private TextPaint titlePaint;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            recs.prefetchAll();
            dirty = true;
            chromeDirty = true;
            prepareNext();
            handler.postDelayed(this, 60_000);
        }
    };

    DreamView(Context c) {
        super(c);
        data = HomeData.get(c);
        recs = Recs.get(c);
        Shaders.dream(scene.palette);
        scene.setMotion(0.4f, 0.32f);
        scene.applyPalette();
        content.setUseCompositingLayer(true, null);
        // The clock is drawn solid (same font), in white or near-black after the photo behind it: the Liquid Glass
        // version (Shaders.GLASS_TEXT, makeGlass) looked smeared and got cut at its bitmap's edges, so it stays off.
        glassShader = null;
        black.setColor(0xFF000000);
        shadowPaint.setColor(0xFF000000);
    }

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        startedAt = clock();
        lastFrame = 0;
        failed.clear();
        fallback = recs.showcase().isEmpty();
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        recs.prefetchAll();
        prepareNext();
        handler.postDelayed(tick, 60_000);
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        decoding = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
        // The photos stay in GPU memory until the garbage collector runs: release them now (after the last frame).
        final java.util.Set<Bitmap> gone = new java.util.HashSet<>();
        for (Slide s : new Slide[] {current, incoming, prepared}) {
            if (s == null) continue;
            gone.add(s.image);
            gone.add(s.frost);
        }
        if (!gone.isEmpty()) handler.postDelayed(() -> { for (Bitmap b : gone) b.recycle(); }, 1000);
        current = incoming = prepared = null;
        glass = null;
        slides = 0;
        glassFor = "";
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
    }

    @Override public void onData() {
        dirty = true;
        chromeDirty = true;
        invalidate();
    }

    @Override public void onRecs() {
        if (running) prepareNext();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        scene.setSize(w, h);
        content.setPosition(0, 0, w, h);
        chrome.setPosition(0, 0, w, h);
        // Without photos (the clock over the aurora).
        datePaint = Ui.text(Ui.semibold, 36 * k, 0xD9FFFFFF);
        datePaint.setTextAlign(Paint.Align.CENTER);
        clockPaint = Ui.numbers(Ui.thin, 280 * k, 0xF2FFFFFF);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        clockPaint.setLetterSpacing(-0.01f);
        weatherPaint = Ui.numbers(Ui.medium, 30 * k, 0xC7FFFFFF);
        weatherDim = Ui.numbers(Ui.regular, 28 * k, 0x80FFFFFF);
        quotePaint = Ui.numbers(Ui.medium, 24 * k, 0x99FFFFFF);
        stalePaint = Ui.text(Ui.regular, 20 * k, 0x59FFFFFF);
        stalePaint.setTextAlign(Paint.Align.CENTER);
        // With photos: the corner (date, clock, prices) and the caption.
        cornerDate = Ui.numbers(Ui.semibold, 30 * k, 0xEBFFFFFF);
        cornerQuote = Ui.numbers(Ui.regular, 23 * k, 0xC7FFFFFF);
        cornerStale = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cornerClock = Ui.numbers(Ui.displaySemibold, CLOCK_SIZE * k, 0xF2FFFFFF);
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xB8FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xC7FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        for (Paint p : new Paint[] {cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, titlePaint, metaPaint}) {
            p.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        }
        glassFor = "";
        dirty = true;
        chromeDirty = true;
        for (Slide s : new Slide[] {current, incoming, prepared}) if (s != null) recordCaption(s);
    }

    // MARK: - Photos

    /** Loads the next photo in the background (one at a time); once ready, it comes in at the right moment. */
    private void prepareNext() {
        if (!running || decoding || prepared != null) return;
        List<Recs.Pick> list = recs.showcase();
        if (list.isEmpty()) return;
        Slide last = incoming != null ? incoming : current;
        int start = 0;
        if (last != null) {
            for (int i = 0; i < list.size(); i++) {
                if (Recs.key(list.get(i)).equals(Recs.key(last.pick))) {
                    start = i + 1;
                    break;
                }
            }
        }
        Recs.Pick pick = null;
        for (int i = 0; i < list.size() && pick == null; i++) {
            Recs.Pick p = list.get((start + i) % list.size());
            if (!failed.contains(Recs.key(p))) pick = p;
        }
        if (pick == null) {
            if (current == null) fallback = true;
            return;
        }
        if (last != null && Recs.key(pick).equals(Recs.key(last.pick))) {
            // Only one photo: the same one again, with a different motion.
            prepared = newSlide(pick, last.image, last.frost, last.lightCaption, last.lightClock, last.darkInk);
            prepared.captionDark = last.captionDark;   // the same photo: same inks (and its text treatment is baked in)
            recordCaption(prepared);
            return;
        }
        File file = recs.readyImage(pick);
        if (file == null) {
            failed.add(Recs.key(pick));   // the image is gone from disk: try the next one
            prepareNext();
            return;
        }
        decoding = true;
        final int gen = generation;
        final Recs.Pick chosen = pick;
        worker.execute(() -> {
            Bitmap[] images = null;
            float[] light = new float[2];
            boolean[] dark = new boolean[2];
            try {
                images = Backdrop.photo(file, PW, PH, light);
                images[0] = Backdrop.gpu565(prepareText(images[0], light, dark, chosen.title));
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver: photo for “" + chosen.title + "”: " + e.getMessage());
            }
            final Bitmap[] ready = images;
            handler.post(() -> {
                if (gen != generation) {
                    // The screensaver closed while the photo was loading: free the memory right away.
                    if (ready != null) for (Bitmap b : ready) b.recycle();
                    return;
                }
                decoding = false;
                if (ready == null) {
                    failed.add(Recs.key(chosen));
                    prepareNext();
                    return;
                }
                prepared = newSlide(chosen, ready[0], ready[1], light[0], light[1], dark[0]);
                prepared.captionDark = dark[1];
                recordCaption(prepared);
                invalidate();
            });
        });
    }

    private Slide newSlide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock, boolean darkInk) {
        Slide s = new Slide(pick, image, frost, lightCaption, lightClock, slides++);
        s.darkInk = darkInk;
        if (w > 0) recordCaption(s);
        return s;
    }

    /** The prepared photo goes on screen when its turn comes (the first one right away; the others every 30 s). */
    private void advance(long up) {
        if (current == null) {
            if (prepared == null) return;
            current = prepared;
            prepared = null;
            current.startedAt = up;
            firstAt = up;
            bindPhotos();
            prepareNext();
            return;
        }
        if (incoming == null && prepared != null && up >= current.startedAt + SHOW_MS) {
            incoming = prepared;
            prepared = null;
            incoming.startedAt = up;
            bindPhotos();
            return;
        }
        if (incoming != null && up >= incoming.startedAt + FADE_MS) {
            Slide old = current;
            current = incoming;
            incoming = null;
            bindPhotos();
            if (old.image != current.image) {
                // Once the last frame that uses them has been drawn.
                Bitmap gone = old.image, goneFrost = old.frost;
                handler.postDelayed(() -> {
                    gone.recycle();
                    goneFrost.recycle();
                }, 1000);
            }
            prepareNext();
        }
    }

    /** The photos the clock's glass shows (the current one and, during a crossfade, the incoming one). */
    private void bindPhotos() {
        if (glassShader == null || current == null) return;
        glassShader.setInputShader("imageA", current.shader);
        glassShader.setInputShader("imageB", incoming != null ? incoming.shader : current.shader);
    }

    /** Photo position and scale now: zoom from 1 to 1.1 (or back) and a pan toward a corner, never showing an edge. */
    private void place(Slide s, long up, float[] out) {
        float u = Ui.clamp((up - s.startedAt) / (float) (SHOW_MS + FADE_MS));
        float f = s.zoomIn ? u : 1 - u;
        float z = 1 + ZOOM * f;
        float rx = w * (z - 1) / 2, ry = h * (z - 1) / 2;
        out[0] = rx * (s.panX * f - 1);
        out[1] = ry * (s.panY * f - 1);
        out[2] = (float) w / s.image.getWidth() * z;
    }

    private void drawPhoto(Canvas c, Slide s, float[] place, float alpha) {
        if (alpha <= 0.004f) return;
        matrix.setScale(place[2], place[2]);
        matrix.postTranslate(place[0], place[1]);
        photoPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(s.image, matrix, photoPaint);
    }

    // MARK: - Frames: 20 per second, locked to every 3rd display frame (60 Hz)
    // Motion is slow (under half a pixel per frame). Measured on the TV: ~4 ms of GPU per frame of ours, but drawing
    // every frame, it only reaches ~50 fps (the system also composites the UI on the GPU); at 20 there's room to spare.

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (frameTimeNanos - lastFrame >= 45_000_000L) {
            lastFrame = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** The clock that drives the animations. */
    private long clock() {
        long up = SystemClock.uptimeMillis();
        return up;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long up = clock();
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            clock = Ui.clock(now);
            date = Ui.date(now);
            dirty = true;
            chromeDirty = true;
        }
        if (running) advance(up);
        if (!glassFor.equals(clock)) buildGlass(clock);

        float first = current == null ? 0 : fadeIn(up - firstAt, FIRST_MS);
        if (current == null || first < 1) {
            if (fallback) drawFallback(canvas, up, current == null ? 1 : 1 - first);
            else canvas.drawRect(0, 0, w, h, black);
        }
        if (current == null) return;
        if (fallback && first >= 1) fallback = false;

        place(current, up, placeA);
        float e = incoming == null ? 0 : fadeIn(up - incoming.startedAt, FADE_MS);
        drawPhoto(canvas, current, placeA, first);
        if (incoming != null) {
            place(incoming, up, placeB);
            drawPhoto(canvas, incoming, placeB, e);
        }

        int ink = blendRgb(current.darkInk ? DARK_INK : 0xFFFFFF,
                incoming == null ? (current.darkInk ? DARK_INK : 0xFFFFFF) : (incoming.darkInk ? DARK_INK : 0xFFFFFF), e);
        if (ink != inkRecorded) chromeDirty = true;   // crossfading to a photo of the other kind: the text follows
        if (chromeDirty) recordChrome(ink);
        chrome.setAlpha(first);
        chrome.draw(canvas);
        drawGlass(canvas, first, e);
        drawCaptions(canvas, up);
    }

    private static float fadeIn(long elapsed, long duration) {
        float t = Ui.clamp(elapsed / (float) duration);
        return t * t * (3 - 2 * t);
    }

    /**
     * Black scrims where there is text, stronger where the photo is bright, painted once onto the photo itself (in the
     * background): each frame draws only the photo (the TV's GPU feels every full-screen layer). With the slow zoom,
     * they move a little along with the photo, which makes no difference to the text.
     */
    private static Bitmap withScrims(Bitmap photo, float lightCaption, float lightClock, boolean darkInk, boolean captionDark) {
        int w = photo.getWidth(), h = photo.getHeight();
        Canvas c = new Canvas(photo);
        Paint p = new Paint(Paint.DITHER_FLAG);
        // The dark scrims are only for white text: under near-black text they would fight it.
        float caption = captionDark ? 0 : scrimStrength(lightCaption, 0.5f), corner = darkInk ? 0 : scrimStrength(lightClock, 0.42f);
        p.setShader(new LinearGradient(0, h, 0, h * 0.42f,
                new int[] {black(0.55f * caption), black(0.33f * caption), black(0.11f * caption), 0},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.42f, w, h, p);
        RadialGradient around = new RadialGradient(0, h, w * 0.7f,
                new int[] {black(caption), black(0.55f * caption), black(0.15f * caption), 0},
                new float[] {0, 0.4f, 0.72f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, 0.62f, 0, h);
        around.setLocalMatrix(m);
        p.setShader(around);
        c.drawRect(0, h - w * 0.7f * 0.62f, w * 0.7f, h, p);
        float top = darkInk ? 0 : 0.3f;   // the top scrim is only for white text
        p.setShader(new LinearGradient(0, 0, 0, h * 0.3f, new int[] {black(top), black(top / 4), 0},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.3f, p);
        float cx = w * 0.86f, cy = h * 0.15f;
        RadialGradient clockSide = new RadialGradient(cx, cy, w * 0.36f,
                new int[] {black(corner), black(0.6f * corner), black(0.15f * corner), 0},
                new float[] {0, 0.42f, 0.75f, 1}, Shader.TileMode.CLAMP);
        Matrix mc = new Matrix();
        mc.setScale(1, 0.72f, cx, cy);
        clockSide.setLocalMatrix(mc);
        p.setShader(clockSide);
        c.drawRect(w * 0.5f, 0, w, cy + w * 0.36f * 0.72f, p);
        return photo;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    /**
     * Everything the text needs, baked into the new photo once, in the background (#92, #94): a barely visible blur
     * behind both text blocks, then each block's ink from the blurred photo as it will be shown there (white or
     * near-black, whichever contrasts more; no memory of the previous photo), the usual soft scrims for white text,
     * and an extra soft scrim only where neither ink reaches MIN_CONTRAST. dark[0] = corner, dark[1] = caption.
     */
    private static Bitmap prepareText(Bitmap photo, float[] light, boolean[] dark, String title) {
        int[] a = shown(photo, REGION_A), b = shown(photo, REGION_B);
        if (BLUR_STRENGTH > 0) {
            softBlur(photo, a, BLUR_STRENGTH);
            softBlur(photo, b, BLUR_STRENGTH);
        }
        float[] sa = stats(photo, a), sb = stats(photo, b);
        dark[0] = darkWins(sa);
        dark[1] = darkWins(sb);
        withScrims(photo, light[0], light[1], dark[0], dark[1]);
        float[] fa = fixContrast(photo, a, dark[0]), fb = fixContrast(photo, b, dark[1]);
        AppLog.w("screensaver: “" + title + "” clock " + (dark[0] ? "dark" : "white") + " " + fmt(fa[0]) + ":1"
                + (fa[1] > 0 ? " (scrim " + fmt(fa[1]) + ")" : "") + ", caption " + (dark[1] ? "dark" : "white") + " "
                + fmt(fb[0]) + ":1" + (fb[1] > 0 ? " (scrim " + fmt(fb[1]) + ")" : ""));
        return photo;
    }

    private static String fmt(float v) {
        return String.valueOf(Math.round(v * 10) / 10f);
    }

    /**
     * A screen region in the photo's pixels, as it is drawn: the photo spans the screen at a zoom of 1 to 1.1 (1.05 on
     * average) around the center, the pan moves it a few percent; the block is widened a little to cover that.
     */
    private static int[] shown(Bitmap photo, float[] r) {
        float z = 1.05f, pad = 0.015f;
        int pw = photo.getWidth(), ph = photo.getHeight();
        return new int[] {
                Math.max(0, Math.round(pw * (0.5f + (r[0] - pad - 0.5f) / z))),
                Math.max(0, Math.round(ph * (0.5f + (r[1] - pad - 0.5f) / z))),
                Math.min(pw, Math.round(pw * (0.5f + (r[2] + pad - 0.5f) / z))),
                Math.min(ph, Math.round(ph * (0.5f + (r[3] + pad - 0.5f) / z)))};
    }

    /** The region's relative luminance (linear, WCAG): {25th percentile, 75th percentile, mean}. */
    private static float[] stats(Bitmap b, int[] r) {
        int nx = 48, ny = 24, n = nx * ny;
        float[] v = new float[n];
        float sum = 0;
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                int x = Math.min(r[2] - 1, r[0] + (int) ((r[2] - r[0]) * (i + 0.5f) / nx));
                int y = Math.min(r[3] - 1, r[1] + (int) ((r[3] - r[1]) * (j + 0.5f) / ny));
                int p = b.getPixel(x, y);
                float l = 0.2126f * lin((p >> 16) & 255) + 0.7152f * lin((p >> 8) & 255) + 0.0722f * lin(p & 255);
                v[j * nx + i] = l;
                sum += l;
            }
        }
        java.util.Arrays.sort(v);
        return new float[] {v[n / 4], v[n * 3 / 4], sum / n};
    }

    private static float lin(int c) {
        float s = c / 255f;
        return s <= 0.04045f ? s / 12.92f : (float) Math.pow((s + 0.055f) / 1.055f, 2.4f);
    }

    /** White text must beat the bright parts of the block (75th pct), dark text the dark parts (25th pct). */
    private static float whiteContrast(float[] s) { return 1.05f / (s[1] + 0.05f); }
    private static float darkContrast(float[] s) { return (s[0] + 0.05f) / (DARK_INK_LUM + 0.05f); }

    /**
     * White is the rule (#95): near-black gets lost on covers. A block goes near-black only behind a really bright
     * area, after the blur: mean relative luminance above 0.52 (about 75% gray) and white under 3:1 even at its
     * best spots. Never from the launcher's theme: nothing here reads Tema or the night state.
     */
    private static boolean darkWins(float[] s) {
        return s[2] > 0.52f && whiteContrast(s) < 3f;
    }

    /** If the block is still under MIN_CONTRAST, a soft feathered scrim just behind it. Returns {ratio, scrim alpha}. */
    private static float[] fixContrast(Bitmap photo, int[] r, boolean dark) {
        float[] s = stats(photo, r);
        float ratio = dark ? darkContrast(s) : whiteContrast(s), total = 0;
        // A few soft passes, measuring again each time (the feathered edge counts less than the middle).
        for (int pass = 0; pass < 4 && ratio < MIN_CONTRAST && total < 0.6f; pass++) {
            float alpha;
            if (dark) {
                float need = MIN_CONTRAST * (DARK_INK_LUM + 0.05f) - 0.05f;   // the 25th pct must reach this
                alpha = (need - s[0]) / Math.max(0.05f, 1 - s[0]);
            } else {
                float need = 1.05f / MIN_CONTRAST - 0.05f;                    // the 75th pct must drop to this
                alpha = 1 - need / Math.max(need, s[1]);
            }
            alpha = Math.min(0.6f - total, Math.max(0.06f, alpha * 1.3f));
            feathered(photo, r, dark ? 0xFFFFFFFF : 0xFF000000, alpha);
            total += alpha;
            s = stats(photo, r);
            ratio = dark ? darkContrast(s) : whiteContrast(s);
        }
        return new float[] {ratio, total};
    }

    /** An ellipse around the block, solid in the middle and fading to nothing well before its edge: no visible box. */
    private static RadialGradient mask(int[] r, int color) {
        float cx = (r[0] + r[2]) / 2f, cy = (r[1] + r[3]) / 2f;
        float rx = (r[2] - r[0]) / 2f * 1.7f, ry = (r[3] - r[1]) / 2f * 1.7f;
        int clear = color & 0x00FFFFFF;
        RadialGradient g = new RadialGradient(cx, cy, rx, new int[] {color, color, Ui.alpha(color, 0.45f), clear},
                new float[] {0, 0.5f, 0.75f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, ry / rx, cx, cy);
        g.setLocalMatrix(m);
        return g;
    }

    private static void feathered(Bitmap photo, int[] r, int color, float alpha) {
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(mask(r, color));
        p.setAlpha(Math.round(255 * alpha));
        drawAround(new Canvas(photo), photo, r, p);
    }

    private static void drawAround(Canvas c, Bitmap photo, int[] r, Paint p) {
        float cx = (r[0] + r[2]) / 2f, cy = (r[1] + r[3]) / 2f;
        float rx = (r[2] - r[0]) / 2f * 1.7f, ry = (r[3] - r[1]) / 2f * 1.7f;
        c.drawRect(Math.max(0, cx - rx), Math.max(0, cy - ry), Math.min(photo.getWidth(), cx + rx),
                Math.min(photo.getHeight(), cy + ry), p);
    }

    /**
     * The barely visible blur (#94): the area around the block scaled down BLUR_SCALE times (halving, so it averages)
     * and back up with filtering, laid over the photo through the same feathered ellipse at a low strength.
     */
    private static void softBlur(Bitmap photo, int[] r, float strength) {
        float cx = (r[0] + r[2]) / 2f, cy = (r[1] + r[3]) / 2f;
        float rx = (r[2] - r[0]) / 2f * 1.7f, ry = (r[3] - r[1]) / 2f * 1.7f;
        int x0 = Math.max(0, (int) (cx - rx)), y0 = Math.max(0, (int) (cy - ry));
        int x1 = Math.min(photo.getWidth(), (int) (cx + rx)), y1 = Math.min(photo.getHeight(), (int) (cy + ry));
        if (x1 - x0 < 16 || y1 - y0 < 16) return;
        Bitmap crop = Bitmap.createBitmap(photo, x0, y0, x1 - x0, y1 - y0);
        Bitmap small = crop;
        for (float f = 1; f < BLUR_SCALE; f *= 2) {
            Bitmap half = Bitmap.createScaledBitmap(small, Math.max(1, small.getWidth() / 2), Math.max(1, small.getHeight() / 2), true);
            if (small != crop) small.recycle();
            small = half;
        }
        Bitmap up = Bitmap.createScaledBitmap(small, crop.getWidth(), crop.getHeight(), true);
        if (small != crop) small.recycle();
        if (crop != photo) crop.recycle();
        BitmapShader bs = new BitmapShader(up, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix t = new Matrix();
        t.setTranslate(x0, y0);
        bs.setLocalMatrix(t);
        Paint p = new Paint(Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new android.graphics.ComposeShader(bs, mask(r, 0xFF000000), android.graphics.PorterDuff.Mode.DST_IN));
        p.setAlpha(Math.round(255 * Math.min(1, strength)));
        drawAround(new Canvas(photo), photo, r, p);
        up.recycle();
    }

    private static float scrimStrength(float light, float base) {
        return Math.max(base - 0.1f, Math.min(0.78f, base + (light - 0.3f) * 0.9f));
    }

    // MARK: - Corner: date and weather, glass clock, prices

    private static int blendRgb(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** The paint's own alpha with the corner ink; the soft shadow only under light ink (it muddies dark text). */
    private void ink(Paint p, int ink, float whiteness) {
        p.setColor((p.getColor() & 0xFF000000) | ink);
        if (whiteness > 0.02f) p.setShadowLayer(18 * k, 0, 2 * k, Math.round(0x66 * whiteness) << 24);
        else p.clearShadowLayer();
    }

    private void recordChrome(int inkRgb) {
        inkRecorded = inkRgb;
        float whiteness = (inkRgb & 255) / 255f;   // 1 = white ink, ~0.1 = near-black
        for (Paint p : new Paint[] {cornerDate, cornerQuote, cornerStale, cornerClock}) ink(p, inkRgb, whiteness);
        Canvas c = chrome.beginRecording(w, h);
        try {
            float right = RIGHT * k;
            float x = right;
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                String temp = HomeData.degrees(wx.temp);
                float tw = cornerDate.measureText(temp);
                c.drawText(temp, x - tw, DATE_BASE * k, cornerDate);
                x -= tw + 10 * k;
                String symbol = Symbols.weather(wx.code, wx.isDay);
                float size = 30 * k, sw = Symbols.width(getContext(), symbol, size);
                Symbols.draw(getContext(), c, symbol, x - sw / 2, DATE_BASE * k - Ui.halfCap(cornerDate), size, 0xEB000000 | inkRgb);
                x -= sw + 26 * k;
            }
            c.drawText(date, x - cornerDate.measureText(date), DATE_BASE * k, cornerDate);

            Glass g = glass;
            if (g != null && glassShader != null) {
                // The shadow under the glass: sets the digits apart from a bright photo.
                shadowPaint.setAlpha(140);
                c.drawBitmap(g.shadow, g.x, g.y + 6 * k, shadowPaint);
            } else {
                // Solid digits on the full-screen canvas (nothing to clip them). The baseline comes from the font's cap
                // height, not this string's ink, so it never jumps between times; right-aligned by the advance widths
                // (tabular figures), so the colon stays put from 10:08 to 23:59.
                cornerClock.setTextAlign(Paint.Align.RIGHT);
                c.drawText(clock, right, CLOCK_TOP * k + Ui.cap(cornerClock), cornerClock);
                cornerClock.setTextAlign(Paint.Align.LEFT);
            }

            // Compact prices under the clock, right-aligned.
            float base = (CLOCK_TOP + Ui.cap(cornerClock) / k + QUOTES_GAP) * k;
            float cap = Ui.cap(cornerQuote), gap = 7 * k, spacing = 26 * k;
            int[] coins = HomeData.active();
            int n = HomeData.coinCount();
            String[] values = new String[n];
            float[] glyphs = new float[n];
            float total = spacing * (n - 1);
            for (int i = 0; i < n; i++) {
                values[i] = HomeData.compact(data.prices[coins[i]]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                total += glyphs[i] + gap + cornerQuote.measureText(values[i]);
            }
            float qx = right - total;
            for (int i = 0; i < n; i++) {
                Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], qx + glyphs[i] / 2, base - cap / 2, cap, 0xA6000000 | inkRgb);
                qx += glyphs[i] + gap;
                c.drawText(values[i], qx, base, cornerQuote);
                qx += cornerQuote.measureText(values[i]) + spacing;
            }
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            if (!stale.isEmpty() && n > 0) c.drawText(stale, right - total - 24 * k - cornerStale.measureText(stale), base, cornerStale);
        } finally {
            chrome.endRecording();
        }
        chromeDirty = false;
    }

    private void drawGlass(Canvas c, float alpha, float e) {
        Glass g = glass;
        if (g == null || glassShader == null || alpha <= 0.004f) return;
        // The glass samples the frosted version (1/6 of the photo): same position, with the scale 6 times larger.
        float f = Backdrop.PHOTO_FROST;
        glassShader.setFloatUniform("placeA", placeA[0], placeA[1], placeA[2] * f);
        if (incoming != null) glassShader.setFloatUniform("placeB", placeB[0], placeB[1], placeB[2] * f);
        else glassShader.setFloatUniform("placeB", placeA[0], placeA[1], placeA[2] * f);
        glassShader.setFloatUniform("mixB", incoming == null ? 0 : e);
        glassShader.setFloatUniform("origin", g.x, g.y);
        glassShader.setFloatUniform("ink", g.inkTop, g.inkHeight);
        glassShader.setFloatUniform("refraction", 14 * k);
        glassShader.setFloatUniform("specular", 1);
        glassShader.setFloatUniform("alpha", alpha);
        c.drawRect(g.x, g.y, g.x + g.cover.getWidth(), g.y + g.cover.getHeight(), glassPaint);
    }

    /** Builds the glass clock's digits in the background (once a minute) and swaps them in when they're ready. */
    private void buildGlass(String text) {
        glassFor = text;
        if (glassShader == null || text.isEmpty()) {
            chromeDirty = true;
            return;
        }
        final Paint paint = new Paint(cornerClock);
        paint.clearShadowLayer();
        paint.setColor(0xFFFFFFFF);
        final float right = RIGHT * k, top = CLOCK_TOP * k, scale = k;
        final int gen = generation;
        worker.execute(() -> {
            Glass g = makeGlass(text, paint, right, top, scale);
            handler.post(() -> {
                if (gen != generation || !text.equals(glassFor)) return;
                glass = g;
                glassShader.setInputShader("slope", nearest(g.slope));
                glassShader.setInputShader("cover", nearest(g.cover));
                chromeDirty = true;
                invalidate();
            });
        });
    }

    private static BitmapShader nearest(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Frost.filter(s, BitmapShader.FILTER_MODE_NEAREST);
        return s;
    }

    /**
     * The sharp rendering of the digits, the surface height (the blurred rendering: 0.5 at the edge, 1 in the middle of
     * the strokes), its slope (for the lens and the highlight) and the shadow.
     */
    private static Glass makeGlass(String text, Paint paint, float right, float top, float k) {
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        int pad = Math.round(40 * k);
        int w = ink.width() + 2 * pad, h = ink.height() + 2 * pad;
        Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, paint);
        int[] px = new int[w * h];
        cover.getPixels(px, 0, w, 0, 0, w, h);
        int[] alpha = new int[px.length];
        for (int i = 0; i < px.length; i++) alpha[i] = (px[i] >>> 24) * 16;
        int[] height = blur(alpha, w, h, Math.max(2, Math.round(6 * k)), 3);
        int[] soft = blur(alpha, w, h, Math.max(3, Math.round(12 * k)), 3);
        float max = 1;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = height[i + 1] - height[i - 1], gy = height[i + w] - height[i - w];
                max = Math.max(max, (float) Math.sqrt(gx * gx + gy * gy));
            }
        }
        int[] slope = new int[px.length], shadow = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float gx = x > 0 && x < w - 1 ? height[i + 1] - height[i - 1] : 0;
                float gy = y > 0 && y < h - 1 ? height[i + w] - height[i - w] : 0;
                int r = 128 + Math.round(127 * gx / max), g = 128 + Math.round(127 * gy / max);
                int b = Math.min(255, height[i] / 16);
                slope[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                shadow[i] = Math.min(255, soft[i] / 16) << 24;
            }
        }
        int x = Math.round(right - ink.width()) - pad, y = Math.round(top) - pad;
        return new Glass(text, Bitmap.createBitmap(slope, w, h, Bitmap.Config.ARGB_8888), cover,
                Bitmap.createBitmap(shadow, w, h, Bitmap.Config.ARGB_8888), x, y, pad, Math.max(1, ink.height()));
    }

    /** Box blur (passes of radius r in both directions); anything outside the image counts as empty. */
    private static int[] blur(int[] src, int w, int h, int r, int passes) {
        int[] a = src.clone(), b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep, sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += in[base + i * step];
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (sum + div / 2) / div;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }

    // MARK: - Photo caption (bottom left)

    private void recordCaption(Slide s) {
        if (w == 0 || labelPaint == null) return;
        // One ink for the whole block (label, title, details), from this slide's own photo (#92).
        int inkRgb = s.captionDark ? DARK_INK : 0xFFFFFF;
        float whiteness = s.captionDark ? 0 : 1;
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint}) ink(p, inkRgb, whiteness);
        Canvas c = s.caption.beginRecording(w, h);
        try {
            float x = MARGIN * k, metaBase = META_BASE * k, titleBase = metaBase - 58 * k;
            String title = s.pick.title == null ? "" : s.pick.title;
            StaticLayout t = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, Math.round(TITLE_W * k))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).build();
            float originY = titleBase - t.getLineBaseline(t.getLineCount() - 1);
            float capTop = originY + t.getLineBaseline(0) - Ui.cap(titlePaint);
            c.drawText(Recs.periodLabel(), x, capTop - 30 * k, labelPaint);
            c.save();
            c.translate(x - Ui.leftBearing(titlePaint, title), originY);
            t.draw(c);
            c.restore();
            if (s.pick.meta != null && !s.pick.meta.isEmpty()) c.drawText(s.pick.meta, x, metaBase, metaPaint);
        } finally {
            s.caption.endRecording();
        }
        s.caption.setPosition(0, 0, w, h);
    }

    /** The caption fades out fast; the new one rises in once the photo has partly changed (like the home screen). */
    private void drawCaptions(Canvas c, long up) {
        if (incoming == null) {
            float a = current.startedAt == firstAt ? fadeIn(up - firstAt - 700, 900) : 1;
            current.caption.setAlpha(a);
            current.caption.setTranslationY(14 * k * (1 - a));
            current.caption.draw(c);
            return;
        }
        long t = up - incoming.startedAt;
        float out = 1 - Ui.clamp(t / 500f);
        if (out > 0.004f) {
            current.caption.setAlpha(out);
            current.caption.setTranslationY(0);
            current.caption.draw(c);
        }
        float in = fadeIn(t - 1300, 900);
        if (in > 0.004f) {
            incoming.caption.setAlpha(in);
            incoming.caption.setTranslationY(14 * k * (1 - in));
            incoming.caption.draw(c);
        }
    }

    // MARK: - Without photos: the big clock over the aurora

    private void drawFallback(Canvas canvas, long up, float alpha) {
        float t = (up - startedAt) / 1000f;
        if (up - lastSky >= 100) {
            lastSky = up;
            scene.advance(timeOffset + t);
        }
        scene.drawBackground(canvas);
        if (dirty) {
            Canvas rc = content.beginRecording(w, h);
            try {
                drawContent(rc);
            } finally {
                content.endRecording();
            }
            dirty = false;
        }
        double phase = 2 * Math.PI * t / DRIFT_PERIOD_S;
        content.setTranslationX((float) (40 * k * Math.sin(phase)));
        content.setTranslationY((float) (26 * k * Math.sin(2 * phase + 0.7)));
        content.setAlpha(alpha);
        content.draw(canvas);
    }

    private void drawContent(Canvas c) {
        float cx = w / 2f;
        float clockCap = Ui.cap(clockPaint);
        float clockBase = h * 0.5f + clockCap / 2 - 10 * k;
        c.drawText(date, cx, clockBase - clockCap - 34 * k, datePaint);
        c.drawText(clock, cx, clockBase, clockPaint);

        // Weather: symbol, temperature, high and low, centered under the clock.
        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp) && Prefs.weather()) {
            float base = clockBase + 76 * k;
            String symbol = Symbols.weather(wx.code, wx.isDay);
            String now = HomeData.degrees(wx.temp);
            String range = Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "  Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "  L:" + HomeData.degrees(wx.min)
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "  T:" + HomeData.degrees(wx.min)
                    : Ui.it ? "Max " + HomeData.degrees(wx.max) + "  Min " + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "  Mín. " + HomeData.degrees(wx.min);
            float sw = Symbols.width(getContext(), symbol, 32 * k);
            float width = sw + 12 * k + weatherPaint.measureText(now) + 18 * k + weatherDim.measureText(range);
            float x = cx - width / 2;
            Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(weatherPaint), 32 * k, 0xC7FFFFFF);
            x += sw + 12 * k;
            c.drawText(now, x, base, weatherPaint);
            x += weatherPaint.measureText(now) + 18 * k;
            c.drawText(range, x, base, weatherDim);
        }

        // Prices near the bottom edge: small white logo + value, all four on one line.
        float cap = Ui.cap(quotePaint), spacing = 56 * k, gap = 10 * k;
        int[] coins = HomeData.active();
        String[] values = new String[HomeData.coinCount()];
        float total = spacing * (values.length - 1);
        for (int i = 0; i < values.length; i++) {
            values[i] = HomeData.price(data.prices[coins[i]]);
            total += Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap) + gap + quotePaint.measureText(values[i]);
        }
        float x = cx - total / 2, base = h - 92 * k;
        for (int i = 0; i < values.length; i++) {
            float gw = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + gw / 2, base - cap / 2, cap, 0x80FFFFFF);
            x += gw + gap;
            c.drawText(values[i], x, base, quotePaint);
            x += quotePaint.measureText(values[i]) + spacing;
        }
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (!stale.isEmpty() && values.length > 0) c.drawText(Ui.tr("cotações de ", "prices from ") + stale, cx, base + 38 * k, stalePaint);
    }
}
