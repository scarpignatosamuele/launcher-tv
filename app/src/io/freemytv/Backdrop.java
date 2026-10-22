package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The home screen background made from the recommendation's image, built once in the background:
 *  - the photo cropped to 16:9 at the TV's UI size (1920×1080; 1280×720 on TVs that draw their UI at 720p, like TCL's
 *    1 GB models, where every bitmap here is 2/3 as wide), with the dark scrims already applied (on the left,
 *    where the hero text sits; at the bottom, behind the shelf; a faint one at the top, behind the buttons); the left
 *    scrim gets stronger when the photo is bright there;
 *  - the frosted version (192×108, heavily blurred) of the same image: the background of the frosted glass and of the
 *    bottom drawer;
 *  - the Liquid Glass version (480×270, lightly blurred): the photo shows through the shelf and the cards;
 *  - with forLight: the cards (the photo without scrims, cropped wide and tall, already at the focused card size) and
 *    the frosted and glass versions blended with white (by day) and with near-black (at night).
 * Only what is needed gets built (with forLight, no full photo: the cards are lightweight, so all 9 for the three tabs
 * fit in memory, ready); the other fields stay null.
 * The final image lives only in GPU memory (HARDWARE): drawing it costs one texture, and the process doesn't hold 8 MB.
 */
final class Backdrop {
    /** The UI's size against 1920×1080 (at most 1): nothing is built bigger than the screen can show. */
    private static final float UI_SCALE = uiScale();
    static final int W = ui(1920), H = ui(1080), FW = 192, FH = 108, GW = 480, GH = 270;
    /** The cards at their focused size (1.05×): the wide one and the two tall ones. */
    static final int WIDE_W = ui(800), WIDE_H = ui(450), TALL_W = ui(16), TALL_H = ui(16);   // 16:9 cards (all carousel sizes); tall unused

    private static float uiScale() {
        android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
        int longest = Math.max(m.widthPixels, m.heightPixels);
        return longest > 0 ? Math.min(1f, longest / 1920f) : 1f;
    }

    private static int ui(int px) {
        return Math.round(px * UI_SCALE);
    }
    final Bitmap image;
    final int[] frost;      // heavily blurred (frosted glass, bottom drawer)
    final int[] glass;      // lightly blurred (Liquid Glass: the photo shows through the glass)
    final Bitmap wide, tall;            // the photo on the cards
    final int[] light, lightGlass;      // by day: the frosted and glass versions, lightened
    final Bitmap lightImage;            // by day: the background (the lightened frosted version, scaled up on screen)
    final int[] nightFrost, nightGlass; // at night: frosted (the bottom drawer's frosted glass) and glass, darkened
    final Bitmap nightImage;            // at night: the background (the darkened frosted version)
    /** The photo's vibrant dominant color (opaque RGB), or 0 for a photo with no real color: the home's tint. */
    int vibrant;
    /** The background with the cover's soft glow baked in (by day, at night): built once, on first use (#91). */
    Bitmap tintedLight, tintedNight;
    /** Dark only (#98): the glass version, darkened, packed as RGB 565 (half of an int[]; expanded when shown). */
    short[] glass565;

    /** The dark glass image into `out` (GW × GH ARGB), or null if this backdrop has none. */
    int[] nightGlassInto(int[] out) {
        if (glass565 == null) return nightGlass;
        for (int i = 0; i < glass565.length; i++) {
            int v = glass565[i] & 0xFFFF;
            int r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
            out[i] = 0xFF000000 | ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
        }
        return out;
    }

    /** Roughly what this backdrop holds (heap, native and GPU), for the cache's byte budget. */
    int bytes() {
        int n = 0;
        if (wide != null) n += wide.getWidth() * wide.getHeight() * (wide.getConfig() == Bitmap.Config.ARGB_8888 ? 4 : 2);
        if (image != null) n += image.getWidth() * image.getHeight() * 4;
        if (glass565 != null) n += glass565.length * 2;
        if (frost != null) n += frost.length * 4;
        if (glass != null) n += glass.length * 4;
        if (nightFrost != null) n += nightFrost.length * 4;
        if (nightGlass != null) n += nightGlass.length * 4;
        if (light != null) n += light.length * 4;
        if (lightGlass != null) n += lightGlass.length * 4;
        n += 2 * FW * FH * 4;   // the background bitmaps (and the tinted copy)
        return n;
    }

