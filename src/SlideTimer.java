import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stand-alone, fully configurable countdown timer that can be dropped onto any
 * slide — independent of the Quiz feature. Self-contained the same way
 * {@link SlideAnnotation} is: the main app only decides WHEN the timer is on
 * screen and hands this class a slide-relative millisecond clock.
 *
 * <p>The model carries everything the user configures from the "Timer…" box:
 * visual style, position (% of frame), dimensions (% of frame), countdown
 * length, when it starts (slide start / before-or-after a text's audio / an
 * exact time), how long it stays on screen, entrance / idle / exit effects and
 * the tick + finish sounds.
 *
 * <p>Rendering is pure — it never reads global state — so the editor's live
 * preview and the export pipeline draw byte-identical frames for the same
 * millisecond.
 */
public class SlideTimer {

    // =====================================================================
    // Catalogs (drive the dialog's drop-downs)
    // =====================================================================

    /** Every timer look, ordered roughly "most useful first". */
    public static String[] styles() {
        return new String[] {
            "Neon Ring", "Ring Arc", "Segmented Ring", "Radial Sweep",
            "Number Circle", "Glass Capsule", "Minimal Digits", "Digital LCD",
            "Flip Clock", "Analog Clock", "Hourglass",
            "Progress Bar H", "Progress Bar V", "Dot Grid", "Bar Stack"
        };
    }

    public static String[] entranceEffects() {
        return new String[] { "None", "Fade", "Pop", "Scale Up", "Zoom Out",
            "Slide In Top", "Slide In Bottom", "Slide In Left", "Slide In Right",
            "Rotate In", "Bounce", "Flip In", "Blur In" };
    }

    public static String[] exitEffects() {
        return new String[] { "None", "Fade", "Pop Out", "Zoom Out", "Zoom In",
            "Slide Out Top", "Slide Out Bottom", "Slide Out Left", "Slide Out Right",
            "Rotate Out", "Flip Out" };
    }

    public static String[] idleEffects() {
        return new String[] { "None", "Pulse", "Breathe", "Shake", "Bounce",
            "Spin", "Wobble", "Glow Pulse", "Flash" };
    }

    public static String[] idleTriggers() {
        return new String[] { "Each Tick", "Urgent Phase", "Always", "Never" };
    }

    public static String[] easings() {
        return new String[] { "Linear", "Ease In", "Ease Out", "Ease In Out", "Bounce", "Back" };
    }

    /** How the timer's start moment is anchored inside the slide. */
    public static String[] startModes() {
        return new String[] { "Slide Start", "After Text Audio", "Before Text Audio", "At Time" };
    }

    /** Built-in tick beds, synthesized on the fly (no asset files needed). */
    public static String[] tickSounds() {
        return new String[] { "None", "Classic Clock", "Soft Tick", "Digital Beep",
            "Heartbeat", "Suspense Pulse", "Wood Block", "Bomb Fuse", "Sci-Fi Sweep" };
    }

    /** Built-in "time is up" stingers. */
    public static String[] endSounds() {
        return new String[] { "None", "Bell Ding", "Triple Chime", "Gong",
            "Buzzer", "Alarm", "Whoosh", "Success Sparkle" };
    }

    /** Handy countdown lengths offered in the dialog (seconds). */
    public static Integer[] lengthPresets() {
        return new Integer[] { 3, 5, 7, 10, 15, 20, 30, 45, 60, 90, 120 };
    }

    public static String[] digitFormats() {
        return new String[] { "Auto", "Seconds", "MM:SS", "Tenths" };
    }

    // =====================================================================
    // Model
    // =====================================================================

    /** Master switch — nothing is drawn or mixed while this is false. */
    public boolean enabled = false;

    /** One of {@link #styles()}. */
    public String style = "Neon Ring";

    // ---- placement (live-previewed) ----
    /** Timer CENTER as a % of frame width / height. */
    public double xPct = 86;
    public double yPct = 16;
    /** Timer HEIGHT as a % of frame height. */
    public double sizePct = 16;
    /** Timer WIDTH as a % of frame width. 0 = automatic (from the style). */
    public double widthPct = 0;
    /** Extra rotation of the whole widget, in degrees. */
    public double rotationDeg = 0;

    // ---- countdown ----
    /** Countdown length in seconds (3 / 5 / 7 / 10 / 15 / …). */
    public int lengthSec = 10;
    /** Last N seconds switch to the urgent colour (and can trigger effects). */
    public int urgentSec = 3;
    /** Count up (0 → length) instead of down. */
    public boolean countUp = false;
    /** One of {@link #digitFormats()}. */
    public String digitFormat = "Auto";
    public boolean showDigits = true;
    /** Optional caption drawn with the timer (e.g. "Time left"). */
    public String label = "";

    // ---- when it starts ----
    /** One of {@link #startModes()}. */
    public String startMode = "Slide Start";
    /** 1-based text index used by the After / Before Text Audio modes. */
    public int startTextIndex = 1;
    /** Absolute slide-relative time (ms) for the "At Time" mode. */
    public int startAtMs = 0;
    /** Nudge applied to every mode (may be negative). */
    public int startOffsetMs = 0;

    // ---- how long it stays on screen ----
    /** true = sum of this slide's audio lengths + 1 s (the documented default). */
    public boolean autoDisplayDuration = true;
    /** Explicit on-screen time in ms, used when {@link #autoDisplayDuration} is false. */
    public int displayMs = 0;
    /** Hide the widget the moment the countdown reaches zero. */
    public boolean hideWhenFinished = false;
    /** Keep the slide on screen long enough for the timer to finish. */
    public boolean extendSlide = true;

    // ---- look ----
    public Color color       = new Color(64, 214, 255);   // accent / progress
    public Color color2      = new Color(122, 92, 255);   // gradient partner
    public boolean gradient  = true;
    public Color trackColor  = new Color(255, 255, 255, 46);
    public Color textColor   = Color.WHITE;
    public Color plateColor  = new Color(12, 16, 28, 205);  // panel / disc fill
    public Color urgentColor = new Color(255, 74, 92);
    public String fontName   = "Segoe UI";
    public boolean digitBold = true;
    /** Digit size as a % of the automatic size (50..200). */
    public int digitSizePct  = 100;
    public int opacity       = 100;   // 0..100 overall
    public boolean shadow    = true;
    public boolean glow      = true;
    public int glowPct       = 60;    // 0..100 glow spread / strength
    public boolean showPlate = true;  // draw the backing disc / panel
    /** Corner rounding for panel-ish styles, % of the shorter side. */
    public int cornerPct     = 38;

    // ---- effects ----
    public String entrance   = "Pop";
    public String exit       = "Fade";
    public int    entranceMs = 520;
    public int    exitMs     = 420;
    public String idle       = "Pulse";
    public String idleTrigger= "Each Tick";
    public int    effectStrengthPct = 100;   // 0..200
    public String easing     = "Ease Out";

    // ---- sound ----
    /** One of {@link #tickSounds()}; "None" disables the tick bed. */
    public String tickSound = "None";
    /** One of {@link #endSounds()}; plays when the countdown reaches zero. */
    public String endSound  = "Bell Ding";
    /** Optional user file played once at the timer's start (any ffmpeg format). */
    public File customSoundFile = null;
    /** Optional user file played once when the countdown ends. */
    public File customEndSoundFile = null;
    /** 0..100 mix level for every timer sound. */
    public int soundVolumePct = 80;

    public SlideTimer() {}

    public SlideTimer copy() {
        SlideTimer c = new SlideTimer();
        c.enabled = enabled; c.style = style;
        c.xPct = xPct; c.yPct = yPct; c.sizePct = sizePct; c.widthPct = widthPct;
        c.rotationDeg = rotationDeg;
        c.lengthSec = lengthSec; c.urgentSec = urgentSec; c.countUp = countUp;
        c.digitFormat = digitFormat; c.showDigits = showDigits; c.label = label;
        c.startMode = startMode; c.startTextIndex = startTextIndex;
        c.startAtMs = startAtMs; c.startOffsetMs = startOffsetMs;
        c.autoDisplayDuration = autoDisplayDuration; c.displayMs = displayMs;
        c.hideWhenFinished = hideWhenFinished; c.extendSlide = extendSlide;
        c.color = color; c.color2 = color2; c.gradient = gradient;
        c.trackColor = trackColor; c.textColor = textColor; c.plateColor = plateColor;
        c.urgentColor = urgentColor; c.fontName = fontName; c.digitBold = digitBold;
        c.digitSizePct = digitSizePct; c.opacity = opacity; c.shadow = shadow;
        c.glow = glow; c.glowPct = glowPct; c.showPlate = showPlate; c.cornerPct = cornerPct;
        c.entrance = entrance; c.exit = exit; c.entranceMs = entranceMs; c.exitMs = exitMs;
        c.idle = idle; c.idleTrigger = idleTrigger;
        c.effectStrengthPct = effectStrengthPct; c.easing = easing;
        c.tickSound = tickSound; c.endSound = endSound;
        c.customSoundFile = customSoundFile; c.customEndSoundFile = customEndSoundFile;
        c.soundVolumePct = soundVolumePct;
        return c;
    }

    /** Copy every field except {@link #enabled} — used by "apply to all slides". */
    public void copyVisualsFrom(SlideTimer s) {
        if (s == null) return;
        boolean wasEnabled = this.enabled;
        SlideTimer c = s.copy();
        c.enabled = wasEnabled;
        assign(c);
    }

