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

/**
 * A single on-slide countdown timer: a self-contained overlay drawn on top of an
 * already-rendered frame, exactly the way {@link SlideAnnotation} and
 * {@code QuizSlide.applyOverlay(...)} composite onto a frame.
 *
 * <p>Everything the timer needs rides on this object: which look to draw
 * ({@link #style}), how long it counts ({@link #seconds}), where and how big it
 * sits on the frame ({@link #xPct}/{@link #yPct}/{@link #sizePct}), WHEN it
 * shows up ({@link #startMode} + {@link #offsetMs}, resolved against the slide's
 * audio timeline by {@link #resolveStartMs}), which entrance / idle / finish
 * effects animate it, and which sound plays while it runs.
 *
 * <p>Animation is driven by real elapsed time (ms) so it is frame-rate
 * independent — the same call renders the editor's live preview and every
 * exported frame.
 */
public class SlideTimer {

    // ---- start modes -------------------------------------------------------
    /** Timer starts when the slide starts (+ offset). */
    public static final String START_SLIDE = "Slide start";
    /** Timer starts after ALL of this slide's audio has finished (+ offset). This is the default. */
    public static final String START_AFTER_ALL_AUDIO = "After all audio";
    /** Timer starts when the chosen text's audio ends (+ offset). */
    public static final String START_AFTER_AUDIO = "After text audio";
    /** Timer ends exactly when the chosen text's audio starts (+ offset). */
    public static final String START_BEFORE_AUDIO = "Before text audio";
    /** Timer starts at an explicit slide-relative time (+ offset). */
    public static final String START_AT_TIME = "At specific time";

    /** How long the entrance animation runs, in ms. */
    private static final double ENTRANCE_MS = 520.0;
    /** How long the finish animation runs, in ms. */
    private static final double FINISH_MS = 700.0;

    // ---- model: what it looks like ----------------------------------------
    /** Master switch. False = nothing is drawn and no sound is muxed. */
    public boolean enabled = false;
    public String style = "Neon Ring";
    /** Countdown length in seconds. */
    public int seconds = 10;
    /** Centre X as % of frame width. */
    public double xPct = 50;
    /** Centre Y as % of frame height. */
    public double yPct = 78;
    /** Width as % of frame width. */
    public double sizePct = 16;
    /** Height as % of frame WIDTH; 0 = the style's natural height for that width. */
    public double heightPct = 0;
    /** Overall opacity, 0..100. */
    public int opacity = 100;
    public double rotationDeg = 0;

    public Color color      = new Color(64, 214, 255);   // accent / progress
    public Color color2     = new Color(255, 76, 92);    // urgent (last seconds)
    public Color trackColor = new Color(255, 255, 255, 60);
    public Color textColor  = Color.WHITE;
    public Color panelColor = new Color(10, 14, 24);
    /** Backing plate opacity, 0..100 (0 = no plate). */
    public int panelOpacity = 55;

    public String fontName = "SansSerif";
    /** "Seconds" | "MM:SS" | "0.0 s" | "None". */
    public String format = "Seconds";
    /** Small caption under / beside the digits. Empty = none. */
    public String label = "";
    public boolean glowOn = true;
    public int glowPct = 65;
    public boolean shadow = true;
    /** Count 0→N instead of N→0. */
    public boolean countUp = false;

    // ---- model: when it shows -------------------------------------------
    public String startMode = START_AFTER_ALL_AUDIO;
    /** 1-based text index for the After/Before-text-audio modes. */
    public int startTextIndex = 1;
    /** Explicit slide-relative start, ms — used by {@link #START_AT_TIME}. */
    public int startAtMs = 0;
    /** Nudge applied on top of whatever the mode resolves to, ms (may be negative). */
    public int offsetMs = 1000;
    /** Disappear once the countdown hits zero (after {@link #lingerMs}). */
    public boolean hideAfterEnd = true;
    /** How long the finished timer stays on screen before it goes, ms. */
    public int lingerMs = 900;

    // ---- model: effects ---------------------------------------------------
    public String entrance = "Pop";
    public String effect   = "Pulse";
    public String finish   = "Flash";
    /** Swap to {@link #color2} for the final {@link #urgentSeconds}. */
    public boolean urgentColor = true;
    public int urgentSeconds = 3;

    // ---- model: sound -----------------------------------------------------
    /** "None" | "Built-in" | "File". */
    public String soundMode = "Built-in";
    /** Built-in ticking bed played for the whole countdown. */
    public String soundName = "Tick-Tock";
    /** Built-in one-shot played when the countdown hits zero. */
    public String endSoundName = "Chime";
    /** External sound file, used when {@code soundMode} is "File". */
    public String soundPath = "";
    /** Playback gain, 0..100. */
    public int soundVolume = 70;

    public SlideTimer() {}

    public SlideTimer copy() {
        SlideTimer t = new SlideTimer();
        t.enabled = enabled; t.style = style; t.seconds = seconds;
        t.xPct = xPct; t.yPct = yPct; t.sizePct = sizePct; t.heightPct = heightPct;
        t.opacity = opacity; t.rotationDeg = rotationDeg;
        t.color = color; t.color2 = color2; t.trackColor = trackColor;
        t.textColor = textColor; t.panelColor = panelColor; t.panelOpacity = panelOpacity;
        t.fontName = fontName; t.format = format; t.label = label;
        t.glowOn = glowOn; t.glowPct = glowPct; t.shadow = shadow; t.countUp = countUp;
        t.startMode = startMode; t.startTextIndex = startTextIndex;
        t.startAtMs = startAtMs; t.offsetMs = offsetMs;
        t.hideAfterEnd = hideAfterEnd; t.lingerMs = lingerMs;
        t.entrance = entrance; t.effect = effect; t.finish = finish;
        t.urgentColor = urgentColor; t.urgentSeconds = urgentSeconds;
        t.soundMode = soundMode; t.soundName = soundName; t.endSoundName = endSoundName;
        t.soundPath = soundPath; t.soundVolume = soundVolume;
        return t;
    }

    // ---- catalogs (for the dialog's dropdowns) ----------------------------
    public static String[] styles() {
        return new String[] {
                "Neon Ring", "Glass Digital", "Circular Progress", "Segmented Ring",
                "Dual Ring", "Gauge Dial", "Flip Clock", "LED Panel",
                "Sand Timer", "Radial Sweep", "Minimal Bar", "Pill Countdown",
                "Dot Grid", "Wave Bar", "Analog Clock", "Ticks Ring",
                "Chrome Badge", "Frosted Card" };
    }
    public static String[] lengths() {
        return new String[] { "3", "5", "7", "10", "15", "20", "30", "45", "60", "90", "120" };
    }
    public static String[] startModes() {
        return new String[] { START_AFTER_ALL_AUDIO, START_SLIDE, START_AFTER_AUDIO,
                START_BEFORE_AUDIO, START_AT_TIME };
    }
    public static String[] formats() {
        return new String[] { "Seconds", "MM:SS", "0.0 s", "None" };
    }
    public static String[] entrances() {
        return new String[] { "None", "Fade", "Pop", "Zoom In", "Slide Up", "Slide Down",
                "Drop In", "Flip In", "Sweep In", "Bounce", "Unfold" };
    }
    public static String[] effects() {
        return new String[] { "None", "Pulse", "Breathe Glow", "Rotate Halo", "Color Shift",
                "Sparkle", "Ripple", "Neon Flicker", "Tick Bounce", "Urgent Shake" };
    }
    public static String[] finishes() {
        return new String[] { "None", "Flash", "Burst", "Shockwave", "Fade Out",
                "Zoom Out", "Shake Out", "Drop Out" };
    }
    /** Built-in ticking beds. Index 0 ("None") means a silent countdown. */
    public static String[] tickSounds() {
        return new String[] { "None", "Tick-Tock", "Clock Ticks", "Soft Beep", "Digital Blips",
                "Heartbeat", "Metronome", "Suspense Riser", "Bubble Pops", "Sci-Fi Pulse" };
    }
    /** Built-in one-shots played at zero. */
    public static String[] endSounds() {
        return new String[] { "None", "Chime", "Bell", "Ding", "Gong", "Buzzer",
                "Power Down", "Whoosh", "Fanfare" };
    }
    public static String[] soundModes() { return new String[] { "None", "Built-in", "File" }; }

    // ---- timing ------------------------------------------------------------

    /** Countdown length in ms (never below 200 ms so a bad value can't divide by zero). */
    public int totalMs() { return Math.max(200, seconds * 1000); }

    /**
     * Resolve the slide-relative moment (ms) at which this timer starts.
     *
     * @param audioStartMs per-text audio start times, slide-relative; entry &lt; 0 = that text has no audio
     * @param audioEndMs   per-text audio end times, parallel to {@code audioStartMs}
     * @param totalAudioMs end of the last audio on the slide (0 when the slide is silent)
     */
    public int resolveStartMs(int[] audioStartMs, int[] audioEndMs, int totalAudioMs) {
        int base;
        if (START_SLIDE.equals(startMode)) {
            base = 0;
        } else if (START_AT_TIME.equals(startMode)) {
            base = Math.max(0, startAtMs);
        } else if (START_AFTER_AUDIO.equals(startMode)) {
            base = audioAt(audioEndMs, startTextIndex, totalAudioMs);
        } else if (START_BEFORE_AUDIO.equals(startMode)) {
            // Finish exactly as that audio begins: back the countdown up by its own length.
            base = audioAt(audioStartMs, startTextIndex, 0) - totalMs();
        } else {
            base = totalAudioMs;      // START_AFTER_ALL_AUDIO (default)
        }
        return Math.max(0, base + offsetMs);
    }

    private static int audioAt(int[] arr, int oneBasedIdx, int fallback) {
        int i = oneBasedIdx - 1;
        if (arr == null || i < 0 || i >= arr.length || arr[i] < 0) return fallback;
        return arr[i];
    }

    /** How long the timer stays on screen from its start, ms (countdown + linger / rest of slide). */
    public int visibleMs() {
        return hideAfterEnd ? totalMs() + Math.max(0, lingerMs) : Integer.MAX_VALUE / 4;
    }

