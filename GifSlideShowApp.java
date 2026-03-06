
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.*;

public class GifSlideShowApp extends JFrame {

    private static final int GIF_WIDTH = 1920;
    private static final int GIF_HEIGHT = 1080;
    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 360;

    private static final Map<String, Font> loadedFonts = new LinkedHashMap<>();
    private static String[] loadedFontNames = new String[0];
    static {
        // Scan parent directory for .ttf and .otf font files
        File appDir = new File(".").getAbsoluteFile().getParentFile();
        File[] fontFiles = appDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".ttf") || lower.endsWith(".otf");
        });
        if (fontFiles != null) {
            Arrays.sort(fontFiles, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File f : fontFiles) {
                try {
                    Font font = Font.createFont(Font.TRUETYPE_FONT, f);
                    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
                    // Use filename without extension as display name
                    String displayName = f.getName().replaceFirst("\\.[^.]+$", "");
                    loadedFonts.put(displayName, font);
                } catch (Exception e) {
                    System.err.println("Could not load font " + f.getName() + ": " + e.getMessage());
                }
            }
        }
        if (loadedFonts.isEmpty()) {
            loadedFonts.put("SansSerif Bold", new Font("SansSerif", Font.BOLD, 1));
        }
        loadedFontNames = loadedFonts.keySet().toArray(new String[0]);
    }

    private final List<SlideRow> slideRows = new ArrayList<>();
    private final JPanel slidesPanel;
    private final JScrollPane scrollPane;

    private boolean isSyncingFormat = false;

    public GifSlideShowApp() {
        super("GIF/Video Slide Show Creator — YouTube HD (1920×1080)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 780);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(900, 600));

        JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        mainPanel.setBackground(new Color(30, 30, 30));

        JLabel header = new JLabel("GIF / Video Slide Show Creator — YouTube HD (1920×1080)", SwingConstants.CENTER);
        header.setFont(new Font("Segoe UI", Font.BOLD, 22));
        header.setForeground(new Color(29, 161, 242));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        mainPanel.add(header, BorderLayout.NORTH);

        slidesPanel = new JPanel();
        slidesPanel.setLayout(new BoxLayout(slidesPanel, BoxLayout.Y_AXIS));
        slidesPanel.setBackground(new Color(30, 30, 30));

        scrollPane = new JScrollPane(slidesPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.setBackground(new Color(30, 30, 30));
        scrollPane.getViewport().setBackground(new Color(30, 30, 30));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        bottomPanel.setBackground(new Color(30, 30, 30));

        JButton addBtn = createStyledButton("+ Add Slide", new Color(29, 161, 242));
        addBtn.addActionListener(e -> addSlideRow());

        JButton bulkBtn = createStyledButton("📁 Bulk Images", new Color(160, 100, 220));
        bulkBtn.addActionListener(e -> bulkImport());

        JButton bulkTextBtn = createStyledButton("📝 Bulk Text", new Color(220, 160, 50));
        bulkTextBtn.addActionListener(e -> bulkImportText());

        JButton titleGridBtn = createStyledButton("🖼 Title Grid Slide", new Color(60, 160, 200));
        titleGridBtn.addActionListener(e -> addTitleGridSlide());

        JButton gifBtn = createStyledButton("🎞 Create GIF", new Color(0, 186, 124));
        gifBtn.addActionListener(e -> createGif());

        JButton mp4Btn = createStyledButton("🎬 Create MP4", new Color(220, 60, 60));
        mp4Btn.addActionListener(e -> createMp4());

        bottomPanel.add(addBtn);
        bottomPanel.add(bulkBtn);
        bottomPanel.add(bulkTextBtn);
        bottomPanel.add(titleGridBtn);
        bottomPanel.add(gifBtn);
        bottomPanel.add(mp4Btn);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
        addSlideRow();
    }

    // ==================== Title Grid Slide ====================

    private void addTitleGridSlide() {
        List<BufferedImage> images = new ArrayList<>();
        for (SlideRow row : slideRows) {
            if (row.getImage() != null && !row.isTitleGridSlide) {
                images.add(row.getImage());
            }
        }

        if (images.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Add images to your slides first.\nThe title grid is built from your slide images.",
                    "No Images", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String titleText = JOptionPane.showInputDialog(this,
                "Enter title text for the grid slide:", "My Slide Show");
        if (titleText == null) return;

        String[] layouts = {
                "1. Auto Grid (even mosaic)",
                "2. Big Center + Surround",
                "3. Big Left + Right Stack",
                "4. Big Right + Left Stack",
                "5. Big Top + Bottom Row",
                "6. Big Bottom + Top Row",
                "7. Center Empty + Border Ring",
                "8. Diagonal Cascade",
                "9. Left Column + Right Grid",
                "10. Checkerboard Scatter"
        };
        String chosen = (String) JOptionPane.showInputDialog(this,
                "Choose grid layout:", "Title Grid Layout",
                JOptionPane.QUESTION_MESSAGE, null, layouts, layouts[0]);
        if (chosen == null) return;
        int layoutIndex = Integer.parseInt(chosen.substring(0, chosen.indexOf('.')).trim());

        BufferedImage gridImage = generateGridImage(images, GIF_WIDTH, GIF_HEIGHT, layoutIndex);

        slideRows.removeIf(r -> r.isTitleGridSlide);

        SlideRow titleRow = new SlideRow(1);
        titleRow.isTitleGridSlide = true;
        titleRow.gridLayoutIndex = layoutIndex;
        titleRow.gridSourceImages = new ArrayList<>(images);
        titleRow.setImageDirectly(gridImage, "📸 Layout " + layoutIndex + " (" + images.size() + " images)");
        titleRow.setSubtitleText(titleText);
        titleRow.applyFormatting("Segoe UI", 48, Font.BOLD,
                Color.WHITE, SwingConstants.CENTER, false, "Blur-Fit", 5, 78,
                false, loadedFontNames[0], 50, 10, 80, Color.WHITE);

        slideRows.add(0, titleRow);
        rebuildSlidesPanel();

        JOptionPane.showMessageDialog(this,
                "Title grid slide created with layout #" + layoutIndex + " and " + images.size() + " images!",
                "Title Grid Slide", JOptionPane.INFORMATION_MESSAGE);
    }

    // ==================== Grid Image Generation ====================

    private static BufferedImage generateGridImage(List<BufferedImage> images, int targetW, int targetH) {
        return generateGridImage(images, targetW, targetH, 1);
    }

    private static BufferedImage generateGridImage(List<BufferedImage> images, int targetW, int targetH, int layout) {
        BufferedImage grid = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = grid.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        g.setColor(new Color(18, 18, 24));
        g.fillRect(0, 0, targetW, targetH);

        int gap = Math.max(6, Math.min(targetW, targetH) / 80);
        int count = images.size();

        List<int[]> cells;
        switch (layout) {
            case 2:  cells = layoutBigCenter(targetW, targetH, gap, count); break;
            case 3:  cells = layoutBigSide(targetW, targetH, gap, count, true); break;
            case 4:  cells = layoutBigSide(targetW, targetH, gap, count, false); break;
            case 5:  cells = layoutBigTopBottom(targetW, targetH, gap, count, true); break;
            case 6:  cells = layoutBigTopBottom(targetW, targetH, gap, count, false); break;
            case 7:  cells = layoutBorderRing(targetW, targetH, gap, count); break;
            case 8:  cells = layoutDiagonalCascade(targetW, targetH, gap, count); break;
            case 9:  cells = layoutColumnGrid(targetW, targetH, gap, count); break;
            case 10: cells = layoutCheckerboard(targetW, targetH, gap, count); break;
            default: cells = layoutAutoGrid(targetW, targetH, gap, count); break;
        }

        for (int i = 0; i < Math.min(count, cells.size()); i++) {
            int[] cell = cells.get(i);
            drawImageInCell(g, images.get(i), cell[0], cell[1], cell[2], cell[3]);
        }

        GradientPaint gradient = new GradientPaint(
                0, targetH * 0.5f, new Color(0, 0, 0, 0),
                0, targetH, new Color(0, 0, 0, 200));
        g.setPaint(gradient);
        g.fillRect(0, (int) (targetH * 0.5), targetW, targetH);

        // Top vignette for polish
        GradientPaint topGrad = new GradientPaint(
                0, 0, new Color(0, 0, 0, 100),
                0, targetH * 0.15f, new Color(0, 0, 0, 0));
        g.setPaint(topGrad);
        g.fillRect(0, 0, targetW, (int) (targetH * 0.15));

        g.dispose();
        return grid;
    }

    private static void drawImageInCell(Graphics2D g, BufferedImage img, int cx, int cy, int cw, int ch) {
        if (cw < 2 || ch < 2) return;

        int radius = Math.min(cw, ch) / 12;
        java.awt.geom.RoundRectangle2D roundRect =
                new java.awt.geom.RoundRectangle2D.Float(cx, cy, cw, ch, radius, radius);

        Shape oldClip = g.getClip();
        g.setClip(roundRect);

        // Fill cell with cover-crop of the image
        double scX = (double) cw / img.getWidth();
        double scY = (double) ch / img.getHeight();
        double coverSc = Math.max(scX, scY);
        int drawW = (int) (img.getWidth() * coverSc);
        int drawH = (int) (img.getHeight() * coverSc);
        g.drawImage(img, cx + (cw - drawW) / 2, cy + (ch - drawH) / 2, drawW, drawH, null);

        g.setClip(oldClip);

        // Subtle border
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(255, 255, 255, 40));
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(roundRect);
        g2.dispose();
    }

    // ===== Layout 1: Auto Grid =====
    private static List<int[]> layoutAutoGrid(int w, int h, int gap, int count) {
        List<int[]> cells = new ArrayList<>();
        int cols, rows;
        if (count <= 1) { cols = 1; rows = 1; }
        else if (count <= 2) { cols = 2; rows = 1; }
        else if (count <= 4) { cols = 2; rows = 2; }
        else if (count <= 6) { cols = 3; rows = 2; }
        else if (count <= 9) { cols = 3; rows = 3; }
        else if (count <= 12) { cols = 4; rows = 3; }
        else if (count <= 16) { cols = 4; rows = 4; }
        else if (count <= 20) { cols = 5; rows = 4; }
        else if (count <= 25) { cols = 5; rows = 5; }
        else { cols = 6; rows = (int) Math.ceil(count / 6.0); }

        int cw = (w - gap * (cols + 1)) / cols;
        int ch = (h - gap * (rows + 1)) / rows;
        int idx = 0;
        for (int r = 0; r < rows && idx < count; r++) {
            for (int c = 0; c < cols && idx < count; c++) {
                cells.add(new int[]{gap + c * (cw + gap), gap + r * (ch + gap), cw, ch});
                idx++;
            }
        }
        return cells;
    }

    // ===== Layout 2: Big Center + Surround =====
    private static List<int[]> layoutBigCenter(int w, int h, int gap, int count) {
        List<int[]> cells = new ArrayList<>();
        if (count < 2) return layoutAutoGrid(w, h, gap, count);

        int bigW = (int) (w * 0.5);
        int bigH = (int) (h * 0.55);
        int bigX = (w - bigW) / 2;
        int bigY = (h - bigH) / 2;
        cells.add(new int[]{bigX, bigY, bigW, bigH});

        int remaining = count - 1;
        // Distribute evenly around the sides: top, bottom, left, right
        int perSide = Math.max(1, (remaining + 3) / 4);
        int topCount = Math.min(perSide, remaining);
        int bottomCount = Math.min(perSide, remaining - topCount);
        int leftCount = Math.min(perSide, remaining - topCount - bottomCount);
        int rightCount = remaining - topCount - bottomCount - leftCount;

        // Top row
        if (topCount > 0) {
            int slotW = (w - gap * (topCount + 1)) / topCount;
            int slotH = bigY - gap * 2;
            for (int i = 0; i < topCount; i++) {
                cells.add(new int[]{gap + i * (slotW + gap), gap, slotW, Math.max(slotH, 50)});
            }
        }

        // Bottom row
        if (bottomCount > 0) {
            int slotW = (w - gap * (bottomCount + 1)) / bottomCount;
            int slotH = h - (bigY + bigH + gap * 2);
            for (int i = 0; i < bottomCount; i++) {
                cells.add(new int[]{gap + i * (slotW + gap), bigY + bigH + gap, slotW, Math.max(slotH, 50)});
            }
        }

        // Left column
        if (leftCount > 0) {
            int lSlotW = bigX - gap * 2;
            int lSlotH = (bigH - gap * (leftCount - 1)) / leftCount;
            for (int i = 0; i < leftCount; i++) {
                cells.add(new int[]{gap, bigY + i * (lSlotH + gap), Math.max(lSlotW, 50), lSlotH});
            }
        }

        // Right column
        if (rightCount > 0) {
            int rSlotW = w - (bigX + bigW + gap * 2);
            int rSlotH = (bigH - gap * (rightCount - 1)) / rightCount;
            for (int i = 0; i < rightCount; i++) {
                cells.add(new int[]{bigX + bigW + gap, bigY + i * (rSlotH + gap), Math.max(rSlotW, 50), rSlotH});
            }
        }
        return cells;
    }

    // ===== Layout 3/4: Big Left or Big Right + Stack =====
    private static List<int[]> layoutBigSide(int w, int h, int gap, int count, boolean bigOnLeft) {
        List<int[]> cells = new ArrayList<>();
        if (count < 2) return layoutAutoGrid(w, h, gap, count);

        int bigW = (int) (w * 0.55);
        int smallW = w - bigW - gap * 3;
        int remaining = count - 1;

        // If too many, use 2 columns on the small side
        int cols = remaining > 4 ? 2 : 1;
        int rows = (int) Math.ceil((double) remaining / cols);
        int cellW = (smallW - gap * (cols - 1)) / cols;
        int cellH = (h - gap * (rows + 1)) / rows;

        int smallX = bigOnLeft ? bigW + gap * 2 : gap;
        int bigX = bigOnLeft ? gap : w - bigW - gap;

        cells.add(new int[]{bigX, gap, bigW, h - gap * 2});
        int idx = 0;
        for (int r = 0; r < rows && idx < remaining; r++) {
            for (int c = 0; c < cols && idx < remaining; c++) {
                cells.add(new int[]{smallX + c * (cellW + gap), gap + r * (cellH + gap), cellW, cellH});
                idx++;
            }
        }
        return cells;
    }

    // ===== Layout 5/6: Big Top or Big Bottom + Row =====
    private static List<int[]> layoutBigTopBottom(int w, int h, int gap, int count, boolean bigOnTop) {
        List<int[]> cells = new ArrayList<>();
        if (count < 2) return layoutAutoGrid(w, h, gap, count);

        int bigH = (int) (h * 0.55);
        int smallH = h - bigH - gap * 3;
        int remaining = count - 1;

        // If too many, use 2 rows on the small side
        int rows = remaining > 5 ? 2 : 1;
        int cols = (int) Math.ceil((double) remaining / rows);
        int cellW = (w - gap * (cols + 1)) / cols;
        int cellH = (smallH - gap * (rows - 1)) / rows;

        int smallY = bigOnTop ? bigH + gap * 2 : gap;
        int bigY = bigOnTop ? gap : h - bigH - gap;

        cells.add(new int[]{gap, bigY, w - gap * 2, bigH});
        int idx = 0;
        for (int r = 0; r < rows && idx < remaining; r++) {
            for (int c = 0; c < cols && idx < remaining; c++) {
                cells.add(new int[]{gap + c * (cellW + gap), smallY + r * (cellH + gap), cellW, cellH});
                idx++;
            }
        }
        return cells;
    }

    // ===== Layout 7: Center Empty + Border Ring =====
    private static List<int[]> layoutBorderRing(int w, int h, int gap, int count) {
        List<int[]> cells = new ArrayList<>();
        if (count < 4) return layoutAutoGrid(w, h, gap, count);

        int borderSize = Math.min(w, h) / 4;
        int top = Math.max(1, count / 4);
        int bottom = Math.max(1, count / 4);
        int left = Math.max(1, (count - top - bottom) / 2);
        int right = count - top - bottom - left;

        int slotW = top > 0 ? (w - gap * (top + 1)) / top : 0;
        for (int i = 0; i < top; i++) {
            cells.add(new int[]{gap + i * (slotW + gap), gap, slotW, borderSize});
        }

        slotW = bottom > 0 ? (w - gap * (bottom + 1)) / bottom : 0;
        for (int i = 0; i < bottom; i++) {
            cells.add(new int[]{gap + i * (slotW + gap), h - borderSize - gap, slotW, borderSize});
        }

        int midH = h - borderSize * 2 - gap * 4;
        int lSlotH = left > 0 ? (midH - gap * (left - 1)) / left : 0;
        for (int i = 0; i < left; i++) {
            cells.add(new int[]{gap, borderSize + gap * 2 + i * (lSlotH + gap), borderSize, lSlotH});
        }

        int rSlotH = right > 0 ? (midH - gap * (right - 1)) / right : 0;
        for (int i = 0; i < right; i++) {
            cells.add(new int[]{w - borderSize - gap, borderSize + gap * 2 + i * (rSlotH + gap), borderSize, rSlotH});
        }
        return cells;
    }

    // ===== Layout 8: Diagonal Cascade =====
    private static List<int[]> layoutDiagonalCascade(int w, int h, int gap, int count) {
        List<int[]> cells = new ArrayList<>();
        if (count == 0) return cells;

        int cellW = (int) (w * 0.4);
        int cellH = (int) (h * 0.4);
        int stepX = count > 1 ? (w - cellW - gap * 2) / (count - 1) : 0;
        int stepY = count > 1 ? (h - cellH - gap * 2) / (count - 1) : 0;

        for (int i = 0; i < count; i++) {
            cells.add(new int[]{gap + i * stepX, gap + i * stepY, cellW, cellH});
        }
        return cells;
    }

    // ===== Layout 9: Left Column + Right Grid =====
    private static List<int[]> layoutColumnGrid(int w, int h, int gap, int count) {
        List<int[]> cells = new ArrayList<>();
        if (count < 2) return layoutAutoGrid(w, h, gap, count);

        int colW = w / 3 - gap;
        int gridW = w - colW - gap * 3;

        cells.add(new int[]{gap, gap, colW, h - gap * 2});

        int remaining = count - 1;
        int cols = (int) Math.ceil(Math.sqrt(remaining * (double) gridW / (h > 0 ? h : 1)));
        cols = Math.max(1, Math.min(cols, remaining));
        int rows = (int) Math.ceil((double) remaining / cols);

        int slotW = (gridW - gap * (cols - 1)) / cols;
        int slotH = (h - gap * (rows + 1)) / rows;
        int idx = 0;
        for (int r = 0; r < rows && idx < remaining; r++) {
            for (int c = 0; c < cols && idx < remaining; c++) {
                cells.add(new int[]{
                        colW + gap * 2 + c * (slotW + gap),
                        gap + r * (slotH + gap),
                        slotW, slotH
                });
                idx++;
            }
        }
        return cells;
    }

    // ===== Layout 10: Checkerboard Scatter =====
    private static List<int[]> layoutCheckerboard(int w, int h, int gap, int count) {
        List<int[]> cells = new ArrayList<>();
        if (count == 0) return cells;

        int cols = (int) Math.ceil(Math.sqrt(count * 1.5));
        int rows = (int) Math.ceil((double) count / cols);
        cols = Math.max(cols, 1);
        rows = Math.max(rows, 1);

        int slotW = (w - gap * (cols + 1)) / cols;
        int slotH = (h - gap * (rows + 1)) / rows;
        int offsetX = slotW / 4;
        int offsetY = slotH / 4;

        int idx = 0;
        for (int r = 0; r < rows && idx < count; r++) {
            for (int c = 0; c < cols && idx < count; c++) {
                int baseX = gap + c * (slotW + gap);
                int baseY = gap + r * (slotH + gap);
                int ox = (r % 2 == 1) ? offsetX : 0;
                int oy = (c % 2 == 1) ? offsetY / 2 : 0;
                int cx = Math.min(baseX + ox, w - slotW - gap);
                int cy = Math.min(baseY + oy, h - slotH - gap);
                int cw = slotW - Math.abs(ox) / 2;
                int ch = slotH - Math.abs(oy) / 2;
                cells.add(new int[]{Math.max(cx, gap), Math.max(cy, gap), Math.max(cw, 50), Math.max(ch, 50)});
                idx++;
            }
        }
        return cells;
    }

    // ==================== Bulk Import Images ====================

    private void bulkImport() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new FileNameExtensionFilter(
                "Images (jpg, png, gif, bmp, webp, avif, heif)",
                "jpg", "jpeg", "png", "gif", "bmp", "webp", "avif", "heif", "heic"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File[] files = fc.getSelectedFiles();
        if (files.length == 0) return;

        Arrays.sort(files, (a, b) -> {
            int numA = extractLeadingNumber(a.getName());
            int numB = extractLeadingNumber(b.getName());
            if (numA != Integer.MAX_VALUE || numB != Integer.MAX_VALUE) {
                return Integer.compare(numA, numB);
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });

        if (slideRows.size() == 1 && slideRows.get(0).getImage() == null
                && !slideRows.get(0).isTitleGridSlide) {
            slideRows.clear();
            slidesPanel.removeAll();
        }

        int loaded = 0;
        int failed = 0;
        for (File file : files) {
            try {
                BufferedImage img = loadImageFile(file);
                if (img == null) { failed++; continue; }
                SlideRow row = new SlideRow(slideRows.size() + 1);
                row.setImageDirectly(img, file.getName());
                slideRows.add(row);
                slidesPanel.add(row.getPanel());
                slidesPanel.add(Box.createRigidArea(new Dimension(0, 10)));
                loaded++;
            } catch (IOException ex) {
                failed++;
            }
        }

        applyFirstSlideFormattingToAll();
        slidesPanel.revalidate();
        slidesPanel.repaint();

        String msg = loaded + " images imported successfully.";
        if (failed > 0) msg += "\n" + failed + " files failed to load.";
        JOptionPane.showMessageDialog(this, msg, "Bulk Import", JOptionPane.INFORMATION_MESSAGE);
    }

    // ==================== Bulk Import Text ====================

    private void bulkImportText() {
        String[] options = {"From File (.txt)", "From Clipboard / Paste"};
        int choice = JOptionPane.showOptionDialog(this,
                "Import text lines (one line per slide).\nLine 1 → Slide 1, Line 2 → Slide 2, etc.\n(Title grid slide is skipped)",
                "Bulk Import Text", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);

        if (choice < 0) return;

        List<String> lines;

        if (choice == 0) {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Text files (*.txt)", "txt"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

            try {
                lines = Files.readAllLines(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                try {
                    lines = Files.readAllLines(fc.getSelectedFile().toPath());
                } catch (IOException ex2) {
                    JOptionPane.showMessageDialog(this,
                            "Failed to read file:\n" + ex2.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        } else {
            JTextArea pasteArea = new JTextArea(12, 40);
            pasteArea.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            pasteArea.setLineWrap(true);
            pasteArea.setWrapStyleWord(true);
            JScrollPane sp = new JScrollPane(pasteArea);
            sp.setPreferredSize(new Dimension(500, 300));

            int result = JOptionPane.showConfirmDialog(this, sp,
                    "Paste text (one line per slide):",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;

            String text = pasteArea.getText();
            if (text.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "No text entered.", "Empty", JOptionPane.WARNING_MESSAGE);
                return;
            }
            lines = Arrays.asList(text.split("\\r?\\n"));
        }

        ArrayList<String> trimmed = new ArrayList<>(lines);
        while (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1).trim().isEmpty()) {
            trimmed.remove(trimmed.size() - 1);
        }
        lines = trimmed;

        if (lines.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No text lines found.", "Empty", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<SlideRow> targetSlides = new ArrayList<>();
        for (SlideRow row : slideRows) {
            if (!row.isTitleGridSlide) {
                targetSlides.add(row);
            }
        }

        int assigned = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (i < targetSlides.size()) {
                targetSlides.get(i).setSubtitleText(line);
            } else {
                SlideRow row = new SlideRow(slideRows.size() + 1);
                row.setSubtitleText(line);
                slideRows.add(row);
                slidesPanel.add(row.getPanel());
                slidesPanel.add(Box.createRigidArea(new Dimension(0, 10)));
            }
            assigned++;
        }

        applyFirstSlideFormattingToAll();
        slidesPanel.revalidate();
        slidesPanel.repaint();

        JOptionPane.showMessageDialog(this,
                assigned + " lines assigned to slides.\nSlides: " + slideRows.size() + " total.",
                "Bulk Text Import", JOptionPane.INFORMATION_MESSAGE);
    }

    // ==================== Format Sync ====================

    void syncFormattingFromFirstSlide() {
        if (isSyncingFormat) return;
        if (slideRows.isEmpty()) return;

        SlideRow source = null;
        for (SlideRow row : slideRows) {
            if (!row.isTitleGridSlide) {
                source = row;
                break;
            }
        }
        if (source == null) return;

        String fontName = source.getSelectedFont();
        int fontSize = source.getFontSize();
        int fontStyle = source.getFontStyle();
        Color fontColor = source.getFontColor();
        int alignment = source.getTextAlignment();
        boolean showPin = source.isShowPin();
        String displayMode = source.getDisplayMode();
        int subtitleY = source.getSubtitleY();
        int subtitleBgOpacity = source.getSubtitleBgOpacity();
        boolean showSlideNumber = source.isShowSlideNumber();
        String slideNumberFontName = source.getSlideNumberFontName();
        int slideNumberX = source.getSlideNumberX();
        int slideNumberY = source.getSlideNumberY();
        int slideNumberSize = source.getSlideNumberSize();
        Color slideNumberColor = source.getSlideNumberColor();

        isSyncingFormat = true;
        try {
            for (SlideRow row : slideRows) {
                if (row == source || row.isTitleGridSlide) continue;
                row.applyFormatting(fontName, fontSize, fontStyle, fontColor, alignment, showPin, displayMode, subtitleY, subtitleBgOpacity,
                        showSlideNumber, slideNumberFontName, slideNumberX, slideNumberY, slideNumberSize, slideNumberColor);
            }
        } finally {
            isSyncingFormat = false;
        }
    }

    private void applyFirstSlideFormattingToAll() {
        if (slideRows.size() < 2) return;
        syncFormattingFromFirstSlide();
    }

    // ==================== Utility ====================

    private static int extractLeadingNumber(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        StringBuilder digits = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else if (digits.length() > 0) break;
        }
        if (digits.length() > 0) {
            try { return Integer.parseInt(digits.toString()); }
            catch (NumberFormatException e) { /* fall through */ }
        }
        return Integer.MAX_VALUE;
    }

    private JButton createStyledButton(String text, Color bg) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isPressed() ? bg.darker() : getModel().isRollover() ? bg.brighter() : bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(getText(), x, y);
                g2.dispose();
            }
        };
        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setPreferredSize(new Dimension(165, 40));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void addSlideRow() {
        SlideRow row = new SlideRow(slideRows.size() + 1);
        slideRows.add(row);
        slidesPanel.add(row.getPanel());
        slidesPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        if (slideRows.size() > 1) {
            applyFirstSlideFormattingToAll();
        }

        slidesPanel.revalidate();
        slidesPanel.repaint();
        SwingUtilities.invokeLater(() -> {
            JScrollBar vBar = scrollPane.getVerticalScrollBar();
            vBar.setValue(vBar.getMaximum());
        });
    }

    private void removeSlideRow(SlideRow row) {
        slideRows.remove(row);
        rebuildSlidesPanel();
    }

    private void moveSlideRow(SlideRow row, int direction) {
        int idx = slideRows.indexOf(row);
        int newIdx = idx + direction;
        if (newIdx < 0 || newIdx >= slideRows.size()) return;
        Collections.swap(slideRows, idx, newIdx);
        rebuildSlidesPanel();
        applyFirstSlideFormattingToAll();
    }

    private void rebuildSlidesPanel() {
        slidesPanel.removeAll();
        for (int i = 0; i < slideRows.size(); i++) {
            slideRows.get(i).updateNumber(i + 1);
            slidesPanel.add(slideRows.get(i).getPanel());
            slidesPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        }
        slidesPanel.revalidate();
        slidesPanel.repaint();
    }

    // ==================== Image Loading ====================

    private static BufferedImage loadImageFile(File file) throws IOException {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".avif") || name.endsWith(".heif") || name.endsWith(".heic")) {
            return loadWithFfmpeg(file);
        }
        BufferedImage img = ImageIO.read(file);
        if (img == null) {
            return loadWithFfmpeg(file);
        }
        return img;
    }

    private static BufferedImage loadWithFfmpeg(File file) throws IOException {
        File tempPng = File.createTempFile("img_convert_", ".png");
        tempPng.deleteOnExit();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", file.getAbsolutePath(), tempPng.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                while (br.readLine() != null) { /* drain */ }
            }
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                throw new IOException(
                        "ffmpeg failed (exit " + exitCode + ").\n" +
                                "Ensure ffmpeg is installed: https://ffmpeg.org/download.html");
            }
            BufferedImage img = ImageIO.read(tempPng);
            if (img == null) throw new IOException("Failed to read converted image.");
            return img;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Conversion interrupted.", e);
        } finally {
            tempPng.delete();
        }
    }

    // ==================== Slide Rendering ====================

    private static void drawLocationPin(Graphics2D g, int x, int y, int size, Color color) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);

        int pinW = size;
        int pinH = (int) (size * 1.4);
        int cx = x + pinW / 2;
        int topY = y;

        Ellipse2D circle = new Ellipse2D.Double(cx - pinW / 2.0, topY, pinW, pinW);
        int triTopY = topY + (int) (pinW * 0.6);
        int[] triX = {cx - pinW / 3, cx + pinW / 3, cx};
        int[] triY = {triTopY, triTopY, topY + pinH};
        Polygon triangle = new Polygon(triX, triY, 3);

        g2.fill(circle);
        g2.fillPolygon(triangle);

        int dotR = (int) (pinW * 0.22);
        g2.setColor(Color.WHITE);
        g2.fillOval(cx - dotR, topY + pinW / 2 - dotR, dotR * 2, dotR * 2);

        g2.dispose();
    }

    static BufferedImage renderFrame(BufferedImage image, String text,
                                     String fontName, int fontSize, int fontStyle,
                                     Color fontColor, int alignment,
                                     boolean showPin, int targetW, int targetH) {
        return renderFrame(image, text, fontName, fontSize, fontStyle,
                fontColor, alignment, showPin, targetW, targetH, "Blur-Fit", 5, 78,
                false, null, null, 0, 0, 0, null);
    }

    static BufferedImage renderFrame(BufferedImage image, String text,
                                     String fontName, int fontSize, int fontStyle,
                                     Color fontColor, int alignment,
                                     boolean showPin, int targetW, int targetH,
                                     String displayMode) {
        return renderFrame(image, text, fontName, fontSize, fontStyle,
                fontColor, alignment, showPin, targetW, targetH, displayMode, 5, 78,
                false, null, null, 0, 0, 0, null);
    }

    static BufferedImage renderFrame(BufferedImage image, String text,
                                     String fontName, int fontSize, int fontStyle,
                                     Color fontColor, int alignment,
                                     boolean showPin, int targetW, int targetH,
                                     String displayMode, int subtitleY, int subtitleBgOpacity,
                                     boolean showSlideNumber, String slideNumberText,
                                     String slideNumberFontName,
                                     int slideNumberX, int slideNumberY,
                                     int slideNumberSize, Color slideNumberColor) {
        BufferedImage frame = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        g.setColor(new Color(21, 32, 43));
        g.fillRect(0, 0, targetW, targetH);

        if (displayMode == null) displayMode = "Blur-Fit";

        switch (displayMode) {
            case "Direct": {
                g.drawImage(image, 0, 0, targetW, targetH, null);
                break;
            }
            case "Fill (Crop)": {
                double sc = Math.max((double) targetW / image.getWidth(), (double) targetH / image.getHeight());
                int dw = (int) (image.getWidth() * sc);
                int dh = (int) (image.getHeight() * sc);
                g.drawImage(image, (targetW - dw) / 2, (targetH - dh) / 2, dw, dh, null);
                break;
            }
            case "Fit (Bars)": {
                double sc = Math.min((double) targetW / image.getWidth(), (double) targetH / image.getHeight());
                int dw = (int) (image.getWidth() * sc);
                int dh = (int) (image.getHeight() * sc);
                g.drawImage(image, (targetW - dw) / 2, (targetH - dh) / 2, dw, dh, null);
                break;
            }
            case "Original Size": {
                int ox = (targetW - image.getWidth()) / 2;
                int oy = (targetH - image.getHeight()) / 2;
                g.drawImage(image, ox, oy, null);
                break;
            }
            case "Stretch": {
                g.drawImage(image, 0, 0, targetW, targetH, null);
                break;
            }
            case "Top-Fit": {
                double sc = Math.min((double) targetW / image.getWidth(), (double) targetH / image.getHeight());
                int dw = (int) (image.getWidth() * sc);
                int dh = (int) (image.getHeight() * sc);
                g.drawImage(image, (targetW - dw) / 2, 0, dw, dh, null);
                break;
            }
            case "Bottom-Fit": {
                double sc = Math.min((double) targetW / image.getWidth(), (double) targetH / image.getHeight());
                int dw = (int) (image.getWidth() * sc);
                int dh = (int) (image.getHeight() * sc);
                g.drawImage(image, (targetW - dw) / 2, targetH - dh, dw, dh, null);
                break;
            }
            case "Left-Fit": {
                double sc = Math.min((double) targetW / image.getWidth(), (double) targetH / image.getHeight());
                int dw = (int) (image.getWidth() * sc);
                int dh = (int) (image.getHeight() * sc);
                g.drawImage(image, 0, (targetH - dh) / 2, dw, dh, null);
                break;
            }
            case "Right-Fit": {
                double sc = Math.min((double) targetW / image.getWidth(), (double) targetH / image.getHeight());
                int dw = (int) (image.getWidth() * sc);
                int dh = (int) (image.getHeight() * sc);
                g.drawImage(image, targetW - dw, (targetH - dh) / 2, dw, dh, null);
                break;
            }
            default: { // "Blur-Fit"
                double coverScale = Math.max((double) targetW / image.getWidth(), (double) targetH / image.getHeight());
                int bgW = (int) (image.getWidth() * coverScale);
                int bgH = (int) (image.getHeight() * coverScale);
                BufferedImage bgScaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D bgG = bgScaled.createGraphics();
                bgG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                bgG.drawImage(image, (targetW - bgW) / 2, (targetH - bgH) / 2, bgW, bgH, null);
                bgG.dispose();
                BufferedImage blurred = applyStackBlur(bgScaled, 60);
                Graphics2D blurG = blurred.createGraphics();
                blurG.setColor(new Color(0, 0, 0, 100));
                blurG.fillRect(0, 0, targetW, targetH);
                blurG.dispose();
                g.drawImage(blurred, 0, 0, null);

                double fitScale = Math.min((double) targetW / image.getWidth(), (double) targetH / image.getHeight());
                int dw = (int) (image.getWidth() * fitScale);
                int dh = (int) (image.getHeight() * fitScale);
                g.drawImage(image, (targetW - dw) / 2, (targetH - dh) / 2, dw, dh, null);
                break;
            }
        }

        // ========== SLIDE NUMBER OVERLAY ==========
        if (showSlideNumber && slideNumberText != null && !slideNumberText.isEmpty()) {
            float numScaleFactor = targetW / 1920.0f;
            int scaledNumSize = Math.max(10, (int) (slideNumberSize * numScaleFactor));

            // Resolve font from loaded fonts map
            Font baseFont = loadedFonts.getOrDefault(slideNumberFontName,
                    loadedFonts.values().iterator().next());
            Font numFont = baseFont.deriveFont(Font.BOLD, (float) scaledNumSize);
            g.setFont(numFont);
            FontMetrics numFm = g.getFontMetrics();

            int numX = (int) (slideNumberX / 100.0 * targetW);
            int numY = (int) (slideNumberY / 100.0 * targetH);

            int textW = numFm.stringWidth(slideNumberText);
            int textH = numFm.getAscent();
            int drawX = numX - textW / 2;
            int drawY = numY + textH / 2;

            // Circular transparent background sized to fit 1 or 2 digits
            int diameter = (int) (Math.max(textW, textH) * 1.5);
            int circleX = numX - diameter / 2;
            int circleY = numY - diameter / 2;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0, 120));
            g2.fillOval(circleX, circleY, diameter, diameter);
            g2.dispose();

            // Draw number text centered in circle
            g.setColor(slideNumberColor != null ? slideNumberColor : Color.WHITE);
            g.drawString(slideNumberText, drawX, drawY);
        }

        // ========== OVERLAY TEXT ==========
        String subtitle = text.trim();
        if (!subtitle.isEmpty()) {
            float scaleFactor = targetW / 1200.0f;
            int scaledFontSize = Math.max(10, (int) (fontSize * scaleFactor));
            Font font = new Font(fontName, fontStyle, scaledFontSize);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();

            int paddingX = (int) (20 * scaleFactor);
            int paddingY = (int) (10 * scaleFactor);
            int pinSize = (int) (scaledFontSize * 0.9);
            int pinGap = (int) (8 * scaleFactor);
            int textOffsetX = showPin ? pinSize + pinGap : 0;

            List<String> lines = wrapTextStatic(subtitle, fm, targetW - paddingX * 2 - textOffsetX - paddingX);

            int lineHeight = fm.getHeight() + (int) (3 * scaleFactor);
            int blockHeight = lines.size() * lineHeight + paddingY * 2;
            int bottomMargin = (int) (targetH * subtitleY / 100.0);
            int blockY = targetH - blockHeight - bottomMargin;

            int maxLineWidth = 0;
            for (String line : lines) {
                maxLineWidth = Math.max(maxLineWidth, fm.stringWidth(line));
            }

            int blockWidth, blockX;
            if (alignment == SwingConstants.CENTER) {
                blockWidth = targetW;
                blockX = 0;
            } else if (alignment == SwingConstants.RIGHT) {
                blockWidth = maxLineWidth + textOffsetX + paddingX * 3;
                blockX = targetW - blockWidth;
            } else {
                blockWidth = maxLineWidth + textOffsetX + paddingX * 3;
                blockX = 0;
            }

            int bgAlpha = (int) (subtitleBgOpacity / 100.0 * 255);
            bgAlpha = Math.max(0, Math.min(255, bgAlpha));
            g.setColor(new Color(40, 50, 65, bgAlpha));
            if (alignment == SwingConstants.LEFT) {
                int arc = (int) (12 * scaleFactor);
                g.fillRoundRect(blockX, blockY, blockWidth + arc, blockHeight, arc, arc);
                g.fillRect(blockX, blockY, arc, blockHeight);
            } else if (alignment == SwingConstants.RIGHT) {
                int arc = (int) (12 * scaleFactor);
                g.fillRoundRect(blockX - arc, blockY, blockWidth + arc, blockHeight, arc, arc);
                g.fillRect(blockX + blockWidth - arc, blockY, arc, blockHeight);
            } else {
                g.fillRect(blockX, blockY, blockWidth, blockHeight);
            }

            int textStartY = blockY + paddingY + fm.getAscent();
            if (showPin) {
                int pinX = blockX + paddingX;
                int pinY = blockY + (blockHeight - pinSize) / 2;
                drawLocationPin(g, pinX, pinY, pinSize, new Color(255, 200, 0));
            }

            g.setColor(fontColor);
            int lineY = textStartY;
            for (String line : lines) {
                int lx;
                if (alignment == SwingConstants.CENTER) {
                    lx = (targetW - fm.stringWidth(line)) / 2;
                } else if (alignment == SwingConstants.RIGHT) {
                    lx = blockX + blockWidth - paddingX - fm.stringWidth(line);
                } else {
                    lx = blockX + paddingX + textOffsetX;
                }
                g.drawString(line, lx, lineY);
                lineY += lineHeight;
            }
        }

        g.dispose();
        return frame;
    }

    // ========== Stack Blur ==========

    private static BufferedImage applyStackBlur(BufferedImage src, int radius) {
        if (radius < 1) return src;

        int w = src.getWidth();
        int h = src.getHeight();

        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
        int[] result = new int[pixels.length];

        blurPass(pixels, result, w, h, radius, true);
        blurPass(result, pixels, w, h, radius, false);

        BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        output.setRGB(0, 0, w, h, pixels, 0, w);
        return output;
    }

    private static void blurPass(int[] src, int[] dst, int w, int h, int radius, boolean horizontal) {
        int length = horizontal ? w : h;
        int span = horizontal ? h : w;
        int div = radius + radius + 1;

        for (int j = 0; j < span; j++) {
            long sumR = 0, sumG = 0, sumB = 0;

            for (int k = -radius; k <= radius; k++) {
                int idx = clamp(k, 0, length - 1);
                int pixel = horizontal ? src[j * w + idx] : src[idx * w + j];
                sumR += (pixel >> 16) & 0xFF;
                sumG += (pixel >> 8) & 0xFF;
                sumB += pixel & 0xFF;
            }

            for (int i = 0; i < length; i++) {
                int r = (int) (sumR / div);
                int gg = (int) (sumG / div);
                int b = (int) (sumB / div);

                if (horizontal) {
                    dst[j * w + i] = (r << 16) | (gg << 8) | b;
                } else {
                    dst[i * w + j] = (r << 16) | (gg << 8) | b;
                }

                int addIdx = clamp(i + radius + 1, 0, length - 1);
                int remIdx = clamp(i - radius, 0, length - 1);

                int addPixel = horizontal ? src[j * w + addIdx] : src[addIdx * w + j];
                int remPixel = horizontal ? src[j * w + remIdx] : src[remIdx * w + j];

                sumR += ((addPixel >> 16) & 0xFF) - ((remPixel >> 16) & 0xFF);
                sumG += ((addPixel >> 8) & 0xFF) - ((remPixel >> 8) & 0xFF);
                sumB += (addPixel & 0xFF) - (remPixel & 0xFF);
            }
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    // ========== Text Wrapping ==========

    static List<String> wrapTextStatic(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (maxWidth <= 0) { lines.add(text); return lines; }
        for (String paragraph : text.split("\n")) {
            if (paragraph.isEmpty()) { lines.add(""); continue; }
            String[] words = paragraph.split("\\s+");
            StringBuilder current = new StringBuilder();
            for (String word : words) {
                String test = current.length() == 0 ? word : current + " " + word;
                if (fm.stringWidth(test) > maxWidth && current.length() > 0) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(test);
                }
            }
            if (current.length() > 0) lines.add(current.toString());
        }
        return lines;
    }

    // ==================== Collect Slides & Ask Duration ====================

    private List<SlideData> collectSlides() {
        List<SlideData> slides = new ArrayList<>();
        for (int i = 0; i < slideRows.size(); i++) {
            SlideRow row = slideRows.get(i);
            if (row.getImage() == null) {
                JOptionPane.showMessageDialog(this,
                        "Slide " + (i + 1) + " has no image.", "Missing Image", JOptionPane.WARNING_MESSAGE);
                return null;
            }
            BufferedImage slideImage = row.getImage();
            String slideDisplayMode = row.getDisplayMode();
            if (row.isTitleGridSlide && row.gridSourceImages != null) {
                slideImage = row.regenerateGridImage(GIF_WIDTH, GIF_HEIGHT);
                slideDisplayMode = "Direct";
            }
            slides.add(new SlideData(slideImage, row.getSubtitleText(),
                    row.getSelectedFont(), row.getFontSize(), row.getFontStyle(),
                    row.getFontColor(), row.getTextAlignment(), row.isShowPin(), slideDisplayMode,
                    row.getSubtitleY(), row.getSubtitleBgOpacity(),
                    row.isShowSlideNumber(), row.getSlideNumberText(), row.getSlideNumberFontName(),
                    row.getSlideNumberX(), row.getSlideNumberY(),
                    row.getSlideNumberSize(), row.getSlideNumberColor()));
        }
        if (slides.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Add at least one slide.", "No Slides", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return slides;
    }

    private int askDuration() {
        String durStr = JOptionPane.showInputDialog(this, "Duration per slide (milliseconds):", "2000");
        if (durStr == null) return -1;
        try {
            int duration = Integer.parseInt(durStr.trim());
            if (duration < 100 || duration > 30000) throw new NumberFormatException();
            return duration;
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Enter 100–30000.", "Invalid", JOptionPane.ERROR_MESSAGE);
            return -1;
        }
    }

    // ==================== Progress Dialog ====================

    private JDialog createProgressDialog(String title) {
        JDialog dialog = new JDialog(this, title, true);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        JLabel label = new JLabel("Preparing...", SwingConstants.CENTER);
        JPanel pp = new JPanel(new BorderLayout(10, 10));
        pp.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));
        pp.add(label, BorderLayout.NORTH);
        pp.add(bar, BorderLayout.CENTER);
        dialog.setContentPane(pp);
        dialog.setSize(500, 140);
        dialog.setLocationRelativeTo(this);
        return dialog;
    }

    private JLabel getProgressLabel(JDialog d) {
        return (JLabel) ((JPanel) d.getContentPane()).getComponent(0);
    }

    private JProgressBar getProgressBar(JDialog d) {
        return (JProgressBar) ((JPanel) d.getContentPane()).getComponent(1);
    }

    // ==================== Shared Frame Rendering ====================

    private List<BufferedImage> renderAllFrames(List<SlideData> slides, int w, int h,
                                                JProgressBar progressBar, int maxPct) {
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < slides.size(); i++) {
            SlideData s = slides.get(i);
            BufferedImage frame = renderFrame(
                    s.image, s.text, s.fontName, s.fontSize,
                    s.fontStyle, s.fontColor, s.alignment, s.showPin,
                    w, h, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                    s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                    s.slideNumberX, s.slideNumberY,
                    s.slideNumberSize, s.slideNumberColor);
            frames.add(frame);
            int pct = (int) ((i + 1.0) / slides.size() * maxPct);
            SwingUtilities.invokeLater(() -> progressBar.setValue(pct));
        }
        return frames;
    }

    // ==================== GIF Creation ====================

    private void createGif() {
        List<SlideData> slides = collectSlides();
        if (slides == null) return;

        int duration = askDuration();
        if (duration < 0) return;

        String[] options = {"High-Quality GIF (ImageIO)", "Ultra-Quality GIF (ffmpeg)"};
        int method = JOptionPane.showOptionDialog(this,
                "Choose encoding:\n• ImageIO: No dependencies, good quality.\n• ffmpeg: Best dithering (requires ffmpeg).",
                "Encoding", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[1]);
        if (method < 0) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("slideshow.gif"));
        chooser.setFileFilter(new FileNameExtensionFilter("GIF files", "gif"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File outFile = chooser.getSelectedFile();
        if (!outFile.getName().toLowerCase().endsWith(".gif"))
            outFile = new File(outFile.getAbsolutePath() + ".gif");

        final File finalOut = outFile;
        final int finalMethod = method;
        final long MAX_SIZE = 15L * 1024 * 1024;

        JDialog progressDialog = createProgressDialog("Creating GIF...");
        JProgressBar progressBar = getProgressBar(progressDialog);
        JLabel progressLabel = getProgressLabel(progressDialog);

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            String errorMsg = null;
            String finalInfo = null;

            @Override
            protected Void doInBackground() {
                try {
                    int[][] resolutions = {
                            {1920, 1080},
                            {1280, 720},
                            {854, 480},
                    };

                    for (int[] res : resolutions) {
                        int w = res[0];
                        int h = res[1];
                        publish("Rendering at " + w + "×" + h + "...");

                        List<BufferedImage> frames = renderAllFrames(slides, w, h, progressBar, 60);

                        publish("Encoding GIF at " + w + "×" + h + "...");
                        SwingUtilities.invokeLater(() -> progressBar.setValue(70));

                        if (finalMethod == 1) {
                            writeGifWithFfmpeg(frames, duration, finalOut);
                        } else {
                            writeAnimatedGif(frames, duration, finalOut);
                        }

                        SwingUtilities.invokeLater(() -> progressBar.setValue(90));

                        long fileSize = finalOut.length();
                        double sizeMB = fileSize / (1024.0 * 1024.0);

                        if (fileSize <= MAX_SIZE) {
                            finalInfo = String.format(
                                    "GIF created at %d×%d\nSize: %.2f MB (under 15 MB limit)\nFile: %s",
                                    w, h, sizeMB, finalOut.getAbsolutePath());
                            SwingUtilities.invokeLater(() -> progressBar.setValue(100));
                            return null;
                        }

                        publish(String.format("%.1f MB too large at %d×%d, trying smaller...", sizeMB, w, h));
                    }

                    publish("Trying aggressive optimization...");
                    if (finalMethod == 1 || tryAggressiveFfmpeg(slides, duration, finalOut, MAX_SIZE)) {
                        long sz = finalOut.length();
                        if (sz <= MAX_SIZE) {
                            finalInfo = String.format(
                                    "GIF created (optimized)\nSize: %.2f MB\nFile: %s",
                                    sz / (1024.0 * 1024.0), finalOut.getAbsolutePath());
                            return null;
                        }
                    }

                    long sz = finalOut.length();
                    finalInfo = String.format(
                            "⚠ GIF created but is %.2f MB (exceeds 15 MB).\n" +
                                    "Consider reducing slide count or duration.\nFile: %s",
                            sz / (1024.0 * 1024.0), finalOut.getAbsolutePath());

                } catch (Exception ex) {
                    errorMsg = ex.getMessage();
                    ex.printStackTrace();
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                progressLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                if (errorMsg != null) {
                    JOptionPane.showMessageDialog(GifSlideShowApp.this,
                            "Error:\n" + errorMsg, "Error", JOptionPane.ERROR_MESSAGE);
                } else if (finalInfo != null) {
                    int ch = JOptionPane.showOptionDialog(GifSlideShowApp.this,
                            finalInfo, "Result", JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null, new String[]{"Preview", "OK"}, "Preview");
                    if (ch == 0) showPreview(finalOut);
                }
            }
        };
        worker.execute();
        progressDialog.setVisible(true);
    }

    // ==================== MP4 Video Creation ====================

    private void createMp4() {
        List<SlideData> slides = collectSlides();
        if (slides == null) return;

        int duration = askDuration();
        if (duration < 0) return;

        String[] resOptions = {"1920×1080 (Full HD)", "2560×1440 (2K QHD)", "3840×2160 (4K UHD)"};
        int resChoice = JOptionPane.showOptionDialog(this,
                "Choose video resolution:",
                "MP4 Resolution", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, resOptions, resOptions[0]);
        if (resChoice < 0) return;

        final int videoW, videoH;
        switch (resChoice) {
            case 1:  videoW = 2560; videoH = 1440; break;
            case 2:  videoW = 3840; videoH = 2160; break;
            default: videoW = 1920; videoH = 1080; break;
        }

        String[] qualityOptions = {"High Quality (CRF 18)", "Medium Quality (CRF 23)", "Small File (CRF 28)"};
        int qualityChoice = JOptionPane.showOptionDialog(this,
                "Choose video quality:\n• High: Best quality, larger file\n• Medium: Balanced\n• Small: Smaller file, lower quality",
                "MP4 Quality", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, qualityOptions, qualityOptions[0]);
        if (qualityChoice < 0) return;

        final int crf;
        switch (qualityChoice) {
            case 1:  crf = 23; break;
            case 2:  crf = 28; break;
            default: crf = 18; break;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("slideshow.mp4"));
        chooser.setFileFilter(new FileNameExtensionFilter("MP4 Video", "mp4"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File outFile = chooser.getSelectedFile();
        if (!outFile.getName().toLowerCase().endsWith(".mp4"))
            outFile = new File(outFile.getAbsolutePath() + ".mp4");

        final File finalOut = outFile;

        JDialog progressDialog = createProgressDialog("Creating MP4 Video...");
        JProgressBar progressBar = getProgressBar(progressDialog);
        JLabel progressLabel = getProgressLabel(progressDialog);

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            String errorMsg = null;
            String finalInfo = null;

            @Override
            protected Void doInBackground() {
                File tempDir = null;
                try {
                    tempDir = new File(System.getProperty("java.io.tmpdir"),
                            "mp4_frames_" + System.currentTimeMillis());
                    if (!tempDir.mkdirs() && !tempDir.exists()) {
                        throw new IOException("Failed to create temp directory: " + tempDir);
                    }

                    int fps = 30;
                    int framesPerSlide = Math.max(1, (int) Math.round(duration / 1000.0 * fps));

                    publish("Rendering " + slides.size() + " slides at " + videoW + "×" + videoH + "...");

                    int totalFrames = slides.size() * framesPerSlide;
                    int frameIndex = 0;

                    for (int i = 0; i < slides.size(); i++) {
                        SlideData s = slides.get(i);
                        BufferedImage frame = renderFrame(
                                s.image, s.text, s.fontName, s.fontSize,
                                s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                s.slideNumberX, s.slideNumberY,
                                s.slideNumberSize, s.slideNumberColor);

                        for (int d = 0; d < framesPerSlide; d++) {
                            ImageIO.write(frame, "png",
                                    new File(tempDir, String.format("frame_%05d.png", frameIndex)));
                            frameIndex++;
                        }

                        int pct = (int) ((i + 1.0) / slides.size() * 60);
                        final int p = pct;
                        SwingUtilities.invokeLater(() -> progressBar.setValue(p));
                        publish("Rendered slide " + (i + 1) + "/" + slides.size());
                    }

                    publish("Encoding MP4 at " + videoW + "×" + videoH + " (CRF " + crf + ")...");
                    SwingUtilities.invokeLater(() -> progressBar.setValue(65));

                    ProcessBuilder pb = new ProcessBuilder(
                            "ffmpeg", "-y",
                            "-framerate", String.valueOf(fps),
                            "-i", new File(tempDir, "frame_%05d.png").getAbsolutePath(),
                            "-c:v", "libx264",
                            "-preset", "slow",
                            "-crf", String.valueOf(crf),
                            "-pix_fmt", "yuv420p",
                            "-movflags", "+faststart",
                            finalOut.getAbsolutePath());
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();

                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.contains("frame=")) {
                                publish("Encoding: " + line.trim());
                            }
                        }
                    }

                    SwingUtilities.invokeLater(() -> progressBar.setValue(90));

                    int exit = proc.waitFor();
                    if (exit != 0) {
                        throw new IOException(
                                "ffmpeg failed (exit " + exit + ").\n" +
                                        "Ensure ffmpeg is installed with H.264 (libx264) support.\n" +
                                        "Download: https://ffmpeg.org/download.html");
                    }

                    SwingUtilities.invokeLater(() -> progressBar.setValue(100));

                    long fileSize = finalOut.length();
                    double sizeMB = fileSize / (1024.0 * 1024.0);
                    double totalDurationSec = (slides.size() * duration) / 1000.0;

                    finalInfo = String.format(
                            "✅ MP4 Video created successfully!\n\n" +
                                    "Resolution: %d×%d\n" +
                                    "Quality: CRF %d\n" +
                                    "Size: %.2f MB\n" +
                                    "Slides: %d (%d frames at %d fps)\n" +
                                    "Duration: %.1f seconds\n\n" +
                                    "File: %s\n\n" +
                                    "Upload to Twitter/X for fullscreen playback!",
                            videoW, videoH, crf, sizeMB, slides.size(),
                            totalFrames, fps, totalDurationSec,
                            finalOut.getAbsolutePath());

                } catch (Exception ex) {
                    errorMsg = ex.getMessage();
                    ex.printStackTrace();
                } finally {
                    if (tempDir != null) {
                        cleanupTempDir(tempDir);
                    }
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                progressLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                if (errorMsg != null) {
                    JOptionPane.showMessageDialog(GifSlideShowApp.this,
                            "Error:\n" + errorMsg, "Error", JOptionPane.ERROR_MESSAGE);
                } else if (finalInfo != null) {
                    String[] btns = {"Open Folder", "OK"};
                    int ch = JOptionPane.showOptionDialog(GifSlideShowApp.this,
                            finalInfo, "MP4 Video Created",
                            JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                            null, btns, btns[0]);
                    if (ch == 0) {
                        try {
                            Desktop.getDesktop().open(finalOut.getParentFile());
                        } catch (IOException ex) {
                            JOptionPane.showMessageDialog(GifSlideShowApp.this,
                                    "Could not open folder.", "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }
        };
        worker.execute();
        progressDialog.setVisible(true);
    }

    // ==================== GIF Encoding ====================

    private void writeAnimatedGif(List<BufferedImage> frames, int delayMs, File output) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);

            for (int i = 0; i < frames.size(); i++) {
                BufferedImage indexed = convertToIndexed(frames.get(i));
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        new ImageTypeSpecifier(indexed), param);
                configureGifMetadata(metadata, delayMs / 10, i == 0);
                writer.writeToSequence(new IIOImage(indexed, null, metadata), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private void writeGifWithFfmpeg(List<BufferedImage> frames, int delayMs, File output) throws IOException {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "gif_frames_" + System.currentTimeMillis());
        if (!tempDir.mkdirs() && !tempDir.exists()) {
            throw new IOException("Failed to create temp directory: " + tempDir);
        }
        try {
            for (int i = 0; i < frames.size(); i++) {
                ImageIO.write(frames.get(i), "png", new File(tempDir, String.format("frame_%04d.png", i)));
            }
            double fps = 1000.0 / delayMs;
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-framerate", String.valueOf(fps),
                    "-i", new File(tempDir, "frame_%04d.png").getAbsolutePath(),
                    "-vf", "split[s0][s1];[s0]palettegen=max_colors=256:stats_mode=full[p];[s1][p]paletteuse=dither=floyd_steinberg",
                    "-loop", "0",
                    output.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                while (br.readLine() != null) { /* drain */ }
            }
            int exit = proc.waitFor();
            if (exit != 0) throw new IOException("ffmpeg failed (exit " + exit + "). Is ffmpeg installed?");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private boolean tryAggressiveFfmpeg(List<SlideData> slides, int duration, File output, long maxSize) {
        try {
            int[][] attempts = {{1280, 720}, {960, 540}, {640, 360}};
            int[] colors = {192, 128, 96};

            for (int[] res : attempts) {
                for (int maxColors : colors) {
                    File tempDir = new File(System.getProperty("java.io.tmpdir"),
                            "gif_opt_" + System.currentTimeMillis());
                    if (!tempDir.mkdirs() && !tempDir.exists()) continue;
                    try {
                        for (int i = 0; i < slides.size(); i++) {
                            SlideData s = slides.get(i);
                            BufferedImage frame = renderFrame(
                                    s.image, s.text, s.fontName, s.fontSize,
                                    s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                    res[0], res[1], s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                    s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                    s.slideNumberX, s.slideNumberY,
                                    s.slideNumberSize, s.slideNumberColor);
                            ImageIO.write(frame, "png",
                                    new File(tempDir, String.format("frame_%04d.png", i)));
                        }

                        double fps = 1000.0 / duration;
                        String palette = String.format(
                                "split[s0][s1];[s0]palettegen=max_colors=%d:stats_mode=diff[p];[s1][p]paletteuse=dither=floyd_steinberg:diff_mode=rectangle",
                                maxColors);
                        ProcessBuilder pb = new ProcessBuilder(
                                "ffmpeg", "-y",
                                "-framerate", String.valueOf(fps),
                                "-i", new File(tempDir, "frame_%04d.png").getAbsolutePath(),
                                "-vf", palette,
                                "-loop", "0",
                                output.getAbsolutePath());
                        pb.redirectErrorStream(true);
                        Process proc = pb.start();
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(proc.getInputStream()))) {
                            while (br.readLine() != null) { /* drain */ }
                        }
                        proc.waitFor();

                        if (output.length() <= maxSize) return true;
                    } finally {
                        cleanupTempDir(tempDir);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private static void cleanupTempDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.delete();
    }

    private BufferedImage convertToIndexed(BufferedImage src) {
        BufferedImage indexed = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_INDEXED);
        Graphics2D g = indexed.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return indexed;
    }

    private void configureGifMetadata(IIOMetadata metadata, int delayCs, boolean first) throws IOException {
        String fmt = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(fmt);

        IIOMetadataNode gce = getOrCreateNode(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("delayTime", String.valueOf(delayCs));
        gce.setAttribute("transparentColorIndex", "0");

        if (first) {
            IIOMetadataNode appExts = getOrCreateNode(root, "ApplicationExtensions");
            IIOMetadataNode appExt = new IIOMetadataNode("ApplicationExtension");
            appExt.setAttribute("applicationID", "NETSCAPE");
            appExt.setAttribute("authenticationCode", "2.0");
            appExt.setUserObject(new byte[]{1, 0, 0});
            appExts.appendChild(appExt);
        }
        metadata.setFromTree(fmt, root);
    }

    private IIOMetadataNode getOrCreateNode(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name))
                return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    private void showPreview(File gifFile) {
        JDialog dialog = new JDialog(this, "GIF Preview", true);
        dialog.setSize(PREVIEW_WIDTH + 40, PREVIEW_HEIGHT + 80);
        dialog.setLocationRelativeTo(this);
        ImageIcon icon = new ImageIcon(gifFile.getAbsolutePath());
        Image scaled = icon.getImage().getScaledInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, Image.SCALE_DEFAULT);
        JLabel label = new JLabel(new ImageIcon(scaled));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(21, 32, 43));
        panel.add(label, BorderLayout.CENTER);
        dialog.setContentPane(panel);
        dialog.setVisible(true);
    }

    // ==================== SlideData ====================

    private static class SlideData {
        final BufferedImage image;
        final String text;
        final String fontName;
        final int fontSize;
        final int fontStyle;
        final Color fontColor;
        final int alignment;
        final boolean showPin;
        final String displayMode;
        final int subtitleY;
        final int subtitleBgOpacity;
        final boolean showSlideNumber;
        final String slideNumberText;
        final String slideNumberFontName;
        final int slideNumberX;
        final int slideNumberY;
        final int slideNumberSize;
        final Color slideNumberColor;

        SlideData(BufferedImage image, String text, String fontName, int fontSize,
                  int fontStyle, Color fontColor, int alignment, boolean showPin, String displayMode,
                  int subtitleY, int subtitleBgOpacity,
                  boolean showSlideNumber, String slideNumberText, String slideNumberFontName,
                  int slideNumberX, int slideNumberY,
                  int slideNumberSize, Color slideNumberColor) {
            this.image = image;
            this.text = text;
            this.fontName = fontName;
            this.fontSize = fontSize;
            this.fontStyle = fontStyle;
            this.fontColor = fontColor;
            this.alignment = alignment;
            this.showPin = showPin;
            this.displayMode = displayMode;
            this.subtitleY = subtitleY;
            this.subtitleBgOpacity = subtitleBgOpacity;
            this.showSlideNumber = showSlideNumber;
            this.slideNumberText = slideNumberText;
            this.slideNumberFontName = slideNumberFontName;
            this.slideNumberX = slideNumberX;
            this.slideNumberY = slideNumberY;
            this.slideNumberSize = slideNumberSize;
            this.slideNumberColor = slideNumberColor;
        }
    }

    // ==================== SlideRow ====================

    private class SlideRow {
        private final JPanel panel;
        private final JLabel numberLabel;
        private final JLabel imagePreview;
        private final JTextArea textArea;
        private final JComboBox<String> fontCombo;
        private final JSpinner sizeSpinner;
        private final JToggleButton boldBtn;
        private final JToggleButton italicBtn;
        private final JButton colorBtn;
        private final JComboBox<String> alignCombo;
        private final JCheckBox pinCheckBox;
        private final JComboBox<String> displayModeCombo;
        private final JCheckBox slideNumberCheckBox;
        private final JTextField slideNumberField;
        private final JComboBox<String> slideNumberFontCombo;
        private final JSpinner slideNumberXSpinner;
        private final JSpinner slideNumberYSpinner;
        private final JSpinner slideNumberSizeSpinner;
        private final JButton slideNumberColorBtn;
        private Color slideNumberColor = Color.WHITE;
        private final JSpinner subtitleYSpinner;
        private final JSpinner subtitleBgOpacitySpinner;
        private final JLabel livePreviewLabel;
        private BufferedImage loadedImage;
        private Color selectedColor = Color.WHITE;
        private final Timer previewTimer;

        boolean isTitleGridSlide = false;
        private int gridLayoutIndex = 1;
        private List<BufferedImage> gridSourceImages = null;









        SlideRow(int number) {
            panel = new JPanel(new BorderLayout(10, 0));
            panel.setBackground(new Color(44, 47, 51));
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(60, 63, 68), 1, true),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 380));
            panel.setPreferredSize(new Dimension(1100, 370));

            previewTimer = new Timer(150, e -> updateLivePreview());
            previewTimer.setRepeats(false);

            // LEFT: number + controls
            JPanel leftCtrl = new JPanel();
            leftCtrl.setLayout(new BoxLayout(leftCtrl, BoxLayout.Y_AXIS));
            leftCtrl.setBackground(new Color(44, 47, 51));
            leftCtrl.setPreferredSize(new Dimension(40, 0));

            numberLabel = new JLabel(String.valueOf(number), SwingConstants.CENTER);
            numberLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
            numberLabel.setForeground(new Color(29, 161, 242));
            numberLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            JButton upBtn = makeSmallButton("▲");
            upBtn.addActionListener(e -> moveSlideRow(this, -1));
            JButton downBtn = makeSmallButton("▼");
            downBtn.addActionListener(e -> moveSlideRow(this, 1));
            JButton delBtn = makeSmallButton("✕");
            delBtn.setForeground(new Color(255, 80, 80));
            delBtn.addActionListener(e -> removeSlideRow(this));

            leftCtrl.add(numberLabel);
            leftCtrl.add(Box.createVerticalStrut(8));
            leftCtrl.add(upBtn);
            leftCtrl.add(Box.createVerticalStrut(4));
            leftCtrl.add(downBtn);
            leftCtrl.add(Box.createVerticalGlue());
            leftCtrl.add(delBtn);

            // CENTER: image drop + live preview
            JPanel centerPanel = new JPanel(new BorderLayout(0, 6));
            centerPanel.setBackground(new Color(44, 47, 51));

            imagePreview = new JLabel("Drag image here or click to browse", SwingConstants.CENTER);
            imagePreview.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            imagePreview.setForeground(new Color(160, 170, 180));
            imagePreview.setPreferredSize(new Dimension(260, 150));
            imagePreview.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createDashedBorder(new Color(80, 90, 100), 2, 6, 4, true),
                    BorderFactory.createEmptyBorder(6, 6, 6, 6)));
            imagePreview.setOpaque(true);
            imagePreview.setBackground(new Color(35, 38, 42));
            imagePreview.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            imagePreview.setVerticalTextPosition(SwingConstants.BOTTOM);
            imagePreview.setHorizontalTextPosition(SwingConstants.CENTER);
            imagePreview.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) { browseImage(); }
            });

            new DropTarget(imagePreview, new DropTargetAdapter() {
                @Override
                public void drop(DropTargetDropEvent dtde) {
                    try {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) dtde.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty()) loadImage(files.get(0));
                        dtde.dropComplete(true);
                    } catch (Exception ex) {
                        dtde.dropComplete(false);
                    }
                }
            });

            livePreviewLabel = new JLabel("Live Preview", SwingConstants.CENTER);
            livePreviewLabel.setFont(new Font("Segoe UI", Font.ITALIC, 10));
            livePreviewLabel.setForeground(new Color(120, 130, 140));
            livePreviewLabel.setPreferredSize(new Dimension(260, 146));
            livePreviewLabel.setBorder(BorderFactory.createLineBorder(new Color(60, 63, 68)));
            livePreviewLabel.setOpaque(true);
            livePreviewLabel.setBackground(new Color(21, 32, 43));

            centerPanel.add(imagePreview, BorderLayout.CENTER);
            centerPanel.add(livePreviewLabel, BorderLayout.SOUTH);

            // RIGHT: formatting + text
            JPanel rightPanel = new JPanel(new BorderLayout(0, 6));
            rightPanel.setBackground(new Color(44, 47, 51));
            rightPanel.setPreferredSize(new Dimension(570, 0));

            JPanel toolbar1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            toolbar1.setBackground(new Color(44, 47, 51));

            String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            fontCombo = new JComboBox<>(fonts);
            fontCombo.setSelectedItem("Segoe UI");
            fontCombo.setPreferredSize(new Dimension(140, 28));
            fontCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fontCombo.addActionListener(e -> onFormatChanged());

            sizeSpinner = new JSpinner(new SpinnerNumberModel(28, 8, 120, 2));
            sizeSpinner.setPreferredSize(new Dimension(55, 28));
            sizeSpinner.addChangeListener(e -> onFormatChanged());

            boldBtn = new JToggleButton("B");
            boldBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
            boldBtn.setPreferredSize(new Dimension(36, 28));
            boldBtn.setFocusPainted(false);
            boldBtn.addActionListener(e -> onFormatChanged());

            italicBtn = new JToggleButton("I");
            italicBtn.setFont(new Font("Segoe UI", Font.ITALIC, 13));
            italicBtn.setPreferredSize(new Dimension(36, 28));
            italicBtn.setFocusPainted(false);
            italicBtn.addActionListener(e -> onFormatChanged());

            colorBtn = new JButton("■");
            colorBtn.setForeground(selectedColor);
            colorBtn.setFont(new Font("Segoe UI", Font.PLAIN, 18));
            colorBtn.setPreferredSize(new Dimension(36, 28));
            colorBtn.setFocusPainted(false);
            colorBtn.setToolTipText("Text Color");
            colorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(panel, "Text Color", selectedColor);
                if (c != null) {
                    selectedColor = c;
                    colorBtn.setForeground(c);
                    onFormatChanged();
                }
            });

            alignCombo = new JComboBox<>(new String[]{"Left", "Center", "Right"});
            alignCombo.setSelectedIndex(0);
            alignCombo.setPreferredSize(new Dimension(80, 28));
            alignCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            alignCombo.addActionListener(e -> onFormatChanged());

            pinCheckBox = new JCheckBox("📍 Pin", true);
            pinCheckBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            pinCheckBox.setForeground(Color.LIGHT_GRAY);
            pinCheckBox.setBackground(new Color(44, 47, 51));
            pinCheckBox.setFocusPainted(false);
            pinCheckBox.addActionListener(e -> onFormatChanged());

            displayModeCombo = new JComboBox<>(new String[]{
                    "Blur-Fit", "Fill (Crop)", "Fit (Bars)", "Original Size",
                    "Stretch", "Top-Fit", "Bottom-Fit", "Left-Fit", "Right-Fit"
            });
            displayModeCombo.setSelectedIndex(0);
            displayModeCombo.setPreferredSize(new Dimension(110, 28));
            displayModeCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            displayModeCombo.addActionListener(e -> onFormatChanged());


            toolbar1.add(styledLabel("Font:"));
            toolbar1.add(fontCombo);
            toolbar1.add(styledLabel("Size:"));
            toolbar1.add(sizeSpinner);
            toolbar1.add(boldBtn);
            toolbar1.add(italicBtn);
            toolbar1.add(colorBtn);
            toolbar1.add(alignCombo);
            toolbar1.add(pinCheckBox);

            // ===== Toolbar Row 2: Image display mode =====
            JPanel toolbar2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            toolbar2.setBackground(new Color(44, 47, 51));

            JLabel displayLabel = styledLabel("🖼 Image Display:");
            displayLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
            displayLabel.setForeground(new Color(29, 161, 242));
            toolbar2.add(displayLabel);
            toolbar2.add(displayModeCombo);

            subtitleYSpinner = new JSpinner(new SpinnerNumberModel(5, 0, 100, 1));
            subtitleYSpinner.setPreferredSize(new Dimension(50, 28));
            subtitleYSpinner.setToolTipText("Subtitle vertical position (% from bottom)");
            subtitleYSpinner.addChangeListener(e -> onFormatChanged());

            subtitleBgOpacitySpinner = new JSpinner(new SpinnerNumberModel(78, 0, 100, 5));
            subtitleBgOpacitySpinner.setPreferredSize(new Dimension(50, 28));
            subtitleBgOpacitySpinner.setToolTipText("Subtitle background opacity (0=transparent, 100=solid)");
            subtitleBgOpacitySpinner.addChangeListener(e -> onFormatChanged());

            toolbar2.add(styledLabel("Text Y%:"));
            toolbar2.add(subtitleYSpinner);
            toolbar2.add(styledLabel("BG%:"));
            toolbar2.add(subtitleBgOpacitySpinner);

            // ===== Toolbar Row 3: Slide number overlay =====
            JPanel toolbar3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            toolbar3.setBackground(new Color(44, 47, 51));

            slideNumberCheckBox = new JCheckBox("# Number", false);
            slideNumberCheckBox.setFont(new Font("Segoe UI", Font.BOLD, 11));
            slideNumberCheckBox.setForeground(new Color(255, 200, 0));
            slideNumberCheckBox.setBackground(new Color(44, 47, 51));
            slideNumberCheckBox.setFocusPainted(false);
            slideNumberCheckBox.addActionListener(e -> onFormatChanged());

            slideNumberField = new JTextField(String.valueOf(number), 3);
            slideNumberField.setFont(new Font("Segoe UI", Font.BOLD, 12));
            slideNumberField.setPreferredSize(new Dimension(40, 28));
            slideNumberField.setToolTipText("Number to display");
            slideNumberField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { schedulePreview(); }
                @Override public void removeUpdate(DocumentEvent e) { schedulePreview(); }
                @Override public void changedUpdate(DocumentEvent e) { schedulePreview(); }
            });

            slideNumberFontCombo = new JComboBox<>(loadedFontNames);
            slideNumberFontCombo.setPreferredSize(new Dimension(105, 28));
            slideNumberFontCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideNumberFontCombo.setToolTipText("Number font (from parent directory)");
            slideNumberFontCombo.addActionListener(e -> onFormatChanged());

            slideNumberXSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
            slideNumberXSpinner.setPreferredSize(new Dimension(50, 28));
            slideNumberXSpinner.setToolTipText("X position (% of width)");
            slideNumberXSpinner.addChangeListener(e -> onFormatChanged());

            slideNumberYSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 100, 1));
            slideNumberYSpinner.setPreferredSize(new Dimension(50, 28));
            slideNumberYSpinner.setToolTipText("Y position (% of height)");
            slideNumberYSpinner.addChangeListener(e -> onFormatChanged());

            slideNumberSizeSpinner = new JSpinner(new SpinnerNumberModel(80, 10, 500, 5));
            slideNumberSizeSpinner.setPreferredSize(new Dimension(55, 28));
            slideNumberSizeSpinner.setToolTipText("Number font size");
            slideNumberSizeSpinner.addChangeListener(e -> onFormatChanged());

            slideNumberColorBtn = new JButton("■");
            slideNumberColorBtn.setForeground(slideNumberColor);
            slideNumberColorBtn.setFont(new Font("Segoe UI", Font.PLAIN, 18));
            slideNumberColorBtn.setPreferredSize(new Dimension(36, 28));
            slideNumberColorBtn.setFocusPainted(false);
            slideNumberColorBtn.setToolTipText("Number Color");
            slideNumberColorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(panel, "Number Color", slideNumberColor);
                if (c != null) {
                    slideNumberColor = c;
                    slideNumberColorBtn.setForeground(c);
                    onFormatChanged();
                }
            });

            toolbar3.add(slideNumberCheckBox);
            toolbar3.add(slideNumberField);
            toolbar3.add(slideNumberColorBtn);
            toolbar3.add(slideNumberFontCombo);
            toolbar3.add(styledLabel("X%:"));
            toolbar3.add(slideNumberXSpinner);
            toolbar3.add(styledLabel("Y%:"));
            toolbar3.add(slideNumberYSpinner);
            toolbar3.add(styledLabel("Size:"));
            toolbar3.add(slideNumberSizeSpinner);

            textArea = new JTextArea(6, 20);
            textArea.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setBackground(new Color(35, 38, 42));
            textArea.setForeground(Color.WHITE);
            textArea.setCaretColor(Color.WHITE);
            textArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            textArea.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { schedulePreview(); }
                @Override public void removeUpdate(DocumentEvent e) { schedulePreview(); }
                @Override public void changedUpdate(DocumentEvent e) { schedulePreview(); }
            });

            JScrollPane textScroll = new JScrollPane(textArea);
            textScroll.setBorder(BorderFactory.createLineBorder(new Color(60, 63, 68)));


            JPanel toolbarsPanel = new JPanel();
            toolbarsPanel.setLayout(new BoxLayout(toolbarsPanel, BoxLayout.Y_AXIS));
            toolbarsPanel.setBackground(new Color(44, 47, 51));
            toolbarsPanel.add(toolbar1);
            toolbarsPanel.add(toolbar2);
            toolbarsPanel.add(toolbar3);

            rightPanel.add(toolbarsPanel, BorderLayout.NORTH);
            rightPanel.add(textScroll, BorderLayout.CENTER);

            panel.add(leftCtrl, BorderLayout.WEST);
            panel.add(centerPanel, BorderLayout.CENTER);
            panel.add(rightPanel, BorderLayout.EAST);
        }

        private void onFormatChanged() {
            schedulePreview();
            updateTextAreaStyle();
            if (!isSyncingFormat && !isTitleGridSlide && !slideRows.isEmpty()) {
                for (SlideRow row : slideRows) {
                    if (!row.isTitleGridSlide) {
                        if (row == this) {
                            syncFormattingFromFirstSlide();
                        }
                        break;
                    }
                }
            }
        }

        private JLabel styledLabel(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(Color.LIGHT_GRAY);
            l.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            return l;
        }

        private void schedulePreview() {
            previewTimer.restart();
        }

        private void updateTextAreaStyle() {
            String fontName = (String) fontCombo.getSelectedItem();
            int size = Math.min((int) sizeSpinner.getValue(), 40);
            int style = Font.PLAIN;
            if (boldBtn.isSelected()) style |= Font.BOLD;
            if (italicBtn.isSelected()) style |= Font.ITALIC;
            textArea.setFont(new Font(fontName, style, Math.max(size, 12)));
            textArea.setForeground(selectedColor);
        }

        void applyFormatting(String fontName, int fontSize, int fontStyle,
                             Color fontColor, int alignment, boolean showPin, String displayMode,
                             int subtitleY, int subtitleBgOpacity,
                             boolean showSlideNumber, String slideNumberFontName,
                             int slideNumberX, int slideNumberY,
                             int slideNumberSize, Color slideNumberColor) {
            fontCombo.setSelectedItem(fontName);
            sizeSpinner.setValue(fontSize);
            boldBtn.setSelected((fontStyle & Font.BOLD) != 0);
            italicBtn.setSelected((fontStyle & Font.ITALIC) != 0);
            selectedColor = fontColor;
            colorBtn.setForeground(fontColor);

            switch (alignment) {
                case SwingConstants.CENTER: alignCombo.setSelectedIndex(1); break;
                case SwingConstants.RIGHT:  alignCombo.setSelectedIndex(2); break;
                default:                    alignCombo.setSelectedIndex(0); break;
            }

            pinCheckBox.setSelected(showPin);
            displayModeCombo.setSelectedItem(displayMode);
            subtitleYSpinner.setValue(subtitleY);
            subtitleBgOpacitySpinner.setValue(subtitleBgOpacity);

            slideNumberCheckBox.setSelected(showSlideNumber);
            slideNumberFontCombo.setSelectedItem(slideNumberFontName);
            slideNumberXSpinner.setValue(slideNumberX);
            slideNumberYSpinner.setValue(slideNumberY);
            slideNumberSizeSpinner.setValue(slideNumberSize);
            this.slideNumberColor = slideNumberColor;
            slideNumberColorBtn.setForeground(slideNumberColor);

            updateTextAreaStyle();
            schedulePreview();
        }

        void markAsTitleGrid() {
            isTitleGridSlide = true;
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(60, 160, 200), 2, true),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            numberLabel.setForeground(new Color(60, 160, 200));
        }

        BufferedImage regenerateGridImage(int w, int h) {
            if (gridSourceImages == null || gridSourceImages.isEmpty()) return loadedImage;
            return generateGridImage(gridSourceImages, w, h, gridLayoutIndex);
        }

        private void updateLivePreview() {
            if (loadedImage == null) {
                livePreviewLabel.setIcon(null);
                livePreviewLabel.setText("Live Preview (add image first)");
                return;
            }
            BufferedImage frameImage = loadedImage;
            if (isTitleGridSlide && gridSourceImages != null) {
                frameImage = regenerateGridImage(PREVIEW_WIDTH, PREVIEW_HEIGHT);
            }
            BufferedImage preview = renderFrame(
                    frameImage, textArea.getText(),
                    getSelectedFont(), getFontSize(), getFontStyle(),
                    getFontColor(), getTextAlignment(), isShowPin(),
                    PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    isTitleGridSlide ? "Direct" : getDisplayMode(), getSubtitleY(),
                    getSubtitleBgOpacity(),
                    isShowSlideNumber(), getSlideNumberText(), getSlideNumberFontName(),
                    getSlideNumberX(), getSlideNumberY(),
                    getSlideNumberSize(), getSlideNumberColor());

            int lw = livePreviewLabel.getWidth();
            int lh = livePreviewLabel.getHeight();
            if (lw < 50) lw = 260;
            if (lh < 50) lh = 146;
            double sc = Math.min((double) lw / preview.getWidth(), (double) lh / preview.getHeight());
            Image scaled = preview.getScaledInstance(
                    Math.max(1, (int) (preview.getWidth() * sc)),
                    Math.max(1, (int) (preview.getHeight() * sc)),
                    Image.SCALE_SMOOTH);
            livePreviewLabel.setIcon(new ImageIcon(scaled));
            livePreviewLabel.setText(null);
        }

        private JButton makeSmallButton(String text) {
            JButton btn = new JButton(text);
            btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            btn.setPreferredSize(new Dimension(32, 24));
            btn.setMaximumSize(new Dimension(32, 24));
            btn.setAlignmentX(Component.CENTER_ALIGNMENT);
            btn.setMargin(new Insets(0, 0, 0, 0));
            btn.setFocusPainted(false);
            return btn;
        }

        private void browseImage() {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter(
                    "Images (jpg, png, gif, bmp, webp, avif, heif)",
                    "jpg", "jpeg", "png", "gif", "bmp", "webp", "avif", "heif", "heic"));
            if (fc.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION)
                loadImage(fc.getSelectedFile());
        }

        private void loadImage(File file) {
            try {
                loadedImage = loadImageFile(file);
                if (loadedImage == null) {
                    JOptionPane.showMessageDialog(panel, "Cannot read: " + file.getName(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                updateImagePreviewThumb(file.getName());
                schedulePreview();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(panel, "Error:\n" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        void setImageDirectly(BufferedImage img, String fileName) {
            this.loadedImage = img;
            updateImagePreviewThumb(fileName);
            schedulePreview();
        }

        private void updateImagePreviewThumb(String fileName) {
            if (loadedImage == null) return;
            int pw = imagePreview.getWidth() - 16;
            int ph = imagePreview.getHeight() - 16;
            if (pw < 50) pw = 240;
            if (ph < 50) ph = 130;
            double sc = Math.min((double) pw / loadedImage.getWidth(), (double) ph / loadedImage.getHeight());
            Image thumb = loadedImage.getScaledInstance(
                    Math.max(1, (int) (loadedImage.getWidth() * sc)),
                    Math.max(1, (int) (loadedImage.getHeight() * sc)),
                    Image.SCALE_SMOOTH);
            imagePreview.setIcon(new ImageIcon(thumb));
            imagePreview.setText(fileName);
        }

        void setSubtitleText(String text) {
            textArea.setText(text);
        }

        void updateNumber(int n) {
            String prefix = isTitleGridSlide ? "🖼 " : "";
            numberLabel.setText(prefix + n);
        }

        JPanel getPanel() { return panel; }
        BufferedImage getImage() { return loadedImage; }
        String getSubtitleText() { return textArea.getText(); }
        String getSelectedFont() { return (String) fontCombo.getSelectedItem(); }
        int getFontSize() { return (int) sizeSpinner.getValue(); }
        Color getFontColor() { return selectedColor; }
        boolean isShowPin() { return pinCheckBox.isSelected(); }
        String getDisplayMode() { return (String) displayModeCombo.getSelectedItem(); }
        int getSubtitleY() { return (int) subtitleYSpinner.getValue(); }
        int getSubtitleBgOpacity() { return (int) subtitleBgOpacitySpinner.getValue(); }

        int getFontStyle() {
            int s = Font.PLAIN;
            if (boldBtn.isSelected()) s |= Font.BOLD;
            if (italicBtn.isSelected()) s |= Font.ITALIC;
            return s;
        }

        int getTextAlignment() {
            switch (alignCombo.getSelectedIndex()) {
                case 1: return SwingConstants.CENTER;
                case 2: return SwingConstants.RIGHT;
                default: return SwingConstants.LEFT;
            }
        }

        boolean isShowSlideNumber() { return slideNumberCheckBox.isSelected(); }
        String getSlideNumberText() { return slideNumberField.getText().trim(); }
        String getSlideNumberFontName() { return (String) slideNumberFontCombo.getSelectedItem(); }
        int getSlideNumberX() { return (int) slideNumberXSpinner.getValue(); }
        int getSlideNumberY() { return (int) slideNumberYSpinner.getValue(); }
        int getSlideNumberSize() { return (int) slideNumberSizeSpinner.getValue(); }
        Color getSlideNumberColor() { return slideNumberColor; }
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new GifSlideShowApp().setVisible(true));
    }
}
