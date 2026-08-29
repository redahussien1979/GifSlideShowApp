import java.io.*;
import java.util.*;

/**
 * Reports what an exported video's picture and sound actually do, so a
 * suspected glitch can be located instead of guessed at.
 *
 * Needs nothing but the ffmpeg this app already uses. Run it on an export:
 *
 *     java tools/DiagnoseExport.java myvideo.mp4
 *
 * It prints, for the file given:
 *   - the frame rate and whether every frame interval is identical (a video
 *     that is not strictly constant rate stutters on playback),
 *   - the picture and sound lengths (they should agree to ~20 ms),
 *   - every stretch of near-silence longer than 1 ms, with its position,
 *   - every waveform discontinuity - the jumps that are heard as a click.
 *
 * Run it on the SOURCE audio too. Anything reported for both is in the
 * material, not the export.
 */
public class DiagnoseExport {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: java tools/DiagnoseExport.java <video file> [ffmpeg path]");
            return;
        }
        File in = new File(args[0]);
        if (!in.isFile()) { System.out.println("No such file: " + in); return; }
        String ffmpeg = args.length > 1 ? args[1] : "ffmpeg";

        System.out.println("File: " + in.getName());
        System.out.println("=".repeat(64));
        try {
            reportVideo(ffmpeg, in);
            reportAudio(ffmpeg, in);
        } catch (IOException e) {
            System.out.println("Could not run ffmpeg (" + e.getMessage() + ").");
            System.out.println("Pass its full path as the second argument, e.g.");
            System.out.println("  java tools/DiagnoseExport.java video.mp4 C:\\\\ffmpeg\\\\bin\\\\ffmpeg.exe");
        }
    }

    // ---- picture ---------------------------------------------------------

    private static void reportVideo(String ffmpeg, File in) throws IOException, InterruptedException {
        List<Double> pts = new ArrayList<>();
        Process p = start(ffmpeg, "-i", in.getAbsolutePath(), "-map", "0:v",
                          "-vf", "showinfo", "-f", "null", "-");
        String header = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (header == null && line.contains("Video:")) header = line.trim();
                int k = line.indexOf("pts_time:");
                if (k >= 0) {
                    String v = line.substring(k + 9).trim();
                    int sp = v.indexOf(' ');
                    if (sp > 0) v = v.substring(0, sp);
                    try { pts.add(Double.parseDouble(v)); } catch (NumberFormatException ignore) { }
                }
            }
        }
        p.waitFor();
        if (pts.isEmpty()) { System.out.println("PICTURE: no video stream found.\n"); return; }
        Collections.sort(pts);

        Map<Long, Integer> gaps = new TreeMap<>();
        for (int i = 0; i + 1 < pts.size(); i++) {
            long us = Math.round((pts.get(i + 1) - pts.get(i)) * 1e6);
            gaps.merge(us, 1, Integer::sum);
        }
        System.out.println("PICTURE");
        if (header != null) System.out.println("  stream : " + header);
        System.out.printf ("  frames : %d over %.3f s%n", pts.size(), pts.get(pts.size() - 1));
        // Timebase rounding splits one true interval across two adjacent values.
        boolean steady = gaps.size() <= 1
                || (gaps.size() == 2 && Math.abs(diffOfTwoKeys(gaps)) <= 2);
        if (steady) {
            long us = gaps.keySet().iterator().next();
            System.out.printf("  timing : steady, one frame every %.3f ms (%.3f fps)%n",
                    us / 1000.0, 1e6 / us);
        } else {
            System.out.println("  timing : NOT STEADY - the frame spacing changes, which is");
            System.out.println("           seen as a stutter. Intervals present:");
            gaps.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(8).forEach(e -> System.out.printf("             %8.3f ms  x%d%n",
                        e.getKey() / 1000.0, e.getValue()));
        }
        System.out.println();
    }

    private static long diffOfTwoKeys(Map<Long, Integer> m) {
        Iterator<Long> it = m.keySet().iterator();
        return it.next() - it.next();
    }

    // ---- sound -----------------------------------------------------------

    private static void reportAudio(String ffmpeg, File in) throws IOException, InterruptedException {
        final int RATE = 48000;
        File pcm = File.createTempFile("diag", ".raw");
        pcm.deleteOnExit();
        Process p = start(ffmpeg, "-v", "error", "-y", "-i", in.getAbsolutePath(), "-map", "0:a",
                          "-f", "s16le", "-acodec", "pcm_s16le",
                          "-ar", String.valueOf(RATE), "-ac", "1", pcm.getAbsolutePath());
        drain(p);
        if (p.waitFor() != 0 || pcm.length() == 0) {
            System.out.println("SOUND\n  no audio stream found.\n"); return;
        }
        long total = pcm.length() / 2;
        System.out.println("SOUND");
        System.out.printf ("  length : %.3f s%n", total / (double) RATE);

        // Pass 1 - typical sample-to-sample step, and the level of each 1 ms block.
        int[] stepHist = new int[65536];
        int blockLen = RATE / 1000;
        List<Double> blocks = new ArrayList<>();
        readSamples(pcm, new SampleSink() {
            int prev = 0; boolean first = true; long sq = 0; int n = 0;
            public void accept(int s) {
                if (!first) stepHist[Math.min(65535, Math.abs(s - prev))]++;
                prev = s; first = false;
                sq += (long) s * s;
                if (++n == blockLen) { blocks.add(Math.sqrt(sq / (double) n)); sq = 0; n = 0; }
            }
        });
        long half = 0, seen = 0;
        for (int v : stepHist) half += v;
        half /= 2;
        int medianStepTmp = 0;
        for (int v = 0; v < stepHist.length; v++) {
            seen += stepHist[v];
            if (seen >= half) { medianStepTmp = v; break; }
        }
        final int medianStep = medianStepTmp;
        double[] sorted = blocks.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double medianLevel = sorted.length == 0 ? 0 : sorted[sorted.length / 2];

        // Near-silent stretches: the dropout you hear as the sound cutting out.
        List<String> holes = new ArrayList<>();
        int runStart = -1;
        for (int i = 0; i < blocks.size(); i++) {
            boolean quiet = blocks.get(i) < medianLevel * 0.2;
            if (quiet && runStart < 0) runStart = i;
            if (!quiet && runStart >= 0) {
                if (i - runStart >= 1) holes.add(String.format("%.3f s (%d ms)", runStart / 1000.0, i - runStart));
                runStart = -1;
            }
        }
        if (runStart >= 0) holes.add(String.format("%.3f s (%d ms, at the very end)",
                runStart / 1000.0, blocks.size() - runStart));

        // Waveform jumps: the discontinuity you hear as a click.
        final int clickThreshold = Math.max(200, medianStep * 5);
        List<long[]> found = new ArrayList<>();          // {jump size, sample index}
        readSamples(pcm, new SampleSink() {
            int prev = 0; long idx = 0; boolean first = true; long quietUntil = -1;
            public void accept(int s) {
                if (!first && Math.abs(s - prev) > clickThreshold && idx > quietUntil) {
                    found.add(new long[]{ Math.abs(s - prev), idx });
                    quietUntil = idx + RATE / 500;   // one report per 2 ms
                }
                prev = s; first = false; idx++;
            }
        });
        // Biggest jumps first. Busy material throws up a haze of moderate ones;
        // a real cut or splice stands well clear of it at the top of the list.
        found.sort((x, y) -> Long.compare(y[0], x[0]));
        List<String> clicks = new ArrayList<>();
        for (long[] f : found) {
            clicks.add(String.format("%.3f s  jump %d  = %.0fx the typical step (%d)",
                    f[1] / (double) RATE, f[0], f[0] / (double) Math.max(1, medianStep), medianStep));
        }

        System.out.println("  dropouts (sound falls away):");
        print(holes);
        System.out.printf ("  clicks (waveform jumps), worst first - %d over threshold:%n", clicks.size());
        print(clicks);
        System.out.println();
        System.out.println("  Busy or noisy material always produces some moderate jumps. What");
        System.out.println("  marks a real splice is a jump far above the rest of the list.");
        System.out.println("  Run this on the SOURCE audio too: anything reported for both is in");
        System.out.println("  your material, not the export.");
        pcm.delete();
    }

    private static void print(List<String> items) {
        if (items.isEmpty()) { System.out.println("     none"); return; }
        for (int i = 0; i < Math.min(items.size(), 15); i++) System.out.println("     " + items.get(i));
        if (items.size() > 15) System.out.println("     ... and " + (items.size() - 15) + " more");
    }

    private interface SampleSink { void accept(int sample); }

    private static void readSamples(File pcm, SampleSink sink) throws IOException {
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(new FileInputStream(pcm), 1 << 16))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = d.read(buf)) > 0) {
                for (int i = 0; i + 1 < n; i += 2) {
                    sink.accept((short) ((buf[i] & 0xff) | (buf[i + 1] << 8)));
                }
            }
        }
    }

    private static Process start(String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private static void drain(Process p) throws IOException {
        try (InputStream in = p.getInputStream()) { while (in.read() >= 0) { } }
    }
}
