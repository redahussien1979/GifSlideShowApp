import java.util.*;

/**
 * Checks that Word Sync lines a narration up with the words on screen.
 *
 * Build it against the app and run it:
 *
 *     javac -encoding UTF-8 -d build/classes src/*.java tools/CheckWordSync.java
 *     java -cp build/classes CheckWordSync
 *
 * Each case feeds alignTimingsToText a hint (the text as it is WRITTEN) and a
 * synthetic Scribe stream (the text as it is SAID), one entry per spoken word
 * at a fixed 0.4 s pace, so the window every visible token should receive is
 * known exactly and can be asserted rather than eyeballed.
 *
 * The cases that matter most are the ones where the two spellings diverge: a
 * number is written "18,000km/h" and said "eighteen thousand kilometres per
 * hour", sharing no letters in order. Alongside them are the behaviours the
 * aligner already had - split contractions, punctuation entries, words the
 * narration never reaches - so a change made for one cannot quietly cost the
 * others. Exits non-zero on any failure.
 */
public class CheckWordSync {
    static int pass = 0, fail = 0;

    static List<GifSlideShowApp.WordTiming> scribe(String spoken) {
        List<GifSlideShowApp.WordTiming> out = new ArrayList<>();
        double t = 0.0;
        for (String w : spoken.trim().split("\\s+")) {
            out.add(new GifSlideShowApp.WordTiming(w, t, t + 0.35));
            t += 0.4;
        }
        return out;
    }

    static List<GifSlideShowApp.WordTiming> align(String hint, String spoken) {
        return GifSlideShowApp.alignTimingsToText(scribe(spoken), hint);
    }

