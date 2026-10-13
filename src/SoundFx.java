import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sound effects for a slide text's audio (the "Sound" toolbar row): echo,
 * reverb, radio, pitch and so on, rendered with ffmpeg audio filters.
 *
 * <p>Every effect keeps the clip's length exactly — tails (echo, reverb) are
 * cut at the original end and pitch shifts are tempo-compensated — because the
 * slide's timing, karaoke word timings and quiz/timer cues are all derived from
 * the original duration.
 *
 * <p>A setting is stored as one string, {@code "Echo,Reverb@6"}: the chosen
 * effects in any order, then {@code @} and the amount (1..10, 5 = normal). An
 * empty string means no effects.
 */
final class SoundFx {

    private SoundFx() {}

    /** One effect: its name (also the stored token) and the button tooltip. */
    static final class Effect {
        final String name;
        final String tip;
        Effect(String name, String tip) { this.name = name; this.tip = tip; }
    }

    /** Shown as buttons on the Sound row. */
    static final List<Effect> MAIN = Collections.unmodifiableList(Arrays.asList(
            new Effect("Echo",    "Distinct repeating echo, like a voice in a canyon. Amt sets how loud the repeats are."),
            new Effect("Reverb",  "Room / hall ambience: a dense, natural reflection tail. Amt sets the room size."),
            new Effect("Radio",   "Old-radio broadcast tone: narrow band with a mid-range push."),
            new Effect("Phone",   "Telephone line: thin 300-3400 Hz band."),
            new Effect("Deep",    "Lower the voice pitch (same speed). Amt sets how many semitones."),
            new Effect("High",    "Raise the voice pitch (same speed). Amt sets how many semitones."),
            new Effect("Robot",   "Metallic robot voice (flat-pitched vocoder sound)."),
            new Effect("Chorus",  "Thicker, doubled voice, like several speakers in unison."),
            new Effect("Clarity", "Voice enhancer: removes rumble, adds presence and evens the level.")));

    /** Shown in the "More" popup. */
    static final List<Effect> MORE = Collections.unmodifiableList(Arrays.asList(
            new Effect("Hall",         "Large concert-hall / stadium reverb with a long, spacious tail."),
            new Effect("Megaphone",    "Loud-hailer: narrow band with gentle distortion."),
            new Effect("Underwater",   "Muffled and wobbly, as if heard under water."),
            new Effect("Lo-Fi",        "Gritty low-bit, bandwidth-limited sound."),
            new Effect("Flanger",      "Sweeping jet-plane whoosh."),
            new Effect("Tremolo",      "Rhythmic volume pulsing."),
            new Effect("Vibrato",      "Rhythmic pitch wobble."),
            new Effect("Bass Boost",   "Fuller, heavier low end."),
            new Effect("Treble Boost", "Brighter, crisper top end."),
            new Effect("Warm",         "Softer, warmer tone (more lows, fewer harsh highs)."),
            new Effect("Stereo Wide",  "Wider stereo image."),
            new Effect("Compressor",   "Evens out loud and quiet parts for a steady, broadcast level."),
            new Effect("Normalize",    "Brings quiet recordings up to a consistent loudness."),
            new Effect("Denoise",      "Reduces steady background hiss / hum."),
            new Effect("Fade In",      "Fade the audio in from silence."),
            new Effect("Fade Out",     "Fade the audio out to silence at its end.")));

    /** Effects that can't be combined: picking one turns off the others in its group. */
    static final List<Set<String>> EXCLUSIVE = Collections.unmodifiableList(Arrays.asList(
            new LinkedHashSet<>(Arrays.asList("Deep", "High")),
            new LinkedHashSet<>(Arrays.asList("Radio", "Phone", "Megaphone", "Lo-Fi", "Underwater")),
            new LinkedHashSet<>(Arrays.asList("Reverb", "Hall"))));

    static final int AMOUNT_MIN = 1;
    static final int AMOUNT_MAX = 10;
    static final int DEFAULT_AMOUNT = 5;

    // ---------- the stored "Echo,Reverb@6" form ----------

