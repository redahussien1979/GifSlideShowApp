import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A vertical "card carousel" overlay: a stack of rounded cards — each with a
 * round icon badge hanging off its left edge, a bold title and a smaller
 * subtitle — that turns one card at a time. The centre card is large, solid
 * and casts a soft shadow; the cards above and below it are smaller and
 * see-through. On every turn the next card rises (or drops) into the centre,
 * growing and turning solid, while the centre card shrinks back into the stack
 * and a new card fades in at the far end.
 *
 * <p>Self-contained post-pass, exactly like {@link SlideTimer} and
 * {@link SlideAnnotation}: {@link #paint} composites onto an already rendered
 * frame, driven by real elapsed time (ms), so the editor preview and every
 * exported frame come out of the same call.
 *
 * <p>All geometry is relative to the centre card's width, which is itself a
 * percentage of the frame width, so the look is the same at any resolution.
 * The proportions (badge radius, text inset, side-card scale and spacing,
 * see-through level, colours) were measured off the reference design.
 */
public class SlideCarousel {

    // ---- icon kinds ---------------------------------------------------------
    public static final String ICON_DOT   = "Dot";
    public static final String ICON_IMAGE = "Image";
    public static final String ICON_TEXT  = "Letter / symbol";
    public static final String ICON_NONE  = "None";

    public static String[] iconKinds() {
        return new String[] { ICON_DOT, ICON_IMAGE, ICON_TEXT, ICON_NONE };
    }

    // ---- turn timing modes --------------------------------------------------
    /** Every card stays {@link #holdMs}, then turns in {@link #moveMs}. */
    public static final String TIMING_FIXED = "Fixed timing";
    /** A card turns to the centre the moment its slide text's audio starts. */
    public static final String TIMING_AUDIO = "Follow the slide's text audio";

    public static String[] timingModes() {
        return new String[] { TIMING_FIXED, TIMING_AUDIO };
    }

    public static final String DIR_UP   = "Up (next card rises from below)";
    public static final String DIR_DOWN = "Down (next card drops from above)";

    public static String[] directions() {
        return new String[] { DIR_UP, DIR_DOWN };
    }

    /** Stock colours handed to new cards' dots, in turn. */
    private static final Color[] PALETTE = {
            new Color(57, 182, 234), new Color(255, 99, 132), new Color(255, 183, 3),
            new Color(46, 204, 113), new Color(155, 89, 182), new Color(255, 127, 80),
            new Color(26, 188, 156), new Color(231, 76, 60)
    };

    public static Color paletteColor(int i) {
        return PALETTE[Math.floorMod(i, PALETTE.length)];
    }

    /** Fade-in length when {@link #fadeIn} is on, ms. */
    private static final double FADE_IN_MS = 450.0;

    // ---- one card -----------------------------------------------------------
    public static class Item {
        public String title = "";
        public String subtitle = "";
        public String iconKind = ICON_DOT;
        public Color  iconColor = PALETTE[0];
        /** Picture file for {@link #ICON_IMAGE}. */
        public String iconPath = "";
        /** Letter / number / symbol for {@link #ICON_TEXT}. */
        public String iconText = "";
        /** 1-based slide text this card came from (0 = typed in). Used by
         *  {@link #TIMING_AUDIO} to find the audio that turns the card. */
        public int sourceTextIndex = 0;
        /** Sound played the moment this card arrives in the centre ("" = none). */
        public String audioPath = "";
        /** Length of {@link #audioPath}, ms (0 = none / not known yet). The card
         *  stays in the centre at least this long, so its sound is never cut. */
        public int audioMs = 0;

        public Item() { }

        public Item(String title, String subtitle, Color color) {
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            if (color != null) this.iconColor = color;
        }

        public Item copy() {
            Item c = new Item();
            c.title = title;
            c.subtitle = subtitle;
            c.iconKind = iconKind;
            c.iconColor = iconColor;
            c.iconPath = iconPath;
            c.iconText = iconText;
            c.sourceTextIndex = sourceTextIndex;
            c.audioPath = audioPath;
            c.audioMs = audioMs;
            return c;
        }

        /** Short label for list views. */
        public String label() {
            String t = title == null ? "" : title.replace('\n', ' ').trim();
            String s = subtitle == null ? "" : subtitle.replace('\n', ' ').trim();
            if (t.isEmpty() && s.isEmpty()) return "(empty card)";
            String base = s.isEmpty() ? t : (t.isEmpty() ? s : t + "  —  " + s);
            return hasAudio() ? "♪ " + base : base;
        }

        /** True when this card has a sound file to play. */
        public boolean hasAudio() {
            return audioPath != null && !audioPath.trim().isEmpty();
        }
    }

    // ---- model --------------------------------------------------------------
    /** Master switch. False = nothing is drawn. */
    public boolean enabled = false;
    public List<Item> items = new ArrayList<>();

    // placement
    /** Centre X of the centre card (badge + card together), % of frame width. */
    public double xPct = 50;
    /** Centre Y of the centre card, % of frame height. */
    public double yPct = 50;
    /** Centre card width, % of frame width. */
    public double widthPct = 33.6;
    /** Centre card height, % of its own width. */
    public double heightPct = 27.7;
    /** Overall opacity, 0..100. */
    public int opacity = 100;

    // look
    public Color cardColor     = Color.WHITE;
    public Color sideCardColor = Color.WHITE;
    /** See-through level of the side cards' fill, 0..100. */
    public int sideOpacity = 40;
    /** Opacity of the side cards' text, 0..100. */
    public int sideTextOpacity = 62;
    /** Opacity of the side cards' icons, 0..100. */
    public int sideIconOpacity = 82;
    public Color haloColor     = new Color(215, 244, 253);
    public boolean showHalo    = true;
    /** Badge radius, % of the stock size. */
    public int haloSizePct = 100;
    /** Icon size inside the badge, % of the stock size. */
    public int iconSizePct = 100;
    public Color titleColor    = new Color(69, 71, 77);
    public Color subtitleColor = new Color(92, 95, 103);
    public String fontName = "Montserrat";
    public boolean titleBold = true;
    public boolean titleUpper = true;
    public boolean subtitleBold = false;
    /** Title / subtitle size, % of the stock size. */
    public int titleSizePct = 100;
    public int subtitleSizePct = 100;
    public boolean shadow = true;
    /** Card corner roundness, % of the stock radius. */
    public int cornerPct = 100;
    /** Cards visible on EACH side of the centre card (1..3). */
    public int sideCards = 1;
    /** Size of the first side card, % of the centre card. */
    public int sideScalePct = 70;
    /** Gap between cards, % of the stock gap. */
    public int gapPct = 100;

    // motion
    public String timingMode = TIMING_FIXED;
    public String direction = DIR_UP;
    /** When the carousel appears, ms from slide start (also shifts audio-synced turns). */
    public int startMs = 0;
    /** How long each card rests in the centre, ms. */
    public int holdMs = 1600;
    /** How long one turn takes, ms. */
    public int moveMs = 650;
    /** Endless loop: after the last card comes the first again. */
    public boolean loop = true;
    public boolean fadeIn = true;
    /** Stretch the slide so every card gets its turn. */
    public boolean stretchSlide = true;
    /** Volume of the cards' own sounds, 0..100. */
    public int audioVolume = 100;

    // optional backdrop
    /** Paint a full-frame gradient behind the cards (covers the slide picture). */
    public boolean backdrop = false;
    public Color backdropColor1 = new Color(68, 7, 135);
    public Color backdropColor2 = new Color(12, 184, 187);
    /** Gradient direction, degrees counter-clockwise from "left to right". */
    public int backdropAngle = 31;

    /**
     * Per-card turn times on the slide's timeline, ms — resolved from the
     * slide's audio for {@link #TIMING_AUDIO}. Transient: set on the export
     * snapshot by the app (and by the editor for its preview), never saved.
     */
    public int[] audioCues = null;

    /** A carousel carrying the three sample cards, ready to edit. */
    public static SlideCarousel withSampleItems() {
        SlideCarousel c = new SlideCarousel();
        c.items.add(new Item("Spouting Whale", "Unicode: U+1F433", new Color(57, 182, 234)));
        c.items.add(new Item("Whale", "Unicode: U+1F40B", new Color(30, 144, 214)));
        c.items.add(new Item("Dolphin", "Unicode: U+1F42C", new Color(41, 128, 185)));
        return c;
    }

    public boolean isActive() {
        return enabled && items != null && !items.isEmpty();
    }

    public SlideCarousel copy() {
        SlideCarousel c = new SlideCarousel();
        c.copyDesignFrom(this);
        c.enabled = enabled;
        c.items = new ArrayList<>();
        if (items != null) for (Item it : items) if (it != null) c.items.add(it.copy());
        c.audioCues = audioCues == null ? null : audioCues.clone();
        return c;
    }

    /** Take every look / placement / motion setting of {@code s}, keeping this one's cards. */
    public void copyDesignFrom(SlideCarousel s) {
        if (s == null) return;
        xPct = s.xPct; yPct = s.yPct; widthPct = s.widthPct; heightPct = s.heightPct;
        opacity = s.opacity;
        cardColor = s.cardColor; sideCardColor = s.sideCardColor;
        sideOpacity = s.sideOpacity; sideTextOpacity = s.sideTextOpacity; sideIconOpacity = s.sideIconOpacity;
        haloColor = s.haloColor; showHalo = s.showHalo; haloSizePct = s.haloSizePct; iconSizePct = s.iconSizePct;
        titleColor = s.titleColor; subtitleColor = s.subtitleColor; fontName = s.fontName;
        titleBold = s.titleBold; titleUpper = s.titleUpper; subtitleBold = s.subtitleBold;
        titleSizePct = s.titleSizePct; subtitleSizePct = s.subtitleSizePct;
        shadow = s.shadow; cornerPct = s.cornerPct; sideCards = s.sideCards;
        sideScalePct = s.sideScalePct; gapPct = s.gapPct;
        timingMode = s.timingMode; direction = s.direction; startMs = s.startMs;
        holdMs = s.holdMs; moveMs = s.moveMs; loop = s.loop; fadeIn = s.fadeIn;
        stretchSlide = s.stretchSlide; audioVolume = s.audioVolume;
        backdrop = s.backdrop; backdropColor1 = s.backdropColor1; backdropColor2 = s.backdropColor2;
        backdropAngle = s.backdropAngle;
    }

    // ======================================================================
    //  TIMING
    // ======================================================================

    /** Silence left after a card's own sound before it turns away, ms. */
    private static final int AUDIO_TAIL_MS = 350;

    private int stepMs() { return Math.max(1, Math.max(0, holdMs) + Math.max(1, moveMs)); }

    private int move() { return Math.max(1, moveMs); }

    /** How long card {@code k} rests in the centre: the hold time, or longer when its sound needs it. */
    private int holdOf(int k) {
        int hold = Math.max(0, holdMs);
        Item it = k >= 0 && k < items.size() ? items.get(k) : null;
        if (it != null && it.hasAudio() && it.audioMs > 0) hold = Math.max(hold, it.audioMs + AUDIO_TAIL_MS);
        return hold;
    }

    private boolean audioMode() {
        return TIMING_AUDIO.equals(timingMode) && audioCues != null && audioCues.length == items.size();
    }

    /**
     * Resolve {@link #TIMING_AUDIO} turn times from the slide's per-text audio
     * starts (entry N = text N+1's audio start, -1 = no audio). Card K follows
     * the audio of its source text, or of text K+1 when it was typed in. A card
     * with no audio of its own turns one step after the card before it.
     */
    public int[] resolveAudioCues(int[] audioStarts) {
        int n = items == null ? 0 : items.size();
        int[] cues = new int[n];
        int prev = 0;
        for (int k = 0; k < n; k++) {
            Item it = items.get(k);
            int src = it != null && it.sourceTextIndex > 0 ? it.sourceTextIndex - 1 : k;
            int at = audioStarts != null && src >= 0 && src < audioStarts.length ? audioStarts[src] : -1;
            if (k == 0) {
                cues[k] = 0;                      // the first card opens the carousel
            } else if (at >= 0) {
                cues[k] = Math.max(prev, at);
            } else {
                cues[k] = prev + holdOf(k - 1) + move();
            }
            prev = cues[k];
        }
        return cues;
    }

    /**
     * Fixed timing: when each card arrives in the centre during the first pass,
     * ms after the carousel starts; entry n is when the pass ends (the first
     * card back in the centre).
     */
    private int[] fixedArrivals() {
        int n = items.size();
        int[] at = new int[n + 1];
        for (int k = 1; k <= n; k++) at[k] = at[k - 1] + holdOf(k - 1) + move();
        return at;
    }

    /** How long the carousel needs (from slide start) to give every card its turn, ms. */
    public int requiredSlideMs() {
        if (!isActive()) return 0;
        int n = items.size();
        int body;
        if (audioMode()) {
            body = audioCues[n - 1] + Math.max(holdOf(n - 1), 600);
        } else if (n <= 1) {
            body = Math.max(holdOf(0), 600);
        } else {
            int[] at = fixedArrivals();
            body = loop ? at[n]                   // back round to the first card
                        : at[n - 1] + holdOf(n - 1);
        }
        return Math.max(0, startMs) + body;
    }

    /**
     * Every moment a card with a sound arrives in the centre while the slide is
     * on screen: {slide-relative ms, card index}. A loop replays a card's sound
     * each time it comes back, as long as the sound fits before the slide ends.
     */
    public List<int[]> audioEvents(int slideMs) {
        List<int[]> out = new ArrayList<>();
        if (!isActive()) return out;
        int n = items.size();
        int base = Math.max(0, startMs);
        if (audioMode() || n <= 1 || !loop) {
            int[] at = audioMode() ? audioCues : fixedArrivals();
            for (int k = 0; k < n; k++) addEvent(out, k, base + (k == 0 ? 0 : at[k]), slideMs, true);
            return out;
        }
        int[] at = fixedArrivals();
        int period = Math.max(1, at[n]);
        for (long cycle = 0; base + cycle * period < slideMs; cycle++) {
            for (int k = 0; k < n; k++) {
                addEvent(out, k, (int) (base + cycle * period + at[k]), slideMs, cycle == 0);
            }
        }
        return out;
    }

    private void addEvent(List<int[]> out, int k, int atMs, int slideMs, boolean always) {
        Item it = items.get(k);
        if (it == null || !it.hasAudio() || atMs >= slideMs) return;
        // A replay that would be cut off by the end of the slide is left out.
        if (!always && it.audioMs > 0 && atMs + it.audioMs > slideMs) return;
        out.add(new int[] { atMs, k });
    }

    /**
     * A key that is equal for two moments exactly when the carousel looks the
     * same at both (same turn position, same fade). Lets the GIF export merge a
     * card's resting frames into one long frame instead of storing each copy.
     */
    public String stateKey(long elapsedMs) {
        if (!isActive()) return "off";
        long t = elapsedMs - Math.max(0, startMs);
        if (t < 0) return "before";
        double a = fadeIn ? Math.min(1.0, t / FADE_IN_MS) : 1.0;
        return String.format(java.util.Locale.US, "%.4f|%.3f", progress(t), a);
    }

    private static double easeInOut(double t) {
        t = Math.max(0, Math.min(1, t));
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    /** Index (fractional while turning) of the card at the centre, {@code t} ms after start. */
    private double progress(long t) {
        int n = items.size();
        if (n <= 1 || t <= 0) return 0;
        if (audioMode()) {
            int move = move();
            for (int k = n - 1; k >= 1; k--) {
                int arrive = audioCues[k];
                int leave = Math.max(audioCues[k - 1], arrive - move);
                if (t >= arrive) return k;
                if (t > leave) {
                    double span = Math.max(1, arrive - leave);
                    return (k - 1) + easeInOut((t - leave) / span);
                }
            }
            return 0;
        }
        int[] at = fixedArrivals();
        long cycles = 0;
        long r = t;
        if (loop) {
            int period = Math.max(1, at[n]);
            cycles = t / period;
            r = t - cycles * period;
        } else if (t >= at[n - 1]) {
            return n - 1;
        }
        for (int k = 0; k < n; k++) {
            if (r < at[k + 1]) {
                long into = r - at[k];
                int hold = holdOf(k);
                double frac = into < hold ? 0 : easeInOut((into - hold) / (double) move());
                return cycles * n + k + frac;
            }
        }
        return cycles * n + n;   // not reached: r < at[n] always holds
    }

    // ======================================================================
    //  GEOMETRY (all in units of the centre card's height H)
    // ======================================================================

    /** Scale of the card sitting {@code k} whole places from the centre. */
    private double scaleAtSlot(int k) {
        if (k <= 0) return 1.0;
        double s1 = Math.max(0.2, Math.min(1.0, sideScalePct / 100.0));
        return s1 * Math.pow(0.74, k - 1);
    }

    /** Distance (in H) from the centre card's centre to slot {@code k}'s centre. */
    private double offsetAtSlot(int k) {
        double g = 0.153 * Math.max(0, gapPct) / 100.0;
        double off = 0;
        for (int i = 1; i <= k; i++) off += (scaleAtSlot(i - 1) + scaleAtSlot(i)) * 0.5 + g;
        return off;
    }

    private double lerpSlot(double d, boolean offset) {
        int k = (int) Math.floor(d);
        double f = d - k;
        double a = offset ? offsetAtSlot(k) : scaleAtSlot(k);
        double b = offset ? offsetAtSlot(k + 1) : scaleAtSlot(k + 1);
        return a + (b - a) * f;
    }

    // ======================================================================
    //  RENDERING
    // ======================================================================

    /**
     * Composite the carousel onto {@code frame}.
     *
     * @param elapsedMs ms since the slide started
     * @param preview   true for editor previews (draws a hint when there are no cards)
     */
    public static void paint(BufferedImage frame, SlideCarousel c, long elapsedMs, boolean preview) {
        if (frame == null || c == null || !c.enabled) return;
        int fw = frame.getWidth(), fh = frame.getHeight();
        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);

            if (c.backdrop) paintBackdrop(g, c, fw, fh);
            if (c.items == null || c.items.isEmpty()) {
                if (preview) {
                    g.setColor(new Color(255, 255, 255, 170));
                    g.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, fh / 24)));
                    String s = "Carousel: add a card to see it here";
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(s, (fw - fm.stringWidth(s)) / 2, fh / 2);
                }
                return;
            }
            long t = elapsedMs - Math.max(0, c.startMs);
            if (t < 0) return;
            double master = Math.max(0, Math.min(100, c.opacity)) / 100.0;
            if (c.fadeIn) master *= Math.min(1.0, t / FADE_IN_MS);
            if (master <= 0.001) return;
            c.paintCards(g, fw, fh, c.progress(t), master);
        } finally {
            g.dispose();
        }
    }

    private static void paintBackdrop(Graphics2D g, SlideCarousel c, int fw, int fh) {
        double a = Math.toRadians(c.backdropAngle);
        double dx = Math.cos(a), dy = -Math.sin(a);
        // Project the four corners on the direction so the gradient spans the
        // whole frame exactly, whatever the angle.
        double cx = fw / 2.0, cy = fh / 2.0;
        double half = Math.abs(dx) * fw / 2.0 + Math.abs(dy) * fh / 2.0;
        Point2D p1 = new Point2D.Double(cx - dx * half, cy - dy * half);
        Point2D p2 = new Point2D.Double(cx + dx * half, cy + dy * half);
        if (p1.distance(p2) < 1) p2 = new Point2D.Double(p1.getX() + 1, p1.getY());
        Color c1 = c.backdropColor1 != null ? c.backdropColor1 : new Color(68, 7, 135);
        Color c2 = c.backdropColor2 != null ? c.backdropColor2 : new Color(12, 184, 187);
        g.setPaint(new LinearGradientPaint(p1, p2, new float[] { 0f, 1f }, new Color[] { c1, c2 },
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fillRect(0, 0, fw, fh);
    }

    /** One card to draw: which item, and its signed distance from the centre. */
    private static final class Slot {
        final Item item; final double pos;
        Slot(Item item, double pos) { this.item = item; this.pos = pos; }
    }

    private void paintCards(Graphics2D g, int fw, int fh, double p, double master) {
        int n = items.size();
        int depth = Math.max(1, Math.min(3, sideCards));
        double W = Math.max(8, widthPct / 100.0 * fw);
        double H = Math.max(4, W * Math.max(5, heightPct) / 100.0);
        double R = 0.371 * H * Math.max(10, haloSizePct) / 100.0;
        double cx = xPct / 100.0 * fw;
        double cy = yPct / 100.0 * fh;
        double dirSign = DIR_DOWN.equals(direction) ? -1 : 1;

        // Build the visible slots: "tape" around the current position.
        List<Slot> slots = new ArrayList<>();
        int base = (int) Math.floor(p);
        double frac = p - base;
        for (int k = -depth - 1; k <= depth + 1; k++) {
            int idx = base + k;
            double pos = k - frac;
            if (Math.abs(pos) >= depth + 1) continue;
            if (loop && n > 1) idx = Math.floorMod(idx, n);
            else if (idx < 0 || idx >= n) continue;
            if (n == 1 && k != 0) continue;
            slots.add(new Slot(items.get(idx), pos));
        }
        // Far cards first, the centre card last (on top).
        slots.sort((a, b) -> Double.compare(Math.abs(b.pos), Math.abs(a.pos)));

        Font titleBase = resolveFont(fontName, titleBold ? Font.BOLD : Font.PLAIN);
        Font subBase = resolveFont(fontName, subtitleBold ? Font.BOLD : Font.PLAIN);

        for (Slot s : slots) {
            double d = Math.abs(s.pos);
            double vis = d <= depth ? 1.0 : Math.max(0, 1.0 - (d - depth));
            if (vis <= 0.001) continue;
            double scale = lerpSlot(d, false);
            double off = Math.signum(s.pos) * lerpSlot(d, true) * H * dirSign;
            double m = Math.min(1.0, d);          // 0 = centre look, 1 = side look

            AffineTransform saved = g.getTransform();
            Composite savedComp = g.getComposite();
            g.translate(cx, cy + off);
            g.scale(scale, scale);
            paintCard(g, s.item, W, H, R, m, master * vis, titleBase, subBase);
            g.setTransform(saved);
            g.setComposite(savedComp);
        }
    }

    private static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                (int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t),
                (int) Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    private static Color withAlpha(Color c, double a) {
        int al = (int) Math.round(Math.max(0, Math.min(1, a)) * c.getAlpha());
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), al);
    }

    private static void setAlpha(Graphics2D g, double a) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                (float) Math.max(0, Math.min(1, a))));
    }

    /**
     * Draw one card centred on (0,0) of the current transform (the card + its
     * badge together are centred, so the stack scales around the same axis).
     *
     * @param m     0 = centre-card look, 1 = side-card look (blended in between)
     * @param alpha overall opacity of this card
     */
    private void paintCard(Graphics2D g, Item it, double W, double H, double R, double m,
                           double alpha, Font titleBase, Font subBase) {
        double left = -(W - R) / 2.0;          // card's left edge = the badge's centre
        double top = -H / 2.0;
        double arc = 2 * 0.065 * H * Math.max(0, cornerPct) / 100.0;
        Shape card = new RoundRectangle2D.Double(left, top, W, H, arc, arc);
        Shape badge = new Ellipse2D.Double(left - R, -R, 2 * R, 2 * R);
        Area body = new Area(card);
        body.add(new Area(badge));

        // Soft drop shadow under the centre card only.
        if (shadow && m < 1) {
            double sa = 0.15 * (1 - m) * alpha;
            AffineTransform t0 = g.getTransform();
            g.translate(0, H * 0.05);
            setAlpha(g, 1);
            int layers = 7;
            double blur = H * 0.16;
            for (int i = layers; i >= 1; i--) {
                g.setStroke(new BasicStroke((float) (blur * i / layers),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(10, 16, 40, (int) Math.round(255 * sa / layers)));
                g.draw(body);
            }
            g.setColor(new Color(10, 16, 40, (int) Math.round(255 * sa)));
            g.fill(body);
            g.setTransform(t0);
        }

        // Card (+ badge) fill: solid in the centre, see-through at the sides.
        Color solid = cardColor != null ? cardColor : Color.WHITE;
        Color sideC = sideCardColor != null ? sideCardColor : Color.WHITE;
        Color sideFill = withAlpha(sideC, sideOpacity / 100.0);
        Color fill = mix(solid, sideFill, m);
        setAlpha(g, alpha);
        g.setColor(fill);
        g.fill(body);

        // Badge: its own pale colour on the centre card, melting into the card at the sides.
        if (showHalo && haloColor != null && m < 1) {
            g.setColor(withAlpha(haloColor, 1 - m));
            g.fill(badge);
        }

        // Icon inside the badge.
        double iconAlpha = alpha * (1 - m + m * sideIconOpacity / 100.0);
        paintIcon(g, it, left, 0, R, iconAlpha, titleBase);

        // Texts.
        double textAlpha = alpha * (1 - m + m * sideTextOpacity / 100.0);
        paintTexts(g, it, left, top, W, H, R, textAlpha, titleBase, subBase);
    }

    private void paintIcon(Graphics2D g, Item it, double bx, double by, double R, double alpha,
                           Font titleBase) {
        if (it == null || alpha <= 0.001) return;
        String kind = it.iconKind == null ? ICON_DOT : it.iconKind;
        double size = 1.30 * R * Math.max(10, iconSizePct) / 100.0;
        Color col = it.iconColor != null ? it.iconColor : PALETTE[0];
        setAlpha(g, alpha);
        switch (kind) {
            case ICON_NONE:
                return;
            case ICON_IMAGE: {
                BufferedImage img = loadIcon(it.iconPath, (int) Math.ceil(size * 2));
                if (img == null) { paintDot(g, bx, by, size, col); return; }
                double iw = img.getWidth(), ih = img.getHeight();
                double sc = Math.min(size / iw, size / ih);
                double dw = iw * sc, dh = ih * sc;
                AffineTransform at = new AffineTransform();
                at.translate(bx - dw / 2, by - dh / 2);
                at.scale(sc, sc);
                g.drawImage(img, at, null);
                return;
            }
            case ICON_TEXT: {
                String s = it.iconText == null ? "" : it.iconText.trim();
                if (s.isEmpty()) { paintDot(g, bx, by, size, col); return; }
                Font f = titleBase.deriveFont(Font.BOLD, (float) (size * 0.82));
                java.awt.font.TextLayout gv = shapedLayout(s, f, g.getFontRenderContext());
                Rectangle2D vb = gv.getOutline(null).getBounds2D();
                double fit = Math.min(1.0, Math.min(size / Math.max(1e-3, vb.getWidth()),
                        size / Math.max(1e-3, vb.getHeight())));
                AffineTransform t0 = g.getTransform();
                g.translate(bx, by);
                g.scale(fit, fit);
                g.setColor(col);
                g.fill(gv.getOutline(AffineTransform.getTranslateInstance(-vb.getCenterX(), -vb.getCenterY())));
                g.setTransform(t0);
                return;
            }
            case ICON_DOT:
            default:
                paintDot(g, bx, by, size, col);
        }
    }

    private static void paintDot(Graphics2D g, double cx, double cy, double size, Color col) {
        double d = size * 0.92;
        g.setColor(col);
        g.fill(new Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));
    }

    private void paintTexts(Graphics2D g, Item it, double left, double top, double W, double H,
                            double R, double alpha, Font titleBase, Font subBase) {
        if (it == null || alpha <= 0.001) return;
        String title = it.title == null ? "" : it.title.replace('\n', ' ').trim();
        String sub = it.subtitle == null ? "" : it.subtitle.replace('\n', ' ').trim();
        if (titleUpper) title = title.toUpperCase();
        if (title.isEmpty() && sub.isEmpty()) return;

        double textX = left + R + 0.21 * H;
        double maxW = left + W - 0.10 * H - textX;
        if (maxW <= 4) return;
        float titleSize = (float) (0.155 * H * Math.max(10, titleSizePct) / 100.0);
        float subSize = (float) (0.145 * H * Math.max(10, subtitleSizePct) / 100.0);
        setAlpha(g, alpha);

        boolean both = !title.isEmpty() && !sub.isEmpty();
        if (!title.isEmpty()) {
            double capMid = top + H * (both ? 0.335 : 0.5);
            drawFitted(g, title, titleBase.deriveFont(titleSize), textX, capMid, maxW,
                    titleColor != null ? titleColor : new Color(69, 71, 77),
                    titleBold ? 0.055 : 0.018);
        }
        if (!sub.isEmpty()) {
            double capMid = top + H * (both ? 0.655 : 0.5);
            drawFitted(g, sub, subBase.deriveFont(subSize), textX, capMid, maxW,
                    subtitleColor != null ? subtitleColor : new Color(92, 95, 103),
                    subtitleBold ? 0.055 : 0.022);
        }
    }

    /**
     * Draw {@code s} with its capitals centred on {@code capMid}, shrunk (then cut)
     * to fit. Drawn as an outline so it can be given a little extra weight: the
     * fonts bundled with the app ship a single (regular) face, and Java's "bold"
     * on such a font is no bolder, so {@code weightEm} (of the font size) is
     * stroked around the glyphs when the font has no real bold face of its own.
     */
    private static void drawFitted(Graphics2D g, String s, Font f, double x, double capMid,
                                   double maxW, Color col, double weightEm) {
        java.awt.font.FontRenderContext frc = g.getFontRenderContext();
        java.awt.font.TextLayout tl = shapedLayout(s, f, frc);
        Font use = f;
        if (tl.getAdvance() > maxW) {
            float shrunk = (float) Math.max(f.getSize2D() * 0.72, f.getSize2D() * maxW / tl.getAdvance());
            use = f.deriveFont(shrunk);
            tl = shapedLayout(s, use, frc);
            if (tl.getAdvance() > maxW) {
                String ell = "\u2026";
                String cut = s;
                while (cut.length() > 1 && shapedLayout(cut + ell, use, frc).getAdvance() > maxW) {
                    cut = cut.substring(0, cut.length() - 1);
                }
                tl = shapedLayout(cut.trim() + ell, use, frc);
            }
        }
        double capH = use.createGlyphVector(frc, "H").getVisualBounds().getHeight();
        Shape outline = tl.getOutline(AffineTransform.getTranslateInstance(x, capMid + capH / 2.0));
        g.setColor(col);
        g.fill(outline);
        boolean realBold = use.isBold() && !use.getFontName().equals(use.deriveFont(Font.PLAIN).getFontName());
        double sw = realBold ? 0 : weightEm * use.getSize2D();
        if (sw > 0.01) {
            g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(outline);
        }
    }

    /**
     * Lay {@code s} out the way a word processor would: through TextLayout, which
     * joins Arabic letters into their connected forms and puts right-to-left text
     * in the right order (a plain glyph vector does neither, which is what gave
     * separated, back-to-front Arabic). Characters the chosen font has no glyph
     * for — typically Arabic in a Latin-only font such as Montserrat — are set in
     * a font that has them, at the same size and weight.
     */
    static java.awt.font.TextLayout shapedLayout(String s, Font f, java.awt.font.FontRenderContext frc) {
        if (s == null || s.isEmpty()) s = " ";
        java.text.AttributedString as = new java.text.AttributedString(s);
        as.addAttribute(java.awt.font.TextAttribute.FONT, f);
        if (f.canDisplayUpTo(s) != -1) {
            Font fb = null;
            int i = 0;
            while (i < s.length()) {
                int cp = s.codePointAt(i);
                int n = Character.charCount(cp);
                if (!f.canDisplay(cp) && !Character.isWhitespace(cp)) {
                    if (fb == null) fb = fallbackFont(cp, f);
                    if (fb != null) {
                        // Grow the run over neighbouring characters the fallback
                        // also covers (spaces included), so a whole Arabic phrase
                        // is shaped as one piece.
                        int j = i + n;
                        while (j < s.length()) {
                            int c2 = s.codePointAt(j);
                            if (f.canDisplay(c2) && !Character.isWhitespace(c2)) break;
                            if (!fb.canDisplay(c2) && !Character.isWhitespace(c2)) break;
                            j += Character.charCount(c2);
                        }
                        as.addAttribute(java.awt.font.TextAttribute.FONT, fb, i, j);
                        i = j;
                        continue;
                    }
                }
                i += n;
            }
        }
        return new java.awt.font.TextLayout(as.getIterator(), frc);
    }

    private static final Map<String, Font> FALLBACK_CACHE = new ConcurrentHashMap<>();

    /** A font that can draw {@code cp}, at {@code like}'s size and style (null when none can). */
    private static Font fallbackFont(int cp, Font like) {
        String key = Character.UnicodeScript.of(cp) + "|" + like.getStyle();
        Font base = FALLBACK_CACHE.get(key);
        if (base == null) {
            String[] candidates = { "Segoe UI", "Tahoma", "Arial", "Times New Roman",
                    "Noto Sans Arabic", "Noto Naskh Arabic", "DejaVu Sans", "FreeSerif", "Dialog" };
            for (String name : candidates) {
                Font c = new Font(name, like.getStyle(), 32);
                // new Font() silently falls back to Dialog for unknown names; keep
                // the named font only when it really is installed (or is Dialog).
                boolean real = name.equals("Dialog") || c.getFamily().equalsIgnoreCase(name);
                if (real && c.canDisplay(cp)) { base = c; break; }
            }
            if (base == null) return null;
            FALLBACK_CACHE.put(key, base);
        }
        return base.deriveFont(like.getStyle(), like.getSize2D());
    }

    // ======================================================================
    //  FONTS + ICON IMAGES
    // ======================================================================

    private static Font resolveFont(String name, int style) {
        try {
            return GifSlideShowApp.resolveFontByName(name, style, 32f);
        } catch (Throwable t) {
            return new Font(name != null && !name.isEmpty() ? name : "SansSerif", style, 32);
        }
    }

    private static final Map<String, BufferedImage> ICON_CACHE = new ConcurrentHashMap<>();

    /**
     * Load an icon picture, pre-shrunk (in halving steps, for a clean result)
     * to at most about {@code maxPx} on its long side. Cached per file + size.
     */
    static BufferedImage loadIcon(String path, int maxPx) {
        if (path == null || path.trim().isEmpty()) return null;
        File f = new File(path.trim());
        if (!f.isFile()) return null;
        int bucket = Math.max(16, Integer.highestOneBit(Math.max(16, maxPx)) * 2);
        String key = f.getAbsolutePath() + "|" + f.lastModified() + "|" + bucket;
        BufferedImage hit = ICON_CACHE.get(key);
        if (hit != null) return hit;
        try {
            BufferedImage src = javax.imageio.ImageIO.read(f);
            if (src == null) return null;
            BufferedImage cur = toArgb(src);
            while (Math.max(cur.getWidth(), cur.getHeight()) > bucket * 2) {
                int nw = Math.max(1, cur.getWidth() / 2), nh = Math.max(1, cur.getHeight() / 2);
                BufferedImage half = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
                Graphics2D hg = half.createGraphics();
                hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                hg.drawImage(cur, 0, 0, nw, nh, null);
                hg.dispose();
                cur = half;
            }
            if (ICON_CACHE.size() > 256) ICON_CACHE.clear();
            ICON_CACHE.put(key, cur);
            return cur;
        } catch (Exception ex) {
            return null;
        }
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }
}
