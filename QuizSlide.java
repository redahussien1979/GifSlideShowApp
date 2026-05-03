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
    //        "Ring Arc", "Numeric Only".
    public String timerStyle = "Number Circle";
    // Anchor in % of frame size (0..100). Interpreted as the CENTER of the
    // timer for Circle / Ring / Numeric, and as the TOP-LEFT for Progress Bars.
    public int timerXPct  = 92;
    public int timerYPct  = 12;
    // "Size" = diameter (% of frame height) for Circle / Ring,
    //          font height (% of frame height) for Numeric Only,
    //          bar thickness (% of frame height) for Progress Bar H/V.
    public int timerSizePct  = 14;
    // "Width" = bar length (% of frame width for horiz, % of frame height
    //           for vert). Ignored for Circle / Ring / Numeric Only.
    public int timerWidthPct = 30;

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
        c.timerStyle    = timerStyle;
        c.timerXPct     = timerXPct;
        c.timerYPct     = timerYPct;
        c.timerSizePct  = timerSizePct;
        c.timerWidthPct = timerWidthPct;
        return c;
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
            long revealAt = qEndMs + timerMs;

            if (elapsedMs < qEndMs) {
                // Question is being read — timer is visible but FROZEN at full
                // seconds (no progress yet). Counting starts when the audio ends.
                drawCountdown(g, w, h, quiz, quiz.timerSeconds, false, 0.0);
                return;
            }

            if (elapsedMs < revealAt) {
                long timerElapsed = elapsedMs - qEndMs;
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
                drawReveal(g, w, h, slideTexts, quiz.correctOptionIndex, sinceReveal);
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
        Color accent = red ? new Color(235, 70, 70) : new Color(80, 200, 255);
        Color bg     = new Color(15, 18, 25, 220);
        Color textCol = red ? new Color(255, 235, 235) : Color.WHITE;
        String style = quiz.timerStyle != null ? quiz.timerStyle : "Number Circle";

        switch (style) {
            case "Progress Bar H":
                drawProgressBar(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress, true);
                break;
            case "Progress Bar V":
                drawProgressBar(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress, false);
                break;
            case "Ring Arc":
                drawRingArc(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress);
                break;
            case "Numeric Only":
                drawNumericOnly(g, w, h, quiz, remainingSec, textCol, red);
                break;
            case "Number Circle":
            default:
                drawNumberCircle(g, w, h, quiz, remainingSec, accent, bg, textCol, red);
                break;
        }
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

        drawDigit(g, cx, cy, diameter, remainingSec, textCol, red);
    }

    /** Disc with a sweeping arc that depletes as the timer ticks down. */
    private static void drawRingArc(Graphics2D g, int w, int h, QuizSlide quiz,
                                    int remainingSec, Color accent, Color bg,
                                    Color textCol, boolean red, double progress) {
        int diameter = Math.max(40, (int) (h * quiz.timerSizePct / 100.0));
        int cx = (int) (w * quiz.timerXPct / 100.0);
        int cy = (int) (h * quiz.timerYPct / 100.0);

        g.setColor(bg);
        g.fillOval(cx - diameter / 2, cy - diameter / 2, diameter, diameter);

        // Faint full circle behind the arc (track).
        Stroke s0 = g.getStroke();
        float ringWidth = Math.max(4, diameter / 12f);
        g.setStroke(new BasicStroke(ringWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 40));
        int r = diameter - (int) ringWidth;
        g.drawOval(cx - r / 2, cy - r / 2, r, r);

        // Active arc — starts at 12 o'clock, sweeps clockwise, depletes as time runs out.
        g.setColor(accent);
        int sweep = (int) Math.round((1.0 - progress) * 360.0);
        g.draw(new Arc2D.Double(cx - r / 2.0, cy - r / 2.0, r, r,
                90, -sweep, Arc2D.OPEN));
        g.setStroke(s0);

        drawDigit(g, cx, cy, diameter, remainingSec, textCol, red);
    }

    /** Just the number (no shape). */
    private static void drawNumericOnly(Graphics2D g, int w, int h, QuizSlide quiz,
                                        int remainingSec, Color textCol, boolean red) {
        int fontPx = Math.max(28, (int) (h * quiz.timerSizePct / 100.0));
        int cx = (int) (w * quiz.timerXPct / 100.0);
        int cy = (int) (h * quiz.timerYPct / 100.0);

        Font font = new Font("Segoe UI", Font.BOLD, fontPx);
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
        int arc  = thickness;

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

        // Digit centered on the bar.
        int cx = x + barW / 2;
        int cy = y + barH / 2;
        int digitSize = (int) (Math.min(barW, barH) * 0.7);
        drawDigit(g, cx, cy, digitSize, remainingSec, textCol, red);
    }

    private static void drawDigit(Graphics2D g, int cx, int cy, int sizeRef,
                                  int remainingSec, Color textCol, boolean red) {
        String txt = remainingSec <= 0 ? "0" : String.valueOf(remainingSec);
        Font font = new Font("Segoe UI", Font.BOLD, Math.max(12, (int) (sizeRef * 0.55)));
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
                                   List<?> slideTexts, int optionIdx1Based,
                                   long sinceRevealMs) {
        if (slideTexts == null || slideTexts.isEmpty()) return;
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
            // Text is centered around (x%, y%) in this app's renderer.
            int px = (int) (w * xPct / 100.0);
            int py = (int) (h * yPct / 100.0);
            // fontSize in this app is in 1920p reference pixels and gets
            // scaled by max(targetW,targetH)/1920 at draw time (see renderFrame).
            float scale = Math.max(w, h) / 1920.0f;
            int fontPx = Math.max(18, (int) (fSizeRef * scale));

            // Estimate text width (no measurement context for the actual font;
            // generous estimate: avg glyph width ~= 0.55 * font size).
            int tw = (int) (text.length() * fontPx * 0.55) + fontPx;
            int th = (int) (fontPx * 1.5);

            // Box centered on the text anchor (most slide texts are centered
            // around their (x,y) point in this app).
            int bx = px - tw / 2;
            int by = py - th / 2;

            // Pulse: stronger glow in first 800ms, then steady.
            float pulse = (float) Math.max(0.0,
                    Math.min(1.0, 1.0 - sinceRevealMs / 800.0));
            int padPulse = (int) (12 + 18 * pulse);
            int rx = bx - padPulse;
            int ry = by - padPulse;
            int rw = tw + padPulse * 2;
            int rh = th + padPulse * 2;
            int arc = th + padPulse;

            // Outer glow rings.
            Composite oldC = g.getComposite();
            for (int i = 6; i >= 0; i--) {
                float alpha = (float) (0.10 + 0.05 * pulse) * (1 - i / 8f);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.setColor(new Color(60, 220, 110));
                g.fillRoundRect(rx - i * 4, ry - i * 4, rw + i * 8, rh + i * 8,
                        arc + i * 4, arc + i * 4);
            }
            g.setComposite(oldC);

            // Solid border.
            g.setStroke(new BasicStroke(Math.max(3, fontPx / 14f)));
            g.setColor(new Color(80, 240, 130));
            g.drawRoundRect(rx, ry, rw, rh, arc, arc);

            // Checkmark badge to the right of the box.
            int badge = Math.max(36, fontPx);
            int bxC = rx + rw + badge / 2 + 6;
            int byC = ry + rh / 2;
            if (bxC + badge / 2 < w) {
                g.setColor(new Color(40, 200, 90));
                g.fillOval(bxC - badge / 2, byC - badge / 2, badge, badge);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(Math.max(3, badge / 9f),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int qx = bxC - badge / 4;
                int qy = byC + badge / 12;
                g.drawLine(qx, qy, qx + badge / 6, qy + badge / 5);
                g.drawLine(qx + badge / 6, qy + badge / 5,
                        qx + badge / 2, qy - badge / 4);
            }
        } catch (Exception ex) {
            // Reflection mismatch — fail visibly to stderr but don't break the export.
            System.err.println("[QuizSlide] reveal-overlay reflection failed: "
                    + ex);
        }
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

        // Step 2: concat question + tick-loop + ding into the final WAV.
        File out = File.createTempFile("quizslide-combined-", ".wav");
        out.deleteOnExit();
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