    /** Slide-relative ms at which the timer is fully done — used to stretch the slide so it fits. */
    public int endMs(int startMs) {
        return startMs + totalMs() + Math.max(0, lingerMs);
    }

    // ---- painting ----------------------------------------------------------

    /**
     * Draw the timer onto {@code frame} in place.
     *
     * @param startMs   slide-relative start, from {@link #resolveStartMs}
     * @param elapsedMs slide-relative time of this frame
     * @param preview   editor preview: never hides the timer, so it can always be
     *                  positioned and styled regardless of its schedule
     */
    public static void paint(BufferedImage frame, SlideTimer t, int startMs, long elapsedMs, boolean preview) {
        if (frame == null || t == null || !t.enabled) return;
        final int totalMs = t.totalMs();
        long local = elapsedMs - startMs;
        if (preview) {
            local = Math.max(0, Math.min(local, totalMs + (long) FINISH_MS));
        } else {
            if (local < 0) return;
            if (local > t.visibleMs()) return;
        }

        final int fw = frame.getWidth(), fh = frame.getHeight();
        double w = Math.max(24, fw * clamp(t.sizePct, 2, 100) / 100.0);
        double h = t.heightPct > 0 ? Math.max(16, fw * t.heightPct / 100.0) : naturalHeight(t.style, w);
        double cx = fw * t.xPct / 100.0;
        double cy = fh * t.yPct / 100.0;

        double prog = clamp01(local / (double) totalMs);              // 0 → 1 through the countdown
        double remainSec = Math.max(0, (totalMs - local) / 1000.0);
        boolean finished = local >= totalMs;
        double fin = finished ? clamp01((local - totalMs) / FINISH_MS) : 0;

        Color accent = t.color != null ? t.color : Color.CYAN;
        if (t.urgentColor && !finished && remainSec <= Math.max(0, t.urgentSeconds) && t.color2 != null) {
            // Ease into the urgent colour over the last second of the safe zone so it
            // does not snap; inside the urgent window it also throbs with the tick.
            double throb = 0.72 + 0.28 * Math.abs(Math.sin(Math.PI * (local % 1000) / 1000.0));
            accent = mix(t.color, t.color2, 1.0);
            accent = scaleBrightness(accent, throb);
        }

        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // ----- entrance / idle / finish, folded into one transform + alpha -----
            double alpha = clamp01(t.opacity / 100.0);
            double scale = 1, dx = 0, dy = 0, rot = Math.toRadians(t.rotationDeg), shearY = 0;

            double e = clamp01(local / ENTRANCE_MS);
            if (e < 1 && !"None".equals(nz(t.entrance))) {
                double se = easeOutBack(e), so = easeOutCubic(e);
                switch (nz(t.entrance)) {
                    case "Fade":       alpha *= so; break;
                    case "Pop":        scale *= 0.55 + 0.45 * se; alpha *= so; break;
                    case "Zoom In":    scale *= 0.15 + 0.85 * so; alpha *= so; break;
                    case "Slide Up":   dy += h * 1.4 * (1 - so); alpha *= so; break;
                    case "Slide Down": dy -= h * 1.4 * (1 - so); alpha *= so; break;
                    case "Drop In":    dy -= h * 2.2 * (1 - easeOutBounce(e)); break;
                    case "Flip In":    shearY = (1 - so) * 0.9; scale *= 0.6 + 0.4 * so; alpha *= so; break;
                    case "Sweep In":   dx -= w * 1.6 * (1 - so); alpha *= so; break;
                    case "Bounce":     scale *= easeOutBounce(e); alpha *= so; break;
                    case "Unfold":     scale *= 0.2 + 0.8 * so; rot += (1 - se) * Math.PI / 3; alpha *= so; break;
                    default: break;
                }
            }

            if (!finished && !"None".equals(nz(t.effect))) {
                double beat = (local % 1000) / 1000.0;           // 0..1 inside each second
                boolean urgent = remainSec <= Math.max(0, t.urgentSeconds);
                switch (nz(t.effect)) {
                    case "Pulse":       scale *= 1 + 0.05 * Math.pow(1 - beat, 3); break;
                    case "Tick Bounce": dy -= h * 0.06 * Math.pow(1 - beat, 4); break;
                    case "Urgent Shake":
                        if (urgent) {
                            dx += w * 0.02 * Math.sin(local / 32.0);
                            dy += h * 0.012 * Math.sin(local / 21.0);
                        }
                        break;
                    case "Neon Flicker":
                        alpha *= 0.86 + 0.14 * Math.abs(Math.sin(local / 47.0) * Math.sin(local / 13.0));
                        break;
                    default: break;   // Breathe Glow / Rotate Halo / Color Shift / Sparkle / Ripple draw below
                }
            }

            if (finished && !"None".equals(nz(t.finish))) {
                switch (nz(t.finish)) {
                    case "Fade Out":  alpha *= 1 - fin; break;
                    case "Zoom Out":  scale *= 1 + 0.5 * fin; alpha *= 1 - fin; break;
                    case "Shake Out": dx += w * 0.035 * Math.sin(fin * 40) * (1 - fin); break;
                    case "Drop Out":  dy += h * 2.0 * fin * fin; alpha *= 1 - fin * fin; break;
                    case "Flash":     scale *= 1 + 0.16 * Math.pow(1 - fin, 2); break;
                    default: break;   // Burst / Shockwave draw below
                }
            } else if (finished && t.hideAfterEnd && t.lingerMs > 0) {
                // Even with no finish effect, ease the timer out at the very end so it
                // never pops off screen mid-frame.
                double outStart = Math.max(0, t.lingerMs - 260);
                double outT = (local - totalMs - outStart) / 260.0;
                if (outT > 0) alpha *= clamp01(1 - outT);
            }

            if (alpha <= 0.004) return;

            AffineTransform saved = g.getTransform();
            g.translate(cx + dx, cy + dy);
            if (rot != 0) g.rotate(rot);
            if (shearY != 0) g.shear(0, shearY);
            if (scale != 1) g.scale(scale, scale);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));

            // ----- behind-the-face decoration (glow, halo, ripple, shockwave) -----
            double glowBoost = 1;
            if ("Breathe Glow".equals(nz(t.effect)) && !finished) {
                glowBoost = 0.7 + 0.6 * (0.5 + 0.5 * Math.sin(local / 420.0));
            }
            if (t.glowOn) drawBackGlow(g, t, w, h, accent, glowBoost);
            if ("Rotate Halo".equals(nz(t.effect)) && !finished) drawRotatingHalo(g, w, h, accent, local);
            if ("Ripple".equals(nz(t.effect)) && !finished) drawRipples(g, w, h, accent, local);

            Color drawAccent = accent;
            if ("Color Shift".equals(nz(t.effect)) && !finished) {
                drawAccent = hueShift(accent, (local / 3200.0) % 1.0);
            }

            String txt = t.timeText(remainSec, local, totalMs);
            drawFace(g, t, w, h, prog, txt, drawAccent, local, totalMs, finished, fin);

            if ("Sparkle".equals(nz(t.effect)) && !finished) drawSparkles(g, w, h, drawAccent, local);
            if (finished) {
                if ("Burst".equals(nz(t.finish)))     drawBurst(g, w, h, drawAccent, fin);
                if ("Shockwave".equals(nz(t.finish))) drawShockwave(g, w, h, drawAccent, fin);
                if ("Flash".equals(nz(t.finish)))     drawFlash(g, w, h, fin);
            }

            g.setTransform(saved);
        } finally {
            g.dispose();
        }
    }

    /** Digits for the current instant, honouring {@link #format} and {@link #countUp}. */
    private String timeText(double remainSec, long local, int totalMs) {
        if ("None".equals(nz(format))) return "";
        double shown = countUp ? Math.min(totalMs / 1000.0, local / 1000.0) : remainSec;
        if ("0.0 s".equals(nz(format))) return String.format(java.util.Locale.US, "%.1f", Math.max(0, shown));
        int r = countUp ? (int) Math.floor(shown + 1e-6) : (int) Math.ceil(shown - 1e-6);
        if (r < 0) r = 0;
        if ("MM:SS".equals(nz(format))) return String.format(java.util.Locale.US, "%d:%02d", r / 60, r % 60);
        return String.valueOf(r);
    }

    /** The height a style wants for a given width when the user leaves Height on auto. */
    private static double naturalHeight(String style, double w) {
        switch (nz(style)) {
            case "Minimal Bar":  return w * 0.15;
            case "Wave Bar":     return w * 0.22;
            case "Pill Countdown": return w * 0.34;
            case "Glass Digital":  return w * 0.46;
            case "Frosted Card":   return w * 0.58;
            case "LED Panel":      return w * 0.46;
            case "Flip Clock":     return w * 0.60;
            case "Sand Timer":     return w * 1.25;
            case "Dot Grid":       return w * 0.42;
            default:               return w;      // rings, dials, clocks are square
        }
    }

    // ---- shared decoration -------------------------------------------------

    /** Soft outer glow behind the whole face — a few fading passes stand in for a blur. */
    private static void drawBackGlow(Graphics2D g, SlideTimer t, double w, double h, Color accent, double boost) {
        double strength = clamp01(t.glowPct / 100.0) * boost;
        if (strength <= 0.01) return;
        double r = Math.max(w, h) * (0.62 + 0.30 * strength);
        Color c = accent != null ? accent : Color.CYAN;
        try {
            RadialGradientPaint rg = new RadialGradientPaint(
                    new Point2D.Double(0, 0), (float) r,
                    new float[] { 0f, 0.55f, 1f },
                    new Color[] {
                            new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (110 * strength)),
                            new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (44 * strength)),
                            new Color(c.getRed(), c.getGreen(), c.getBlue(), 0) },
                    MultipleGradientPaint.CycleMethod.NO_CYCLE);
            g.setPaint(rg);
            g.fill(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        } catch (Exception ignored) { /* degenerate radius — skip the glow */ }
    }

    /** Slowly turning conic-ish halo of short rays (the "Rotate Halo" idle effect). */
    private static void drawRotatingHalo(Graphics2D g, double w, double h, Color accent, long local) {
        double r = Math.max(w, h) * 0.62;
        double a0 = local / 900.0;
        AffineTransform sv = g.getTransform();
        g.rotate(a0);
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12;
            float alpha = (float) (0.06 + 0.10 * (0.5 + 0.5 * Math.sin(a * 3 + a0 * 2)));
            g.setColor(withAlpha(accent, (int) (alpha * 255)));
            g.setStroke(new BasicStroke((float) (r * 0.035), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double x1 = Math.cos(a) * r * 0.86, y1 = Math.sin(a) * r * 0.86;
            double x2 = Math.cos(a) * r, y2 = Math.sin(a) * r;
            g.draw(new java.awt.geom.Line2D.Double(x1, y1, x2, y2));
        }
        g.setTransform(sv);
    }

    /** Expanding rings that repeat once a second (the "Ripple" idle effect). */
    private static void drawRipples(Graphics2D g, double w, double h, Color accent, long local) {
        double base = Math.max(w, h) * 0.5;
        for (int k = 0; k < 2; k++) {
            double p = (((local + k * 500) % 1000) / 1000.0);
            double r = base * (0.9 + 0.55 * p);
            int a = (int) (110 * (1 - p));
            if (a <= 2) continue;
            g.setColor(withAlpha(accent, a));
            g.setStroke(new BasicStroke((float) (base * 0.035 * (1 - p) + 0.6)));
            g.draw(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        }
    }

    /** Tiny twinkles orbiting the face (the "Sparkle" idle effect). */
    private static void drawSparkles(Graphics2D g, double w, double h, Color accent, long local) {
        double r = Math.max(w, h) * 0.55;
        for (int i = 0; i < 7; i++) {
            double phase = ((local / 1400.0) + i / 7.0) % 1.0;
            double a = phase * Math.PI * 2 + i;
            double rr = r * (0.86 + 0.22 * Math.sin(phase * Math.PI * 2 + i));
            double s = Math.max(1.2, r * 0.035 * (0.5 + 0.5 * Math.sin(phase * Math.PI * 6 + i)));
            g.setColor(withAlpha(brighten(accent, 0.5), (int) (200 * (0.35 + 0.65 * Math.abs(Math.sin(phase * Math.PI * 3 + i))))));
            double x = Math.cos(a) * rr, y = Math.sin(a) * rr;
            Path2D.Double star = new Path2D.Double();
            star.moveTo(x - s, y); star.lineTo(x, y - s); star.lineTo(x + s, y); star.lineTo(x, y + s);
            star.closePath();
            g.fill(star);
        }
    }

    private static void drawBurst(Graphics2D g, double w, double h, Color accent, double p) {
        double r = Math.max(w, h) * 0.5;
        int n = 16;
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n + p * 0.6;
            double r0 = r * (0.55 + 0.75 * easeOutCubic(p));
            double len = r * 0.30 * (1 - p);
            g.setColor(withAlpha(i % 2 == 0 ? accent : brighten(accent, 0.6), (int) (235 * (1 - p))));
            g.setStroke(new BasicStroke((float) Math.max(1, r * 0.045 * (1 - p)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Line2D.Double(
                    Math.cos(a) * r0, Math.sin(a) * r0,
                    Math.cos(a) * (r0 + len), Math.sin(a) * (r0 + len)));
        }
    }

    private static void drawShockwave(Graphics2D g, double w, double h, Color accent, double p) {
        double base = Math.max(w, h) * 0.5;
        for (int k = 0; k < 3; k++) {
            double pp = clamp01(p - k * 0.16);
            if (pp <= 0) continue;
            double r = base * (0.7 + 1.5 * easeOutCubic(pp));
            int a = (int) (170 * (1 - pp));
            if (a <= 2) continue;
            g.setColor(withAlpha(accent, a));
            g.setStroke(new BasicStroke((float) Math.max(1, base * 0.06 * (1 - pp))));
            g.draw(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        }
    }

    private static void drawFlash(Graphics2D g, double w, double h, double p) {
        double r = Math.max(w, h) * (0.6 + 0.5 * p);
        int a = (int) (185 * Math.pow(1 - p, 2.2));
        if (a <= 2) return;
        try {
            RadialGradientPaint rg = new RadialGradientPaint(
                    new Point2D.Double(0, 0), (float) r,
                    new float[] { 0f, 1f },
                    new Color[] { new Color(255, 255, 255, a), new Color(255, 255, 255, 0) },
                    MultipleGradientPaint.CycleMethod.NO_CYCLE);
            g.setPaint(rg);
            g.fill(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        } catch (Exception ignored) { }
    }

    /** Rounded backing plate shared by the panel-shaped styles. */
    private static void drawPlate(Graphics2D g, SlideTimer t, double w, double h, double arc) {
        if (t.panelOpacity <= 0) return;
        Color pc = t.panelColor != null ? t.panelColor : new Color(10, 14, 24);
        RoundRectangle2D.Double rr = new RoundRectangle2D.Double(-w / 2, -h / 2, w, h, arc, arc);
        if (t.shadow) {
            g.setColor(new Color(0, 0, 0, (int) (90 * t.panelOpacity / 100.0)));
            g.fill(new RoundRectangle2D.Double(-w / 2 + h * 0.05, -h / 2 + h * 0.07, w, h, arc, arc));
        }
        int a = (int) (255 * clamp01(t.panelOpacity / 100.0));
        g.setPaint(new GradientPaint(
                0, (float) (-h / 2), withAlpha(brighten(pc, 0.22), a),
                0, (float) (h / 2), withAlpha(pc, a)));
        g.fill(rr);
        // top sheen + hairline edge — what sells the "glass" look
        g.setPaint(new GradientPaint(
                0, (float) (-h / 2), new Color(255, 255, 255, (int) (46 * a / 255.0)),
                0, (float) (0), new Color(255, 255, 255, 0)));
        g.fill(new RoundRectangle2D.Double(-w / 2, -h / 2, w, h * 0.55, arc, arc));
        g.setColor(new Color(255, 255, 255, (int) (52 * a / 255.0)));
        g.setStroke(new BasicStroke((float) Math.max(1, h * 0.018)));
        g.draw(rr);
    }

    /** Centre a string (with an optional soft shadow) on the given baseline box. */
    private static void drawCenteredText(Graphics2D g, SlideTimer t, String txt,
                                         double cx, double cy, double fontPx, Color col, boolean bold) {
        if (txt == null || txt.isEmpty()) return;
        Font f = new Font(t.fontName != null && !t.fontName.isEmpty() ? t.fontName : "SansSerif",
                bold ? Font.BOLD : Font.PLAIN, Math.max(6, (int) Math.round(fontPx)));
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        float x = (float) (cx - fm.stringWidth(txt) / 2.0);
        float y = (float) (cy + (fm.getAscent() - fm.getDescent()) / 2.0);
        if (t.shadow) {
            g.setColor(new Color(0, 0, 0, 130));
            g.drawString(txt, x + (float) (fontPx * 0.045), y + (float) (fontPx * 0.05));
        }
        g.setColor(col);
        g.drawString(txt, x, y);
    }

    private static void drawLabel(Graphics2D g, SlideTimer t, double cy, double fontPx, Color col) {
        if (t.label == null || t.label.trim().isEmpty()) return;
        drawCenteredText(g, t, t.label.trim(), 0, cy, fontPx, withAlpha(col, 205), false);
    }

    // ---- math / colour helpers --------------------------------------------
    private static String nz(String s) { return s == null ? "" : s; }
    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static double easeOutCubic(double p) { double q = 1 - clamp01(p); return 1 - q * q * q; }
    private static double easeOutBack(double p) {
        double c1 = 1.70158, c3 = c1 + 1, q = clamp01(p) - 1;
        return 1 + c3 * q * q * q + c1 * q * q;
    }
    private static double easeOutBounce(double p) {
        double x = clamp01(p), n1 = 7.5625, d1 = 2.75;
        if (x < 1 / d1) return n1 * x * x;
        if (x < 2 / d1) { x -= 1.5 / d1; return n1 * x * x + 0.75; }
        if (x < 2.5 / d1) { x -= 2.25 / d1; return n1 * x * x + 0.9375; }
        x -= 2.625 / d1; return n1 * x * x + 0.984375;
    }
    private static Color withAlpha(Color c, int a) {
        if (c == null) c = Color.WHITE;
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }
    private static Color brighten(Color c, double f) {
        if (c == null) c = Color.WHITE;
        return new Color(
                (int) Math.min(255, c.getRed() + (255 - c.getRed()) * clamp01(f)),
                (int) Math.min(255, c.getGreen() + (255 - c.getGreen()) * clamp01(f)),
                (int) Math.min(255, c.getBlue() + (255 - c.getBlue()) * clamp01(f)),
                c.getAlpha());
    }
    private static Color darken(Color c, double f) {
        if (c == null) c = Color.BLACK;
        double k = 1 - clamp01(f);
        return new Color((int) (c.getRed() * k), (int) (c.getGreen() * k), (int) (c.getBlue() * k), c.getAlpha());
    }
    private static Color scaleBrightness(Color c, double k) {
        if (c == null) c = Color.WHITE;
        return new Color(
                (int) Math.max(0, Math.min(255, c.getRed() * k)),
                (int) Math.max(0, Math.min(255, c.getGreen() * k)),
                (int) Math.max(0, Math.min(255, c.getBlue() * k)),
                c.getAlpha());
    }
    private static Color mix(Color a, Color b, double t) {
        if (a == null) return b; if (b == null) return a;
        double k = clamp01(t);
        return new Color(
                (int) (a.getRed()   + (b.getRed()   - a.getRed())   * k),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * k),
                (int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * k),
                (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * k));
    }
    private static Color hueShift(Color c, double delta) {
        if (c == null) c = Color.WHITE;
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        Color out = Color.getHSBColor((float) ((hsb[0] + delta) % 1.0), hsb[1], hsb[2]);
        return new Color(out.getRed(), out.getGreen(), out.getBlue(), c.getAlpha());
    }

    // ======================= the timer faces ===============================

    /** Dispatch to the chosen look. Everything is drawn centred on the origin. */
    private static void drawFace(Graphics2D g, SlideTimer t, double w, double h, double prog,
                                 String txt, Color accent, long local, int totalMs,
                                 boolean finished, double fin) {
        double remainFrac = 1 - prog;      // 1 → 0
        switch (nz(t.style)) {
            case "Glass Digital":     drawGlassDigital(g, t, w, h, remainFrac, txt, accent); break;
            case "Circular Progress": drawCircularProgress(g, t, w, h, remainFrac, txt, accent); break;
            case "Segmented Ring":    drawSegmentedRing(g, t, w, h, remainFrac, txt, accent); break;
            case "Dual Ring":         drawDualRing(g, t, w, h, remainFrac, txt, accent, local); break;
            case "Gauge Dial":        drawGaugeDial(g, t, w, h, remainFrac, txt, accent); break;
            case "Flip Clock":        drawFlipClock(g, t, w, h, txt, accent, local); break;
            case "LED Panel":         drawLedPanel(g, t, w, h, remainFrac, txt, accent); break;
            case "Sand Timer":        drawSandTimer(g, t, w, h, remainFrac, txt, accent, local); break;
            case "Radial Sweep":      drawRadialSweep(g, t, w, h, remainFrac, txt, accent); break;
            case "Minimal Bar":       drawMinimalBar(g, t, w, h, remainFrac, txt, accent); break;
            case "Pill Countdown":    drawPill(g, t, w, h, remainFrac, txt, accent, local); break;
            case "Dot Grid":          drawDotGrid(g, t, w, h, remainFrac, txt, accent); break;
            case "Wave Bar":          drawWaveBar(g, t, w, h, remainFrac, txt, accent, local); break;
            case "Analog Clock":      drawAnalogClock(g, t, w, h, remainFrac, txt, accent); break;
            case "Ticks Ring":        drawTicksRing(g, t, w, h, remainFrac, txt, accent); break;
            case "Chrome Badge":      drawChromeBadge(g, t, w, h, remainFrac, txt, accent); break;
            case "Frosted Card":      drawFrostedCard(g, t, w, h, remainFrac, txt, accent); break;
            case "Neon Ring":
            default:                  drawNeonRing(g, t, w, h, remainFrac, txt, accent, local); break;
        }
    }

    /** Glowing arc that drains anticlockwise-from-12, with a comet head and centre digits. */
    private static void drawNeonRing(Graphics2D g, SlideTimer t, double w, double h,
                                     double frac, String txt, Color accent, long local) {
        double r = Math.min(w, h) / 2 * 0.88;
        double sw = r * 0.16;
        // dark backing disc keeps the digits readable over any photo
        if (t.panelOpacity > 0) {
            g.setColor(withAlpha(t.panelColor != null ? t.panelColor : Color.BLACK,
                    (int) (200 * t.panelOpacity / 100.0)));
            g.fill(new Ellipse2D.Double(-r - sw * 0.5, -r - sw * 0.5, (r + sw * 0.5) * 2, (r + sw * 0.5) * 2));
        }
        g.setColor(t.trackColor != null ? t.trackColor : new Color(255, 255, 255, 50));
        g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(-r, -r, r * 2, r * 2));

        double extent = -360.0 * frac;
        Shape arc = new Arc2D.Double(-r, -r, r * 2, r * 2, 90, extent, Arc2D.OPEN);
        // three fading passes fake a neon bloom around the live arc
        for (int i = 3; i >= 1; i--) {
            g.setColor(withAlpha(accent, 34 * i / 3));
            g.setStroke(new BasicStroke((float) (sw * (1 + i * 0.55)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(arc);
        }
        g.setPaint(new GradientPaint((float) -r, (float) -r, brighten(accent, 0.45),
                (float) r, (float) r, accent));
        g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arc);

        if (frac > 0.001) {
            double a = Math.toRadians(90 + extent);
            double hx = Math.cos(a) * r, hy = -Math.sin(a) * r;
            g.setColor(withAlpha(Color.WHITE, 235));
            g.fill(new Ellipse2D.Double(hx - sw * 0.34, hy - sw * 0.34, sw * 0.68, sw * 0.68));
            g.setColor(withAlpha(brighten(accent, 0.5), 120));
            g.fill(new Ellipse2D.Double(hx - sw * 0.72, hy - sw * 0.72, sw * 1.44, sw * 1.44));
        }
        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        drawCenteredText(g, t, txt, 0, hasLabel ? -r * 0.18 : 0, r * 0.86, t.textColor, true);
        drawLabel(g, t, r * 0.54, r * 0.22, t.textColor);
    }

    /** Frosted glass slab: big digits, thin depleting underline, optional caption. */
    private static void drawGlassDigital(Graphics2D g, SlideTimer t, double w, double h,
                                         double frac, String txt, Color accent) {
        drawPlate(g, t, w, h, h * 0.34);
        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        drawCenteredText(g, t, txt, 0, hasLabel ? -h * 0.10 : -h * 0.04, h * 0.60, t.textColor, true);
        drawLabel(g, t, h * 0.26, h * 0.14, t.textColor);
        double bw = w * 0.82, bh = Math.max(2, h * 0.055), by = h / 2 - bh * 2.1;
        g.setColor(withAlpha(t.trackColor, 90));
        g.fill(new RoundRectangle2D.Double(-bw / 2, by, bw, bh, bh, bh));
        double fw = bw * frac;
        if (fw > 0.5) {
            g.setPaint(new GradientPaint((float) (-bw / 2), 0, brighten(accent, 0.35),
                    (float) (bw / 2), 0, accent));
            g.fill(new RoundRectangle2D.Double(-bw / 2, by, fw, bh, bh, bh));
        }
    }

    /** Flat, thick ring with rounded caps over a muted track — the classic app look. */
    private static void drawCircularProgress(Graphics2D g, SlideTimer t, double w, double h,
                                             double frac, String txt, Color accent) {
        double r = Math.min(w, h) / 2 * 0.86;
        double sw = r * 0.22;
        if (t.panelOpacity > 0) {
            g.setColor(withAlpha(t.panelColor, (int) (190 * t.panelOpacity / 100.0)));
            g.fill(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        }
        g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(t.trackColor != null ? t.trackColor : new Color(255, 255, 255, 55));
        g.draw(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(-r, -r), new Point2D.Double(r, r),
                new float[] { 0f, 1f }, new Color[] { brighten(accent, 0.4), darken(accent, 0.12) }));
        g.draw(new Arc2D.Double(-r, -r, r * 2, r * 2, 90, -360.0 * frac, Arc2D.OPEN));
        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        drawCenteredText(g, t, txt, 0, hasLabel ? -r * 0.18 : 0, r * 0.76, t.textColor, true);
        drawLabel(g, t, r * 0.52, r * 0.21, t.textColor);
    }

    /** One lit segment per second — reads as "how many seconds are left" at a glance. */
    private static void drawSegmentedRing(Graphics2D g, SlideTimer t, double w, double h,
                                          double frac, String txt, Color accent) {
        double r = Math.min(w, h) / 2 * 0.88;
        double sw = r * 0.20;
        int n = Math.max(4, Math.min(60, t.seconds));
        double gap = Math.min(6.0, 240.0 / n);
        double step = 360.0 / n;
        double lit = frac * n;
        if (t.panelOpacity > 0) {
            g.setColor(withAlpha(t.panelColor, (int) (190 * t.panelOpacity / 100.0)));
            g.fill(new Ellipse2D.Double(-r - sw / 2, -r - sw / 2, (r + sw / 2) * 2, (r + sw / 2) * 2));
        }
        g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < n; i++) {
            double start = 90 - i * step - gap / 2;
            boolean on = i < Math.ceil(lit - 1e-6);
            double partial = on ? Math.min(1, lit - i) : 0;
            g.setColor(t.trackColor != null ? t.trackColor : new Color(255, 255, 255, 45));
            g.draw(new Arc2D.Double(-r, -r, r * 2, r * 2, start, -(step - gap), Arc2D.OPEN));
            if (on) {
                g.setColor(partial < 1 ? withAlpha(accent, (int) (255 * Math.max(0.25, partial))) : accent);
                g.draw(new Arc2D.Double(-r, -r, r * 2, r * 2, start, -(step - gap) * Math.max(0.12, partial), Arc2D.OPEN));
            }
        }
        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        drawCenteredText(g, t, txt, 0, hasLabel ? -r * 0.18 : 0, r * 0.76, t.textColor, true);
        drawLabel(g, t, r * 0.52, r * 0.21, t.textColor);
    }

    /** Outer ring drains clockwise while a thinner inner ring fills the other way. */
    private static void drawDualRing(Graphics2D g, SlideTimer t, double w, double h,
                                     double frac, String txt, Color accent, long local) {
        double r = Math.min(w, h) / 2 * 0.90;
        double sw = r * 0.13;
        if (t.panelOpacity > 0) {
            g.setColor(withAlpha(t.panelColor, (int) (185 * t.panelOpacity / 100.0)));
            g.fill(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        }
        g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(t.trackColor, 70));
        g.draw(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        g.setColor(accent);
        g.draw(new Arc2D.Double(-r, -r, r * 2, r * 2, 90, -360.0 * frac, Arc2D.OPEN));

        double r2 = r * 0.70, sw2 = sw * 0.62;
        g.setStroke(new BasicStroke((float) sw2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(t.trackColor, 55));
        g.draw(new Ellipse2D.Double(-r2, -r2, r2 * 2, r2 * 2));
        g.setColor(withAlpha(brighten(accent, 0.55), 210));
        g.draw(new Arc2D.Double(-r2, -r2, r2 * 2, r2 * 2, 90, 360.0 * (1 - frac), Arc2D.OPEN));

        // a slim orbiting marker keeps the face alive between seconds
        double a = Math.toRadians(90 - (local % 2400) / 2400.0 * 360.0);
        g.setColor(withAlpha(Color.WHITE, 150));
        g.fill(new Ellipse2D.Double(Math.cos(a) * r2 - sw2 * 0.4, -Math.sin(a) * r2 - sw2 * 0.4, sw2 * 0.8, sw2 * 0.8));

        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        drawCenteredText(g, t, txt, 0, hasLabel ? -r * 0.10 : 0, r * 0.66, t.textColor, true);
        drawLabel(g, t, r * 0.44, r * 0.20, t.textColor);
    }

    /** 240° speedometer-style gauge with graduated ticks and a needle. */
    private static void drawGaugeDial(Graphics2D g, SlideTimer t, double w, double h,
                                      double frac, String txt, Color accent) {
        double r = Math.min(w, h) / 2 * 0.92;
        double sw = r * 0.15;
        final double SPAN = 250, START = 215;   // sweep from lower-left to lower-right
        if (t.panelOpacity > 0) {
            g.setColor(withAlpha(t.panelColor, (int) (195 * t.panelOpacity / 100.0)));
            g.fill(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        }
        g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(t.trackColor, 80));
        g.draw(new Arc2D.Double(-r, -r, r * 2, r * 2, START, -SPAN, Arc2D.OPEN));
        g.setPaint(new LinearGradientPaint(
                new Point2D.Double(-r, 0), new Point2D.Double(r, 0),
                new float[] { 0f, 1f }, new Color[] { accent, brighten(accent, 0.5) }));
        g.draw(new Arc2D.Double(-r, -r, r * 2, r * 2, START, -SPAN * frac, Arc2D.OPEN));

        int ticks = 11;
        g.setStroke(new BasicStroke((float) Math.max(1, r * 0.022), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < ticks; i++) {
            double a = Math.toRadians(START - SPAN * i / (ticks - 1.0));
            double r0 = r * 0.72, r1 = r * 0.80;
            g.setColor(withAlpha(Color.WHITE, i % 5 == 0 ? 190 : 95));
            g.draw(new java.awt.geom.Line2D.Double(Math.cos(a) * r0, -Math.sin(a) * r0,
                    Math.cos(a) * r1, -Math.sin(a) * r1));
        }
        double na = Math.toRadians(START - SPAN * frac);
        g.setStroke(new BasicStroke((float) Math.max(1.5, r * 0.045), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(Color.BLACK, 120));
        g.draw(new java.awt.geom.Line2D.Double(0, 0, Math.cos(na) * r * 0.66 + r * 0.02, -Math.sin(na) * r * 0.66 + r * 0.02));
        g.setColor(brighten(accent, 0.25));
        g.draw(new java.awt.geom.Line2D.Double(0, 0, Math.cos(na) * r * 0.66, -Math.sin(na) * r * 0.66));
        g.setColor(withAlpha(Color.WHITE, 230));
        g.fill(new Ellipse2D.Double(-r * 0.07, -r * 0.07, r * 0.14, r * 0.14));

        drawCenteredText(g, t, txt, 0, r * 0.42, r * 0.46, t.textColor, true);
        drawLabel(g, t, r * 0.76, r * 0.18, t.textColor);
    }

    /** Split-flap cards, one per character, with a hinge line and a per-second flip. */
    private static void drawFlipClock(Graphics2D g, SlideTimer t, double w, double h,
                                      String txt, Color accent, long local) {
        String s = (txt == null || txt.isEmpty()) ? "0" : txt;
        int n = s.length();
        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        double ch = hasLabel ? h * 0.80 : h;          // leave room for the caption
        double cy = hasLabel ? -h * 0.10 : 0;
        // Separators get a narrow cell so the digits themselves stay card-shaped.
        double units = 0;
        for (int i = 0; i < n; i++) units += (s.charAt(i) == ':' || s.charAt(i) == '.') ? 0.42 : 1.0;
        double gap = w * 0.030;
        double unit = (w - gap * (n - 1)) / units;
        double flip = 1 - clamp01(((local % 1000) / 1000.0) / 0.22);   // 1 → 0 over the first 220 ms
        double x = -w / 2;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean sep = (c == ':' || c == '.');
            double cw = unit * (sep ? 0.42 : 1.0);
            if (sep) {
                drawCenteredText(g, t, String.valueOf(c), x + cw / 2, cy, ch * 0.58, t.textColor, true);
                x += cw + gap;
                continue;
            }
            RoundRectangle2D.Double card =
                    new RoundRectangle2D.Double(x, cy - ch / 2, cw, ch, cw * 0.18, cw * 0.18);
            if (t.shadow) {
                g.setColor(new Color(0, 0, 0, 120));
                g.fill(new RoundRectangle2D.Double(x + cw * 0.05, cy - ch / 2 + ch * 0.04,
                        cw, ch, cw * 0.18, cw * 0.18));
            }
            Color base = t.panelColor != null ? t.panelColor : new Color(18, 20, 26);
            g.setPaint(new GradientPaint(0, (float) (cy - ch / 2), brighten(base, 0.30),
                    0, (float) (cy + ch / 2), darken(base, 0.25)));
            g.fill(card);
            drawCenteredText(g, t, String.valueOf(c), x + cw / 2, cy, ch * 0.66, t.textColor, true);
            if (flip > 0) {
                // The upper leaf folding down: a translucent sheet hinged at the
                // middle, so the digit stays readable through the flip.
                Shape old = g.getClip();
                g.clip(new Rectangle2D.Double(x, cy - ch / 2, cw, ch / 2));
                AffineTransform sv = g.getTransform();
                g.translate(x + cw / 2, cy);
                g.scale(1, Math.max(0.02, flip));
                g.translate(-(x + cw / 2), -cy);
                g.setPaint(new GradientPaint(0, (float) (cy - ch / 2), withAlpha(darken(base, 0.10), 150),
                        0, (float) cy, withAlpha(darken(base, 0.45), 205)));
                g.fill(new RoundRectangle2D.Double(x, cy - ch / 2, cw, ch / 2, cw * 0.18, cw * 0.18));
                g.setTransform(sv);
                g.setClip(old);
            }
            // hinge seam last so the fold never paints over it
            g.setColor(new Color(0, 0, 0, 170));
            g.fill(new Rectangle2D.Double(x, cy - ch * 0.020, cw, ch * 0.040));
            g.setColor(withAlpha(accent, 110));
            g.fill(new Rectangle2D.Double(x, cy - ch * 0.020, cw, ch * 0.010));
            g.setColor(withAlpha(accent, 130));
            g.setStroke(new BasicStroke((float) Math.max(1, cw * 0.035)));
            g.draw(card);
            x += cw + gap;
        }
        if (hasLabel) drawLabel(g, t, h * 0.40, h * 0.13, t.textColor);
    }

    /** Dark panel with seven-segment digits, lit segments in the accent colour. */
    private static void drawLedPanel(Graphics2D g, SlideTimer t, double w, double h,
                                     double frac, String txt, Color accent) {
        drawPlate(g, t, w, h, h * 0.20);
        String s = (txt == null || txt.isEmpty()) ? "0" : txt;
        int n = s.length();
        // A seven-segment glyph is roughly 3:5, so drive the size off the panel
        // height and only shrink when a long string (e.g. "12:05") won't fit.
        double dh = h * 0.66;
        double dw = dh * 0.58;
        double gap = dw * 0.26;
        double totalW = n * dw + (n - 1) * gap;
        double maxW = w * 0.84;
        if (totalW > maxW) {
            double k = maxW / totalW;
            dw *= k; dh *= k; gap *= k; totalW = maxW;
        }
        double x = -totalW / 2;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            double cw = (c == ':' || c == '.') ? dw * 0.45 : dw;
            drawSevenSeg(g, c, x + (dw - cw) / 2, -dh / 2 - h * 0.04, cw, dh, accent,
                    withAlpha(t.trackColor != null ? t.trackColor : Color.GRAY, 26));
            x += dw + gap;
        }
        double bw = w * 0.80, bh = Math.max(2, h * 0.05), by = h / 2 - bh * 2.4;
        g.setColor(withAlpha(t.trackColor, 70));
        g.fill(new RoundRectangle2D.Double(-bw / 2, by, bw, bh, bh, bh));
        g.setColor(accent);
        g.fill(new RoundRectangle2D.Double(-bw / 2, by, Math.max(0, bw * frac), bh, bh, bh));
    }

    /** Classic seven-segment glyph. Unknown characters fall back to a dash. */
    private static void drawSevenSeg(Graphics2D g, char c, double x, double y, double w, double h,
                                     Color on, Color off) {
        boolean[] seg = segmentsFor(c);          // a b c d e f g
        double th = Math.min(w, h) * 0.17;       // segment thickness
        double m = th * 0.55;                    // gap between segments
        double innerW = w - th, innerH = h - th;
        double midY = y + h / 2;
        Shape[] shapes = new Shape[] {
                hSeg(x + th / 2, y + th / 2, innerW, th, m),                 // a  top
                vSeg(x + w - th / 2, y + th / 2, innerH / 2, th, m),         // b  top-right
                vSeg(x + w - th / 2, midY, innerH / 2, th, m),               // c  bottom-right
                hSeg(x + th / 2, y + h - th / 2, innerW, th, m),             // d  bottom
                vSeg(x + th / 2, midY, innerH / 2, th, m),                   // e  bottom-left
                vSeg(x + th / 2, y + th / 2, innerH / 2, th, m),             // f  top-left
                hSeg(x + th / 2, midY, innerW, th, m) };                     // g  middle
        for (int i = 0; i < 7; i++) {
            g.setColor(seg[i] ? on : off);
            g.fill(shapes[i]);
            if (seg[i]) {   // a soft bloom over lit segments
                g.setColor(withAlpha(brighten(on, 0.55), 95));
                g.fill(shapes[i]);
            }
        }
    }

    private static Shape hSeg(double x, double y, double len, double th, double m) {
        return new RoundRectangle2D.Double(x + m, y - th / 2, Math.max(1, len - m * 2), th, th * 0.8, th * 0.8);
    }
    private static Shape vSeg(double x, double y, double len, double th, double m) {
        return new RoundRectangle2D.Double(x - th / 2, y + m, th, Math.max(1, len - m * 2), th * 0.8, th * 0.8);
    }

    private static boolean[] segmentsFor(char c) {
        switch (c) {
            case '0': return new boolean[]{true,true,true,true,true,true,false};
            case '1': return new boolean[]{false,true,true,false,false,false,false};
            case '2': return new boolean[]{true,true,false,true,true,false,true};
            case '3': return new boolean[]{true,true,true,true,false,false,true};
            case '4': return new boolean[]{false,true,true,false,false,true,true};
            case '5': return new boolean[]{true,false,true,true,false,true,true};
            case '6': return new boolean[]{true,false,true,true,true,true,true};
            case '7': return new boolean[]{true,true,true,false,false,false,false};
            case '8': return new boolean[]{true,true,true,true,true,true,true};
            case '9': return new boolean[]{true,true,true,true,false,true,true};
            case ':': case '.': return new boolean[]{false,false,false,false,false,false,true};
            default:  return new boolean[]{false,false,false,false,false,false,true};
        }
    }

    /** Hourglass whose upper chamber drains into the lower one, with a falling stream. */
    private static void drawSandTimer(Graphics2D g, SlideTimer t, double w, double h,
                                      double frac, String txt, Color accent, long local) {
        // Stack the box: glass on top, digits under it, caption last — sized from
        // what is actually shown so no part can ever run into another.
        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        boolean hasText = txt != null && !txt.isEmpty();
        double labelH = hasLabel ? h * 0.13 : 0;
        double digitsH = hasText ? h * 0.20 : 0;
        double bh = h - digitsH - labelH;
        double bw = Math.min(w * 0.72, bh * 0.78);
        double neck = bw * 0.10;
        double glassCy = -h / 2 + bh / 2;

        AffineTransform svSand = g.getTransform();
        g.translate(0, glassCy);
        Path2D.Double top = new Path2D.Double();
        top.moveTo(-bw / 2, -bh / 2 + bh * 0.05); top.lineTo(bw / 2, -bh / 2 + bh * 0.05);
        top.lineTo(neck / 2, 0); top.lineTo(-neck / 2, 0); top.closePath();
        Path2D.Double bot = new Path2D.Double();
        bot.moveTo(-neck / 2, 0); bot.lineTo(neck / 2, 0);
        bot.lineTo(bw / 2, bh / 2 - bh * 0.05); bot.lineTo(-bw / 2, bh / 2 - bh * 0.05); bot.closePath();

        if (t.panelOpacity > 0) {
            g.setColor(withAlpha(t.panelColor, (int) (150 * t.panelOpacity / 100.0)));
            g.fill(top); g.fill(bot);
        }
        double half = bh / 2 - bh * 0.05;
        // sand in the top chamber drains toward the neck …
        Area topSand = new Area(top);
        topSand.intersect(new Area(new Rectangle2D.Double(-bw, -half + half * (1 - frac), bw * 2, bh)));
        g.setPaint(new GradientPaint(0, (float) -half, brighten(accent, 0.35), 0, 0, accent));
        g.fill(topSand);
        // … and piles up as a mound in the bottom one
        Area botSand = new Area(bot);
        botSand.intersect(new Area(new Rectangle2D.Double(-bw, half - half * (1 - frac), bw * 2, bh)));
        g.setPaint(new GradientPaint(0, 0, accent, 0, (float) half, darken(accent, 0.20)));
        g.fill(botSand);
        if (frac > 0.002) {   // the trickle
            double jitter = Math.sin(local / 45.0) * neck * 0.10;
            g.setColor(withAlpha(brighten(accent, 0.5), 200));
            g.fill(new Rectangle2D.Double(-neck * 0.10 + jitter, 0, neck * 0.20,
                    Math.max(0, half - half * (1 - frac))));
        }
        g.setColor(withAlpha(Color.WHITE, 180));
        g.setStroke(new BasicStroke((float) Math.max(1.2, bw * 0.020), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(top); g.draw(bot);
        double cap = bh * 0.05;
        g.setColor(withAlpha(t.textColor, 220));
        g.fill(new RoundRectangle2D.Double(-bw * 0.58, -bh / 2, bw * 1.16, cap * 1.4, cap, cap));
        g.fill(new RoundRectangle2D.Double(-bw * 0.58, bh / 2 - cap * 1.4, bw * 1.16, cap * 1.4, cap, cap));
        g.setTransform(svSand);

        if (hasText) {
            drawCenteredText(g, t, txt, 0, -h / 2 + bh + digitsH / 2, digitsH * 0.95, t.textColor, true);
        }
        if (hasLabel) {
            drawCenteredText(g, t, t.label.trim(), 0, -h / 2 + bh + digitsH + labelH / 2,
                    labelH * 0.80, withAlpha(t.textColor, 205), false);
        }
    }

    /** Pie wedge of remaining time over a soft disc — bold and instantly readable. */
    private static void drawRadialSweep(Graphics2D g, SlideTimer t, double w, double h,
                                        double frac, String txt, Color accent) {
        double r = Math.min(w, h) / 2 * 0.92;
        if (t.panelOpacity > 0) {
            g.setColor(withAlpha(t.panelColor, (int) (200 * t.panelOpacity / 100.0)));
            g.fill(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        }
        g.setColor(withAlpha(t.trackColor, 70));
        g.fill(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        g.setPaint(new GradientPaint((float) -r, (float) -r, brighten(accent, 0.30),
                (float) r, (float) r, darken(accent, 0.10)));
        g.fill(new Arc2D.Double(-r, -r, r * 2, r * 2, 90, -360.0 * frac, Arc2D.PIE));
        g.setColor(withAlpha(Color.WHITE, 200));
        g.setStroke(new BasicStroke((float) Math.max(1.2, r * 0.045)));
        g.draw(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        // a knocked-out centre disc so the digits stay legible on top of the wedge
        double ir = r * 0.52;
        g.setColor(withAlpha(t.panelColor != null ? t.panelColor : Color.BLACK, 215));
        g.fill(new Ellipse2D.Double(-ir, -ir, ir * 2, ir * 2));
        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        drawCenteredText(g, t, txt, 0, hasLabel ? -ir * 0.22 : 0, ir * 0.98, t.textColor, true);
        drawLabel(g, t, ir * 0.64, ir * 0.30, t.textColor);
    }

    /** Slim depleting bar with the digits sitting at its right end. */
    private static void drawMinimalBar(Graphics2D g, SlideTimer t, double w, double h,
                                       double frac, String txt, Color accent) {
        boolean showNum = txt != null && !txt.isEmpty();
        double numW = showNum ? h * 1.9 : 0;
        double bw = w - numW;
        double bh = h * 0.62;
        double bx = -w / 2;
        if (t.panelOpacity > 0) {
            g.setColor(withAlpha(t.panelColor, (int) (150 * t.panelOpacity / 100.0)));
            g.fill(new RoundRectangle2D.Double(-w / 2 - h * 0.22, -h * 0.62, w + h * 0.44, h * 1.24, h, h));
        }
        g.setColor(withAlpha(t.trackColor, 110));
        g.fill(new RoundRectangle2D.Double(bx, -bh / 2, bw, bh, bh, bh));
        double fillW = Math.max(0, bw * frac);
        if (fillW > 0.5) {
            g.setPaint(new GradientPaint((float) bx, 0, brighten(accent, 0.45),
                    (float) (bx + bw), 0, accent));
            g.fill(new RoundRectangle2D.Double(bx, -bh / 2, fillW, bh, bh, bh));
            g.setColor(withAlpha(Color.WHITE, 90));
            g.fill(new RoundRectangle2D.Double(bx, -bh / 2, fillW, bh * 0.42, bh, bh));
        }
        if (showNum) drawCenteredText(g, t, txt, bx + bw + numW / 2, 0, h * 0.92, t.textColor, true);
    }

    /** Compact pill: a blinking status dot next to the digits. Good for corners. */
    private static void drawPill(Graphics2D g, SlideTimer t, double w, double h,
                                 double frac, String txt, Color accent, long local) {
        drawPlate(g, t, w, h, h);
        double dr = h * 0.20;
        double dx = -w / 2 + h * 0.52;
        double blink = 0.45 + 0.55 * Math.abs(Math.sin(Math.PI * (local % 1000) / 1000.0));
        g.setColor(withAlpha(accent, (int) (90 * blink)));
        g.fill(new Ellipse2D.Double(dx - dr * 1.9, -dr * 1.9, dr * 3.8, dr * 3.8));
        g.setColor(withAlpha(accent, (int) (255 * blink)));
        g.fill(new Ellipse2D.Double(dx - dr, -dr, dr * 2, dr * 2));
        drawCenteredText(g, t, txt, dx + h * 0.30 + (w - h) / 2 * 0.55, 0, h * 0.55, t.textColor, true);
        double bw = w * 0.86, bh = Math.max(1.5, h * 0.05);
        g.setColor(withAlpha(t.trackColor, 70));
        g.fill(new RoundRectangle2D.Double(-bw / 2, h / 2 - bh * 2.2, bw, bh, bh, bh));
        g.setColor(accent);
        g.fill(new RoundRectangle2D.Double(-bw / 2, h / 2 - bh * 2.2, Math.max(0, bw * frac), bh, bh, bh));
    }

    /** A grid of dots, one per second, going dark as the time is spent. */
    private static void drawDotGrid(Graphics2D g, SlideTimer t, double w, double h,
                                    double frac, String txt, Color accent) {
        int n = Math.max(1, Math.min(60, t.seconds));
        int cols = (int) Math.ceil(Math.sqrt(n * (w / Math.max(1, h))));
        cols = Math.max(1, Math.min(n, cols));
        int rows = (int) Math.ceil(n / (double) cols);
        boolean showNum = txt != null && !txt.isEmpty();
        double gridH = showNum ? h * 0.66 : h;
        double cellW = w / cols, cellH = gridH / rows;
        double r = Math.min(cellW, cellH) * 0.34;
        double lit = frac * n;
        for (int i = 0; i < n; i++) {
            int rIdx = i / cols, cIdx = i % cols;
            double x = -w / 2 + cellW * (cIdx + 0.5);
            double y = -h / 2 + cellH * (rIdx + 0.5);
            boolean on = i < Math.ceil(lit - 1e-6);
            if (on) {
                g.setColor(withAlpha(accent, 70));
                g.fill(new Ellipse2D.Double(x - r * 1.7, y - r * 1.7, r * 3.4, r * 3.4));
                g.setColor(accent);
            } else {
                g.setColor(withAlpha(t.trackColor, 90));
            }
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }
        if (showNum) drawCenteredText(g, t, txt, 0, h / 2 - h * 0.16, h * 0.30, t.textColor, true);
    }

    /** Rounded tank whose water level is the remaining time, with a live surface wave. */
    private static void drawWaveBar(Graphics2D g, SlideTimer t, double w, double h,
                                    double frac, String txt, Color accent, long local) {
        RoundRectangle2D.Double body = new RoundRectangle2D.Double(-w / 2, -h / 2, w, h, h * 0.5, h * 0.5);
        if (t.panelOpacity > 0) {
            g.setColor(withAlpha(t.panelColor, (int) (190 * t.panelOpacity / 100.0)));
            g.fill(body);
        }
        Shape old = g.getClip();
        g.clip(body);
        double level = -w / 2 + w * frac;
        Path2D.Double wave = new Path2D.Double();
        wave.moveTo(-w / 2, h / 2);
        wave.lineTo(-w / 2, -h / 2);
        double amp = h * 0.10, len = w * 0.30;
        wave.lineTo(level, -h / 2);
        for (double y = -h / 2; y <= h / 2; y += Math.max(1, h / 24)) {
            wave.lineTo(level + Math.sin((y / len) * Math.PI * 2 + local / 220.0) * amp, y);
        }
        wave.lineTo(level, h / 2);
        wave.closePath();
        g.setPaint(new GradientPaint((float) (-w / 2), 0, brighten(accent, 0.40), (float) level, 0, accent));
        g.fill(wave);
        g.setColor(withAlpha(Color.WHITE, 70));
        g.fill(new RoundRectangle2D.Double(-w / 2, -h / 2, w, h * 0.34, h * 0.4, h * 0.4));
        g.setClip(old);
        g.setColor(withAlpha(Color.WHITE, 150));
        g.setStroke(new BasicStroke((float) Math.max(1, h * 0.05)));
        g.draw(body);
        drawCenteredText(g, t, txt, 0, 0, h * 0.62, t.textColor, true);
    }

    /** Analog face: minute ticks plus a hand sweeping through the remaining arc. */
    private static void drawAnalogClock(Graphics2D g, SlideTimer t, double w, double h,
                                        double frac, String txt, Color accent) {
        double r = Math.min(w, h) / 2 * 0.94;
        g.setColor(withAlpha(t.panelColor != null ? t.panelColor : new Color(245, 245, 248),
                (int) (235 * Math.max(0.35, t.panelOpacity / 100.0))));
        g.fill(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        g.setColor(withAlpha(accent, 230));
        g.setStroke(new BasicStroke((float) Math.max(1.5, r * 0.055)));
        g.draw(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        // remaining sector, drawn faintly under the ticks
        g.setColor(withAlpha(accent, 70));
        g.fill(new Arc2D.Double(-r * 0.92, -r * 0.92, r * 1.84, r * 1.84, 90, -360.0 * frac, Arc2D.PIE));
        for (int i = 0; i < 60; i++) {
            double a = Math.toRadians(90 - i * 6);
            boolean major = i % 5 == 0;
            double r0 = r * (major ? 0.76 : 0.84), r1 = r * 0.90;
            g.setColor(withAlpha(t.textColor, major ? 235 : 120));
            g.setStroke(new BasicStroke((float) Math.max(1, r * (major ? 0.035 : 0.016)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Line2D.Double(Math.cos(a) * r0, -Math.sin(a) * r0,
                    Math.cos(a) * r1, -Math.sin(a) * r1));
        }
        double ha = Math.toRadians(90 - 360.0 * frac);
        g.setStroke(new BasicStroke((float) Math.max(1.5, r * 0.045), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(accent);
        g.draw(new java.awt.geom.Line2D.Double(-Math.cos(ha) * r * 0.12, Math.sin(ha) * r * 0.12,
                Math.cos(ha) * r * 0.78, -Math.sin(ha) * r * 0.78));
        g.setColor(withAlpha(t.textColor, 245));
        g.fill(new Ellipse2D.Double(-r * 0.06, -r * 0.06, r * 0.12, r * 0.12));
        if (txt != null && !txt.isEmpty()) {
            drawCenteredText(g, t, txt, 0, r * 0.42, r * 0.34, t.textColor, true);
        }
        drawLabel(g, t, -r * 0.42, r * 0.16, t.textColor);
    }

    /** Radial tick marks around the digits — each tick is a slice of the countdown. */
    private static void drawTicksRing(Graphics2D g, SlideTimer t, double w, double h,
                                      double frac, String txt, Color accent) {
        double r = Math.min(w, h) / 2 * 0.94;
        if (t.panelOpacity > 0) {
            g.setColor(withAlpha(t.panelColor, (int) (190 * t.panelOpacity / 100.0)));
            g.fill(new Ellipse2D.Double(-r * 0.86, -r * 0.86, r * 1.72, r * 1.72));
        }
        int n = 48;
        double lit = frac * n;
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(90 - i * 360.0 / n);
            boolean on = i < Math.ceil(lit - 1e-6);
            double r0 = r * (on ? 0.74 : 0.80), r1 = r;
            g.setColor(on ? accent : withAlpha(t.trackColor, 80));
            g.setStroke(new BasicStroke((float) Math.max(1, r * (on ? 0.045 : 0.028)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Line2D.Double(Math.cos(a) * r0, -Math.sin(a) * r0,
                    Math.cos(a) * r1, -Math.sin(a) * r1));
        }
        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        drawCenteredText(g, t, txt, 0, hasLabel ? -r * 0.18 : 0, r * 0.74, t.textColor, true);
        drawLabel(g, t, r * 0.50, r * 0.20, t.textColor);
    }

    /** Metallic bevelled medal with an inset progress ring. */
    private static void drawChromeBadge(Graphics2D g, SlideTimer t, double w, double h,
                                        double frac, String txt, Color accent) {
        double r = Math.min(w, h) / 2 * 0.94;
        if (t.shadow) {
            g.setColor(new Color(0, 0, 0, 110));
            g.fill(new Ellipse2D.Double(-r + r * 0.05, -r + r * 0.07, r * 2, r * 2));
        }
        try {
            g.setPaint(new LinearGradientPaint(
                    new Point2D.Double(0, -r), new Point2D.Double(0, r),
                    new float[] { 0f, 0.45f, 0.55f, 1f },
                    new Color[] { new Color(238, 240, 246), new Color(150, 156, 168),
                                  new Color(108, 114, 128), new Color(206, 210, 220) }));
        } catch (Exception ex) {
            g.setPaint(new Color(170, 176, 188));
        }
        g.fill(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        double ir = r * 0.80;
        g.setColor(withAlpha(t.panelColor != null ? t.panelColor : new Color(16, 18, 24), 245));
        g.fill(new Ellipse2D.Double(-ir, -ir, ir * 2, ir * 2));
        double sw = r * 0.11, pr = ir * 0.86;
        g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(t.trackColor, 70));
        g.draw(new Ellipse2D.Double(-pr, -pr, pr * 2, pr * 2));
        g.setColor(accent);
        g.draw(new Arc2D.Double(-pr, -pr, pr * 2, pr * 2, 90, -360.0 * frac, Arc2D.OPEN));
        g.setColor(new Color(255, 255, 255, 46));   // top-light bevel highlight
        g.fill(new Arc2D.Double(-r * 0.96, -r * 0.96, r * 1.92, r * 0.90, 20, 140, Arc2D.CHORD));
        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        drawCenteredText(g, t, txt, 0, hasLabel ? -ir * 0.20 : 0, ir * 0.80, t.textColor, true);
        drawLabel(g, t, ir * 0.56, ir * 0.22, t.textColor);
    }

    /** Wide frosted card: a ring on the left, digits and caption stacked on the right. */
    private static void drawFrostedCard(Graphics2D g, SlideTimer t, double w, double h,
                                        double frac, String txt, Color accent) {
        drawPlate(g, t, w, h, h * 0.22);
        double r = h * 0.34;
        double rx = -w / 2 + h * 0.42;
        double sw = r * 0.30;
        g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(t.trackColor, 90));
        g.draw(new Ellipse2D.Double(rx - r, -r, r * 2, r * 2));
        g.setColor(accent);
        g.draw(new Arc2D.Double(rx - r, -r, r * 2, r * 2, 90, -360.0 * frac, Arc2D.OPEN));

        double tx = rx + r + h * 0.22;
        double availW = (w / 2) - tx;
        boolean hasLabel = t.label != null && !t.label.trim().isEmpty();
        drawCenteredText(g, t, txt, tx + availW / 2, hasLabel ? -h * 0.10 : 0, h * 0.46, t.textColor, true);
        if (hasLabel) {
            drawCenteredText(g, t, t.label.trim(), tx + availW / 2, h * 0.22, h * 0.15,
                    withAlpha(t.textColor, 200), false);
        }
    }

    // ======================= sound =========================================
    //
    // The built-in timer sounds are synthesized here rather than shipped as
    // assets, exactly like GifSlideShowApp.MotionSound's whoosh: one PCM buffer
    // is rendered for the whole countdown (ticking bed + the one-shot at zero),
    // played through a Clip for the dialog's Preview button and written out as a
    // WAV that the exporter mixes onto the finished video with ffmpeg.

    private static final float SOUND_SR = 44100f;
    private static final javax.sound.sampled.AudioFormat SOUND_FORMAT =
            new javax.sound.sampled.AudioFormat(SOUND_SR, 16, 1, true, false);
    /** The dialog's preview clip, so a second press can stop the first. */
    private static javax.sound.sampled.Clip previewClip;

    /** True when this timer would contribute a sound to the export. */
    public boolean hasSound() {
        if (!enabled || "None".equals(nz(soundMode)) || soundVolume <= 0) return false;
        if ("File".equals(nz(soundMode))) {
            return soundPath != null && !soundPath.trim().isEmpty() && new File(soundPath).isFile();
        }
        return !("None".equals(nz(soundName)) && "None".equals(nz(endSoundName)));
    }

    /**
     * Render the whole countdown soundtrack: the ticking bed for every second
     * plus the one-shot at zero. Mono, 44.1 kHz, 16-bit — ready for a WAV.
     */
    public byte[] renderPcm() {
        double tailSec = 2.0;
        int n = (int) (SOUND_SR * (totalMs() / 1000.0 + tailSec));
        double[] buf = new double[n];

        String tick = nz(soundName);
        if (!"None".equals(tick) && !tick.isEmpty()) {
            if ("Suspense Riser".equals(tick)) {
                addRiser(buf, 0, totalMs() / 1000.0);
            }
            for (int s = 0; s < seconds; s++) {
                double at = s;
                double toEnd = seconds - s;            // seconds left when this tick fires
                boolean urgent = toEnd <= Math.max(1, urgentSeconds);
                addTick(buf, tick, at, s, urgent);
            }
        }
        String end = nz(endSoundName);
        if (!"None".equals(end) && !end.isEmpty()) addEndSound(buf, end, totalMs() / 1000.0);

        double gain = clamp01(soundVolume / 100.0);
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double v = buf[i] * gain;
            // soft clip so stacked layers never crackle
            v = Math.tanh(v * 1.15);
            int s = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v * 32000));
            out[i * 2]     = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    /** Write {@link #renderPcm()} to a WAV in {@code dir} (for muxing with ffmpeg). */
    public File writeWav(File dir, String fileName) throws IOException {
        byte[] pcm = renderPcm();
        File f = new File(dir, fileName);
        try (javax.sound.sampled.AudioInputStream ais = new javax.sound.sampled.AudioInputStream(
                new java.io.ByteArrayInputStream(pcm), SOUND_FORMAT, pcm.length / SOUND_FORMAT.getFrameSize())) {
            javax.sound.sampled.AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, f);
        }
        return f;
    }

    /** Play the built-in countdown sound once (dialog "Preview sound" button). */
    public void playPreview() {
        stopPreview();
        try {
            byte[] pcm = renderPcm();
            javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
            clip.open(SOUND_FORMAT, pcm, 0, pcm.length);
            clip.addLineListener(ev -> {
                if (ev.getType() == javax.sound.sampled.LineEvent.Type.STOP) ev.getLine().close();
            });
            previewClip = clip;
            clip.start();
        } catch (Exception ignored) {
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }

    /** Play an external sound file once (dialog "Preview sound" with a File source). */
    public static boolean playFilePreview(File f) {
        stopPreview();
        if (f == null || !f.isFile()) return false;
        try (javax.sound.sampled.AudioInputStream in = javax.sound.sampled.AudioSystem.getAudioInputStream(f)) {
            javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
            clip.open(in);
            clip.addLineListener(ev -> {
                if (ev.getType() == javax.sound.sampled.LineEvent.Type.STOP) ev.getLine().close();
            });
            previewClip = clip;
            clip.start();
            return true;
        } catch (Exception ex) {
            return false;   // e.g. MP3 — Java can't decode it, but ffmpeg still can at export
        }
    }

    public static void stopPreview() {
        try {
            if (previewClip != null) { previewClip.stop(); previewClip.close(); }
        } catch (Exception ignored) { }
        previewClip = null;
    }

    // ---- synthesis primitives ---------------------------------------------

    private static void mix(double[] buf, int at, double v) {
        if (at >= 0 && at < buf.length) buf[at] += v;
    }

    /** Damped sine partial: amp · e^(−decay·t) · sin(2πf t), optionally swept to {@code f2}. */
    private static void addPartial(double[] buf, double atSec, double durSec,
                                   double f, double f2, double amp, double decay) {
        int start = (int) (atSec * SOUND_SR);
        int n = (int) (durSec * SOUND_SR);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double p = i / (double) n;
            double freq = f + (f2 - f) * p;
            phase += 2 * Math.PI * freq / SOUND_SR;
            double env = Math.exp(-decay * (i / SOUND_SR));
            // 4 ms fade-in kills the click at the attack
            double fin = Math.min(1, i / (SOUND_SR * 0.004));
            mix(buf, start + i, Math.sin(phase) * amp * env * fin);
        }
    }

    /** Short filtered-noise burst — the "body" of clicks, whooshes and buzzes. */
    private static void addNoise(double[] buf, double atSec, double durSec, double amp, double decay) {
        int start = (int) (atSec * SOUND_SR);
        int n = (int) (durSec * SOUND_SR);
        double last = 0;
        for (int i = 0; i < n; i++) {
            double white = Math.random() * 2 - 1;
            last = last * 0.55 + white * 0.45;          // gentle low-pass so it isn't hissy
            double env = Math.exp(-decay * (i / SOUND_SR));
            mix(buf, start + i, last * amp * env);
        }
    }

    /** One tick of the chosen ticking bed at {@code atSec}. */
    private static void addTick(double[] buf, String kind, double atSec, int index, boolean urgent) {
        double u = urgent ? 1.35 : 1.0;
        switch (kind) {
            case "Tick-Tock":
                addPartial(buf, atSec, 0.055, index % 2 == 0 ? 1550 : 1180, index % 2 == 0 ? 900 : 700, 0.30 * u, 42);
                addNoise(buf, atSec, 0.030, 0.16 * u, 130);
                break;
            case "Clock Ticks":
                addNoise(buf, atSec, 0.028, 0.30 * u, 190);
                addPartial(buf, atSec, 0.045, 2400, 1600, 0.16 * u, 80);
                break;
            case "Soft Beep":
                addPartial(buf, atSec, 0.13, 880, 880, 0.26 * u, 16);
                addPartial(buf, atSec, 0.13, 1760, 1760, 0.07 * u, 20);
                break;
            case "Digital Blips":
                addPartial(buf, atSec, 0.07, 1480, 1480, 0.24 * u, 34);
                addPartial(buf, atSec, 0.07, 2960, 2960, 0.09 * u, 40);
                break;
            case "Heartbeat":
                addPartial(buf, atSec, 0.18, 78, 46, 0.42 * u, 16);
                addPartial(buf, atSec + 0.28, 0.16, 66, 40, 0.30 * u, 18);
                break;
            case "Metronome":
                addNoise(buf, atSec, 0.020, 0.26 * u, 240);
                addPartial(buf, atSec, 0.06, index % 2 == 0 ? 1900 : 1500, 1100, 0.22 * u, 60);
                break;
            case "Suspense Riser":
                addNoise(buf, atSec, 0.025, 0.12 * u, 200);
                break;
            case "Bubble Pops":
                addPartial(buf, atSec, 0.09, 420, 1500, 0.26 * u, 26);
                break;
            case "Sci-Fi Pulse":
                addPartial(buf, atSec, 0.16, 300, 1200, 0.22 * u, 14);
                addPartial(buf, atSec, 0.16, 600, 2400, 0.08 * u, 18);
                break;
            default:
                addPartial(buf, atSec, 0.05, 1400, 1000, 0.22 * u, 45);
                break;
        }
    }

    /** Slowly rising bed under the whole countdown ("Suspense Riser"). */
    private static void addRiser(double[] buf, double atSec, double durSec) {
        int start = (int) (atSec * SOUND_SR);
        int n = (int) (durSec * SOUND_SR);
        double phase = 0, phase2 = 0;
        for (int i = 0; i < n; i++) {
            double p = i / (double) n;
            double f = 110 * Math.pow(4.0, p);            // two octaves up over the countdown
            phase  += 2 * Math.PI * f / SOUND_SR;
            phase2 += 2 * Math.PI * (f * 1.5) / SOUND_SR;
            double env = 0.05 + 0.22 * p * p;             // swells toward zero
            double trem = 0.85 + 0.15 * Math.sin(2 * Math.PI * (3 + 9 * p) * i / SOUND_SR);
            mix(buf, start + i, (Math.sin(phase) + 0.35 * Math.sin(phase2)) * env * trem * 0.5);
        }
    }

    /** The one-shot that lands exactly on zero. */
    private static void addEndSound(double[] buf, String kind, double atSec) {
        switch (kind) {
            case "Chime":
                addPartial(buf, atSec, 1.6, 880, 880, 0.34, 3.0);
                addPartial(buf, atSec, 1.6, 1318, 1318, 0.20, 3.4);
                addPartial(buf, atSec + 0.10, 1.5, 1760, 1760, 0.14, 3.6);
                break;
            case "Bell":
                addPartial(buf, atSec, 1.9, 660, 658, 0.32, 2.2);
                addPartial(buf, atSec, 1.9, 1580, 1576, 0.16, 2.8);
                addPartial(buf, atSec, 1.9, 2490, 2480, 0.09, 3.4);
                addNoise(buf, atSec, 0.05, 0.10, 90);
                break;
            case "Ding":
                addPartial(buf, atSec, 0.9, 1046, 1046, 0.34, 5.0);
                addPartial(buf, atSec, 0.9, 2093, 2093, 0.12, 6.0);
                break;
            case "Gong":
                addPartial(buf, atSec, 2.0, 196, 190, 0.36, 1.5);
                addPartial(buf, atSec, 2.0, 311, 305, 0.18, 1.8);
                addPartial(buf, atSec, 2.0, 466, 452, 0.10, 2.2);
                addNoise(buf, atSec, 0.20, 0.14, 22);
                break;
            case "Buzzer":
                for (int k = 1; k <= 6; k += 2) {
                    addPartial(buf, atSec, 0.75, 210 * k, 205 * k, 0.22 / k, 3.2);
                }
                break;
            case "Power Down":
                addPartial(buf, atSec, 1.1, 900, 70, 0.30, 2.0);
                addPartial(buf, atSec, 1.1, 1350, 105, 0.12, 2.4);
                break;
            case "Whoosh":
                addNoise(buf, atSec, 0.55, 0.34, 6.5);
                addPartial(buf, atSec, 0.55, 320, 1400, 0.14, 5.0);
                break;
            case "Fanfare":
                addPartial(buf, atSec,        0.30, 523, 523, 0.28, 7.0);
                addPartial(buf, atSec + 0.14, 0.30, 659, 659, 0.28, 7.0);
                addPartial(buf, atSec + 0.28, 1.10, 784, 784, 0.30, 3.0);
                addPartial(buf, atSec + 0.28, 1.10, 1046, 1046, 0.16, 3.4);
                break;
            default:
                addPartial(buf, atSec, 1.0, 880, 880, 0.30, 4.0);
                break;
        }
    }
}