    /** Overwrite every field of this instance from {@code s}. */
    public void assign(SlideTimer s) {
        if (s == null) return;
        enabled = s.enabled; style = s.style;
        xPct = s.xPct; yPct = s.yPct; sizePct = s.sizePct; widthPct = s.widthPct;
        rotationDeg = s.rotationDeg;
        lengthSec = s.lengthSec; urgentSec = s.urgentSec; countUp = s.countUp;
        digitFormat = s.digitFormat; showDigits = s.showDigits; label = s.label;
        startMode = s.startMode; startTextIndex = s.startTextIndex;
        startAtMs = s.startAtMs; startOffsetMs = s.startOffsetMs;
        autoDisplayDuration = s.autoDisplayDuration; displayMs = s.displayMs;
        hideWhenFinished = s.hideWhenFinished; extendSlide = s.extendSlide;
        color = s.color; color2 = s.color2; gradient = s.gradient;
        trackColor = s.trackColor; textColor = s.textColor; plateColor = s.plateColor;
        urgentColor = s.urgentColor; fontName = s.fontName; digitBold = s.digitBold;
        digitSizePct = s.digitSizePct; opacity = s.opacity; shadow = s.shadow;
        glow = s.glow; glowPct = s.glowPct; showPlate = s.showPlate; cornerPct = s.cornerPct;
        entrance = s.entrance; exit = s.exit; entranceMs = s.entranceMs; exitMs = s.exitMs;
        idle = s.idle; idleTrigger = s.idleTrigger;
        effectStrengthPct = s.effectStrengthPct; easing = s.easing;
        tickSound = s.tickSound; endSound = s.endSound;
        customSoundFile = s.customSoundFile; customEndSoundFile = s.customEndSoundFile;
        soundVolumePct = s.soundVolumePct;
    }

    // =====================================================================
    // Timeline helpers
    // =====================================================================

    /** Countdown length in ms (never below 1 s). */
    public long countdownMs() { return Math.max(1, lengthSec) * 1000L; }

    /**
     * How long the widget stays on screen, in ms.
     *
     * @param autoMs the app-computed automatic value — the sum of the slide's
     *               audio lengths + 1 s. Ignored unless
     *               {@link #autoDisplayDuration} is on.
     */
    public long windowMs(long autoMs) {
        if (autoDisplayDuration) {
            return autoMs > 0 ? autoMs : countdownMs();
        }
        return displayMs > 0 ? displayMs : countdownMs();
    }

    /** True when this timer has anything to mix into the exported audio. */
    public boolean hasSound() {
        return enabled && soundVolumePct > 0
                && ((tickSound != null && !"None".equals(tickSound))
                 || (endSound  != null && !"None".equals(endSound))
                 || customSoundFile != null || customEndSoundFile != null);
    }

    // =====================================================================
    // Font registry — mirrors the app's loaded .ttf files so the timer can use
    // the very same families the slide texts use.
    // =====================================================================

    private static final Map<String, Font> FONTS = new LinkedHashMap<>();

    /** Called once by the app after it loads its bundled .ttf files. */
    public static synchronized void registerFonts(Map<String, Font> loaded) {
        if (loaded == null) return;
        FONTS.clear();
        FONTS.putAll(loaded);
    }

    private static Font font(String name, int style, double sizePx) {
        float sz = (float) Math.max(6.0, sizePx);
        Font base;
        synchronized (SlideTimer.class) { base = name == null ? null : FONTS.get(name); }
        if (base != null) return base.deriveFont(style, sz);
        return new Font(name == null || name.isEmpty() ? "Segoe UI" : name, style, (int) sz)
                .deriveFont(style, sz);
    }

    // =====================================================================
    // Painting
    // =====================================================================

    /**
     * Composite the timer onto an already-rendered frame.
     *
     * @param sinceStartMs milliseconds since the timer's start moment. Negative
     *                     means the timer has not started yet — nothing drawn.
     * @param windowMs     how long the timer stays on screen (see {@link #windowMs}).
     */
    public static void paint(BufferedImage frame, SlideTimer t, long sinceStartMs, long windowMs) {
        if (frame == null || t == null || !t.enabled) return;
        if (sinceStartMs < 0) return;
        if (windowMs <= 0) windowMs = t.countdownMs();
        if (sinceStartMs > windowMs) return;
        long cd = t.countdownMs();
        if (t.hideWhenFinished && sinceStartMs > cd) return;
        drawInternal(frame, t, sinceStartMs, windowMs, false);
    }

    /**
     * Editor preview: same renderer, but the widget is always fully visible
     * (entrance / exit alpha are not applied at the very edges) so the user can
     * position and size it comfortably.
     */
    public static void paintPreview(BufferedImage frame, SlideTimer t, long sinceStartMs, long windowMs) {
        if (frame == null || t == null || !t.enabled) return;
        if (windowMs <= 0) windowMs = t.countdownMs();
        long s = Math.max(0, Math.min(windowMs, sinceStartMs));
        drawInternal(frame, t, s, windowMs, true);
    }

    /** Static, timing-free preview: the timer frozen at its full value. */
    public static void paintStatic(BufferedImage frame, SlideTimer t) {
        if (frame == null || t == null || !t.enabled) return;
        drawInternal(frame, t, 0L, t.countdownMs(), true);
    }

    // ---------------------------------------------------------------------

