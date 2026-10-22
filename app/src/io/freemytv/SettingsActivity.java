package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Launcher's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Launcher / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, THEME = 6;
        private static final float LIST_ROW_GAP = 6;

        /** List rows: 68 tall, 56 when Home Settings has more rows than fit (the theme's two times make 13). */
        private float listRowH() {
            return screen == HOME_SETTINGS && settingLabels().length > 11 ? 56 : 68;
        }
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 120, LIST_R = 1800, LIST_TOP = 262, ROW_H = 74, ROW_GAP = 8, CHOICE_H = 104;
        private static final int AMBER = 0xFFFFC266;

        private final SettingsActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[16];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0;
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt;
        private String lastShown = "";
        private int w, h;
        private float k = 1;
        private Paint card, section, title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                // Redraws only when something shown changed (#89: idle, the screen stays still), or the theme flips.
                String shown = state.toString() + recs.toString() + hasKey
                        + HomeData.get(getContext()).isNight(System.currentTimeMillis());
                if (!shown.equals(lastShown) || nowBusy) invalidate();
                lastShown = shown;
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            setFocusable(true);
            setContentDescription("Launcher");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    int[] items = mainItems();
                    if (row >= items.length) return "";
                    String v = mainValue(items[row]);
                    return mainLabel(items[row]) + (v == null ? "" : ", " + v);
                }
                case THEME: {
                    if (row < 4) return themeName(THEME_KEYS[row]) + (THEME_KEYS[row].equals(Prefs.theme()) ? Ui.tr(", escolhido", ", selected") : "");
                    return (row == 4 ? Ui.tr("Escuro a partir das", "Dark from") : Ui.tr("Claro a partir das", "Light from")) + ", "
                            + hhmm(row == 4 ? Prefs.darkFrom() : Prefs.lightFrom());
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Launcher, " + Ui.tr("sem anúncios, com a tela de descanso nova", "no ads, with the new screensaver")
                            : "Original, " + Ui.tr("o Início de fábrica da TV", "the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". Em uso", ". In use") : "") + ", " + (row + 1) + Ui.tr(" de 2", " of 2");
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = keys ? keyLabels() : settingLabels(), values = keys ? keyValues() : settingValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", escolhido", ", selected") : "")
                            + ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size();
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    return app[1] + ", " + (place >= 0 ? Ui.tr("na prateleira, posição ", "on the shelf, place ") + (place + 1)
                            : Ui.tr("fora da prateleira", "not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                layoutMain();
                focusBox.set(mainRects[mainItems()[Math.min(row, mainItems().length - 1)]]);
                return focusBox;
            } else if (screen == THEME) {
                top = LIST_TOP + row * (MAIN_ROW_H + MAIN_GAP) + (row >= 4 ? 24 : 0);
                height = MAIN_ROW_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (listRowH() + LIST_ROW_GAP);
                height = listRowH();
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            invalidate();
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
        }


        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            makePaints();
        }

        /**
         * Light or dark like the home screen (#89: Settings > Theme): a plain, still background, no animation. By day
         * the white-on-dark text colors turn into the same alphas of near-black.
         */
        private boolean light;
        private android.graphics.Shader background;
        private final Paint bgPaint = new Paint(Paint.DITHER_FLAG);

        private int fg(int white) {
            return light ? (white & 0xFF000000) | 0x1C1C1E : white;
        }

        /** Text on the focused row: near-black on the white pill (dark theme), white on the near-black pill (light). */
        private int focusInk(int alpha) {
            return (alpha << 24) | (light ? 0xFFFFFF : 0x1C1C1E);
        }

        private void makePaints() {
            background = new android.graphics.LinearGradient(0, 0, 0, h, light ? 0xFFF2F2F5 : focusInk(0xFF),
                    light ? 0xFFDDDDE2 : 0xFF0B0B0D, android.graphics.Shader.TileMode.CLAMP);
            title = Ui.text(Ui.displaySemibold, 60 * k, fg(0xFFFFFFFF));
            card = new Paint(Paint.ANTI_ALIAS_FLAG);
            card.setColor(light ? 0xB3FFFFFF : 0x14FFFFFF);
            section = Ui.text(Ui.semibold, 24 * k, fg(0x8CFFFFFF));
            section.setLetterSpacing(0.08f);
            subtitle = Ui.text(Ui.regular, 26 * k, fg(0x99FFFFFF));
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, fg(0x66FFFFFF));
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 32 * k, fg(0xF2FFFFFF));
            rowValue = Ui.text(Ui.regular, 30 * k, fg(0x8CFFFFFF));
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            header = Ui.text(Ui.semibold, 21 * k, fg(0x73FFFFFF));
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, fg(0xD9FFFFFF));
            cellValue = Ui.numbers(Ui.regular, 25 * k, fg(0xD9FFFFFF));
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, light ? 0xFFA05A00 : AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, fg(0xB3FFFFFF)));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(light ? 0xFF1C1C1E : 0xFFFFFFFF);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return mainItems().length;
                case THEME: return themeRows();
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            boolean wantLight = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
            if (wantLight != light) {
                light = wantLight;
                makePaints();
            }
            bgPaint.setShader(background);   // opaque: the window behind never shows through
            c.drawRect(0, 0, w, h, bgPaint);

            // No name or version on top (#98): the settings start with their own rows.

            if (screen == MAIN) drawMain(c, seconds); else if (screen == THEME) drawTheme(c); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c); else drawList(c);
            // Next frame only while a focus spring settles or the switch spinner turns: idle, nothing redraws.
            boolean moving = busy();
            for (Spring sp : pop) moving |= !sp.settled();
            if (running && moving) postInvalidateOnAnimation();
        }

        // MARK: - Main screen (#90): two columns of grouped rows over the whole screen

        private static final int I_HOME = 0, I_THEME = 1, I_LANG = 2, I_UNITS = 3, I_SOUNDS = 4, I_DOCK = 5, I_CITY = 6,
                I_WEATHER = 7, I_YOUTUBE = 8, I_SAVER = 9, I_KEYS = 10, I_TV = 11, I_GLASS = 12;
        private static final float COL_W = 810, COL_GAP = 60, MAIN_TOP = 270, MAIN_ROW_H = 84, MAIN_GAP = 12, SECTION_H = 64;
        private final RectF[] mainRects = new RectF[13];

        /** What the main screen lists, in focus order (the remote's app buttons only on TVs that have them). */
        private int[] mainItems() {
            return RemoteKeys.supported() ? new int[] {I_HOME, I_LANG, I_UNITS, I_SOUNDS, I_GLASS, I_DOCK, I_CITY, I_WEATHER, I_YOUTUBE, I_SAVER, I_KEYS, I_TV}
                    : new int[] {I_HOME, I_LANG, I_UNITS, I_SOUNDS, I_GLASS, I_DOCK, I_CITY, I_WEATHER, I_YOUTUBE, I_SAVER, I_TV};   // no Tema: dark only (#96)
        }

        private static int column(int id) {
            return id <= I_SOUNDS || id == I_GLASS ? 0 : 1;
        }

        private String mainLabel(int id) {
            switch (id) {
                case I_HOME: return Ui.tr("Início", "Home Screen");
                case I_THEME: return Ui.tr("Tema", "Theme");
                case I_LANG: return Ui.tr("Idioma", "Language");
                case I_UNITS: return Ui.tr("Temperatura", "Temperature");
                case I_SOUNDS: return Ui.tr("Sons de navegação", "Navigation sounds");
                case I_GLASS: return Ui.tr("Efeito vidro", "Glass effect");
                case I_DOCK: return Ui.tr("Prateleira", "Shelf");
                case I_CITY: return Ui.tr("Cidade", "City");
                case I_WEATHER: return Ui.tr("Widget do clima", "Weather widget");
                case I_YOUTUBE: return Ui.tr("Conectar YouTube", "Link YouTube");
                case I_SAVER: return Ui.tr("Tela de Descanso", "Screensaver");
                case I_KEYS: return Ui.tr("Botões do Controle", "Remote Buttons");
                default: return Ui.tr("Ajustes da TV", "TV Settings");
            }
        }

        /** The value on the right; null: a chevron (the row opens a screen). */
        private String mainValue(int id) {
            switch (id) {
                case I_HOME: {
                    if (busy()) { String b = busyText(); return b.isEmpty() ? Ui.tr("Começando…", "Starting…") : b; }
                    return freeActive() ? "Launcher" : Ui.tr("Original", "Original");
                }
                case I_THEME: return themeName(Prefs.theme());
                case I_LANG: {
                    String lang = Prefs.language();
                    return lang.equals("pt") ? "Português" : lang.equals("en") ? "English" : lang.equals("es") ? "Español"
                            : lang.equals("de") ? "Deutsch" : lang.equals("it") ? "Italiano" : lang.equals("ru") ? "Русский" : Ui.tr("Automático", "Automatic");
                }
                case I_UNITS: {
                    String unit = Prefs.temperature();
                    return unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
                }
                case I_SOUNDS: return onOff(Prefs.sounds());
                case I_GLASS: return glassName(Prefs.glass());
                case I_CITY: return !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
                case I_WEATHER: return onOff(Prefs.weather());
                case I_YOUTUBE: return youtubeValue();
                case I_SAVER: return Ui.tr("Ver agora", "Preview");
                default: return null;
            }
        }

        /** The glass levels' names (← → on the row steps through them; OK too, wrapping). */
        private String glassName(int level) {
            switch (level) {
                case 0: return Ui.tr("Opaco", "Opaque");
                case 1: return Ui.tr("Mínimo", "Minimal");
                case 2: return Ui.tr("Leve", "Subtle");
                case 4: return Ui.tr("Marcado", "Strong");
                case 5: return Ui.tr("Máximo", "Maximum");
                default: return Ui.tr("Padrão", "Standard");
            }
        }

        /**
         * Live preview left of the value: a small glass capsule over a colored stripe, filled as the dock's glass is at
         * that level (solid at Opaque, the stripe showing through more as the level rises) with its rim.
         */
        private void drawGlassPreview(Canvas c, float right, float cy, float f) {
            float w = 120 * k, h = 40 * k, x = right - w;
            RectF band = new RectF(x - 18 * k, cy - 6 * k, x + w + 18 * k, cy + 6 * k);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new android.graphics.LinearGradient(band.left, 0, band.right, 0,
                    new int[] {0xFFFF375F, 0xFFFFD60A, 0xFF30D158, 0xFF0A84FF}, null, android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(band, 6 * k, 6 * k, p);
            p.setShader(null);
            RectF pill = new RectF(x, cy - h / 2, x + w, cy + h / 2);
            p.setColor(Ui.alpha(0xFF111113, Scene.glassFill(0.8f)));
            c.drawRoundRect(pill, h / 2, h / 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * k);
            float rim = new float[] {0.12f, 0.2f, 0.26f, 0.32f, 0.42f, 0.52f}[Prefs.glass()];
            p.setColor(blend(Ui.alpha(0xFFFFFFFF, rim), Ui.alpha(0xFF000000, rim), f));
            c.drawRoundRect(pill, h / 2, h / 2, p);
        }

        private String themeName(String theme) {
            return theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark")
                    : theme.equals("scheduled") ? Ui.tr("Programado", "Scheduled") : Ui.tr("Automático", "Automatic");
        }

        /** Row places: the left column holds "General", the right one "Home" then "Screensaver and TV". */
        private void layoutMain() {
            float[] y = {MAIN_TOP + SECTION_H, MAIN_TOP + SECTION_H};
            for (int id : mainItems()) {
                int col = column(id);
                if (id == I_SAVER) y[1] += SECTION_H;
                float x = LIST_X + col * (COL_W + COL_GAP);
                if (mainRects[id] == null) mainRects[id] = new RectF();
                mainRects[id].set(x * k, y[col] * k, (x + COL_W) * k, (y[col] + MAIN_ROW_H) * k);
                y[col] += MAIN_ROW_H + MAIN_GAP;
            }
        }

        private void drawMain(Canvas c, float seconds) {
            layoutMain();
            int[] items = mainItems();
            float rightX = (LIST_X + COL_W + COL_GAP) * k;
            c.drawText(Ui.tr("GERAL", "GENERAL"), LIST_X * k + 8 * k, (MAIN_TOP + 40) * k, section);
            c.drawText(Ui.tr("INÍCIO", "HOME"), rightX + 8 * k, (MAIN_TOP + 40) * k, section);
            c.drawText(Ui.tr("TELA DE DESCANSO E TV", "SCREENSAVER AND TV"), rightX + 8 * k,
                    mainRects[I_SAVER].top - (SECTION_H - 40) * k, section);
            for (int i = 0; i < items.length; i++) {
                int id = items[i];
                rowRect.set(mainRects[id]);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), focusInk(0xFF), f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(mainLabel(id), rowRect.left + 32 * k, base, rowLabel);
                float right = rowRect.right - 32 * k;
                String value = mainValue(id);
                if (value == null) {
                    float cw = Symbols.width(getContext(), "chevron_right", 24 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 24 * k, blend(fg(0x8CFFFFFF), focusInk(0xB3), f));
                    continue;
                }
                rowValue.setColor(blend(fg(0x99FFFFFF), focusInk(0xCC), f));
                if (id == I_GLASS) value = "‹  " + value + "  ›";
                String shown = TextUtils.ellipsize(value, new TextPaint(rowValue), 400 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(shown, right, base, rowValue);
                if (id == I_GLASS) drawGlassPreview(c, right - rowValue.measureText(shown) - 28 * k, rowRect.centerY(), f);
                if (id == I_HOME && busy()) {
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(fg(0xFFFFFFFF), focusInk(0xFF), f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                }
            }
            // Under the left column: what went wrong with the last switch, if anything (the whole message: #14).
            float y = mainRects[I_SOUNDS].bottom + 60 * k;
            String error = state.optString("error");
            String busyText = busyText();
            if (busy() && (busyText.contains("Permitir") || busyText.contains("Allow") || busyText.contains("разрешите"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 8 * k, y, warn);
            } else if (!busy() && !error.isEmpty()) {
                int width = Math.round((COL_W - 16) * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 8 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
            }
            // Footer: the credit TMDB asks for.
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), LIST_X * k + 8 * k, 1020 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
        }

        /** ↑ ↓ within a column, ← → to the other column (to the row nearest in height). */
        private void moveMain(int dx, int dy) {
            int[] items = mainItems();
            focus = Math.min(focus, items.length - 1);
            int cur = items[focus];
            if (dy != 0) {
                for (int i = focus + dy; i >= 0 && i < items.length; i += dy) {
                    if (column(items[i]) == column(cur)) { focus = i; return; }
                }
                return;
            }
            int want = column(cur) + dx;
            if (want < 0 || want > 1) return;
            layoutMain();
            float cy = mainRects[cur].centerY(), best = Float.MAX_VALUE;
            for (int i = 0; i < items.length; i++) {
                if (column(items[i]) != want) continue;
                float d = Math.abs(mainRects[items[i]].centerY() - cy);
                if (d < best) { best = d; focus = i; }
            }
        }

        private int mainIndex(int id) {
            int[] items = mainItems();
            for (int i = 0; i < items.length; i++) if (items[i] == id) return i;
            return 0;
        }

        // MARK: - Theme (#89)

        private static final String[] THEME_KEYS = {"light", "dark", "auto", "scheduled"};

        private int themeRows() {
            return scheduled() ? 6 : 4;
        }

        private void drawTheme(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 40 * k, fg(0xFFFFFFFF));
            c.drawText(Ui.tr("Tema", "Theme"), LIST_X * k + 8 * k, (LIST_TOP - 40) * k, heading);
            String current = Prefs.theme();
            String[] notes = {"", "", Ui.tr("Escuro do pôr do sol ao nascer do sol", "Dark from sunset to sunrise"),
                    Ui.tr("Escuro entre os dois horários abaixo", "Dark between the two times below")};
            for (int i = 0; i < themeRows(); i++) {
                rowRect.set(LIST_X * k, (LIST_TOP + i * (MAIN_ROW_H + MAIN_GAP)) * k, (LIST_X + COL_W * 1.25f) * k,
                        (LIST_TOP + i * (MAIN_ROW_H + MAIN_GAP) + MAIN_ROW_H) * k);
                if (i >= 4) rowRect.offset(0, 24 * k);   // the two times sit a little apart
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), focusInk(0xFF), f));
                rowValue.setColor(blend(fg(0x99FFFFFF), focusInk(0xCC), f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel), right = rowRect.right - 32 * k;
                if (i < 4) {
                    c.drawText(themeName(THEME_KEYS[i]), rowRect.left + 32 * k, base, rowLabel);
                    float x = right;
                    if (THEME_KEYS[i].equals(current)) {
                        float cw = Symbols.width(getContext(), "check", 28 * k);
                        Symbols.draw(getContext(), c, "check", right - cw / 2, rowRect.centerY(), 28 * k, blend(fg(0xFFFFFFFF), focusInk(0xFF), f));
                        x -= cw + 24 * k;
                    }
                    if (!notes[i].isEmpty()) c.drawText(notes[i], x, base, rowValue);
                } else {
                    boolean dark = i == 4;
                    c.drawText(dark ? Ui.tr("Escuro a partir das", "Dark from") : Ui.tr("Claro a partir das", "Light from"),
                            rowRect.left + 32 * k, base, rowLabel);
                    c.drawText("‹  " + hhmm(dark ? Prefs.darkFrom() : Prefs.lightFrom()) + "  ›", right, base, rowValue);
                }
            }
            Paint hint = Ui.text(Ui.regular, 26 * k, fg(0x8CFFFFFF));
            float hy = (LIST_TOP + themeRows() * (MAIN_ROW_H + MAIN_GAP) + (scheduled() ? 24 : 0) + 40) * k;
            c.drawText(scheduled() ? Ui.tr("OK escolhe · ← → mudam o horário (30 min)", "OK picks · ← → change the time (30 min)")
                    : Ui.tr("OK escolhe", "OK picks"), LIST_X * k + 8 * k, hy, hint);
        }

        /** A theme row: OK picks it (applies at once, here and on the home screen); the times move by half an hour. */
        private void themeKey(int dir, boolean ok) {
            if (focus < 4) {
                if (!ok) return;
                Prefs.setTheme(THEME_KEYS[focus]);
            } else if (focus == 4) {
                Prefs.setDarkFrom((Prefs.darkFrom() + (dir == 0 ? 1 : dir) * 30 + 1440) % 1440);
            } else {
                Prefs.setLightFrom((Prefs.lightFrom() + (dir == 0 ? 1 : dir) * 30 + 1440) % 1440);
            }
            focus = Math.min(focus, themeRows() - 1);
            invalidate();
        }
        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            c.drawRoundRect(rowRect, 18 * k, 18 * k, card);   // every row is a soft rounded card
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, (light ? 0.28f : 0.5f) * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        /** Home screen choice: Launcher or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, fg(0xFFFFFFFF));
            c.drawText(Ui.tr("Início", "Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Launcher", Ui.tr("Sem anúncios, com a tela de descanso nova", "No ads, with the new screensaver")},
                    {Ui.tr("Original", "Original"), Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), focusInk(0xFF), f));
                rowSub.setColor(blend(fg(0x8CFFFFFF), focusInk(0x99), f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(fg(0xFFFFFFFF), focusInk(0xFF), f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            c.drawText(Ui.tr("A troca leva uns segundos e a tela pisca nas medições.",
                    "Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String versionName(Context c) {
            try {
                return "Launcher " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Launcher";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && screen == MAIN) moveMain(0, -1);
                    else if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && screen == MAIN) moveMain(0, 1);
                    else if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == MAIN && mainItems()[Math.min(focus, mainItems().length - 1)] == I_GLASS) {
                        // The glass row is a stepper: ← → change the level (the home screen picks it up on return).
                        int step = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                        Prefs.setGlass(Prefs.glass() + step);
                        invalidate();
                        spoken.focusMoved();
                    } else if (down && screen == MAIN) {
                        moveMain(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, 0);
                        invalidate();
                        spoken.focusMoved();
                    } else if (down && screen == THEME) {
                        themeKey(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, false);
                    } else if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && screen == HOME_SETTINGS) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER && pickFor == PICK_CITY) {
                                focus = mainIndex(I_CITY);
                                screen = MAIN;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else {
                                focus = mainIndex(screen == THEME ? I_THEME : screen == SHELF_EDIT ? I_DOCK : screen == REMOTE_KEYS ? I_KEYS : I_HOME);
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] LANGUAGES = {"auto", "pt", "en", "es", "de", "it", "ru"}, UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark", "scheduled"};
        /** Home Settings: language, temperature, theme, sounds, weather, city, quotes, then one row per coin. */
        private static final int CITY_ROW = 5, YOUTUBE_ROW = 7, COIN_ROWS = 8;
        /** "Scheduled" theme: its two times show right under Theme (display rows 3 and 4; the rest move down by 2). */
        private static final int DARK_ROW = 100, LIGHT_ROW = 101;

        private static boolean scheduled() {
            return Prefs.theme().equals("scheduled");
        }

        /** The row's meaning (the numbers above) from where it is on screen. */
        private static int logical(int display) {
            if (display <= 2 || !scheduled()) return display;
            if (display == 3) return DARK_ROW;
            if (display == 4) return LIGHT_ROW;
            return display - 2;
        }

        /** Where a row is on screen. */
        private static int row(int logical) {
            return logical <= 2 || !scheduled() ? logical : logical + 2;
        }

        private static String hhmm(int minute) {
            return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
        }

        private String youtubeStatus = "";

        private String youtubeValue() {
            if (!YouTubeAuth.configured()) return "—";
            if (YouTubeAuth.linked(getContext())) return Ui.tr("Conectado", "Linked");
            org.json.JSONObject p = YouTubeAuth.pending(getContext());
            if (p != null) return "google.com/device · " + Ui.tr("Código ", "Code ") + p.optString("user_code");
            return youtubeStatus.isEmpty() ? Ui.tr("Não conectado", "Not linked") : youtubeStatus;
        }

        /** OK: a code to type at google.com/device (the home screen polls Google until approved); linked: unlinks. */
        private void linkYouTube() {
            if (!YouTubeAuth.configured()) return;
            if (YouTubeAuth.linked(getContext())) {
                YouTubeAuth.unlink(getContext());
                invalidate();
                return;
            }
            youtubeStatus = Ui.tr("Pedindo o código…", "Getting the code…");
            invalidate();
            final Context c = getContext().getApplicationContext();
            new Thread(() -> {
                String status = "";
                try {
                    if (YouTubeAuth.pending(c) == null) YouTubeAuth.start(c);
                } catch (Exception e) {
                    status = Ui.tr("Erro: ", "Error: ") + e.getMessage();
                }
                final String s = status;
                handler.post(() -> { youtubeStatus = s; invalidate(); });
                // Keeps the row current while the code waits (it turns "Linked" once approved).
                for (int i = 0; i < 180 && !YouTubeAuth.linked(c); i++) {
                    YouTubeAuth.poll(c);
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
                    handler.post(this::invalidate);
                }
                handler.post(this::invalidate);
            }).start();
        }

        private boolean dollar() {
            return "BRL".equalsIgnoreCase(Config.CURRENCY.trim());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Idioma", "Language"));
            l.add(Ui.tr("Temperatura", "Temperature"));
            l.add(Ui.tr("Tema", "Theme"));
            l.add(Ui.tr("Sons de navegação", "Navigation sounds"));
            l.add(Ui.tr("Widget do clima", "Weather widget"));
            l.add(Ui.tr("Cidade", "City"));
            l.add(Ui.tr("Widget de cotações", "Quotes widget"));
            l.add(Ui.tr("Conectar YouTube", "Link YouTube"));
            l.add("Bitcoin");
            l.add("Ethereum");
            l.add("Solana");
            if (dollar()) l.add(Ui.tr("Dólar", "Dollar"));
            if (scheduled()) {
                l.add(3, Ui.tr("Escuro a partir das", "Dark from"));
                l.add(4, Ui.tr("Claro a partir das", "Light from"));
            }
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            int extra = scheduled() ? 2 : 0;
            String[] v = new String[labels.length - extra];   // by meaning; the theme's times go in at the end
            String lang = Prefs.language();
            v[0] = lang.equals("pt") ? "Português" : lang.equals("en") ? "English" : lang.equals("es") ? "Español"
                    : lang.equals("de") ? "Deutsch" : lang.equals("it") ? "Italiano" : lang.equals("ru") ? "Русский" : Ui.tr("Automático", "Automatic");
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark")
                    : theme.equals("scheduled") ? Ui.tr("Programado", "Scheduled") : Ui.tr("Automático", "Automatic");
            v[3] = onOff(Prefs.sounds());
            v[4] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[6] = onOff(Prefs.quotes());
            v[YOUTUBE_ROW] = youtubeValue();
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            if (extra == 0) return v;
            List<String> out = new ArrayList<>(java.util.Arrays.asList(v));
            out.add(3, hhmm(Prefs.darkFrom()));
            out.add(4, hhmm(Prefs.lightFrom()));
            return out.toArray(new String[0]);
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }

        private String[] keyLabels() {
            if (RemoteKeys.VESTEL) return new String[] {Ui.tr("Botão Netflix", "Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Botão Tabii", "Tabii button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("Botão BRAVIA CORE", "BRAVIA CORE button"),
                    Ui.tr("Botão Netflix", "Netflix button"), Ui.tr("Botão Disney+", "Disney+ button"),
                    Ui.tr("Botão Prime Video", "Prime Video button"), Ui.tr("Botão YouTube", "YouTube button"),
                    Ui.tr("Botão Crunchyroll", "Crunchyroll button")};
            return new String[] {Ui.tr("Botão Netflix", "Netflix button"), Ui.tr("Botão YouTube", "YouTube button"),
                    Ui.tr("Botão Prime Video", "Prime Video button"), Ui.tr("Botão TCL Channel", "TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Padrão da Sony", "Sony default") : Ui.tr("Nada", "Nothing"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            int at0 = logical(focus);
            switch (at0) {
                case 0: {
                    int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language());
                    Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.length) % LANGUAGES.length]);
                    break;
                }
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case 3: Prefs.setSounds(!Prefs.sounds()); break;
                case 4: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case DARK_ROW: Prefs.setDarkFrom((Prefs.darkFrom() + dir * 30 + 1440) % 1440); return;
                case LIGHT_ROW: Prefs.setLightFrom((Prefs.lightFrom() + dir * 30 + 1440) % 1440); return;
                case 6: Prefs.setQuotes(!Prefs.quotes()); break;
                case YOUTUBE_ROW: linkYouTube(); return;
                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(at0 - COIN_ROWS, !Prefs.coin(at0 - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (Ui.tr("Procurando…", "Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Digite o nome da cidade", "Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Cidade", "City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Buscar", "Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Procurando…", "Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("Nenhuma cidade com esse nome.", "No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.tr("Não deu para buscar agora: ", "Couldn't search now: ") + e.getMessage();
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            shelfOrder.clear();
            shelfOrder.addAll(HomeApps.shelfPackages(getContext()));
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, fg(0xFFFFFFFF));
            c.drawText(Ui.tr("Prateleira", "Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (listRowH() + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + listRowH() * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? fg(0xF2FFFFFF) : fg(0x80FFFFFF), place >= 0 ? focusInk(0xFF) : focusInk(0x80), f));
                rowValue.setColor(blend(fg(0xB3FFFFFF), focusInk(0xB3), f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("Fora", "Off"), rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            float hy = (LIST_TOP + 20 + rows * (listRowH() + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK põe ou tira da prateleira (até " + HomeApps.MAX_SHELF + "). ← → mudam a ordem.",
                    "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                    "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden."), LIST_X * k + 30 * k, hy, hint);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, fg(0xFFFFFFFF));
            String title = pickFor == PICK_CITY ? Ui.tr("Cidade", "City") : keyLabels()[pickFor - 1];
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (listRowH() + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + listRowH() * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), focusInk(0xFF), f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(fg(0xFFFFFFFF), focusInk(0xFF), f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            float hy = (LIST_TOP + 20 + rows * (listRowH() + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = keys ? keyLabels() : settingLabels();
            String[] values = keys ? keyValues() : settingValues();
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, fg(0xFFFFFFFF));
            c.drawText(keys ? Ui.tr("Botões do Controle", "Remote Buttons") : Ui.tr("Ajustes do Início", "Home Settings"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (listRowH() + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + listRowH() * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), focusInk(0xFF), f));
                boolean locked = !keys && logical(i) >= COIN_ROWS && logical(i) < DARK_ROW && !Prefs.quotes();
                rowValue.setColor(blend(locked ? fg(0x59FFFFFF) : fg(0xB3FFFFFF), locked ? focusInk(0x59) : focusInk(0xB3), f));
                if (locked) rowLabel.setColor(blend(fg(0x73FFFFFF), focusInk(0x73), f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            float hy = (LIST_TOP + 20 + labels.length * (listRowH() + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint);
            if (keys) {
                c.drawText(RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video e Google Play: a própria TV controla esses botões.",
                        "YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube e Prime Video: a própria TV controla esses botões.",
                        "Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Padrão da Sony: o botão abre o mesmo app de sempre.",
                        "Sony default: the button opens what it always did.")
                        : RemoteKeys.supported() ? Ui.tr("Disney e Globoplay: a própria TV controla esses botões.",
                        "Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("Esses são os botões do controle da TCL: nesta TV, os botões funcionam como sempre.",
                        "These are the TCL remote's buttons: on this TV, the buttons work as they always did."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = mainIndex(I_HOME);
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                focus = mainIndex(I_CITY);
                screen = MAIN;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS && logical(focus) == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (screen == THEME) {
                themeKey(0, true);
                return;
            } else {
                switch (mainItems()[Math.min(focus, mainItems().length - 1)]) {
                    case I_HOME:
                        if (busy()) return;
                        screen = HOME_CHOICE;
                        focus = freeActive() ? 0 : 1;
                        break;
                    case I_THEME:
                        screen = THEME;
                        focus = java.util.Arrays.asList(THEME_KEYS).indexOf(Prefs.theme());
                        break;
                    case I_LANG: {
                        int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language());
                        Prefs.setLanguage(LANGUAGES[(at + 1) % LANGUAGES.length]);
                        HomeData.get(c).settingsChanged();
                        invalidate();
                        return;
                    }
                    case I_UNITS: {
                        int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                        Prefs.setTemperature(UNITS[(at + 1) % UNITS.length]);
                        HomeData.get(c).settingsChanged();
                        invalidate();
                        return;
                    }
                    case I_SOUNDS: Prefs.setSounds(!Prefs.sounds()); invalidate(); return;
                    case I_GLASS: Prefs.setGlass((Prefs.glass() + 1) % Prefs.GLASS_LEVELS); invalidate(); return;
                    case I_WEATHER: Prefs.setWeather(!Prefs.weather()); HomeData.get(c).settingsChanged(); invalidate(); return;
                    case I_CITY: askCity(); return;
                    case I_YOUTUBE: linkYouTube(); invalidate(); return;
                    case I_DOCK: openShelfEditor(); break;
                    case I_SAVER:
                        pause();
                        activity.showPreview();
                        break;
                    case I_KEYS:
                        HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                        loadApps();
                        screen = REMOTE_KEYS;
                        focus = 0;
                        break;
                    default:
                        activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
