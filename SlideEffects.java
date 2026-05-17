import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * Ambient overlay and image-transform effects driven by an animation frame
 * index. All renderers are stateless: per-particle properties are derived
 * deterministically from the particle index via {@link #prand(int, int)} so
 * animation is reproducible without retained state across frames.
 *
 * Entry point: {@link #applyOtherEffect(BufferedImage, int, int, String, int, int)}.
 */
final class SlideEffects {

    private SlideEffects() {}

    static void applyOtherEffect(BufferedImage frame, int targetW, int targetH,
                                 String fxOtherKind, int fxOther, int animFrameIndex) {
        if (fxOther <= 0 || fxOtherKind == null || "None".equals(fxOtherKind)) return;
        if (animFrameIndex < 0) animFrameIndex = 0;

        // Image-transform effects (operate on frame pixels directly).
        if ("Water Waves".equals(fxOtherKind)) {
            applyWaterWaves(frame, targetW, targetH, fxOther, animFrameIndex);
            return;
        }
        if ("Water Waves 2".equals(fxOtherKind)) {
            applyWaterWaves2(frame, targetW, targetH, fxOther, animFrameIndex);
            return;
        }
        if ("Heat Haze".equals(fxOtherKind)) {
            applyHeatHaze(frame, targetW, targetH, fxOther, animFrameIndex);
            return;
        }
        if ("Ken Burns".equals(fxOtherKind)) {
            applyKenBurns(frame, targetW, targetH, fxOther, animFrameIndex);
            return;
        }
        if ("Handheld Drift".equals(fxOtherKind)) {
            applyHandheldDrift(frame, targetW, targetH, fxOther, animFrameIndex);
            return;
        }
        if ("Grade Breathe".equals(fxOtherKind)) {
            applyGradeBreathe(frame, targetW, targetH, fxOther, animFrameIndex);
            return;
        }

        // Overlay effects (drawn on top of the frame).
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        double density = fxOther / 100.0;
        // Resolution scale: assets sized for ~720p; scale up for larger frames.
        double scale = Math.max(0.5, targetH / 720.0);

        switch (fxOtherKind) {
            case "Snow":            drawSnow(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Rain":            drawRain(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Falling Leaves":  drawLeaves(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Cherry Blossom":  drawCherryBlossom(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Confetti":        drawConfetti(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Bokeh":           drawBokeh(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Water Droplets":  drawWaterDroplets(g, frame, targetW, targetH, density, scale, animFrameIndex); break;
            case "Sparkle":         drawSparkle(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Fireflies":       drawFireflies(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Bubbles":         drawBubbles(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Light Leaks":     drawLightLeaks(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Lens Flare":      drawLensFlare(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Dust Motes":      drawDustMotes(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Fog":             drawFog(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Embers":          drawEmbers(g, targetW, targetH, density, scale, animFrameIndex); break;
            case "Film Scratches":  drawFilmScratches(g, targetW, targetH, density, scale, animFrameIndex); break;
        }
        g.dispose();
    }

    private static double prand(int idx, int salt) {
        long x = ((long) (idx + 1)) * 0x9E3779B97F4A7C15L
                ^ ((long) (salt + 1)) * 0xBF58476D1CE4E5B9L;
        x ^= x >>> 30; x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 27; x *= 0x94D049BB133111EBL;
        x ^= x >>> 31;
        return ((x >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    // ==================== New effects ====================

    /**
     * Per-slide deterministic seed sampled from the slide's pixel content.
     * Different slide images produce different seeds, so motion direction
     * varies naturally across a slideshow without needing slide identity
     * plumbed in. Stateless and reproducible for a given image.
     */
    private static int slideSeed(BufferedImage frame, int W, int H) {
        int p0 = frame.getRGB(0, 0);
        int p1 = frame.getRGB(W - 1, 0);
        int p2 = frame.getRGB(0, H - 1);
        int p3 = frame.getRGB(W - 1, H - 1);
        int p4 = frame.getRGB(W / 2, H / 2);
        return p0 ^ (p1 * 31) ^ (p2 * 131) ^ (p3 * 1597) ^ (p4 * 7919);
    }

    /**
     * Ken Burns: slow zoom-in combined with a gentle pan whose direction
     * varies per slide. Asymptotic ease keeps both zoom and pan smoothly
     * progressing toward their maxima. Pan amplitude is bounded by the
     * scaled-up extra pixels so the frame edge is never revealed.
     */
    private static void applyKenBurns(BufferedImage frame, int W, int H, int fxOther, int t) {
        // Max zoom: intensity 100 -> +50% (1.5x). Tasteful upper bound.
        double maxAdd = (fxOther / 100.0) * 0.5;
        // Asymptotic ease: progress = t / (t + halfLife). Reaches half of
        // maxAdd at halfLife frames, ~90% at 9*halfLife. Never overshoots.
        double halfLife = 90.0;
        double progress = t / (t + halfLife);
        double zoom = 1.0 + maxAdd * progress;
        if (zoom <= 1.0005) return;

        int newW = (int) Math.round(W * zoom);
        int newH = (int) Math.round(H * zoom);

        // Per-slide pan direction. Pan stays within 35% of the available
        // headroom so corners of the scaled image never come into view.
        int seed = slideSeed(frame, W, H);
        double angle = prand(seed, 1) * Math.PI * 2.0;
        double maxPanX = (newW - W) * 0.35;
        double maxPanY = (newH - H) * 0.35;
        double panX = Math.cos(angle) * maxPanX * progress;
        double panY = Math.sin(angle) * maxPanY * progress;
        int offX = (W - newW) / 2 + (int) panX;
        int offY = (H - newH) / 2 + (int) panY;

        // Snapshot via Graphics2D blit (avoids int[] round-trip through
        // getRGB/setRGB on managed images).
        BufferedImage src = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = src.createGraphics();
        sg.setComposite(AlphaComposite.Src);
        sg.drawImage(frame, 0, 0, null);
        sg.dispose();

        Graphics2D g = frame.createGraphics();
        // Bilinear is ~3x faster than bicubic and visually indistinguishable
        // for the slow uniform zoom this effect produces.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, offX, offY, newW, newH, null);
        g.dispose();
    }

    /**
     * Handheld drift: subtle low-frequency translation + sub-degree rotation
     * simulating an unsteady operator. Two octaves of sine give smooth
     * organic motion. Frame is scaled up slightly so rotation/translation
     * never reveal the edge.
     */
    private static void applyHandheldDrift(BufferedImage frame, int W, int H, int fxOther, int t) {
        double strength = fxOther / 100.0;

        int seed = slideSeed(frame, W, H);
        double phaseX = prand(seed, 1) * Math.PI * 2.0;
        double phaseY = prand(seed, 2) * Math.PI * 2.0;
        double phaseR = prand(seed, 3) * Math.PI * 2.0;

        // Low-frequency noise: very slow base + smaller faster wobble.
        double slowX = Math.sin(t * 0.012 + phaseX) + 0.5 * Math.sin(t * 0.031 + phaseX * 1.3);
        double slowY = Math.cos(t * 0.010 + phaseY) + 0.5 * Math.cos(t * 0.028 + phaseY * 1.7);
        double slowR = Math.sin(t * 0.008 + phaseR) + 0.4 * Math.sin(t * 0.023 + phaseR * 1.5);

        double maxTx = W * 0.015 * strength;
        double maxTy = H * 0.015 * strength;
        double maxRot = Math.toRadians(0.6) * strength;
        double tx = slowX / 1.5 * maxTx;
        double ty = slowY / 1.5 * maxTy;
        double rot = slowR / 1.4 * maxRot;

        if (Math.abs(tx) < 0.1 && Math.abs(ty) < 0.1 && Math.abs(rot) < 0.0005) return;

        // Snapshot via Graphics2D blit (avoids int[] round-trip).
        BufferedImage src = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = src.createGraphics();
        sg.setComposite(AlphaComposite.Src);
        sg.drawImage(frame, 0, 0, null);
        sg.dispose();

        // Slight over-scale prevents the edge from being revealed by motion.
        double margin = 1.04;

        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setComposite(AlphaComposite.Src);
        g.translate(W / 2.0 + tx, H / 2.0 + ty);
        g.rotate(rot);
        g.scale(margin, margin);
        g.translate(-W / 2.0, -H / 2.0);
        g.drawImage(src, 0, 0, null);
        g.dispose();
    }

    /**
     * Grade breathe: slow oscillation of warm/cool color balance combined
     * with a subtle radial vignette pulse. Sub-perceptual per-frame, but
     * the cumulative effect is what makes a static frame feel "alive"
     * rather than frozen. Both phases vary per slide.
     */
    private static void applyGradeBreathe(BufferedImage frame, int W, int H, int fxOther, int t) {
        double strength = fxOther / 100.0;

        int seed = slideSeed(frame, W, H);
        double phaseW = prand(seed, 1) * Math.PI * 2.0;
        double phaseV = prand(seed, 2) * Math.PI * 2.0;

        // Warmth oscillates -1..1; vignette pulses 0..1.
        double warmth = Math.sin(t * 0.035 + phaseW);
        double vigPulse = 0.5 + 0.5 * Math.sin(t * 0.025 + phaseV);

        int warmShift = (int) (warmth * 8.0 * strength);
        double vigPeak = vigPulse * 0.12 * strength;

        // Channel LUTs: one add + one clamp per source byte, precomputed once.
        int[] rLut = new int[256];
        int[] bLut = new int[256];
        for (int i = 0; i < 256; i++) {
            int rv = i + warmShift;
            if (rv < 0) rv = 0; else if (rv > 255) rv = 255;
            rLut[i] = rv;
            int bv = i - warmShift;
            if (bv < 0) bv = 0; else if (bv > 255) bv = 255;
            bLut[i] = bv;
        }

        // Direct access to the underlying int array when the frame is one of
        // the supported INT-packed types avoids two full-frame memcopies
        // (the int[] round-trip through getRGB/setRGB).
        int frameType = frame.getType();
        boolean direct = (frameType == BufferedImage.TYPE_INT_ARGB
                       || frameType == BufferedImage.TYPE_INT_RGB
                       || frameType == BufferedImage.TYPE_INT_ARGB_PRE);
        int[] pixels;
        if (direct) {
            pixels = ((java.awt.image.DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        } else {
            pixels = new int[W * H];
            frame.getRGB(0, 0, W, H, pixels, 0, W);
        }

        double cx = W / 2.0, cy = H / 2.0;
        double k = vigPeak / (cx * cx + cy * cy);
        // Per-column dx² precomputed once for the whole frame.
        double[] dxSq = new double[W];
        for (int x = 0; x < W; x++) {
            double dxV = x - cx;
            dxSq[x] = dxV * dxV;
        }
        // Per-row vignette factor cache as fixed-point 16.16, scaled to 0..65536.
        int[] facRow = new int[W];

        for (int y = 0; y < H; y++) {
            double dyV = y - cy;
            double dySqK = dyV * dyV * k;
            for (int x = 0; x < W; x++) {
                facRow[x] = (int) ((1.0 - (dxSq[x] * k + dySqK)) * 65536.0);
            }
            int rowOff = y * W;
            for (int x = 0; x < W; x++) {
                int idx = rowOff + x;
                int p = pixels[idx];
                int r = rLut[(p >> 16) & 0xFF];
                int g = (p >> 8) & 0xFF;
                int b = bLut[p & 0xFF];
                int f = facRow[x];
                r = (r * f) >> 16;
                g = (g * f) >> 16;
                b = (b * f) >> 16;
                pixels[idx] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
        }
        if (!direct) {
            frame.setRGB(0, 0, W, H, pixels, 0, W);
        }
    }

    /**
     * Soft drifting dust motes: tiny backlit specks with halos, brightness
     * pulsing slowly. Diffuse cinematic atmosphere, very light footprint.
     */
    private static void drawDustMotes(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (140 * density);
        for (int i = 0; i < N; i++) {
            double size = (1.5 + prand(i, 1) * 3.5) * s;
            double speed = (0.15 + prand(i, 2) * 0.35) * s;
            double angle = prand(i, 3) * Math.PI * 2.0;
            double bx = prand(i, 4) * W;
            double by = prand(i, 5) * H;
            double driftX = Math.cos(angle) * speed * t;
            double driftY = (Math.sin(angle) - 0.15) * speed * t; // gentle upward bias
            double x = ((bx + driftX) % (W + 60) + (W + 60)) % (W + 60) - 30;
            double y = ((by + driftY) % (H + 60) + (H + 60)) % (H + 60) - 30;
            double phase = prand(i, 6) * Math.PI * 2.0;
            double pulse = 0.55 + 0.45 * Math.sin(t * 0.05 + phase);
            int alpha = (int) (pulse * 180);
            if (alpha < 6) continue;
            float fr = (float) (size * 3.5);
            if (fr < 1.5f) continue;
            RadialGradientPaint rgp = new RadialGradientPaint(
                    (float) x, (float) y, fr,
                    new float[]{0f, 0.4f, 1f},
                    new Color[]{
                            new Color(255, 250, 230, (int) (alpha * 0.35)),
                            new Color(255, 245, 220, (int) (alpha * 0.12)),
                            new Color(255, 240, 210, 0)
                    });
            g.setPaint(rgp);
            g.fill(new Ellipse2D.Double(x - fr, y - fr, fr * 2, fr * 2));
            g.setColor(new Color(255, 250, 235, Math.min(220, alpha + 30)));
            g.fillOval((int) (x - size / 2), (int) (y - size / 2), (int) size, (int) size);
        }
    }

    /**
     * Atmospheric fog: large soft cloud blobs drifting horizontally at
     * different heights and speeds. Density controls cloud count and opacity.
     */
    private static void drawFog(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = 3 + (int) (density * 6);
        Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(0.75f));
        for (int i = 0; i < N; i++) {
            double speed = (0.25 + prand(i, 1) * 0.45) * s;
            double size = (Math.min(W, H) * (0.45 + prand(i, 2) * 0.55));
            double by = prand(i, 3) * H;
            double travelW = W + size;
            double dx = ((prand(i, 4) * travelW + t * speed) % travelW + travelW) % travelW - size / 2;
            double dy = by + Math.sin(t * 0.012 + i * 1.3) * H * 0.04;
            int alpha = (int) (density * 90 + prand(i, 5) * 30);
            if (alpha < 8) continue;
            float fr = (float) (size / 2.0);
            RadialGradientPaint rgp = new RadialGradientPaint(
                    (float) dx, (float) dy, fr,
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{
                            new Color(235, 238, 242, alpha),
                            new Color(225, 230, 236, alpha / 2),
                            new Color(220, 226, 232, 0)
                    });
            g.setPaint(rgp);
            g.fill(new Ellipse2D.Double(dx - fr, dy - fr, fr * 2, fr * 2));
        }
        g.setComposite(oc);
    }

    /**
     * Rising warm embers: small orange/red specks with halos that drift
     * upward, flicker, and fade near the top of the frame. Sunset/fireplace mood.
     */
    private static void drawEmbers(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (90 * density);
        double cycle = H + 80 * s;
        for (int i = 0; i < N; i++) {
            double speed = (1.0 + prand(i, 1) * 2.0) * s;
            double size = (1.5 + prand(i, 2) * 3.5) * s;
            double baseX = prand(i, 3) * (W + 60 * s) - 30 * s;
            double swayA = (4.0 + prand(i, 4) * 14.0) * s;
            double swayF = 0.03 + prand(i, 5) * 0.05;
            double phase = prand(i, 6) * cycle;
            // Rising: subtract from cycle so motion is bottom-up.
            double y = (cycle - ((phase + t * speed) % cycle)) - 40 * s;
            double x = baseX + swayA * Math.sin(t * swayF + i * 0.6);
            // Lifecycle envelope: dim at extremes, full mid-flight.
            double lifeT = 1.0 - (y / (double) H);
            if (lifeT < 0) lifeT = 0; else if (lifeT > 1) lifeT = 1;
            double envelope;
            if (lifeT < 0.15) envelope = lifeT / 0.15;
            else if (lifeT > 0.85) envelope = (1.0 - lifeT) / 0.15;
            else envelope = 1.0;
            double flicker = 0.75 + 0.25 * Math.sin(t * 0.4 + i * 1.7);
            int alpha = (int) (envelope * flicker * 220);
            if (alpha < 6) continue;
            int rC = 255;
            int gC = 140 + (int) (prand(i, 7) * 60);
            int bC = 60 + (int) (prand(i, 8) * 40);
            float fr = (float) (size * 4.0);
            if (fr < 1.5f) continue;
            RadialGradientPaint rgp = new RadialGradientPaint(
                    (float) x, (float) y, fr,
                    new float[]{0f, 0.4f, 1f},
                    new Color[]{
                            new Color(rC, gC, bC, (int) (alpha * 0.55)),
                            new Color(rC, gC / 2, bC / 2, (int) (alpha * 0.20)),
                            new Color(rC, gC / 2, bC / 2, 0)
                    });
            g.setPaint(rgp);
            g.fill(new Ellipse2D.Double(x - fr, y - fr, fr * 2, fr * 2));
            g.setColor(new Color(255, 230, 170, Math.min(240, alpha + 30)));
            g.fillOval((int) (x - size / 2), (int) (y - size / 2), (int) size, (int) size);
        }
    }

    /**
     * Vintage film scratches and dust: a deterministic pool of vertical
     * scratches blink on for a few frames at a time; tiny dust specks
     * sprinkle the frame and resample per-frame for an organic flicker.
     */
    private static void drawFilmScratches(Graphics2D g, int W, int H, double density, double s, int t) {
        int scratchBudget = 1 + (int) (density * 3);
        int pool = 40;
        Stroke os = g.getStroke();
        for (int i = 0; i < pool; i++) {
            int onCycle = 12 + (int) (prand(i, 1) * 30);
            int onLen = 2 + (int) (prand(i, 2) * 4);
            int frameInCycle = ((t + (int) (prand(i, 3) * onCycle)) % onCycle + onCycle) % onCycle;
            if (frameInCycle >= onLen) continue;
            // Throttle to scratchBudget concurrent scratches via deterministic gate.
            if ((int) (prand(i, 4) * pool) >= scratchBudget) continue;
            double x = prand(i, 5) * W;
            double yTop = prand(i, 6) * H * 0.4;
            double yBot = H * 0.6 + prand(i, 7) * H * 0.4;
            float thickness = (float) Math.max(1.0, (0.6 + prand(i, 8) * 1.4) * s);
            int alpha = 120 + (int) (prand(i, 9) * 100);
            boolean dark = prand(i, 10) > 0.65;
            Color c = dark ? new Color(20, 18, 14, alpha) : new Color(255, 245, 220, alpha);
            g.setColor(c);
            g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // Slight horizontal jitter along the line for organic feel.
            Path2D.Double path = new Path2D.Double();
            path.moveTo(x, yTop);
            int segments = 6;
            for (int k = 1; k <= segments; k++) {
                double frac = k / (double) segments;
                double yy = yTop + (yBot - yTop) * frac;
                double xx = x + (prand(i * 31 + k, 11) - 0.5) * 1.5 * s;
                path.lineTo(xx, yy);
            }
            g.draw(path);
        }
        g.setStroke(os);

        // Dust specks: resample positions every frame so they don't appear pinned.
        int dustN = (int) (35 * density);
        for (int j = 0; j < dustN; j++) {
            int salt = t * 13 + j * 7;
            double x = prand(salt, 1) * W;
            double y = prand(salt, 2) * H;
            double r = (0.5 + prand(salt, 3) * 1.5) * s;
            int a = 140 + (int) (prand(salt, 4) * 100);
            boolean dark = prand(salt, 5) > 0.4;
            g.setColor(dark ? new Color(20, 18, 14, a) : new Color(245, 240, 225, a));
            g.fillOval((int) (x - r), (int) (y - r), (int) (r * 2), (int) (r * 2));
        }
    }

    // ==================== Existing overlay effects ====================

    private static void drawSnow(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (220 * density);
        double cycle = H + 60 * s;
        for (int i = 0; i < N; i++) {
            double speed = (1.0 + prand(i, 1) * 2.5) * s;
            double size  = (2.0 + prand(i, 2) * 4.0) * s;
            double baseX = prand(i, 3) * (W + 40 * s) - 20 * s;
            double swayA = (3.0 + prand(i, 4) * 12.0) * s;
            double swayF = 0.04 + prand(i, 5) * 0.05;
            double phase = prand(i, 6) * cycle;
            double y = ((phase + t * speed) % cycle) - 30 * s;
            double x = baseX + swayA * Math.sin(t * swayF + i * 0.7);
            int alpha = 200 - (int) (prand(i, 7) * 80);
            g.setColor(new Color(255, 255, 255, Math.max(80, alpha)));
            g.fillOval((int) (x - size / 2), (int) (y - size / 2), (int) size, (int) size);
        }
    }

    private static void drawRain(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (260 * density);
        double cycle = H + 80 * s;
        Stroke origStroke = g.getStroke();
        for (int i = 0; i < N; i++) {
            double speed = (12.0 + prand(i, 1) * 14.0) * s;
            double length = (10.0 + prand(i, 2) * 20.0) * s;
            double baseX = prand(i, 3) * (W + 60 * s) - 30 * s;
            double phase = prand(i, 4) * cycle;
            double y = ((phase + t * speed) % cycle) - 40 * s;
            double dx = -length * 0.18;
            float thickness = (float) (1.0 + prand(i, 5) * 1.5) * (float) Math.max(1.0, s * 0.7);
            int alpha = 110 + (int) (prand(i, 6) * 80);
            g.setColor(new Color(180, 210, 240, Math.min(220, alpha)));
            g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine((int) baseX, (int) (y - length), (int) (baseX + dx), (int) y);
        }
        g.setStroke(origStroke);
    }

    private static final Color[] LEAF_COLORS = {
            new Color(197, 87, 43),  new Color(225, 139, 59),
            new Color(184, 59, 47),  new Color(217, 160, 31),
            new Color(140, 80, 35),  new Color(232, 105, 50)
    };

    private static void drawLeaves(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (90 * density);
        double cycle = H + 100 * s;
        for (int i = 0; i < N; i++) {
            double speed = (1.0 + prand(i, 1) * 1.6) * s;
            double size  = (8.0 + prand(i, 2) * 18.0) * s;
            double baseX = prand(i, 3) * (W + 80 * s) - 40 * s;
            double swayA = (12.0 + prand(i, 4) * 25.0) * s;
            double swayF = 0.02 + prand(i, 5) * 0.04;
            double phase = prand(i, 6) * cycle;
            double y = ((phase + t * speed) % cycle) - 40 * s;
            double x = baseX + swayA * Math.sin(t * swayF + i);
            double rot = t * (0.05 + prand(i, 7) * 0.05) + i * 0.5;
            Color c = LEAF_COLORS[(int) (prand(i, 8) * LEAF_COLORS.length) % LEAF_COLORS.length];
            AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.rotate(rot);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 220));
            g.fill(new Ellipse2D.Double(-size * 0.6, -size * 0.3, size * 1.2, size * 0.6));
            g.setColor(new Color(60, 40, 25, 160));
            g.drawLine((int) (-size * 0.55), 0, (int) (size * 0.55), 0);
            g.setTransform(old);
        }
    }

    private static void drawCherryBlossom(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (130 * density);
        double cycle = H + 100 * s;
        for (int i = 0; i < N; i++) {
            double speed = (0.6 + prand(i, 1) * 0.9) * s;
            double size  = (5.0 + prand(i, 2) * 9.0) * s;
            double baseX = prand(i, 3) * (W + 80 * s) - 40 * s;
            double swayA = (8.0 + prand(i, 4) * 22.0) * s;
            double swayF = 0.025 + prand(i, 5) * 0.04;
            double phase = prand(i, 6) * cycle;
            double y = ((phase + t * speed) % cycle) - 40 * s;
            double x = baseX + swayA * Math.sin(t * swayF + i * 0.9);
            double rot = t * (0.03 + prand(i, 7) * 0.04) + i * 0.7;
            int pinkR = 255;
            int pinkG = 175 + (int) (prand(i, 8) * 35);
            int pinkB = 195 + (int) (prand(i, 9) * 30);
            Color petalColor = new Color(pinkR, pinkG, pinkB, 220);
            AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.rotate(rot);
            g.setColor(petalColor);
            for (int p = 0; p < 5; p++) {
                double a = p * (2 * Math.PI / 5);
                double px = Math.cos(a) * size * 0.5;
                double py = Math.sin(a) * size * 0.5;
                AffineTransform t2 = g.getTransform();
                g.translate(px, py);
                g.rotate(a);
                g.fill(new Ellipse2D.Double(-size * 0.4, -size * 0.25, size * 0.8, size * 0.5));
                g.setTransform(t2);
            }
            g.setColor(new Color(255, 230, 130, 200));
            g.fill(new Ellipse2D.Double(-size * 0.18, -size * 0.18, size * 0.36, size * 0.36));
            g.setTransform(old);
        }
    }

    private static final Color[] CONFETTI_COLORS = {
            new Color(255, 80, 80),   new Color(80, 200, 255),
            new Color(255, 220, 60),  new Color(120, 255, 130),
            new Color(255, 130, 220), new Color(180, 130, 255),
            new Color(255, 170, 60),  new Color(120, 240, 220)
    };

    private static void drawConfetti(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (220 * density);
        double cycle = H + 80 * s;
        for (int i = 0; i < N; i++) {
            double speed = (3.0 + prand(i, 1) * 5.0) * s;
            double sw = (4.0 + prand(i, 2) * 8.0) * s;
            double sh = (2.0 + prand(i, 3) * 4.0) * s;
            double baseX = prand(i, 4) * (W + 60 * s) - 30 * s;
            double swayA = (6.0 + prand(i, 5) * 14.0) * s;
            double swayF = 0.05 + prand(i, 6) * 0.07;
            double phase = prand(i, 7) * cycle;
            double y = ((phase + t * speed) % cycle) - 40 * s;
            double x = baseX + swayA * Math.sin(t * swayF + i * 0.5);
            double rot = t * (0.15 + prand(i, 8) * 0.2) + i;
            Color c = CONFETTI_COLORS[(int) (prand(i, 9) * CONFETTI_COLORS.length) % CONFETTI_COLORS.length];
            AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.rotate(rot);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 230));
            g.fillRect((int) (-sw / 2), (int) (-sh / 2), (int) sw, (int) sh);
            g.setTransform(old);
        }
    }

    private static void drawBokeh(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (70 * density);
        double cycle = H + 200 * s;
        Composite origComp = g.getComposite();
        for (int i = 0; i < N; i++) {
            double speed = (0.5 + prand(i, 1) * 1.3) * s;
            double size = (14.0 + prand(i, 2) * 50.0) * s;
            double baseX = prand(i, 3) * (W + 100 * s) - 50 * s;
            double swayA = (5.0 + prand(i, 4) * 20.0) * s;
            double swayF = 0.015 + prand(i, 5) * 0.025;
            double phase = prand(i, 6) * cycle;
            double y = (cycle - ((phase + t * speed) % cycle)) - 80 * s;
            double x = baseX + swayA * Math.sin(t * swayF + i * 0.4);
            float alpha = (float) (0.18 + prand(i, 7) * 0.32);
            int tint = (int) (prand(i, 8) * 3);
            Color core;
            switch (tint) {
                case 0: core = new Color(255, 240, 200); break;
                case 1: core = new Color(200, 230, 255); break;
                default: core = new Color(255, 255, 255); break;
            }
            float fx = (float) x;
            float fy = (float) y;
            float fr = (float) (size / 2.0);
            if (fr < 1f) continue;
            RadialGradientPaint rgp = new RadialGradientPaint(
                    fx, fy, fr,
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{
                            new Color(core.getRed(), core.getGreen(), core.getBlue(), (int) (alpha * 200)),
                            new Color(core.getRed(), core.getGreen(), core.getBlue(), (int) (alpha * 80)),
                            new Color(core.getRed(), core.getGreen(), core.getBlue(), 0)
                    });
            g.setPaint(rgp);
            g.fill(new Ellipse2D.Double(x - fr, y - fr, size, size));
        }
        g.setComposite(origComp);
    }

    private static void drawWaterDroplets(Graphics2D g, BufferedImage frame, int W, int H,
                                          double density, double s, int t) {
        int N = (int) (80 * density);
        int life = 200;
        int[] src = frame.getRGB(0, 0, W, H, null, 0, W);

        for (int i = 0; i < N; i++) {
            double size = (10.0 + prand(i, 1) * 22.0) * s;
            double bx = prand(i, 2) * W;
            double by = prand(i, 3) * H;
            int phase = (int) (prand(i, 4) * life);
            int frameInLife = ((t + phase) % life + life) % life;
            double lifeT = frameInLife / (double) life;
            double alpha;
            if (lifeT < 0.12) alpha = lifeT / 0.12;
            else if (lifeT < 0.78) alpha = 1.0;
            else alpha = 1.0 - (lifeT - 0.78) / 0.22;
            alpha = Math.max(0, Math.min(1, alpha));
            if (alpha <= 0.02) continue;

            boolean runner = prand(i, 5) < 0.10;
            double dy = 0;
            if (runner) {
                dy = lifeT * lifeT * H * 0.55;
            }
            double cx = bx;
            double cy = by + dy;
            double r = size / 2.0;

            if (runner) {
                int trails = 6;
                for (int k = trails; k >= 1; k--) {
                    double tY = cy - k * size * 0.55;
                    if (tY < by - r) break;
                    double tR = r * (1.0 - k * 0.10);
                    if (tR < 1.5) continue;
                    renderRefractiveDroplet(g, src, W, H, cx, tY, tR, alpha * (1.0 - k * 0.13));
                }
            }
            renderRefractiveDroplet(g, src, W, H, cx, cy, r, alpha);
        }
    }

    private static void renderRefractiveDroplet(Graphics2D g, int[] src, int W, int H,
                                                double cx, double cy, double r, double alpha) {
        if (alpha <= 0 || r < 1.5) return;
        int ir = (int) Math.ceil(r) + 1;
        int xMin = Math.max(0, (int) (cx - ir));
        int xMax = Math.min(W - 1, (int) (cx + ir));
        int yMin = Math.max(0, (int) (cy - ir));
        int yMax = Math.min(H - 1, (int) (cy + ir));
        if (xMin > xMax || yMin > yMax) return;

        int dropW = xMax - xMin + 1;
        int dropH = yMax - yMin + 1;
        int[] dst = new int[dropW * dropH];

        double hlNX = -0.45, hlNY = -0.45;

        for (int yy = 0; yy < dropH; yy++) {
            int py = yMin + yy;
            double dyC = py - cy;
            for (int xx = 0; xx < dropW; xx++) {
                int px = xMin + xx;
                double dxC = px - cx;
                double dist2 = dxC * dxC + dyC * dyC;
                if (dist2 > r * r) continue;
                double dist = Math.sqrt(dist2);
                double nd = dist / r;

                double bend = 1.0 - 0.55 * Math.pow(nd, 1.6);
                double sxD = cx + dxC * bend;
                double syD = cy + dyC * bend;
                int sx = (int) sxD;
                int sy = (int) syD;
                if (sx < 0) sx = 0; else if (sx > W - 1) sx = W - 1;
                if (sy < 0) sy = 0; else if (sy > H - 1) sy = H - 1;
                int sp = src[sy * W + sx];
                int rC = (sp >> 16) & 0xFF;
                int gC = (sp >> 8) & 0xFF;
                int bC = sp & 0xFF;

                rC = Math.min(255, rC - 4);
                gC = Math.min(255, gC + 4);
                bC = Math.min(255, bC + 12);

                double rim = Math.pow(nd, 5);
                rC = (int) (rC * (1.0 - rim * 0.45));
                gC = (int) (gC * (1.0 - rim * 0.45));
                bC = (int) (bC * (1.0 - rim * 0.40));

                double nxRel = dxC / r;
                double nyRel = dyC / r;
                double hldx = nxRel - hlNX;
                double hldy = nyRel - hlNY;
                double hl = Math.max(0, 1.0 - Math.sqrt(hldx * hldx + hldy * hldy) * 4.5);
                hl = hl * hl * hl;
                double hldx2 = nxRel - 0.30;
                double hldy2 = nyRel - 0.05;
                double hl2 = Math.max(0, 1.0 - Math.sqrt(hldx2 * hldx2 + hldy2 * hldy2) * 9.0);
                hl2 = hl2 * hl2 * 0.6;
                int boost = (int) ((hl + hl2) * 230);
                rC = Math.min(255, rC + boost);
                gC = Math.min(255, gC + boost);
                bC = Math.min(255, bC + boost);

                double ea = 1.0;
                if (nd > 0.92) ea = (1.0 - nd) / 0.08;
                if (ea < 0) ea = 0;
                int aCh = (int) (255 * alpha * ea);
                if (aCh <= 0) continue;
                if (rC < 0) rC = 0; if (gC < 0) gC = 0; if (bC < 0) bC = 0;
                dst[yy * dropW + xx] = (aCh << 24) | (rC << 16) | (gC << 8) | bC;
            }
        }

        BufferedImage drop = new BufferedImage(dropW, dropH, BufferedImage.TYPE_INT_ARGB);
        drop.setRGB(0, 0, dropW, dropH, dst, 0, dropW);
        g.drawImage(drop, xMin, yMin, null);
    }

    private static void drawSparkle(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (110 * density);
        int life = 36;
        for (int i = 0; i < N; i++) {
            double maxSize = (5.0 + prand(i, 1) * 10.0) * s;
            double x = prand(i, 2) * W;
            double y = prand(i, 3) * H;
            int phase = (int) (prand(i, 4) * (life * 4));
            int cycle = life * 4;
            int frameInCycle = ((t + phase) % cycle + cycle) % cycle;
            if (frameInCycle >= life) continue;
            double lifeT = frameInCycle / (double) life;
            double pulse = Math.sin(lifeT * Math.PI);
            double size = maxSize * pulse;
            if (size < 0.5) continue;
            int hue = (int) (prand(i, 5) * 3);
            Color core;
            switch (hue) {
                case 0: core = new Color(255, 250, 210); break;
                case 1: core = new Color(220, 240, 255); break;
                default: core = new Color(255, 255, 255); break;
            }
            int alpha = (int) (pulse * 240);
            float thickness = (float) Math.max(1.0, size * 0.18);
            Stroke os = g.getStroke();
            g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(core.getRed(), core.getGreen(), core.getBlue(), alpha));
            g.drawLine((int) (x - size), (int) y, (int) (x + size), (int) y);
            g.drawLine((int) x, (int) (y - size), (int) x, (int) (y + size));
            float diag = (float) (size * 0.5);
            g.setStroke(new BasicStroke(thickness * 0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine((int) (x - diag), (int) (y - diag), (int) (x + diag), (int) (y + diag));
            g.drawLine((int) (x - diag), (int) (y + diag), (int) (x + diag), (int) (y - diag));
            g.setColor(new Color(255, 255, 255, alpha));
            g.fillOval((int) (x - size * 0.25), (int) (y - size * 0.25),
                    (int) (size * 0.5), (int) (size * 0.5));
            g.setStroke(os);
        }
    }

    private static void drawFireflies(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (60 * density);
        int life = 240;
        for (int i = 0; i < N; i++) {
            double size = (3.0 + prand(i, 1) * 5.0) * s;
            double bx = prand(i, 2) * W;
            double by = prand(i, 3) * H;
            int phase = (int) (prand(i, 4) * life);
            int frameInLife = ((t + phase) % life + life) % life;
            double lifeT = frameInLife / (double) life;
            double brightness = 0.5 + 0.5 * Math.sin(lifeT * Math.PI * 2.0 + i * 0.7);
            double driftSpeed = (0.10 + prand(i, 5) * 0.20) * s;
            double driftAngle = prand(i, 6) * Math.PI * 2.0;
            double driftX = Math.cos(driftAngle) * driftSpeed * t;
            double driftY = (Math.sin(driftAngle) - 0.4) * driftSpeed * t;
            double x = ((bx + driftX) % (W + 80) + (W + 80)) % (W + 80) - 40;
            double y = ((by + driftY) % (H + 80) + (H + 80)) % (H + 80) - 40;
            int alpha = (int) (brightness * 230);
            if (alpha < 8) continue;

            float fr = (float) (size * 4.0);
            if (fr < 1.5f) continue;
            RadialGradientPaint rgp = new RadialGradientPaint(
                    (float) x, (float) y, fr,
                    new float[]{0f, 0.4f, 1f},
                    new Color[]{
                            new Color(220, 255, 140, (int) (alpha * 0.55)),
                            new Color(190, 230, 110, (int) (alpha * 0.18)),
                            new Color(170, 220, 100, 0)
                    });
            g.setPaint(rgp);
            g.fill(new Ellipse2D.Double(x - fr, y - fr, fr * 2, fr * 2));
            g.setColor(new Color(255, 255, 200, alpha));
            g.fillOval((int) (x - size / 2), (int) (y - size / 2), (int) size, (int) size);
        }
    }

    private static void drawBubbles(Graphics2D g, int W, int H, double density, double s, int t) {
        int N = (int) (90 * density);
        double cycle = H + 120 * s;
        for (int i = 0; i < N; i++) {
            double speed = (1.2 + prand(i, 1) * 2.0) * s;
            double size = (6.0 + prand(i, 2) * 22.0) * s;
            double baseX = prand(i, 3) * (W + 80 * s) - 40 * s;
            double swayA = (4.0 + prand(i, 4) * 12.0) * s;
            double swayF = 0.04 + prand(i, 5) * 0.06;
            double phase = prand(i, 6) * cycle;
            double y = (cycle - ((phase + t * speed) % cycle)) - 60 * s;
            double x = baseX + swayA * Math.sin(t * swayF + i * 0.6);
            int alpha = 110 + (int) (prand(i, 7) * 80);
            g.setColor(new Color(190, 220, 240, Math.min(180, alpha) / 3));
            g.fillOval((int) (x - size / 2), (int) (y - size / 2), (int) size, (int) size);
            Stroke os = g.getStroke();
            g.setStroke(new BasicStroke((float) Math.max(1.0, size * 0.06)));
            g.setColor(new Color(220, 240, 255, Math.min(220, alpha)));
            g.drawOval((int) (x - size / 2), (int) (y - size / 2), (int) size, (int) size);
            g.setStroke(os);
            double hx = x - size * 0.22;
            double hy = y - size * 0.28;
            double hs = size * 0.30;
            g.setColor(new Color(255, 255, 255, Math.min(230, alpha + 30)));
            g.fillOval((int) (hx - hs / 2), (int) (hy - hs / 2), (int) hs, (int) hs * 1 / 2);
        }
    }

    private static final Color[] LEAK_COLORS = {
            new Color(255, 130, 60),
            new Color(255, 80, 110),
            new Color(255, 200, 80),
            new Color(120, 80, 200),
            new Color(70, 180, 255)
    };

    private static void drawLightLeaks(Graphics2D g, int W, int H, double density, double s, int t) {
        int streaks = 1 + (int) Math.round(density * 1.5);
        for (int i = 0; i < streaks; i++) {
            double cycleFrames = 240 + i * 60;
            double cyclePhase = ((t + i * 80) % cycleFrames) / cycleFrames;
            double angle = (prand(i, 1) - 0.5) * Math.PI * 0.5 + Math.PI * 0.25;
            double diag = Math.sqrt(W * W + H * H);
            double progress = cyclePhase * 1.4 - 0.2;
            double cx = W / 2.0 + (progress - 0.5) * diag * Math.cos(angle);
            double cy = H / 2.0 + (progress - 0.5) * diag * Math.sin(angle);
            Color col = LEAK_COLORS[(int) (prand(i, 2) * LEAK_COLORS.length) % LEAK_COLORS.length];
            float bandW = (float) (Math.min(W, H) * (0.45 + prand(i, 3) * 0.35));
            double envelope;
            if (cyclePhase < 0.15) envelope = cyclePhase / 0.15;
            else if (cyclePhase > 0.85) envelope = (1.0 - cyclePhase) / 0.15;
            else envelope = 1.0;
            envelope = Math.max(0, Math.min(1, envelope));
            int peakAlpha = (int) (density * 160 * envelope);
            if (peakAlpha < 6) continue;

            double nx = Math.sin(angle);
            double ny = -Math.cos(angle);
            float gx1 = (float) (cx - nx * bandW);
            float gy1 = (float) (cy - ny * bandW);
            float gx2 = (float) (cx + nx * bandW);
            float gy2 = (float) (cy + ny * bandW);
            LinearGradientPaint lgp = new LinearGradientPaint(
                    gx1, gy1, gx2, gy2,
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{
                            new Color(col.getRed(), col.getGreen(), col.getBlue(), 0),
                            new Color(col.getRed(), col.getGreen(), col.getBlue(), peakAlpha),
                            new Color(col.getRed(), col.getGreen(), col.getBlue(), 0)
                    });
            Composite oc = g.getComposite();
            g.setComposite(AlphaComposite.SrcOver.derive(0.85f));
            g.setPaint(lgp);
            g.fillRect(0, 0, W, H);
            g.setComposite(oc);
        }
    }

    private static void drawLensFlare(Graphics2D g, int W, int H, double density, double s, int t) {
        double driftX = Math.cos(t * 0.012) * W * 0.12;
        double driftY = Math.sin(t * 0.009) * H * 0.10;
        double sunX = W * 0.78 + driftX;
        double sunY = H * 0.22 + driftY;
        double centerX = W / 2.0;
        double centerY = H / 2.0;

        double streakLen = W * (0.7 + density * 0.3);
        double streakH   = Math.max(2, H * 0.012 * (1 + density));
        Composite oc = g.getComposite();
        LinearGradientPaint streakPaint = new LinearGradientPaint(
                (float) (sunX - streakLen / 2), (float) sunY,
                (float) (sunX + streakLen / 2), (float) sunY,
                new float[]{0f, 0.5f, 1f},
                new Color[]{
                        new Color(120, 180, 255, 0),
                        new Color(180, 220, 255, (int) (density * 200)),
                        new Color(120, 180, 255, 0)
                });
        g.setPaint(streakPaint);
        g.fill(new Ellipse2D.Double(
                sunX - streakLen / 2, sunY - streakH / 2,
                streakLen, streakH));

        float coreR = (float) (Math.min(W, H) * (0.10 + density * 0.10));
        if (coreR > 2) {
            RadialGradientPaint sun = new RadialGradientPaint(
                    (float) sunX, (float) sunY, coreR,
                    new float[]{0f, 0.35f, 1f},
                    new Color[]{
                            new Color(255, 245, 220, (int) (density * 230)),
                            new Color(255, 210, 150, (int) (density * 100)),
                            new Color(255, 200, 130, 0)
                    });
            g.setPaint(sun);
            g.fill(new Ellipse2D.Double(sunX - coreR, sunY - coreR, coreR * 2, coreR * 2));
        }

        double oppX = 2 * centerX - sunX;
        double oppY = 2 * centerY - sunY;
        int ghosts = 6;
        Color[] ghostColors = {
                new Color(255, 200, 180),
                new Color(180, 220, 255),
                new Color(220, 200, 255),
                new Color(255, 240, 200),
                new Color(200, 255, 230),
                new Color(255, 180, 200)
        };
        for (int i = 0; i < ghosts; i++) {
            double frac = (i + 1) / (double) (ghosts + 1);
            double gx = sunX + (oppX - sunX) * (frac * 1.4 - 0.2);
            double gy = sunY + (oppY - sunY) * (frac * 1.4 - 0.2);
            float gr = (float) (Math.min(W, H) * (0.025 + 0.04 * Math.abs(0.5 - frac)));
            int gAlpha = (int) (density * (110 - i * 12));
            if (gAlpha < 6 || gr < 1) continue;
            Color gc = ghostColors[i % ghostColors.length];
            RadialGradientPaint ghp = new RadialGradientPaint(
                    (float) gx, (float) gy, gr,
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{
                            new Color(gc.getRed(), gc.getGreen(), gc.getBlue(), gAlpha),
                            new Color(gc.getRed(), gc.getGreen(), gc.getBlue(), gAlpha / 3),
                            new Color(gc.getRed(), gc.getGreen(), gc.getBlue(), 0)
                    });
            g.setPaint(ghp);
            g.fill(new Ellipse2D.Double(gx - gr, gy - gr, gr * 2, gr * 2));
        }
        g.setComposite(oc);
    }

    // ==================== Existing image-transform effects ====================

    private static void applyWaterWaves(BufferedImage frame, int W, int H, int fxOther, int t) {
        double strength = fxOther / 100.0;
        int[] src = frame.getRGB(0, 0, W, H, null, 0, W);
        int[] dst = new int[src.length];

        double amp = 1.5 + 6.5 * strength;
        double waveLen1 = Math.max(60.0, H * 0.22);
        double waveLen2 = Math.max(80.0, H * 0.31);
        double f1 = 2.0 * Math.PI / waveLen1;
        double f2 = 2.0 * Math.PI / waveLen2;
        double phase1 = t * 0.14;
        double phase2 = t * 0.10;

        for (int y = 0; y < H; y++) {
            double rowSinA = Math.sin(f1 * y + phase1);
            double rowCosB = Math.cos(f2 * y * 0.7 + phase2 * 1.1);
            int rowOff = y * W;
            for (int x = 0; x < W; x++) {
                double xOff = amp * rowSinA
                            + amp * 0.45 * Math.sin(f2 * x * 1.3 + phase2);
                double yOff = amp * 0.55 * Math.cos(f1 * x + phase1 * 1.2)
                            + amp * 0.30 * rowCosB;
                int sx = x + (int) xOff;
                int sy = y + (int) yOff;
                if (sx < 0) sx = 0; else if (sx > W - 1) sx = W - 1;
                if (sy < 0) sy = 0; else if (sy > H - 1) sy = H - 1;
                dst[rowOff + x] = src[sy * W + sx];
            }
        }

        frame.setRGB(0, 0, W, H, dst, 0, W);
    }

    private static void applyWaterWaves2(BufferedImage frame, int W, int H, int fxOther, int t) {
        double strength = fxOther / 100.0;
        int[] src = frame.getRGB(0, 0, W, H, null, 0, W);
        int[] dst = new int[src.length];

        double amp1 = 4.0 + 10.0 * strength;
        double amp2 = 2.0 + 5.5 * strength;
        double amp3 = 1.0 + 2.5 * strength;
        double waveLen1 = Math.max(180, W * 0.45);
        double waveLen2 = Math.max(90,  W * 0.22);
        double waveLen3 = Math.max(40,  W * 0.10);
        double speed1 = waveLen1 / 110.0;
        double speed2 = waveLen2 / 80.0;
        double speed3 = waveLen3 / 50.0;
        double k1 = 2.0 * Math.PI / waveLen1;
        double k2 = 2.0 * Math.PI / waveLen2;
        double k3 = 2.0 * Math.PI / waveLen3;
        double d1x =  1.00, d1y =  0.00;
        double d2x =  0.93, d2y = -0.36;
        double d3x =  0.86, d3y =  0.51;

        for (int y = 0; y < H; y++) {
            int rowOff = y * W;
            for (int x = 0; x < W; x++) {
                double p1 = k1 * (x * d1x + y * d1y) - speed1 * t * k1;
                double p2 = k2 * (x * d2x + y * d2y) - speed2 * t * k2;
                double p3 = k3 * (x * d3x + y * d3y) - speed3 * t * k3;

                double s1 = Math.sin(p1);
                double s2 = Math.sin(p2);
                double s3 = Math.sin(p3);
                double sk1 = s1 + 0.35 * s1 * Math.abs(s1);
                double sk2 = s2 + 0.25 * s2 * Math.abs(s2);

                double yOff = amp1 * sk1 + amp2 * sk2 + amp3 * s3;
                double c1 = Math.cos(p1), c2 = Math.cos(p2), c3 = Math.cos(p3);
                double xOff = amp1 * 0.65 * c1 * d1x
                            + amp2 * 0.65 * c2 * d2x
                            + amp3 * 0.55 * c3 * d3x;
                yOff +=       amp1 * 0.20 * c1 * d1y
                            + amp2 * 0.30 * c2 * d2y
                            + amp3 * 0.25 * c3 * d3y;

                int sx = x + (int) xOff;
                int sy = y + (int) yOff;
                if (sx < 0) sx = 0; else if (sx > W - 1) sx = W - 1;
                if (sy < 0) sy = 0; else if (sy > H - 1) sy = H - 1;
                dst[rowOff + x] = src[sy * W + sx];
            }
        }
        frame.setRGB(0, 0, W, H, dst, 0, W);
    }

    private static void applyHeatHaze(BufferedImage frame, int W, int H, int fxOther, int t) {
        double strength = fxOther / 100.0;
        int[] src = frame.getRGB(0, 0, W, H, null, 0, W);
        int[] dst = new int[src.length];

        double amp = 1.5 + 5.5 * strength;
        double waveLenY = Math.max(20, H * 0.05);
        double waveLenX = Math.max(35, W * 0.07);
        double kY = 2.0 * Math.PI / waveLenY;
        double kX = 2.0 * Math.PI / waveLenX;
        double phase = t * 0.45;

        for (int y = 0; y < H; y++) {
            double tFrac = y / (double) H;
            double envelope;
            if (tFrac < 0.30) envelope = 0;
            else envelope = (tFrac - 0.30) / 0.70;
            envelope = envelope * envelope;
            int rowOff = y * W;
            for (int x = 0; x < W; x++) {
                double xOff = envelope * amp * Math.sin(kY * y + kX * x * 0.4 + phase);
                double yOff = envelope * amp * 0.35 * Math.cos(kX * x + phase * 1.3);
                int sx = x + (int) xOff;
                int sy = y + (int) yOff;
                if (sx < 0) sx = 0; else if (sx > W - 1) sx = W - 1;
                if (sy < 0) sy = 0; else if (sy > H - 1) sy = H - 1;
                dst[rowOff + x] = src[sy * W + sx];
            }
        }
        frame.setRGB(0, 0, W, H, dst, 0, W);
    }
}
