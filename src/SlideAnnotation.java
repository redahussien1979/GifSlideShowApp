import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A single animated vector annotation (Line or Arrow for Phase 1) that is drawn
 * on top of an already-rendered frame. Self-contained so the main app only wires
 * it in — mirrors how {@code QuizSlide.applyOverlay(...)} composites onto a frame.
 *
 * <p>Every annotation carries its own timing ({@code appearMs}/{@code durationMs}),
 * position (as % of frame), size, colour, and a two-layer animation model:
 * a one-shot {@code entrance} plus an optional continuous {@code idle} loop.
 * Animation is driven by real elapsed time (ms) so it is frame-rate independent.
 */
public class SlideAnnotation {

    public static final String KIND_LINE = "Line";
    public static final String KIND_ARROW = "Arrow";
    public static final String KIND_SHAPE = "Shape";

    /** How long the entrance animation runs, in ms. */
    private static final double ENTRANCE_MS = 650.0;

    // ---- model -------------------------------------------------------------
    public String kind = KIND_ARROW;
    public String subtype = "Straight";
    public double xPct = 50;          // center X as % of frame width
    public double yPct = 50;          // center Y as % of frame height
    public double sizePct = 22;       // WIDTH (Line/Arrow length) as % of frame width
    public double heightPct = 0;      // Shape HEIGHT as % of frame width; 0 = auto/natural
    public double rotationDeg = 0;    // 0 = pointing right; grows clockwise
    public Color color = new Color(255, 76, 76);
    public Color color2 = new Color(255, 186, 60);
    public boolean gradient = false;
    public double strokeWidthPct = 0.55;  // stroke width as % of min(frame w,h)
    public boolean shadow = true;
    public boolean filled = true;         // Shape kind: fill (true) vs outline (false)
    public double cornerPct = 0;          // vertex rounding, % of shape min-dim (0 = sharp)
    public Color borderColor = null;      // Shape: outline drawn on top of fill (null = none)
    public double borderWidthPct = 0;     // border thickness, % of min(frame w,h); 0 = none
    public int opacity = 100;             // 0..100 overall opacity
    public int appearMs = 0;
    public int durationMs = 0;        // 0 = stay until the slide ends
    public String entrance = "Draw-In";
    public String idle = "None";
    public int groupId = -1;          // reserved for grouping (Phase later)
    public String text = "";          // reserved for callouts (Phase later)

    public SlideAnnotation() {}

    public SlideAnnotation copy() {
        SlideAnnotation a = new SlideAnnotation();
        a.kind = kind; a.subtype = subtype; a.xPct = xPct; a.yPct = yPct;
        a.sizePct = sizePct; a.heightPct = heightPct; a.rotationDeg = rotationDeg; a.color = color;
        a.color2 = color2; a.gradient = gradient; a.strokeWidthPct = strokeWidthPct;
        a.shadow = shadow; a.appearMs = appearMs; a.durationMs = durationMs;
        a.entrance = entrance; a.idle = idle; a.groupId = groupId; a.text = text;
        a.filled = filled; a.cornerPct = cornerPct; a.borderColor = borderColor;
        a.borderWidthPct = borderWidthPct; a.opacity = opacity;
        return a;
    }