    /** Effects named in {@code spec}, in catalogue order; unknown names are dropped. */
    static Set<String> effectsOf(String spec) {
        Set<String> out = new LinkedHashSet<>();
        if (spec == null || spec.isEmpty()) return out;
        int at = spec.lastIndexOf('@');
        String names = at >= 0 ? spec.substring(0, at) : spec;
        Set<String> given = new LinkedHashSet<>();
        for (String n : names.split(",")) given.add(n.trim());
        for (Effect e : all()) if (given.contains(e.name)) out.add(e.name);
        return out;
    }

    /** The amount in {@code spec}, clamped to 1..10 ({@link #DEFAULT_AMOUNT} when absent). */
    static int amountOf(String spec) {
        if (spec == null) return DEFAULT_AMOUNT;
        int at = spec.lastIndexOf('@');
        if (at < 0) return DEFAULT_AMOUNT;
        try {
            int v = Integer.parseInt(spec.substring(at + 1).trim());
            return Math.max(AMOUNT_MIN, Math.min(AMOUNT_MAX, v));
        } catch (NumberFormatException e) {
            return DEFAULT_AMOUNT;
        }
    }

    /** Store {@code effects} + {@code amount}; no effects gives "" whatever the amount. */
    static String format(Set<String> effects, int amount) {
        if (effects == null || effects.isEmpty()) return "";
        List<String> ordered = new ArrayList<>();
        for (Effect e : all()) if (effects.contains(e.name)) ordered.add(e.name);
        if (ordered.isEmpty()) return "";
        int a = Math.max(AMOUNT_MIN, Math.min(AMOUNT_MAX, amount));
        return String.join(",", ordered) + "@" + a;
    }

    static boolean isActive(String spec) {
        return !effectsOf(spec).isEmpty();
    }

    /** Short summary for a tooltip / status, e.g. "Echo + Reverb (6)". */
    static String describe(String spec) {
        Set<String> fx = effectsOf(spec);
        if (fx.isEmpty()) return "none";
        return String.join(" + ", fx) + " (" + amountOf(spec) + ")";
    }

    static List<Effect> all() {
        List<Effect> l = new ArrayList<>(MAIN);
        l.addAll(MORE);
        return l;
    }

    // ---------- ffmpeg filter graph ----------

    private static final int SR = 44100;
    private static final String STEREO = "aformat=sample_fmts=fltp:sample_rates=" + SR + ":channel_layouts=stereo";

    /**
     * The ffmpeg {@code -af} chain for {@code spec} on a clip of {@code durationMs},
     * or null when there is nothing to apply. The order is a normal mixing chain:
     * clean-up → pitch → tone → modulation → character (radio …) → space
     * (echo / reverb) → dynamics → stereo → fades, then the result is padded /
     * cut to the exact original length.
     */
    static String buildFilter(String spec, int durationMs) {
        return buildFilter(spec, durationMs, 0, true);
    }