    private static void drawInternal(BufferedImage frame, SlideTimer t,
                                     long sinceStartMs, long windowMs, boolean preview) {
        int w = frame.getWidth(), h = frame.getHeight();
        if (w <= 0 || h <= 0) return;

        long cd = t.countdownMs();
        long clamped = Math.max(0, Math.min(cd, sinceStartMs));
        // progress 0 → 1 across the countdown (always "how much has elapsed")
        double progress = cd <= 0 ? 1.0 : clamped / (double) cd;
        long remainMs = Math.max(0, cd - clamped);
        int remainSec = (int) Math.ceil(remainMs / 1000.0);
        if (remainMs > 0 && remainSec < 1) remainSec = 1;
        boolean urgent = remainSec <= Math.max(0, t.urgentSec) && remainMs > 0;
        boolean done = remainMs <= 0;

        // ---- alpha & transform from entrance / exit / idle ----
        double alpha = Math.max(0, Math.min(100, t.opacity)) / 100.0;
        Fx fx = new Fx();
        if (!preview) {
            applyEntrance(t, sinceStartMs, fx);
            applyExit(t, sinceStartMs, windowMs, fx);
        }
        applyIdle(t, sinceStartMs, urgent, done, fx);
        alpha *= fx.alpha;
        if (alpha <= 0.004) return;

        Geo geo = geometry(t, w, h);
        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));

            AffineTransform saved = g.getTransform();
            g.translate(geo.cx + fx.dx * geo.h, geo.cy + fx.dy * geo.h);
            if (Math.abs(t.rotationDeg) > 0.01 || Math.abs(fx.rotate) > 0.0001) {
                g.rotate(Math.toRadians(t.rotationDeg) + fx.rotate);
            }
            if (Math.abs(fx.scaleX - 1) > 0.0001 || Math.abs(fx.scaleY - 1) > 0.0001) {
                g.scale(fx.scaleX, fx.scaleY);
            }
            g.translate(-geo.cx, -geo.cy);

            Ctx c = new Ctx();
            c.t = t; c.g = g; c.geo = geo; c.progress = progress;
            c.remainMs = remainMs; c.remainSec = remainSec;
            c.urgent = urgent; c.done = done; c.sinceStartMs = sinceStartMs;
            c.accent = urgent ? nn(t.urgentColor, new Color(255, 74, 92)) : nn(t.color, new Color(64, 214, 255));
            c.accent2 = urgent ? mix(nn(t.urgentColor, new Color(255, 74, 92)), new Color(255, 170, 60), 0.45)
                               : nn(t.color2, c.accent);
            c.text = urgent ? lighten(nn(t.urgentColor, Color.WHITE), 0.55) : nn(t.textColor, Color.WHITE);
            c.glowBoost = fx.glowBoost;

            String style = t.style == null ? "Neon Ring" : t.style;
            switch (style) {
                case "Ring Arc":        drawRingArc(c, false); break;
                case "Neon Ring":       drawRingArc(c, true);  break;
                case "Segmented Ring":  drawSegmentedRing(c);  break;
                case "Radial Sweep":    drawRadialSweep(c);    break;
                case "Number Circle":   drawNumberCircle(c);   break;
                case "Glass Capsule":   drawGlassCapsule(c);   break;
                case "Minimal Digits":  drawMinimalDigits(c);  break;
                case "Digital LCD":     drawDigitalLcd(c);     break;
                case "Flip Clock":      drawFlipClock(c);      break;
                case "Analog Clock":    drawAnalogClock(c);    break;
                case "Hourglass":       drawHourglass(c);      break;
                case "Progress Bar H":  drawProgressBar(c, true);  break;
                case "Progress Bar V":  drawProgressBar(c, false); break;
                case "Dot Grid":        drawDotGrid(c);        break;
                case "Bar Stack":       drawBarStack(c);       break;
                default:                drawRingArc(c, true);  break;
            }
            drawLabel(c);
            g.setTransform(saved);
        } finally {
            g.dispose();
        }
    }

    // ---------------------------------------------------------------------
    // Geometry / context
    // ---------------------------------------------------------------------

    /** Resolved pixel box for the widget. */
    private static final class Geo {
        double cx, cy;      // centre
        double w, h;        // box size in px
        double frameW, frameH;
        double left()   { return cx - w / 2.0; }
        double top()    { return cy - h / 2.0; }
        double min()    { return Math.min(w, h); }
    }

    private static final class Ctx {
        SlideTimer t; Graphics2D g; Geo geo;
        double progress; long remainMs; int remainSec; long sinceStartMs;
        boolean urgent, done;
        Color accent, accent2, text;
        double glowBoost = 1.0;
    }

    /** Natural width : height ratio per style when the user leaves width on auto. */
    private static double autoAspect(String style) {
        if (style == null) return 1.0;
        switch (style) {
            case "Glass Capsule":  return 2.45;
            case "Minimal Digits": return 2.10;
            case "Digital LCD":    return 2.30;
            case "Flip Clock":     return 2.05;
            case "Progress Bar H": return 6.50;
            case "Progress Bar V": return 0.22;
            case "Dot Grid":       return 2.60;
            case "Bar Stack":      return 1.70;
            case "Hourglass":      return 0.72;
            default:               return 1.0;   // circular styles
        }
    }

    private static Geo geometry(SlideTimer t, int w, int h) {
        Geo geo = new Geo();
        geo.frameW = w; geo.frameH = h;
        double hh = Math.max(4.0, h * clamp(t.sizePct, 1, 200) / 100.0);
        double ww = t.widthPct > 0 ? Math.max(4.0, w * clamp(t.widthPct, 1, 200) / 100.0)
                                   : hh * autoAspect(t.style);
        geo.h = hh; geo.w = ww;
        geo.cx = w * clamp(t.xPct, -20, 120) / 100.0;
        geo.cy = h * clamp(t.yPct, -20, 120) / 100.0;
        return geo;
    }

    // ---------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------

    private static final class Fx {
        double alpha = 1.0, scaleX = 1.0, scaleY = 1.0, rotate = 0.0;
        double dx = 0.0, dy = 0.0;      // in units of the widget height
        double glowBoost = 1.0;
    }

    private static void applyEntrance(SlideTimer t, long since, Fx fx) {
        String e = t.entrance == null ? "None" : t.entrance;
        int dur = Math.max(0, t.entranceMs);
        if ("None".equals(e) || dur <= 0 || since >= dur) return;
        double p = ease(t.easing, since / (double) dur);
        double inv = 1.0 - p;
        switch (e) {
            case "Fade":            fx.alpha *= p; break;
            case "Pop":             fx.alpha *= Math.min(1, p * 1.6);
                                    { double s = 0.55 + 0.45 * back(p); fx.scaleX *= s; fx.scaleY *= s; } break;
            case "Scale Up":        fx.alpha *= p; fx.scaleX *= 0.4 + 0.6 * p; fx.scaleY *= 0.4 + 0.6 * p; break;
            case "Zoom Out":        fx.alpha *= p; fx.scaleX *= 1.9 - 0.9 * p; fx.scaleY *= 1.9 - 0.9 * p; break;
            case "Slide In Top":    fx.alpha *= Math.min(1, p * 1.5); fx.dy -= 2.2 * inv; break;
            case "Slide In Bottom": fx.alpha *= Math.min(1, p * 1.5); fx.dy += 2.2 * inv; break;
            case "Slide In Left":   fx.alpha *= Math.min(1, p * 1.5); fx.dx -= 3.0 * inv; break;
            case "Slide In Right":  fx.alpha *= Math.min(1, p * 1.5); fx.dx += 3.0 * inv; break;
            case "Rotate In":       fx.alpha *= p; fx.rotate -= Math.PI * inv;
                                    fx.scaleX *= 0.6 + 0.4 * p; fx.scaleY *= 0.6 + 0.4 * p; break;
            case "Bounce":          fx.alpha *= Math.min(1, p * 2.0); fx.dy -= 1.6 * (1.0 - bounce(p)); break;
            case "Flip In":         fx.alpha *= p; fx.scaleX *= Math.max(0.04, Math.sin(p * Math.PI / 2)); break;
            case "Blur In":         fx.alpha *= p * p;
                                    { double s = 1.35 - 0.35 * p; fx.scaleX *= s; fx.scaleY *= s; } break;
            default: break;
        }
    }

    private static void applyExit(SlideTimer t, long since, long windowMs, Fx fx) {
        String e = t.exit == null ? "None" : t.exit;
        int dur = Math.max(0, t.exitMs);
        if ("None".equals(e) || dur <= 0) return;
        long start = windowMs - dur;
        if (since < start) return;
        double p = ease(t.easing, (since - start) / (double) dur);   // 0 → 1 leaving
        double inv = 1.0 - p;
        switch (e) {
            case "Fade":             fx.alpha *= inv; break;
            case "Pop Out":          fx.alpha *= inv; { double s = 1.0 + 0.35 * p; fx.scaleX *= s; fx.scaleY *= s; } break;
            case "Zoom Out":         fx.alpha *= inv; { double s = 1.0 + 0.9 * p; fx.scaleX *= s; fx.scaleY *= s; } break;
            case "Zoom In":          fx.alpha *= inv; { double s = 1.0 - 0.7 * p; fx.scaleX *= s; fx.scaleY *= s; } break;
            case "Slide Out Top":    fx.alpha *= inv; fx.dy -= 2.2 * p; break;
            case "Slide Out Bottom": fx.alpha *= inv; fx.dy += 2.2 * p; break;
            case "Slide Out Left":   fx.alpha *= inv; fx.dx -= 3.0 * p; break;
            case "Slide Out Right":  fx.alpha *= inv; fx.dx += 3.0 * p; break;
            case "Rotate Out":       fx.alpha *= inv; fx.rotate += Math.PI * p;
                                     fx.scaleX *= 1.0 - 0.4 * p; fx.scaleY *= 1.0 - 0.4 * p; break;
            case "Flip Out":         fx.alpha *= inv; fx.scaleX *= Math.max(0.04, Math.cos(p * Math.PI / 2)); break;
            default: break;
        }
    }

    private static void applyIdle(SlideTimer t, long since, boolean urgent, boolean done, Fx fx) {
        String anim = t.idle == null ? "None" : t.idle;
        if ("None".equals(anim)) return;
        String trig = t.idleTrigger == null ? "Each Tick" : t.idleTrigger;
        boolean active;
        switch (trig) {
            case "Never":        active = false; break;
            case "Always":       active = true;  break;
            case "Urgent Phase": active = urgent; break;
            case "Each Tick":
            default:             active = !done; break;
        }
        if (!active) return;

        double amp = clamp(t.effectStrengthPct, 0, 200) / 100.0;
        if (amp <= 0.0001) return;

        // 0 at each second boundary, → 1 just before the next one.
        double secFrac = (since % 1000L) / 1000.0;
        double phase = "Each Tick".equals(trig) ? (secFrac < 0.34 ? secFrac / 0.34 : 1.0) : secFrac;
        double eased = ease(t.easing, phase);
        double kick = 1.0 - eased;                 // 1 right at the tick, decaying
        double cont = since / 1000.0;              // continuous seconds

        switch (anim) {
            case "Pulse":    { double s = 1.0 + 0.11 * amp * kick; fx.scaleX *= s; fx.scaleY *= s; } break;
            case "Breathe":  { double s = 1.0 + 0.05 * amp * Math.sin(cont * Math.PI); fx.scaleX *= s; fx.scaleY *= s; } break;
            case "Shake":    fx.dx += Math.sin(cont * 21.0) * 0.05 * amp;
                             fx.dy += Math.cos(cont * 17.0) * 0.04 * amp; break;
            case "Bounce":   fx.dy -= 0.14 * amp * kick; break;
            case "Spin":     fx.rotate += cont * 0.55 * amp; break;
            case "Wobble":   fx.rotate += Math.sin(cont * 6.0) * 0.10 * amp; break;
            case "Glow Pulse": fx.glowBoost = 1.0 + 1.5 * amp * (0.5 + 0.5 * Math.sin(cont * Math.PI * 2)); break;
            case "Flash":    fx.alpha *= 1.0 - 0.45 * amp * kick; break;
            default: break;
        }
    }

    private static double ease(String name, double t) {
        double x = t < 0 ? 0 : (t > 1 ? 1 : t);
        if (name == null) name = "Ease Out";
        switch (name) {
            case "Linear":      return x;
            case "Ease In":     return x * x;
            case "Ease In Out": return x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2.0;
            case "Bounce":      return bounce(x);
            case "Back":        return back(x);
            case "Ease Out":
            default:            return 1 - (1 - x) * (1 - x);
        }
    }

    private static double bounce(double x) {
        double n1 = 7.5625, d1 = 2.75;
        if (x < 1 / d1)        return n1 * x * x;
        else if (x < 2 / d1)   return n1 * (x -= 1.5 / d1) * x + 0.75;
        else if (x < 2.5 / d1) return n1 * (x -= 2.25 / d1) * x + 0.9375;
        else                   return n1 * (x -= 2.625 / d1) * x + 0.984375;
    }

    private static double back(double x) {
        double c1 = 1.70158, c3 = c1 + 1;
        return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
    }

    // ---------------------------------------------------------------------
    // Small colour / shape helpers
    // ---------------------------------------------------------------------

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    private static Color nn(Color c, Color fallback) { return c != null ? c : fallback; }

    private static Color alpha(Color c, double a) {
        if (c == null) return null;
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                clamp255((int) Math.round(clamp(a, 0, 1) * c.getAlpha())));
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp255(a));
    }

    private static Color lighten(Color c, double amt) {
        return new Color(clamp255((int) (c.getRed()   + (255 - c.getRed())   * amt)),
                         clamp255((int) (c.getGreen() + (255 - c.getGreen()) * amt)),
                         clamp255((int) (c.getBlue()  + (255 - c.getBlue())  * amt)),
                         c.getAlpha());
    }

    private static Color darken(Color c, double amt) {
        double k = 1.0 - clamp(amt, 0, 1);
        return new Color(clamp255((int) (c.getRed() * k)),
                         clamp255((int) (c.getGreen() * k)),
                         clamp255((int) (c.getBlue() * k)), c.getAlpha());
    }

    private static Color mix(Color a, Color b, double f) {
        double k = clamp(f, 0, 1);
        return new Color(clamp255((int) (a.getRed()   * (1 - k) + b.getRed()   * k)),
                         clamp255((int) (a.getGreen() * (1 - k) + b.getGreen() * k)),
                         clamp255((int) (a.getBlue()  * (1 - k) + b.getBlue()  * k)),
                         clamp255((int) (a.getAlpha() * (1 - k) + b.getAlpha() * k)));
    }

    /** Accent paint (flat or a tasteful two-stop gradient across the box). */
    private static Paint accentPaint(Ctx c, Rectangle2D box) {
        if (!c.t.gradient || c.accent2 == null || c.accent.equals(c.accent2)) return c.accent;
        return new GradientPaint((float) box.getMinX(), (float) box.getMinY(), c.accent,
                                 (float) box.getMaxX(), (float) box.getMaxY(), c.accent2);
    }

    /** Soft drop shadow under a shape. */
    private static void shadow(Ctx c, Shape s) {
        if (!c.t.shadow) return;
        Graphics2D g = c.g;
        double d = Math.max(1.0, c.geo.min() * 0.03);
        for (int i = 4; i >= 1; i--) {
            g.setColor(new Color(0, 0, 0, 16));
            AffineTransform sv = g.getTransform();
            g.translate(d * 0.35, d * 0.55);
            g.setStroke(new BasicStroke((float) (i * d * 0.5), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
            g.setTransform(sv);
        }
    }

    /** Outer glow around a stroked shape. */
    private static void glowStroke(Ctx c, Shape s, Color col, double baseW) {
        if (!c.t.glow || c.t.glowPct <= 0) return;
        Graphics2D g = c.g;
        double strength = clamp(c.t.glowPct, 0, 100) / 100.0 * c.glowBoost;
        int layers = 5;
        for (int i = layers; i >= 1; i--) {
            double f = i / (double) layers;
            float sw = (float) (baseW * (1.0 + 2.6 * f * strength));
            int a = (int) (30 * strength * (1.0 - f) + 8 * strength);
            g.setColor(withAlpha(col, clamp255(a)));
            g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
        }
    }

    /** Outer glow around a filled shape. */
    private static void glowFill(Ctx c, Shape s, Color col) {
        if (!c.t.glow || c.t.glowPct <= 0) return;
        Graphics2D g = c.g;
        double strength = clamp(c.t.glowPct, 0, 100) / 100.0 * c.glowBoost;
        double base = c.geo.min() * 0.02;
        for (int i = 5; i >= 1; i--) {
            g.setColor(withAlpha(col, clamp255((int) (16 * strength))));
            g.setStroke(new BasicStroke((float) (base * i * (1 + strength)),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
        }
    }

    private static RoundRectangle2D roundRect(double x, double y, double w, double h, double r) {
        return new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2);
    }

    /** Frosted-glass backing panel: dark fill, subtle top sheen, hairline border. */
    private static void glassPanel(Ctx c, Shape s) {
        Graphics2D g = c.g;
        Rectangle2D b = s.getBounds2D();
        shadow(c, s);
        g.setPaint(new GradientPaint((float) b.getMinX(), (float) b.getMinY(),
                lighten(nn(c.t.plateColor, new Color(12, 16, 28, 205)), 0.10),
                (float) b.getMinX(), (float) b.getMaxY(),
                darken(nn(c.t.plateColor, new Color(12, 16, 28, 205)), 0.25)));
        g.fill(s);
        // top sheen
        Area sheen = new Area(s);
        sheen.intersect(new Area(new Rectangle2D.Double(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight() * 0.46)));
        g.setPaint(new GradientPaint((float) b.getMinX(), (float) b.getMinY(), new Color(255, 255, 255, 42),
                (float) b.getMinX(), (float) (b.getMinY() + b.getHeight() * 0.46), new Color(255, 255, 255, 0)));
        g.fill(sheen);
        g.setStroke(new BasicStroke((float) Math.max(1.0, c.geo.min() * 0.012)));
        g.setColor(new Color(255, 255, 255, 46));
        g.draw(s);
    }

    private static void drawTextCentered(Ctx c, String s, Font f, Color col, double cx, double cy) {
        if (s == null || s.isEmpty()) return;
        Graphics2D g = c.g;
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        float tx = (float) (cx - fm.stringWidth(s) / 2.0);
        float ty = (float) (cy + (fm.getAscent() - fm.getDescent()) / 2.0);
        if (c.t.shadow) {
            g.setColor(new Color(0, 0, 0, 130));
            g.drawString(s, tx + Math.max(1f, f.getSize2D() * 0.035f), ty + Math.max(1f, f.getSize2D() * 0.045f));
        }
        if (c.t.glow && c.t.glowPct > 0) {
            g.setColor(withAlpha(col, clamp255((int) (46 * clamp(c.t.glowPct, 0, 100) / 100.0 * c.glowBoost))));
            for (int i = 1; i <= 2; i++) {
                g.drawString(s, tx - i * 0.6f, ty);
                g.drawString(s, tx + i * 0.6f, ty);
            }
        }
        g.setColor(col);
        g.drawString(s, tx, ty);
    }

    /** The countdown text for the current moment, honouring the chosen format. */
    private static String digits(Ctx c) {
        SlideTimer t = c.t;
        long ms = t.countUp ? (c.t.countdownMs() - c.remainMs) : c.remainMs;
        int totalSec = (int) Math.ceil(ms / 1000.0);
        if (t.countUp) totalSec = (int) (ms / 1000L);
        String fmt = t.digitFormat == null ? "Auto" : t.digitFormat;
        if ("Auto".equals(fmt)) fmt = t.lengthSec >= 60 ? "MM:SS" : "Seconds";
        switch (fmt) {
            case "MM:SS":  return String.format("%d:%02d", totalSec / 60, totalSec % 60);
            case "Tenths": return String.format(java.util.Locale.US, "%.1f", ms / 1000.0);
            case "Seconds":
            default:       return String.valueOf(totalSec);
        }
    }

    private static Font digitFont(Ctx c, double naturalSize) {
        double sz = naturalSize * clamp(c.t.digitSizePct, 30, 250) / 100.0;
        return font(c.t.fontName, c.t.digitBold ? Font.BOLD : Font.PLAIN, sz);
    }

    private static void drawLabel(Ctx c) {
        String s = c.t.label;
        if (s == null || s.trim().isEmpty()) return;
        Geo geo = c.geo;
        Font f = font(c.t.fontName, Font.BOLD, geo.h * 0.16);
        Graphics2D g = c.g;
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        double ty = geo.top() - fm.getDescent() - geo.h * 0.06;
        drawTextCentered(c, s.trim(), f, withAlpha(nn(c.t.textColor, Color.WHITE), 225), geo.cx, ty);
    }

    // =====================================================================
    // Style renderers
    // =====================================================================

    /** Fraction of the ring/bar that should still be "full". */
    private static double fill(Ctx c) {
        return c.t.countUp ? clamp(c.progress, 0, 1) : clamp(1.0 - c.progress, 0, 1);
    }

    // ---- 1/2. Ring Arc & Neon Ring --------------------------------------
    private static void drawRingArc(Ctx c, boolean neon) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        double d = Math.min(geo.w, geo.h);
        double r = d / 2.0;
        double sw = Math.max(2.0, d * (neon ? 0.095 : 0.085));
        double rr = r - sw / 2.0 - d * 0.02;
        Ellipse2D circle = new Ellipse2D.Double(geo.cx - rr, geo.cy - rr, rr * 2, rr * 2);

        if (c.t.showPlate) {
            Ellipse2D plate = new Ellipse2D.Double(geo.cx - r, geo.cy - r, r * 2, r * 2);
            shadow(c, plate);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(geo.cx, geo.cy - r * 0.3), (float) r,
                    new float[] { 0f, 1f },
                    new Color[] { lighten(nn(c.t.plateColor, new Color(12, 16, 28, 205)), 0.14),
                                  darken(nn(c.t.plateColor, new Color(12, 16, 28, 205)), 0.20) }));
            g.fill(plate);
        }

        // track
        g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(nn(c.t.trackColor, new Color(255, 255, 255, 46)));
        g.draw(circle);

        double frac = fill(c);
        if (frac > 0.0005) {
            Arc2D arc = new Arc2D.Double(geo.cx - rr, geo.cy - rr, rr * 2, rr * 2,
                    90, -360.0 * frac, Arc2D.OPEN);
            if (neon) glowStroke(c, arc, c.accent, sw);
            g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setPaint(accentPaint(c, new Rectangle2D.Double(geo.cx - rr, geo.cy - rr, rr * 2, rr * 2)));
            g.draw(arc);

            if (neon) {   // bright head dot riding the arc tip
                double ang = Math.toRadians(90 - 360.0 * frac);
                double hx = geo.cx + Math.cos(ang) * rr;
                double hy = geo.cy - Math.sin(ang) * rr;
                double hr = sw * 0.62;
                Ellipse2D head = new Ellipse2D.Double(hx - hr, hy - hr, hr * 2, hr * 2);
                glowFill(c, head, c.accent);
                g.setColor(lighten(c.accent, 0.55));
                g.fill(head);
            }
        }

        if (c.t.showDigits) {
            drawTextCentered(c, digits(c), digitFont(c, d * 0.40), c.text, geo.cx, geo.cy);
        }
    }

    // ---- 3. Segmented Ring ----------------------------------------------
    private static void drawSegmentedRing(Ctx c) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        double d = Math.min(geo.w, geo.h);
        double r = d / 2.0;
        int segs = Math.max(6, Math.min(60, c.t.lengthSec));
        double sw = Math.max(2.0, d * 0.11);
        double rr = r - sw / 2.0 - d * 0.02;
        double gapDeg = Math.min(6.0, 240.0 / segs);
        double stepDeg = 360.0 / segs;
        double frac = fill(c);
        int lit = (int) Math.ceil(frac * segs - 1e-6);

        if (c.t.showPlate) {
            Ellipse2D plate = new Ellipse2D.Double(geo.cx - r, geo.cy - r, r * 2, r * 2);
            shadow(c, plate);
            g.setColor(nn(c.t.plateColor, new Color(12, 16, 28, 205)));
            g.fill(plate);
        }

        g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        for (int i = 0; i < segs; i++) {
            double start = 90 - i * stepDeg - gapDeg / 2.0;
            Arc2D a = new Arc2D.Double(geo.cx - rr, geo.cy - rr, rr * 2, rr * 2,
                    start, -(stepDeg - gapDeg), Arc2D.OPEN);
            boolean on = i < lit;
            if (on) {
                if (i == lit - 1) glowStroke(c, a, c.accent, sw);
                g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g.setColor(c.t.gradient ? mix(c.accent, c.accent2, i / (double) Math.max(1, segs - 1)) : c.accent);
            } else {
                g.setColor(nn(c.t.trackColor, new Color(255, 255, 255, 46)));
            }
            g.draw(a);
        }
        if (c.t.showDigits) {
            drawTextCentered(c, digits(c), digitFont(c, d * 0.38), c.text, geo.cx, geo.cy);
        }
    }

    // ---- 4. Radial Sweep (pie) ------------------------------------------
    private static void drawRadialSweep(Ctx c) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        double d = Math.min(geo.w, geo.h);
        double r = d / 2.0 - d * 0.03;
        Ellipse2D disc = new Ellipse2D.Double(geo.cx - r, geo.cy - r, r * 2, r * 2);
        shadow(c, disc);
        g.setColor(c.t.showPlate ? nn(c.t.plateColor, new Color(12, 16, 28, 205))
                                 : nn(c.t.trackColor, new Color(255, 255, 255, 46)));
        g.fill(disc);

        double frac = fill(c);
        if (frac > 0.0005) {
            Arc2D pie = new Arc2D.Double(geo.cx - r, geo.cy - r, r * 2, r * 2,
                    90, -360.0 * frac, Arc2D.PIE);
            glowFill(c, pie, c.accent);
            g.setPaint(accentPaint(c, disc.getBounds2D()));
            g.fill(pie);
        }
        g.setStroke(new BasicStroke((float) Math.max(1.5, d * 0.02)));
        g.setColor(new Color(255, 255, 255, 70));
        g.draw(disc);

        if (c.t.showDigits) {
            double ir = r * 0.58;
            Ellipse2D hole = new Ellipse2D.Double(geo.cx - ir, geo.cy - ir, ir * 2, ir * 2);
            g.setColor(darken(nn(c.t.plateColor, new Color(12, 16, 28, 230)), 0.10));
            g.fill(hole);
            drawTextCentered(c, digits(c), digitFont(c, d * 0.34), c.text, geo.cx, geo.cy);
        }
    }

    // ---- 5. Number Circle ------------------------------------------------
    private static void drawNumberCircle(Ctx c) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        double d = Math.min(geo.w, geo.h);
        double r = d / 2.0 - d * 0.04;
        Ellipse2D disc = new Ellipse2D.Double(geo.cx - r, geo.cy - r, r * 2, r * 2);
        shadow(c, disc);
        glowFill(c, disc, c.accent);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(geo.cx - r * 0.3, geo.cy - r * 0.45),
                (float) (r * 1.6), new float[] { 0f, 1f },
                new Color[] { lighten(c.accent, 0.35), darken(c.t.gradient ? c.accent2 : c.accent, 0.25) }));
        g.fill(disc);
        // glossy highlight
        Area gloss = new Area(disc);
        gloss.intersect(new Area(new Ellipse2D.Double(geo.cx - r * 1.1, geo.cy - r * 1.85, r * 2.2, r * 2.0)));
        g.setPaint(new GradientPaint((float) (geo.cy - r), (float) (geo.cy - r), new Color(255, 255, 255, 90),
                (float) geo.cx, (float) geo.cy, new Color(255, 255, 255, 0)));
        g.fill(gloss);
        g.setStroke(new BasicStroke((float) Math.max(1.5, d * 0.025)));
        g.setColor(new Color(255, 255, 255, 120));
        g.draw(disc);
        if (c.t.showDigits) {
            drawTextCentered(c, digits(c), digitFont(c, d * 0.50), c.text, geo.cx, geo.cy);
        }
    }

    // ---- 6. Glass Capsule ------------------------------------------------
    private static void drawGlassCapsule(Ctx c) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        double w = geo.w, h = geo.h;
        double x = geo.left(), y = geo.top();
        double rad = h * clamp(c.t.cornerPct, 0, 100) / 100.0;
        RoundRectangle2D panel = roundRect(x, y, w, h, Math.min(rad, h / 2.0));
        if (c.t.showPlate) glassPanel(c, panel);

        // progress underline inside the capsule
        double padX = h * 0.22, barH = Math.max(2.0, h * 0.10);
        double barY = y + h - padX * 0.55 - barH;
        double barW = w - padX * 2;
        RoundRectangle2D track = roundRect(x + padX, barY, barW, barH, barH / 2);
        g.setColor(nn(c.t.trackColor, new Color(255, 255, 255, 46)));
        g.fill(track);
        double frac = fill(c);
        if (frac > 0.001) {
            RoundRectangle2D fillBar = roundRect(x + padX, barY, Math.max(barH, barW * frac), barH, barH / 2);
            glowFill(c, fillBar, c.accent);
            g.setPaint(accentPaint(c, new Rectangle2D.Double(x + padX, barY, barW, barH)));
            g.fill(fillBar);
        }

        // clock glyph + digits
        double glyphR = h * 0.24;
        double glyphCx = x + padX + glyphR;
        double glyphCy = y + h * 0.42;
        drawClockGlyph(c, glyphCx, glyphCy, glyphR, c.accent);
        if (c.t.showDigits) {
            double textLeft = glyphCx + glyphR * 1.35;
            double textRight = x + w - padX;
            drawTextCentered(c, digits(c), digitFont(c, h * 0.46), c.text,
                    (textLeft + textRight) / 2.0, glyphCy);
        }
    }

    /** Minimal clock icon used as an accent by several styles. */
    private static void drawClockGlyph(Ctx c, double cx, double cy, double r, Color col) {
        Graphics2D g = c.g;
        g.setStroke(new BasicStroke((float) Math.max(1.2, r * 0.16), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Ellipse2D e = new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
        glowStroke(c, e, col, Math.max(1.2, r * 0.16));
        g.setStroke(new BasicStroke((float) Math.max(1.2, r * 0.16), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(col);
        g.draw(e);
        double ang = -Math.PI / 2 + c.progress * Math.PI * 2;
        g.draw(new java.awt.geom.Line2D.Double(cx, cy, cx, cy - r * 0.55));
        g.draw(new java.awt.geom.Line2D.Double(cx, cy,
                cx + Math.cos(ang) * r * 0.72, cy + Math.sin(ang) * r * 0.72));
    }

    // ---- 7. Minimal Digits ----------------------------------------------
    private static void drawMinimalDigits(Ctx c) {
        Geo geo = c.geo;
        Graphics2D g = c.g;
        if (c.t.showPlate) {
            RoundRectangle2D panel = roundRect(geo.left(), geo.top(), geo.w, geo.h,
                    geo.h * clamp(c.t.cornerPct, 0, 100) / 100.0 * 0.5);
            glassPanel(c, panel);
        }
        String s = digits(c);
        Font f = digitFont(c, geo.h * 0.72);
        // Accent underline that shrinks with the countdown — subtle, editorial.
        double frac = fill(c);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        double tw = Math.max(fm.stringWidth(s), geo.w * 0.35);
        double uy = geo.cy + geo.h * 0.34;
        double uh = Math.max(2.0, geo.h * 0.055);
        RoundRectangle2D track = roundRect(geo.cx - tw / 2, uy, tw, uh, uh / 2);
        g.setColor(nn(c.t.trackColor, new Color(255, 255, 255, 46)));
        g.fill(track);
        if (frac > 0.001) {
            RoundRectangle2D bar = roundRect(geo.cx - tw / 2, uy, Math.max(uh, tw * frac), uh, uh / 2);
            glowFill(c, bar, c.accent);
            g.setPaint(accentPaint(c, track.getBounds2D()));
            g.fill(bar);
        }
        if (c.t.showDigits) drawTextCentered(c, s, f, c.text, geo.cx, geo.cy - geo.h * 0.06);
    }

    // ---- 8. Digital LCD --------------------------------------------------
    private static void drawDigitalLcd(Ctx c) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        RoundRectangle2D panel = roundRect(geo.left(), geo.top(), geo.w, geo.h, geo.h * 0.18);
        if (c.t.showPlate) {
            shadow(c, panel);
            g.setPaint(new GradientPaint((float) geo.left(), (float) geo.top(), new Color(10, 14, 12, 232),
                    (float) geo.left(), (float) (geo.top() + geo.h), new Color(4, 8, 8, 238)));
            g.fill(panel);
            g.setStroke(new BasicStroke((float) Math.max(1.0, geo.h * 0.03)));
            g.setColor(new Color(255, 255, 255, 40));
            g.draw(panel);
        }
        String s = digits(c);
        // A real 7-segment glyph is much taller than it is wide, so the cell
        // grid is built from a fixed aspect and then centred in the panel —
        // that keeps the display crisp at any width the user dials in.
        double availW = geo.w * 0.84, availH = geo.h * 0.62;
        double ch = availH;
        double digitW = ch * 0.62, punctW = ch * 0.24;
        double gap = ch * 0.10;
        double total = -gap;
        for (int i = 0; i < s.length(); i++) {
            char k = s.charAt(i);
            total += ((k == ':' || k == '.') ? punctW : digitW) + gap;
        }
        if (total > availW && total > 0) {          // shrink to fit a narrow box
            double k = availW / total;
            ch *= k; digitW *= k; punctW *= k; gap *= k; total = availW;
        }
        double x = geo.cx - total / 2.0;
        double y = geo.cy - ch / 2.0;
        for (int i = 0; i < s.length(); i++) {
            char k = s.charAt(i);
            double cw = (k == ':' || k == '.') ? punctW : digitW;
            if (k != ':' && k != '.') {
                drawSevenSeg(c, '8', x, y, cw, ch, withAlpha(c.accent, 18));   // ghost segments
            }
            drawSevenSeg(c, k, x, y, cw, ch, c.accent);
            x += cw + gap;
        }
    }

    /** Segment order in {@link #SEG_MASK}: a b c d e f g (MSB → LSB). */
    private static final int[] SEG_MASK = {
        0b1111110, // 0
        0b0110000, // 1
        0b1101101, // 2
        0b1111001, // 3
        0b0110011, // 4
        0b1011011, // 5
        0b1011111, // 6
        0b1110000, // 7
        0b1111111, // 8
        0b1111011  // 9
    };

    private static void drawSevenSeg(Ctx c, char ch, double x, double y, double w, double h, Color on) {
        Graphics2D g = c.g;
        double t = h * 0.135;              // segment thickness
        double gap = t * 0.30;             // breathing room between segments
        if (ch == ':') {
            double r = t * 0.52;
            fillDot(c, x + w / 2 - r, y + h * 0.33 - r, r * 2, on);
            fillDot(c, x + w / 2 - r, y + h * 0.70 - r, r * 2, on);
            return;
        }
        if (ch == '.') {
            double r = t * 0.52;
            fillDot(c, x + w / 2 - r, y + h - r * 2, r * 2, on);
            return;
        }
        int digit = Character.isDigit(ch) ? ch - '0' : 8;
        int mask = SEG_MASK[digit];
        boolean segA = (mask & 0b1000000) != 0, segB = (mask & 0b0100000) != 0,
                segC = (mask & 0b0010000) != 0, segD = (mask & 0b0001000) != 0,
                segE = (mask & 0b0000100) != 0, segF = (mask & 0b0000010) != 0,
                segG = (mask & 0b0000001) != 0;

        double hw = w - t - gap * 2;                 // horizontal segment length
        double vh = (h - t * 3) / 2.0 - gap;         // vertical segment length
        double lx = x, rx = x + w - t;
        if (segA) fillSeg(c, x + t / 2 + gap, y,                  hw, t, on);
        if (segG) fillSeg(c, x + t / 2 + gap, y + h / 2 - t / 2,  hw, t, on);
        if (segD) fillSeg(c, x + t / 2 + gap, y + h - t,          hw, t, on);
        if (segF) fillSeg(c, lx, y + t + gap,                     t, vh, on);
        if (segB) fillSeg(c, rx, y + t + gap,                     t, vh, on);
        if (segE) fillSeg(c, lx, y + h / 2 + t / 2 + gap,         t, vh, on);
        if (segC) fillSeg(c, rx, y + h / 2 + t / 2 + gap,         t, vh, on);
    }

    private static void fillDot(Ctx c, double x, double y, double d, Color col) {
        Ellipse2D e = new Ellipse2D.Double(x, y, d, d);
        if (col.getAlpha() > 120) glowFill(c, e, col);
        c.g.setColor(col);
        c.g.fill(e);
    }

    private static void fillSeg(Ctx c, double x, double y, double w, double h, Color col) {
        if (w <= 0 || h <= 0) return;
        Graphics2D g = c.g;
        RoundRectangle2D r = roundRect(x, y, w, h, Math.min(w, h) * 0.34);
        if (col.getAlpha() > 120) glowFill(c, r, col);
        g.setColor(col);
        g.fill(r);
    }

    // ---- 9. Flip Clock ---------------------------------------------------
    private static void drawFlipClock(Ctx c) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        String s = digits(c);
        int n = s.length();
        double gap = geo.w * 0.045 / Math.max(1, n);
        double cw = (geo.w - gap * (n - 1)) / n;
        double ch = geo.h;
        double x = geo.left();
        // animation: the last card flips on each second boundary
        double flipPhase = (c.sinceStartMs % 1000L) / 1000.0;
        for (int i = 0; i < n; i++) {
            char k = s.charAt(i);
            boolean flipping = (i == n - 1) && flipPhase < 0.30 && !c.done;
            drawFlipCard(c, x, geo.cy - ch / 2, cw, ch, String.valueOf(k),
                    flipping ? (flipPhase / 0.30) : 1.0);
            x += cw + gap;
        }
    }

    private static void drawFlipCard(Ctx c, double x, double y, double w, double h,
                                     String glyph, double settle) {
        Graphics2D g = c.g;
        double rad = Math.min(w, h) * 0.14;
        RoundRectangle2D card = roundRect(x, y, w, h, rad);
        shadow(c, card);
        Color top = lighten(nn(c.t.plateColor, new Color(20, 24, 34, 235)), 0.16);
        Color bot = darken(nn(c.t.plateColor, new Color(20, 24, 34, 235)), 0.28);
        g.setPaint(new GradientPaint((float) x, (float) y, top, (float) x, (float) (y + h), bot));
        g.fill(card);
        g.setStroke(new BasicStroke((float) Math.max(1.0, h * 0.018)));
        g.setColor(new Color(255, 255, 255, 42));
        g.draw(card);
        // hinge line
        g.setColor(new Color(0, 0, 0, 130));
        g.setStroke(new BasicStroke((float) Math.max(1.0, h * 0.022)));
        g.draw(new java.awt.geom.Line2D.Double(x + w * 0.03, y + h / 2, x + w * 0.97, y + h / 2));

        double sc = 0.55 + 0.45 * ease("Ease Out", settle);
        AffineTransform sv = g.getTransform();
        g.translate(x + w / 2, y + h / 2);
        g.scale(1.0, Math.max(0.06, sc));
        g.translate(-(x + w / 2), -(y + h / 2));
        drawTextCentered(c, glyph, digitFont(c, h * 0.62), c.text, x + w / 2, y + h / 2);
        g.setTransform(sv);
    }

    // ---- 10. Analog Clock ------------------------------------------------
    private static void drawAnalogClock(Ctx c) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        double d = Math.min(geo.w, geo.h);
        double r = d / 2.0 - d * 0.03;
        Ellipse2D face = new Ellipse2D.Double(geo.cx - r, geo.cy - r, r * 2, r * 2);
        shadow(c, face);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(geo.cx - r * 0.3, geo.cy - r * 0.35),
                (float) (r * 1.5), new float[] { 0f, 1f },
                new Color[] { lighten(nn(c.t.plateColor, new Color(20, 24, 34, 230)), 0.18),
                              darken(nn(c.t.plateColor, new Color(20, 24, 34, 230)), 0.25) }));
        g.fill(face);
        glowStroke(c, face, c.accent, Math.max(1.5, d * 0.03));
        g.setStroke(new BasicStroke((float) Math.max(1.5, d * 0.035)));
        g.setPaint(accentPaint(c, face.getBounds2D()));
        g.draw(face);

        // ticks
        for (int i = 0; i < 60; i++) {
            double ang = Math.toRadians(i * 6);
            boolean major = i % 5 == 0;
            double r1 = r * (major ? 0.76 : 0.84), r2 = r * 0.92;
            g.setStroke(new BasicStroke((float) (major ? d * 0.018 : d * 0.008), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(major ? new Color(255, 255, 255, 190) : new Color(255, 255, 255, 90));
            g.draw(new java.awt.geom.Line2D.Double(
                    geo.cx + Math.sin(ang) * r1, geo.cy - Math.cos(ang) * r1,
                    geo.cx + Math.sin(ang) * r2, geo.cy - Math.cos(ang) * r2));
        }
        // remaining wedge
        double frac = fill(c);
        if (frac > 0.001) {
            Arc2D wedge = new Arc2D.Double(geo.cx - r * 0.7, geo.cy - r * 0.7, r * 1.4, r * 1.4,
                    90, -360.0 * frac, Arc2D.PIE);
            g.setColor(withAlpha(c.accent, 60));
            g.fill(wedge);
        }
        // sweeping hand — one full turn across the whole countdown
        double ang = Math.toRadians(-90 + 360.0 * c.progress);
        double hx = geo.cx + Math.cos(ang) * r * 0.80;
        double hy = geo.cy + Math.sin(ang) * r * 0.80;
        java.awt.geom.Line2D hand = new java.awt.geom.Line2D.Double(
                geo.cx - Math.cos(ang) * r * 0.14, geo.cy - Math.sin(ang) * r * 0.14, hx, hy);
        glowStroke(c, hand, c.accent, Math.max(1.5, d * 0.035));
        g.setStroke(new BasicStroke((float) Math.max(1.5, d * 0.035), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c.urgent ? c.accent : lighten(c.accent, 0.25));
        g.draw(hand);
        double pr = d * 0.045;
        g.setColor(lighten(c.accent, 0.5));
        g.fill(new Ellipse2D.Double(geo.cx - pr, geo.cy - pr, pr * 2, pr * 2));

        if (c.t.showDigits) {
            drawTextCentered(c, digits(c), digitFont(c, d * 0.20), c.text, geo.cx, geo.cy + r * 0.52);
        }
    }

    // ---- 11. Hourglass ---------------------------------------------------
    private static void drawHourglass(Ctx c) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        double w = geo.w, h = geo.h;
        double x = geo.left(), y = geo.top();
        double neck = w * 0.10;
        double capH = h * 0.06;

        Path2D top = new Path2D.Double();
        top.moveTo(x + w * 0.06, y + capH);
        top.lineTo(x + w * 0.94, y + capH);
        top.lineTo(geo.cx + neck / 2, geo.cy);
        top.lineTo(geo.cx - neck / 2, geo.cy);
        top.closePath();
        Path2D bot = new Path2D.Double();
        bot.moveTo(x + w * 0.06, y + h - capH);
        bot.lineTo(x + w * 0.94, y + h - capH);
        bot.lineTo(geo.cx + neck / 2, geo.cy);
        bot.lineTo(geo.cx - neck / 2, geo.cy);
        bot.closePath();

        shadow(c, top);
        g.setColor(new Color(255, 255, 255, 26));
        g.fill(top); g.fill(bot);

        double frac = clamp(1.0 - c.progress, 0, 1);   // sand left on top
        // sand in the top bulb
        Area sandTop = new Area(top);
        double cut = geo.cy - (geo.cy - (y + capH)) * frac;
        sandTop.intersect(new Area(new Rectangle2D.Double(x, cut, w, geo.cy - cut + 1)));
        g.setPaint(accentPaint(c, top.getBounds2D()));
        g.fill(sandTop);
        // sand piled in the bottom bulb — grows as the countdown runs down
        Area sandBot = new Area(bot);
        double bottomY = y + h - capH;
        double pile = bottomY - (bottomY - geo.cy) * clamp(c.progress, 0, 1);
        sandBot.intersect(new Area(new Rectangle2D.Double(x, pile, w, (y + h) - pile + 1)));
        g.setPaint(accentPaint(c, bot.getBounds2D()));
        g.fill(sandBot);
        // falling stream, stopping at the top of the pile
        if (!c.done && frac > 0.002) {
            g.setColor(lighten(c.accent, 0.35));
            double sx = geo.cx - neck * 0.10;
            g.fill(new Rectangle2D.Double(sx, geo.cy, neck * 0.20, Math.max(0, pile - geo.cy)));
        }
        // glass outline + caps
        g.setStroke(new BasicStroke((float) Math.max(1.5, w * 0.045), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 170));
        g.draw(top); g.draw(bot);
        g.setColor(lighten(nn(c.t.plateColor, new Color(30, 34, 46, 235)), 0.25));
        g.fill(roundRect(x, y, w, capH * 1.6, capH * 0.6));
        g.fill(roundRect(x, y + h - capH * 1.6, w, capH * 1.6, capH * 0.6));

        if (c.t.showDigits) {
            drawTextCentered(c, digits(c), digitFont(c, h * 0.22), c.text,
                    geo.cx, y + h + h * 0.16);
        }
    }

    // ---- 12/13. Progress Bars -------------------------------------------
    private static void drawProgressBar(Ctx c, boolean horizontal) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        double w = geo.w, h = geo.h;
        double x = geo.left(), y = geo.top();
        double rad = Math.min(w, h) * clamp(c.t.cornerPct, 0, 100) / 100.0 * 0.5;
        RoundRectangle2D track = roundRect(x, y, w, h, rad);
        shadow(c, track);
        g.setColor(nn(c.t.trackColor, new Color(255, 255, 255, 46)));
        g.fill(track);

        double frac = fill(c);
        if (frac > 0.001) {
            RoundRectangle2D bar;
            if (horizontal) {
                bar = roundRect(x, y, Math.max(h * 0.4, w * frac), h, rad);
            } else {
                double bh = Math.max(w * 0.4, h * frac);
                bar = roundRect(x, y + h - bh, w, bh, rad);
            }
            glowFill(c, bar, c.accent);
            g.setPaint(accentPaint(c, new Rectangle2D.Double(x, y, w, h)));
            g.fill(bar);
            // inner sheen
            Area sheen = new Area(bar);
            sheen.intersect(new Area(new Rectangle2D.Double(x, y, w, h * 0.45)));
            g.setColor(new Color(255, 255, 255, 55));
            g.fill(sheen);
        }
        g.setStroke(new BasicStroke((float) Math.max(1.0, Math.min(w, h) * 0.06)));
        g.setColor(new Color(255, 255, 255, 60));
        g.draw(track);

        if (c.t.showDigits) {
            if (horizontal) {
                drawTextCentered(c, digits(c), digitFont(c, h * 0.62), c.text, geo.cx, geo.cy);
            } else {
                // Vertical bar: the digit sits just under the track so it never
                // collides with the caption drawn above the widget.
                drawTextCentered(c, digits(c), digitFont(c, w * 1.15), c.text,
                        geo.cx, y + h + w * 0.95);
            }
        }
    }

    // ---- 14. Dot Grid ----------------------------------------------------
    private static void drawDotGrid(Ctx c) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        int n = Math.max(3, Math.min(30, c.t.lengthSec));
        int cols = (int) Math.ceil(Math.sqrt(n * (geo.w / Math.max(1.0, geo.h))));
        cols = Math.max(1, Math.min(n, cols));
        int rows = (int) Math.ceil(n / (double) cols);
        double cw = geo.w / cols, chh = geo.h / rows;
        double r = Math.min(cw, chh) * 0.34;
        double frac = fill(c);
        int lit = (int) Math.ceil(frac * n - 1e-6);
        for (int i = 0; i < n; i++) {
            int rr = i / cols, cc2 = i % cols;
            double dx = geo.left() + cw * (cc2 + 0.5);
            double dy = geo.top() + chh * (rr + 0.5);
            Ellipse2D dot = new Ellipse2D.Double(dx - r, dy - r, r * 2, r * 2);
            if (i < lit) {
                if (i == lit - 1) glowFill(c, dot, c.accent);
                g.setColor(c.t.gradient ? mix(c.accent, c.accent2, i / (double) Math.max(1, n - 1)) : c.accent);
                g.fill(dot);
                g.setColor(new Color(255, 255, 255, 70));
                g.setStroke(new BasicStroke((float) Math.max(1.0, r * 0.16)));
                g.draw(dot);
            } else {
                g.setColor(nn(c.t.trackColor, new Color(255, 255, 255, 46)));
                g.fill(dot);
            }
        }
    }

    // ---- 15. Bar Stack (equalizer) --------------------------------------
    private static void drawBarStack(Ctx c) {
        Graphics2D g = c.g;
        Geo geo = c.geo;
        int n = 7;
        double gap = geo.w * 0.05 / n;
        double bw = (geo.w - gap * (n - 1)) / n;
        double frac = fill(c);
        if (c.t.showPlate) {
            RoundRectangle2D panel = roundRect(geo.left() - geo.w * 0.06, geo.top() - geo.h * 0.10,
                    geo.w * 1.12, geo.h * 1.20, geo.h * 0.16);
            glassPanel(c, panel);
        }
        for (int i = 0; i < n; i++) {
            double t01 = (i + 1) / (double) n;
            double hFrac = clamp((frac - (t01 - 1.0 / n)) * n, 0, 1);
            double bh = geo.h * (0.28 + 0.72 * (i % 2 == 0 ? 1.0 : 0.78));
            double x = geo.left() + i * (bw + gap);
            double yBase = geo.top() + geo.h;
            RoundRectangle2D track = roundRect(x, yBase - bh, bw, bh, bw * 0.35);
            g.setColor(nn(c.t.trackColor, new Color(255, 255, 255, 46)));
            g.fill(track);
            if (hFrac > 0.001) {
                double fh = bh * hFrac;
                RoundRectangle2D bar = roundRect(x, yBase - fh, bw, fh, bw * 0.35);
                if (hFrac < 1.0) glowFill(c, bar, c.accent);
                g.setPaint(c.t.gradient ? new GradientPaint((float) x, (float) (yBase - bh), c.accent2,
                        (float) x, (float) yBase, c.accent) : c.accent);
                g.fill(bar);
            }
        }
        if (c.t.showDigits) {
            drawTextCentered(c, digits(c), digitFont(c, geo.h * 0.34), c.text,
                    geo.cx, geo.top() + geo.h * 1.34);
        }
    }

    // =====================================================================
    // Sound — every built-in bed is synthesized, so nothing ships as an asset.
    // The result is one WAV covering the whole countdown (ticks + finish
    // stinger) which the exporter mixes in at the timer's start moment.
    // =====================================================================

    private static final float SAMPLE_RATE = 44100f;
    private static final javax.sound.sampled.AudioFormat FORMAT =
            new javax.sound.sampled.AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    /**
     * Build the complete timer audio bed as float samples in [-1, 1].
     * Returns null when the timer has no built-in sounds selected.
     */
    private static float[] buildTrack(SlideTimer t, boolean previewShort) {
        String tick = t.tickSound == null ? "None" : t.tickSound;
        String end  = t.endSound  == null ? "None" : t.endSound;
        if ("None".equals(tick) && "None".equals(end)) return null;

        int lengthSec = Math.max(1, t.lengthSec);
        if (previewShort) lengthSec = Math.min(lengthSec, 4);
        double totalSec = lengthSec + 2.6;
        int n = (int) (SAMPLE_RATE * totalSec);
        float[] buf = new float[n];

        int urgent = Math.max(0, Math.min(lengthSec, t.urgentSec));

        if (!"None".equals(tick)) {
            if ("Bomb Fuse".equals(tick)) {
                // continuous crackle for the whole countdown, hotter near the end
                fuseCrackle(buf, 0.0, lengthSec, 0.34);
            } else {
                for (int s = 0; s < lengthSec; s++) {
                    double at = s;
                    boolean hot = (lengthSec - s) <= urgent;
                    double amp = hot ? 0.78 : 0.52;
                    addTick(buf, tick, at, amp, hot);
                    if (hot && ("Classic Clock".equals(tick) || "Digital Beep".equals(tick)
                             || "Wood Block".equals(tick) || "Soft Tick".equals(tick))) {
                        // double-time in the urgent phase for real tension
                        addTick(buf, tick, at + 0.5, amp * 0.72, true);
                    }
                }
            }
        }
        if (!"None".equals(end)) {
            addEnd(buf, end, lengthSec, 1.0);
        }

        // master level + soft clip
        double vol = clamp(t.soundVolumePct, 0, 100) / 100.0;
        for (int i = 0; i < n; i++) {
            double v = buf[i] * vol;
            buf[i] = (float) Math.tanh(v * 1.15);
        }
        // 8 ms fade in / out so the mix never clicks
        int fade = (int) (SAMPLE_RATE * 0.008);
        for (int i = 0; i < fade && i < n; i++) {
            buf[i] *= i / (float) fade;
            buf[n - 1 - i] *= i / (float) fade;
        }
        return buf;
    }

    // ---- tick beds -------------------------------------------------------
    private static void addTick(float[] buf, String kind, double atSec, double amp, boolean hot) {
        switch (kind) {
            case "Classic Clock":
                addClick(buf, atSec, 0.035, hot ? 2400 : 1900, amp, 140);
                addClick(buf, atSec + 0.006, 0.030, hot ? 1500 : 1150, amp * 0.6, 110);
                break;
            case "Soft Tick":
                addClick(buf, atSec, 0.045, 900, amp * 0.75, 60);
                break;
            case "Digital Beep":
                addTone(buf, atSec, 0.075, hot ? 1320 : 880, amp * 0.55, 22, 0.35);
                break;
            case "Heartbeat":
                addThump(buf, atSec, 0.16, 62, amp * 0.95);
                addThump(buf, atSec + 0.22, 0.13, 52, amp * 0.62);
                break;
            case "Suspense Pulse":
                addSwell(buf, atSec, 0.85, hot ? 92 : 74, amp * 0.6);
                break;
            case "Wood Block":
                addClick(buf, atSec, 0.055, hot ? 1150 : 820, amp * 0.9, 48);
                addTone(buf, atSec, 0.055, hot ? 2300 : 1640, amp * 0.25, 55, 0.0);
                break;
            case "Sci-Fi Sweep":
                addSweep(buf, atSec, 0.30, 320, hot ? 1500 : 1050, amp * 0.5);
                break;
            default:
                addClick(buf, atSec, 0.035, 1900, amp, 140);
                break;
        }
    }

    // ---- finish stingers -------------------------------------------------
    private static void addEnd(float[] buf, String kind, double atSec, double amp) {
        switch (kind) {
            case "Bell Ding":
                addBell(buf, atSec, 1.9, 1046.5, amp * 0.85);
                addBell(buf, atSec + 0.012, 1.6, 1567.9, amp * 0.35);
                break;
            case "Triple Chime":
                addBell(buf, atSec,        1.2, 783.99, amp * 0.7);
                addBell(buf, atSec + 0.20, 1.2, 987.77, amp * 0.7);
                addBell(buf, atSec + 0.40, 1.9, 1174.7, amp * 0.8);
                break;
            case "Gong":
                addBell(buf, atSec, 2.4, 174.6, amp * 0.9);
                addBell(buf, atSec, 2.2, 261.6, amp * 0.5);
                addBell(buf, atSec, 2.0, 392.0, amp * 0.28);
                addNoiseBurst(buf, atSec, 0.30, amp * 0.22);
                break;
            case "Buzzer":
                addBuzz(buf, atSec, 0.85, 172, amp * 0.72);
                break;
            case "Alarm":
                for (int i = 0; i < 4; i++) {
                    addTone(buf, atSec + i * 0.22, 0.14, i % 2 == 0 ? 1046 : 784, amp * 0.6, 26, 0.3);
                }
                break;
            case "Whoosh":
                addSweep(buf, atSec, 0.55, 180, 1600, amp * 0.7);
                addNoiseBurst(buf, atSec, 0.45, amp * 0.30);
                break;
            case "Success Sparkle":
                double[] notes = { 1046.5, 1318.5, 1568.0, 2093.0 };
                for (int i = 0; i < notes.length; i++) {
                    addBell(buf, atSec + i * 0.085, 0.9, notes[i], amp * (0.55 - i * 0.05));
                }
                break;
            default: break;
        }
    }

    // ---- primitive generators -------------------------------------------
    private static void addTone(float[] buf, double atSec, double durSec, double freq,
                                double amp, double decay, double harmonic) {
        int start = (int) (atSec * SAMPLE_RATE);
        int n = (int) (durSec * SAMPLE_RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= buf.length) continue;
            double t = i / SAMPLE_RATE;
            double env = Math.exp(-decay * t) * Math.min(1.0, i / (SAMPLE_RATE * 0.004));
            double v = Math.sin(2 * Math.PI * freq * t);
            if (harmonic > 0) v += harmonic * Math.sin(2 * Math.PI * freq * 2 * t);
            buf[idx] += (float) (v * env * amp);
        }
    }

    private static void addClick(float[] buf, double atSec, double durSec, double freq,
                                 double amp, double decay) {
        int start = (int) (atSec * SAMPLE_RATE);
        int n = (int) (durSec * SAMPLE_RATE);
        java.util.Random rnd = new java.util.Random((long) (atSec * 1000) ^ (long) freq);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= buf.length) continue;
            double t = i / SAMPLE_RATE;
            double env = Math.exp(-decay * t);
            double v = 0.7 * Math.sin(2 * Math.PI * freq * t)
                     + 0.3 * (rnd.nextDouble() * 2 - 1) * Math.exp(-decay * 2.4 * t);
            buf[idx] += (float) (v * env * amp);
        }
    }

    private static void addThump(float[] buf, double atSec, double durSec, double freq, double amp) {
        int start = (int) (atSec * SAMPLE_RATE);
        int n = (int) (durSec * SAMPLE_RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= buf.length) continue;
            double t = i / SAMPLE_RATE;
            double f = freq * (1.0 + 0.8 * Math.exp(-28 * t));   // pitch drop = punch
            double env = Math.exp(-11 * t) * Math.min(1.0, i / (SAMPLE_RATE * 0.003));
            buf[idx] += (float) (Math.sin(2 * Math.PI * f * t) * env * amp);
        }
    }

    private static void addSwell(float[] buf, double atSec, double durSec, double freq, double amp) {
        int start = (int) (atSec * SAMPLE_RATE);
        int n = (int) (durSec * SAMPLE_RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= buf.length) continue;
            double t = i / SAMPLE_RATE;
            double p = t / durSec;
            double env = Math.sin(Math.PI * p);
            env *= env;
            double v = Math.sin(2 * Math.PI * freq * t)
                     + 0.45 * Math.sin(2 * Math.PI * freq * 1.5 * t);
            buf[idx] += (float) (v * env * amp * 0.6);
        }
    }

    private static void addSweep(float[] buf, double atSec, double durSec,
                                 double f0, double f1, double amp) {
        int start = (int) (atSec * SAMPLE_RATE);
        int n = (int) (durSec * SAMPLE_RATE);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            double t = i / SAMPLE_RATE;
            double p = t / durSec;
            double f = f0 + (f1 - f0) * p;
            phase += 2 * Math.PI * f / SAMPLE_RATE;
            if (idx < 0 || idx >= buf.length) continue;
            double env = Math.sin(Math.PI * p);
            buf[idx] += (float) (Math.sin(phase) * env * amp);
        }
    }

    private static void addBell(float[] buf, double atSec, double durSec, double freq, double amp) {
        double[] partials = { 1.0, 2.01, 2.99, 4.21, 5.43 };
        double[] gains    = { 1.0, 0.55, 0.32, 0.18, 0.09 };
        int start = (int) (atSec * SAMPLE_RATE);
        int n = (int) (durSec * SAMPLE_RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= buf.length) continue;
            double t = i / SAMPLE_RATE;
            double v = 0;
            for (int k = 0; k < partials.length; k++) {
                v += gains[k] * Math.sin(2 * Math.PI * freq * partials[k] * t)
                        * Math.exp(-(2.2 + k * 1.3) * t);
            }
            double attack = Math.min(1.0, i / (SAMPLE_RATE * 0.004));
            buf[idx] += (float) (v * attack * amp * 0.5);
        }
    }

    private static void addBuzz(float[] buf, double atSec, double durSec, double freq, double amp) {
        int start = (int) (atSec * SAMPLE_RATE);
        int n = (int) (durSec * SAMPLE_RATE);
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= buf.length) continue;
            double t = i / SAMPLE_RATE;
            double sq = Math.sin(2 * Math.PI * freq * t) >= 0 ? 1 : -1;
            double am = 0.6 + 0.4 * Math.sin(2 * Math.PI * 22 * t);
            double env = Math.min(1.0, i / (SAMPLE_RATE * 0.006)) * Math.exp(-1.4 * t);
            buf[idx] += (float) (sq * am * env * amp * 0.45);
        }
    }

    private static void addNoiseBurst(float[] buf, double atSec, double durSec, double amp) {
        int start = (int) (atSec * SAMPLE_RATE);
        int n = (int) (durSec * SAMPLE_RATE);
        java.util.Random rnd = new java.util.Random(0xC0FFEE);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= buf.length) continue;
            double t = i / SAMPLE_RATE;
            lp += ((rnd.nextDouble() * 2 - 1) - lp) * 0.25;
            buf[idx] += (float) (lp * Math.exp(-6 * t) * amp);
        }
    }

    private static void fuseCrackle(float[] buf, double atSec, double durSec, double amp) {
        int start = (int) (atSec * SAMPLE_RATE);
        int n = (int) (durSec * SAMPLE_RATE);
        java.util.Random rnd = new java.util.Random(4242);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= buf.length) continue;
            double t = i / SAMPLE_RATE;
            double p = t / durSec;
            lp += ((rnd.nextDouble() * 2 - 1) - lp) * 0.45;
            double spark = rnd.nextDouble() < 0.0016 ? (rnd.nextDouble() * 2 - 1) * 1.6 : 0;
            buf[idx] += (float) ((lp * 0.55 + spark) * amp * (0.55 + 0.75 * p));
        }
    }

    // ---- WAV output ------------------------------------------------------

    private static byte[] toPcm16(float[] f) {
        byte[] out = new byte[f.length * 2];
        for (int i = 0; i < f.length; i++) {
            int v = (int) (Math.max(-1f, Math.min(1f, f[i])) * 32767);
            out[i * 2]     = (byte) (v & 0xFF);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    /**
     * Write the built-in timer bed as a WAV in {@code dir}. Returns null when
     * the timer uses no built-in sounds (custom files are handled separately by
     * the exporter).
     */
    public static File writeTrackWav(File dir, SlideTimer t, String tag) throws IOException {
        float[] track = buildTrack(t, false);
        if (track == null) return null;
        byte[] pcm = toPcm16(track);
        File f = new File(dir, "slide_timer_" + (tag == null ? "0" : tag) + ".wav");
        try (javax.sound.sampled.AudioInputStream ais = new javax.sound.sampled.AudioInputStream(
                new java.io.ByteArrayInputStream(pcm), FORMAT, pcm.length / FORMAT.getFrameSize())) {
            javax.sound.sampled.AudioSystem.write(ais,
                    javax.sound.sampled.AudioFileFormat.Type.WAVE, f);
        }
        return f;
    }

    /** Fire-and-forget audition of the current sound settings (dialog button). */
    public static void playPreview(SlideTimer t) {
        try {
            float[] track = buildTrack(t, true);
            if (track == null) { java.awt.Toolkit.getDefaultToolkit().beep(); return; }
            byte[] pcm = toPcm16(track);
            javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
            clip.open(FORMAT, pcm, 0, pcm.length);
            clip.addLineListener(ev -> {
                if (ev.getType() == javax.sound.sampled.LineEvent.Type.STOP) ev.getLine().close();
            });
            clip.start();
        } catch (Exception ignored) {
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }
}
