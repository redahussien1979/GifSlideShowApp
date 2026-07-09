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
        a.filled = filled;
        return a;
    }

    // ---- catalogs (for UI dropdowns) --------------------------------------
    public static String[] kinds()          { return new String[]{KIND_LINE, KIND_ARROW, KIND_SHAPE}; }
    public static String[] lineSubtypes()   { return new String[]{"Straight", "Dashed", "Dotted", "Double", "Underline", "Divider"}; }
    public static String[] arrowSubtypes()  { return new String[]{"Straight", "Curved", "Hand-Drawn", "Double", "Block"}; }
    public static String[] shapeSubtypes()  { return new String[]{"Circle", "Ring", "Rectangle", "Star", "Heart", "Badge"}; }
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
        // Natural height keeps each shape's default proportions when heightPct==0.
        double naturalH = (KIND_SHAPE.equals(kind) && "Rectangle".equals(subtype)) ? width * 0.66 : width;
        double height = heightPct > 0 ? Math.max(6, heightPct / 100.0 * W) : naturalH;
        double sw = Math.max(2.0, strokeWidthPct / 100.0 * Math.min(W, H) * entScale * idleScale);

        Graphics2D g = (Graphics2D) gRoot.create();
        try {
            if (entAlpha < 1.0) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) entAlpha));
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
                drawShape(sg, halfW, halfH, sw * (glow > 0 ? 1.7 : 1.0), revealT, new Color(0, 0, 0));
                sg.dispose();
            }

            if (gradient) {
                g.setPaint(new GradientPaint((float) -halfW, 0, color, (float) halfW, 0, color2));
            } else {
                g.setPaint(color);
            }
            drawShape(g, halfW, halfH, sw, revealT, color);
        } finally {
            g.dispose();
        }
    }

    /** Draw the shape in local space, bounded by [-halfW..halfW] × [-halfH..halfH]. */
    private void drawShape(Graphics2D g, double halfW, double halfH, double sw, double revealT, Color col) {
        if (KIND_LINE.equals(kind)) {
            drawLine(g, halfW, sw, revealT);          // 1-D: height not used
        } else if (KIND_SHAPE.equals(kind)) {
            drawDecoShape(g, halfW, halfH, sw, revealT);
        } else {
            drawArrow(g, halfW, sw, revealT);          // 1-D: height not used
        }
    }

    // ----- decorative shapes -----------------------------------------------
    // For closed shapes revealT is interpreted as a grow-from-centre factor so a
    // "Draw-In" entrance blossoms outward; Pop/Grow/Fly-In/Fade compose on top.
    private void drawDecoShape(Graphics2D g, double halfW, double halfH, double sw, double revealT) {
        double t = Math.max(0.0001, revealT);
        double rx = Math.max(0.5, halfW * t);
        double ry = Math.max(0.5, halfH * t);
        String st = subtype == null ? "Circle" : subtype;
        java.awt.Shape shp;
        boolean forceOutline = false;
        switch (st) {
            case "Ring": {
                // Always an outline ellipse with a bold stroke.
                shp = new java.awt.geom.Ellipse2D.Double(-rx, -ry, 2 * rx, 2 * ry);
                forceOutline = true;
                sw = Math.max(sw, Math.min(halfW, halfH) * 0.28);
                break;
            }
            case "Rectangle": {
                double arc = Math.min(rx, ry) * 0.5;
                shp = new java.awt.geom.RoundRectangle2D.Double(-rx, -ry, 2 * rx, 2 * ry, arc, arc);
                break;
            }
            case "Star":  shp = starPath(rx, ry, rx * 0.42, ry * 0.42, 5); break;
            case "Heart": shp = heartPath(rx, ry); break;
            case "Badge": shp = starPath(rx, ry, rx * 0.80, ry * 0.80, 12); break; // scalloped seal
            default:      shp = new java.awt.geom.Ellipse2D.Double(-rx, -ry, 2 * rx, 2 * ry); break; // Circle
        }
        if (filled && !forceOutline) {
            g.fill(shp);
            if ("Badge".equals(st)) {
                // subtle inner ring for a finished medal look
                Graphics2D ig = (Graphics2D) g.create();
                ig.setStroke(roundStroke(Math.max(1.5, sw * 0.8)));
                ig.setColor(new Color(255, 255, 255, 150));
                double irx = rx * 0.6, iry = ry * 0.6;
                ig.draw(new java.awt.geom.Ellipse2D.Double(-irx, -iry, 2 * irx, 2 * iry));
                ig.dispose();
            }
        } else {
            g.setStroke(roundStroke(sw));
            g.draw(shp);
        }
    }

    // Elliptical star/seal: outer radii (ox,oy), inner radii (ix,iy), N points.
    private static Path2D starPath(double ox, double oy, double ix, double iy, int points) {
        Path2D.Double p = new Path2D.Double();
        double step = Math.PI / points;
        double a = -Math.PI / 2;   // start at top
        for (int i = 0; i < points * 2; i++) {
            boolean outer = (i % 2 == 0);
            double x = Math.cos(a) * (outer ? ox : ix);
            double y = Math.sin(a) * (outer ? oy : iy);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            a += step;
        }
        p.closePath();
        return p;
    }

    // Heart fitted to the box [-hw..hw] × [-hh..hh]. Two lobes + point.
    private static Path2D heartPath(double hw, double hh) {
        Path2D.Double p = new Path2D.Double();
        double sx = hw / 8.0, sy = hh / 9.0;   // model spans x[-8..8], y[-12..6]
        p.moveTo(0, 6 * sy);
        p.curveTo(-2 * sx, 1 * sy, -8 * sx, -1 * sy, -8 * sx, -6 * sy);
        p.curveTo(-8 * sx, -11 * sy, -2 * sx, -12 * sy, 0, -7 * sy);
        p.curveTo(2 * sx, -12 * sy, 8 * sx, -11 * sy, 8 * sx, -6 * sy);
        p.curveTo(8 * sx, -1 * sy, 2 * sx, 1 * sy, 0, 6 * sy);
        p.closePath();
        return p;
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