    // ---- catalogs (for UI dropdowns) --------------------------------------
    public static String[] kinds()          { return new String[]{KIND_LINE, KIND_ARROW, KIND_SHAPE}; }
    public static String[] lineSubtypes()   { return new String[]{"Straight", "Dashed", "Dotted", "Double", "Underline", "Divider"}; }
    public static String[] arrowSubtypes()  { return new String[]{"Straight", "Curved", "Hand-Drawn", "Double", "Block"}; }
    public static String[] shapeSubtypes()  { return new String[]{
            // rounded / basic
            "Circle", "Ring", "Rectangle", "Squircle", "Pill", "Blob", "Teardrop",
            // polygons
            "Triangle", "Diamond", "Pentagon", "Hexagon", "Octagon", "Chevron",
            // stars
            "Star", "Star 4", "Star 6", "Star 8", "Rounded Star", "Sparkle", "Burst",
            // hearts
            "Heart", "Heart Plump",
            // symbols
            "Check", "Cross", "Plus", "Lightning", "Crown", "Shield", "Pin", "Gear",
            // banners / seals
            "Badge", "Rosette", "Banner", "Sunburst" }; }
    public static String[] subtypesFor(String kind) {
        if (KIND_LINE.equals(kind)) return lineSubtypes();
        if (KIND_SHAPE.equals(kind)) return shapeSubtypes();
        return arrowSubtypes();
    }
    public static String[] entranceModes()  { return new String[]{"None", "Draw-In", "Pop", "Fly-In", "Fade", "Grow"}; }
    public static String[] idleModes()      { return new String[]{"None", "Pulse", "Bob", "Spin", "Wobble", "Glow"}; }

    // ---- compositing entry point ------------------------------------------
    /**
     * Draw every visible annotation onto {@code frame}. When {@code previewAllVisible}
     * is true (editor preview) timing is ignored and each shape is shown at its
     * settled final state so it can be positioned/styled.
     */
    public static void paintAll(BufferedImage frame, List<SlideAnnotation> anns,
                                long elapsedMs, boolean previewAllVisible) {
        if (frame == null || anns == null || anns.isEmpty()) return;
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        try {
            for (SlideAnnotation a : anns) {
                if (a == null) continue;
                a.paintOne(g, frame.getWidth(), frame.getHeight(), elapsedMs, previewAllVisible);
            }
        } finally {
            g.dispose();
        }
    }

    /** True if this annotation is on-screen at {@code elapsedMs}. */
    public boolean visibleAt(long elapsedMs) {
        if (elapsedMs < appearMs) return false;
        if (durationMs > 0 && elapsedMs > (long) appearMs + durationMs) return false;
        return true;
    }