    private static short[] pack565(int[] px) {
        short[] out = new short[px.length];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            out[i] = (short) ((((p >> 16) & 0xF8) << 8) | (((p >> 8) & 0xFC) << 3) | ((p & 0xF8) >> 3));
        }
        return out;
    }

    private Backdrop(Bitmap image, int[] frost, int[] glass, Bitmap wide, Bitmap tall, int[] light, int[] lightGlass,
                     int[] night, int[] nightGlass) {
        this.image = image;
        this.frost = frost;
        this.glass = glass;
        this.wide = wide;
        this.tall = tall;
        this.light = light;
        this.lightGlass = lightGlass;
        this.nightFrost = night;
        this.nightGlass = nightGlass;
        lightImage = light == null ? null : Bitmap.createBitmap(light, FW, FH, Bitmap.Config.ARGB_8888);
        nightImage = night == null ? null : Bitmap.createBitmap(night, FW, FH, Bitmap.Config.ARGB_8888);
    }

    /** The light design's working copy: a bit larger than the focused card (everything else is much smaller). */
    static final int LIGHT_W = ui(960), LIGHT_H = ui(540);

    static Backdrop make(Bitmap src, boolean forLight) {
        // Light design: the cards (800 × 450 at most) and the small glass/frost copies all come from a 960 × 540 copy,
        // a quarter of the pixels of a full-screen one (measured: the build was most of the ~0.5 s per image).
        int W = forLight ? LIGHT_W : Backdrop.W, H = forLight ? LIGHT_H : Backdrop.H;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        float scale = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        boolean square = src.getWidth() < src.getHeight() * 1.3f;
        if (square) {
            // Square art (music, #98): a soft, dark, blurred copy fills the 16:9 frame; the art sits whole in the middle.
            Bitmap tiny = Bitmap.createScaledBitmap(src, 16, 9, true);
            c.drawBitmap(tiny, null, new RectF(0, 0, W, H), photo);
            tiny.recycle();
            c.drawColor(0x8C000000);
            float a = H * 0.86f, ax = (W - a) / 2, ay = (H - a) / 2;
            c.drawBitmap(src, null, new RectF(ax, ay, ax + a, ay + a), photo);
        } else {
            c.drawBitmap(src, null, new RectF((W - sw) / 2, (H - sh) / 2, (W + sw) / 2, (H + sh) / 2), photo);
        }
        if (!(forLight && square)) src.recycle();
        if (forLight) {
            // Light design: everything comes from the photo without scrims.
            // Dark only (#98): no lightened copies; the card in RGB 565 (dithered; a photo has no alpha) and the glass
            // packed in 565: about a third of what each image held before.
            Bitmap wide = card(out, WIDE_W, WIDE_H);
            if (square) {
                // The square tile shows the art whole (above); the background, the dock's glass and the tint take the
                // same art cover-filled across the 16:9 frame, like any photo (no dark letterbox in the reflection).
                c.drawBitmap(src, null, new RectF((W - sw) / 2, (H - sh) / 2, (W + sw) / 2, (H + sh) / 2), photo);
                src.recycle();
            }
            int[] night = darken(reduce(out, FW, FH, 3, 3), 0.76f);
            int[] nightGlass = darken(reduce(out, GW, GH, 2, 2), 0.56f);
            int tint = vibrant(out);
            out.recycle();
            Backdrop b = new Backdrop(null, null, null, wide, null, null, null, night, null);
            b.glass565 = pack565(nightGlass);
            b.vibrant = tint;
            return b;
        }

        float boost = Math.max(0, Math.min(0.3f, (luminance(out, 0, 0.22f, 0.5f, 0.62f) - 0.22f) * 1.1f));
        Paint scrim = new Paint(Paint.DITHER_FLAG);
        scrim.setShader(new LinearGradient(0, 0, W * 0.68f, 0,
                new int[] {black(0.66f + boost), black(0.52f + boost * 0.8f), black(0.26f + boost * 0.4f), black(0.07f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, scrim);
        // At the bottom, a faint scrim: the photo must show through the glass shelf (the glass provides the contrast).
        scrim.setShader(new LinearGradient(0, H, 0, H * 0.4f,
                new int[] {black(0.38f), black(0.3f), black(0.18f), black(0.05f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, H * 0.4f, W, H, scrim);
        // Behind the prices (above the shelf, on the right), a localized scrim for the small text.
        scrim.setShader(new android.graphics.RadialGradient(W * 0.8f, H * 0.65f, W * 0.24f,
                new int[] {black(0.42f), black(0.18f), 0}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.32f, W * 0.8f, H * 0.65f);
        c.drawCircle(W * 0.8f, H * 0.65f, W * 0.24f, scrim);
        c.restore();
        scrim.setShader(new LinearGradient(0, 0, 0, H * 0.24f, new int[] {black(0.34f), black(0.1f), 0},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H * 0.24f, scrim);

        int[] frost = reduce(out, FW, FH, 3, 3);
        int[] glass = reduce(out, GW, GH, 2, 2);
        return new Backdrop(gpu(out), frost, glass, null, null, null, null, null, null);
    }

    /**
     * The photo's vibrant dominant color, from a 48×27 copy (1,296 pixels, built here on the background thread with the
     * rest of the backdrop, so it is cached with it): hues in 24 buckets, each pixel weighted by how saturated it is
     * and by how far it is from black and white; the heaviest bucket (with its two neighbours) wins and its average
     * color is returned. A gray or near-black photo returns 0 (no tint).
     */
    static int vibrant(Bitmap photo) {
        Bitmap s = Bitmap.createScaledBitmap(photo, 48, 27, true);
        int n = s.getWidth() * s.getHeight();
        int[] px = new int[n];
        s.getPixels(px, 0, s.getWidth(), 0, 0, s.getWidth(), s.getHeight());
        if (s != photo) s.recycle();
        final int B = 24;
        float[] w = new float[B], r = new float[B], g = new float[B], bl = new float[B];
        float[] hsv = new float[3];
        for (int p : px) {
            android.graphics.Color.colorToHSV(p, hsv);
            float sat = hsv[1], val = hsv[2];
            if (sat < 0.18f || val < 0.16f) continue;
            float weight = sat * sat * (1 - Math.abs(val - 0.62f));
            int i = Math.min(B - 1, (int) (hsv[0] / 360f * B));
            w[i] += weight;
            r[i] += weight * ((p >> 16) & 255);
            g[i] += weight * ((p >> 8) & 255);
            bl[i] += weight * (p & 255);
        }
        int best = -1;
        float bestW = 0;
        for (int i = 0; i < B; i++) {
            float sum = w[i] + 0.5f * (w[(i + 1) % B] + w[(i + B - 1) % B]);
            if (sum > bestW) { bestW = sum; best = i; }
        }
        // Too little color (under ~3% of the frame at full weight): leave the background neutral.
        if (best < 0 || bestW < n * 0.03f * 0.25f) return 0;
        float tw = 0, tr = 0, tg = 0, tb = 0;
        for (int d = -1; d <= 1; d++) {
            int i = (best + d + B) % B;
            float f = d == 0 ? 1 : 0.5f;
            tw += w[i] * f; tr += r[i] * f; tg += g[i] * f; tb += bl[i] * f;
        }
        return 0xFF000000 | (Math.round(tr / tw) << 16) | (Math.round(tg / tw) << 8) | Math.round(tb / tw);
    }

    /** How many times smaller the screensaver photo's frosted version is (the glass math uses the same number). */
    static final int PHOTO_FROST = 6;

    /**
     * Screensaver: the whole original from disk at w × h (center-cropped to that aspect ratio), filtered down, plus the
     * frosted version (1/6, blurred) for the glass clock. `light` receives the luminance (0 to 1) where the caption
     * (bottom left) and the clock (top right) sit, for the scrims. Returns {photo (mutable), frosted}.
     */
    static Bitmap[] photo(java.io.File f, int w, int h, float[] light) throws java.io.IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("unreadable image");
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap src = android.graphics.BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) throw new java.io.IOException("unreadable image");
        float ratio = (float) w / h;
        int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
        int ch = Math.min(src.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
        if (b != src) src.recycle();
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            b.recycle();
            b = half;
        }
        Bitmap out = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
        if (out != b) b.recycle();
        if (light != null && light.length >= 2) {
            light[0] = luminance(out, 0, 0.6f, 0.5f, 0.95f);
            light[1] = luminance(out, 0.62f, 0.04f, 0.96f, 0.36f);
        }
        int fw = w / PHOTO_FROST, fh = h / PHOTO_FROST;
        Bitmap frost = Bitmap.createBitmap(reduce(out, fw, fh, 3, 2), fw, fh, Bitmap.Config.ARGB_8888);
        if (!out.isMutable()) {
            Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
            out.recycle();
            out = copy;
        }
        return new Bitmap[] {out, frost};
    }

    /** The center of the photo at the card's aspect ratio, filtered down (halving while possible) to the exact size. */
    private static Bitmap card(Bitmap full, int w, int h) {
        float ratio = (float) w / h;
        int cw = Math.min(full.getWidth(), Math.round(full.getHeight() * ratio));
        int ch = Math.min(full.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(full, (full.getWidth() - cw) / 2, (full.getHeight() - ch) / 2, cw, ch);
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        // RGB 565 with dithering (#98): half the memory of ARGB on the GPU; a photo needs no alpha.
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        new Canvas(out).drawBitmap(b, null, new RectF(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        if (b != full) b.recycle();
        return gpu(out);
    }

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
    /** An opaque photo on the GPU in RGB 565 (dithered): half the memory of ARGB (#98: the screensaver's photos). */
    static Bitmap gpu565(Bitmap b) {
        Bitmap o = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.RGB_565);
        new Canvas(o).drawBitmap(b, 0, 0, new Paint(Paint.DITHER_FLAG));
        b.recycle();
        return gpu(o);
    }

    static Bitmap gpu(Bitmap b) {
        Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
        if (g == null) return b;
        b.recycle();
        return g;
    }

    /** Blends with a near-black (the darkest gray of Apple's dark-mode backgrounds), keeping the photo's tint. */
    private static int[] darken(int[] px, float amount) {
        float keep = 1 - amount, base = 11 * amount;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * keep + base);
            int g = Math.round(((p >> 8) & 255) * keep + base);
            int b = Math.round((p & 255) * keep + base + 2 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    private static int[] whiten(int[] px, float amount) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * (1 - amount) + 255 * amount);
            int g = Math.round(((p >> 8) & 255) * (1 - amount) + 255 * amount);
            int b = Math.round((p & 255) * (1 - amount) + 255 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** Moving average of radius r in one direction, repeating the edge pixels. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float k = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
    static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        float sum = 0;
        int count = 0;
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
                int p = b.getPixel(x, y);
                sum += (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
                count++;
            }
        }
        return sum / count;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }
}
