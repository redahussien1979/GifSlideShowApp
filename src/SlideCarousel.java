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

    // ---- text alignment inside the card ------------------------------------
    public static final String ALIGN_LEFT   = "Left";
    public static final String ALIGN_CENTER = "Centre";
    public static final String ALIGN_RIGHT  = "Right";
    /** Right for right-to-left text (Arabic, Hebrew…), left for everything else. */
    public static final String ALIGN_AUTO   = "Auto (Arabic right, others left)";

    public static String[] alignments() {
        return new String[] { ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT, ALIGN_AUTO };
    }

    // ---- effects -----------------------------------------------------------
    public static final String LAYOUT_VERTICAL   = "Vertical (cards stacked)";
    public static final String LAYOUT_HORIZONTAL = "Horizontal (cards side by side)";
    public static String[] layouts() { return new String[] { LAYOUT_VERTICAL, LAYOUT_HORIZONTAL }; }

    public static final String KARAOKE_OFF   = "Off";
    public static final String KARAOKE_TITLE = "Title";
    public static final String KARAOKE_BOTH  = "Title, then subtitle";
    public static String[] karaokeModes() { return new String[] { KARAOKE_OFF, KARAOKE_TITLE, KARAOKE_BOTH }; }

    public static final String PROGRESS_NONE    = "None";
    public static final String PROGRESS_BAR     = "Bar inside the card";
    public static final String PROGRESS_DOTS    = "Dots beside the cards";
    public static final String PROGRESS_COUNTER = "Counter (3 / 12)";
    public static String[] progressStyles() {
        return new String[] { PROGRESS_NONE, PROGRESS_BAR, PROGRESS_DOTS, PROGRESS_COUNTER };
    }

    public static final String REVEAL_FADE  = "Fade in";
    public static final String REVEAL_SLIDE = "Slide up";
    public static final String REVEAL_FLIP  = "Flip the card (flash card)";
    public static String[] revealStyles() { return new String[] { REVEAL_FADE, REVEAL_SLIDE, REVEAL_FLIP }; }
    /** How long the reveal animation itself takes, ms. */
    static final int REVEAL_MS = 520;

    public static final String GLOW_OFF  = "Off";
    public static final String GLOW_SOFT = "Soft glow";
    public static final String GLOW_NEON = "Neon (bright rim, gentle pulse)";
    public static String[] glowStyles() { return new String[] { GLOW_OFF, GLOW_SOFT, GLOW_NEON }; }

    public static final String PARTICLES_OFF      = "Off";
    public static final String PARTICLES_BOKEH    = "Bokeh lights";
    public static final String PARTICLES_SPARKLES = "Sparkles";
    public static String[] particleStyles() {
        return new String[] { PARTICLES_OFF, PARTICLES_BOKEH, PARTICLES_SPARKLES };
    }

    public static final String KB_OFF       = "Off";
    public static final String KB_ZOOM_IN   = "Slow zoom in";
    public static final String KB_ZOOM_OUT  = "Slow zoom out";
    public static final String KB_PAN_LEFT  = "Slow pan left";
    public static final String KB_PAN_RIGHT = "Slow pan right";
    public static String[] kenBurnsModes() {
        return new String[] { KB_OFF, KB_ZOOM_IN, KB_ZOOM_OUT, KB_PAN_LEFT, KB_PAN_RIGHT };
    }

    /** Sound choice meaning "a file the user picked" (its path is kept beside it). */
    public static final String SOUND_NONE = "None";
    public static final String SOUND_FILE = "Your own file…";

    public static final String DIR_UP   = "Next card comes from below (or from the right)";
    public static final String DIR_DOWN = "Next card comes from above (or from the left)";

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
    /** Where the title / subtitle sit across the card's text area (see {@link #alignments()}). */
    public String titleAlign = ALIGN_LEFT;
    public String subtitleAlign = ALIGN_LEFT;
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

    // effects
    public String layout = LAYOUT_VERTICAL;
    /** Springy pop when a card lands in the centre. */
    public boolean popOn = false;
    /** A shine sweeps across the centre card as it lands. */
    public boolean shineOn = false;
    public String glow = GLOW_OFF;
    public Color glowColor = new Color(57, 182, 234);
    /** Glow in each card's own icon colour instead of {@link #glowColor}. */
    public boolean glowUseIcon = true;
    public String karaoke = KARAOKE_OFF;
    public Color karaokeColor = new Color(255, 122, 69);
    /** Show the title first and reveal the subtitle (the answer) after a pause. */
    public boolean revealOn = false;
    public String revealStyle = REVEAL_FADE;
    /** Pause before the reveal, ms after the card lands. */
    public int revealDelayMs = 1500;
    public String progressStyle = PROGRESS_NONE;
    public Color progressColor = new Color(57, 182, 234);
    /** Effect sounds: a built-in name, {@link #SOUND_NONE} or {@link #SOUND_FILE} (+ path). */
    public String turnSound = SOUND_NONE;
    public String turnSoundPath = "";
    public String arriveSound = SOUND_NONE;
    public String arriveSoundPath = "";
    public String revealSound = SOUND_NONE;
    public String revealSoundPath = "";
    public int fxVolume = 70;

    // optional backdrop
    /** Paint a full-frame gradient behind the cards (covers the slide picture). */
    public boolean backdrop = false;
    public Color backdropColor1 = new Color(68, 7, 135);
    public Color backdropColor2 = new Color(12, 184, 187);
    /** Gradient direction, degrees counter-clockwise from "left to right". */
    public int backdropAngle = 31;
    /** Let the gradient sway slowly with a soft drifting light. */
    public boolean backdropAnimate = false;
    public String particles = PARTICLES_OFF;
    public int particleCount = 28;
    public Color particleColor = Color.WHITE;
    /** Slow zoom / pan of the slide picture behind the cards (ignored under the backdrop). */
    public String kenBurns = KB_OFF;

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
        titleAlign = s.titleAlign; subtitleAlign = s.subtitleAlign;
        titleSizePct = s.titleSizePct; subtitleSizePct = s.subtitleSizePct;
        shadow = s.shadow; cornerPct = s.cornerPct; sideCards = s.sideCards;
        sideScalePct = s.sideScalePct; gapPct = s.gapPct;
        timingMode = s.timingMode; direction = s.direction; startMs = s.startMs;
        holdMs = s.holdMs; moveMs = s.moveMs; loop = s.loop; fadeIn = s.fadeIn;
        stretchSlide = s.stretchSlide; audioVolume = s.audioVolume;
        backdrop = s.backdrop; backdropColor1 = s.backdropColor1; backdropColor2 = s.backdropColor2;
        backdropAngle = s.backdropAngle;
        backdropAnimate = s.backdropAnimate; particles = s.particles; particleCount = s.particleCount;
        particleColor = s.particleColor; kenBurns = s.kenBurns;
        layout = s.layout; popOn = s.popOn; shineOn = s.shineOn; glow = s.glow; glowColor = s.glowColor;
        glowUseIcon = s.glowUseIcon; karaoke = s.karaoke; karaokeColor = s.karaokeColor;
        revealOn = s.revealOn; revealStyle = s.revealStyle; revealDelayMs = s.revealDelayMs;
        progressStyle = s.progressStyle; progressColor = s.progressColor;
        turnSound = s.turnSound; turnSoundPath = s.turnSoundPath;
        arriveSound = s.arriveSound; arriveSoundPath = s.arriveSoundPath;
        revealSound = s.revealSound; revealSoundPath = s.revealSoundPath; fxVolume = s.fxVolume;
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
        // A reveal needs its pause, the animation, and time to read the answer.
        if (revealOn) hold = Math.max(hold, Math.max(0, revealDelayMs) + REVEAL_MS + 1300);
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
     * Every card arrival while the slide runs, slide-relative ms:
     * {arrives, card index, its turn started (-1 = it opens the carousel), first pass 1/0}.
     */
    public List<int[]> arrivals(int slideMs) {
        List<int[]> out = new ArrayList<>();
        if (!isActive()) return out;
        int n = items.size();
        int base = Math.max(0, startMs);
        if (audioMode() || n <= 1 || !loop) {
            int[] at = audioMode() ? audioCues : fixedArrivals();
            for (int k = 0; k < n; k++) {
                int a = base + (k == 0 ? 0 : at[k]);
                if (a >= slideMs) break;
                int turn = k == 0 ? -1 : (audioMode()
                        ? base + Math.max(audioCues[k - 1], audioCues[k] - move())
                        : a - move());
                out.add(new int[] { a, k, turn, 1 });
            }
            return out;
        }
        int[] at = fixedArrivals();
        int period = Math.max(1, at[n]);
        for (long cycle = 0; base + cycle * period < slideMs; cycle++) {
            for (int k = 0; k < n; k++) {
                long a = base + cycle * period + at[k];
                if (a >= slideMs) break;
                boolean opening = cycle == 0 && k == 0;
                out.add(new int[] { (int) a, k, opening ? -1 : (int) (a - move()), cycle == 0 ? 1 : 0 });
            }
        }
        return out;
    }

    /**
     * Every moment a card with a sound arrives in the centre while the slide is
     * on screen: {slide-relative ms, card index}. A loop replays a card's sound
     * each time it comes back, as long as the sound fits before the slide ends.
     */
    public List<int[]> audioEvents(int slideMs) {
        List<int[]> out = new ArrayList<>();
        for (int[] a : arrivals(slideMs)) addEvent(out, a[1], a[0], slideMs, a[3] == 1);
        return out;
    }

    /** Effect-sound kinds returned by {@link #fxEvents}. */
    public static final int FX_TURN = 0, FX_ARRIVE = 1, FX_REVEAL = 2;

    /**
     * When the effect sounds fire, slide-relative ms: {at, kind}. The whoosh as
     * each turn starts, the tick as the card lands, the reveal chime as the
     * answer appears. Only kinds that have a sound chosen are listed.
     */
    public List<int[]> fxEvents(int slideMs) {
        List<int[]> out = new ArrayList<>();
        boolean turn = hasSound(turnSound, turnSoundPath);
        boolean arrive = hasSound(arriveSound, arriveSoundPath);
        boolean rev = revealOn && hasSound(revealSound, revealSoundPath);
        if (!turn && !arrive && !rev) return out;
        for (int[] a : arrivals(slideMs)) {
            if (turn && a[2] >= 0) out.add(new int[] { a[2], FX_TURN });
            if (arrive && a[2] >= 0) out.add(new int[] { a[0], FX_ARRIVE });
            if (rev) {
                int at = a[0] + Math.max(0, revealDelayMs);
                if (at < slideMs) out.add(new int[] { at, FX_REVEAL });
            }
        }
        return out;
    }

    /** True when a sound choice actually names something to play. */
    public static boolean hasSound(String choice, String path) {
        if (choice == null || SOUND_NONE.equals(choice) || choice.isEmpty()) return false;
        if (SOUND_FILE.equals(choice)) return path != null && !path.trim().isEmpty();
        return true;
    }

    private void addEvent(List<int[]> out, int k, int atMs, int slideMs, boolean always) {
        Item it = items.get(k);
        if (it == null || !it.hasAudio() || atMs >= slideMs) return;
        // A replay that would be cut off by the end of the slide is left out.
        if (!always && it.audioMs > 0 && atMs + it.audioMs > slideMs) return;
        out.add(new int[] { atMs, k });
    }

    /**
     * The card resting in the centre {@code t} ms after the carousel started:
     * {card index, the moment it arrived (same clock as t), how long it rests},
     * or null while the cards are turning.
     */
    private long[] centreRest(long t) {
        int n = items.size();
        if (n <= 1) return new long[] { 0, 0, holdOf(0) };
        if (audioMode()) {
            for (int k = n - 1; k >= 0; k--) {
                long arrive = k == 0 ? 0 : audioCues[k];
                if (t < arrive) continue;
                long leave = k < n - 1 ? Math.max(audioCues[k], audioCues[k + 1] - move()) : Long.MAX_VALUE;
                if (t >= leave) return null;
                return new long[] { k, arrive, k < n - 1 ? leave - arrive : holdOf(k) };
            }
            return null;
        }
        int[] at = fixedArrivals();
        long cycles = 0, r = t;
        if (loop) {
            int period = Math.max(1, at[n]);
            cycles = t / period;
            r = t - cycles * period;
        } else if (t >= at[n - 1]) {
            return new long[] { n - 1, at[n - 1], holdOf(n - 1) };
        }
        for (int k = 0; k < n; k++) {
            if (r < at[k + 1]) {
                long into = r - at[k];
                int hold = holdOf(k);
                return into < hold ? new long[] { k, t - into, hold } : null;
            }
        }
        return null;
    }

    /** True when something on screen moves on every frame (living gradient, particles, Ken Burns, neon). */
    public boolean animatesContinuously() {
        return (backdrop && backdropAnimate)
                || (particles != null && !PARTICLES_OFF.equals(particles))
                || (!backdrop && kenBurns != null && !KB_OFF.equals(kenBurns))
                || GLOW_NEON.equals(glow);
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
        // Effects that never stop moving make every moment unique.
        if (backdrop && backdropAnimate) return "e" + elapsedMs;
        if (particles != null && !PARTICLES_OFF.equals(particles)) return "e" + elapsedMs;
        if (!backdrop && kenBurns != null && !KB_OFF.equals(kenBurns)) return "e" + elapsedMs;
        if (GLOW_NEON.equals(glow)) return "e" + elapsedMs;
        double a = fadeIn ? Math.min(1.0, t / FADE_IN_MS) : 1.0;
        String key = String.format(java.util.Locale.US, "%.4f|%.3f", progress(t), a);
        // While a card rests, its landing effects run for a while, then it is still.
        long[] rest = centreRest(t);
        if (rest != null) {
            long since = t - rest[1];
            boolean busy = (popOn && since < 1200) || (shineOn && since < 1000)
                    || (revealOn && since >= revealDelayMs && since < revealDelayMs + REVEAL_MS)
                    || (!KARAOKE_OFF.equals(karaoke) && karaoke != null && since < rest[2])
                    || (PROGRESS_BAR.equals(progressStyle) && since < rest[2]);
            if (busy) key += "|s" + since;
            else if (revealOn) key += since >= revealDelayMs ? "|r" : "|h";
        }
        return key;
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

    /**
     * Distance from the centre card's centre to slot {@code k}'s centre, in px:
     * half of each neighbouring card's extent ({@code unit} = card height when
     * stacked vertically, card + badge width side by side) plus the gap.
     */
    private double offsetAtSlot(int k, double unit, double H) {
        double g = 0.153 * H * Math.max(0, gapPct) / 100.0;
        double off = 0;
        for (int i = 1; i <= k; i++) off += (scaleAtSlot(i - 1) + scaleAtSlot(i)) * 0.5 * unit + g;
        return off;
    }

    private double lerpScale(double d) {
        int k = (int) Math.floor(d);
        double f = d - k;
        return scaleAtSlot(k) + (scaleAtSlot(k + 1) - scaleAtSlot(k)) * f;
    }

    private double lerpOffset(double d, double unit, double H) {
        int k = (int) Math.floor(d);
        double f = d - k;
        double a = offsetAtSlot(k, unit, H), b = offsetAtSlot(k + 1, unit, H);
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
        // Ken Burns moves the picture underneath, so it goes first, on its own copy.
        if (!c.backdrop && !KB_OFF.equals(c.kenBurns) && c.kenBurns != null) {
            paintKenBurns(frame, c, elapsedMs);
        }
        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);

            if (c.backdrop) paintBackdrop(g, c, fw, fh, elapsedMs);
            if (c.particles != null && !PARTICLES_OFF.equals(c.particles)) {
                paintParticles(g, c, fw, fh, elapsedMs);
            }
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
            c.paintCards(g, fw, fh, t, elapsedMs, master);
        } finally {
            g.dispose();
        }
    }

    // ---- background ---------------------------------------------------------

    private static void paintBackdrop(Graphics2D g, SlideCarousel c, int fw, int fh, long elapsedMs) {
        double deg = c.backdropAngle;
        // "Living" gradient: the direction sways slowly and a soft light drifts
        // across, so a still slide never looks frozen. Both loop seamlessly.
        if (c.backdropAnimate) deg += 22 * Math.sin(2 * Math.PI * elapsedMs / 9000.0);
        double a = Math.toRadians(deg);
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
        g.setComposite(AlphaComposite.SrcOver);
        g.setPaint(new LinearGradientPaint(p1, p2, new float[] { 0f, 1f }, new Color[] { c1, c2 },
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fillRect(0, 0, fw, fh);
        if (c.backdropAnimate) {
            double e = elapsedMs / 1000.0;
            double lx = fw * (0.5 + 0.32 * Math.sin(e * 2 * Math.PI / 11.0));
            double ly = fh * (0.45 + 0.28 * Math.cos(e * 2 * Math.PI / 8.5));
            float rad = (float) (0.62 * Math.max(fw, fh));
            g.setPaint(new java.awt.RadialGradientPaint(new Point2D.Double(lx, ly), rad,
                    new float[] { 0f, 0.55f, 1f },
                    new Color[] { new Color(255, 255, 255, 46), new Color(255, 255, 255, 14),
                            new Color(255, 255, 255, 0) }));
            g.fillRect(0, 0, fw, fh);
        }
    }

    /**
     * Floating bokeh lights or twinkling sparkles. Every particle's path is a
     * pure function of time (fixed seeds), so each exported frame is exact and
     * the preview matches the video.
     */
    private static void paintParticles(Graphics2D g, SlideCarousel c, int fw, int fh, long elapsedMs) {
        boolean bokeh = PARTICLES_BOKEH.equals(c.particles);
        int count = Math.max(1, Math.min(150, c.particleCount));
        Color pc = c.particleColor != null ? c.particleColor : Color.WHITE;
        double e = elapsedMs / 1000.0;
        java.util.Random rnd = new java.util.Random(0x5EED_CA20L);
        g.setComposite(AlphaComposite.SrcOver);
        for (int i = 0; i < count; i++) {
            double x0 = rnd.nextDouble(), y0 = rnd.nextDouble();
            double size = rnd.nextDouble(), speed = rnd.nextDouble(), ph = rnd.nextDouble() * 2 * Math.PI;
            double alpha0 = rnd.nextDouble();
            if (bokeh) {
                double r = fh * (0.014 + 0.040 * size);
                double v = fh * (0.010 + 0.028 * speed);              // px / s, upwards
                double span = fh + 4 * r;
                double y = (y0 * span - v * e) % span;
                if (y < 0) y += span;
                y -= 2 * r;
                double x = x0 * fw + fh * 0.03 * Math.sin(e * (0.25 + 0.3 * speed) + ph);
                double a = 0.05 + 0.15 * alpha0;
                a *= 0.75 + 0.25 * Math.sin(e * (0.6 + speed) + ph);
                java.awt.RadialGradientPaint rp = new java.awt.RadialGradientPaint(
                        new Point2D.Double(x, y), (float) r, new float[] { 0f, 0.7f, 1f },
                        new Color[] { withAlpha(pc, a), withAlpha(pc, a * 0.75), withAlpha(pc, 0) });
                g.setPaint(rp);
                g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
            } else {
                double r = fh * (0.0025 + 0.0045 * size);
                double v = fh * (0.004 + 0.012 * speed);
                double span = fh + 10 * r;
                double y = (y0 * span - v * e) % span;
                if (y < 0) y += span;
                y -= 5 * r;
                double x = x0 * fw + fh * 0.01 * Math.sin(e * 0.5 + ph);
                double tw = 0.5 + 0.5 * Math.sin(e * (2.2 + 3 * speed) + ph);   // twinkle
                double a = (0.25 + 0.65 * alpha0) * tw;
                double gr = r * 4;
                g.setPaint(new java.awt.RadialGradientPaint(new Point2D.Double(x, y), (float) gr,
                        new float[] { 0f, 1f }, new Color[] { withAlpha(pc, a * 0.35), withAlpha(pc, 0) }));
                g.fill(new Ellipse2D.Double(x - gr, y - gr, 2 * gr, 2 * gr));
                g.setColor(withAlpha(pc, a));
                g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
                // A small four-point glint on the brightest ones.
                if (alpha0 > 0.7 && tw > 0.6) {
                    g.setStroke(new BasicStroke((float) Math.max(0.6, r * 0.35), BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g.setColor(withAlpha(pc, a * 0.8));
                    double L = r * 3.2 * tw;
                    g.draw(new java.awt.geom.Line2D.Double(x - L, y, x + L, y));
                    g.draw(new java.awt.geom.Line2D.Double(x, y - L, x, y + L));
                }
            }
        }
    }

    /** Slow zoom / pan of everything under the carousel, across the carousel's run. */
    private static void paintKenBurns(BufferedImage frame, SlideCarousel c, long elapsedMs) {
        int fw = frame.getWidth(), fh = frame.getHeight();
        double span = Math.max(4000, c.isActive() ? c.requiredSlideMs() : 8000);
        double u = Math.max(0, Math.min(1, elapsedMs / span));
        u = 0.5 - 0.5 * Math.cos(Math.PI * u);                       // gentle in and out
        double z = 0.12;
        double s, ox = 0;
        switch (c.kenBurns) {
            case KB_ZOOM_OUT:  s = 1 + z * (1 - u); break;
            case KB_PAN_LEFT:  s = 1 + z * 0.75; ox = (0.5 - u) * fw * 0.08; break;
            case KB_PAN_RIGHT: s = 1 + z * 0.75; ox = (u - 0.5) * fw * 0.08; break;
            case KB_ZOOM_IN:
            default:           s = 1 + z * u;
        }
        BufferedImage src = new BufferedImage(fw, fh,
                frame.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_ARGB : frame.getType());
        Graphics2D cg = src.createGraphics();
        cg.drawImage(frame, 0, 0, null);
        cg.dispose();
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        AffineTransform at = new AffineTransform();
        at.translate(fw / 2.0 + ox, fh / 2.0);
        at.scale(s, s);
        at.translate(-fw / 2.0, -fh / 2.0);
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, at, null);
        g.dispose();
    }

    // ---- cards -----------------------------------------------------------------

    /** One card to draw: which item, its signed distance from the centre, and its rest timing. */
    private static final class Slot {
        final Item item; final double pos;
        /** ms since this card arrived in the centre and its rest length; -1 = not resting there. */
        long since = -1; int hold = 0;
        Slot(Item item, double pos) { this.item = item; this.pos = pos; }
    }

    private boolean horizontal() { return LAYOUT_HORIZONTAL.equals(layout); }

    private void paintCards(Graphics2D g, int fw, int fh, long t, long elapsedMs, double master) {
        int n = items.size();
        int depth = Math.max(1, Math.min(3, sideCards));
        double W = Math.max(8, widthPct / 100.0 * fw);
        double H = Math.max(4, W * Math.max(5, heightPct) / 100.0);
        double R = 0.371 * H * Math.max(10, haloSizePct) / 100.0;
        double cx = xPct / 100.0 * fw;
        double cy = yPct / 100.0 * fh;
        double dirSign = DIR_DOWN.equals(direction) ? -1 : 1;
        double unit = horizontal() ? (W + R) : H;
        double p = progress(t);
        long[] rest = centreRest(t);

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
            Slot s = new Slot(items.get(idx), pos);
            if (k == 0 && frac == 0 && rest != null) {
                s.since = Math.max(0, t - rest[1]);
                s.hold = (int) rest[2];
            }
            slots.add(s);
        }
        // Far cards first, the centre card last (on top).
        slots.sort((a, b) -> Double.compare(Math.abs(b.pos), Math.abs(a.pos)));

        Font titleBase = resolveFont(fontName, titleBold ? Font.BOLD : Font.PLAIN);
        Font subBase = resolveFont(fontName, subtitleBold ? Font.BOLD : Font.PLAIN);

        for (Slot s : slots) {
            double d = Math.abs(s.pos);
            double vis = d <= depth ? 1.0 : Math.max(0, 1.0 - (d - depth));
            if (vis <= 0.001) continue;
            double scale = lerpScale(d);
            double off = Math.signum(s.pos) * lerpOffset(d, unit, H) * dirSign;
            double m = Math.min(1.0, d);          // 0 = centre look, 1 = side look
            if (popOn && s.since >= 0) scale *= popScale(s.since);

            AffineTransform saved = g.getTransform();
            Composite savedComp = g.getComposite();
            if (horizontal()) g.translate(cx + off, cy);
            else              g.translate(cx, cy + off);
            g.scale(scale, scale);
            paintCard(g, s, W, H, R, m, master * vis, titleBase, subBase, elapsedMs);
            g.setTransform(saved);
            g.setComposite(savedComp);
        }

        if (PROGRESS_DOTS.equals(progressStyle) || PROGRESS_COUNTER.equals(progressStyle)) {
            paintIndicator(g, p, n, cx, cy, W, H, R, master, titleBase);
        }
    }

    /** Springy "pop" when a card lands: a quick swell that settles with a small wobble. */
    private static double popScale(long since) {
        double t = since / 1000.0;
        return 1 + 0.085 * Math.exp(-t / 0.16) * Math.sin(2 * Math.PI * t / 0.42);
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

    private static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    /** 0 = only the front shows, 1 = fully revealed (subtitle shown / card flipped). */
    private double revealOf(Slot s) {
        if (!revealOn) return 1;
        if (s.pos < -1e-9) return 1;          // already been in the centre
        if (s.pos > 1e-9) return 0;           // still to come — don't give the answer away
        if (s.since < 0) return 0;
        return Math.max(0, Math.min(1, (s.since - Math.max(0, revealDelayMs)) / (double) REVEAL_MS));
    }

    /**
     * Draw one card centred on (0,0) of the current transform (the card + its
     * badge together are centred, so the stack scales around the same axis).
     *
     * @param m     0 = centre-card look, 1 = side-card look (blended in between)
     * @param alpha overall opacity of this card
     */
    private void paintCard(Graphics2D g, Slot s, double W, double H, double R, double m,
                           double alpha, Font titleBase, Font subBase, long elapsedMs) {
        Item it = s.item;
        double reveal = revealOf(s);
        boolean flip = revealOn && REVEAL_FLIP.equals(revealStyle);
        boolean backFace = false;
        if (flip) {
            // Turn the whole card about its vertical axis: squeeze to an edge,
            // swap to the back face, open out again.
            double f = easeInOut(reveal);
            double sx = Math.abs(Math.cos(Math.PI * f));
            backFace = f >= 0.5;
            g.scale(Math.max(0.001, sx), 1);
        }

        double left = -(W - R) / 2.0;          // card's left edge = the badge's centre
        double top = -H / 2.0;
        double arc = 2 * 0.065 * H * Math.max(0, cornerPct) / 100.0;
        Shape card = new RoundRectangle2D.Double(left, top, W, H, arc, arc);
        Shape badge = new Ellipse2D.Double(left - R, -R, 2 * R, 2 * R);
        Area body = new Area(card);
        body.add(new Area(badge));
        double centre = 1 - m;                 // how "centre card" this card is right now

        // Glow / neon halo around the centre card (drawn first, so it sits behind).
        if (!GLOW_OFF.equals(glow) && glow != null && centre > 0) {
            Color gc = glowUseIcon && it != null && it.iconColor != null ? it.iconColor
                    : (glowColor != null ? glowColor : new Color(57, 182, 234));
            double pulse = GLOW_NEON.equals(glow)
                    ? 0.78 + 0.22 * Math.sin(2 * Math.PI * elapsedMs / 1500.0) : 1.0;
            double ga = (GLOW_NEON.equals(glow) ? 0.55 : 0.38) * centre * alpha * pulse;
            setAlpha(g, 1);
            int layers = 12;
            double reach = H * (GLOW_NEON.equals(glow) ? 0.42 : 0.34);
            for (int i = layers; i >= 1; i--) {
                double f = i / (double) layers;
                g.setStroke(new BasicStroke((float) (reach * f * 2), BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g.setColor(withAlpha(gc, ga * (1 - f) * (1 - f) * 0.55 + ga * 0.02));
                g.draw(body);
            }
        }

        // Soft drop shadow under the centre card only.
        if (shadow && m < 1) {
            double sa = 0.15 * centre * alpha;
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
            g.setColor(withAlpha(haloColor, centre));
            g.fill(badge);
        }

        // Neon: a crisp bright rim on top of the soft glow.
        if (GLOW_NEON.equals(glow) && centre > 0) {
            Color gc = glowUseIcon && it != null && it.iconColor != null ? it.iconColor
                    : (glowColor != null ? glowColor : new Color(57, 182, 234));
            setAlpha(g, alpha * centre);
            g.setStroke(new BasicStroke((float) (H * 0.028), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(gc);
            g.draw(body);
        }

        // Icon inside the badge.
        double iconAlpha = alpha * (centre + m * sideIconOpacity / 100.0);
        paintIcon(g, it, left, 0, R, iconAlpha, titleBase);

        // Texts.
        double textAlpha = alpha * (centre + m * sideTextOpacity / 100.0);
        paintTexts(g, s, left, top, W, H, R, textAlpha, titleBase, subBase, reveal, flip, backFace);

        // Progress bar along the bottom of the resting centre card.
        if (PROGRESS_BAR.equals(progressStyle) && s.since >= 0 && s.hold > 0) {
            Color pc = progressColor != null ? progressColor : new Color(57, 182, 234);
            double x0 = left + R + 0.21 * H, x1 = left + W - 0.10 * H;
            double bh = Math.max(1.5, H * 0.036), by = top + H - H * 0.10;
            double f = Math.max(0, Math.min(1, s.since / (double) s.hold));
            setAlpha(g, alpha);
            g.setColor(withAlpha(pc, 0.18));
            g.fill(new RoundRectangle2D.Double(x0, by, x1 - x0, bh, bh, bh));
            g.setColor(pc);
            g.fill(new RoundRectangle2D.Double(x0, by, Math.max(bh, (x1 - x0) * f), bh, bh, bh));
        }

        // Highlight sweep: one shine glides across the card as it lands.
        if (shineOn && s.since >= 0) {
            double u = (s.since - 120) / 780.0;
            if (u > 0 && u < 1) {
                u = easeInOut(u);
                double bw = W * 0.30;
                double xL = left - R - bw, xR = left + W + bw;
                double bx = xL + (xR - xL) * u;
                Color shine = luminance(fill) > 0.82
                        ? new Color(150, 200, 255)     // a cool sheen reads on a white card
                        : Color.WHITE;
                double peak = luminance(fill) > 0.82 ? 0.40 : 0.45;
                Shape oldClip = g.getClip();
                g.clip(body);
                AffineTransform t0 = g.getTransform();
                g.shear(-0.35, 0);
                setAlpha(g, alpha);
                g.setPaint(new LinearGradientPaint(
                        new Point2D.Double(bx - bw / 2, 0), new Point2D.Double(bx + bw / 2, 0),
                        new float[] { 0f, 0.5f, 1f },
                        new Color[] { withAlpha(shine, 0), withAlpha(shine, peak), withAlpha(shine, 0) }));
                g.fill(new Rectangle2D.Double(bx - bw / 2, top - H, bw, H * 3));
                g.setTransform(t0);
                g.setClip(oldClip);
            }
        }

        // Flip shading: the card darkens a little as it turns edge-on.
        if (flip && reveal > 0 && reveal < 1) {
            double sh = Math.sin(Math.PI * easeInOut(reveal)) * 0.10;
            setAlpha(g, alpha * sh);
            g.setColor(Color.BLACK);
            g.fill(body);
        }
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
                java.awt.font.TextLayout gv = shapedLayout(s, f, STABLE_FRC);
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

    /**
     * Karaoke fill fractions for the resting centre card: {title, subtitle}, each
     * 0..1 (or -1 = no fill). The fill runs with the card's own sound when it has
     * one; otherwise over the time before the reveal, or most of the rest.
     */
    private double[] karaokeOf(Slot s, boolean showsTitle, boolean showsSub) {
        double[] out = { -1, -1 };
        if (KARAOKE_OFF.equals(karaoke) || karaoke == null || s.since < 0) return out;
        Item it = s.item;
        long start = 150;
        double span;
        if (it != null && it.hasAudio() && it.audioMs > 0) span = it.audioMs;
        else if (revealOn) span = Math.max(400, revealDelayMs - 250);
        else span = Math.max(400, s.hold * 0.8);
        double f = Math.max(0, Math.min(1, (s.since - start) / span));
        boolean both = KARAOKE_BOTH.equals(karaoke);
        if (revealOn) {
            // Title fills before the reveal; the subtitle (if wanted) after it.
            out[0] = showsTitle ? f : -1;
            if (both && showsSub) {
                long subStart = Math.max(0, revealDelayMs) + REVEAL_MS;
                double subSpan = Math.max(500, s.hold - subStart - 300);
                out[1] = Math.max(0, Math.min(1, (s.since - subStart) / subSpan));
            }
            return out;
        }
        if (both && showsTitle && showsSub) {
            out[0] = Math.min(1, f * 2);       // title first, then the subtitle
            out[1] = Math.max(0, f * 2 - 1);
        } else {
            if (showsTitle) out[0] = f;
            else if (showsSub && both) out[1] = f;
        }
        return out;
    }

    private void paintTexts(Graphics2D g, Slot s, double left, double top, double W, double H,
                            double R, double alpha, Font titleBase, Font subBase,
                            double reveal, boolean flip, boolean backFace) {
        Item it = s.item;
        if (it == null || alpha <= 0.001) return;
        String title = it.title == null ? "" : it.title.replace('\n', ' ').trim();
        String sub = it.subtitle == null ? "" : it.subtitle.replace('\n', ' ').trim();
        if (titleUpper) title = title.toUpperCase();
        if (flip) {
            // Flash-card: the front carries the title, the back the subtitle,
            // each centred on its face in the title's size.
            if (backFace) { title = ""; } else { sub = ""; }
        }
        if (title.isEmpty() && sub.isEmpty()) return;

        double textX = left + R + 0.21 * H;
        double maxW = left + W - 0.10 * H - textX;
        if (maxW <= 4) return;
        float titleSize = (float) (0.155 * H * Math.max(10, titleSizePct) / 100.0);
        float subSize = (float) (0.145 * H * Math.max(10, subtitleSizePct) / 100.0);
        if (flip && backFace) subSize = Math.max(subSize, titleSize);

        // Reveal (fade / slide-up): the subtitle waits, then eases in.
        boolean slideReveal = revealOn && REVEAL_SLIDE.equals(revealStyle);
        double subAlpha = flip ? 1 : reveal;
        double subLift = slideReveal ? (1 - easeInOut(reveal)) * H * 0.16 : 0;
        boolean subShown = !sub.isEmpty() && subAlpha > 0.001;

        // With a reveal, the title waits centred on its own and glides up to its
        // place as the subtitle arrives — the card is never half empty.
        boolean both = !title.isEmpty() && !sub.isEmpty();
        double titleMid = both ? 0.335 : 0.5;
        if (both && revealOn && !flip) titleMid = 0.5 + (0.335 - 0.5) * easeInOut(reveal);
        double[] k = karaokeOf(s, !title.isEmpty(), subShown);
        Color kc = karaokeColor != null ? karaokeColor : new Color(255, 122, 69);
        // The card that just had its turn keeps its fill as it leaves, the colour
        // easing back to normal on the way, instead of snapping off.
        if (!KARAOKE_OFF.equals(karaoke) && karaoke != null && s.pos < 0 && s.pos > -1) {
            double keep = 1 + s.pos;                       // 1 at the centre → 0 one place away
            Color base = titleColor != null ? titleColor : new Color(69, 71, 77);
            kc = mix(base, kc, keep);
            k[0] = title.isEmpty() ? -1 : 1;
            if (KARAOKE_BOTH.equals(karaoke) && subShown) k[1] = 1;
        }

        if (!title.isEmpty()) {
            setAlpha(g, alpha);
            double capMid = top + H * titleMid;
            drawFitted(g, title, titleBase.deriveFont(titleSize), textX, capMid, maxW,
                    titleColor != null ? titleColor : new Color(69, 71, 77),
                    titleBold ? 0.055 : 0.018, titleAlign, k[0], kc);
        }
        if (subShown) {
            setAlpha(g, alpha * subAlpha);
            double capMid = top + H * (both ? 0.655 : 0.5) + subLift;
            Color sc = subtitleColor != null ? subtitleColor : new Color(92, 95, 103);
            if (flip && backFace && titleColor != null) sc = titleColor;
            drawFitted(g, sub, subBase.deriveFont(subSize), textX, capMid, maxW, sc,
                    subtitleBold ? 0.055 : 0.022, subtitleAlign, k[1], kc);
        }
    }

    /** Dots (one per card, the current one long and coloured) or a "3 / 12" counter pill. */
    private void paintIndicator(Graphics2D g, double p, int n, double cx, double cy, double W, double H,
                                double R, double master, Font titleBase) {
        Color pc = progressColor != null ? progressColor : new Color(57, 182, 234);
        boolean horiz = horizontal();
        // Beside the stack: right of the cards when vertical, under them when side by side.
        double ax = horiz ? cx : cx + (W + R) / 2.0 + H * 0.45;
        double ay = horiz ? cy + H / 2.0 + H * 0.42 : cy;
        int cur = Math.floorMod((int) Math.round(p), Math.max(1, n));
        setAlpha(g, master);
        if (PROGRESS_DOTS.equals(progressStyle) && n <= 20) {
            double r = H * 0.050, gap = H * 0.20;
            double len = (n - 1) * gap;
            double frac = p - Math.floor(p);
            int from = Math.floorMod((int) Math.floor(p), n);
            for (int i = 0; i < n; i++) {
                double c = i * gap - len / 2.0;
                double x = horiz ? ax + c : ax, y = horiz ? ay : ay + c;
                // The active "pill" glides from dot to dot as the cards turn.
                double w = (i == from) ? 1 - frac : (i == (from + 1) % n ? frac : 0);
                double rr = r * (1 + 0.45 * w);
                g.setColor(mix(withAlpha(pc, 0.35), pc, w));
                g.fill(new Ellipse2D.Double(x - rr, y - rr, 2 * rr, 2 * rr));
            }
            return;
        }
        // Counter pill.
        String txt = (cur + 1) + " / " + n;
        Font f = titleBase.deriveFont(Font.BOLD, (float) (H * 0.17));
        java.awt.font.FontRenderContext frc = STABLE_FRC;
        Rectangle2D sb = f.getStringBounds(txt, frc);
        double capH = f.createGlyphVector(frc, "8").getVisualBounds().getHeight();
        double pw = sb.getWidth() + H * 0.30, ph = capH + H * 0.20;
        double px = horiz ? ax - pw / 2 : ax - H * 0.05, py = ay - ph / 2;
        g.setColor(pc);
        g.fill(new RoundRectangle2D.Double(px, py, pw, ph, ph, ph));
        g.setColor(luminance(pc) > 0.7 ? new Color(30, 30, 36) : Color.WHITE);
        g.setFont(f);
        g.drawString(txt, (float) (px + (pw - sb.getWidth()) / 2), (float) (py + ph / 2 + capH / 2));
    }

    /**
     * Draw {@code s} with its capitals centred on {@code capMid}, shrunk (then cut)
     * to fit. Drawn as an outline so it can be given a little extra weight: the
     * fonts bundled with the app ship a single (regular) face, and Java's "bold"
     * on such a font is no bolder, so {@code weightEm} (of the font size) is
     * stroked around the glyphs when the font has no real bold face of its own.
     * {@code karaoke} (0..1, or -1 for none) repaints that share of the line in
     * {@code karaokeCol}, in reading order (right to left for Arabic).
     */
    private static void drawFitted(Graphics2D g, String s, Font f, double x, double capMid,
                                   double maxW, Color col, double weightEm, String align,
                                   double karaoke, Color karaokeCol) {
        java.awt.font.FontRenderContext frc = STABLE_FRC;
        java.awt.font.TextLayout tl = shapedLayout(s, f, frc);
        Font use = f;
        if (tl.getAdvance() > maxW) {
            float shrunk = (float) Math.max(f.getSize2D() * 0.72, f.getSize2D() * maxW / tl.getAdvance());
            use = f.deriveFont(shrunk);
            tl = shapedLayout(s, use, frc);
            if (tl.getAdvance() > maxW) {
                String ell = "…";
                String cut = s;
                while (cut.length() > 1 && shapedLayout(cut + ell, use, frc).getAdvance() > maxW) {
                    cut = cut.substring(0, cut.length() - 1);
                }
                tl = shapedLayout(cut.trim() + ell, use, frc);
            }
        }
        double capH = use.createGlyphVector(frc, "H").getVisualBounds().getHeight();
        // Place the line inside the text area [x, x + maxW] as asked.
        double free = Math.max(0, maxW - tl.getAdvance());
        String a = align == null ? ALIGN_LEFT : align;
        if (ALIGN_AUTO.equals(a)) a = tl.isLeftToRight() ? ALIGN_LEFT : ALIGN_RIGHT;
        if (ALIGN_CENTER.equals(a))     x += free / 2.0;
        else if (ALIGN_RIGHT.equals(a)) x += free;
        Shape outline = tl.getOutline(AffineTransform.getTranslateInstance(x, capMid + capH / 2.0));
        boolean realBold = use.isBold() && !use.getFontName().equals(use.deriveFont(Font.PLAIN).getFontName());
        double sw = realBold ? 0 : weightEm * use.getSize2D();
        fillText(g, outline, col, sw);
        if (karaoke > 0 && karaokeCol != null) {
            Rectangle2D b = outline.getBounds2D();
            double pad = sw + 1;
            double w = (b.getWidth() + 2 * pad) * Math.min(1, karaoke);
            Rectangle2D part = tl.isLeftToRight()
                    ? new Rectangle2D.Double(b.getX() - pad, b.getY() - pad, w, b.getHeight() + 2 * pad)
                    : new Rectangle2D.Double(b.getMaxX() + pad - w, b.getY() - pad, w, b.getHeight() + 2 * pad);
            Shape oldClip = g.getClip();
            g.clip(part);
            fillText(g, outline, karaokeCol, sw);
            g.setClip(oldClip);
        }
    }

    /**
     * Text is measured and laid out at a fixed, unscaled resolution and drawn as
     * outlines, so a line fits (or shrinks) the same way at every card size — a
     * card that pops or turns never flickers between a full and a shortened title.
     */
    private static final java.awt.font.FontRenderContext STABLE_FRC =
            new java.awt.font.FontRenderContext(null, true, true);

    private static void fillText(Graphics2D g, Shape outline, Color col, double strokeW) {
        g.setColor(col);
        g.fill(outline);
        if (strokeW > 0.01) {
            g.setStroke(new BasicStroke((float) strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
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
