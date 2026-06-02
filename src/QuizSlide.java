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

    // ============================================================
    //  QUIZ AUDIO CUE
    // ============================================================
    /**
     * Per-text (or text-less) audio cue for a quiz slide.
     *
     *  - The audio is mixed into the combined slide audio at the chosen
     *    {@code startMs} offset (absolute, from slide start).
     *  - When {@code targetTextIndex >= 1}, the cue's effects + highlight
     *    color are applied to slide-text item #{@code targetTextIndex}
     *    (1-based) while the cue is playing — re-using the existing
     *    audio-highlight pipeline (Glow / Enlarge / Bold / Underline /
     *    Color / Shake / Pulse / Others:*).
     *  - When {@code targetTextIndex == 0}, the cue is a "special" text-less
     *    audio: it plays at {@code startMs} but no text effect is applied.
     */
    public static class QuizCue {
        public File   audioFile = null;
        public int    durationMs = 0;
        public int    startMs = 0;
        public int    targetTextIndex = 0;   // 1-based; 0 = special / no text
        public String effects = "Glow";       // comma-separated; same vocab as toolbar7b
        public Color  hlColor = new Color(255, 200, 50, 160);
        public int    glowSize = 7;
        // When true, the cue is also played a second time the moment the
        // reveal phase begins (elapsedMs == revealAtMs). The same text effect
        // re-fires for the duration. Useful for "read the correct option
        // aloud right after the timer ends" workflows.
        public boolean playAfterReveal = false;
        // Optional play-order rank. When >= 1, the cue's effective start time
        // is computed by chaining the durations of cues with a smaller rank
        // (so rank=1 plays first at t=0, rank=2 starts when rank=1 finishes,
        // and so on). When 0, the manual {@link #startMs} value is used as-is.
        // Lets users say "play these in order" without computing offsets by
        // hand. See {@link QuizSlide#effectiveStartMs}.
        public int    rank = 0;

        public QuizCue copy() {
            QuizCue c = new QuizCue();
            c.audioFile        = audioFile;
            c.durationMs       = durationMs;
            c.startMs          = startMs;
            c.targetTextIndex  = targetTextIndex;
            c.effects          = effects;
            c.hlColor          = hlColor;
            c.glowSize         = glowSize;
            c.playAfterReveal  = playAfterReveal;
            c.rank             = rank;
            return c;
        }
    }

    /**
     * All cues configured via the "Audio timeline" sub-dialog. Mixed into the
     * combined slide audio at build time; consulted at render time so the
     * existing audio-highlight pipeline applies the cue's effects to the
     * target text while the cue is playing.
     */
    public List<QuizCue> cues = new ArrayList<>();

    // ============================================================
    //  SPECIAL-AUDIO TIMELINE (NEW — opt-in, parallel feature)
    // ============================================================
    /**
     * Schedule a text effect to fire at a specific moment within a single
     * "special" narration audio. Independent of {@link QuizCue}: there's no
     * per-event audio file — the cue is just an effect window keyed to the
     * special-audio playhead. Used by the new Special Audio Timeline dialog.
     *
     *  - {@link #atMs}             = absolute slide-relative ms when the effect fires
     *  - {@link #targetTextIndex}  = 1-based slide-text item to apply the effect to
     *  - {@link #durationMs}       = how long the effect stays on
     *  - {@link #effects}          = same vocabulary as QuizCue.effects
     *                                (Glow / Enlarge / Bold / Underline / Color
     *                                 / Shake / Pulse / Others:*)
     *  - {@link #hlColor} / {@link #glowSize} same as QuizCue
     */
    public static class TimelineEvent {
        public int    atMs = 0;
        public int    targetTextIndex = 1;
        public int    durationMs = 600;
        public String effects = "Glow";
        public Color  hlColor = new Color(255, 200, 50, 160);
        public int    glowSize = 7;

        public TimelineEvent copy() {
            TimelineEvent e = new TimelineEvent();
            e.atMs            = atMs;
            e.targetTextIndex = targetTextIndex;
            e.durationMs      = durationMs;
            e.effects         = effects;
            e.hlColor         = hlColor;
            e.glowSize        = glowSize;
            return e;
        }
    }

    // Opt-in switch. When false, every new field/method below is dormant
    // and the slide behaves byte-identically to the pre-feature codebase.
    public boolean useSpecialTimeline = false;

    // The single narration audio that drives the timeline. Becomes the
    // slide audio (mixed in alongside ticks/ding) when useSpecialTimeline
    // is true.
    public File specialTimelineAudioFile = null;
    public int  specialTimelineAudioDurationMs = 0;

    // Effect-fires scheduled against specialTimelineAudioFile's playhead.
    public List<TimelineEvent> timelineEvents = new ArrayList<>();

    // ============================================================
    //  AFTER-REVEAL TIMELINE (opt-in, plays AFTER the reveal moment)
    // ============================================================
    // Optional second narration that plays after revealAtMs(), with its own
    // schedule of effect fires on the same slide texts. Independent of the
    // pre-reveal special timeline above — either, both, or neither can be on.
    // Event atMs values are relative to this audio's own playhead (i.e. an
    // event with atMs = 500 fires 500ms after the after-reveal audio starts,
    // which is revealAtMs() + 500ms in slide-relative time).
    public boolean useAfterRevealTimeline = false;
    public File    afterRevealAudioFile = null;
    public int     afterRevealAudioDurationMs = 0;
    public List<TimelineEvent> afterRevealEvents = new ArrayList<>();

    // ===== Per-slide settings =====
    public boolean enabled = false;
    public int correctOptionIndex = 1;        // 1-based; refers to slide-text item index
    public int timerSeconds = 10;
    public int redThresholdSeconds = 4;

    // 1-based index of a slide-text item that should remain HIDDEN while the
    // countdown is running and only appear (without animation) once the timer
    // reaches zero. 0 = feature disabled. Independent of correctOptionIndex so
    // the hidden text can be the "answer reveal" and the highlighted text can
    // be one of the visible options (or both can point to the same item).
    public int hideTextIndex = 0;

    // ---- Hidden-text reveal animation ----
    // How the hidden text should APPEAR when the timer ends. "None" = pops in
    // instantly. Other values play a brief one-shot animation that runs from
    // revealAtMs() to revealAtMs() + hideRevealDurationMs.
    //   "None", "Fade", "Slide In Top", "Slide In Bottom",
    //   "Slide In Left", "Slide In Right", "Scale Up", "Bounce",
    //   "Zoom Out", "Rotate In", "Pop".
    public String hideRevealAnimation = "Fade";
    // Animation length in milliseconds (50..3000).
    public int    hideRevealDurationMs = 700;
    // Easing curve. Same vocabulary the timer animation uses:
    //   "Linear", "Ease In", "Ease Out", "Ease In Out", "Bounce".
    public String hideRevealEasing = "Ease Out";

    public String tickPreset = "Stock: Classic Clock";   // or "Custom"
    public File   customTickFile = null;

    public String dingPreset = "Stock: Bell";            // or "Custom"
    public File   customDingFile = null;

    public File questionAudioFile = null;
    public int  questionAudioDurationMs = 0;

    // When false, the question audio is skipped entirely: no validation,
    // no contribution to the combined audio, questionEndMs forced to 0
    // (so the timer starts at slide-start in both timer modes). Use this
    // when you want the special / per-text cues to BE the slide narration.
    public boolean useQuestionAudio = true;

    // Generated combined audio (question + tick*N + ding); attached to slide.
    public File generatedAudioFile = null;
    public int  generatedAudioDurationMs = 0;

    // Cached question-end offset in ms (== questionAudioDurationMs at attach time).
    public int  questionEndMs = 0;

    // ---- Timer visual style (configured via the toolbar7c row) ----
    // Style: "Number Circle" (default), "Progress Bar H", "Progress Bar V",
    //        "Ring Arc", "Analog Clock", "Hourglass", "Flip Clock",
    //        "Bomb Fuse", "Dot Grid".
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

    // ---- Custom urgent-phase color ----
    // When the remaining seconds drop into the red threshold, the timer flips
    // to this color (and pulses, depending on animation knobs). null keeps the
    // legacy fire-engine red.
    public Color timerRedColor = null;

    // ---- Animation knobs (applies to ALL styles) ----
    // "Pulse" (default — gentle scale-up at each second tick),
    // "Spin"  (slow rotation around the timer's visual center),
    // "Bounce" (vertical bob on each tick),
    // "Shake" (small jitter — best for the "red panic" trigger),
    // "None"  (no transform applied).
    public String timerAnimation = "Pulse";
    // 0..200 — scales the animation amplitude. 0 = effectively off,
    // 100 = default, 200 = exaggerated.
    public int timerAnimStrengthPct = 100;
    // When to fire the animation:
    //   "Red Phase" (default) — only during the urgent red countdown.
    //   "Always"              — entire timer life including frozen pre-roll.
    //   "Each Tick"           — fires only at second boundaries (everywhere).
    //   "Never"               — disabled regardless of style.
    public String timerAnimTrigger = "Red Phase";
    // Easing curve applied to the per-second 0→1 "since this tick" progress
    // that drives the animation amplitude.
    //   "Linear", "Ease In", "Ease Out" (default), "Ease In Out", "Bounce".
    public String timerAnimEasing = "Ease Out";

    // ---- Countdown digit fine-tuning (Look toolbar) ----
    // Offsets are % of the timer's reference size (diameter for Circle/Ring/
    // Clock, min(barW,barH) for bars). Range −300..300. 0 = default centered;
    // ±300 lets the digit be pushed clear of the timer shape entirely.
    public int     digitXOffsetPct = 0;
    public int     digitYOffsetPct = 0;
    // Multiplier on the auto-computed digit font size. 50..200, 100 = default.
    public int     digitSizePct    = 100;
    // Bold vs plain digit. Default true reads better against busy backgrounds.
    public boolean digitBold       = true;
    // Soft dark drop-shadow behind the digit so it stays readable on light
    // backgrounds. Default true.
    public boolean digitShadow     = true;
    // Whether to draw the countdown number at all. Set false for purely-visual
    // timers (bar / dots / fuse) where the shape alone communicates the time.
    public boolean digitShow       = true;

    // ---- Progress-bar tick direction ----
    // false (default): horizontal bar fills from the LEFT (drains from the
    //                  right edge); vertical bar fills from the BOTTOM.
    // true:            mirrored — horizontal fills from the RIGHT, vertical
    //                  fills from the TOP. Applies to Rounded/Square/Pill and
    //                  Segmented shapes.
    public boolean barReverse      = false;

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
    //  "AfterCue":                timer waits until the cue chosen by
    //                             {@link #timerStartCueIndex} finishes; useful
    //                             when an option / special audio should be heard
    //                             before the countdown begins.
    public String timerStartMode = "AfterQuestion";

    // Used only when timerStartMode == "AfterCue": which cue triggers the timer.
    // Same indexing as {@link QuizCue#targetTextIndex} — 1..N for text-targeting
    // cues, 0 for the special (text-less) cue. If no cue with that target exists
    // at build time, the timer falls back to slide-start (0).
    public int timerStartCueIndex = 0;

    public QuizSlide copy() {
        QuizSlide c = new QuizSlide();
        c.enabled = enabled;
        c.correctOptionIndex = correctOptionIndex;
        c.hideTextIndex = hideTextIndex;
        c.hideRevealAnimation = hideRevealAnimation;
        c.hideRevealDurationMs = hideRevealDurationMs;
        c.hideRevealEasing = hideRevealEasing;
        c.timerSeconds = timerSeconds;
        c.redThresholdSeconds = redThresholdSeconds;
        c.tickPreset = tickPreset;
        c.customTickFile = customTickFile;
        c.dingPreset = dingPreset;
        c.customDingFile = customDingFile;
        c.questionAudioFile = questionAudioFile;
        c.questionAudioDurationMs = questionAudioDurationMs;
        c.useQuestionAudio = useQuestionAudio;
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
        c.timerRedColor         = timerRedColor;
        c.timerAnimation        = timerAnimation;
        c.timerAnimStrengthPct  = timerAnimStrengthPct;
        c.timerAnimTrigger      = timerAnimTrigger;
        c.timerAnimEasing       = timerAnimEasing;
        c.digitXOffsetPct = digitXOffsetPct;
        c.digitYOffsetPct = digitYOffsetPct;
        c.digitSizePct    = digitSizePct;
        c.digitBold       = digitBold;
        c.digitShadow     = digitShadow;
        c.digitShow       = digitShow;
        c.barReverse      = barReverse;
        c.timerStartMode = timerStartMode;
        c.timerStartCueIndex = timerStartCueIndex;
        c.revealMarkStyle   = revealMarkStyle;
        c.revealMarkSizePct = revealMarkSizePct;
        c.revealMarkColor   = revealMarkColor;
        c.revealPadPct      = revealPadPct;
        if (cues != null) {
            c.cues = new ArrayList<>(cues.size());
            for (QuizCue q : cues) {
                if (q != null) c.cues.add(q.copy());
            }
        }
        // Opt-in special-audio timeline (parallel feature; no-op when off).
        c.useSpecialTimeline             = useSpecialTimeline;
        c.specialTimelineAudioFile       = specialTimelineAudioFile;
        c.specialTimelineAudioDurationMs = specialTimelineAudioDurationMs;
        if (timelineEvents != null) {
            c.timelineEvents = new ArrayList<>(timelineEvents.size());
            for (TimelineEvent e : timelineEvents) {
                if (e != null) c.timelineEvents.add(e.copy());
            }
        }
        c.useAfterRevealTimeline       = useAfterRevealTimeline;
        c.afterRevealAudioFile         = afterRevealAudioFile;
        c.afterRevealAudioDurationMs   = afterRevealAudioDurationMs;
        if (afterRevealEvents != null) {
            c.afterRevealEvents = new ArrayList<>(afterRevealEvents.size());
            for (TimelineEvent e : afterRevealEvents) {
                if (e != null) c.afterRevealEvents.add(e.copy());
            }
        }
        return c;
    }

    // ============================================================
    //  QUIZ CUE HELPERS (consulted at render time)
    // ============================================================

    /**
     * Returns the cue (if any) that targets slide-text index {@code textIdx0Based}
     * and is currently inside its [startMs, startMs+durationMs) window. Used by
     * the renderer to decide which cue's color / effects / glow to apply.
     */
    public QuizCue activeCueForTextIndex(int textIdx0Based, long elapsedMs) {
        // After-reveal timeline overlays on top of any other mode once the
        // reveal moment has passed — its events take precedence so the user's
        // post-reveal highlights actually appear.
        TimelineEvent ar = activeAfterRevealEventForTextIndex(textIdx0Based, elapsedMs);
        if (ar != null) return synthCueForAfterRevealEvent(ar);
        // Special-audio timeline mode is EXCLUSIVE: only timeline events
        // drive text highlights, and the old per-text cues are not consulted.
        // When the flag is off, the legacy search below runs unchanged.
        if (useSpecialTimeline) {
            return synthCueForTimelineEvent(
                    activeTimelineEventForTextIndex(textIdx0Based, elapsedMs));
        }
        if (cues == null || cues.isEmpty()) return null;
        long revealAt = revealAtMs();
        for (QuizCue c : cues) {
            if (c == null || c.audioFile == null || c.durationMs <= 0) continue;
            if (c.targetTextIndex - 1 != textIdx0Based) continue;
            int start = effectiveStartMs(c);
            if (elapsedMs >= start && elapsedMs < start + c.durationMs) {
                return c;
            }
            if (c.playAfterReveal
                    && elapsedMs >= revealAt
                    && elapsedMs < revealAt + c.durationMs) {
                return c;
            }
        }
        return null;
    }

    // -- Special timeline helpers (no-op when useSpecialTimeline is off) --

    /** First timeline event whose [atMs, atMs+durationMs) window contains
     *  elapsedMs and targets the given 0-based text index, or null. */
    public TimelineEvent activeTimelineEventForTextIndex(int textIdx0Based, long elapsedMs) {
        if (!useSpecialTimeline || timelineEvents == null) return null;
        for (TimelineEvent e : timelineEvents) {
            if (e == null || e.durationMs <= 0) continue;
            if (e.targetTextIndex - 1 != textIdx0Based) continue;
            if (elapsedMs >= e.atMs && elapsedMs < e.atMs + e.durationMs) {
                return e;
            }
        }
        return null;
    }

    /** First text-targeting timeline event currently alive at elapsedMs. */
    public TimelineEvent activeTimelineEventAt(long elapsedMs) {
        if (!useSpecialTimeline || timelineEvents == null) return null;
        for (TimelineEvent e : timelineEvents) {
            if (e == null || e.durationMs <= 0) continue;
            if (e.targetTextIndex <= 0) continue;
            if (elapsedMs >= e.atMs && elapsedMs < e.atMs + e.durationMs) {
                return e;
            }
        }
        return null;
    }

    /** First after-reveal event whose [revealAt+atMs, +durationMs) window
     *  contains elapsedMs and targets the given 0-based text index, or null.
     *  No-op when the after-reveal timeline is off or the moment hasn't
     *  arrived yet. */
    public TimelineEvent activeAfterRevealEventForTextIndex(int textIdx0Based, long elapsedMs) {
        if (!useAfterRevealTimeline || afterRevealEvents == null) return null;
        long revealAt = revealAtMs();
        if (elapsedMs < revealAt) return null;
        long sinceReveal = elapsedMs - revealAt;
        for (TimelineEvent e : afterRevealEvents) {
            if (e == null || e.durationMs <= 0) continue;
            if (e.targetTextIndex - 1 != textIdx0Based) continue;
            if (sinceReveal >= e.atMs && sinceReveal < e.atMs + e.durationMs) {
                return e;
            }
        }
        return null;
    }

    /** First text-targeting after-reveal event currently alive at elapsedMs. */
    public TimelineEvent activeAfterRevealEventAt(long elapsedMs) {
        if (!useAfterRevealTimeline || afterRevealEvents == null) return null;
        long revealAt = revealAtMs();
        if (elapsedMs < revealAt) return null;
        long sinceReveal = elapsedMs - revealAt;
        for (TimelineEvent e : afterRevealEvents) {
            if (e == null || e.durationMs <= 0) continue;
            if (e.targetTextIndex <= 0) continue;
            if (sinceReveal >= e.atMs && sinceReveal < e.atMs + e.durationMs) {
                return e;
            }
        }
        return null;
    }

    /** Wrap an after-reveal event in a transient QuizCue. Points audioFile
     *  at the after-reveal narration so downstream "is this cue alive"
     *  checks pass. */
    private QuizCue synthCueForAfterRevealEvent(TimelineEvent e) {
        if (e == null) return null;
        QuizCue c = new QuizCue();
        c.audioFile        = afterRevealAudioFile;
        c.durationMs       = Math.max(1, e.durationMs);
        c.startMs          = (int) Math.max(0, revealAtMs()) + e.atMs;
        c.targetTextIndex  = e.targetTextIndex;
        c.effects          = e.effects != null ? e.effects : "Glow";
        c.hlColor          = e.hlColor != null ? e.hlColor : new Color(255, 200, 50, 160);
        c.glowSize         = e.glowSize > 0 ? e.glowSize : 7;
        c.playAfterReveal  = false;
        c.rank             = 0;
        return c;
    }

    /** Wrap a TimelineEvent in a transient QuizCue so the existing
     *  render-side pipeline (which consumes QuizCue values) "just works". */
    private QuizCue synthCueForTimelineEvent(TimelineEvent e) {
        if (e == null) return null;
        QuizCue c = new QuizCue();
        // audioFile gates "is this cue alive" in some downstream checks —
        // point it at the special-timeline narration so it passes the check.
        c.audioFile        = specialTimelineAudioFile;
        c.durationMs       = Math.max(1, e.durationMs);
        c.startMs          = e.atMs;
        c.targetTextIndex  = e.targetTextIndex;
        c.effects          = e.effects != null ? e.effects : "Glow";
        c.hlColor          = e.hlColor != null ? e.hlColor : new Color(255, 200, 50, 160);
        c.glowSize         = e.glowSize > 0 ? e.glowSize : 7;
        c.playAfterReveal  = false;
        c.rank             = 0;
        return c;
    }

    /**
     * Returns the first text-targeting cue currently active at {@code elapsedMs},
     * or null. "Text-targeting" = targetTextIndex >= 1 (excludes special cues).
     */
    public QuizCue activeTextCueAt(long elapsedMs) {
        // After-reveal timeline takes precedence once the reveal moment passes.
        TimelineEvent ar = activeAfterRevealEventAt(elapsedMs);
        if (ar != null) return synthCueForAfterRevealEvent(ar);
        // Special-audio timeline mode is EXCLUSIVE: only timeline events
        // drive text highlights, and the old per-text cues are not consulted.
        // When the flag is off, the legacy search below runs unchanged.
        if (useSpecialTimeline) {
            return synthCueForTimelineEvent(activeTimelineEventAt(elapsedMs));
        }
        if (cues == null || cues.isEmpty()) return null;
        long revealAt = revealAtMs();
        for (QuizCue c : cues) {
            if (c == null || c.audioFile == null || c.durationMs <= 0) continue;
            if (c.targetTextIndex <= 0) continue;
            int start = effectiveStartMs(c);
            if (elapsedMs >= start && elapsedMs < start + c.durationMs) {
                return c;
            }
            if (c.playAfterReveal
                    && elapsedMs >= revealAt
                    && elapsedMs < revealAt + c.durationMs) {
                return c;
            }
        }
        return null;
    }

    /**
     * Resolve a cue's effective start time (slide-relative ms). When the cue
     * has {@code rank >= 1} it plays in rank order — its start is the sum of
     * durations of every cue with a smaller positive rank. When {@code rank
     * == 0} the cue uses its manually-set {@link QuizCue#startMs}. Lets users
     * say "play these in order" without computing offsets by hand.
     */
    public int effectiveStartMs(QuizCue cue) {
        if (cue == null) return 0;
        if (cue.rank <= 0) return Math.max(0, cue.startMs);
        if (cues == null) return 0;
        int acc = 0;
        for (QuizCue other : cues) {
            if (other == null || other.audioFile == null || other.durationMs <= 0) continue;
            if (other == cue) continue;
            if (other.rank <= 0) continue;
            if (other.rank < cue.rank) {
                acc += other.durationMs;
            } else if (other.rank == cue.rank) {
                // Tie-breaker: target index (lower wins, special=0 last).
                int tThis  = cue.targetTextIndex   <= 0 ? Integer.MAX_VALUE : cue.targetTextIndex;
                int tOther = other.targetTextIndex <= 0 ? Integer.MAX_VALUE : other.targetTextIndex;
                if (tOther < tThis) acc += other.durationMs;
            }
        }
        return acc;
    }

    /**
     * Highest end-ms (startMs + durationMs) across all cues — used by the
     * combined-audio builder to pad the output WAV out to the latest cue end
     * so the slide's totalAudioDurationMs covers the whole timeline.
     */
    public int cuesEndMs() {
        if (cues == null || cues.isEmpty()) return 0;
        int end = 0;
        for (QuizCue c : cues) {
            if (c == null || c.audioFile == null || c.durationMs <= 0) continue;
            end = Math.max(end, effectiveStartMs(c) + c.durationMs);
        }
        return end;
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
        this.timerRedColor        = src.timerRedColor;
        this.timerAnimation       = src.timerAnimation;
        this.timerAnimStrengthPct = src.timerAnimStrengthPct;
        this.timerAnimTrigger     = src.timerAnimTrigger;
        this.timerAnimEasing      = src.timerAnimEasing;
        this.digitXOffsetPct   = src.digitXOffsetPct;
        this.digitYOffsetPct   = src.digitYOffsetPct;
        this.digitSizePct      = src.digitSizePct;
        this.digitBold         = src.digitBold;
        this.digitShadow       = src.digitShadow;
        this.digitShow         = src.digitShow;
        this.barReverse        = src.barReverse;
        this.hideTextIndex     = src.hideTextIndex;
        this.hideRevealAnimation  = src.hideRevealAnimation;
        this.hideRevealDurationMs = src.hideRevealDurationMs;
        this.hideRevealEasing     = src.hideRevealEasing;
        this.revealMarkStyle   = src.revealMarkStyle;
        this.revealMarkSizePct = src.revealMarkSizePct;
        this.revealMarkColor   = src.revealMarkColor;
        this.revealPadPct      = src.revealPadPct;
        this.timerSeconds      = src.timerSeconds;
        this.redThresholdSeconds = src.redThresholdSeconds;
        this.timerStartMode    = src.timerStartMode;
        this.timerStartCueIndex = src.timerStartCueIndex;
        this.tickPreset        = src.tickPreset;
        this.dingPreset        = src.dingPreset;
        this.customTickFile    = src.customTickFile;
        this.customDingFile    = src.customDingFile;

        // Merge per-cue VISUAL settings keyed by targetTextIndex. The
        // destination cue's audioFile / durationMs / startMs are preserved so
        // each slide keeps its own recording — only effects / color / glow /
        // rank / playAfterReveal propagate from the master.
        if (src.cues != null) {
            if (this.cues == null) this.cues = new ArrayList<>();
            for (QuizCue srcCue : src.cues) {
                if (srcCue == null) continue;
                QuizCue dstCue = null;
                for (QuizCue c : this.cues) {
                    if (c != null && c.targetTextIndex == srcCue.targetTextIndex) {
                        dstCue = c;
                        break;
                    }
                }
                if (dstCue == null) {
                    dstCue = new QuizCue();
                    dstCue.targetTextIndex = srcCue.targetTextIndex;
                    this.cues.add(dstCue);
                }
                dstCue.effects         = srcCue.effects;
                dstCue.hlColor         = srcCue.hlColor;
                dstCue.glowSize        = srcCue.glowSize;
                dstCue.rank            = srcCue.rank;
                // playAfterReveal is deliberately NOT propagated from the
                // master — each slide owns its own Replay decision. The
                // CSV QUIZ_CUE_REPLAY column is the supported way to set
                // it per slide.
            }
        }
    }

    /**
     * Slide-relative ms at which the countdown begins. Computed from the
     * timer-start mode plus, for "AfterCue", the chosen cue's end offset.
     * Returns 0 when "AtSlideStart", and falls back to 0 when "AfterCue"
     * references a cue that doesn't exist.
     */
    public long timerStartMs() {
        // EXCLUSIVE special-timeline mode: the special narration IS the
        // question — timer always waits for it to finish, regardless of
        // the legacy timerStartMode (which targets the old narration paths).
        if (useSpecialTimeline && specialTimelineAudioDurationMs > 0) {
            return specialTimelineAudioDurationMs;
        }
        if ("AtSlideStart".equals(timerStartMode)) return 0L;
        if ("AfterCue".equals(timerStartMode)) {
            if (cues != null) {
                for (QuizCue c : cues) {
                    if (c == null || c.audioFile == null || c.durationMs <= 0) continue;
                    if (c.targetTextIndex == timerStartCueIndex) {
                        return (long) effectiveStartMs(c) + c.durationMs;
                    }
                }
            }
            return 0L;
        }
        return Math.max(0, questionEndMs);
    }

    /**
     * Slide-relative ms at which the timer naturally hits zero — independent
     * of how long the surrounding audio takes. Used by the countdown overlay
     * so the digits actually animate to 0 even when {@link #revealAtMs} is
     * pushed back to wait for late-finishing cue audio.
     */
    public long naturalRevealMs() {
        return timerStartMs() + Math.max(0, timerSeconds) * 1000L;
    }

    /**
     * Latest end-ms across all "pre-reveal" audio: the question narration
     * (if enabled) and every cue that's not marked playAfterReveal. The
     * reveal box waits at least until this moment so no narration is cut off.
     */
    public long lastNonReplayAudioEndMs() {
        long end = 0L;
        // In special-timeline mode the narration is the special audio; in
        // classic mode it's the question audio + per-text cues.
        if (useSpecialTimeline && specialTimelineAudioDurationMs > 0) {
            end = Math.max(end, specialTimelineAudioDurationMs);
        } else {
            if (useQuestionAudio) end = Math.max(end, Math.max(0, questionEndMs));
            if (cues != null) {
                for (QuizCue c : cues) {
                    if (c == null || c.audioFile == null || c.durationMs <= 0) continue;
                    if (c.playAfterReveal) continue;
                    end = Math.max(end, (long) effectiveStartMs(c) + c.durationMs);
                }
            }
        }
        return end;
    }

    /**
     * Reveal-moment elapsed-ms boundary for this quiz: the chosen hidden text
     * (and the reveal badge) should appear at or after this point in time.
     * Mirrors the same math used by {@link #applyOverlay} — the reveal waits
     * for ALL non-replay audio to finish (question, cues) so the box never
     * appears while audio is still being read. Replay-after-reveal cues are
     * excluded on purpose — they're designed to play AFTER reveal.
     */
    public long revealAtMs() {
        return Math.max(naturalRevealMs(), lastNonReplayAudioEndMs());
    }

    /**
     * True iff this quiz has a configured "hidden until reveal" text index
     * that points within the supplied slide-text list AND the current frame
     * is still BEFORE the reveal moment (so the chosen text should be hidden
     * from the renderer).
     *
     * @param textIdx0Based  zero-based slide-text index being considered
     * @param elapsedMs      elapsed time since slide start
     * @param textListSize   size of the slide-text list (out-of-range = no hide)
     */
    public boolean shouldHideText(int textIdx0Based, long elapsedMs, int textListSize) {
        if (!enabled) return false;
        int target = hideTextIndex - 1;
        if (target < 0 || target >= textListSize) return false;
        if (textIdx0Based != target) return false;
        return elapsedMs < revealAtMs();
    }

    /**
     * True iff the chosen "reveal" text is currently in its appear-animation
     * window (i.e. the timer just finished and the chosen animation hasn't
     * completed yet).
     */
    public boolean isInRevealAnimWindow(int textIdx0Based, long elapsedMs, int textListSize) {
        if (!enabled) return false;
        int target = hideTextIndex - 1;
        if (target < 0 || target >= textListSize) return false;
        if (textIdx0Based != target) return false;
        if (hideRevealAnimation == null || "None".equalsIgnoreCase(hideRevealAnimation)) return false;
        int dur = Math.max(0, hideRevealDurationMs);
        if (dur <= 0) return false;
        long rs = revealAtMs();
        return elapsedMs >= rs && elapsedMs < rs + dur;
    }

    /** Progress through the reveal animation, 0.0 (just started) → 1.0 (done). */
    public double revealAnimProgress(long elapsedMs) {
        int dur = Math.max(1, hideRevealDurationMs);
        long rs = revealAtMs();
        double t = (elapsedMs - rs) / (double) dur;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        return t;
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

            long timerMs       = quiz.timerSeconds * 1000L;
            long timerStart    = quiz.timerStartMs();
            long naturalReveal = quiz.naturalRevealMs();
            long revealAt      = quiz.revealAtMs();

            if (elapsedMs < timerStart) {
                // Pre-timer phase (question / cue still reading): timer visible
                // but FROZEN at full seconds.
                drawCountdown(g, w, h, quiz, quiz.timerSeconds, false, 0.0);
                return;
            }

            if (elapsedMs < naturalReveal) {
                // Counting down phase.
                long timerElapsed = elapsedMs - timerStart;
                long remainingMs  = Math.max(0, timerMs - timerElapsed);
                int  remainingSec = (int) Math.ceil(remainingMs / 1000.0);
                if (remainingSec < 1) remainingSec = 1;
                double progress = Math.max(0.0,
                        Math.min(1.0, timerElapsed / (double) timerMs));
                drawCountdown(g, w, h, quiz, remainingSec,
                        remainingSec <= quiz.redThresholdSeconds, progress);
            } else if (elapsedMs < revealAt) {
                // Audio-wait phase: timer hit zero but a non-replay cue is
                // still being read. Hold the timer at 0; suppress the reveal
                // box until the last narration finishes.
                drawCountdown(g, w, h, quiz, 0, true, 1.0);
            } else {
                // Reveal phase: highlight the correct option.
                long sinceReveal = elapsedMs - revealAt;
                drawReveal(g, w, h, slideTexts, quiz, sinceReveal);
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
        applyPreviewOverlay(frame, quiz, null);
    }

    /**
     * Same as {@link #applyPreviewOverlay(BufferedImage, QuizSlide)} but also
     * paints the reveal badge + highlight ring on the correct option, so the
     * editor preview is WYSIWYG for the tick-mark style/size/color too.
     * Pass the slide's text list (may be null to skip the reveal preview).
     */
    public static void applyPreviewOverlay(BufferedImage frame, QuizSlide quiz,
                                           List<?> slideTexts) {
        if (quiz == null || !quiz.enabled || frame == null) return;
        int w = frame.getWidth();
        int h = frame.getHeight();
        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Timer frozen at full seconds — what the viewer sees the instant
            // the question begins reading.
            drawCountdown(g, w, h, quiz, quiz.timerSeconds, false, 0.0);
            // Reveal (badge + highlight box) drawn past the pulse-in window so
            // it's stable, not animating, in the editor preview.
            if (slideTexts != null) {
                drawReveal(g, w, h, slideTexts, quiz, 1500L);
            }
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
        Color urgent = quiz.timerRedColor != null ? quiz.timerRedColor
                : new Color(235, 70, 70);
        Color accent = red ? urgent : userColor;
        Color bg     = new Color(15, 18, 25, 220);
        Color userText = quiz.timerTextColor != null ? quiz.timerTextColor : Color.WHITE;
        Color textCol = red ? brighten(urgent, 200) : userText;
        String style = quiz.timerStyle != null ? quiz.timerStyle : "Number Circle";

        // Animation transform (Pulse/Spin/Bounce/Shake) is computed once
        // around the timer's visual center so every style animates uniformly.
        java.awt.geom.AffineTransform savedTx = applyTimerAnimation(g, w, h, quiz,
                red, progress);

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
            case "Hourglass":
                drawHourglass(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress);
                break;
            case "Flip Clock":
                drawFlipClock(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress);
                break;
            case "Bomb Fuse":
                drawBombFuse(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress);
                break;
            case "Dot Grid":
                drawDotGrid(g, w, h, quiz, remainingSec, accent, bg, textCol, red, progress);
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

    /**
     * Push an animation transform onto `g` based on the user's animation
     * knobs. Returns the original transform so the caller can restore it,
     * or null when no animation is in effect.
     *
     * Trigger semantics:
     *   "Never"     — no transform.
     *   "Red Phase" — only while `red` is true.
     *   "Always"    — always while the timer is counting.
     *   "Each Tick" — short burst around each per-second boundary regardless
     *                 of red phase.
     */
    private static java.awt.geom.AffineTransform applyTimerAnimation(
            Graphics2D g, int w, int h, QuizSlide quiz, boolean red, double progress) {
        if (quiz == null || quiz.timerSeconds <= 0) return null;
        String anim = quiz.timerAnimation != null ? quiz.timerAnimation : "Pulse";
        if ("None".equalsIgnoreCase(anim)) return null;

        String trig = quiz.timerAnimTrigger != null ? quiz.timerAnimTrigger : "Red Phase";
        boolean active;
        switch (trig) {
            case "Never":     active = false; break;
            case "Always":    active = true;  break;
            case "Each Tick": active = true;  break;
            case "Red Phase":
            default:          active = red;   break;
        }
        if (!active) return null;

        // Per-second progress: 0.0 at the moment a second starts, → 1.0 just
        // before the next second tick.
        double secFrac = (progress * quiz.timerSeconds) % 1.0;
        if (secFrac < 0) secFrac += 1.0;

        // For "Each Tick" we want a narrow burst centered on the tick
        // boundary — collapse the [0,1] cycle into a quick rise+fall.
        double phase;
        if ("Each Tick".equalsIgnoreCase(trig)) {
            // first ~30% of each second carries the burst; rest is rest.
            phase = secFrac < 0.30 ? (secFrac / 0.30) : 0.0;
        } else {
            phase = secFrac;
        }
        double eased = ease(quiz.timerAnimEasing, phase);

        // Amplitude scaling: 0..200% maps to 0.0..2.0 of the per-style "unit".
        int strength = Math.max(0, Math.min(200, quiz.timerAnimStrengthPct));
        double amp = strength / 100.0;
        if (amp <= 0.0001) return null;

        double[] cxy = pulseCenter(w, h, quiz);
        java.awt.geom.AffineTransform saved = g.getTransform();

        switch (anim) {
            case "Spin": {
                // Continuous rotation — base spin proceeds with overall progress
                // so the wheel turns even outside the per-second pulse cycle.
                double baseTurns = progress * (1.0 + amp); // more strength = faster spin
                double extra = (1.0 - eased) * 0.25 * amp;  // little kick at tick
                double angle = 2 * Math.PI * (baseTurns + extra);
                g.translate(cxy[0], cxy[1]);
                g.rotate(angle);
                g.translate(-cxy[0], -cxy[1]);
                break;
            }
            case "Bounce": {
                // Vertical bob: jumps up at tick, eases back down. 8% of timer
                // height at amp=1.0.
                double lift = -h * 0.08 * amp * (1.0 - eased);
                g.translate(0, lift);
                break;
            }
            case "Shake": {
                // Small pseudo-random jitter scaled by amplitude. Uses the
                // continuous progress so successive frames differ.
                double t = progress * quiz.timerSeconds * 17.0;
                double dx = Math.sin(t * 3.1) * 6.0 * amp;
                double dy = Math.cos(t * 4.7) * 6.0 * amp;
                g.translate(dx, dy);
                break;
            }
            case "Pulse":
            default: {
                // Scale-up at tick, ease back to 1.0. 10% peak at amp=1.0.
                double scale = 1.0 + 0.10 * amp * (1.0 - eased);
                g.translate(cxy[0], cxy[1]);
                g.scale(scale, scale);
                g.translate(-cxy[0], -cxy[1]);
                break;
            }
        }
        return saved;
    }

    /** Public alias of {@link #ease(String, double)} for cross-class callers. */
    public static double easeNamed(String name, double t) {
        return ease(name, t);
    }

    /** Apply a named easing curve to t in [0,1]. */
    private static double ease(String name, double t) {
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        if (name == null) name = "Ease Out";
        switch (name) {
            case "Linear":      return t;
            case "Ease In":     return t * t;
            case "Ease In Out": return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2.0;
            case "Bounce": {
                double n1 = 7.5625, d1 = 2.75;
                double x = t;
                if (x < 1 / d1)      return n1 * x * x;
                else if (x < 2 / d1) return n1 * (x -= 1.5 / d1) * x + 0.75;
                else if (x < 2.5 / d1) return n1 * (x -= 2.25 / d1) * x + 0.9375;
                else                  return n1 * (x -= 2.625 / d1) * x + 0.984375;
            }
            case "Ease Out":
            default:            return 1 - (1 - t) * (1 - t);
        }
    }

    /** Lighten a base color toward white by `pctOf255` (0..255). */
    private static Color brighten(Color c, int target) {
        if (c == null) return Color.WHITE;
        int r = (c.getRed()   + target) / 2;
        int gn = (c.getGreen() + target) / 2;
        int b = (c.getBlue()  + target) / 2;
        return new Color(clamp255(r), clamp255(gn), clamp255(b));
    }
    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

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
        if ("Bomb Fuse".equals(s)) {
            // Bomb Fuse anchors at the LEFT of the fuse path; visual mass is
            // toward the bomb on the right, so the center for animation is
            // halfway along the fuse length.
            double fuseLen = Math.max(50, w * quiz.timerWidthPct / 100.0);
            double fuseH   = Math.max(20, h * quiz.timerSizePct / 100.0);
            return new double[] { x + fuseLen / 2.0, y + fuseH / 2.0 };
        }
        if ("Dot Grid".equals(s)) {
            // Dot Grid uses (x,y) as the top-left of the row of dots.
            double rowW = Math.max(50, w * quiz.timerWidthPct / 100.0);
            double dotR = Math.max(8, h * quiz.timerSizePct / 200.0);
            return new double[] { x + rowW / 2.0, y + dotR };
        }
        // Hourglass / Flip Clock: (x,y) is treated as the visual center.
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
        String style = quiz.timerStyle != null ? quiz.timerStyle : "";
        if (horizontalBar || "Bomb Fuse".equals(style) || "Dot Grid".equals(style)) {
            // Bar/fuse/dot row anchor (cx,cy) is the top-left; label above.
            tx = cx;
            ty = cy - 6;
        } else if ("Progress Bar V".equals(style)) {
            tx = cx;
            ty = cy - 6;
        } else if ("Flip Clock".equals(style)) {
            // Flip Clock cards are wider than tall — label hugs the top edge.
            int cardH = Math.max(40, (int) (h * quiz.timerSizePct / 100.0));
            tx = cx - tw / 2;
            ty = cy - cardH / 2 - 6;
        } else if ("Hourglass".equals(style)) {
            // Hourglass is taller than wide — label sits above the top cap.
            int glassH = Math.max(60, (int) (h * quiz.timerSizePct / 100.0));
            tx = cx - tw / 2;
            ty = cy - glassH / 2 - 6;
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

            // Filled portion (depleting). barReverse mirrors the fill side
            // so the horizontal bar drains from LEFT (instead of right) and
            // the vertical bar drains from BOTTOM (instead of top).
            int filled;
            if (horizontal) {
                filled = (int) (barW * (1.0 - progress));
                g.setColor(accent);
                if (quiz.barReverse) {
                    g.fillRoundRect(x + (barW - Math.max(0, filled)), y,
                            Math.max(0, filled), barH, arc, arc);
                } else {
                    g.fillRoundRect(x, y, Math.max(0, filled), barH, arc, arc);
                }
            } else {
                filled = (int) (barH * (1.0 - progress));
                g.setColor(accent);
                if (quiz.barReverse) {
                    g.fillRoundRect(x, y, barW, Math.max(0, filled), arc, arc);
                } else {
                    g.fillRoundRect(x, y + (barH - filled), barW,
                            Math.max(0, filled), arc, arc);
                }
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

        boolean reverse = quiz.barReverse;
        if (horizontal) {
            int segW = Math.max(2, (barW - gap * (totalSegs - 1)) / totalSegs);
            for (int i = 0; i < totalSegs; i++) {
                int sx = x + i * (segW + gap);
                // Default: deplete from the LEFT (lit blocks on the right).
                // Reverse: deplete from the RIGHT (lit blocks on the left).
                boolean lit = reverse ? (i < filledSegs)
                        : (i >= (totalSegs - filledSegs));
                g.setColor(lit ? accent : bg);
                g.fillRoundRect(sx, y, segW, barH, segArc, segArc);
                g.setColor(new Color(255, 255, 255, 60));
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(sx, y, segW, barH, segArc, segArc);
            }
        } else {
            int segH = Math.max(2, (barH - gap * (totalSegs - 1)) / totalSegs);
            for (int i = 0; i < totalSegs; i++) {
                int sy = y + i * (segH + gap);
                // Default: deplete from the TOP (lit blocks at the bottom).
                // Reverse: deplete from the BOTTOM (lit blocks at the top).
                boolean lit = reverse ? (i < filledSegs)
                        : (i >= (totalSegs - filledSegs));
                g.setColor(lit ? accent : bg);
                g.fillRoundRect(x, sy, barW, segH, segArc, segArc);
                g.setColor(new Color(255, 255, 255, 60));
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(x, sy, barW, segH, segArc, segArc);
            }
        }
    }

    /**
     * Hourglass — professional game-grade look. Stack from bottom to top:
     * (1) outer drop shadow, (2) glass bulbs with vertical translucent
     * gradient + a diagonal highlight streak, (3) sand inside the top bulb
     * with a sand-color gradient and a slightly wavy top surface, (4) a CONE
     * pile in the bottom bulb that rises with time (instead of a flat slab),
     * (5) a wobbling falling stream + free-flying particles + a tiny impact
     * splash where the stream meets the pile, (6) glass outline with light
     * top edge + dark inner shadow at the neck, (7) brass top and bottom
     * caps with bevel highlights, (8) two wooden side posts joining caps.
     * Caps turn coppery-red and sand uses the urgent accent in the red phase.
     */
    private static void drawHourglass(Graphics2D g, int w, int h, QuizSlide quiz,
                                      int remainingSec, Color accent, Color bg,
                                      Color textCol, boolean red, double progress) {
        int glassH = Math.max(60, (int) (h * quiz.timerSizePct / 100.0));
        int glassW = Math.max(40, (int) (glassH * 0.62));
        int cx = (int) (w * quiz.timerXPct / 100.0);
        int cy = (int) (h * quiz.timerYPct / 100.0);
        double prog = Math.max(0.0, Math.min(1.0, progress));

        // --- Frame geometry ---
        int top      = cy - glassH / 2;
        int bottom   = cy + glassH / 2;
        int neckHalf = Math.max(3, glassW / 18);
        int capH     = Math.max(5, glassH / 18);
        int capOver  = Math.max(4, glassW / 10);
        int frameW   = glassW + capOver * 2;
        int postW    = Math.max(3, glassW / 26);
        int postPad  = postW / 2 + 1;
        int capTopY  = top - capH;
        int capBotY  = bottom;

        Stroke s0 = g.getStroke();
        Paint  p0 = g.getPaint();
        Composite oc0 = g.getComposite();

        // --- 1. Drop shadow under the whole assembly ---
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
        g.setColor(Color.BLACK);
        int shOff = Math.max(3, glassH / 36);
        g.fillRoundRect(cx - frameW / 2 + shOff, capTopY + shOff,
                frameW, capBotY - capTopY + capH, capH, capH);
        g.setComposite(oc0);

        // --- 2. Glass bulbs (triangles) with translucent gradient ---
        Polygon topTri = new Polygon(
                new int[] { cx - glassW / 2, cx + glassW / 2, cx + neckHalf, cx - neckHalf },
                new int[] { top,             top,             cy,            cy            }, 4);
        Polygon botTri = new Polygon(
                new int[] { cx - neckHalf, cx + neckHalf, cx + glassW / 2, cx - glassW / 2 },
                new int[] { cy,            cy,            bottom,          bottom          }, 4);

        Paint glassPaint = new GradientPaint(
                cx, top,    new Color(240, 245, 250, 95),
                cx, bottom, new Color(180, 195, 210, 60));
        g.setPaint(glassPaint);
        g.fillPolygon(topTri);
        g.fillPolygon(botTri);

        // Diagonal highlight streak on the LEFT face of each bulb.
        Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g.setColor(new Color(255, 255, 255, 90));
        Polygon shineTop = new Polygon(
                new int[] { cx - glassW / 2 + glassW / 6, cx - glassW / 2 + glassW / 3,
                        cx - neckHalf / 2 - glassW / 18, cx - neckHalf },
                new int[] { top + glassH / 22, top + glassH / 22,
                        cy - glassH / 22, cy - glassH / 22 }, 4);
        g.fillPolygon(shineTop);
        Polygon shineBot = new Polygon(
                new int[] { cx - neckHalf, cx - neckHalf / 2 - glassW / 18,
                        cx - glassW / 2 + glassW / 3, cx - glassW / 2 + glassW / 6 },
                new int[] { cy + glassH / 22, cy + glassH / 22,
                        bottom - glassH / 22, bottom - glassH / 22 }, 4);
        g.fillPolygon(shineBot);
        g.setComposite(oc);

        // --- 3. Sand inside the TOP bulb (gradient + wavy top surface) ---
        Color sandTop = brighten(accent, 245);
        Color sandBot = darken(accent, 55);
        double sandTopY = top + (cy - top) * prog;
        double tFrac = (cy - top) > 0 ? (sandTopY - top) / (double) (cy - top) : 1.0;
        int topWHere = (int) Math.round(glassW + (2 * neckHalf - glassW) * tFrac);
        Polygon topSand = new Polygon(
                new int[] { cx - topWHere / 2, cx + topWHere / 2, cx + neckHalf, cx - neckHalf },
                new int[] { (int) sandTopY,    (int) sandTopY,    cy,            cy             }, 4);
        g.setPaint(new GradientPaint(cx, (float) sandTopY, sandTop,
                cx, cy,               sandBot));
        g.fillPolygon(topSand);

        if (topWHere > 8 && prog < 0.99) {
            g.setColor(darken(accent, 80));
            g.setStroke(new BasicStroke(Math.max(1.2f, glassW / 50f),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            java.awt.geom.Path2D.Double wave = new java.awt.geom.Path2D.Double();
            int segs = Math.max(4, topWHere / 8);
            double waveAmp = Math.max(0.8, glassH / 90.0);
            for (int i = 0; i <= segs; i++) {
                double t = i / (double) segs;
                double xx = cx - topWHere / 2.0 + topWHere * t;
                double yy = sandTopY + Math.sin(t * Math.PI * 3 + prog * 6) * waveAmp;
                if (i == 0) wave.moveTo(xx, yy); else wave.lineTo(xx, yy);
            }
            g.draw(wave);
        }

        // --- 4. Cone-shaped pile of fallen sand in the BOTTOM bulb ---
        int bulbH = bottom - cy;
        int pileH = (int) (bulbH * prog);
        if (pileH > 0) {
            int pileBaseY = bottom;
            int pileApexY = bottom - pileH;
            Polygon pile = new Polygon(
                    new int[] { cx - glassW / 2, cx + glassW / 2, cx },
                    new int[] { pileBaseY,       pileBaseY,       pileApexY }, 3);
            java.awt.Shape oldClip = g.getClip();
            g.setClip(botTri);
            g.setPaint(new GradientPaint(cx, pileApexY, sandTop,
                    cx, pileBaseY, sandBot));
            g.fillPolygon(pile);
            g.setColor(brighten(accent, 255));
            g.setStroke(new BasicStroke(Math.max(1.0f, glassW / 60f),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(cx, pileApexY + 1, cx, pileBaseY - 1);
            g.setClip(oldClip);
        }

        // --- 5. Wobbling falling stream + a few particles + impact splash ---
        if (prog > 0.01 && prog < 0.99) {
            int streamTopY = cy + 1;
            int streamBotY = bottom - pileH - 1;
            if (streamBotY > streamTopY + 2) {
                double phase = prog * Math.PI * 12;
                int segs = Math.max(6, (streamBotY - streamTopY) / 4);
                java.awt.geom.Path2D.Double stream = new java.awt.geom.Path2D.Double();
                float streamW = Math.max(1.6f, glassW / 32f);
                g.setStroke(new BasicStroke(streamW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(sandTop);
                for (int i = 0; i <= segs; i++) {
                    double t = i / (double) segs;
                    double sy = streamTopY + (streamBotY - streamTopY) * t;
                    double sx = cx + Math.sin(t * Math.PI * 4 + phase) * (glassW / 60.0);
                    if (i == 0) stream.moveTo(sx, sy); else stream.lineTo(sx, sy);
                }
                g.draw(stream);

                int dotR = Math.max(1, (int) (glassW / 36f));
                for (int k = 0; k < 4; k++) {
                    double t = ((phase / 6.0) + k * 0.25) % 1.0;
                    double sy = streamTopY + (streamBotY - streamTopY) * t;
                    double sx = cx + Math.sin(t * Math.PI * 4 + phase) * (glassW / 40.0)
                            + ((k % 2 == 0) ? -1 : 1) * (glassW / 30.0);
                    g.fillOval((int) sx - dotR, (int) sy - dotR, dotR * 2, dotR * 2);
                }

                g.setColor(brighten(accent, 245));
                g.fillOval(cx - dotR * 2, streamBotY - dotR, dotR * 4, dotR * 2);
            }
        }

        // --- 6. Glass outline + dark neck shadow ---
        g.setStroke(new BasicStroke(Math.max(1.8f, glassW / 30f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 150));
        g.drawPolygon(topTri);
        g.setColor(new Color(255, 255, 255, 110));
        g.drawPolygon(botTri);
        g.setColor(new Color(0, 0, 0, 60));
        g.setStroke(new BasicStroke(Math.max(1.0f, glassW / 60f)));
        g.drawLine(cx - neckHalf, cy, cx + neckHalf, cy);

        // --- 7. Brass caps (top + bottom) with bevel ---
        Color brass1 = red ? new Color(190, 100, 70) : new Color(210, 175, 95);
        Color brass2 = red ? new Color(110, 50, 40)  : new Color(135, 100, 45);
        drawHourglassCap(g, cx - frameW / 2, capTopY, frameW, capH, brass1, brass2);
        drawHourglassCap(g, cx - frameW / 2, capBotY, frameW, capH, brass1, brass2);

        // --- 8. Wooden side posts joining the two caps ---
        Color woodLight = new Color(120, 80, 50);
        Color woodDark  = new Color(70, 45, 25);
        int postLeftX  = cx - frameW / 2 + postPad;
        int postRightX = cx + frameW / 2 - postPad - postW;
        int postYTop   = capTopY + capH - 1;
        int postYBot   = capBotY + 1;
        g.setPaint(new GradientPaint(0, postYTop, woodLight, 0, postYBot, woodDark));
        g.fillRoundRect(postLeftX,  postYTop, postW, postYBot - postYTop, postW, postW);
        g.fillRoundRect(postRightX, postYTop, postW, postYBot - postYTop, postW, postW);
        g.setColor(new Color(255, 255, 255, 60));
        g.setStroke(new BasicStroke(Math.max(0.8f, postW / 3f)));
        g.drawLine(postLeftX + 1,  postYTop + 2, postLeftX + 1,  postYBot - 2);
        g.drawLine(postRightX + 1, postYTop + 2, postRightX + 1, postYBot - 2);

        g.setComposite(oc0);
        g.setPaint(p0);
        g.setStroke(s0);

        // Digit hangs to the RIGHT of the entire frame.
        int digitRef = (int) (glassW * 1.2);
        drawDigit(g, cx + frameW / 2 + digitRef / 2 + 10, cy, digitRef,
                remainingSec, textCol, red, quiz);
    }

    /** Beveled brass cap used by drawHourglass for the top and bottom plates. */
    private static void drawHourglassCap(Graphics2D g, int x, int y, int w, int h,
                                         Color light, Color dark) {
        int r = Math.max(2, h / 2);
        Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        g.setColor(Color.BLACK);
        g.fillRoundRect(x + 2, y + 2, w, h, r, r);
        g.setComposite(oc);

        g.setPaint(new GradientPaint(x, y, light, x, y + h, dark));
        g.fillRoundRect(x, y, w, h, r, r);
        g.setColor(new Color(255, 255, 255, 140));
        g.setStroke(new BasicStroke(Math.max(1.0f, h / 6f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x + r, y + 2, x + w - r, y + 2);
        g.setColor(new Color(0, 0, 0, 120));
        g.drawLine(x + r, y + h - 2, x + w - r, y + h - 2);
        g.setColor(new Color(0, 0, 0, 160));
        g.setStroke(new BasicStroke(Math.max(1.0f, h / 14f)));
        g.drawRoundRect(x, y, w, h, r, r);
    }

    /** Darken a color toward black by `target` (0..255). */
    private static Color darken(Color c, int target) {
        if (c == null) return Color.BLACK;
        int r = (c.getRed()   + target) / 2;
        int gn = (c.getGreen() + target) / 2;
        int b = (c.getBlue()  + target) / 2;
        return new Color(clamp255(r), clamp255(gn), clamp255(b));
    }

    /**
     * Flip Clock — a mechanical split-flap card showing the remaining seconds.
     * For multi-digit numbers we stack two cards side-by-side. A thin black
     * split line runs across the middle of every card to sell the "flap".
     * When a second changes, the top half of the new card slides down from
     * above to give a subtle flip animation cue.
     */
    private static void drawFlipClock(Graphics2D g, int w, int h, QuizSlide quiz,
                                      int remainingSec, Color accent, Color bg,
                                      Color textCol, boolean red, double progress) {
        int cardH = Math.max(40, (int) (h * quiz.timerSizePct / 100.0));
        int cardW = (int) (cardH * 0.72);
        int cx = (int) (w * quiz.timerXPct / 100.0);
        int cy = (int) (h * quiz.timerYPct / 100.0);

        String txt = remainingSec <= 0 ? "0" : String.valueOf(remainingSec);
        int nDigits = txt.length();
        int gap = Math.max(4, cardW / 10);
        int totalW = nDigits * cardW + (nDigits - 1) * gap;
        int startX = cx - totalW / 2;

        Color card = new Color(25, 28, 38, 235);
        Color split = new Color(0, 0, 0, 200);

        // Flip phase — how far the "new" top half has descended into place
        // (0 = just dropped, 1 = fully seated). Drives a subtle scale-Y wiggle.
        double secFrac = (progress * Math.max(1, quiz.timerSeconds)) % 1.0;
        if (secFrac < 0) secFrac += 1.0;
        double flipEased = ease(quiz.timerAnimEasing != null ? quiz.timerAnimEasing : "Ease Out",
                Math.min(1.0, secFrac * 4.0));  // first quarter of the second carries the flip

        Stroke s0 = g.getStroke();
        for (int i = 0; i < nDigits; i++) {
            int x = startX + i * (cardW + gap);
            int y = cy - cardH / 2;

            // Drop shadow.
            Composite oc = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            g.setColor(Color.BLACK);
            g.fillRoundRect(x + 4, y + 4, cardW, cardH, cardH / 5, cardH / 5);
            g.setComposite(oc);

            // Card face.
            g.setColor(card);
            g.fillRoundRect(x, y, cardW, cardH, cardH / 5, cardH / 5);
            // Subtle top-half tint (lighter).
            g.setColor(new Color(255, 255, 255, 20));
            g.fillRoundRect(x, y, cardW, cardH / 2, cardH / 5, cardH / 5);
            // Bottom-half darker.
            g.setColor(new Color(0, 0, 0, 50));
            g.fillRoundRect(x, y + cardH / 2, cardW, cardH / 2, cardH / 5, cardH / 5);

            // Split line.
            g.setColor(split);
            g.setStroke(new BasicStroke(Math.max(2f, cardH / 32f)));
            g.drawLine(x + 2, y + cardH / 2, x + cardW - 2, y + cardH / 2);

            // Card border (accent thin frame).
            g.setStroke(new BasicStroke(Math.max(2f, cardH / 40f)));
            g.setColor(accent);
            g.drawRoundRect(x, y, cardW, cardH, cardH / 5, cardH / 5);

            // Digit centered on the card; scaleY for the flip wiggle.
            String ch = txt.substring(i, i + 1);
            int digitRef = (int) (cardH * 1.05);
            java.awt.geom.AffineTransform saved = g.getTransform();
            double sy = 0.85 + 0.15 * flipEased;
            g.translate(x + cardW / 2.0, y + cardH / 2.0);
            g.scale(1.0, sy);
            g.translate(-(x + cardW / 2.0), -(y + cardH / 2.0));
            drawDigitText(g, x + cardW / 2, y + cardH / 2, digitRef, ch,
                    textCol, red, quiz);
            g.setTransform(saved);
        }
        g.setStroke(s0);
    }

    /**
     * Bomb Fuse — a horizontal sparking fuse that retracts from left to right
     * (or right to left when `barReverse` is true) ending at a cartoon bomb.
     * `timerColor` is the spark/flame; the unburnt fuse cord is a fixed
     * hemp-rope tan. The bomb body is the standard dark bg accent.
     */
    private static void drawBombFuse(Graphics2D g, int w, int h, QuizSlide quiz,
                                     int remainingSec, Color accent, Color bg,
                                     Color textCol, boolean red, double progress) {
        int xLeft = (int) (w * quiz.timerXPct / 100.0);
        int y     = (int) (h * quiz.timerYPct / 100.0);
        int fuseLen = Math.max(50, (int) (w * quiz.timerWidthPct / 100.0));
        int fuseH   = Math.max(20, (int) (h * quiz.timerSizePct / 100.0));
        int bombR   = (int) (fuseH * 1.6);

        Color cord = new Color(180, 140, 80);  // hemp rope tan
        boolean rtl = quiz.barReverse;  // reuse existing tick-direction toggle

        // Anchor: bomb sits on whichever end the fuse "ends" at.
        int bombCx = rtl ? (xLeft + bombR / 2) : (xLeft + fuseLen - bombR / 2);
        int fuseStartX = rtl ? xLeft + bombR  : xLeft;
        int fuseEndX   = rtl ? xLeft + fuseLen : xLeft + fuseLen - bombR;
        int fuseY      = y + fuseH / 2;

        Stroke s0 = g.getStroke();

        // Drop-shadow under the bomb.
        Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g.setColor(Color.BLACK);
        g.fillOval(bombCx - bombR / 2 + 5, y + fuseH / 2 - bombR / 2 + 5, bombR, bombR);
        g.setComposite(oc);

        // Bomb body.
        g.setColor(new Color(30, 30, 35));
        g.fillOval(bombCx - bombR / 2, y + fuseH / 2 - bombR / 2, bombR, bombR);
        g.setColor(new Color(255, 255, 255, 60));
        g.setStroke(new BasicStroke(Math.max(2f, bombR / 28f)));
        g.drawOval(bombCx - bombR / 2, y + fuseH / 2 - bombR / 2, bombR, bombR);
        // Highlight glint on the bomb.
        g.setColor(new Color(255, 255, 255, 110));
        g.fillOval(bombCx - bombR / 4, y + fuseH / 2 - bombR / 4 - bombR / 12,
                bombR / 6, bombR / 6);

        // Fuse — wavy line from bomb out to the burn tip.
        int totalFuse = Math.abs(fuseEndX - fuseStartX);
        int burnedLen = (int) (totalFuse * progress);
        int burnedTip = rtl ? (fuseEndX - (totalFuse - burnedLen))
                : (fuseStartX + (totalFuse - burnedLen));

        float cordThick = Math.max(3f, fuseH / 4f);
        g.setStroke(new BasicStroke(cordThick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // Unburnt portion (cord) — from bomb out to the spark.
        java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
        int a = rtl ? fuseEndX : fuseStartX;       // bomb-side end of unburnt cord
        // wait — unburnt cord runs between bomb attachment and the burn tip.
        a = rtl ? fuseEndX : fuseStartX;
        // Actually: unburnt fuse spans from BOMB-attachment to burned-tip.
        int bombAttachX = rtl ? (bombCx + bombR / 2) : (bombCx - bombR / 2);
        int u0 = Math.min(bombAttachX, burnedTip);
        int u1 = Math.max(bombAttachX, burnedTip);
        path.moveTo(u0, fuseY);
        int wiggles = Math.max(3, (u1 - u0) / 22);
        for (int i = 1; i <= wiggles; i++) {
            double t = i / (double) wiggles;
            double px = u0 + (u1 - u0) * t;
            double py = fuseY + Math.sin(t * Math.PI * 4) * (fuseH * 0.18);
            path.lineTo(px, py);
        }
        g.setColor(cord);
        g.draw(path);

        // Spark / flame at the burning tip — pulses with the same per-second
        // progress so it looks alive even with no "red" phase. Uses `accent`
        // (which is already `urgent` during red phase, user color otherwise).
        double secFrac = (progress * Math.max(1, quiz.timerSeconds)) % 1.0;
        if (secFrac < 0) secFrac += 1.0;
        double pulse = 1.0 + 0.25 * Math.sin(secFrac * Math.PI * 2);
        int sparkR = (int) (fuseH * 0.9 * pulse);
        // Outer halo.
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90));
        g.fillOval(burnedTip - sparkR, fuseY - sparkR, sparkR * 2, sparkR * 2);
        // Inner core (white-ish hot).
        Color hot = brighten(accent, 240);
        g.setColor(hot);
        g.fillOval(burnedTip - sparkR / 2, fuseY - sparkR / 2, sparkR, sparkR);
        // A few short sparks shooting outward.
        g.setStroke(new BasicStroke(Math.max(1.5f, fuseH / 10f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(accent);
        for (int k = 0; k < 5; k++) {
            double ang = secFrac * Math.PI * 2 + k * (Math.PI * 2 / 5);
            double r1 = sparkR;
            double r2 = sparkR + fuseH * 0.6;
            g.drawLine(
                    (int) (burnedTip + Math.cos(ang) * r1),
                    (int) (fuseY    + Math.sin(ang) * r1),
                    (int) (burnedTip + Math.cos(ang) * r2),
                    (int) (fuseY    + Math.sin(ang) * r2));
        }

        g.setStroke(s0);

        // Digit centered ON the bomb body so the timer is unmissable.
        drawDigit(g, bombCx, y + fuseH / 2, bombR, remainingSec, textCol, red, quiz);
    }

    /**
     * Dot Grid — a row of N circles, one per second of the timer. Lit dots
     * use `accent`; spent dots use a dim variant of the accent. Dots tick off
     * cleanly at second boundaries — no per-second fade so all `remainingSec`
     * dots match colors exactly at any moment.
     */
    private static void drawDotGrid(Graphics2D g, int w, int h, QuizSlide quiz,
                                    int remainingSec, Color accent, Color bg,
                                    Color textCol, boolean red, double progress) {
        int xLeft = (int) (w * quiz.timerXPct / 100.0);
        int y     = (int) (h * quiz.timerYPct / 100.0);
        int rowW  = Math.max(50, (int) (w * quiz.timerWidthPct / 100.0));
        int dotD  = Math.max(8, (int) (h * quiz.timerSizePct / 100.0));

        int total = Math.max(1, quiz.timerSeconds);
        // Auto-shrink dot diameter so all of them fit even when the user sets
        // a wide-but-thick combo. Reserve at least 4 px gap between dots.
        int maxDotByWidth = Math.max(6, (rowW - (total - 1) * 4) / total);
        if (dotD > maxDotByWidth) dotD = maxDotByWidth;
        int gap = (rowW - dotD * total) / Math.max(1, total - 1);
        if (gap < 2) gap = 2;

        Color unlit = new Color(accent.getRed() / 4, accent.getGreen() / 4, accent.getBlue() / 4, 180);
        int spentCount = Math.max(0, Math.min(total, total - remainingSec));

        Stroke s0 = g.getStroke();
        for (int i = 0; i < total; i++) {
            int dx = xLeft + i * (dotD + gap);
            // Default: deplete from LEFT — lit dots cluster on the RIGHT.
            // Reverse: deplete from RIGHT — lit dots cluster on the LEFT.
            boolean lit = quiz.barReverse ? (i < remainingSec) : (i >= spentCount);

            // Drop shadow.
            Composite oc = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
            g.setColor(Color.BLACK);
            g.fillOval(dx + 3, y + 3, dotD, dotD);
            g.setComposite(oc);

            g.setColor(lit ? accent : unlit);
            g.fillOval(dx, y, dotD, dotD);

            // Outline.
            g.setColor(new Color(255, 255, 255, 80));
            g.setStroke(new BasicStroke(Math.max(1.5f, dotD / 18f)));
            g.drawOval(dx, y, dotD, dotD);

            // Highlight on lit dots only — a tiny gloss spot.
            if (lit) {
                g.setColor(new Color(255, 255, 255, 110));
                g.fillOval(dx + dotD / 5, y + dotD / 6, dotD / 4, dotD / 4);
            }
        }
        g.setStroke(s0);

        // Optional center digit — only if a label slot isn't already showing
        // a number. Place to the RIGHT of the last dot so it doesn't crowd
        // the grid.
        int digitRef = dotD * 2;
        int digitX = xLeft + rowW + digitRef / 2 + 8;
        drawDigit(g, digitX, y + dotD / 2, digitRef, remainingSec, textCol, red, quiz);
    }

    /**
     * Variant of `drawDigit` that draws an explicit text string rather than
     * the seconds-remaining integer. Used by Flip Clock to render each card's
     * digit independently.
     */
    private static void drawDigitText(Graphics2D g, int cx, int cy, int sizeRef,
                                      String txt, Color textCol, boolean red,
                                      QuizSlide quiz) {
        if (quiz != null && !quiz.digitShow) return;
        String family = (quiz != null && quiz.timerFont != null && !quiz.timerFont.isEmpty())
                ? quiz.timerFont : "Segoe UI";
        int sizePct  = (quiz != null) ? quiz.digitSizePct    : 100;
        int xOffPct  = (quiz != null) ? quiz.digitXOffsetPct : 0;
        int yOffPct  = (quiz != null) ? quiz.digitYOffsetPct : 0;
        boolean bold = (quiz == null) || quiz.digitBold;
        boolean shadow = (quiz == null) || quiz.digitShadow;
        if (sizePct <= 0) sizePct = 100;
        sizePct = Math.max(50, Math.min(200, sizePct));
        xOffPct = Math.max(-300, Math.min(300, xOffPct));
        yOffPct = Math.max(-300, Math.min(300, yOffPct));

        int fontPx = Math.max(12, (int) (sizeRef * 0.55 * sizePct / 100.0));
        Font font = new Font(family, bold ? Font.BOLD : Font.PLAIN, fontPx);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(txt);
        int adjCx = cx + (int) Math.round(sizeRef * xOffPct / 100.0);
        int adjCy = cy + (int) Math.round(sizeRef * yOffPct / 100.0);
        int tx = adjCx - tw / 2;
        int ty = adjCy + fm.getAscent() / 2 - fm.getDescent() / 2;

        if (shadow && !red) {
            int sOff = Math.max(2, fontPx / 22);
            Composite oc = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g.setColor(Color.BLACK);
            g.drawString(txt, tx + sOff, ty + sOff);
            g.setComposite(oc);
        }
        g.setColor(textCol);
        g.drawString(txt, tx, ty);
    }

    private static void drawDigit(Graphics2D g, int cx, int cy, int sizeRef,
                                  int remainingSec, Color textCol, boolean red,
                                  QuizSlide quiz) {
        if (quiz != null && !quiz.digitShow) return;
        String txt = remainingSec <= 0 ? "0" : String.valueOf(remainingSec);
        String family = (quiz != null && quiz.timerFont != null && !quiz.timerFont.isEmpty())
                ? quiz.timerFont : "Segoe UI";

        // Honor the Look toolbar's digit controls. Clamp defensively so manual
        // edits to old presets don't blow up the font size.
        int sizePct  = (quiz != null) ? quiz.digitSizePct    : 100;
        int xOffPct  = (quiz != null) ? quiz.digitXOffsetPct : 0;
        int yOffPct  = (quiz != null) ? quiz.digitYOffsetPct : 0;
        boolean bold = (quiz == null) || quiz.digitBold;
        boolean shadow = (quiz == null) || quiz.digitShadow;
        if (sizePct <= 0) sizePct = 100;
        sizePct = Math.max(50, Math.min(200, sizePct));
        xOffPct = Math.max(-300, Math.min(300, xOffPct));
        yOffPct = Math.max(-300, Math.min(300, yOffPct));

        int fontPx = Math.max(12, (int) (sizeRef * 0.55 * sizePct / 100.0));
        Font font = new Font(family, bold ? Font.BOLD : Font.PLAIN, fontPx);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(txt);

        int adjCx = cx + (int) Math.round(sizeRef * xOffPct / 100.0);
        int adjCy = cy + (int) Math.round(sizeRef * yOffPct / 100.0);
        int tx = adjCx - tw / 2;
        int ty = adjCy + fm.getAscent() / 2 - fm.getDescent() / 2;

        // Soft drop shadow first (skipped in red urgency phase — the red glow
        // already provides plenty of contrast and the shadow would muddy it).
        if (shadow && !red) {
            int sOff = Math.max(2, fontPx / 22);
            Composite oc = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g.setColor(new Color(0, 0, 0));
            g.drawString(txt, tx + sOff, ty + sOff);
            g.setComposite(oc);
        }

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
            int shiftXPct    = readIntFieldOr(std, "shiftX", 0);
            boolean xLeftAlg = readBoolFieldOr(std, "xLeftAligned", false);
            int fontStyle    = readIntFieldOr(std, "fontStyle", Font.PLAIN);
            int alignment    = readIntFieldOr(std, "alignment", SwingConstants.CENTER);
            int widthPct     = readIntFieldOr(std, "widthPct", 0);
            String fontName  = (String) readFieldOr(std, "fontName", "Segoe UI");
            String text  = (String) readField(std, "text");
            if (text == null || text.isEmpty()) text = "Option " + optionIdx1Based;

            // The slide-text coordinates are percentages of frame dimensions.
            // Mirror GifSlideShowApp's text-render positioning: x/y locate the
            // text-block CENTER, then shiftX adds a horizontal offset (% of
            // width). xLeftAligned re-anchors x to the text's left/right edge.
            int px = (int) (w * xPct / 100.0);
            int py = (int) (h * yPct / 100.0);
            px += (int) (w * shiftXPct / 100.0);
            float scale = Math.max(w, h) / 1920.0f;
            int fontPx = Math.max(18, (int) (fSizeRef * scale));

            // Measure the real text width with FontMetrics so the highlight
            // hugs the glyphs instead of relying on a character-count guess
            // (which drifts on short/long options and wide-glyph fonts).
            // Prefer the renderer's own loaded-font cache (custom TTFs like
            // DancingScript/Montserrat aren't visible to `new Font(name,...)`
            // — it silently falls back to Dialog, which under-measures).
            Font measureFont = lookupLoadedFont(fontName, fontStyle, fontPx);
            if (measureFont == null) {
                try {
                    measureFont = new Font(fontName, fontStyle, fontPx);
                } catch (Exception ignore) {
                    measureFont = g.getFont().deriveFont((float) fontPx);
                }
            }
            FontMetrics fm = g.getFontMetrics(measureFont);
            String[] lines = text.split("\n");
            int widestLineW = 0;
            for (String ln : lines) {
                widestLineW = Math.max(widestLineW, fm.stringWidth(ln));
            }
            int firstLineW = widestLineW;
            int tw = Math.max(widestLineW, fontPx / 2) + fontPx / 3;
            int th = (int) (fontPx * 1.15 * Math.max(1, lines.length));

            if (xLeftAlg) {
                boolean isRTL = false;
                for (int ci = 0; ci < text.length(); ci++) {
                    int dir = Character.getDirectionality(text.charAt(ci));
                    if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                            || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                        isRTL = true;
                        break;
                    }
                }
                if (isRTL) {
                    px = w - (int) (w * xPct / 100.0) - firstLineW / 2
                            + (int) (w * shiftXPct / 100.0);
                } else {
                    px += firstLineW / 2;
                }
            } else {
                // LEFT/RIGHT alignment positions the line inside a wrap-width
                // box centered on (x,y). When that box is wider than the line
                // (the common case for option text), the rendered glyphs sit
                // off-center, so the reveal has to slide the same way.
                int alignWidth = (widthPct > 0 && widthPct < 100)
                        ? (int) (w * widthPct / 100.0)
                        : w;
                if (alignment == SwingConstants.LEFT) {
                    px += (firstLineW - alignWidth) / 2;
                } else if (alignment == SwingConstants.RIGHT) {
                    px += (alignWidth - firstLineW) / 2;
                }
            }

            int bx = px - tw / 2;
            int by = py - th / 2;

            // Padding scales by both the pulse-in animation AND the user setting.
            double padScale = Math.max(0.0, Math.min(2.0, quiz.revealPadPct / 100.0));
            float pulse = (float) Math.max(0.0,
                    Math.min(1.0, 1.0 - sinceRevealMs / 800.0));
            int padBase  = (int) ((4 + 8 * pulse) * padScale);
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

    private static int readIntFieldOr(Object o, String name, int dflt) {
        try { return readIntField(o, name); } catch (Exception e) { return dflt; }
    }

    private static boolean readBoolFieldOr(Object o, String name, boolean dflt) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(o);
        } catch (Exception e) { return dflt; }
    }

    private static Object readFieldOr(Object o, String name, Object dflt) {
        try {
            Object v = readField(o, name);
            return v == null ? dflt : v;
        } catch (Exception e) { return dflt; }
    }

    // Reflectively read GifSlideShowApp.loadedFonts so the reveal can measure
    // with the same custom TTFs the renderer uses. Returns null when the map
    // or the requested name isn't present (caller falls back to new Font()).
    private static Font lookupLoadedFont(String name, int style, float sizePx) {
        if (name == null) return null;
        try {
            Class<?> cls = Class.forName("GifSlideShowApp");
            java.lang.reflect.Field f = cls.getDeclaredField("loadedFonts");
            f.setAccessible(true);
            Object m = f.get(null);
            if (!(m instanceof Map)) return null;
            Object base = ((Map<?, ?>) m).get(name);
            if (base instanceof Font) {
                return ((Font) base).deriveFont(style, sizePx);
            }
        } catch (Throwable ignore) { /* fall through */ }
        return null;
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
     * @param textItemTexts   the actual text strings for those items (in order);
     *                        used by the read-only special-timeline viewer to
     *                        show the target text instead of "Text #N". May be
     *                        null or shorter than {@code textItemCount}.
     * @param onAttach        called with (combinedAudioFile, durationMs);
     *                        caller should call setSlideAudio(0, file, ms)
     */
    public static void openConfigDialog(Window parent, QuizSlide quiz,
                                        int textItemCount,
                                        List<String> textItemTexts,
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
        JRadioButton afterCueRadio = new JRadioButton(
                "After this audio cue finishes:",
                "AfterCue".equals(quiz.timerStartMode));
        // Dropdown of possible cue targets: Text #1..#N plus the special audio.
        String[] cueOptions = new String[textItemCount + 1];
        for (int i = 0; i < textItemCount; i++) cueOptions[i] = "Text #" + (i + 1);
        cueOptions[textItemCount] = "Special (no text)";
        JComboBox<String> afterCueCombo = new JComboBox<>(cueOptions);
        // Map current timerStartCueIndex (0=special, 1..N=text) to combo index.
        if (quiz.timerStartCueIndex == 0) {
            afterCueCombo.setSelectedIndex(textItemCount);
        } else if (quiz.timerStartCueIndex >= 1
                && quiz.timerStartCueIndex <= textItemCount) {
            afterCueCombo.setSelectedIndex(quiz.timerStartCueIndex - 1);
        }
        afterCueCombo.setEnabled("AfterCue".equals(quiz.timerStartMode));
        afterCueRadio.addActionListener(e ->
                afterCueCombo.setEnabled(afterCueRadio.isSelected()));
        afterQRadio.addActionListener(e ->
                afterCueCombo.setEnabled(false));
        atStartRadio.addActionListener(e ->
                afterCueCombo.setEnabled(false));

        ButtonGroup startGroup = new ButtonGroup();
        startGroup.add(afterQRadio);
        startGroup.add(atStartRadio);
        startGroup.add(afterCueRadio);
        JPanel startPanel = new JPanel(new GridLayout(3, 1));
        startPanel.setOpaque(false);
        startPanel.add(afterQRadio);
        startPanel.add(atStartRadio);
        JPanel afterCuePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        afterCuePanel.setOpaque(false);
        afterCuePanel.add(afterCueRadio);
        afterCuePanel.add(afterCueCombo);
        startPanel.add(afterCuePanel);
        gc.gridx = 1; gc.gridwidth = 2;
        form.add(startPanel, gc);
        gc.gridwidth = 1;
        row++;

        // Question audio (optional via checkbox).
        JCheckBox useQuestionCheck = new JCheckBox(
                "Use question audio", quiz.useQuestionAudio);
        useQuestionCheck.setToolTipText("Uncheck to skip the question narration "
                + "entirely — the timeline cues / special audio become the slide audio.");
        gc.gridx = 0; gc.gridy = row;
        form.add(useQuestionCheck, gc);
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
        // Grey out the file picker when "Use question audio" is unchecked.
        qAudioBtn.setEnabled(quiz.useQuestionAudio);
        qAudioLabel.setEnabled(quiz.useQuestionAudio);
        useQuestionCheck.addActionListener(e -> {
            boolean on = useQuestionCheck.isSelected();
            qAudioBtn.setEnabled(on);
            qAudioLabel.setEnabled(on);
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

        // ---- Audio timeline (per-text cues + special) ----
        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("Audio timeline:"), gc);
        JButton timelineBtn = new JButton("Open audio timeline…");
        JLabel timelineLabel = new JLabel(cueSummary(quiz));
        timelineLabel.setForeground(new Color(120, 200, 255));
        timelineBtn.addActionListener(e -> {
            openTimelineDialog(dialog, quiz, textItemCount);
            timelineLabel.setText(cueSummary(quiz));
        });
        gc.gridx = 1; form.add(timelineBtn, gc);
        gc.gridx = 2; form.add(timelineLabel, gc);
        row++;

        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 3;
        form.add(small("Optional: per-option audios trigger text effects, "
                + "plus one text-less \"special\" audio."), gc);
        gc.gridwidth = 1;
        row++;

        // ---- NEW: Special-audio timeline (single narration → second-by-second
        //           effect fires on chosen texts). Independent of "Audio timeline"
        //           above — leave OFF to keep classic behavior.
        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("Special audio timeline:"), gc);
        JButton specialTimelineBtn = new JButton("Open special timeline…");
        JLabel  specialTimelineLabel = new JLabel(specialTimelineSummary(quiz));
        specialTimelineLabel.setForeground(new Color(120, 200, 255));
        specialTimelineBtn.addActionListener(e -> {
            openSpecialTimelineDialog(dialog, quiz, textItemCount, textItemTexts);
            specialTimelineLabel.setText(specialTimelineSummary(quiz));
        });
        gc.gridx = 1; form.add(specialTimelineBtn, gc);
        gc.gridx = 2; form.add(specialTimelineLabel, gc);
        row++;

        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 3;
        form.add(small("New: one narration audio + per-second schedule of "
                + "which text effect fires at which moment."), gc);
        gc.gridwidth = 1;
        row++;

        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 3;
        form.add(small("Saving will build the slide audio: "
                + "question → ticks → ding (+ timeline cues mixed in), and attach it to slide audio (Text 1)."), gc);
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
            if (afterCueRadio.isSelected()) {
                quiz.timerStartMode = "AfterCue";
                int sel = afterCueCombo.getSelectedIndex();
                quiz.timerStartCueIndex = (sel == textItemCount) ? 0 : (sel + 1);
            } else if (atStartRadio.isSelected()) {
                quiz.timerStartMode = "AtSlideStart";
            } else {
                quiz.timerStartMode = "AfterQuestion";
            }
            quiz.useQuestionAudio    = useQuestionCheck.isSelected();

            if (quiz.enabled) {
                // In special-timeline mode the special audio IS the narration,
                // so we don't require a question-audio file.
                if (!quiz.useSpecialTimeline
                        && quiz.useQuestionAudio
                        && (quiz.questionAudioFile == null
                        || !quiz.questionAudioFile.exists())) {
                    JOptionPane.showMessageDialog(dialog,
                            "Please choose a question-audio file, "
                                    + "or uncheck \"Use question audio\".",
                            "Missing question audio",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (quiz.useSpecialTimeline
                        && (quiz.specialTimelineAudioFile == null
                        || !quiz.specialTimelineAudioFile.exists())) {
                    JOptionPane.showMessageDialog(dialog,
                            "Special-audio timeline is on but no narration "
                                    + "audio is chosen. Open \"Special timeline…\" "
                                    + "and browse for one, or turn the timeline off.",
                            "Missing special audio",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                try {
                    File built = generateCombinedAudio(quiz);
                    quiz.generatedAudioFile = built;
                    quiz.generatedAudioDurationMs = probeDurationMs(built);
                    quiz.questionEndMs = quiz.useQuestionAudio
                            ? quiz.questionAudioDurationMs : 0;
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

    // ============================================================
    //  AUDIO TIMELINE SUB-DIALOG
    // ============================================================

    private static final String[] FX_NAMES = {
            "Glow", "Enlarge", "Bold", "Underline", "Color", "Shake", "Pulse"
    };

    /** Build a one-line summary of the configured cues for the main dialog label. */
    private static String cueSummary(QuizSlide quiz) {
        if (quiz == null || quiz.cues == null) return "(none)";
        int text = 0, special = 0;
        for (QuizCue c : quiz.cues) {
            if (c == null || c.audioFile == null) continue;
            if (c.targetTextIndex >= 1) text++;
            else special++;
        }
        if (text == 0 && special == 0) return "(none)";
        StringBuilder sb = new StringBuilder();
        if (text > 0) sb.append(text).append(text == 1 ? " text cue" : " text cues");
        if (special > 0) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(special).append(" special");
        }
        return sb.toString();
    }

    /**
     * Modal sub-dialog for editing the slide's audio cues:
     *   - one row per text item (target=#1..#N)
     *   - one row for the "special" text-less audio (target=0)
     *
     * Each row: Browse audio / file label / Start (s) / Effects… / Color / Glow.
     * Save commits the edits into {@code quiz.cues}; Cancel discards.
     */
    private static void openTimelineDialog(Window parent, QuizSlide quiz, int textItemCount) {
        JDialog dlg = new JDialog(parent, "Quiz Audio Timeline",
                Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new BorderLayout(8, 8));

        JPanel rows = new JPanel(new GridBagLayout());
        rows.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill   = GridBagConstraints.HORIZONTAL;

        int r = 0;

        // Header.
        gc.gridx = 0; gc.gridy = r;
        rows.add(headerLabel("Target"), gc);
        gc.gridx = 1; rows.add(headerLabel("Audio file"), gc);
        gc.gridx = 2; rows.add(headerLabel("Start (s)"), gc);
        gc.gridx = 3; rows.add(headerLabel("Rank"), gc);
        gc.gridx = 4; rows.add(headerLabel("Effects"), gc);
        gc.gridx = 5; rows.add(headerLabel("Color"), gc);
        gc.gridx = 6; rows.add(headerLabel("Glow"), gc);
        gc.gridx = 7; rows.add(headerLabel("Replay"), gc);
        r++;

        // Build N+1 row widgets: one per text item + one for the special audio.
        int rowCount = Math.max(1, textItemCount) + 1;
        TimelineRow[] uiRows = new TimelineRow[rowCount];

        for (int i = 0; i < rowCount; i++) {
            boolean special = (i == rowCount - 1);
            int target = special ? 0 : (i + 1);   // 1-based text index, or 0
            QuizCue existing = findCueForTarget(quiz, target);
            uiRows[i] = buildTimelineRow(dlg, rows, gc, r++, target, existing);
        }

        // Footer hint explaining the Rank column.
        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 8;
        rows.add(small("Tip: Rank ≥ 1 means \"play in order\" — the cue's "
                + "start is chained from earlier-ranked cues' lengths; the "
                + "Start (s) field is ignored. Rank 0 uses Start (s) as-is."), gc);
        gc.gridwidth = 1;

        dlg.add(new JScrollPane(rows), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton cancel = new JButton("Cancel");
        JButton save   = new JButton("Save");
        cancel.addActionListener(e -> dlg.dispose());
        save.addActionListener(e -> {
            // Rebuild quiz.cues from the row widgets. Drop rows that have no file.
            List<QuizCue> rebuilt = new ArrayList<>();
            for (TimelineRow ui : uiRows) {
                if (ui == null || ui.cue.audioFile == null) continue;
                ui.cue.startMs = (int) Math.round(
                        ((Number) ui.startSpin.getValue()).doubleValue() * 1000.0);
                ui.cue.rank = (Integer) ui.rankSpin.getValue();
                rebuilt.add(ui.cue);
            }
            quiz.cues = rebuilt;
            dlg.dispose();
        });
        buttons.add(cancel);
        buttons.add(save);
        dlg.add(buttons, BorderLayout.SOUTH);

        dlg.pack();
        dlg.setSize(Math.max(720, dlg.getWidth()), Math.min(560, Math.max(280, dlg.getHeight())));
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
    }

    private static JLabel headerLabel(String t) {
        JLabel l = new JLabel(t);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setForeground(new Color(190, 195, 210));
        return l;
    }

    private static QuizCue findCueForTarget(QuizSlide quiz, int target1Based) {
        if (quiz == null || quiz.cues == null) return null;
        for (QuizCue c : quiz.cues) {
            if (c != null && c.targetTextIndex == target1Based) return c;
        }
        return null;
    }

    /** Holds the widgets and the mutated cue for one row of the timeline editor. */
    private static class TimelineRow {
        QuizCue cue;
        JSpinner startSpin;
        JSpinner rankSpin;
    }

    private static TimelineRow buildTimelineRow(JDialog parent, JPanel rows,
                                                GridBagConstraints gc, int r,
                                                int target1Based, QuizCue existing) {
        final TimelineRow ui = new TimelineRow();
        ui.cue = existing != null ? existing.copy() : new QuizCue();
        ui.cue.targetTextIndex = target1Based;

        String label = target1Based == 0
                ? "Special (no text)"
                : "Text #" + target1Based;

        gc.gridx = 0; gc.gridy = r;
        rows.add(new JLabel(label), gc);

        JButton browse = new JButton("Browse…");
        JLabel fileLabel = new JLabel(ui.cue.audioFile != null
                ? ui.cue.audioFile.getName()
                + (ui.cue.durationMs > 0
                ? "  (" + (ui.cue.durationMs / 1000.0) + "s)" : "")
                : "(none)");
        fileLabel.setForeground(ui.cue.audioFile != null
                ? new Color(120, 200, 255) : Color.GRAY);
        browse.addActionListener(e -> {
            File f = pickAudio(parent);
            if (f != null) {
                ui.cue.audioFile  = f;
                ui.cue.durationMs = probeDurationMs(f);
                fileLabel.setText(f.getName()
                        + (ui.cue.durationMs > 0
                        ? "  (" + (ui.cue.durationMs / 1000.0) + "s)" : ""));
                fileLabel.setForeground(new Color(120, 200, 255));
            }
        });
        JPanel fileCell = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fileCell.setOpaque(false);
        fileCell.add(browse);
        fileCell.add(fileLabel);
        // "Clear" button so users can remove a cue without deleting the row.
        JButton clear = new JButton("✕");
        clear.setMargin(new Insets(0, 4, 0, 4));
        clear.setToolTipText("Remove this cue");
        clear.addActionListener(e -> {
            ui.cue.audioFile  = null;
            ui.cue.durationMs = 0;
            fileLabel.setText("(none)");
            fileLabel.setForeground(Color.GRAY);
        });
        fileCell.add(clear);
        gc.gridx = 1; rows.add(fileCell, gc);

        ui.startSpin = new JSpinner(new SpinnerNumberModel(
                ui.cue.startMs / 1000.0, 0.0, 3600.0, 0.1));
        ((JSpinner.NumberEditor) ui.startSpin.getEditor())
                .getTextField().setColumns(5);
        ui.startSpin.setToolTipText("Absolute start in seconds. Ignored "
                + "when Rank is ≥ 1 (rank-based scheduling takes over).");
        gc.gridx = 2; rows.add(ui.startSpin, gc);

        // Rank: when >= 1, the cue plays in rank order — its start is the
        // sum of earlier-ranked cues' durations, so the user doesn't have
        // to compute offsets manually. 0 = "use Start (s)" instead.
        ui.rankSpin = new JSpinner(new SpinnerNumberModel(
                Math.max(0, ui.cue.rank), 0, 99, 1));
        ((JSpinner.NumberEditor) ui.rankSpin.getEditor())
                .getTextField().setColumns(3);
        ui.rankSpin.setToolTipText("0 = use Start (s) as-is. 1, 2, 3… = "
                + "play in that order; each ranked cue starts right after "
                + "the previous one finishes (durations chained automatically).");
        // Visual cue: dim the Start spinner when rank takes precedence.
        ui.startSpin.setEnabled(ui.cue.rank <= 0);
        ui.rankSpin.addChangeListener(e -> {
            int rk = (Integer) ui.rankSpin.getValue();
            ui.cue.rank = rk;
            ui.startSpin.setEnabled(rk <= 0);
        });
        gc.gridx = 3; rows.add(ui.rankSpin, gc);

        // Effects: only for text-targeting cues; special audio has no text effect.
        if (target1Based >= 1) {
            JButton fxBtn = new JButton(fxLabel(ui.cue.effects));
            fxBtn.addActionListener(e -> {
                String picked = pickEffects(parent, ui.cue.effects);
                if (picked != null) {
                    ui.cue.effects = picked;
                    fxBtn.setText(fxLabel(picked));
                }
            });
            gc.gridx = 4; rows.add(fxBtn, gc);

            JButton colorBtn = new JButton("       ");
            colorBtn.setBackground(ui.cue.hlColor);
            colorBtn.setOpaque(true);
            colorBtn.addActionListener(e -> {
                Color picked = JColorChooser.showDialog(parent,
                        "Highlight color", ui.cue.hlColor);
                if (picked != null) {
                    ui.cue.hlColor = picked;
                    colorBtn.setBackground(picked);
                }
            });
            gc.gridx = 5; rows.add(colorBtn, gc);

            JSpinner glowSp = new JSpinner(new SpinnerNumberModel(
                    Math.max(1, ui.cue.glowSize), 1, 40, 1));
            ((JSpinner.NumberEditor) glowSp.getEditor())
                    .getTextField().setColumns(3);
            glowSp.addChangeListener(e ->
                    ui.cue.glowSize = (Integer) glowSp.getValue());
            gc.gridx = 6; rows.add(glowSp, gc);

            JCheckBox afterRevealBox = new JCheckBox(
                    "After reveal too", ui.cue.playAfterReveal);
            afterRevealBox.setToolTipText("Also play this cue (and re-fire its "
                    + "text effect) the moment the timer ends.");
            afterRevealBox.addActionListener(e ->
                    ui.cue.playAfterReveal = afterRevealBox.isSelected());
            gc.gridx = 7; rows.add(afterRevealBox, gc);
        } else {
            // Special cue: greyed-out placeholders for clarity.
            JLabel dash1 = small("—"); JLabel dash2 = small("—"); JLabel dash3 = small("—");
            JLabel dash4 = small("—");
            gc.gridx = 4; rows.add(dash1, gc);
            gc.gridx = 5; rows.add(dash2, gc);
            gc.gridx = 6; rows.add(dash3, gc);
            gc.gridx = 7; rows.add(dash4, gc);
        }
        return ui;
    }

    private static String fxLabel(String csv) {
        if (csv == null || csv.isEmpty()) return "Glow ▾";
        String trimmed = csv.length() > 28 ? csv.substring(0, 25) + "…" : csv;
        return trimmed + " ▾";
    }

    /** Modal checkbox popup that returns the new comma-separated effects string. */
    private static String pickEffects(Window parent, String current) {
        JDialog d = new JDialog(parent, "Cue effects",
                Dialog.ModalityType.APPLICATION_MODAL);
        d.setLayout(new BorderLayout(6, 6));

        JPanel grid = new JPanel(new GridLayout(0, 2, 4, 2));
        grid.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        java.util.Set<String> have = new java.util.HashSet<>();
        if (current != null) {
            for (String t : current.split(",")) {
                String tt = t.trim();
                if (!tt.isEmpty()) have.add(tt);
            }
        }
        JCheckBox noneBox = new JCheckBox("None (no effect)", have.contains("None"));
        grid.add(noneBox);
        grid.add(new JLabel());
        JCheckBox[] boxes = new JCheckBox[FX_NAMES.length];
        for (int i = 0; i < FX_NAMES.length; i++) {
            boxes[i] = new JCheckBox(FX_NAMES[i], have.contains(FX_NAMES[i]));
            grid.add(boxes[i]);
        }
        d.add(grid, BorderLayout.CENTER);

        final String[] result = {null};
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        JButton ok = new JButton("OK");
        JButton ca = new JButton("Cancel");
        ok.addActionListener(e -> {
            if (noneBox.isSelected()) {
                result[0] = "None";
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < boxes.length; i++) {
                    if (boxes[i].isSelected()) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(FX_NAMES[i]);
                    }
                }
                result[0] = sb.length() == 0 ? "None" : sb.toString();
            }
            d.dispose();
        });
        ca.addActionListener(e -> d.dispose());
        btns.add(ca); btns.add(ok);
        d.add(btns, BorderLayout.SOUTH);

        d.pack();
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
        return result[0];
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

    // ============================================================
    //  SPECIAL-AUDIO TIMELINE SUB-DIALOG (NEW)
    // ============================================================
    /** One-liner summary of the special-timeline config for the main label. */
    private static String specialTimelineSummary(QuizSlide quiz) {
        if (quiz == null) return "(off)";
        StringBuilder sb = new StringBuilder();
        if (quiz.useSpecialTimeline) {
            int n = quiz.timelineEvents != null ? quiz.timelineEvents.size() : 0;
            if (quiz.specialTimelineAudioFile == null) sb.append("pre: no audio");
            else sb.append("pre: ").append(n).append(n == 1 ? " event" : " events");
        }
        if (quiz.useAfterRevealTimeline) {
            if (sb.length() > 0) sb.append(" · ");
            int m = quiz.afterRevealEvents != null ? quiz.afterRevealEvents.size() : 0;
            if (quiz.afterRevealAudioFile == null) sb.append("after-reveal: no audio");
            else sb.append("after-reveal: ").append(m).append(m == 1 ? " event" : " events");
        }
        return sb.length() == 0 ? "(off)" : "on — " + sb;
    }

    /** Special-audio timeline viewer.
     *  Read-only: At(s), Dur (ms), narration audio, enable flag — these
     *             come from the CSV import.
     *  Editable:  Target (dropdown of the slide's texts), Effect (multi-
     *             select popup over the effect vocabulary), Color (color
     *             picker), Glow (1-40). All edits commit to the slide's
     *             live timelineEvents on selection / Enter, so closing
     *             the dialog persists them. */
    private static void openSpecialTimelineDialog(Window parent, QuizSlide quiz,
                                                  int textItemCount,
                                                  List<String> textItemTexts) {
        JDialog dlg = new JDialog(parent, "Quiz Special Audio Timeline",
                Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new BorderLayout(8, 8));

        // ----- Top panel: read-only display of CSV-imported values -----
        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 8);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill   = GridBagConstraints.HORIZONTAL;
        int r = 0;

        JCheckBox enableBox = new JCheckBox(
                "Use special-audio timeline on this slide", quiz.useSpecialTimeline);
        enableBox.setEnabled(false);
        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 4;
        top.add(enableBox, gc);
        gc.gridwidth = 1;

        gc.gridx = 0; gc.gridy = r;
        top.add(new JLabel("Narration audio:"), gc);
        String audioText = quiz.specialTimelineAudioFile != null
                ? quiz.specialTimelineAudioFile.getName()
                + (quiz.specialTimelineAudioDurationMs > 0
                ? "  (" + (quiz.specialTimelineAudioDurationMs / 1000.0) + " s)" : "")
                : "(none)";
        JLabel fileLabel = new JLabel(audioText);
        fileLabel.setForeground(quiz.specialTimelineAudioFile != null
                ? new Color(120, 200, 255) : Color.GRAY);
        gc.gridx = 1; gc.gridwidth = 3;
        top.add(fileLabel, gc);
        gc.gridwidth = 1;
        r++;

        // Build Target-dropdown options once: one per slide-text item, with
        // the actual text content for context (e.g. "Text #2 — Sparse").
        final int targetCount = Math.max(textItemCount,
                textItemTexts != null ? textItemTexts.size() : 0);
        final TargetOption[] targetOptions = new TargetOption[Math.max(1, targetCount)];
        for (int i = 0; i < targetOptions.length; i++) {
            String t = (textItemTexts != null && i < textItemTexts.size())
                    ? textItemTexts.get(i) : null;
            targetOptions[i] = new TargetOption(i + 1, t);
        }

        // ----- Pre-reveal (existing) timeline table -----
        if (quiz.timelineEvents == null) quiz.timelineEvents = new ArrayList<>();
        TimelineTablePanel preTable = buildTimelineTablePanel(
                dlg, quiz.timelineEvents, targetOptions,
                "Effect fires (one per row)", false);

        // ----- After-reveal section: audio picker + table -----
        if (quiz.afterRevealEvents == null) quiz.afterRevealEvents = new ArrayList<>();
        JPanel afterTop = new JPanel(new GridBagLayout());
        afterTop.setBorder(BorderFactory.createEmptyBorder(8, 14, 4, 14));
        GridBagConstraints ag = new GridBagConstraints();
        ag.insets = new Insets(4, 4, 4, 8);
        ag.anchor = GridBagConstraints.WEST;
        ag.fill   = GridBagConstraints.HORIZONTAL;
        JCheckBox afterEnable = new JCheckBox(
                "Use after-reveal audio + timeline on this slide",
                quiz.useAfterRevealTimeline);
        ag.gridx = 0; ag.gridy = 0; ag.gridwidth = 4;
        afterTop.add(afterEnable, ag);
        ag.gridwidth = 1;

        ag.gridx = 0; ag.gridy = 1;
        afterTop.add(new JLabel("After-reveal audio:"), ag);
        JLabel afterFileLabel = new JLabel(afterRevealAudioSummary(quiz));
        afterFileLabel.setForeground(quiz.afterRevealAudioFile != null
                ? new Color(120, 200, 255) : Color.GRAY);
        ag.gridx = 1; ag.gridwidth = 2;
        afterTop.add(afterFileLabel, ag);
        ag.gridwidth = 1;
        JButton afterPickBtn = new JButton("Choose audio…");
        afterPickBtn.addActionListener(e -> {
            File picked = pickAudio(dlg);
            if (picked != null && picked.exists()) {
                quiz.afterRevealAudioFile = picked;
                int dur = probeDurationMs(picked);
                quiz.afterRevealAudioDurationMs = dur > 0 ? dur : 0;
                afterFileLabel.setText(afterRevealAudioSummary(quiz));
                afterFileLabel.setForeground(new Color(120, 200, 255));
            }
        });
        ag.gridx = 3;
        afterTop.add(afterPickBtn, ag);

        afterEnable.addActionListener(e ->
                quiz.useAfterRevealTimeline = afterEnable.isSelected());

        TimelineTablePanel postTable = buildTimelineTablePanel(
                dlg, quiz.afterRevealEvents, targetOptions,
                "After-reveal effect fires (times relative to after-reveal audio start)",
                true);

        JPanel afterSection = new JPanel(new BorderLayout(6, 6));
        afterSection.add(afterTop, BorderLayout.NORTH);
        afterSection.add(postTable.panel, BorderLayout.CENTER);

        // Stack pre-reveal table over the after-reveal section, sharing
        // vertical space evenly so both stay usable in a single dialog.
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                preTable.panel, afterSection);
        split.setResizeWeight(0.5);
        split.setBorder(null);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.add(top, BorderLayout.NORTH);
        center.add(split, BorderLayout.CENTER);
        dlg.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            // Commit any in-progress editor before closing so the last edit
            // isn't silently discarded.
            preTable.stopEditing();
            postTable.stopEditing();
            dlg.dispose();
        });
        buttons.add(close);
        dlg.add(buttons, BorderLayout.SOUTH);

        dlg.pack();
        dlg.setSize(Math.max(820, dlg.getWidth()), Math.max(680, dlg.getHeight()));
        dlg.setLocationRelativeTo(parent);
        split.setDividerLocation(0.5);
        dlg.setVisible(true);
    }

    /** Container returned by {@link #buildTimelineTablePanel} so callers can
     *  embed the table panel and still flush any in-progress edits on close. */
    private static final class TimelineTablePanel {
        final JPanel panel;
        final JTable table;
        TimelineTablePanel(JPanel panel, JTable table) {
            this.panel = panel;
            this.table = table;
        }
        void stopEditing() {
            if (table != null && table.isEditing() && table.getCellEditor() != null) {
                table.getCellEditor().stopCellEditing();
            }
        }
    }

    /** Build a table panel (with optional Add/Remove buttons) bound to the
     *  given {@code view} list. Cell edits mutate the events in place. Used
     *  for both the pre-reveal and the after-reveal timeline tables. */
    private static TimelineTablePanel buildTimelineTablePanel(
            JDialog dlg,
            List<TimelineEvent> view,
            TargetOption[] targetOptions,
            String borderTitle,
            boolean editable) {
        String[] cols = {"At (s)", "Target", "Effect", "Color", "Glow", "Dur (ms)"};
        javax.swing.table.DefaultTableModel tm =
                new javax.swing.table.DefaultTableModel(cols, 0) {
                    @Override public boolean isCellEditable(int row, int column) {
                        // Target (1) and Glow (4) always editable. When the
                        // table is fully editable (after-reveal section) the
                        // At (0) and Dur (5) columns are editable too — the
                        // pre-reveal table keeps them read-only because they
                        // come from CSV.
                        if (column == 1 || column == 4) return true;
                        return editable && (column == 0 || column == 5);
                    }
                    @Override public Class<?> getColumnClass(int column) {
                        if (column == 1) return TargetOption.class;
                        if (column == 4) return Integer.class;
                        return Object.class;
                    }
                };
        JTable table = new JTable(tm);
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);

        // Populate from existing events.
        for (TimelineEvent e : view) {
            tm.addRow(rowDataFor(e, targetOptions));
        }

        // Target column: combo-box editor over the slide's texts.
        JComboBox<TargetOption> targetCombo = new JComboBox<>(targetOptions);
        table.getColumnModel().getColumn(1)
                .setCellEditor(new javax.swing.DefaultCellEditor(targetCombo));

        // Color column: swatch renderer (no text — just the colored cell).
        table.getColumnModel().getColumn(3).setCellRenderer(
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override public Component getTableCellRendererComponent(
                            JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                        Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                        if (row >= 0 && row < view.size()) {
                            c.setBackground(view.get(row).hlColor);
                            setText("");
                        }
                        return c;
                    }
                });

        // Effect (col 2) and Color (col 3): click opens the picker dialog.
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent me) {
                int row = table.rowAtPoint(me.getPoint());
                int col = table.columnAtPoint(me.getPoint());
                if (row < 0 || row >= view.size()) return;
                TimelineEvent te = view.get(row);
                if (col == 2) {
                    String picked = pickEffects(dlg, te.effects);
                    if (picked != null) {
                        te.effects = picked;
                        tm.setValueAt(picked, row, 2);
                    }
                } else if (col == 3) {
                    Color picked = JColorChooser.showDialog(dlg,
                            "Highlight color", te.hlColor);
                    if (picked != null) {
                        te.hlColor = picked;
                        tm.setValueAt(String.format("#%02X%02X%02X",
                                picked.getRed(), picked.getGreen(), picked.getBlue()),
                                row, 3);
                    }
                }
            }
        });

        // Commit edits back to the live TimelineEvent list.
        tm.addTableModelListener(ev -> {
            int row = ev.getFirstRow();
            int col = ev.getColumn();
            if (row < 0 || row >= view.size() || col < 0) return;
            TimelineEvent te = view.get(row);
            Object val = tm.getValueAt(row, col);
            if (col == 0) {
                try {
                    double sec = Double.parseDouble(String.valueOf(val).trim());
                    te.atMs = (int) Math.round(Math.max(0, sec) * 1000.0);
                } catch (NumberFormatException ignored) {
                    tm.setValueAt(String.format("%.2f", te.atMs / 1000.0), row, 0);
                }
            } else if (col == 1 && val instanceof TargetOption) {
                te.targetTextIndex = ((TargetOption) val).index;
            } else if (col == 4) {
                try {
                    int g = Integer.parseInt(String.valueOf(val).trim());
                    te.glowSize = Math.max(1, Math.min(40, g));
                    if (te.glowSize != g) tm.setValueAt(te.glowSize, row, 4);
                } catch (NumberFormatException ignored) {
                    tm.setValueAt(te.glowSize, row, 4);
                }
            } else if (col == 5) {
                try {
                    int d = Integer.parseInt(String.valueOf(val).trim());
                    te.durationMs = Math.max(1, d);
                    if (te.durationMs != d) tm.setValueAt(te.durationMs, row, 5);
                } catch (NumberFormatException ignored) {
                    tm.setValueAt(te.durationMs, row, 5);
                }
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder(borderTitle));
        tableScroll.setPreferredSize(new Dimension(720, 200));

        JPanel wrap = new JPanel(new BorderLayout(6, 6));
        wrap.add(tableScroll, BorderLayout.CENTER);

        if (editable) {
            // Add/Remove row controls for the after-reveal table — the pre-
            // reveal one is CSV-driven so it doesn't expose these.
            JPanel rowButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            JButton addBtn = new JButton("Add row");
            JButton delBtn = new JButton("Remove selected");
            addBtn.addActionListener(e -> {
                TimelineEvent ne = new TimelineEvent();
                view.add(ne);
                tm.addRow(rowDataFor(ne, targetOptions));
            });
            delBtn.addActionListener(e -> {
                int sel = table.getSelectedRow();
                if (sel < 0 || sel >= view.size()) return;
                view.remove(sel);
                tm.removeRow(sel);
            });
            rowButtons.add(addBtn);
            rowButtons.add(delBtn);
            wrap.add(rowButtons, BorderLayout.SOUTH);
        }
        return new TimelineTablePanel(wrap, table);
    }

    /** Build the row-array view of an event for the timeline table. */
    private static Object[] rowDataFor(TimelineEvent e, TargetOption[] targetOptions) {
        TargetOption opt = null;
        for (TargetOption o : targetOptions) {
            if (o.index == e.targetTextIndex) { opt = o; break; }
        }
        if (opt == null) {
            opt = new TargetOption(e.targetTextIndex,
                    e.targetTextIndex >= 1 ? null : "—");
        }
        String colorHex = String.format("#%02X%02X%02X",
                e.hlColor.getRed(), e.hlColor.getGreen(), e.hlColor.getBlue());
        return new Object[] {
                String.format("%.2f", e.atMs / 1000.0),
                opt,
                e.effects != null ? e.effects : "Glow",
                colorHex,
                e.glowSize,
                e.durationMs
        };
    }

    /** One-liner describing the after-reveal audio for the dialog label. */
    private static String afterRevealAudioSummary(QuizSlide quiz) {
        if (quiz == null || quiz.afterRevealAudioFile == null) return "(none)";
        return quiz.afterRevealAudioFile.getName()
                + (quiz.afterRevealAudioDurationMs > 0
                ? "  (" + (quiz.afterRevealAudioDurationMs / 1000.0) + " s)"
                : "");
    }

    /** Dropdown item for the Target column. Display = "Text #N — content"
     *  (or "Text #N" when content is empty); selected value carries the
     *  1-based slide-text index back to the event. */
    private static final class TargetOption {
        final int index;
        final String text;
        TargetOption(int index, String text) {
            this.index = index;
            this.text = text;
        }
        @Override public String toString() {
            return text != null && !text.isEmpty()
                    ? "Text #" + index + " — " + text
                    : "Text #" + index;
        }
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

        boolean hasQuestion = quiz.useQuestionAudio
                && quiz.questionAudioFile != null
                && quiz.questionAudioFile.exists();
        // EXCLUSIVE MODE: when the special-audio timeline is on, the single
        // narration audio REPLACES the old question/per-text-cue narration.
        // We force-off the question leg and skip the existing cues block in
        // the mixer below so the user hears only: special audio + ticks + ding.
        boolean special = quiz.useSpecialTimeline
                && quiz.specialTimelineAudioFile != null
                && quiz.specialTimelineAudioFile.exists()
                && quiz.specialTimelineAudioDurationMs > 0;
        if (special) hasQuestion = false;

        // Recompute timing locally (don't call the QuizSlide helpers — they
        // read fields like questionEndMs that the dialog sets only AFTER the
        // build returns).
        int  qEndMs    = hasQuestion ? quiz.questionAudioDurationMs
                : (special ? quiz.specialTimelineAudioDurationMs : 0);
        long timerMs   = Math.max(0, quiz.timerSeconds) * 1000L;
        long timerStart;
        if (special) {
            // EXCLUSIVE: timer waits for the special narration regardless of
            // the legacy timerStartMode (which only knows about old paths).
            timerStart = qEndMs;
        } else if ("AtSlideStart".equals(quiz.timerStartMode)) {
            timerStart = 0L;
        } else if ("AfterCue".equals(quiz.timerStartMode)) {
            timerStart = 0L;
            if (quiz.cues != null) {
                for (QuizCue c : quiz.cues) {
                    if (c == null || c.audioFile == null || c.durationMs <= 0) continue;
                    if (c.targetTextIndex == quiz.timerStartCueIndex) {
                        timerStart = (long) quiz.effectiveStartMs(c) + c.durationMs;
                        break;
                    }
                }
            }
        } else {
            timerStart = qEndMs;
        }
        long naturalReveal = timerStart + timerMs;
        long lastNonReplayEnd = 0L;
        if (hasQuestion) lastNonReplayEnd = Math.max(lastNonReplayEnd, qEndMs);
        if (special) lastNonReplayEnd = Math.max(lastNonReplayEnd, qEndMs);
        if (!special && quiz.cues != null) {
            for (QuizCue c : quiz.cues) {
                if (c == null || c.audioFile == null || c.durationMs <= 0) continue;
                if (c.playAfterReveal) continue;
                lastNonReplayEnd = Math.max(lastNonReplayEnd,
                        (long) quiz.effectiveStartMs(c) + c.durationMs);
            }
        }
        long visualReveal = Math.max(naturalReveal, lastNonReplayEnd);

        // Build a tick-loop track exactly `timerSeconds` long (skipped when
        // timer is zero — pathological, but we don't want a zero-length input).
        File tickLoop = null;
        if (timerMs > 0) {
            tickLoop = File.createTempFile("quizslide-tickloop-", ".wav");
            tickLoop.deleteOnExit();
            runFfmpeg(new String[] {
                    "ffmpeg", "-y",
                    "-stream_loop", "-1",
                    "-i", tickSrc.getAbsolutePath(),
                    "-t", String.valueOf(quiz.timerSeconds),
                    "-ac", "2", "-ar", "44100",
                    tickLoop.getAbsolutePath()
            });
        }

        // Single layered mix: each layer gets adelay'd to its slide-relative
        // offset, then everything is amix'd together (normalize=0 preserves
        // per-layer volumes; duration=longest pads to the latest input). This
        // unified path handles every combination — with/without question,
        // every timer-start mode, "wait for audio" reveal, and replay cues.
        boolean tickQuietMix = "AtSlideStart".equals(quiz.timerStartMode)
                && hasQuestion && timerStart == 0L;

        List<File>    files  = new ArrayList<>();
        List<Long>    delays = new ArrayList<>();
        List<Double>  vols   = new ArrayList<>();
        if (hasQuestion) {
            files.add(quiz.questionAudioFile); delays.add(0L); vols.add(1.0);
        }
        if (tickLoop != null) {
            files.add(tickLoop); delays.add(timerStart);
            vols.add(tickQuietMix ? 0.08 : 1.0);
        }
        // Ding rides the VISUAL reveal moment so the audible "answer" matches
        // the box appearing on screen — not the natural-countdown end.
        files.add(dingSrc); delays.add(visualReveal); vols.add(1.0);
        // Existing per-text cue audios — skipped entirely in EXCLUSIVE MODE
        // so the user's "single audio only" expectation holds.
        if (!special && quiz.cues != null) {
            for (QuizCue c : quiz.cues) {
                if (c == null || c.audioFile == null || !c.audioFile.exists()
                        || c.durationMs <= 0) continue;
                files.add(c.audioFile);
                delays.add((long) Math.max(0, quiz.effectiveStartMs(c)));
                vols.add(1.0);
                if (c.playAfterReveal) {
                    files.add(c.audioFile);
                    delays.add(visualReveal);
                    vols.add(1.0);
                }
            }
        }
        // EXCLUSIVE MODE narration: the single special audio at slide start.
        if (special) {
            files.add(quiz.specialTimelineAudioFile);
            delays.add(0L);
            vols.add(1.0);
        }
        // After-reveal narration: plays once, starting at the visual reveal
        // moment. Independent of every other mode — when enabled and the file
        // exists, it's always mixed in.
        boolean hasAfterReveal = quiz.useAfterRevealTimeline
                && quiz.afterRevealAudioFile != null
                && quiz.afterRevealAudioFile.exists();
        if (hasAfterReveal) {
            files.add(quiz.afterRevealAudioFile);
            delays.add(visualReveal);
            vols.add(1.0);
        }

        File out = File.createTempFile("quizslide-combined-", ".wav");
        out.deleteOnExit();
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg"); cmd.add("-y");
        for (File f : files) {
            cmd.add("-i"); cmd.add(f.getAbsolutePath());
        }
        StringBuilder fc = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            long delay = delays.get(i);
            double vol = vols.get(i);
            fc.append('[').append(i).append(":a]")
                    .append("aformat=sample_rates=44100:channel_layouts=stereo,")
                    .append("volume=").append(vol).append(',')
                    .append("adelay=").append(delay).append('|').append(delay)
                    .append("[a").append(i).append("];");
        }
        for (int i = 0; i < files.size(); i++) {
            fc.append("[a").append(i).append("]");
        }
        fc.append("amix=inputs=").append(files.size())
                .append(":duration=longest:normalize=0[out]");
        cmd.add("-filter_complex"); cmd.add(fc.toString());
        cmd.add("-map"); cmd.add("[out]");
        cmd.add("-ac"); cmd.add("2");
        cmd.add("-ar"); cmd.add("44100");
        cmd.add(out.getAbsolutePath());
        runFfmpeg(cmd.toArray(new String[0]));
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