    static void check(String label, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + label); }
        else    { fail++; System.out.println("  FAIL  " + label + "  -- " + detail); }
    }

    /** Every visible token must get a non-empty window, and windows must advance. */
    static void expectAllLit(String label, String hint, String spoken) {
        List<GifSlideShowApp.WordTiming> out = align(hint, spoken);
        int n = hint.trim().split("\\s+").length;
        if (out.size() != n) { check(label, false, "expected " + n + " entries, got " + out.size()); return; }
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).endSec <= out.get(i).startSec) {
                check(label, false, "token " + i + " (" + out.get(i).word + ") never lit"); return;
            }
        }
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i).startSec < out.get(i - 1).startSec) {
                check(label, false, "token " + i + " goes backwards"); return;
            }
        }
        check(label, true, "");
    }

    /** The window for token `idx` must cover [lo,hi] seconds and not spill past it. */
    static void expectWindow(String label, String hint, String spoken, int idx, double lo, double hi) {
        List<GifSlideShowApp.WordTiming> out = align(hint, spoken);
        GifSlideShowApp.WordTiming w = out.get(idx);
        boolean ok = Math.abs(w.startSec - lo) < 0.06 && Math.abs(w.endSec - hi) < 0.06;
        check(label, ok, String.format("token %d got %.2f->%.2f, wanted %.2f->%.2f", idx, w.startSec, w.endSec, lo, hi));
    }

    /** No two tokens may share an identical window (the tie the karaoke pass can't break). */
    static void expectNoDuplicateWindows(String label, String hint, String spoken) {
        List<GifSlideShowApp.WordTiming> out = align(hint, spoken);
        for (int i = 1; i < out.size(); i++) {
            GifSlideShowApp.WordTiming a = out.get(i - 1), b = out.get(i);
            if (a.startSec == b.startSec && a.endSec == b.endSec && b.endSec > b.startSec) {
                check(label, false, "tokens " + (i - 1) + "/" + i + " share a window"); return;
            }
        }
        check(label, true, "");
    }

    public static void main(String[] args) {
        System.out.println("-- the reported bug --");
        expectAllLit("18,000km/h: whole paragraph still lights",
            "P-waves spread out from the site of the earthquake at over 18,000km/h giving about a minute of warning",
            "P waves spread out from the site of the earthquake at over eighteen thousand kilometers per hour giving about a minute of warning");
        // "over" ends 4.75; "eighteen thousand kilometers per hour" runs 4.80..6.75; "giving" starts 6.80.
        expectWindow("18,000km/h: number spans exactly its five spoken words",
            "P-waves spread out from the site of the earthquake at over 18,000km/h giving about a minute of warning",
            "P waves spread out from the site of the earthquake at over eighteen thousand kilometers per hour giving about a minute of warning",
            11, 4.80, 6.75);
        expectAllLit("80,000: whole sentence still lights",
            "the blast threw 80,000 tonnes of rock into the air",
            "the blast threw eighty thousand tonnes of rock into the air");
        expectWindow("80,000: number spans 'eighty thousand' only",
            "the blast threw 80,000 tonnes of rock into the air",
            "the blast threw eighty thousand tonnes of rock into the air",
            3, 1.20, 1.95);

        System.out.println("-- other number shapes --");
        expectAllLit("year 1990s", "Since the 1990s however the failure",
            "Since the nineteen nineties however the failure");
        expectAllLit("year 2016", "it happened in 2016 and again later",
            "it happened in twenty sixteen and again later");
        expectAllLit("plain 2005", "it happened in 2005 and again later",
            "it happened in two thousand five and again later");
        expectAllLit("small number 7", "there were 7 survivors found alive",
            "there were seven survivors found alive");
        expectAllLit("hundreds 350", "about 350 people were evacuated quickly",
            "about three hundred fifty people were evacuated quickly");
        expectAllLit("millions 2,500,000", "the fund reached 2,500,000 dollars last year",
            "the fund reached two million five hundred thousand dollars last year");
        expectAllLit("unit 5kg", "each box weighs 5kg when it is full",
            "each box weighs five kilograms when it is full");
        expectAllLit("percent sign", "about 40% of them agreed with it",
            "about forty percent of them agreed with it");

        System.out.println("-- Scribe writes figures itself --");
        expectAllLit("figures on both sides", "the speed was 18,000 kilometers per hour",
            "the speed was 18,000 kilometers per hour");
        expectWindow("figures on both sides: number keeps its own word",
            "the speed was 18,000 kilometers per hour",
            "the speed was 18,000 kilometers per hour", 3, 1.20, 1.55);

        System.out.println("-- regressions the old code guarded --");
        // The comment in alignOneReadPass: the trailing "y" of "eighty" must not
        // catch the "y" of "years" and hand both words one identical window.
        expectNoDuplicateWindows("eighty/years must not share a window",
            "he was eighty years old at the time",
            "he was eighty years old at the time");
        expectAllLit("contractions split by Scribe", "it's a telltale sign of trouble",
            "it s a telltale sign of trouble");
        expectAllLit("Scribe emits punctuation entries", "Scientists once believed they might",
            "Scientists , once believed they might .");
        expectAllLit("plain prose control", "Scientists once believed they might one day find telltale signs",
            "Scientists once believed they might one day find telltale signs");

        System.out.println("-- long unmatched token (same stall class) --");
        // Scribe drops a 13-letter word entirely. Before the widened window this
        // pinned the walk exactly as a number did.
        expectAllLit("Scribe misses a 13-letter word",
            "the extraordinary committee met again on Monday",
            "the committee met again on Monday");

        System.out.println("-- text the narration never reaches --");
        List<GifSlideShowApp.WordTiming> partial = align(
            "Reading Practice the quick brown fox jumped over",
            "the quick brown fox");
        check("unspoken tail stays unlit",
            partial.get(5).endSec > partial.get(5).startSec      // "fox" IS spoken
              && partial.get(6).endSec <= partial.get(6).startSec   // "jumped" is not
              && partial.get(7).endSec <= partial.get(7).startSec,  // "over" is not
            "tail tokens should keep empty windows");

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