    /**
     * As {@link #buildFilter(String, int)}, plus {@code gainDb} of make-up gain
     * ahead of the fades; {@code withFades} false leaves the fades out (used to
     * measure the effect's level without the fades pulling the average down).
     */
    private static String buildFilter(String spec, int durationMs, double gainDb, boolean withFades) {
        Set<String> fx = effectsOf(spec);
        if (fx.isEmpty() || durationMs <= 0) return null;
        double k = amountOf(spec) / (double) DEFAULT_AMOUNT;   // 0.2 .. 2.0, 1 = normal
        double durSec = durationMs / 1000.0;
        List<String> f = new ArrayList<>();
        f.add(STEREO);

        if (fx.contains("Denoise"))
            f.add("afftdn=nr=" + num(clamp(8 + 6 * k, 6, 30)) + ":nf=-30");

        // Pitch: resample to shift, then time-stretch back so the length holds.
        if (fx.contains("Deep") || fx.contains("High")) {
            double semis = clamp(1.5 + 2.5 * k, 2, 7) * (fx.contains("Deep") ? -1 : 1);
            double r = Math.pow(2, semis / 12.0);
            f.add("asetrate=" + num(SR * r));
            f.add("aresample=" + SR);
            f.add("atempo=" + num(1.0 / r));
        }

        if (fx.contains("Clarity")) {
            f.add("highpass=f=90");
            f.add("equalizer=f=250:t=q:w=1.0:g=" + num(-2 * k));
            f.add("equalizer=f=3200:t=q:w=1.2:g=" + num(3 * k));
            f.add("acompressor=threshold=0.125:ratio=3:attack=8:release=120:makeup=1.6");
        }
        if (fx.contains("Bass Boost"))   f.add("bass=g=" + num(clamp(5 * k, 2, 12)) + ":f=110:w=0.7");
        if (fx.contains("Treble Boost")) f.add("treble=g=" + num(clamp(5 * k, 2, 12)) + ":f=4500:w=0.7");
        if (fx.contains("Warm")) {
            f.add("bass=g=" + num(clamp(3 * k, 1, 8)) + ":f=180:w=0.8");
            f.add("treble=g=" + num(-clamp(4 * k, 1, 10)) + ":f=6000:w=0.8");
        }

        if (fx.contains("Robot"))
            f.add("afftfilt=real='hypot(re,im)*sin(0)':imag='hypot(re,im)*cos(0)':win_size=512:overlap=0.75");
        if (fx.contains("Chorus"))
            f.add("chorus=0.6:0.9:" + num(45 * k) + "|" + num(60 * k) + ":0.4|0.32:0.25|0.4:2|1.3");
        if (fx.contains("Flanger"))
            f.add("flanger=delay=2:depth=" + num(clamp(2 * k, 1, 8)) + ":regen=" + num(clamp(20 * k, 0, 60))
                    + ":speed=0.4:width=70");
        if (fx.contains("Tremolo"))
            f.add("tremolo=f=" + num(clamp(4 + k, 2, 8)) + ":d=" + num(clamp(0.45 * k, 0.1, 0.9)));
        if (fx.contains("Vibrato"))
            f.add("vibrato=f=" + num(clamp(4 + k, 2, 8)) + ":d=" + num(clamp(0.22 * k, 0.05, 0.6)));

        if (fx.contains("Radio")) {
            f.add("highpass=f=250");
            f.add("lowpass=f=4500");
            f.add("equalizer=f=1600:t=q:w=1.0:g=" + num(5 * k));
            f.add("acompressor=threshold=0.1:ratio=4:attack=5:release=80:makeup=2");
        }
        if (fx.contains("Phone")) {
            f.add("highpass=f=300,highpass=f=300");
            f.add("lowpass=f=3400,lowpass=f=3400");
            f.add("equalizer=f=1200:t=q:w=1.0:g=" + num(4 * k));
        }
        if (fx.contains("Megaphone")) {
            f.add("highpass=f=500,highpass=f=500");
            f.add("lowpass=f=3500,lowpass=f=3500");
            f.add("equalizer=f=1800:t=q:w=0.8:g=" + num(6 * k));
            f.add("volume=" + num(1 + 0.6 * k));
            f.add("asoftclip=type=tanh");
        }
        if (fx.contains("Lo-Fi")) {
            f.add("acrusher=bits=" + num(clamp(10 - 2 * k, 4, 12)) + ":mode=log:aa=1:mix=0.8");
            f.add("lowpass=f=" + num(clamp(6000 / k, 2500, 9000)));
        }
        if (fx.contains("Underwater")) {
            f.add("lowpass=f=" + num(clamp(700 / k, 300, 1500)) + ",lowpass=f=" + num(clamp(700 / k, 300, 1500)));
            f.add("vibrato=f=1.5:d=" + num(clamp(0.15 * k, 0.05, 0.4)));
        }

        if (fx.contains("Echo")) {
            // Two audible repeats, ~0.3 s apart, quieter each time.
            double decay = clamp(0.32 * k, 0.1, 0.7);
            f.add("aecho=0.85:0.85:" + num(clamp(240 + 30 * k, 200, 400)) + "|" + num(clamp(480 + 60 * k, 400, 800))
                    + ":" + num(decay) + "|" + num(decay * 0.5));
        }
        if (fx.contains("Reverb")) {
            // Dense early reflections at prime-spaced delays = smooth room tail.
            double s = clamp(k, 0.4, 2.0);
            f.add("aecho=0.8:0.88:" + taps(new double[]{23, 37, 53, 71, 97, 131, 173}, s) + ":"
                    + gains(new double[]{0.42, 0.36, 0.31, 0.26, 0.21, 0.16, 0.11}, clamp(0.6 + 0.3 * k, 0.6, 1.2)));
        }
        if (fx.contains("Hall")) {
            double s = clamp(k, 0.4, 2.0);
            f.add("aecho=0.8:0.85:" + taps(new double[]{61, 113, 179, 263, 347, 449, 571}, s) + ":"
                    + gains(new double[]{0.45, 0.39, 0.33, 0.27, 0.21, 0.16, 0.11}, clamp(0.7 + 0.2 * k, 0.7, 1.1)));
        }

        if (fx.contains("Compressor"))
            f.add("acompressor=threshold=" + num(clamp(0.18 / k, 0.05, 0.5)) + ":ratio=" + num(clamp(2 + 2 * k, 2, 8))
                    + ":attack=10:release=150:makeup=" + num(clamp(1 + 0.6 * k, 1, 3)));
        if (fx.contains("Normalize"))
            f.add("dynaudnorm=f=200:g=15:p=0.9:m=" + num(clamp(6 * k, 2, 20)));
        if (fx.contains("Stereo Wide"))
            f.add("extrastereo=m=" + num(clamp(1 + 0.5 * k, 1.1, 2.5)));

        if (gainDb > 0) f.add("volume=" + num(gainDb) + "dB");

        if (withFades && fx.contains("Fade In"))
            f.add("afade=t=in:st=0:d=" + num(Math.min(durSec / 2, clamp(0.4 * k, 0.15, 2.0))));
        if (withFades && fx.contains("Fade Out")) {
            double d = Math.min(durSec / 2, clamp(0.5 * k, 0.15, 2.5));
            f.add("afade=t=out:st=" + num(Math.max(0, durSec - d)) + ":d=" + num(d));
        }

        // Exactly the original length: pad a short result, cut tails.
        f.add("apad");
        f.add("atrim=end_sample=" + Math.round(durSec * SR));
        f.add("asetpts=N/SR/TB");
        return String.join(",", f);
    }

