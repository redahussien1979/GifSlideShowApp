import java.io.*;

/**
 * Checks that ffmpeg and ffprobe can be launched exactly the way the app
 * launches them - by bare name, from the folder the app runs in. Run with the
 * app folder as the working directory and with PATH stripped to the Windows
 * system folders, so a pass proves the bundled copies are found rather than
 * some ffmpeg that happens to be installed on the machine.
 */
public class FfmpegProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("working directory: " + new File(".").getAbsolutePath());
        boolean ok = true;
        for (String tool : new String[] { "ffmpeg", "ffprobe" }) {
            try {
                Process p = new ProcessBuilder(tool, "-version")
                        .redirectErrorStream(true).start();
                String first;
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    first = r.readLine();
                }
                int code = p.waitFor();
                System.out.println(tool + ": exit " + code + " | " + first);
                if (code != 0 || first == null) ok = false;
            } catch (IOException e) {
                System.out.println(tool + ": COULD NOT LAUNCH - " + e.getMessage());
                ok = false;
            }
        }
        System.out.println(ok
                ? "PASS - the app can run both tools from its own folder"
                : "FAIL - the app would report 'cannot run program'");
        System.exit(ok ? 0 : 1);
    }
}