    // ---- per-annotation drawing -------------------------------------------
    private void paintOne(Graphics2D gRoot, int W, int H, long elapsedMs, boolean preview) {
        if (!preview && !visibleAt(elapsedMs)) return;

        double localMs = preview ? ENTRANCE_MS : Math.max(0, elapsedMs - appearMs);
        double ep = preview ? 1.0 : clamp(localMs / ENTRANCE_MS, 0, 1);   // entrance progress 0..1
        double revealT = 1.0;                                             // draw-in fraction
        double entScale = 1.0;
        double entAlpha = 1.0;
        double flyDx = 0, flyDy = 0;

        switch (entrance == null ? "None" : entrance) {
            case "Draw-In": revealT = easeOut(ep); break;
            case "Pop":     entScale = easeOutBack(ep); break;
            case "Grow":    entScale = easeOut(ep); break;
            case "Fade":    entAlpha = ep; break;
            case "Fly-In":  double d = (1 - easeOut(ep)) * (0.35 * W); flyDx = -d; flyDy = -d * 0.15; break;
            default: break; // None
        }

        // continuous idle loop
        double idleScale = 1.0, idleRot = 0, idleDy = 0, glow = 0;
        double phase = (elapsedMs % 1800L) / 1800.0 * 2 * Math.PI;
        switch (idle == null ? "None" : idle) {
            case "Pulse":  idleScale = 1 + 0.06 * Math.sin(phase); break;
            case "Bob":    idleDy = 0.012 * H * Math.sin(phase); break;
            case "Spin":   idleRot = (elapsedMs % 4000L) / 4000.0 * 360.0; break;
            case "Wobble": idleRot = 7 * Math.sin(phase); break;
            case "Glow":   glow = 0.5 + 0.5 * Math.sin(phase); break;
            default: break;
        }

        double cx = xPct / 100.0 * W + flyDx;
        double cy = yPct / 100.0 * H + flyDy + idleDy;
        double width = Math.max(6, sizePct / 100.0 * W);
        double height = heightPct > 0 ? Math.max(6, heightPct / 100.0 * W) : naturalHeight(width);
        double sw = Math.max(2.0, strokeWidthPct / 100.0 * Math.min(W, H) * entScale * idleScale);
        double borderPx = (borderColor != null && borderWidthPct > 0)
                ? Math.max(1.0, borderWidthPct / 100.0 * Math.min(W, H) * entScale * idleScale) : 0;

        Graphics2D g = (Graphics2D) gRoot.create();
        try {
            double finalAlpha = clamp(entAlpha * (opacity / 100.0), 0, 1);
            if (finalAlpha < 1.0) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) finalAlpha));
            }
            AffineTransform tf = new AffineTransform();
            tf.translate(cx, cy);
            tf.rotate(Math.toRadians(rotationDeg + idleRot));
            tf.scale(entScale * idleScale, entScale * idleScale);
            g.transform(tf);

            // Local frame: shape spans [-halfW..halfW] × [-halfH..halfH], centered at origin.
            double halfW = width / 2.0;
            double halfH = height / 2.0;

            if (shadow || glow > 0) {
                Graphics2D sg = (Graphics2D) g.create();
                float sa = (float) (glow > 0 ? 0.25 + 0.45 * glow : 0.28);
                sg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, sa)));
                sg.translate(glow > 0 ? 0 : sw * 0.5, glow > 0 ? 0 : sw * 0.6);
                sg.setColor(new Color(0, 0, 0));
                drawShape(sg, halfW, halfH, sw * (glow > 0 ? 1.7 : 1.0), revealT, 0);
                sg.dispose();
            }

            if (gradient) {
                g.setPaint(new GradientPaint((float) -halfW, 0, color, (float) halfW, 0, color2));
            } else {
                g.setPaint(color);
            }
            drawShape(g, halfW, halfH, sw, revealT, borderPx);
        } finally {
            g.dispose();
        }
    }

    /** Natural (auto) height for the current subtype when heightPct==0. */
    private double naturalHeight(double width) {
        if (!KIND_SHAPE.equals(kind)) return width;
        switch (subtype == null ? "" : subtype) {
            case "Rectangle": case "Pill": case "Banner": return width * 0.60;
            case "Chevron":   return width * 0.85;
            case "Shield": case "Pin": case "Teardrop":   return width * 1.15;
            default: return width;
        }
    }

    /** Draw the shape in local space, bounded by [-halfW..halfW] × [-halfH..halfH]. */
    private void drawShape(Graphics2D g, double halfW, double halfH, double sw, double revealT, double borderPx) {
        if (KIND_LINE.equals(kind)) {
            drawLine(g, halfW, sw, revealT);          // 1-D: height not used
        } else if (KIND_SHAPE.equals(kind)) {
            drawDecoShape(g, halfW, halfH, sw, revealT, borderPx);
        } else {
            drawArrow(g, halfW, sw, revealT);          // 1-D: height not used
        }
    }

    // ----- decorative shapes -----------------------------------------------
    // For closed shapes revealT is interpreted as a grow-from-centre factor so a
    // "Draw-In" entrance blossoms outward; Pop/Grow/Fly-In/Fade compose on top.
    private void drawDecoShape(Graphics2D g, double halfW, double halfH, double sw, double revealT, double borderPx) {
        double t = Math.max(0.0001, revealT);
        double rx = Math.max(0.5, halfW * t);
        double ry = Math.max(0.5, halfH * t);
        double mn = Math.min(rx, ry);
        double corner = cornerPct / 100.0 * mn;   // vertex-rounding radius in px
        String st = subtype == null ? "Circle" : subtype;

        java.awt.Shape shp = ellipse(rx, ry);   // default; overwritten below
        java.awt.Shape innerDetail = null;   // e.g. medal inner ring / pin hole
        boolean forceOutline = false;
        double[][] pts = null;               // polygon/star families (roundable)

        switch (st) {
            case "Ring":
                shp = ellipse(rx, ry); forceOutline = true; sw = Math.max(sw, mn * 0.28); break;
            case "Rectangle":
                shp = rounded(rx, ry, cornerPct > 0 ? corner : mn * 0.28); break;
            case "Squircle":
                shp = rounded(rx, ry, mn * 0.55); break;
            case "Pill":
                shp = rounded(rx, ry, Math.min(rx, ry)); break;
            case "Blob":
                shp = blobPath(rx, ry); break;
            case "Teardrop":
                shp = teardropPath(rx, ry); break;
            case "Triangle": pts = regularPoly(3, rx, ry, -90); break;
            case "Diamond":  pts = regularPoly(4, rx, ry, -90); break;
            case "Pentagon": pts = regularPoly(5, rx, ry, -90); break;
            case "Hexagon":  pts = regularPoly(6, rx, ry, 0);   break;
            case "Octagon":  pts = regularPoly(8, rx, ry, 22.5);break;
            case "Chevron":  shp = chevronPath(rx, ry); break;
            case "Star":     pts = starPts(5, rx, ry, 0.42); break;
            case "Star 4":   pts = starPts(4, rx, ry, 0.42); break;
            case "Star 6":   pts = starPts(6, rx, ry, 0.50); break;
            case "Star 8":   pts = starPts(8, rx, ry, 0.55); break;
            case "Rounded Star": pts = starPts(5, rx, ry, 0.45); corner = Math.max(corner, mn * 0.16); break;
            case "Sparkle":  pts = starPts(4, rx, ry, 0.16); break;
            case "Burst":    pts = starPts(10, rx, ry, 0.55); break;
            case "Heart":       shp = heartPath(rx, ry, false); break;
            case "Heart Plump": shp = heartPath(rx, ry, true);  break;
            case "Check":    pts = checkPts(rx, ry); break;
            case "Cross":    pts = plusPts(rx, ry, 0.34, 45); break;
            case "Plus":     pts = plusPts(rx, ry, 0.34, 0);  break;
            case "Lightning":shp = boltPath(rx, ry); break;
            case "Crown":    shp = crownPath(rx, ry); break;
            case "Shield":   shp = shieldPath(rx, ry); break;
            case "Pin":      shp = pinPath(rx, ry); innerDetail =
                    new java.awt.geom.Ellipse2D.Double(-rx * 0.32, -ry * 0.5 - rx * 0.32,
                            rx * 0.64, rx * 0.64); break;
            case "Gear":     shp = gearPath(rx, ry); innerDetail =
                    new java.awt.geom.Ellipse2D.Double(-mn * 0.28, -mn * 0.28, mn * 0.56, mn * 0.56); break;
            case "Badge":    pts = starPts(12, rx, ry, 0.80);
                    innerDetail = new java.awt.geom.Ellipse2D.Double(-rx * 0.6, -ry * 0.6, rx * 1.2, ry * 1.2); break;
            case "Rosette":  pts = starPts(16, rx, ry, 0.82);
                    innerDetail = new java.awt.geom.Ellipse2D.Double(-rx * 0.55, -ry * 0.55, rx * 1.1, ry * 1.1); break;
            case "Banner":   shp = bannerPath(rx, ry); break;
            case "Sunburst": pts = starPts(16, rx, ry, 0.35); break;
            default:         shp = ellipse(rx, ry); break;   // Circle
        }
        if (pts != null) shp = (corner > 0.5) ? roundedPath(pts, corner) : polyToPath(pts);

        if (filled && !forceOutline) {
            g.fill(shp);
            // subtle white inner ring for medal/seal/gear/pin
            if (innerDetail != null) {
                Graphics2D ig = (Graphics2D) g.create();
                boolean hole = "Pin".equals(st) || "Gear".equals(st);
                if (hole) { ig.setColor(new Color(255, 255, 255, 235)); ig.fill(innerDetail); }
                else { ig.setStroke(roundStroke(Math.max(1.5, sw * 0.8)));
                       ig.setColor(new Color(255, 255, 255, 150)); ig.draw(innerDetail); }
                ig.dispose();
            }
            // outline + fill: draw the border on top of the fill
            if (borderColor != null && borderPx > 0) {
                Graphics2D bg = (Graphics2D) g.create();
                bg.setStroke(roundStroke(borderPx));
                bg.setColor(borderColor);
                bg.draw(shp);
                bg.dispose();
            }
        } else {
            g.setStroke(roundStroke(sw));
            g.draw(shp);
        }
    }

    // ---- geometry helpers --------------------------------------------------
    private static java.awt.Shape ellipse(double rx, double ry) {
        return new java.awt.geom.Ellipse2D.Double(-rx, -ry, 2 * rx, 2 * ry);
    }
    private static java.awt.Shape rounded(double rx, double ry, double arc) {
        arc = Math.max(0, Math.min(arc, Math.min(rx, ry)));
        return new java.awt.geom.RoundRectangle2D.Double(-rx, -ry, 2 * rx, 2 * ry, 2 * arc, 2 * arc);
    }
    private static double[][] regularPoly(int n, double rx, double ry, double offDeg) {
        double[][] p = new double[n][2];
        double off = Math.toRadians(offDeg);
        for (int i = 0; i < n; i++) {
            double a = off + i * 2 * Math.PI / n;
            p[i][0] = Math.cos(a) * rx; p[i][1] = Math.sin(a) * ry;
        }
        return p;
    }
    private static double[][] starPts(int points, double ox, double oy, double innerRatio) {
        double[][] p = new double[points * 2][2];
        double step = Math.PI / points, a = -Math.PI / 2;
        for (int i = 0; i < points * 2; i++) {
            boolean outer = (i % 2 == 0);
            double kx = outer ? ox : ox * innerRatio, ky = outer ? oy : oy * innerRatio;
            p[i][0] = Math.cos(a) * kx; p[i][1] = Math.sin(a) * ky; a += step;
        }
        return p;
    }
    // "+" (or rotated to "×") as a 12-point polygon; arm = arm half-thickness ratio.
    private static double[][] plusPts(double rx, double ry, double arm, double rotDeg) {
        double ax = rx * arm, ay = ry * arm;
        double[][] base = {
                {-ax,-ry},{ax,-ry},{ax,-ay},{rx,-ay},{rx,ay},{ax,ay},
                {ax,ry},{-ax,ry},{-ax,ay},{-rx,ay},{-rx,-ay},{-ax,-ay}};
        if (rotDeg == 0) return base;
        double c = Math.cos(Math.toRadians(rotDeg)), s = Math.sin(Math.toRadians(rotDeg));
        for (double[] q : base) { double x = q[0]*c - q[1]*s, y = q[0]*s + q[1]*c; q[0]=x; q[1]=y; }
        return base;
    }
    private static double[][] checkPts(double rx, double ry) {
        // A tick fitted to the box; thickness scales with the shape.
        double w = Math.min(rx, ry) * 0.42;
        return new double[][]{
                {-rx*0.55, 0.02*ry}, {-rx*0.18, ry*0.55}, {rx*0.62, -ry*0.55},
                {rx*0.62 - w*0.2, -ry*0.55 + w}, {-rx*0.18, ry*0.55 - w*0.6}, {-rx*0.55 + w, 0.02*ry - w*0.2}};
    }
    private static Path2D polyToPath(double[][] pts) {
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < pts.length; i++) {
            if (i == 0) p.moveTo(pts[i][0], pts[i][1]); else p.lineTo(pts[i][0], pts[i][1]);
        }
        p.closePath();
        return p;
    }
    // Round every vertex of a closed polygon by radius r (clamped per-edge).
    private static Path2D roundedPath(double[][] pts, double r) {
        int n = pts.length;
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < n; i++) {
            double[] cur = pts[i], prev = pts[(i - 1 + n) % n], next = pts[(i + 1) % n];
            double d1x = prev[0]-cur[0], d1y = prev[1]-cur[1];
            double d2x = next[0]-cur[0], d2y = next[1]-cur[1];
            double l1 = Math.hypot(d1x, d1y), l2 = Math.hypot(d2x, d2y);
            if (l1 < 1e-6 || l2 < 1e-6) { if (i==0) p.moveTo(cur[0],cur[1]); else p.lineTo(cur[0],cur[1]); continue; }
            double rr = Math.min(r, Math.min(l1, l2) * 0.5);
            double p1x = cur[0] + d1x/l1*rr, p1y = cur[1] + d1y/l1*rr;
            double p2x = cur[0] + d2x/l2*rr, p2y = cur[1] + d2y/l2*rr;
            if (i == 0) p.moveTo(p1x, p1y); else p.lineTo(p1x, p1y);
            p.quadTo(cur[0], cur[1], p2x, p2y);
        }
        p.closePath();
        return p;
    }

    private static Path2D heartPath(double hw, double hh, boolean plump) {
        Path2D.Double p = new Path2D.Double();
        double sx = hw / 8.0, sy = hh / 9.0;
        double lobe = plump ? 13.5 : 11.0;      // how high the lobes bulge
        double wide = plump ? 9.0 : 8.0;
        p.moveTo(0, 6 * sy);
        p.curveTo(-2 * sx, 1 * sy, -wide * sx, -1 * sy, -wide * sx, -6 * sy);
        p.curveTo(-wide * sx, -lobe * sy, -2 * sx, -12 * sy, 0, -7 * sy);
        p.curveTo(2 * sx, -12 * sy, wide * sx, -lobe * sy, wide * sx, -6 * sy);
        p.curveTo(wide * sx, -1 * sy, 2 * sx, 1 * sy, 0, 6 * sy);
        p.closePath();
        return p;
    }
    private static Path2D blobPath(double rx, double ry) {
        double[] mul = {1.0, 0.86, 1.06, 0.9, 1.08, 0.84, 1.02};
        int n = mul.length;
        double[][] pts = new double[n][2];
        for (int i = 0; i < n; i++) {
            double a = -Math.PI/2 + i * 2*Math.PI/n;
            pts[i][0] = Math.cos(a) * rx * mul[i];
            pts[i][1] = Math.sin(a) * ry * mul[i];
        }
        return roundedPath(pts, Math.min(rx, ry) * 0.6);
    }
    private static Path2D teardropPath(double rx, double ry) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0, -ry);
        p.curveTo(rx * 1.1, -ry * 0.2, rx, ry * 0.55, 0, ry);
        p.curveTo(-rx, ry * 0.55, -rx * 1.1, -ry * 0.2, 0, -ry);
        p.closePath();
        return p;
    }
    private static Path2D chevronPath(double rx, double ry) {
        double w = rx * 0.9;
        return polyToPath(new double[][]{
                {-rx, -ry}, {-rx + w, -ry}, {rx, 0}, {-rx + w, ry}, {-rx, ry}, {rx - w, 0}});
    }
    private static Path2D boltPath(double rx, double ry) {
        return polyToPath(new double[][]{
                {rx*0.15,-ry}, {-rx*0.55,ry*0.12}, {-rx*0.05,ry*0.12},
                {-rx*0.25,ry}, {rx*0.55,-ry*0.18}, {rx*0.05,-ry*0.18}});
    }
    private static Path2D crownPath(double rx, double ry) {
        return polyToPath(new double[][]{
                {-rx, ry*0.6}, {-rx, -ry}, {-rx*0.4, -ry*0.05}, {0, -ry*0.9},
                {rx*0.4, -ry*0.05}, {rx, -ry}, {rx, ry*0.6}});
    }
    private static Path2D shieldPath(double rx, double ry) {
        Path2D.Double p = new Path2D.Double();
        double a = rx * 0.32;
        p.moveTo(-rx + a, -ry);
        p.lineTo(rx - a, -ry);
        p.quadTo(rx, -ry, rx, -ry + a);
        p.lineTo(rx, ry * 0.15);
        p.quadTo(rx, ry * 0.75, 0, ry);
        p.quadTo(-rx, ry * 0.75, -rx, ry * 0.15);
        p.lineTo(-rx, -ry + a);
        p.quadTo(-rx, -ry, -rx + a, -ry);
        p.closePath();
        return p;
    }
    private static Path2D pinPath(double rx, double ry) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0, ry);
        p.curveTo(rx * 0.25, ry * 0.25, rx, -ry * 0.15, rx, -ry * 0.5);
        p.curveTo(rx, -ry, -rx, -ry, -rx, -ry * 0.5);
        p.curveTo(-rx, -ry * 0.15, -rx * 0.25, ry * 0.25, 0, ry);
        p.closePath();
        return p;
    }
    private static Path2D gearPath(double rx, double ry) {
        int teeth = 8;
        double outerX = rx, outerY = ry, innerX = rx * 0.74, innerY = ry * 0.74;
        Path2D.Double p = new Path2D.Double();
        double step = 2 * Math.PI / teeth;
        double half = step * 0.28;   // tooth angular half-width
        for (int i = 0; i < teeth; i++) {
            double c = -Math.PI/2 + i * step;
            double[] a = {c - half, c + half, c + step/2 - half, c + step/2 + half};
            double[] xr = {outerX, outerX, innerX, innerX};
            double[] yr = {outerY, outerY, innerY, innerY};
            for (int k = 0; k < 4; k++) {
                double x = Math.cos(a[k]) * xr[k], y = Math.sin(a[k]) * yr[k];
                if (i == 0 && k == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
        }
        p.closePath();
        return p;
    }
    private static Path2D bannerPath(double rx, double ry) {
        double notch = rx * 0.22;
        return polyToPath(new double[][]{
                {-rx, -ry}, {rx, -ry}, {rx - notch, 0}, {rx, ry},
                {-rx, ry}, {-rx + notch, 0}});
    }

    // ----- lines ------------------------------------------------------------
    private void drawLine(Graphics2D g, double half, double sw, double revealT) {
        double x0 = -half, x1 = -half + (2 * half) * revealT;
        switch (subtype == null ? "Straight" : subtype) {
            case "Dashed":
                g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                        10f, new float[]{(float) (sw * 3), (float) (sw * 2.2)}, 0f));
                line(g, x0, 0, x1, 0);
                break;
            case "Dotted":
                g.setStroke(new BasicStroke((float) sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        10f, new float[]{0.1f, (float) (sw * 2.4)}, 0f));
                line(g, x0, 0, x1, 0);
                break;
            case "Double":
                g.setStroke(roundStroke(sw * 0.7));
                line(g, x0, -sw * 0.9, x1, -sw * 0.9);
                line(g, x0, sw * 0.9, x1, sw * 0.9);
                break;
            case "Underline":
                g.setStroke(new BasicStroke((float) (sw * 2.6), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Color c = g.getColor();
                if (c != null) g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(),
                        Math.min(255, (int) (c.getAlpha() * 0.55))));
                line(g, x0, 0, x1, 0);
                break;
            case "Divider":
                g.setStroke(roundStroke(sw));
                double gap = sw * 2.2;
                line(g, x0, 0, Math.min(-gap, x1), 0);
                if (revealT > 0.9) {
                    double r = sw * 1.4;
                    g.fill(new java.awt.geom.Ellipse2D.Double(-r, -r, 2 * r, 2 * r));
                }
                if (x1 > gap) line(g, gap, 0, x1, 0);
                break;
            default: // Straight
                g.setStroke(roundStroke(sw));
                line(g, x0, 0, x1, 0);
                break;
        }
    }

    // ----- arrows -----------------------------------------------------------
    private void drawArrow(Graphics2D g, double half, double sw, double revealT) {
        String st = subtype == null ? "Straight" : subtype;
        double tipX = -half + (2 * half) * revealT;
        double headLen = Math.min(half * 0.9, sw * 3.4);
        double headHalf = sw * 2.0;

        if ("Block".equals(st)) {
            // Solid tapered block arrow (filled polygon), grows with revealT.
            double x1 = tipX;
            double shaftH = sw * 0.9;
            double hL = Math.min((x1 - (-half)) * 0.5, headLen * 1.4);
            double hH = headHalf * 1.3;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(-half, -shaftH);
            p.lineTo(x1 - hL, -shaftH);
            p.lineTo(x1 - hL, -hH);
            p.lineTo(x1, 0);
            p.lineTo(x1 - hL, hH);
            p.lineTo(x1 - hL, shaftH);
            p.lineTo(-half, shaftH);
            p.closePath();
            g.fill(p);
            return;
        }

        if ("Curved".equals(st)) {
            g.setStroke(roundStroke(sw));
            Path2D.Double p = new Path2D.Double();
            p.moveTo(-half, 0);
            // quadratic bow upward; evaluate up to revealT
            double t = revealT;
            double cxp = 0, cyp = -half * 0.6;
            double ex = -half + 2 * half * t;
            // De Casteljau split endpoint at t
            double px = (1 - t) * (1 - t) * (-half) + 2 * (1 - t) * t * cxp + t * t * (half);
            double py = (1 - t) * (1 - t) * 0 + 2 * (1 - t) * t * cyp + t * t * 0;
            double qx = (1 - t) * (-half) + t * cxp;
            double qy = (1 - t) * 0 + t * cyp;
            p.quadTo(qx, qy, px, py);
            g.draw(p);
            if (revealT > 0.55) {
                // tangent at endpoint
                double tx = px - qx, ty = py - qy;
                arrowHead(g, px, py, Math.atan2(ty, tx), headLen, headHalf, revealT);
            }
            return;
        }

        // Straight / Hand-Drawn / Double
        g.setStroke(roundStroke(sw));
        if ("Hand-Drawn".equals(st)) {
            Path2D.Double p = new Path2D.Double();
            p.moveTo(-half, 0);
            int seg = 6;
            for (int i = 1; i <= seg; i++) {
                double f = (double) i / seg;
                double x = -half + (tipX - (-half)) * f;
                double y = Math.sin(f * Math.PI * 2.5) * sw * 0.55 * (1 - f);
                p.lineTo(x, y);
            }
            g.draw(p);
        } else {
            line(g, -half, 0, tipX, 0);
        }
        if (revealT > 0.55) {
            arrowHead(g, tipX, 0, 0, headLen, headHalf, revealT);
            if ("Double".equals(st)) arrowHead(g, -half, 0, Math.PI, headLen, headHalf, revealT);
        }
    }

    private void arrowHead(Graphics2D g, double x, double y, double ang,
                           double len, double half, double revealT) {
        double a = Math.min(1.0, (revealT - 0.55) / 0.45);
        len *= a; half *= a;
        AffineTransform old = g.getTransform();
        g.translate(x, y);
        g.rotate(ang);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0, 0);
        p.lineTo(-len, -half);
        p.lineTo(-len, half);
        p.closePath();
        g.fill(p);
        g.setTransform(old);
    }

    // ----- small helpers ----------------------------------------------------
    private static BasicStroke roundStroke(double w) {
        return new BasicStroke((float) Math.max(1, w), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }
    private static void line(Graphics2D g, double x0, double y0, double x1, double y1) {
        if (x1 <= x0 && Math.abs(y1 - y0) < 0.001) return;
        g.draw(new java.awt.geom.Line2D.Double(x0, y0, x1, y1));
    }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static double easeOut(double t) { t = clamp(t, 0, 1); return 1 - Math.pow(1 - t, 3); }
    private static double easeOutBack(double t) {
        t = clamp(t, 0, 1);
        double c1 = 1.70158, c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    // Unused Point2D import guard (kept for potential curve math extensions).
    @SuppressWarnings("unused")
    private static double dist(Point2D a, Point2D b) { return a.distance(b); }
}