    private static String taps(double[] ms, double scale) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ms.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(num(ms[i] * scale));
        }
        return sb.toString();
    }

    private static String gains(double[] g, double scale) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(num(clamp(g[i] * scale, 0.01, 0.9)));
        }
        return sb.toString();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String num(double v) {
        String s = String.format(Locale.US, "%.3f", v);
        // Trim "2.500" → "2.5", "3.000" → "3" so the filter strings stay readable.
        if (s.indexOf('.') >= 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    // ---------- rendering ----------

    private static final Map<String, File> CACHE = new HashMap<>();

    /**
     * {@code src} with {@code spec} applied, as a WAV of exactly {@code durationMs}.
     * Returns {@code src} itself when there is nothing to apply or ffmpeg fails, so
     * a slide always keeps its audio. Results are cached per file + setting.
     */
    static File apply(File src, String spec, int durationMs) {
        if (src == null || !src.isFile() || durationMs <= 0) return src;
        String filter = buildFilter(spec, durationMs);
        if (filter == null) return src;
        String key = src.getAbsolutePath() + "|" + src.lastModified() + "|" + src.length() + "|" + filter;
        synchronized (CACHE) {
            File hit = CACHE.get(key);
            if (hit != null && hit.isFile() && hit.length() > 0) return hit;
        }
        Set<String> fx = effectsOf(spec);
        File out;
        if (fx.contains("Compressor") || fx.contains("Normalize")) {
            // These set the level on purpose; no make-up gain.
            out = render(src, filter);
        } else {
            // Render without fades, measure the level lost, then (when needed)
            // render again with make-up gain ahead of the fades.
            boolean fades = fx.contains("Fade In") || fx.contains("Fade Out");
            File probe = render(src, buildFilter(spec, durationMs, 0, false));
            if (probe == null) return src;
            double gain = makeUpGainDb(src, probe);
            if (gain == 0 && !fades) {
                out = probe;
            } else {
                probe.delete();
                out = render(src, buildFilter(spec, durationMs, gain, true));
            }
        }
        if (out == null) return src;
        synchronized (CACHE) { CACHE.put(key, out); }
        return out;
    }

    /**
     * Band-limiting effects (Phone, Robot, Megaphone …) lose loudness. The gain
     * (dB) that brings {@code processed} back to {@code src}'s average level, so
     * switching an effect on doesn't make the voice drop away. Only losses over
     * 1.5 dB are made up, by at most 12 dB and never past -1 dBFS peak, so
     * nothing clips; 0 when no make-up is needed.
     */
    private static double makeUpGainDb(File src, File processed) {
        // Measure the source through the same stereo conversion the effect chain
        // starts with: a mono file upmixes at -3 dB, which is not a loss to undo.
        double[] in = volumeOf(src, STEREO + ",");
        double[] now = volumeOf(processed, "");
        if (in == null || now == null) return 0;
        double gain = in[0] - now[0];
        if (gain <= 1.5) return 0;
        gain = Math.min(gain, Math.min(12.0, -1.0 - now[1]));
        return gain > 0.5 ? gain : 0;
    }

    /** {mean, max} volume in dBFS via ffmpeg volumedetect, or null when unreadable. */
    private static double[] volumeOf(File f, String preFilter) {
        StringBuilder log = new StringBuilder();
        if (run(Arrays.asList("ffmpeg", "-hide_banner", "-nostats", "-i", f.getAbsolutePath(),
                "-vn", "-af", preFilter + "volumedetect", "-f", "null", "-"), log) != 0) return null;
        double mean = Double.NaN, max = Double.NaN;
        for (String line : log.toString().split("\n")) {
            int i = line.indexOf("mean_volume:");
            if (i >= 0) mean = parseDb(line.substring(i + 12));
            i = line.indexOf("max_volume:");
            if (i >= 0) max = parseDb(line.substring(i + 11));
        }
        // -91 dB is volumedetect's floor for digital silence: nothing to match.
        if (Double.isNaN(mean) || Double.isNaN(max) || mean < -90) return null;
        return new double[]{mean, max};
    }

    private static double parseDb(String s) {
        try { return Double.parseDouble(s.replace("dB", "").trim()); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    /** Run a command to completion; its output goes to {@code log} when given. Returns the exit code (-1 on error). */
    private static int run(List<String> cmd, StringBuilder log) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) if (log != null) log.append(line).append('\n');
            }
            return proc.waitFor();
        } catch (Exception e) {
            if (log != null) log.append(e);
            return -1;
        }
    }

    /**
     * A WAV of {@code src} as it will sound in the video: with {@code spec}
     * applied, or just decoded when there are no effects (so the preview can
     * A/B the plain voice too). Null when ffmpeg can't read it.
     */
    static File previewFile(File src, String spec, int durationMs) {
        if (src == null || !src.isFile() || durationMs <= 0) return null;
        if (isActive(spec)) {
            File out = apply(src, spec, durationMs);
            return out != src ? out : null;
        }
        String key = src.getAbsolutePath() + "|" + src.lastModified() + "|" + src.length() + "|plain";
        synchronized (CACHE) {
            File hit = CACHE.get(key);
            if (hit != null && hit.isFile() && hit.length() > 0) return hit;
        }
        File out = render(src, STEREO);
        if (out != null) synchronized (CACHE) { CACHE.put(key, out); }
        return out;
    }

    // ---------- preview playback ----------

    /** One preview playback: its thread, who started it, and where it is. */
    private static final class Playback {
        final Object owner;
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread;
        volatile javax.sound.sampled.SourceDataLine line;
        volatile long startFrame;     // clip frame the line started at
        volatile long totalFrames;    // clip length in frames (0 = unknown)
        volatile float frameRate;
        Playback(Object owner) { this.owner = owner; }
    }

    // Lock-free so a finishing playback thread never waits on stop(), which
    // joins that very thread while holding the class lock.
    private static final java.util.concurrent.atomic.AtomicReference<Playback> current =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Loop a PCM WAV for {@code owner} (the toolbar asking) from {@code startMs},
     * replacing whatever preview is playing, until {@link #stop()} or another
     * preview takes over. {@code onEnd} runs on the Swing thread when this
     * playback ends. Streams the file, so long clips don't have to fit in memory.
     */
    static synchronized void play(File wav, Object owner, long startMs, Runnable onEnd) {
        stop();
        Playback pb = new Playback(owner);
        Thread t = new Thread(() -> {
            javax.sound.sampled.SourceDataLine line = null;
            try {
                javax.sound.sampled.AudioInputStream in = javax.sound.sampled.AudioSystem.getAudioInputStream(wav);
                javax.sound.sampled.AudioFormat fmt = in.getFormat();
                int frameSize = fmt.getFrameSize();
                long total = in.getFrameLength() > 0 ? in.getFrameLength() : 0;
                long start = Math.max(0, Math.round(startMs / 1000.0 * fmt.getFrameRate()));
                if (total > 0 && start >= total) start = 0;
                skipFully(in, start * frameSize);
                line = javax.sound.sampled.AudioSystem.getSourceDataLine(fmt);
                line.open(fmt);
                pb.frameRate = fmt.getFrameRate();
                pb.totalFrames = total;
                pb.startFrame = start;
                pb.line = line;
                line.start();
                byte[] buf = new byte[frameSize * 2048];
                while (!pb.stop.get()) {
                    int n = in.read(buf, 0, buf.length);
                    if (n > 0) { line.write(buf, 0, n); continue; }
                    // End of the clip: loop seamlessly from the top.
                    in.close();
                    in = javax.sound.sampled.AudioSystem.getAudioInputStream(wav);
                    if (total == 0) break;   // unknown length: can't keep position, play once
                }
                in.close();
                if (pb.stop.get()) line.flush(); else line.drain();
            } catch (Exception e) {
                System.err.println("[SoundFx] preview: " + e);
                java.awt.Toolkit.getDefaultToolkit().beep();
            } finally {
                if (line != null) line.close();
                current.compareAndSet(pb, null);
                if (onEnd != null) javax.swing.SwingUtilities.invokeLater(onEnd);
            }
        }, "SoundFx-preview");
        t.setDaemon(true);
        pb.thread = t;
        current.set(pb);
        t.start();
    }

    private static void skipFully(java.io.InputStream in, long bytes) throws java.io.IOException {
        while (bytes > 0) {
            long k = in.skip(bytes);
            if (k <= 0) break;
            bytes -= k;
        }
    }

    /** Stop the preview, if one is playing (its {@code onEnd} still runs). */
    static synchronized void stop() {
        Playback pb = current.getAndSet(null);
        if (pb == null) return;
        pb.stop.set(true);
        javax.sound.sampled.SourceDataLine line = pb.line;
        if (line != null) line.flush();   // unblock a pending write
        try { pb.thread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** True while a preview started by {@code owner} is playing. */
    static boolean isPlaying(Object owner) {
        Playback pb = current.get();
        return pb != null && pb.owner == owner;
    }

    /**
     * Where {@code owner}'s preview is in its clip, in ms (0 when it isn't
     * playing). Every effect keeps the clip's length, so a re-rendered version
     * can resume from here and the switch sounds live.
     */
    static long positionMs(Object owner) {
        Playback pb = current.get();
        if (pb == null || pb.owner != owner || pb.line == null || pb.frameRate <= 0) return 0;
        long f = pb.startFrame + pb.line.getLongFramePosition();
        if (pb.totalFrames > 0) f %= pb.totalFrames;
        return Math.round(f * 1000.0 / pb.frameRate);
    }

    private static File render(File src, String filter) {
        File out = null;
        try {
            out = File.createTempFile("slide_audio_sfx_", ".wav");
            out.deleteOnExit();
            StringBuilder log = new StringBuilder();
            int code = run(Arrays.asList(
                    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-i", src.getAbsolutePath(),
                    "-vn", "-af", filter,
                    "-c:a", "pcm_s16le", "-ar", String.valueOf(SR), "-ac", "2",
                    out.getAbsolutePath()), log);
            if (code == 0 && out.length() > 0) return out;
            System.err.println("[SoundFx] ffmpeg failed for " + src.getName() + ":\n" + log);
        } catch (Exception e) {
            System.err.println("[SoundFx] " + e);
        }
        if (out != null) out.delete();
        return null;
    }
}
