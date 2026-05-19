import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quiz slide feature: a countdown timer + correct-answer reveal that bakes
 * into the exported video.
 *
 * Per-slide config (held by SlideRow as a `QuizSlide` instance):
 *   - enabled / disabled
 *   - which slide-text item is the correct option (1-based)
 *   - timer length in seconds (default 10), red-threshold (default 4)
 *   - the question narration audio (mandatory; timer starts when it ends)
 *   - tick sound  (stock preset OR custom file)
 *   - ding sound  (stock preset OR custom file)
 *
 * On Save the dialog calls a callback that attaches the generated combined
 * audio (question + tick*N + ding) to the slide via the existing per-slide
 * audio mechanism, so the slide auto-extends to fit and works with every
 * existing export path (GIF / MP4 normal / per-slide / separate / etc.).
 *
 * Visual overlay (countdown digits + reveal flash) is drawn by
 * {@link #applyOverlay} which the main app calls per-frame after the normal
 * frame has been rendered. The hook is a no-op when the slide is not a quiz.
 */
public class QuizSlide {

    // ===== Per-slide settings =====
    public boolean enabled = false;
    public int correctOptionIndex = 1;        // 1-based; refers to slide-text item index
    public int timerSeconds = 10;
    public int redThresholdSeconds = 4;

    public String tickPreset = "Stock: Classic Clock";   // or "Custom"
    public File   customTickFile = null;

    public String dingPreset = "Stock: Bell";            // or "Custom"
    public File   customDingFile = null;

    public File questionAudioFile = null;
    public int  questionAudioDurationMs = 0;

    // Generated combined audio (question + tick*N + ding); attached to slide.
    public File generatedAudioFile = null;
    public int  generatedAudioDurationMs = 0;

    // Cached question-end offset in ms (== questionAudioDurationMs at attach time).
    public int  questionEndMs = 0;

    // ---- Timer visual style (configured via the toolbar7c row) ----
    // Style: "Number Circle" (default), "Progress Bar H", "Progress Bar V",
    //        "Ring Arc", "Analog Clock".
    public String timerStyle = "Number Circle";
    // Anchor in % of frame size (0..100). Interpreted as the CENTER of the
    // timer for Circle / Ring / Clock, and as the TOP-LEFT for Progress Bars.
    public int timerXPct  = 92;
    public int timerYPct  = 12;
    // "Size" = diameter (% of frame height) for Circle / Ring / Clock,
    //          bar thickness (% of frame height) for Progress Bar H/V.
    public int timerSizePct  = 14;
    // "Width" = bar length (% of frame width for horiz, % of frame height
    //           for vert). Ignored for Circle / Ring / Clock.
    public int timerWidthPct = 30;
    // Shape of the progress bar fill+track. Ignored for non-bar styles.
    //   "Rounded" (default), "Square", "Pill", "Segmented".
    public String progressBarShape = "Rounded";
    // Accent color of the ring/arc/bar/hand. Switches to red for
    // the last `redThresholdSeconds` regardless of this setting.
    public Color timerColor = new Color(80, 200, 255);
    // Digit text color (independent of the accent). Red mode still overrides.
    public Color timerTextColor = Color.WHITE;
    // Font family for the countdown digit. Resolved against installed fonts.
    public String timerFont = "Segoe UI";
    // Optional label drawn above the digit (e.g., "Time:", "⏱ Remaining").
    public String timerLabel = "";

    // ---- Correct-answer reveal style ----
    // Badge shape next to the highlighted option.
    //   "Check" (default), "Star", "Crown", "Trophy", "Heart", "Thumbs Up", "None".
    public String revealMarkStyle = "Check";
    // 50..200 — scales the badge relative to the option's font size.
    public int    revealMarkSizePct = 100;
    // Color of the badge AND the highlight ring around the correct option.
    public Color  revealMarkColor = new Color(60, 220, 110);
    // 50..200 — scales the glow box padding around the correct option.
    public int    revealPadPct = 100;
    // When does the countdown start?
    //  "AfterQuestion" (default): timer is shown frozen during question audio;
    //                             counting begins when question audio ends.
    //  "AtSlideStart":            timer counts from t=0 alongside the question
    //                             audio; tick sound is mixed UNDER the question
    //                             so the narration stays clearly audible.
    public String timerStartMode = "AfterQuestion";

    public QuizSlide copy() {
        QuizSlide c = new QuizSlide();
        c.enabled = enabled;
        c.correctOptionIndex = correctOptionIndex;
        c.timerSeconds = timerSeconds;
        c.redThresholdSeconds = redThresholdSeconds;
        c.tickPreset = tickPreset;
        c.customTickFile = customTickFile;
        c.dingPreset = dingPreset;
        c.customDingFile = customDingFile;
        c.questionAudioFile = questionAudioFile;
        c.questionAudioDurationMs = questionAudioDurationMs;
        c.generatedAudioFile = generatedAudioFile;
        c.generatedAudioDurationMs = generatedAudioDurationMs;
        c.questionEndMs = questionEndMs;
        c.timerStyle     = timerStyle;
        c.timerXPct      = timerXPct;
        c.timerYPct      = timerYPct;
        c.timerSizePct   = timerSizePct;
        c.timerWidthPct  = timerWidthPct;
        c.timerColor     = timerColor;
        c.progressBarShape = progressBarShape;
        c.timerTextColor = timerTextColor;
        c.timerFont      = timerFont;
        c.timerLabel     = timerLabel;
        c.timerStartMode = timerStartMode;
        c.revealMarkStyle   = revealMarkStyle;
        c.revealMarkSizePct = revealMarkSizePct;
        c.revealMarkColor   = revealMarkColor;
        c.revealPadPct      = revealPadPct;
        return c;
    }

    /**
     * Broadcast the visual / shared-config fields from another QuizSlide
     * onto this one. Used when the first slide acts as the "master" and
     * also when loading presets. Per-slide fields (enabled flag, correct
     * answer, question audio, generated audio) are intentionally NOT
     * copied so each slide keeps its own quiz content.
     */
    public void copyVisualSettingsFrom(QuizSlide src) {
        if (src == null) return;
        this.timerStyle        = src.timerStyle;
        this.timerXPct         = src.timerXPct;
        this.timerYPct         = src.timerYPct;
        this.timerSizePct      = src.timerSizePct;
        this.timerWidthPct     = src.timerWidthPct;
        this.timerColor        = src.timerColor;
        this.progressBarShape  = src.progressBarShape;
        this.timerTextColor    = src.timerTextColor;
        this.timerFont         = src.timerFont;
        this.timerLabel        = src.timerLabel;
        this.revealMarkStyle   = src.revealMarkStyle;
        this.revealMarkSizePct = src.revealMarkSizePct;
        this.revealMarkColor   = src.revealMarkColor;
        this.revealPadPct      = src.revealPadPct;
        this.timerSeconds      = src.timerSeconds;
        this.redThresholdSeconds = src.redThresholdSeconds;
        this.timerStartMode    = src.timerStartMode;
        this.tickPreset        = src.tickPreset;
        this.dingPreset        = src.dingPreset;
        this.customTickFile    = src.customTickFile;
        this.customDingFile    = src.customDingFile;
    }

    // ============================================================
    //                 PUBLIC OVERLAY DRAW HOOK
    // ============================================================

    /**
     * Paint the quiz overlay (countdown digits + reveal flash on the correct
     * option) on top of an already-rendered frame. No-op when the slide is
     * not a quiz.
     *
     * @param frame      the frame BufferedImage (mutated in place)
     * @param quiz       per-slide quiz config (may be null)
     * @param elapsedMs  elapsed time since slide start
     * @param slideTexts the slide's text layers (used to locate the correct option)
     */
    public static void applyOverlay(BufferedImage frame, QuizSlide quiz,
                                    long elapsedMs, List<?> slideTexts) {
        if (quiz == null || !quiz.enabled || frame == null) return;

        int w = frame.getWidth();
        int h = frame.getHeight();
        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            long qEndMs   = Math.max(0, quiz.questionEndMs);
            long timerMs  = quiz.timerSeconds * 1000L;
            // Counting starts at slide start when the user picks "AtSlideStart",
            // otherwise after the question audio finishes.
            boolean atSlideStart = "AtSlideStart".equals(quiz.timerStartMode);
            long timerStart = atSlideStart ? 0L : qEndMs;
            long revealAt   = timerStart + timerMs;

            if (elapsedMs < timerStart) {
                // Question is being read and start-mode is "AfterQuestion":
                // timer is visible but FROZEN at full seconds (no progress yet).
                drawCountdown(g, w, h, quiz, quiz.timerSeconds, false, 0.0);
                return;
            }

            if (elapsedMs < revealAt) {
                long timerElapsed = elapsedMs - timerStart;
                long remainingMs  = Math.max(0, timerMs - timerElapsed);
                int  remainingSec = (int) Math.ceil(remainingMs / 1000.0);
                if (remainingSec < 1) remainingSec = 1;
                double progress = Math.max(0.0,
                        Math.min(1.0, timerElapsed / (double) timerMs));
                drawCountdown(g, w, h, quiz, remainingSec,
                        remainingSec <= quiz.redThresholdSeconds, progress);
            } else {
                // Reveal phase: highlight the correct option.
                long sinceReveal = elapsedMs - revealAt;
                drawReveal(g, w, h, slideTexts, quiz, sinceReveal);
                // Show "0" timer or a fully-filled bar briefly.
                drawCountdown(g, w, h, quiz, 0, true, 1.0);
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * Render-time-independent preview: draw the timer at its FULL value with
     * a faint "PREVIEW" tag, so the user can tweak style/position/size in the
     * editor and see the result without exporting. No-op when quiz disabled.
     */
    public static void applyPreviewOverlay(BufferedImage frame, QuizSlide quiz) {
        if (quiz == null || !quiz.enabled || frame == null) return;
        int w = frame.getWidth();
        int h = frame.getHeight();
        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Frozen at full seconds, no progress depletion — what the viewer
            // sees the instant the question begins reading.
            drawCountdown(g, w, h, quiz, quiz.timerSeconds, false, 0.0);
        } finally {
            g.dispose();
        }
    }

    private static void drawCountdown(Graphics2D g, int w, int h, QuizSlide quiz,
                                      int remainingSec, boolean red,
                                      double progress) {
        // User-chosen accent unless the urgent-red threshold has triggered;
        // red wins so the urgency cue is preserved.
        Color userColor = quiz.timerColor != null ? quiz.timerColor
                : new Color(80, 200, 255);
        Color accent = red ? new Color(235, 70, 70) : userColor;
        Color bg     = new Color(15, 18, 25, 220);
        Color userText = quiz.timerTextColor != null ? quiz.timerTextColor : Color.WHITE;
        Color textCol = red ? new Color(255, 235, 235) : userText;
        String style = quiz.timerStyle != null ? quiz.timerStyle : "Number Circle";

        // Pulse animation: in the last few "red" seconds, gently scale up at
        // each tick and ease back to 1.0. Applied as a transform around the
        // timer's visual center so all styles pulse uniformly.
        java.awt.geom.AffineTransform savedTx = null;
        if (red && quiz.timerSeconds > 0) {
            double secFrac = (progress * quiz.timerSeconds) % 1.0;
            if (secFrac < 0) secFrac += 1.0;
            double pulse = 1.0 + 0.10 * (1.0 - secFrac); // 1.10 at tick → 1.0 by next sec
            double[] cxy = pulseCenter(w, h, quiz);
            savedTx = g.getTransform();
            g.translate(cxy[0], cxy[1]);
            g.scale(pulse, pulse);
            g.translate(-cxy[0], -cxy[1]);
        }

        switch (style) {
            case "Progress Bar H":
                drawProgressBar(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress, true);
                break;
            case "Progress Bar V":
                drawProgressBar(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress, false);
                break;
            case "Ring Arc":
                drawRingArc(g, w, h, quiz, remainingSec, accent, textCol, red, progress);
                break;
            case "Analog Clock":
                drawAnalogClock(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress);
                break;
            case "Numeric Only":   // legacy alias — fall through to Analog Clock
            case "Number Circle":
            default:
                if ("Numeric Only".equals(style)) {
                    drawAnalogClock(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress);
                } else {
                    drawNumberCircle(g, w, h, quiz, remainingSec, accent, bg, textCol, red);
                }
                break;
        }
        if (savedTx != null) g.setTransform(savedTx);
        drawTimerLabel(g, w, h, quiz, accent);
    }

    /** Visual center used for the pulse-in-red-phase scale transform. */
    private static double[] pulseCenter(int w, int h, QuizSlide quiz) {
        double x = w * quiz.timerXPct / 100.0;
        double y = h * quiz.timerYPct / 100.0;
        String s = quiz.timerStyle != null ? quiz.timerStyle : "";
        if ("Progress Bar H".equals(s)) {
            double barW = Math.max(50, w * quiz.timerWidthPct / 100.0);
            double barH = Math.max(14, h * quiz.timerSizePct / 100.0);
            return new double[] { x + barW / 2.0, y + barH / 2.0 };
        }
        if ("Progress Bar V".equals(s)) {
            double barW = Math.max(14, h * quiz.timerSizePct / 100.0);
            double barH = Math.max(50, h * quiz.timerWidthPct / 100.0);
            return new double[] { x + barW / 2.0, y + barH / 2.0 };
        }
        return new double[] { x, y };
    }

    /** Draws the user-supplied label string above (or beside) the timer shape. */
    private static void drawTimerLabel(Graphics2D g, int w, int h, QuizSlide quiz,
                                       Color color) {
        if (quiz.timerLabel == null || quiz.timerLabel.isEmpty()) return;
        boolean horizontalBar = "Progress Bar H".equals(quiz.timerStyle);
        int cx = (int) (w * quiz.timerXPct / 100.0);
        int cy = (int) (h * quiz.timerYPct / 100.0);
        int diameter = Math.max(40, (int) (h * quiz.timerSizePct / 100.0));
        int fontPx = Math.max(12, diameter / 4);
        String labelFamily = (quiz.timerFont != null && !quiz.timerFont.isEmpty())
                ? quiz.timerFont : "Segoe UI";
        Font f = new Font(labelFamily, Font.BOLD, fontPx);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(quiz.timerLabel);
        int tx, ty;
        if (horizontalBar) {
            // Bar's anchor (cx,cy) is the top-left; label sits just above.
            tx = cx;
            ty = cy - 6;
        } else if ("Progress Bar V".equals(quiz.timerStyle)) {
            tx = cx;
            ty = cy - 6;
        } else {
            // Circle / Ring / Clock: label centered above the shape.
            tx = cx - tw / 2;
            ty = cy - diameter / 2 - 6;
        }
        // Subtle shadow for contrast on bright slides.
        Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
        g.setColor(Color.BLACK);
        g.drawString(quiz.timerLabel, tx + 2, ty + 2);
        g.setComposite(oc);
        g.setColor(color);
        g.drawString(quiz.timerLabel, tx, ty);
    }

    /** Filled disc with a ring border and the digit centered. */
    private static void drawNumberCircle(Graphics2D g, int w, int h, QuizSlide quiz,
                                         int remainingSec, Color accent, Color bg,
                                         Color textCol, boolean red) {
        int diameter = Math.max(40, (int) (h * quiz.timerSizePct / 100.0));
        int cx = (int) (w * quiz.timerXPct / 100.0);
        int cy = (int) (h * quiz.timerYPct / 100.0);

        Composite oldC = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g.setColor(Color.BLACK);
        g.fillOval(cx - diameter / 2 + 4, cy - diameter / 2 + 4, diameter, diameter);
        g.setComposite(oldC);

        g.setColor(bg);
        g.fillOval(cx - diameter / 2, cy - diameter / 2, diameter, diameter);

        g.setColor(accent);
        g.setStroke(new BasicStroke(Math.max(3, diameter / 18f)));
        g.drawOval(cx - diameter / 2 + 2, cy - diameter / 2 + 2, diameter - 4, diameter - 4);

        drawDigit(g, cx, cy, diameter, remainingSec, textCol, red, quiz);
    }

    /**
     * Hollow ring (no fill behind it) with a depleting sweep arc and the
     * digit centered in the open middle. Visually distinct from Number
     * Circle which is a solid filled disc.
     */
    private static void drawRingArc(Graphics2D g, int w, int h, QuizSlide quiz,
                                    int remainingSec, Color accent,
                                    Color textCol, boolean red, double progress) {
        int diameter = Math.max(40, (int) (h * quiz.timerSizePct / 100.0));
        int cx = (int) (w * quiz.timerXPct / 100.0);
        int cy = (int) (h * quiz.timerYPct / 100.0);

        Stroke s0 = g.getStroke();
        float ringWidth = Math.max(5, diameter / 9f);
        int r = diameter - (int) ringWidth;

        // Faint full track ring (no filled disc).
        g.setStroke(new BasicStroke(ringWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 70));
        g.drawOval(cx - r / 2, cy - r / 2, r, r);

        // Depleting arc starting at 12 o'clock, sweeping clockwise.
        g.setColor(accent);
        int sweep = (int) Math.round((1.0 - progress) * 360.0);
        g.draw(new Arc2D.Double(cx - r / 2.0, cy - r / 2.0, r, r,
                90, -sweep, Arc2D.OPEN));
        g.setStroke(s0);

        drawDigit(g, cx, cy, diameter, remainingSec, textCol, red, quiz);
    }

    /**
     * Analog wall-clock face with hour markers, a fixed hour/minute hand and
     * a sweeping second hand that completes one full revolution over the
     * timer duration. Used to be "Numeric Only" which wasn't useful.
     */
    private static void drawAnalogClock(Graphics2D g, int w, int h, QuizSlide quiz,
                                        int remainingSec, Color accent, Color bg,
                                        Color textCol, boolean red, double progress) {
        int diameter = Math.max(60, (int) (h * quiz.timerSizePct / 100.0));
        int cx = (int) (w * quiz.timerXPct / 100.0);
        int cy = (int) (h * quiz.timerYPct / 100.0);
        int radius = diameter / 2;

        Stroke s0 = g.getStroke();

        // Subtle drop shadow.
        Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        g.setColor(Color.BLACK);
        g.fillOval(cx - radius + 4, cy - radius + 4, diameter, diameter);
        g.setComposite(oc);

        // Face.
        g.setColor(bg);
        g.fillOval(cx - radius, cy - radius, diameter, diameter);

        // Outer rim.
        g.setStroke(new BasicStroke(Math.max(3, diameter / 22f)));
        g.setColor(accent);
        g.drawOval(cx - radius + 1, cy - radius + 1, diameter - 2, diameter - 2);

        // Hour ticks (12) + minor minute ticks (every 5°).
        for (int i = 0; i < 60; i++) {
            double ang = Math.toRadians(i * 6 - 90);
            boolean major = (i % 5 == 0);
            int tickInner = radius - (major ? diameter / 9 : diameter / 18);
            int tickOuter = radius - 4;
            int x1 = cx + (int) (tickInner * Math.cos(ang));
            int y1 = cy + (int) (tickInner * Math.sin(ang));
            int x2 = cx + (int) (tickOuter * Math.cos(ang));
            int y2 = cy + (int) (tickOuter * Math.sin(ang));
            g.setStroke(new BasicStroke(major ? Math.max(2, diameter / 50f)
                                              : Math.max(1, diameter / 90f)));
            g.setColor(major ? Color.WHITE : new Color(255, 255, 255, 110));
            g.drawLine(x1, y1, x2, y2);
        }

        // Sweeping "second" hand: one full clockwise revolution across the
        // timer duration. Starts at 12 (top) and ends back at 12.
        double sweepAng = Math.toRadians(progress * 360.0 - 90.0);
        int handLen = radius - diameter / 8;
        int hx = cx + (int) (handLen * Math.cos(sweepAng));
        int hy = cy + (int) (handLen * Math.sin(sweepAng));
        g.setStroke(new BasicStroke(Math.max(3, diameter / 24f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(accent);
        g.drawLine(cx, cy, hx, hy);

        // Centre stud.
        int stud = Math.max(4, diameter / 22);
        g.setColor(accent);
        g.fillOval(cx - stud / 2, cy - stud / 2, stud, stud);
        g.setColor(Color.WHITE);
        g.fillOval(cx - stud / 4, cy - stud / 4, stud / 2, stud / 2);

        g.setStroke(s0);

        // Digit, smaller and pushed up a bit so it doesn't fight the hand.
        drawDigit(g, cx, cy + radius / 4, diameter / 2, remainingSec, textCol, red, quiz);
    }

    /** (Legacy hook — no longer used; kept so old presets don't NPE.) */
    private static void drawNumericOnly(Graphics2D g, int w, int h, QuizSlide quiz,
                                        int remainingSec, Color textCol, boolean red) {
        int fontPx = Math.max(28, (int) (h * quiz.timerSizePct / 100.0));
        int cx = (int) (w * quiz.timerXPct / 100.0);
        int cy = (int) (h * quiz.timerYPct / 100.0);

        String family = (quiz.timerFont != null && !quiz.timerFont.isEmpty())
                ? quiz.timerFont : "Segoe UI";
        Font font = new Font(family, Font.BOLD, fontPx);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        String txt = remainingSec <= 0 ? "0" : String.valueOf(remainingSec);
        int tw = fm.stringWidth(txt);
        int tx = cx - tw / 2;
        int ty = cy + fm.getAscent() / 2 - fm.getDescent() / 2;

        if (red) {
            Composite oc = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g.setColor(new Color(255, 60, 60));
            for (int rad = 8; rad > 0; rad -= 2) {
                g.drawString(txt, tx + rad, ty);
                g.drawString(txt, tx - rad, ty);
                g.drawString(txt, tx, ty + rad);
                g.drawString(txt, tx, ty - rad);
            }
            g.setComposite(oc);
        }
        g.setColor(textCol);
        g.drawString(txt, tx, ty);
    }

    /** Horizontal or vertical depleting progress bar with the digit centered on it. */
    private static void drawProgressBar(Graphics2D g, int w, int h, QuizSlide quiz,
                                        int remainingSec, Color accent, Color bg,
                                        Color textCol, boolean red, double progress,
                                        boolean horizontal) {
        int x = (int) (w * quiz.timerXPct / 100.0);
        int y = (int) (h * quiz.timerYPct / 100.0);
        int thickness = Math.max(14, (int) (h * quiz.timerSizePct / 100.0));
        int length;
        if (horizontal) {
            length = Math.max(50, (int) (w * quiz.timerWidthPct / 100.0));
        } else {
            length = Math.max(50, (int) (h * quiz.timerWidthPct / 100.0));
        }

        int barW = horizontal ? length : thickness;
        int barH = horizontal ? thickness : length;
        String shape = quiz.progressBarShape != null ? quiz.progressBarShape : "Rounded";

        if ("Segmented".equalsIgnoreCase(shape)) {
            drawProgressBarSegmented(g, x, y, barW, barH, quiz, remainingSec,
                    accent, bg, horizontal);
        } else {
            int arc;
            switch (shape.toLowerCase()) {
                case "square": arc = 0; break;
                case "pill":   arc = Math.min(barW, barH); break;
                case "rounded":
                default:       arc = thickness; break;
            }

            // Track background.
            g.setColor(bg);
            g.fillRoundRect(x, y, barW, barH, arc, arc);

            // Filled portion (depleting).
            int filled;
            if (horizontal) {
                filled = (int) (barW * (1.0 - progress));
                g.setColor(accent);
                g.fillRoundRect(x, y, Math.max(0, filled), barH, arc, arc);
            } else {
                filled = (int) (barH * (1.0 - progress));
                g.setColor(accent);
                g.fillRoundRect(x, y + (barH - filled), barW, Math.max(0, filled), arc, arc);
            }

            // Border.
            g.setColor(new Color(255, 255, 255, 80));
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(x, y, barW, barH, arc, arc);
        }

        // Digit centered on the bar.
        int cx = x + barW / 2;
        int cy = y + barH / 2;
        int digitSize = (int) (Math.min(barW, barH) * 0.7);
        drawDigit(g, cx, cy, digitSize, remainingSec, textCol, red, quiz);
    }

    /**
     * Segmented progress bar: one block per second of `timerSeconds`. Blocks
     * empty one at a time as seconds elapse — the leftmost (or top) block
     * disappears first so the visual flows naturally toward the digit.
     */
    private static void drawProgressBarSegmented(Graphics2D g, int x, int y,
                                                 int barW, int barH, QuizSlide quiz,
                                                 int remainingSec, Color accent,
                                                 Color bg, boolean horizontal) {
        int totalSegs = Math.max(1, quiz.timerSeconds);
        int filledSegs = Math.max(0, Math.min(totalSegs, remainingSec));
        int gap = Math.max(2, Math.min(barW, barH) / 12);
        int segArc = Math.max(2, Math.min(barW, barH) / 6);

        if (horizontal) {
            int segW = Math.max(2, (barW - gap * (totalSegs - 1)) / totalSegs);
            for (int i = 0; i < totalSegs; i++) {
                int sx = x + i * (segW + gap);
                // Deplete from the LEFT so the remaining block sits near the digit.
                boolean lit = i >= (totalSegs - filledSegs);
                g.setColor(lit ? accent : bg);
                g.fillRoundRect(sx, y, segW, barH, segArc, segArc);
                g.setColor(new Color(255, 255, 255, 60));
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(sx, y, segW, barH, segArc, segArc);
            }
        } else {
            int segH = Math.max(2, (barH - gap * (totalSegs - 1)) / totalSegs);
            for (int i = 0; i < totalSegs; i++) {
                // Top-down index. Deplete from the TOP so the remaining stack
                // sits at the bottom — same depletion direction as Rounded.
                int sy = y + i * (segH + gap);
                boolean lit = i >= (totalSegs - filledSegs);
                g.setColor(lit ? accent : bg);
                g.fillRoundRect(x, sy, barW, segH, segArc, segArc);
                g.setColor(new Color(255, 255, 255, 60));
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(x, sy, barW, segH, segArc, segArc);
            }
        }
    }

    private static void drawDigit(Graphics2D g, int cx, int cy, int sizeRef,
                                  int remainingSec, Color textCol, boolean red,
                                  QuizSlide quiz) {
        String txt = remainingSec <= 0 ? "0" : String.valueOf(remainingSec);
        String family = (quiz != null && quiz.timerFont != null && !quiz.timerFont.isEmpty())
                ? quiz.timerFont : "Segoe UI";
        Font font = new Font(family, Font.BOLD, Math.max(12, (int) (sizeRef * 0.55)));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(txt);
        int tx = cx - tw / 2;
        int ty = cy + fm.getAscent() / 2 - fm.getDescent() / 2;
        if (red) {
            Composite oc = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g.setColor(new Color(255, 60, 60));
            for (int r = 6; r > 0; r -= 2) {
                g.drawString(txt, tx + r, ty);
                g.drawString(txt, tx - r, ty);
                g.drawString(txt, tx, ty + r);
                g.drawString(txt, tx, ty - r);
            }
            g.setComposite(oc);
        }
        g.setColor(textCol);
        g.drawString(txt, tx, ty);
    }

    private static void drawReveal(Graphics2D g, int w, int h,
                                   List<?> slideTexts, QuizSlide quiz,
                                   long sinceRevealMs) {
        if (slideTexts == null || slideTexts.isEmpty()) return;
        int optionIdx1Based = quiz.correctOptionIndex;
        int idx = optionIdx1Based - 1;
        if (idx < 0 || idx >= slideTexts.size()) return;

        Object std = slideTexts.get(idx);
        // Reflectively pull out the fields we need from SlideTextData so this
        // file stays decoupled from GifSlideShowApp's inner class. The fields
        // are package-private so we MUST use getDeclaredField + setAccessible
        // (getField only finds public fields, which silently returned no
        // highlight in the first cut).
        try {
            int xPct     = readIntField(std, "x");
            int yPct     = readIntField(std, "y");
            int fSizeRef = readIntField(std, "fontSize");
            String text  = (String) readField(std, "text");
            if (text == null || text.isEmpty()) text = "Option " + optionIdx1Based;

            // The slide-text coordinates are percentages of frame dimensions.
            int px = (int) (w * xPct / 100.0);
            int py = (int) (h * yPct / 100.0);
            float scale = Math.max(w, h) / 1920.0f;
            int fontPx = Math.max(18, (int) (fSizeRef * scale));

            int tw = (int) (text.length() * fontPx * 0.55) + fontPx;
            int th = (int) (fontPx * 1.5);

            int bx = px - tw / 2;
            int by = py - th / 2;

            // Padding scales by both the pulse-in animation AND the user setting.
            double padScale = Math.max(0.5, Math.min(2.0, quiz.revealPadPct / 100.0));
            float pulse = (float) Math.max(0.0,
                    Math.min(1.0, 1.0 - sinceRevealMs / 800.0));
            int padBase  = (int) ((12 + 18 * pulse) * padScale);
            int rx = bx - padBase;
            int ry = by - padBase;
            int rw = tw + padBase * 2;
            int rh = th + padBase * 2;
            int arc = th + padBase;

            Color markColor = quiz.revealMarkColor != null
                    ? quiz.revealMarkColor : new Color(60, 220, 110);

            // Outer glow rings tinted to match the user's mark color.
            Composite oldC = g.getComposite();
            for (int i = 6; i >= 0; i--) {
                float alpha = (float) (0.10 + 0.05 * pulse) * (1 - i / 8f);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.setColor(markColor);
                g.fillRoundRect(rx - i * 4, ry - i * 4, rw + i * 8, rh + i * 8,
                        arc + i * 4, arc + i * 4);
            }
            g.setComposite(oldC);

            // Solid border.
            g.setStroke(new BasicStroke(Math.max(3, fontPx / 14f)));
            g.setColor(brighter(markColor, 0.12f));
            g.drawRoundRect(rx, ry, rw, rh, arc, arc);

            // Badge.
            String style = quiz.revealMarkStyle != null ? quiz.revealMarkStyle : "Check";
            if (!"None".equalsIgnoreCase(style)) {
                double sizeScale = Math.max(0.5, Math.min(2.0, quiz.revealMarkSizePct / 100.0));
                int badge = Math.max(24, (int) (Math.max(36, fontPx) * sizeScale));
                int bxC = rx + rw + badge / 2 + 6;
                int byC = ry + rh / 2;
                if (bxC + badge / 2 < w) {
                    drawRevealBadge(g, bxC, byC, badge, style, markColor);
                }
            }
        } catch (Exception ex) {
            System.err.println("[QuizSlide] reveal-overlay reflection failed: " + ex);
        }
    }

    /** Brightens a color toward white by the given fraction (0..1). */
    private static Color brighter(Color c, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int r = (int) (c.getRed()   + (255 - c.getRed())   * f);
        int gg= (int) (c.getGreen() + (255 - c.getGreen()) * f);
        int b = (int) (c.getBlue()  + (255 - c.getBlue())  * f);
        return new Color(r, gg, b);
    }

    /**
     * Paints one of several badge shapes centered at (cx,cy), filling a
     * bounding box of side `badge`. The disc is drawn in `fill`; the inner
     * glyph is white (or contrast-aware).
     */
    private static void drawRevealBadge(Graphics2D g, int cx, int cy, int badge,
                                        String style, Color fill) {
        int half = badge / 2;
        // Filled disc backdrop (skipped for "Heart" — the heart silhouette is the badge).
        boolean discBackdrop = !"Heart".equalsIgnoreCase(style)
                            && !"Star".equalsIgnoreCase(style);
        if (discBackdrop) {
            g.setColor(fill);
            g.fillOval(cx - half, cy - half, badge, badge);
        }
        Color glyph = Color.WHITE;
        float strokeW = Math.max(3, badge / 9f);
        g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (style.toLowerCase()) {
            case "star": {
                fillStar(g, cx, cy, half, fill);
                break;
            }
            case "crown": {
                fillCrown(g, cx, cy, badge, glyph);
                break;
            }
            case "trophy": {
                fillTrophy(g, cx, cy, badge, glyph);
                break;
            }
            case "heart": {
                fillHeart(g, cx, cy, badge, fill);
                break;
            }
            case "thumbs up":
            case "thumbsup":
            case "thumbs-up": {
                fillThumbsUp(g, cx, cy, badge, glyph);
                break;
            }
            case "check":
            default: {
                g.setColor(glyph);
                int qx = cx - badge / 4;
                int qy = cy + badge / 12;
                g.drawLine(qx, qy, qx + badge / 6, qy + badge / 5);
                g.drawLine(qx + badge / 6, qy + badge / 5,
                        qx + badge / 2, qy - badge / 4);
                break;
            }
        }
    }

    private static void fillStar(Graphics2D g, int cx, int cy, int r, Color fill) {
        java.awt.Polygon p = new java.awt.Polygon();
        for (int i = 0; i < 10; i++) {
            double ang = Math.toRadians(-90 + i * 36);
            double rad = (i % 2 == 0) ? r : r * 0.45;
            p.addPoint(cx + (int) (rad * Math.cos(ang)),
                       cy + (int) (rad * Math.sin(ang)));
        }
        g.setColor(fill);
        g.fillPolygon(p);
        g.setColor(brighter(fill, 0.25f));
        g.drawPolygon(p);
    }

    private static void fillCrown(Graphics2D g, int cx, int cy, int s, Color glyph) {
        int w = (int) (s * 0.7);
        int h = (int) (s * 0.5);
        int x = cx - w / 2;
        int y = cy - h / 2;
        java.awt.Polygon p = new java.awt.Polygon();
        p.addPoint(x,             y + h);
        p.addPoint(x,             y + h / 3);
        p.addPoint(x + w / 4,     y + h);
        p.addPoint(x + w / 2,     y);
        p.addPoint(x + 3 * w / 4, y + h);
        p.addPoint(x + w,         y + h / 3);
        p.addPoint(x + w,         y + h);
        g.setColor(glyph);
        g.fillPolygon(p);
        // Gem dots.
        int dot = Math.max(2, s / 16);
        g.fillOval(x + w / 2 - dot / 2,     y + h / 2 - dot / 2,     dot, dot);
        g.fillOval(x + w / 4 - dot / 2,     y + 2 * h / 3 - dot / 2, dot, dot);
        g.fillOval(x + 3 * w / 4 - dot / 2, y + 2 * h / 3 - dot / 2, dot, dot);
    }

    private static void fillTrophy(Graphics2D g, int cx, int cy, int s, Color glyph) {
        int cupW = (int) (s * 0.55);
        int cupH = (int) (s * 0.45);
        int x = cx - cupW / 2;
        int y = cy - cupH / 2 - s / 12;
        g.setColor(glyph);
        // Cup body.
        g.fillRoundRect(x, y, cupW, cupH, cupW / 3, cupH / 2);
        // Handles.
        g.setStroke(new BasicStroke(Math.max(2, s / 18f)));
        g.drawArc(x - cupW / 4, y, cupW / 2, cupH, 90, 180);
        g.drawArc(x + cupW - cupW / 4, y, cupW / 2, cupH, 270, 180);
        // Stem + base.
        int stemW = cupW / 6;
        g.fillRect(cx - stemW / 2, y + cupH, stemW, s / 8);
        int baseW = (int) (cupW * 0.7);
        g.fillRoundRect(cx - baseW / 2, y + cupH + s / 8, baseW, s / 10, s / 14, s / 14);
    }

    private static void fillHeart(Graphics2D g, int cx, int cy, int s, Color fill) {
        int r = (int) (s * 0.28);
        int hw = (int) (s * 0.85);
        int hh = (int) (s * 0.78);
        int x = cx - hw / 2;
        int y = cy - hh / 2;
        java.awt.geom.GeneralPath path = new java.awt.geom.GeneralPath();
        path.moveTo(cx, y + hh);
        path.curveTo(x, y + hh * 0.6, x, y, x + hw / 4.0, y);
        path.curveTo(x + hw / 2.0, y, cx, y + r, cx, y + r * 1.3);
        path.curveTo(cx, y + r, x + 3 * hw / 4.0, y, x + 3 * hw / 4.0, y);
        path.curveTo(x + hw, y, x + hw, y + hh * 0.6, cx, y + hh);
        path.closePath();
        g.setColor(fill);
        g.fill(path);
        g.setColor(brighter(fill, 0.2f));
        g.draw(path);
    }

    private static void fillThumbsUp(Graphics2D g, int cx, int cy, int s, Color glyph) {
        g.setColor(glyph);
        int handW = (int) (s * 0.55);
        int handH = (int) (s * 0.45);
        int x = cx - handW / 2;
        int y = cy - handH / 3;
        // Palm/fist.
        g.fillRoundRect(x, y, handW, handH, handW / 4, handH / 2);
        // Thumb sticking up.
        int thumbW = handW / 2;
        int thumbH = (int) (handH * 0.7);
        g.fillRoundRect(x + handW / 5, y - thumbH + handH / 6,
                thumbW, thumbH, thumbW / 2, thumbW / 2);
    }

    private static int readIntField(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(o);
    }

    private static Object readField(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    // ============================================================
    //                CONFIG DIALOG
    // ============================================================

    /**
     * Open the per-slide quiz configuration dialog. On Save, generates the
     * combined audio file and invokes {@code onAttach} so the caller can
     * attach the audio to the slide via its existing per-slide audio API.
     *
     * @param parent          parent frame (for modality)
     * @param quiz            the slide's QuizSlide instance (mutated)
     * @param textItemCount   how many slide-text items the slide currently has
     * @param onAttach        called with (combinedAudioFile, durationMs);
     *                        caller should call setSlideAudio(0, file, ms)
     */
    public static void openConfigDialog(Window parent, QuizSlide quiz,
                                        int textItemCount,
                                        AudioAttachCallback onAttach) {
        JDialog dialog = new JDialog(parent, "Quiz Slide — Timer & Reveal",
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(8, 8));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 8);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill   = GridBagConstraints.HORIZONTAL;

        int row = 0;

        JCheckBox enableCheck = new JCheckBox("Enable quiz on this slide", quiz.enabled);
        gc.gridx = 0; gc.gridy = row++; gc.gridwidth = 3;
        form.add(enableCheck, gc);
        gc.gridwidth = 1;

        // Correct option index.
        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("Correct option (slide-text #):"), gc);
        Integer[] choices = new Integer[Math.max(textItemCount, 6)];
        for (int i = 0; i < choices.length; i++) choices[i] = i + 1;
        JComboBox<Integer> correctCombo = new JComboBox<>(choices);
        correctCombo.setSelectedItem(Math.max(1,
                Math.min(quiz.correctOptionIndex, choices.length)));
        gc.gridx = 1; gc.gridwidth = 2;
        form.add(correctCombo, gc);
        gc.gridwidth = 1;
        row++;

        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 3;
        form.add(small("Tip: option count is 2–6 — the slide-text index above "
                + "points at whichever text item is the correct answer."), gc);
        gc.gridwidth = 1;
        row++;

        // Timer seconds.
        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("Timer length (seconds):"), gc);
        JSpinner timerSpinner = new JSpinner(
                new SpinnerNumberModel(quiz.timerSeconds, 2, 120, 1));
        gc.gridx = 1; gc.gridwidth = 2;
        form.add(timerSpinner, gc);
        gc.gridwidth = 1;
        row++;

        // Red-threshold seconds.
        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("Red color in last (seconds):"), gc);
        JSpinner redSpinner = new JSpinner(
                new SpinnerNumberModel(quiz.redThresholdSeconds, 1, 30, 1));
        gc.gridx = 1; gc.gridwidth = 2;
        form.add(redSpinner, gc);
        gc.gridwidth = 1;
        row++;

        // Start mode.
        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("Timer starts:"), gc);
        JRadioButton afterQRadio = new JRadioButton(
                "After question audio finishes",
                "AfterQuestion".equals(quiz.timerStartMode));
        JRadioButton atStartRadio = new JRadioButton(
                "At slide start (ticks under question, narration louder)",
                "AtSlideStart".equals(quiz.timerStartMode));
        ButtonGroup startGroup = new ButtonGroup();
        startGroup.add(afterQRadio);
        startGroup.add(atStartRadio);
        JPanel startPanel = new JPanel(new GridLayout(2, 1));
        startPanel.setOpaque(false);
        startPanel.add(afterQRadio);
        startPanel.add(atStartRadio);
        gc.gridx = 1; gc.gridwidth = 2;
        form.add(startPanel, gc);
        gc.gridwidth = 1;
        row++;

        // Question audio.
        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("Question audio (required):"), gc);
        JLabel qAudioLabel = new JLabel(quiz.questionAudioFile != null
                ? quiz.questionAudioFile.getName() : "(no file selected)");
        qAudioLabel.setForeground(quiz.questionAudioFile != null
                ? new Color(120, 200, 255) : Color.GRAY);
        JButton qAudioBtn = new JButton("Browse…");
        qAudioBtn.addActionListener(e -> {
            File f = pickAudio(dialog);
            if (f != null) {
                quiz.questionAudioFile = f;
                quiz.questionAudioDurationMs = probeDurationMs(f);
                qAudioLabel.setText(f.getName()
                        + (quiz.questionAudioDurationMs > 0
                            ? "  (" + (quiz.questionAudioDurationMs / 1000.0) + "s)"
                            : ""));
                qAudioLabel.setForeground(new Color(120, 200, 255));
            }
        });
        gc.gridx = 1; form.add(qAudioBtn, gc);
        gc.gridx = 2; form.add(qAudioLabel, gc);
        row++;

        // Tick sound.
        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("Tick sound (during countdown):"), gc);
        JComboBox<String> tickCombo = new JComboBox<>(stockTickNames());
        tickCombo.addItem("Custom (file)…");
        tickCombo.setSelectedItem(quiz.tickPreset);
        JLabel tickLabel = new JLabel(quiz.customTickFile != null
                ? quiz.customTickFile.getName() : "");
        tickLabel.setForeground(new Color(120, 200, 255));
        tickCombo.addActionListener(e -> {
            String sel = (String) tickCombo.getSelectedItem();
            if ("Custom (file)…".equals(sel)) {
                File f = pickAudio(dialog);
                if (f != null) {
                    quiz.customTickFile = f;
                    quiz.tickPreset = "Custom";
                    tickLabel.setText(f.getName());
                } else {
                    tickCombo.setSelectedItem(quiz.tickPreset);
                }
            } else {
                quiz.tickPreset = sel;
                tickLabel.setText("");
            }
        });
        gc.gridx = 1; form.add(tickCombo, gc);
        gc.gridx = 2; form.add(tickLabel, gc);
        row++;

        // Ding sound.
        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("Reveal sound (at t=0):"), gc);
        JComboBox<String> dingCombo = new JComboBox<>(stockDingNames());
        dingCombo.addItem("Custom (file)…");
        dingCombo.setSelectedItem(quiz.dingPreset);
        JLabel dingLabel = new JLabel(quiz.customDingFile != null
                ? quiz.customDingFile.getName() : "");
        dingLabel.setForeground(new Color(120, 200, 255));
        dingCombo.addActionListener(e -> {
            String sel = (String) dingCombo.getSelectedItem();
            if ("Custom (file)…".equals(sel)) {
                File f = pickAudio(dialog);
                if (f != null) {
                    quiz.customDingFile = f;
                    quiz.dingPreset = "Custom";
                    dingLabel.setText(f.getName());
                } else {
                    dingCombo.setSelectedItem(quiz.dingPreset);
                }
            } else {
                quiz.dingPreset = sel;
                dingLabel.setText("");
            }
        });
        gc.gridx = 1; form.add(dingCombo, gc);
        gc.gridx = 2; form.add(dingLabel, gc);
        row++;

        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 3;
        form.add(small("Saving will build the slide audio: "
                + "question → ticks → ding, and attach it to slide audio (Text 1)."), gc);
        gc.gridwidth = 1;

        dialog.add(form, BorderLayout.CENTER);

        // Buttons.
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton cancel = new JButton("Cancel");
        JButton save   = new JButton("Save & Build Audio");
        cancel.addActionListener(e -> dialog.dispose());
        save.addActionListener(e -> {
            quiz.enabled             = enableCheck.isSelected();
            quiz.correctOptionIndex  = (Integer) correctCombo.getSelectedItem();
            quiz.timerSeconds        = (Integer) timerSpinner.getValue();
            quiz.redThresholdSeconds = (Integer) redSpinner.getValue();
            quiz.timerStartMode      = atStartRadio.isSelected()
                    ? "AtSlideStart" : "AfterQuestion";

            if (quiz.enabled) {
                if (quiz.questionAudioFile == null
                        || !quiz.questionAudioFile.exists()) {
                    JOptionPane.showMessageDialog(dialog,
                            "Please choose a question-audio file.",
                            "Missing question audio",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                try {
                    File built = generateCombinedAudio(quiz);
                    quiz.generatedAudioFile = built;
                    quiz.generatedAudioDurationMs = probeDurationMs(built);
                    quiz.questionEndMs = quiz.questionAudioDurationMs;
                    onAttach.attach(built, quiz.generatedAudioDurationMs);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog,
                            "Failed to build quiz audio:\n" + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
            dialog.dispose();
        });
        buttons.add(cancel);
        buttons.add(save);
        dialog.add(buttons, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static JLabel small(String txt) {
        JLabel l = new JLabel(txt);
        l.setFont(l.getFont().deriveFont(Font.ITALIC, 11f));
        l.setForeground(new Color(140, 145, 160));
        return l;
    }

    private static File pickAudio(Window parent) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter(
                "Audio (mp3, wav, m4a, aac, ogg)",
                "mp3", "wav", "m4a", "aac", "ogg"));
        if (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            return fc.getSelectedFile();
        }
        return null;
    }

    @FunctionalInterface
    public interface AudioAttachCallback {
        void attach(File combined, int durationMs);
    }

    // ============================================================
    //                STOCK SOUND SYNTHESIS
    // ============================================================

    private static String[] stockTickNames() {
        return new String[] {
                "Stock: Classic Clock",
                "Stock: Game Show",
                "Stock: Soft Tap"
        };
    }

    private static String[] stockDingNames() {
        return new String[] {
                "Stock: Bell",
                "Stock: Chime",
                "Stock: Buzzer"
        };
    }

    /**
     * Cache of synthesized stock-sound files so we don't re-synth every export.
     */
    private static final Map<String, File> STOCK_CACHE = new LinkedHashMap<>();

    private static synchronized File getStockTickFile(String name) throws IOException {
        File cached = STOCK_CACHE.get("tick:" + name);
        if (cached != null && cached.exists()) return cached;
        File out = File.createTempFile("quizslide-tick-",
                "-" + safeName(name) + ".wav");
        out.deleteOnExit();
        switch (name) {
            case "Stock: Game Show":
                synthSingleTick(out, 880, 90, 0.45);   // higher beep
                break;
            case "Stock: Soft Tap":
                synthSingleTick(out, 520, 50, 0.20);   // softer wood-block
                break;
            case "Stock: Classic Clock":
            default:
                synthSingleTick(out, 1200, 35, 0.30);  // sharp tick
                break;
        }
        STOCK_CACHE.put("tick:" + name, out);
        return out;
    }

    private static synchronized File getStockDingFile(String name) throws IOException {
        File cached = STOCK_CACHE.get("ding:" + name);
        if (cached != null && cached.exists()) return cached;
        File out = File.createTempFile("quizslide-ding-",
                "-" + safeName(name) + ".wav");
        out.deleteOnExit();
        switch (name) {
            case "Stock: Chime":
                synthDing(out, new double[]{ 1318.51, 1760.0, 2349.32 }, 1200, 0.45);
                break;
            case "Stock: Buzzer":
                synthBuzzer(out, 220, 600, 0.55);
                break;
            case "Stock: Bell":
            default:
                synthDing(out, new double[]{ 880.0, 1108.73, 1318.51 }, 1500, 0.55);
                break;
        }
        STOCK_CACHE.put("ding:" + name, out);
        return out;
    }

    private static String safeName(String s) {
        return s.replaceAll("[^A-Za-z0-9]+", "_");
    }

    /** Synthesize one short tick with sharp attack + exponential decay. */
    private static void synthSingleTick(File out, double freqHz,
                                        int durationMs, double gain) throws IOException {
        int sampleRate = 44100;
        int n = sampleRate * durationMs / 1000;
        byte[] buf = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / (double) sampleRate;
            double env = Math.exp(-t * 28.0);   // very fast decay
            double sample = Math.sin(2 * Math.PI * freqHz * t) * env * gain;
            // Add a soft click transient at start.
            if (i < 30) sample += (Math.random() - 0.5) * (1 - i / 30.0) * gain;
            short s = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, sample * Short.MAX_VALUE));
            buf[i * 2]     = (byte) (s & 0xff);
            buf[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        writeWav(out, buf, sampleRate);
    }

    /** Synthesize a layered chime/bell from multiple harmonics with long decay. */
    private static void synthDing(File out, double[] freqs,
                                  int durationMs, double gain) throws IOException {
        int sampleRate = 44100;
        int n = sampleRate * durationMs / 1000;
        byte[] buf = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / (double) sampleRate;
            double env = Math.exp(-t * 2.4);
            double sample = 0;
            double weight = 1.0;
            for (double f : freqs) {
                sample += Math.sin(2 * Math.PI * f * t) * weight;
                weight *= 0.55;
            }
            sample = sample / freqs.length * env * gain;
            short s = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, sample * Short.MAX_VALUE));
            buf[i * 2]     = (byte) (s & 0xff);
            buf[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        writeWav(out, buf, sampleRate);
    }

    /** Synthesize a square-ish low buzzer for a "wrong" / time-up vibe. */
    private static void synthBuzzer(File out, double freqHz, int durationMs,
                                    double gain) throws IOException {
        int sampleRate = 44100;
        int n = sampleRate * durationMs / 1000;
        byte[] buf = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / (double) sampleRate;
            double env = Math.min(1.0, t * 40) * Math.exp(-t * 2.0);
            double phase = 2 * Math.PI * freqHz * t;
            double sample = (Math.sin(phase) >= 0 ? 1 : -1) * env * gain * 0.6;
            short s = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, sample * Short.MAX_VALUE));
            buf[i * 2]     = (byte) (s & 0xff);
            buf[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        writeWav(out, buf, sampleRate);
    }

    private static void writeWav(File out, byte[] pcmLE, int sampleRate) throws IOException {
        AudioFormat fmt = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, 16, 1, 2, sampleRate, false);
        try (AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(pcmLE), fmt, pcmLE.length / 2)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
        }
    }

    // ============================================================
    //                COMBINED AUDIO BUILDER (ffmpeg)
    // ============================================================

    /**
     * Build the per-slide audio: question → tick × N seconds → ding.
     * Returns the generated WAV. Uses ffmpeg (already a dependency of the
     * main app for video export).
     */
    public static File generateCombinedAudio(QuizSlide quiz) throws IOException, InterruptedException {
        File tickSrc = quiz.tickPreset.equals("Custom") && quiz.customTickFile != null
                ? quiz.customTickFile
                : getStockTickFile(quiz.tickPreset);
        File dingSrc = quiz.dingPreset.equals("Custom") && quiz.customDingFile != null
                ? quiz.customDingFile
                : getStockDingFile(quiz.dingPreset);

        // Step 1: build a tick-loop track exactly `timerSeconds` long.
        File tickLoop = File.createTempFile("quizslide-tickloop-", ".wav");
        tickLoop.deleteOnExit();
        runFfmpeg(new String[] {
                "ffmpeg", "-y",
                "-stream_loop", "-1",
                "-i", tickSrc.getAbsolutePath(),
                "-t", String.valueOf(quiz.timerSeconds),
                "-ac", "2", "-ar", "44100",
                tickLoop.getAbsolutePath()
        });

        File out = File.createTempFile("quizslide-combined-", ".wav");
        out.deleteOnExit();

        if ("AtSlideStart".equals(quiz.timerStartMode)) {
            // Mix mode: question + tick play together from t=0 with the
            // question dominant (weight 1.0) and tick BARELY audible
            // (volume ~0.08, ~-22dB) so the narration is clearly heard
            // and the ticks sit as a faint background pulse. Ding plays
            // at the tick's end. amix's `normalize=0` keeps the tick at
            // 0.08 even after the question ends (without it, the lone
            // remaining tick gets boosted back up which we don't want).
            runFfmpeg(new String[] {
                    "ffmpeg", "-y",
                    "-i", quiz.questionAudioFile.getAbsolutePath(),
                    "-i", tickLoop.getAbsolutePath(),
                    "-i", dingSrc.getAbsolutePath(),
                    "-filter_complex",
                    "[0:a]aformat=sample_rates=44100:channel_layouts=stereo,volume=1.0[q];"
                  + "[1:a]aformat=sample_rates=44100:channel_layouts=stereo,volume=0.08[t];"
                  + "[q][t]amix=inputs=2:duration=longest:normalize=0[mix];"
                  + "[2:a]aformat=sample_rates=44100:channel_layouts=stereo[d];"
                  + "[mix][d]concat=n=2:v=0:a=1[out]",
                    "-map", "[out]",
                    "-ac", "2", "-ar", "44100",
                    out.getAbsolutePath()
            });
        } else {
            // Default mode: concat question + tick-loop + ding sequentially.
            runFfmpeg(new String[] {
                    "ffmpeg", "-y",
                    "-i", quiz.questionAudioFile.getAbsolutePath(),
                    "-i", tickLoop.getAbsolutePath(),
                    "-i", dingSrc.getAbsolutePath(),
                    "-filter_complex",
                    "[0:a]aformat=sample_rates=44100:channel_layouts=stereo[a0];"
                  + "[1:a]aformat=sample_rates=44100:channel_layouts=stereo[a1];"
                  + "[2:a]aformat=sample_rates=44100:channel_layouts=stereo[a2];"
                  + "[a0][a1][a2]concat=n=3:v=0:a=1[out]",
                    "-map", "[out]",
                    "-ac", "2", "-ar", "44100",
                    out.getAbsolutePath()
            });
        }
        return out;
    }

    private static void runFfmpeg(String[] cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder log = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                log.append(line).append('\n');
            }
        }
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IOException("ffmpeg failed (rc=" + rc + "):\n" + log);
        }
    }

    private static int probeDurationMs(File f) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "quiet",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    f.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line = br.readLine();
                if (line != null && !line.isEmpty()) {
                    return (int) (Double.parseDouble(line.trim()) * 1000);
                }
            }
            proc.waitFor();
        } catch (Exception ignored) {}
        return 0;
    }
}
