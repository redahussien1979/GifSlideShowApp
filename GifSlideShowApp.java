
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

    // Orientation: "Landscape" or "Portrait"
    private JComboBox<String> orientationCombo;
    private JLabel header;

    private boolean isPortrait() {
        return orientationCombo != null && "Portrait (1080×1920)".equals(orientationCombo.getSelectedItem());
    }

    private int getOutputWidth() {
        return isPortrait() ? GIF_HEIGHT : GIF_WIDTH; // 1080 or 1920
    }

    private int getOutputHeight() {
        return isPortrait() ? GIF_WIDTH : GIF_HEIGHT; // 1920 or 1080
    }

    private int getPreviewWidth() {
        return isPortrait() ? PREVIEW_HEIGHT : PREVIEW_WIDTH; // 360 or 640
    }

    private int getPreviewHeight() {
        return isPortrait() ? PREVIEW_WIDTH : PREVIEW_HEIGHT; // 640 or 360
    }

    public GifSlideShowApp() {
        super("GIF/Video Slide Show Creator — YouTube HD (1920×1080)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 780);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(900, 600));

        JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        mainPanel.setBackground(new Color(30, 30, 30));

        header = new JLabel("GIF / Video Slide Show Creator — YouTube HD (1920×1080)", SwingConstants.CENTER);
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

        // Orientation selector
        JLabel orientLabel = new JLabel("Orientation:");
        orientLabel.setForeground(Color.LIGHT_GRAY);
        orientLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        orientationCombo = new JComboBox<>(new String[]{
                "Landscape (1920×1080)", "Portrait (1080×1920)"});
        orientationCombo.setPreferredSize(new Dimension(170, 30));
        orientationCombo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        orientationCombo.addActionListener(e -> {
            String dims = isPortrait() ? "1080×1920" : "1920×1080";
            String orientText = isPortrait() ? "Portrait" : "Landscape";
            setTitle("GIF/Video Slide Show Creator — YouTube HD " + orientText + " (" + dims + ")");
            header.setText("GIF / Video Slide Show Creator — YouTube HD " + orientText + " (" + dims + ")");
            // Refresh all live previews
            for (SlideRow row : slideRows) {
                row.schedulePreview();
            }
        });

        bottomPanel.add(orientLabel);
        bottomPanel.add(orientationCombo);
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

        BufferedImage gridImage = generateGridImage(images, getOutputWidth(), getOutputHeight(), layoutIndex);

        // Optionally pick a background image
        BufferedImage bgImage = null;
        int bgChoice = JOptionPane.showConfirmDialog(this,
                "Would you like to add a background image?\n" +
                "The selected display mode effect (Blur-Fit, Fill Crop, etc.)\nwill be applied to it behind the grid.",
                "Background Image", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (bgChoice == JOptionPane.YES_OPTION) {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Images (jpg, png, gif, bmp, webp, avif, heif)",
                    "jpg", "jpeg", "png", "gif", "bmp", "webp", "avif", "heif", "heic"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    bgImage = loadImageFile(fc.getSelectedFile());
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Error loading background: " + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }

        slideRows.removeIf(r -> r.isTitleGridSlide);

        SlideRow titleRow = new SlideRow(1);
        titleRow.isTitleGridSlide = true;
        titleRow.gridLayoutIndex = layoutIndex;
        titleRow.gridSourceImages = new ArrayList<>(images);
        titleRow.titleBgImage = bgImage;
        titleRow.setImageDirectly(gridImage, bgImage != null ?
                "📸 Layout " + layoutIndex + " + BG image" :
                "📸 Layout " + layoutIndex + " (" + images.size() + " images)");
        titleRow.setSubtitleText(titleText);
        titleRow.applyFormatting("Segoe UI", 48, Font.BOLD,
                Color.WHITE, SwingConstants.CENTER, false, "Blur-Fit", 5, 78,
                false, loadedFontNames[0], 50, 10, 80, Color.WHITE,
                Collections.singletonList(new SlideTextData(false, "", "Segoe UI", 40, Font.PLAIN,
                        Color.YELLOW, 50, 50, 0, Color.BLACK, false, 100, 0, SwingConstants.CENTER)),
                false, 60, false, 50, false, 100, false, 50, false, 50, false, 50, false, 50,
                "Rectangular", "Blur", new Color(21, 32, 43), 50, 50, 20,
                false, 100, "", new Color(255, 255, 0, 180), 0);

        slideRows.add(0, titleRow);
        rebuildSlidesPanel();

        String bgMsg = bgImage != null ? "\nBackground image added — change effect via the display mode dropdown." : "";
        JOptionPane.showMessageDialog(this,
                "Title grid slide created with layout #" + layoutIndex + " and " + images.size() + " images!" + bgMsg,
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
        // First ask: import to Subtitle Text or Slide Text?
        String[] targetOptions = {"Subtitle Text (Normal)", "Slide Text"};
        int targetChoice = JOptionPane.showOptionDialog(this,
                "Where do you want to import the text?",
                "Bulk Import Text", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, targetOptions, targetOptions[0]);

        if (targetChoice < 0) return;

        boolean importToSlideText = (targetChoice == 1);

        // Then ask: from file or clipboard?
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
                if (importToSlideText) {
                    targetSlides.get(i).setSlideText(line);
                } else {
                    targetSlides.get(i).setSubtitleText(line);
                }
            } else {
                SlideRow row = new SlideRow(slideRows.size() + 1);
                if (importToSlideText) {
                    row.setSlideText(line);
                } else {
                    row.setSubtitleText(line);
                }
                slideRows.add(row);
                slidesPanel.add(row.getPanel());
                slidesPanel.add(Box.createRigidArea(new Dimension(0, 10)));
            }
            assigned++;
        }

        applyFirstSlideFormattingToAll();
        slidesPanel.revalidate();
        slidesPanel.repaint();

        String targetLabel = importToSlideText ? "slide text" : "subtitle text";
        JOptionPane.showMessageDialog(this,
                assigned + " lines assigned to " + targetLabel + ".\nSlides: " + slideRows.size() + " total.",
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
        List<SlideTextData> slideTextFormats = source.getSlideTextFormats();
        boolean fxRoundCorners = source.isFxRoundCorners();
        int fxCornerRadius = source.getFxCornerRadius();
        boolean fxVignetteOn = source.fxVignetteCheck.isSelected();
        int fxVignetteVal = source.getFxVignetteRaw();
        boolean fxSepiaOn = source.fxSepiaCheck.isSelected();
        int fxSepiaVal = source.getFxSepiaRaw();
        boolean fxGrainOn = source.fxGrainCheck.isSelected();
        int fxGrainVal = source.getFxGrainRaw();
        boolean fxWaterRippleOn = source.fxWaterRippleCheck.isSelected();
        int fxWaterRippleVal = source.getFxWaterRippleRaw();
        boolean fxGlitchOn = source.fxGlitchCheck.isSelected();
        int fxGlitchVal = source.getFxGlitchRaw();
        boolean fxShakeOn = source.fxShakeCheck.isSelected();
        int fxShakeVal = source.getFxShakeRaw();
        String overlayShape = source.getOverlayShape();
        String overlayBgMode = source.getOverlayBgMode();
        Color ovBgColor = source.getOverlayBgColor();
        int overlayX = source.getOverlayX();
        int overlayY = source.getOverlayY();
        int overlaySize = source.getOverlaySize();
        boolean textJustify = source.isTextJustify();
        int textWidthPct = source.getTextWidthPct();
        String highlightText = source.getHighlightText();
        Color hlColor = source.getHighlightColor();
        int textShiftX = source.getTextShiftX();

        isSyncingFormat = true;
        try {
            for (SlideRow row : slideRows) {
                if (row == source || row.isTitleGridSlide) continue;
                row.applyFormatting(fontName, fontSize, fontStyle, fontColor, alignment, showPin, displayMode, subtitleY, subtitleBgOpacity,
                        showSlideNumber, slideNumberFontName, slideNumberX, slideNumberY, slideNumberSize, slideNumberColor,
                        slideTextFormats,
                        fxRoundCorners, fxCornerRadius,
                        fxVignetteOn, fxVignetteVal, fxSepiaOn, fxSepiaVal,
                        fxGrainOn, fxGrainVal, fxWaterRippleOn, fxWaterRippleVal,
                        fxGlitchOn, fxGlitchVal, fxShakeOn, fxShakeVal,
                        overlayShape, overlayBgMode, ovBgColor, overlayX, overlayY, overlaySize,
                        textJustify, textWidthPct, highlightText, hlColor,
                        textShiftX);
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
                false, null, null, 0, 0, 0, null,
                null,
                false, 0, 0, 0, 0, 0, 0, 0,
                false, null, null, null, 0, 0, 0, 0,
                false, 100, null, null, 0);
    }

    static BufferedImage renderFrame(BufferedImage image, String text,
                                     String fontName, int fontSize, int fontStyle,
                                     Color fontColor, int alignment,
                                     boolean showPin, int targetW, int targetH,
                                     String displayMode) {
        return renderFrame(image, text, fontName, fontSize, fontStyle,
                fontColor, alignment, showPin, targetW, targetH, displayMode, 5, 78,
                false, null, null, 0, 0, 0, null,
                null,
                false, 0, 0, 0, 0, 0, 0, 0,
                false, null, null, null, 0, 0, 0, 0,
                false, 100, null, null, 0);
    }

    static BufferedImage renderFrame(BufferedImage image, String text,
                                     String fontName, int fontSize, int fontStyle,
                                     Color fontColor, int alignment,
                                     boolean showPin, int targetW, int targetH,
                                     String displayMode, int subtitleY, int subtitleBgOpacity,
                                     boolean showSlideNumber, String slideNumberText,
                                     String slideNumberFontName,
                                     int slideNumberX, int slideNumberY,
                                     int slideNumberSize, Color slideNumberColor,
                                     List<SlideTextData> slideTexts,
                                     boolean fxRoundCorners, int fxCornerRadius,
                                     int fxVignette, int fxSepia,
                                     int fxGrain, int fxWaterRipple,
                                     int fxGlitch, int fxShake) {
        return renderFrame(image, text, fontName, fontSize, fontStyle,
                fontColor, alignment, showPin, targetW, targetH, displayMode,
                subtitleY, subtitleBgOpacity,
                showSlideNumber, slideNumberText, slideNumberFontName,
                slideNumberX, slideNumberY, slideNumberSize, slideNumberColor,
                slideTexts,
                fxRoundCorners, fxCornerRadius, fxVignette, fxSepia,
                fxGrain, fxWaterRipple, fxGlitch, fxShake,
                false, null, null, null, 0, 0, 0,
                0,
                false, 100, null, null, 0);
    }

    static BufferedImage renderFrame(BufferedImage image, String text,
                                     String fontName, int fontSize, int fontStyle,
                                     Color fontColor, int alignment,
                                     boolean showPin, int targetW, int targetH,
                                     String displayMode, int subtitleY, int subtitleBgOpacity,
                                     boolean showSlideNumber, String slideNumberText,
                                     String slideNumberFontName,
                                     int slideNumberX, int slideNumberY,
                                     int slideNumberSize, Color slideNumberColor,
                                     List<SlideTextData> slideTexts,
                                     boolean fxRoundCorners, int fxCornerRadius,
                                     int fxVignette, int fxSepia,
                                     int fxGrain, int fxWaterRipple,
                                     int fxGlitch, int fxShake,
                                     boolean overlayEnabled,
                                     String overlayShape, String overlayBgMode, Color overlayBgColor,
                                     int overlayX, int overlayY, int overlaySize,
                                     int animFrameIndex,
                                     boolean textJustify, int textWidthPct,
                                     String highlightText, Color highlightColor,
                                     int textShiftX) {
        BufferedImage frame = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

        g.setColor(new Color(21, 32, 43));
        g.fillRect(0, 0, targetW, targetH);

        if (displayMode == null) displayMode = "Blur-Fit";

        // Apply round corners to source image BEFORE display mode rendering
        if (fxRoundCorners && fxCornerRadius > 0) {
            int iw = image.getWidth(), ih = image.getHeight();
            float rcScale = Math.max(iw, ih) / 1920.0f;
            int radius = Math.max(2, (int)(fxCornerRadius * Math.max(rcScale, 0.5f)));
            BufferedImage srcRounded = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
            Graphics2D rg = srcRounded.createGraphics();
            rg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            rg.setClip(new RoundRectangle2D.Double(0, 0, iw, ih, radius * 2, radius * 2));
            rg.drawImage(image, 0, 0, null);
            rg.dispose();
            image = srcRounded;
        }

        // ========== IMAGE SHAPE MODE (floating shaped image on blurred background) ==========
        if (overlayEnabled && overlaySize > 0) {
            // Draw background
            if ("Color".equals(overlayBgMode) && overlayBgColor != null) {
                g.setColor(overlayBgColor);
                g.fillRect(0, 0, targetW, targetH);
            } else {
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
            }

            // Calculate shaped image size and position
            int maxDim = Math.max(targetW, targetH);
            int ovSize = (int) (maxDim * overlaySize / 100.0);
            double imgW = image.getWidth();
            double imgH = image.getHeight();
            double ovScale = Math.min(ovSize / imgW, ovSize / imgH);
            int drawW = Math.max(1, (int) (imgW * ovScale));
            int drawH = Math.max(1, (int) (imgH * ovScale));
            int centerX = (int) (overlayX / 100.0 * targetW);
            int centerY = (int) (overlayY / 100.0 * targetH);
            int drawX = centerX - drawW / 2;
            int drawY = centerY - drawH / 2;

            if ("Circular".equals(overlayShape)) {
                int diameter = Math.min(drawW, drawH);
                BufferedImage circleImg = new BufferedImage(diameter, diameter, BufferedImage.TYPE_INT_ARGB);
                Graphics2D cg = circleImg.createGraphics();
                cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                cg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                cg.setClip(new java.awt.geom.Ellipse2D.Double(0, 0, diameter, diameter));
                // Center-crop the image into the circle
                double cropScale = Math.max((double) diameter / imgW, (double) diameter / imgH);
                int cw = (int) (imgW * cropScale);
                int ch = (int) (imgH * cropScale);
                cg.drawImage(image, (diameter - cw) / 2, (diameter - ch) / 2, cw, ch, null);
                cg.dispose();
                // Draw with subtle shadow
                Graphics2D sg = (Graphics2D) g.create();
                sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                sg.setColor(new Color(0, 0, 0, 80));
                sg.fillOval(centerX - diameter / 2 + 4, centerY - diameter / 2 + 4, diameter, diameter);
                sg.dispose();
                g.drawImage(circleImg, centerX - diameter / 2, centerY - diameter / 2, null);
            } else {
                // Rectangular with slight rounded corners and shadow
                int cornerR = Math.max(4, drawW / 30);
                BufferedImage rectImg = new BufferedImage(drawW, drawH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D rg2 = rectImg.createGraphics();
                rg2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                rg2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                rg2.setClip(new RoundRectangle2D.Double(0, 0, drawW, drawH, cornerR * 2, cornerR * 2));
                rg2.drawImage(image, 0, 0, drawW, drawH, null);
                rg2.dispose();
                // Draw with subtle shadow
                Graphics2D sg = (Graphics2D) g.create();
                sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                sg.setColor(new Color(0, 0, 0, 80));
                sg.fillRoundRect(drawX + 4, drawY + 4, drawW, drawH, cornerR * 2, cornerR * 2);
                sg.dispose();
                g.drawImage(rectImg, drawX, drawY, null);
            }
        } else

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

        // ========== APPLY IMAGE EFFECTS (intensity 0=off, 1-100) ==========
        if (fxSepia > 0) {
            double strength = fxSepia / 100.0;
            int[] px = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            for (int i = 0; i < px.length; i++) {
                int r = (px[i] >> 16) & 0xFF;
                int gr = (px[i] >> 8) & 0xFF;
                int b = px[i] & 0xFF;
                int sr = Math.min(255, (int)(0.393 * r + 0.769 * gr + 0.189 * b));
                int sg2 = Math.min(255, (int)(0.349 * r + 0.686 * gr + 0.168 * b));
                int sb = Math.min(255, (int)(0.272 * r + 0.534 * gr + 0.131 * b));
                int tr = (int)(r + (sr - r) * strength);
                int tg = (int)(gr + (sg2 - gr) * strength);
                int tb = (int)(b + (sb - b) * strength);
                px[i] = (0xFF << 24) | (tr << 16) | (tg << 8) | tb;
            }
            frame.setRGB(0, 0, targetW, targetH, px, 0, targetW);
        }

        if (fxWaterRipple > 0) {
            double strength = fxWaterRipple / 50.0;
            int[] src = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            int[] dst = new int[src.length];
            double amplitude = targetH * 0.006 * strength;
            double frequency = 2.0 * Math.PI / (targetH * 0.12);
            double phase = animFrameIndex * 0.15;
            for (int y = 0; y < targetH; y++) {
                int xOff = (int)(amplitude * Math.sin(frequency * y + phase));
                for (int x = 0; x < targetW; x++) {
                    int sx = Math.max(0, Math.min(targetW - 1, x + xOff));
                    dst[y * targetW + x] = src[y * targetW + sx];
                }
            }
            frame.setRGB(0, 0, targetW, targetH, dst, 0, targetW);
        }

        if (fxGlitch > 0) {
            double strength = fxGlitch / 50.0;
            int[] px = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            Random glitchRng = new Random(137L + animFrameIndex * 31L);
            int numBands = (int)((4 + glitchRng.nextInt(6)) * strength);
            int maxShift = Math.max(1, (int)(targetW / 10 * strength));
            for (int band = 0; band < numBands; band++) {
                int bandY = glitchRng.nextInt(targetH);
                int bandH = (int)((2 + glitchRng.nextInt(Math.max(1, targetH / 40))) * strength);
                int shift = glitchRng.nextInt(Math.max(1, maxShift)) - maxShift / 2;
                for (int y = bandY; y < Math.min(targetH, bandY + bandH); y++) {
                    int[] row = new int[targetW];
                    for (int x = 0; x < targetW; x++) {
                        row[x] = px[y * targetW + ((x + shift + targetW) % targetW)];
                    }
                    System.arraycopy(row, 0, px, y * targetW, targetW);
                }
            }
            int rShift = Math.max(1, (int)(targetW / 80 * strength));
            int channelPhase = (animFrameIndex % 3);
            int[] result = new int[px.length];
            for (int y = 0; y < targetH; y++) {
                for (int x = 0; x < targetW; x++) {
                    int idx = y * targetW + x;
                    int rOff = channelPhase == 0 ? rShift : (channelPhase == 1 ? -rShift : rShift / 2);
                    int bOff = channelPhase == 0 ? -rShift : (channelPhase == 1 ? rShift : -rShift / 2);
                    int rIdx = y * targetW + Math.min(targetW - 1, Math.max(0, x + rOff));
                    int bIdx = y * targetW + Math.min(targetW - 1, Math.max(0, x + bOff));
                    int rv = (px[rIdx] >> 16) & 0xFF;
                    int gv = (px[idx] >> 8) & 0xFF;
                    int bv = px[bIdx] & 0xFF;
                    result[idx] = (0xFF << 24) | (rv << 16) | (gv << 8) | bv;
                }
            }
            frame.setRGB(0, 0, targetW, targetH, result, 0, targetW);
        }

        if (fxGrain > 0) {
            int range = (int)(60 * fxGrain / 50.0);
            int half = range / 2;
            int[] px = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            Random grainRng = new Random(42L + animFrameIndex * 17L);
            for (int i = 0; i < px.length; i++) {
                int noise = grainRng.nextInt(Math.max(1, range)) - half;
                int r = Math.max(0, Math.min(255, ((px[i] >> 16) & 0xFF) + noise));
                int gv = Math.max(0, Math.min(255, ((px[i] >> 8) & 0xFF) + noise));
                int b = Math.max(0, Math.min(255, (px[i] & 0xFF) + noise));
                px[i] = (0xFF << 24) | (r << 16) | (gv << 8) | b;
            }
            frame.setRGB(0, 0, targetW, targetH, px, 0, targetW);
        }

        if (fxShake > 0) {
            double strength = fxShake / 50.0;
            BufferedImage shaken = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = shaken.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sg.setColor(new Color(21, 32, 43));
            sg.fillRect(0, 0, targetW, targetH);
            double shakeAngle = Math.toRadians(1.8 * strength * Math.sin(animFrameIndex * 0.7));
            double shakeOffX = targetW * 0.01 * strength * Math.sin(animFrameIndex * 1.1);
            double shakeOffY = targetH * 0.008 * strength * Math.cos(animFrameIndex * 0.9);
            AffineTransform at = new AffineTransform();
            at.translate(targetW / 2.0, targetH / 2.0);
            at.rotate(shakeAngle);
            at.translate(shakeOffX, shakeOffY);
            at.translate(-targetW / 2.0, -targetH / 2.0);
            sg.drawImage(frame, at, null);
            sg.dispose();
            Graphics2D fg = frame.createGraphics();
            fg.drawImage(shaken, 0, 0, null);
            fg.dispose();
        }

        if (fxVignette > 0) {
            double strength = fxVignette / 50.0;
            int midAlpha = Math.min(255, (int)(100 * strength));
            int edgeAlpha = Math.min(255, (int)(200 * strength));
            Graphics2D vg = (Graphics2D) g.create();
            float cx = targetW / 2.0f, cy = targetH / 2.0f;
            float vRadius = (float) Math.sqrt(cx * cx + cy * cy);
            RadialGradientPaint vPaint = new RadialGradientPaint(
                cx, cy, vRadius,
                new float[]{0.0f, 0.45f, 0.85f, 1.0f},
                new Color[]{new Color(0,0,0,0), new Color(0,0,0,0),
                            new Color(0,0,0,midAlpha), new Color(0,0,0,edgeAlpha)}
            );
            vg.setPaint(vPaint);
            vg.fillRect(0, 0, targetW, targetH);
            vg.dispose();
        }


        // ========== SLIDE NUMBER OVERLAY ==========
        if (showSlideNumber && slideNumberText != null && !slideNumberText.isEmpty()) {
            float numScaleFactor = Math.max(targetW, targetH) / 1920.0f;
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
            int ascent = numFm.getAscent();
            int descent = numFm.getDescent();
            int drawX = numX - textW / 2;
            int drawY = numY + (ascent - descent) / 2;

            // Circular transparent background sized to fit 1 or 2 digits
            int diameter = (int) (Math.max(textW, ascent + descent) * 1.5);
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

        // ========== SLIDE TEXT OVERLAY(S) ==========
        if (slideTexts != null) {
            for (SlideTextData st : slideTexts) {
                if (!st.show || st.text == null || st.text.isEmpty()) continue;
                float stScaleFactor = Math.max(targetW, targetH) / 1920.0f;
                int scaledStSize = Math.max(8, (int) (st.fontSize * stScaleFactor));
                Font stFont;
                if (loadedFonts.containsKey(st.fontName)) {
                    stFont = loadedFonts.get(st.fontName).deriveFont(st.fontStyle, (float) scaledStSize);
                } else {
                    stFont = new Font(st.fontName, st.fontStyle, scaledStSize);
                }
                g.setFont(stFont);
                FontMetrics stFm = g.getFontMetrics();

                int stMaxWrapWidth = targetW;
                if (st.widthPct > 0 && st.widthPct < 100) {
                    stMaxWrapWidth = (int) (targetW * st.widthPct / 100.0);
                }

                List<String> stWrappedLines = new ArrayList<>();
                for (String paragraph : st.text.split("\n")) {
                    if (paragraph.isEmpty()) { stWrappedLines.add(""); continue; }
                    List<String> wrapped = wrapTextStatic(paragraph, stFm, stMaxWrapWidth);
                    stWrappedLines.addAll(wrapped);
                }

                int stLineHeight = stFm.getHeight();
                int stAscent = stFm.getAscent();

                int stCenterX = (int) (st.x / 100.0 * targetW);
                int stCenterY = (int) (st.y / 100.0 * targetH);

                int stShiftPx = (int) (st.shiftX / 100.0 * targetW);
                stCenterX += stShiftPx;

                int totalTextHeight = stAscent + stFm.getDescent() + stLineHeight * (stWrappedLines.size() - 1);
                int stMaxLineWidth = 0;
                for (String line : stWrappedLines) {
                    stMaxLineWidth = Math.max(stMaxLineWidth, stFm.stringWidth(line));
                }

                int stPadX = (int) (6 * stScaleFactor);
                int stPadY = (int) (4 * stScaleFactor);

                int bgX = stCenterX - stMaxLineWidth / 2 - stPadX;
                int bgY = stCenterY - totalTextHeight / 2 - stPadY;
                int bgW = stMaxLineWidth + stPadX * 2;
                int bgH = totalTextHeight + stPadY * 2;

                if (st.bgOpacity > 0) {
                    int alpha = (int) (st.bgOpacity / 100.0 * 255);
                    Color bgc = st.bgColor != null ? st.bgColor : Color.BLACK;
                    Graphics2D g2st = (Graphics2D) g.create();
                    g2st.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2st.setColor(new Color(bgc.getRed(), bgc.getGreen(), bgc.getBlue(), alpha));
                    int arc = (int) (10 * stScaleFactor);
                    g2st.fillRoundRect(bgX, bgY, bgW, bgH, arc, arc);
                    g2st.dispose();
                }

                int stBlockLeft = stCenterX - stMaxLineWidth / 2;

                Color stColor = st.color != null ? st.color : Color.YELLOW;
                String effect = st.textEffect != null ? st.textEffect : "None";
                double intensity = st.textEffectIntensity / 100.0;

                int lineY = stCenterY - totalTextHeight / 2 + stAscent;
                for (int li = 0; li < stWrappedLines.size(); li++) {
                    String line = stWrappedLines.get(li);
                    int lineW = stFm.stringWidth(line);
                    int lineX;
                    if (st.alignment == SwingConstants.LEFT) {
                        lineX = stBlockLeft;
                    } else if (st.alignment == SwingConstants.RIGHT) {
                        lineX = stBlockLeft + stMaxLineWidth - lineW;
                    } else {
                        lineX = stCenterX - lineW / 2;
                    }

                    boolean isLastLine = (li == stWrappedLines.size() - 1);
                    boolean justified = false;
                    String[] justifyWords = null;
                    double justifyExtraSpace = 0;
                    if (st.justify && !isLastLine && stMaxLineWidth > 0) {
                        justifyWords = line.split(" ");
                        if (justifyWords.length > 1) {
                            int totalWordsWidth = 0;
                            for (String w : justifyWords) totalWordsWidth += stFm.stringWidth(w);
                            justifyExtraSpace = (double) (stMaxLineWidth - totalWordsWidth) / (justifyWords.length - 1);
                            justified = true;
                        }
                    }

                    // === Typewriter: limit visible characters ===
                    String visibleLine = line;
                    if (effect.equals("Typewriter")) {
                        int totalChars = 0;
                        for (int tli = 0; tli < stWrappedLines.size(); tli++) totalChars += stWrappedLines.get(tli).length();
                        int charsPerFrame = Math.max(1, (int) (2 + 6 * intensity));
                        int visibleChars = Math.min(totalChars, animFrameIndex * charsPerFrame);
                        int charsBefore = 0;
                        for (int tli = 0; tli < li; tli++) charsBefore += stWrappedLines.get(tli).length();
                        int charsForLine = Math.max(0, Math.min(line.length(), visibleChars - charsBefore));
                        visibleLine = line.substring(0, charsForLine);
                        if (visibleLine.isEmpty()) { lineY += stLineHeight; continue; }
                        if (justified) {
                            // recalc justified words for partial line
                            justifyWords = visibleLine.split(" ");
                            if (justifyWords.length <= 1) justified = false;
                            else {
                                int tw = 0; for (String w : justifyWords) tw += stFm.stringWidth(w);
                                justifyExtraSpace = (double) (stMaxLineWidth - tw) / (justifyWords.length - 1);
                            }
                        }
                    }

                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setFont(stFont);

                    switch (effect) {
                        case "Shadow": {
                            int off = Math.max(1, (int) (scaledStSize * 0.06 * intensity));
                            g2.setColor(new Color(0, 0, 0, (int) (180 * intensity)));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft + off, lineY + off, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX + off, lineY + off);
                            g2.setColor(stColor);
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        case "Glow": {
                            int layers = 3 + (int) (5 * intensity);
                            for (int gl = layers; gl >= 1; gl--) {
                                float spread = gl * scaledStSize * 0.04f * (float) intensity;
                                int alpha = (int) (40 * intensity / gl);
                                g2.setColor(new Color(stColor.getRed(), stColor.getGreen(), stColor.getBlue(), Math.min(255, alpha)));
                                Font glowFont = stFont.deriveFont((float) (scaledStSize + spread));
                                g2.setFont(glowFont);
                                FontMetrics gfm = g2.getFontMetrics();
                                int offY = lineY - (gfm.getAscent() - stAscent) / 2;
                                if (justified) {
                                    double dx = stBlockLeft;
                                    for (String w : justifyWords) {
                                        int origW = stFm.stringWidth(w);
                                        int offX = (int) dx - (gfm.stringWidth(w) - origW) / 2;
                                        g2.drawString(w, offX, offY);
                                        dx += origW + justifyExtraSpace;
                                    }
                                } else {
                                    int glowLineW = gfm.stringWidth(visibleLine);
                                    int offX = lineX - (glowLineW - lineW) / 2;
                                    g2.drawString(visibleLine, offX, offY);
                                }
                            }
                            g2.setFont(stFont);
                            g2.setColor(Color.WHITE);
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        case "Neon": {
                            int layers = 4 + (int) (4 * intensity);
                            for (int nl = layers; nl >= 1; nl--) {
                                float spread = nl * scaledStSize * 0.05f * (float) intensity;
                                int alpha = (int) (60 * intensity / nl);
                                Color neonC = new Color(stColor.getRed(), stColor.getGreen(), stColor.getBlue(), Math.min(255, alpha));
                                g2.setColor(neonC);
                                Font nf = stFont.deriveFont((float) (scaledStSize + spread));
                                g2.setFont(nf);
                                FontMetrics nfm = g2.getFontMetrics();
                                int offY = lineY - (nfm.getAscent() - stAscent) / 2;
                                if (justified) {
                                    double dx = stBlockLeft;
                                    for (String w : justifyWords) {
                                        int origW = stFm.stringWidth(w);
                                        int offX = (int) dx - (nfm.stringWidth(w) - origW) / 2;
                                        g2.drawString(w, offX, offY);
                                        dx += origW + justifyExtraSpace;
                                    }
                                } else {
                                    int nLineW = nfm.stringWidth(visibleLine);
                                    int offX = lineX - (nLineW - lineW) / 2;
                                    g2.drawString(visibleLine, offX, offY);
                                }
                            }
                            g2.setFont(stFont);
                            g2.setColor(new Color(255, 255, 255, (int) (200 + 55 * intensity)));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        case "Outline": {
                            float strokeW = Math.max(1, (float) (scaledStSize * 0.08 * intensity));
                            g2.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            g2.setColor(new Color(0, 0, 0, (int) (220 * intensity)));
                            if (justified) {
                                double dx = stBlockLeft;
                                for (String w : justifyWords) {
                                    java.awt.font.GlyphVector gv = stFont.createGlyphVector(g2.getFontRenderContext(), w);
                                    Shape shape = gv.getOutline((int) dx, lineY);
                                    g2.draw(shape);
                                    dx += stFm.stringWidth(w) + justifyExtraSpace;
                                }
                            } else {
                                java.awt.font.GlyphVector gv = stFont.createGlyphVector(g2.getFontRenderContext(), visibleLine);
                                Shape shape = gv.getOutline(lineX, lineY);
                                g2.draw(shape);
                            }
                            g2.setColor(stColor);
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        case "Emboss": {
                            int off = Math.max(1, (int) (scaledStSize * 0.04 * intensity));
                            g2.setColor(new Color(255, 255, 255, (int) (120 * intensity)));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft - off, lineY - off, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX - off, lineY - off);
                            g2.setColor(new Color(0, 0, 0, (int) (150 * intensity)));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft + off, lineY + off, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX + off, lineY + off);
                            g2.setColor(stColor);
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        case "Water Ripple": {
                            double amplitude = scaledStSize * 0.15 * intensity;
                            double freq = 2.0 * Math.PI / (scaledStSize * 3.0);
                            double phase = animFrameIndex * 0.2;
                            if (justified) {
                                double dx = stBlockLeft;
                                for (String w : justifyWords) {
                                    for (int ci = 0; ci < w.length(); ci++) {
                                        String ch = String.valueOf(w.charAt(ci));
                                        int cx = (int) dx + stFm.stringWidth(w.substring(0, ci));
                                        double waveY = amplitude * Math.sin(freq * cx + phase);
                                        double waveX = amplitude * 0.3 * Math.cos(freq * lineY + phase * 1.3);
                                        g2.setColor(stColor);
                                        g2.drawString(ch, (int) (cx + waveX), (int) (lineY + waveY));
                                    }
                                    dx += stFm.stringWidth(w) + justifyExtraSpace;
                                }
                            } else {
                                for (int ci = 0; ci < visibleLine.length(); ci++) {
                                    String ch = String.valueOf(visibleLine.charAt(ci));
                                    int cx = lineX + stFm.stringWidth(visibleLine.substring(0, ci));
                                    double waveY = amplitude * Math.sin(freq * cx + phase);
                                    double waveX = amplitude * 0.3 * Math.cos(freq * lineY + phase * 1.3);
                                    g2.setColor(stColor);
                                    g2.drawString(ch, (int) (cx + waveX), (int) (lineY + waveY));
                                }
                            }
                            break;
                        }
                        case "Fire": {
                            int layers = 3 + (int) (4 * intensity);
                            Color[] fireColors = {
                                new Color(255, 60, 0, (int) (60 * intensity)),
                                new Color(255, 120, 0, (int) (80 * intensity)),
                                new Color(255, 200, 0, (int) (100 * intensity)),
                                new Color(255, 240, 100, (int) (120 * intensity))
                            };
                            for (int fl = layers; fl >= 1; fl--) {
                                float rise = fl * scaledStSize * 0.03f * (float) intensity;
                                double flicker = rise * 0.4 * Math.sin(animFrameIndex * 0.3 + fl * 1.7);
                                Color fc = fireColors[Math.min(fl - 1, fireColors.length - 1)];
                                g2.setColor(fc);
                                float fSize = scaledStSize + fl * scaledStSize * 0.02f * (float) intensity;
                                Font ff = stFont.deriveFont(fSize);
                                g2.setFont(ff);
                                FontMetrics ffm = g2.getFontMetrics();
                                int offY = (int) (lineY - rise + flicker) - (ffm.getAscent() - stAscent) / 2;
                                if (justified) {
                                    double dx = stBlockLeft;
                                    for (String w : justifyWords) {
                                        int origW = stFm.stringWidth(w);
                                        int offX = (int) dx - (ffm.stringWidth(w) - origW) / 2;
                                        g2.drawString(w, offX, offY);
                                        dx += origW + justifyExtraSpace;
                                    }
                                } else {
                                    int fLineW = ffm.stringWidth(visibleLine);
                                    int offX = lineX - (fLineW - lineW) / 2;
                                    g2.drawString(visibleLine, offX, offY);
                                }
                            }
                            g2.setFont(stFont);
                            g2.setColor(new Color(255, 255, 200));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        case "Ice": {
                            int layers = 3 + (int) (3 * intensity);
                            for (int il = layers; il >= 1; il--) {
                                float spread = il * scaledStSize * 0.03f * (float) intensity;
                                double shimmer = spread * 0.2 * Math.sin(animFrameIndex * 0.15 + il);
                                int alpha = (int) (50 * intensity / il);
                                g2.setColor(new Color(150, 200, 255, Math.min(255, alpha)));
                                Font iFont = stFont.deriveFont((float) (scaledStSize + spread + shimmer));
                                g2.setFont(iFont);
                                FontMetrics ifm = g2.getFontMetrics();
                                int offY = lineY - (ifm.getAscent() - stAscent) / 2;
                                if (justified) {
                                    double dx = stBlockLeft;
                                    for (String w : justifyWords) {
                                        int origW = stFm.stringWidth(w);
                                        int offX = (int) dx - (ifm.stringWidth(w) - origW) / 2;
                                        g2.drawString(w, offX, offY);
                                        dx += origW + justifyExtraSpace;
                                    }
                                } else {
                                    int iLineW = ifm.stringWidth(visibleLine);
                                    int offX = lineX - (iLineW - lineW) / 2;
                                    g2.drawString(visibleLine, offX, offY);
                                }
                            }
                            g2.setFont(stFont);
                            g2.setColor(new Color(220, 240, 255));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        case "Rainbow": {
                            if (justified) {
                                double dx = stBlockLeft;
                                int charIdx = 0;
                                for (String w : justifyWords) {
                                    for (int ci = 0; ci < w.length(); ci++) {
                                        float hue = ((charIdx + animFrameIndex * 3) % 360) / 360.0f;
                                        g2.setColor(Color.getHSBColor(hue, 0.8f + 0.2f * (float) intensity, 1.0f));
                                        String ch = String.valueOf(w.charAt(ci));
                                        int cx = (int) dx + stFm.stringWidth(w.substring(0, ci));
                                        g2.drawString(ch, cx, lineY);
                                        charIdx += 8;
                                    }
                                    dx += stFm.stringWidth(w) + justifyExtraSpace;
                                }
                            } else {
                                int charIdx = 0;
                                for (int ci = 0; ci < visibleLine.length(); ci++) {
                                    float hue = ((charIdx + animFrameIndex * 3) % 360) / 360.0f;
                                    g2.setColor(Color.getHSBColor(hue, 0.8f + 0.2f * (float) intensity, 1.0f));
                                    String ch = String.valueOf(visibleLine.charAt(ci));
                                    int cx = lineX + stFm.stringWidth(visibleLine.substring(0, ci));
                                    g2.drawString(ch, cx, lineY);
                                    charIdx += 8;
                                }
                            }
                            break;
                        }
                        case "Stone Engraving": {
                            // Carved/chiseled text effect — text appears recessed into stone
                            // Light source from top-left: shadow on top-left inner edge, highlight on bottom-right
                            int off = Math.max(1, (int) (scaledStSize * 0.04 * intensity));
                            int deepOff = Math.max(1, (int) (scaledStSize * 0.02 * intensity));

                            // Layer 1: Dark inner shadow (top-left of carving — where light is blocked)
                            g2.setColor(new Color(0, 0, 0, (int) (160 * intensity)));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft - off, lineY - off, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX - off, lineY - off);

                            // Layer 2: Softer dark shadow (slightly less offset for depth)
                            g2.setColor(new Color(40, 30, 20, (int) (100 * intensity)));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft - deepOff, lineY - deepOff, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX - deepOff, lineY - deepOff);

                            // Layer 3: Light highlight (bottom-right edge catching light)
                            g2.setColor(new Color(255, 250, 230, (int) (140 * intensity)));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft + off, lineY + off, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX + off, lineY + off);

                            // Layer 4: Softer highlight
                            g2.setColor(new Color(240, 230, 200, (int) (80 * intensity)));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft + deepOff, lineY + deepOff, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX + deepOff, lineY + deepOff);

                            // Layer 5: Main text — the carved surface (darker than surroundings)
                            int r = stColor.getRed(), gv = stColor.getGreen(), b = stColor.getBlue();
                            int darkR = (int)(r * (0.55 + 0.25 * (1.0 - intensity)));
                            int darkG = (int)(gv * (0.55 + 0.25 * (1.0 - intensity)));
                            int darkB = (int)(b * (0.55 + 0.25 * (1.0 - intensity)));
                            g2.setColor(new Color(
                                    Math.max(0, Math.min(255, darkR)),
                                    Math.max(0, Math.min(255, darkG)),
                                    Math.max(0, Math.min(255, darkB))));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        default: { // "None" and "Typewriter" (typewriter just limits chars above)
                            g2.setColor(stColor);
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                    }
                    g2.dispose();

                    lineY += stLineHeight;
                }
            }
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

            // Apply text width percentage to limit wrapping area
            int maxWrapWidth = targetW - paddingX * 2 - textOffsetX - paddingX;
            if (textWidthPct > 0 && textWidthPct < 100) {
                maxWrapWidth = (int) (maxWrapWidth * textWidthPct / 100.0);
            }

            List<String> lines = wrapTextStatic(subtitle, fm, maxWrapWidth);

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

            // Apply horizontal shift
            int shiftPixels = (int) (textShiftX / 100.0 * targetW);
            blockX += shiftPixels;

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
            for (int li = 0; li < lines.size(); li++) {
                String line = lines.get(li);
                int lx;
                if (alignment == SwingConstants.CENTER) {
                    lx = (targetW - fm.stringWidth(line)) / 2;
                } else if (alignment == SwingConstants.RIGHT) {
                    lx = blockX + blockWidth - paddingX - fm.stringWidth(line);
                } else {
                    lx = blockX + paddingX + textOffsetX;
                }

                // Highlight matching text
                if (highlightText != null && !highlightText.isEmpty()) {
                    String lineLower = line.toLowerCase();
                    String hlLower = highlightText.toLowerCase();
                    int searchFrom = 0;
                    while (searchFrom < lineLower.length()) {
                        int hlIdx = lineLower.indexOf(hlLower, searchFrom);
                        if (hlIdx < 0) break;
                        String before = line.substring(0, hlIdx);
                        String match = line.substring(hlIdx, hlIdx + highlightText.length());
                        int hlX = lx + fm.stringWidth(before);
                        int hlW = fm.stringWidth(match);
                        Color hlC = highlightColor != null ? highlightColor : new Color(255, 255, 0, 180);
                        g.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), hlC.getAlpha()));
                        int hlPad = (int) (2 * scaleFactor);
                        g.fillRoundRect(hlX - hlPad, lineY - fm.getAscent() - hlPad,
                                hlW + hlPad * 2, fm.getHeight() + hlPad * 2,
                                (int) (4 * scaleFactor), (int) (4 * scaleFactor));
                        searchFrom = hlIdx + highlightText.length();
                    }
                    g.setColor(fontColor);
                }

                // Justify: distribute extra space between words (not for last line)
                boolean isLastLine = (li == lines.size() - 1);
                if (textJustify && !isLastLine && maxLineWidth > 0) {
                    String[] words = line.split(" ");
                    if (words.length > 1) {
                        int totalWordsWidth = 0;
                        for (String w : words) {
                            totalWordsWidth += fm.stringWidth(w);
                        }
                        double extraSpace = (double) (maxLineWidth - totalWordsWidth) / (words.length - 1);
                        double drawX = lx;
                        for (int wi = 0; wi < words.length; wi++) {
                            g.drawString(words[wi], (int) drawX, lineY);
                            drawX += fm.stringWidth(words[wi]) + extraSpace;
                        }
                        lineY += lineHeight;
                        continue;
                    }
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

        // Downscale before blur for massive speedup — blur is O(w*h*radius)
        // A 4x downscale makes blur ~16x faster with visually identical results
        int downFactor = 4;
        int smallW = Math.max(1, w / downFactor);
        int smallH = Math.max(1, h / downFactor);
        int smallRadius = Math.max(1, radius / downFactor);

        BufferedImage small = new BufferedImage(smallW, smallH, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = small.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        sg.drawImage(src, 0, 0, smallW, smallH, null);
        sg.dispose();

        int[] pixels = small.getRGB(0, 0, smallW, smallH, null, 0, smallW);
        int[] result = new int[pixels.length];

        blurPass(pixels, result, smallW, smallH, smallRadius, true);
        blurPass(result, pixels, smallW, smallH, smallRadius, false);

        small.setRGB(0, 0, smallW, smallH, pixels, 0, smallW);

        // Upscale back to original size — bilinear interpolation smooths it naturally
        BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D og = output.createGraphics();
        og.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        og.drawImage(small, 0, 0, w, h, null);
        og.dispose();
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

    private static void drawJustified(Graphics2D g, String[] words, int startX, int y,
                                       double extraSpace, FontMetrics fm) {
        double dx = startX;
        for (String w : words) {
            g.drawString(w, (int) dx, y);
            dx += fm.stringWidth(w) + extraSpace;
        }
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
                slideImage = row.composeTitleGridFrame(getOutputWidth(), getOutputHeight());
                if (row.titleBgImage == null) {
                    slideDisplayMode = "Direct";
                }
            }
            slides.add(new SlideData(slideImage, row.getSubtitleText(),
                    row.getSelectedFont(), row.getFontSize(), row.getFontStyle(),
                    row.getFontColor(), row.getTextAlignment(), row.isShowPin(), slideDisplayMode,
                    row.getSubtitleY(), row.getSubtitleBgOpacity(),
                    row.isShowSlideNumber(), row.getSlideNumberText(), row.getSlideNumberFontName(),
                    row.getSlideNumberX(), row.getSlideNumberY(),
                    row.getSlideNumberSize(), row.getSlideNumberColor(),
                    row.getSlideTextDataList(),
                    row.isFxRoundCorners(), row.getFxCornerRadius(),
                    row.getFxVignette(), row.getFxSepia(), row.getFxGrain(),
                    row.getFxWaterRipple(), row.getFxGlitch(), row.getFxShake(),
                    row.isOverlayEnabled(),
                    row.getOverlayShape(), row.getOverlayBgMode(), row.getOverlayBgColor(),
                    row.getOverlayX(), row.getOverlayY(),
                    row.getOverlaySize(),
                    row.isTextJustify(), row.getTextWidthPct(),
                    row.getHighlightText(), row.getHighlightColor(),
                    row.getTextShiftX(),
                    row.getSlideAudioDurationMs(), row.getSlideAudioFile()));
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
                    s.slideNumberSize, s.slideNumberColor,
                    s.slideTexts,
                    s.fxRoundCorners, s.fxCornerRadius,
                    s.fxVignette, s.fxSepia, s.fxGrain,
                    s.fxWaterRipple, s.fxGlitch, s.fxShake,
                    s.overlayEnabled,
                    s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                    s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX);
            frames.add(frame);
            int pct = (int) ((i + 1.0) / slides.size() * maxPct);
            SwingUtilities.invokeLater(() -> progressBar.setValue(pct));
        }
        return frames;
    }

    // ==================== Scroll Direction ====================

    private static final String SCROLL_NONE  = "None (Static)";
    private static final String SCROLL_LEFT  = "Scroll Left";
    private static final String SCROLL_RIGHT = "Scroll Right";
    private static final String SCROLL_UP    = "Scroll Up";
    private static final String SCROLL_DOWN  = "Scroll Down";

    private String askScrollDirection() {
        String[] options = { SCROLL_NONE, SCROLL_LEFT, SCROLL_RIGHT, SCROLL_UP, SCROLL_DOWN };
        int choice = JOptionPane.showOptionDialog(this,
                "Choose slide scroll direction:\n"
                + "• None: Static slides (no scrolling)\n"
                + "• Left/Right: Slides scroll horizontally\n"
                + "• Up/Down: Slides scroll vertically",
                "Slide Scroll", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice < 0) return null;
        return options[choice];
    }

    /**
     * Renders a single scroll frame by compositing two adjacent rendered slides.
     * @param renderedSlides pre-rendered slide images at target resolution
     * @param scrollDir scroll direction
     * @param frameIndex current frame index (0-based across entire video)
     * @param totalFrames total number of frames in video
     * @param w output width
     * @param h output height
     */
    private static BufferedImage renderScrollFrame(List<BufferedImage> renderedSlides,
                                                   String scrollDir, int frameIndex,
                                                   int totalFrames, int w, int h) {
        int numSlides = renderedSlides.size();
        if (numSlides == 1) return renderedSlides.get(0);

        // progress goes from 0.0 (start of first slide) to numSlides-1 (start of last slide)
        // but we want the last slide to be fully visible at the end
        double progress = (double) frameIndex / (totalFrames - 1) * (numSlides - 1);
        progress = Math.max(0, Math.min(numSlides - 1, progress));

        int slideA = (int) Math.floor(progress);
        if (slideA >= numSlides - 1) slideA = numSlides - 1;
        int slideB = Math.min(slideA + 1, numSlides - 1);
        double t = progress - slideA; // 0.0 to 1.0 between slideA and slideB

        BufferedImage imgA = renderedSlides.get(slideA);

        // If exactly on a slide boundary, just return that slide
        if (slideA == slideB || t < 0.001) return imgA;

        BufferedImage imgB = renderedSlides.get(slideB);
        BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        boolean horizontal = SCROLL_LEFT.equals(scrollDir) || SCROLL_RIGHT.equals(scrollDir);
        int dim = horizontal ? w : h;
        int offset = (int) Math.round(t * dim);

        switch (scrollDir) {
            case SCROLL_LEFT:
                // Slides enter from the right, exit to the left
                g.drawImage(imgA, -offset, 0, null);
                g.drawImage(imgB, w - offset, 0, null);
                break;
            case SCROLL_RIGHT:
                // Slides enter from the left, exit to the right
                g.drawImage(imgA, offset, 0, null);
                g.drawImage(imgB, -(w - offset), 0, null);
                break;
            case SCROLL_UP:
                // Slides enter from the bottom, exit to the top
                g.drawImage(imgA, 0, -offset, null);
                g.drawImage(imgB, 0, h - offset, null);
                break;
            case SCROLL_DOWN:
                // Slides enter from the top, exit to the bottom
                g.drawImage(imgA, 0, offset, null);
                g.drawImage(imgB, 0, -(h - offset), null);
                break;
        }

        g.dispose();
        return frame;
    }

    // ==================== GIF Creation ====================

    private void createGif() {
        List<SlideData> slides = collectSlides();
        if (slides == null) return;

        int duration = askDuration();
        if (duration < 0) return;

        // Scroll direction
        String scrollDir = askScrollDirection();
        if (scrollDir == null) return;
        final String gifScrollDir = scrollDir;
        final boolean gifScrollEnabled = !SCROLL_NONE.equals(scrollDir);

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
                    int[][] resolutions;
                    if (isPortrait()) {
                        resolutions = new int[][] {
                            {1080, 1920},
                            {720, 1280},
                            {480, 854},
                        };
                    } else {
                        resolutions = new int[][] {
                            {1920, 1080},
                            {1280, 720},
                            {854, 480},
                        };
                    }

                    for (int[] res : resolutions) {
                        int w = res[0];
                        int h = res[1];
                        publish("Rendering at " + w + "×" + h + "...");

                        List<BufferedImage> frames;
                        List<Integer> frameDelays;

                        if (gifScrollEnabled) {
                            // Pre-render all slides, then generate scroll frames
                            List<BufferedImage> renderedSlides = renderAllFrames(slides, w, h, progressBar, 40);
                            int gifFps = 15; // reasonable for GIF
                            int framesPerSlide = Math.max(1, (int) Math.round(duration / 1000.0 * gifFps));
                            int totalGifFrames = slides.size() * framesPerSlide;
                            int gifDelayMs = Math.max(20, 1000 / gifFps);
                            frames = new ArrayList<>();
                            frameDelays = new ArrayList<>();
                            publish("Generating scroll frames (" + gifScrollDir + ")...");
                            for (int f = 0; f < totalGifFrames; f++) {
                                frames.add(renderScrollFrame(renderedSlides, gifScrollDir, f, totalGifFrames, w, h));
                                frameDelays.add(gifDelayMs);
                                int pct = 40 + (int) ((f + 1.0) / totalGifFrames * 20);
                                final int p = pct;
                                SwingUtilities.invokeLater(() -> progressBar.setValue(p));
                            }
                        } else {
                            frames = renderAllFrames(slides, w, h, progressBar, 60);
                            frameDelays = new ArrayList<>();
                            for (SlideData s : slides) {
                                int delay = (s.audioDurationMs > 0) ? s.audioDurationMs : duration;
                                frameDelays.add(delay);
                            }
                        }

                        publish("Encoding GIF at " + w + "×" + h + "...");
                        SwingUtilities.invokeLater(() -> progressBar.setValue(70));

                        if (finalMethod == 1) {
                            writeGifWithFfmpeg(frames, frameDelays, finalOut);
                        } else {
                            writeAnimatedGif(frames, frameDelays, finalOut);
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
                            null, new String[]{"Play Video", "OK"}, "Play Video");
                    if (ch == 0) {
                        try {
                            java.awt.Desktop.getDesktop().open(finalOut);
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(GifSlideShowApp.this,
                                    "Could not open video player.\nFile saved at: " + finalOut.getAbsolutePath(),
                                    "Preview", JOptionPane.INFORMATION_MESSAGE);
                        }
                    }
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

        String[] resOptions;
        if (isPortrait()) {
            resOptions = new String[]{"1080×1920 (Full HD Portrait)", "1440×2560 (2K QHD Portrait)", "2160×3840 (4K UHD Portrait)"};
        } else {
            resOptions = new String[]{"1920×1080 (Full HD)", "2560×1440 (2K QHD)", "3840×2160 (4K UHD)"};
        }
        int resChoice = JOptionPane.showOptionDialog(this,
                "Choose video resolution:",
                "MP4 Resolution", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, resOptions, resOptions[0]);
        if (resChoice < 0) return;

        final int videoW, videoH;
        if (isPortrait()) {
            switch (resChoice) {
                case 1:  videoW = 1440; videoH = 2560; break;
                case 2:  videoW = 2160; videoH = 3840; break;
                default: videoW = 1080; videoH = 1920; break;
            }
        } else {
            switch (resChoice) {
                case 1:  videoW = 2560; videoH = 1440; break;
                case 2:  videoW = 3840; videoH = 2160; break;
                default: videoW = 1920; videoH = 1080; break;
            }
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

        // Scroll direction
        String scrollDir = askScrollDirection();
        if (scrollDir == null) return;
        final String finalScrollDir = scrollDir;
        final boolean scrollEnabled = !SCROLL_NONE.equals(scrollDir);

        // Optional audio file
        int audioChoice = JOptionPane.showOptionDialog(this,
                "Add audio to the video?",
                "Audio Track", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, new String[]{"No Audio", "Choose Audio File"}, "No Audio");

        File audioFile = null;
        if (audioChoice == 1) {
            JFileChooser audioChooser = new JFileChooser();
            audioChooser.setFileFilter(new FileNameExtensionFilter(
                    "Audio Files (mp3, wav, aac, ogg, m4a, flac, wma)",
                    "mp3", "wav", "aac", "ogg", "m4a", "flac", "wma"));
            audioChooser.setDialogTitle("Select Audio File");
            if (audioChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                audioFile = audioChooser.getSelectedFile();
            }
        }
        final File finalAudioFile = audioFile;

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
                    int defaultFramesPerSlide = Math.max(1, (int) Math.round(duration / 1000.0 * fps));
                    boolean useConcatDemuxer = false;
                    boolean usePipeEncoding = false;
                    File concatFile = null;

                    publish("Rendering " + slides.size() + " slides at " + videoW + "×" + videoH + "...");

                    int totalFrames = 0;
                    for (SlideData s : slides) {
                        int slideDur = (s.audioDurationMs > 0) ? s.audioDurationMs : duration;
                        totalFrames += Math.max(1, (int) Math.round(slideDur / 1000.0 * fps));
                    }
                    int frameIndex = 0;

                    if (scrollEnabled) {
                        // Pre-render all slides first
                        publish("Pre-rendering all slides...");
                        List<BufferedImage> renderedSlides = new ArrayList<>();
                        for (int i = 0; i < slides.size(); i++) {
                            SlideData s = slides.get(i);
                            renderedSlides.add(renderFrame(
                                    s.image, s.text, s.fontName, s.fontSize,
                                    s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                    videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                    s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                    s.slideNumberX, s.slideNumberY,
                                    s.slideNumberSize, s.slideNumberColor,
                                    s.slideTexts,
                                    s.fxRoundCorners, s.fxCornerRadius,
                                    s.fxVignette, s.fxSepia, s.fxGrain,
                                    s.fxWaterRipple, s.fxGlitch, s.fxShake,
                                    s.overlayEnabled,
                                    s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                    s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX));
                            int pct = (int) ((i + 1.0) / slides.size() * 30);
                            final int p = pct;
                            SwingUtilities.invokeLater(() -> progressBar.setValue(p));
                            publish("Rendered slide " + (i + 1) + "/" + slides.size());
                        }

                        // Generate scroll frames
                        publish("Generating scroll frames (" + finalScrollDir + ")...");
                        for (int f = 0; f < totalFrames; f++) {
                            BufferedImage scrollFrame = renderScrollFrame(
                                    renderedSlides, finalScrollDir, f, totalFrames, videoW, videoH);
                            ImageIO.write(scrollFrame, "png",
                                    new File(tempDir, String.format("frame_%05d.png", f)));
                            int pct = 30 + (int) ((f + 1.0) / totalFrames * 30);
                            final int p = pct;
                            SwingUtilities.invokeLater(() -> progressBar.setValue(p));
                            if (f % fps == 0) {
                                publish("Scroll frame " + (f + 1) + "/" + totalFrames);
                            }
                        }
                        frameIndex = totalFrames;
                    } else {
                        // Check if ANY slide has animated effects (determines encoding strategy)
                        boolean anyAnimatedFx = false;
                        for (SlideData s : slides) {
                            boolean hasAnim = s.fxGrain > 0 || s.fxWaterRipple > 0 || s.fxGlitch > 0 || s.fxShake > 0;
                            if (!hasAnim && s.slideTexts != null) {
                                for (SlideTextData stx : s.slideTexts) {
                                    if (stx.show && stx.textEffect != null) {
                                        String fx = stx.textEffect;
                                        if (fx.equals("Water Ripple") || fx.equals("Fire") || fx.equals("Ice")
                                                || fx.equals("Rainbow") || fx.equals("Typewriter")) {
                                            hasAnim = true;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (hasAnim) { anyAnimatedFx = true; break; }
                        }

                        if (!anyAnimatedFx) {
                            // FAST PATH: No animated effects on any slide.
                            // Render one PNG per slide and use FFmpeg concat demuxer with duration.
                            // This avoids writing thousands of duplicate frame files (e.g. a 3-min audio = 1 PNG instead of 5400).
                            useConcatDemuxer = true;
                            concatFile = new File(tempDir, "concat.txt");
                            StringBuilder concatContent = new StringBuilder();

                            for (int i = 0; i < slides.size(); i++) {
                                SlideData s = slides.get(i);
                                int slideDur = (s.audioDurationMs > 0) ? s.audioDurationMs : duration;
                                double slideDurSec = slideDur / 1000.0;

                                BufferedImage frame = renderFrame(
                                        s.image, s.text, s.fontName, s.fontSize,
                                        s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                        videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                        s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                        s.slideNumberX, s.slideNumberY,
                                        s.slideNumberSize, s.slideNumberColor,
                                        s.slideTexts,
                                        s.fxRoundCorners, s.fxCornerRadius,
                                        s.fxVignette, s.fxSepia, s.fxGrain,
                                        s.fxWaterRipple, s.fxGlitch, s.fxShake,
                                        s.overlayEnabled,
                                        s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                        s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX);

                                File slideFile = new File(tempDir, String.format("slide_%03d.png", i));
                                ImageIO.write(frame, "png", slideFile);

                                concatContent.append("file '").append(slideFile.getAbsolutePath().replace("'", "'\\''")).append("'\n");
                                concatContent.append("duration ").append(String.format("%.3f", slideDurSec)).append("\n");

                                int pct = (int) ((i + 1.0) / slides.size() * 60);
                                final int p = pct;
                                SwingUtilities.invokeLater(() -> progressBar.setValue(p));
                                publish("Rendered slide " + (i + 1) + "/" + slides.size());
                            }
                            // Concat demuxer requires last file repeated without duration
                            if (slides.size() > 0) {
                                concatContent.append("file '")
                                        .append(new File(tempDir, String.format("slide_%03d.png", slides.size() - 1))
                                                .getAbsolutePath().replace("'", "'\\''"))
                                        .append("'\n");
                            }

                            try (java.io.FileWriter fw = new java.io.FileWriter(concatFile)) {
                                fw.write(concatContent.toString());
                            }
                        } else {
                            // ANIMATED PATH: At least one slide has animated effects.
                            // Optimization: render base frame once (without animated fx), then clone + apply effects per frame.
                            // Also pipe raw pixels to FFmpeg instead of writing PNG files to disk.
                            usePipeEncoding = true;

                            // Start FFmpeg process that reads raw RGB from stdin
                            File videoOnly2 = new File(tempDir, "video_only.mp4");
                            java.util.List<String> pipeCmdList = new java.util.ArrayList<>();
                            pipeCmdList.add("ffmpeg");
                            pipeCmdList.add("-y");
                            pipeCmdList.add("-f");
                            pipeCmdList.add("rawvideo");
                            pipeCmdList.add("-pixel_format");
                            pipeCmdList.add("rgb24");
                            pipeCmdList.add("-video_size");
                            pipeCmdList.add(videoW + "x" + videoH);
                            pipeCmdList.add("-framerate");
                            pipeCmdList.add(String.valueOf(fps));
                            pipeCmdList.add("-i");
                            pipeCmdList.add("-");
                            pipeCmdList.add("-c:v");
                            pipeCmdList.add("libx264");
                            pipeCmdList.add("-preset");
                            pipeCmdList.add("medium");
                            pipeCmdList.add("-threads");
                            pipeCmdList.add("0");
                            pipeCmdList.add("-crf");
                            pipeCmdList.add(String.valueOf(crf));
                            pipeCmdList.add("-pix_fmt");
                            pipeCmdList.add("yuv420p");
                            pipeCmdList.add(videoOnly2.getAbsolutePath());

                            ProcessBuilder pipePb = new ProcessBuilder(pipeCmdList);
                            pipePb.redirectErrorStream(true);
                            Process pipeProc = pipePb.start();
                            java.io.OutputStream ffmpegStdin = new java.io.BufferedOutputStream(pipeProc.getOutputStream(), 1024 * 1024);

                            // Read FFmpeg output in background thread
                            StringBuilder pipeLog = new StringBuilder();
                            Thread convergenceReader = new Thread(() -> {
                                try (BufferedReader br = new BufferedReader(new InputStreamReader(pipeProc.getInputStream()))) {
                                    String line2;
                                    while ((line2 = br.readLine()) != null) {
                                        pipeLog.append(line2).append("\n");
                                    }
                                } catch (IOException ignored) {}
                            });
                            convergenceReader.setDaemon(true);
                            convergenceReader.start();

                            // Pre-allocate reusable byte buffer for raw RGB pixel data
                            byte[] rgbBytes = new byte[videoW * videoH * 3];

                            for (int i = 0; i < slides.size(); i++) {
                                SlideData s = slides.get(i);
                                int slideDur = (s.audioDurationMs > 0) ? s.audioDurationMs : duration;
                                int slideFrames = Math.max(1, (int) Math.round(slideDur / 1000.0 * fps));
                                boolean hasAnimatedFx = s.fxGrain > 0 || s.fxWaterRipple > 0 || s.fxGlitch > 0 || s.fxShake > 0;
                                boolean hasAnimatedText = false;
                                if (s.slideTexts != null) {
                                    for (SlideTextData stx : s.slideTexts) {
                                        if (stx.show && stx.textEffect != null) {
                                            String fx = stx.textEffect;
                                            if (fx.equals("Water Ripple") || fx.equals("Fire") || fx.equals("Ice")
                                                    || fx.equals("Rainbow") || fx.equals("Typewriter")) {
                                                hasAnimatedText = true;
                                                break;
                                            }
                                        }
                                    }
                                }

                                if (hasAnimatedFx && !hasAnimatedText) {
                                    // Render base frame ONCE without animated effects, then clone + apply effects
                                    publish("Rendering slide " + (i + 1) + " base + " + slideFrames + " effect frames...");
                                    BufferedImage baseFrame = renderFrame(
                                            s.image, s.text, s.fontName, s.fontSize,
                                            s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                            videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                            s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                            s.slideNumberX, s.slideNumberY,
                                            s.slideNumberSize, s.slideNumberColor,
                                            s.slideTexts,
                                            s.fxRoundCorners, s.fxCornerRadius,
                                            s.fxVignette, s.fxSepia, 0, 0, 0, 0,  // zero out animated fx
                                            s.overlayEnabled,
                                            s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                            s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX);
                                    // Cache base pixel data for fast cloning
                                    int[] basePixels = baseFrame.getRGB(0, 0, videoW, videoH, null, 0, videoW);

                                    for (int d = 0; d < slideFrames; d++) {
                                        // Clone base pixels into frame
                                        BufferedImage animFrame = new BufferedImage(videoW, videoH, BufferedImage.TYPE_INT_RGB);
                                        animFrame.setRGB(0, 0, videoW, videoH, basePixels, 0, videoW);
                                        // Apply only animated effects
                                        applyAnimatedEffects(animFrame, videoW, videoH,
                                                s.fxWaterRipple, s.fxGlitch, s.fxGrain, s.fxShake, d);
                                        // Write raw RGB to FFmpeg stdin
                                        writeRawRGB(animFrame, videoW, videoH, rgbBytes, ffmpegStdin);
                                        frameIndex++;
                                    }
                                } else if (hasAnimatedFx || hasAnimatedText) {
                                    // Has animated text effects — must render full frame each time
                                    publish("Rendering slide " + (i + 1) + " with " + slideFrames + " animated frames...");
                                    for (int d = 0; d < slideFrames; d++) {
                                        BufferedImage frame = renderFrame(
                                                s.image, s.text, s.fontName, s.fontSize,
                                                s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                                videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                                s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                                s.slideNumberX, s.slideNumberY,
                                                s.slideNumberSize, s.slideNumberColor,
                                                s.slideTexts,
                                                s.fxRoundCorners, s.fxCornerRadius,
                                                s.fxVignette, s.fxSepia, s.fxGrain,
                                                s.fxWaterRipple, s.fxGlitch, s.fxShake,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, d,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX);
                                        writeRawRGB(frame, videoW, videoH, rgbBytes, ffmpegStdin);
                                        frameIndex++;
                                    }
                                } else {
                                    // Static slide — render once, write same pixels for all frames
                                    BufferedImage frame = renderFrame(
                                            s.image, s.text, s.fontName, s.fontSize,
                                            s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                            videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                            s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                            s.slideNumberX, s.slideNumberY,
                                            s.slideNumberSize, s.slideNumberColor,
                                            s.slideTexts,
                                            s.fxRoundCorners, s.fxCornerRadius,
                                            s.fxVignette, s.fxSepia, 0, 0, 0, 0,
                                            s.overlayEnabled,
                                            s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                            s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX);
                                    writeRawRGB(frame, videoW, videoH, rgbBytes, ffmpegStdin);
                                    for (int d = 1; d < slideFrames; d++) {
                                        ffmpegStdin.write(rgbBytes);  // same bytes, no re-render
                                        frameIndex++;
                                    }
                                    frameIndex++;
                                }

                                int pct = (int) ((i + 1.0) / slides.size() * 60);
                                final int p = pct;
                                SwingUtilities.invokeLater(() -> progressBar.setValue(p));
                                publish("Rendered slide " + (i + 1) + "/" + slides.size());
                            }

                            // Close stdin and wait for FFmpeg to finish
                            ffmpegStdin.close();
                            int pipeExit = pipeProc.waitFor();
                            convergenceReader.join(5000);
                            if (pipeExit != 0) {
                                String lastLines = pipeLog.toString();
                                if (lastLines.length() > 1500) {
                                    lastLines = lastLines.substring(lastLines.length() - 1500);
                                }
                                throw new IOException(
                                        "ffmpeg video encoding failed (exit " + pipeExit + ").\n" +
                                                "FFmpeg output:\n" + lastLines);
                            }
                        }
                    }

                    // Step 1: Create video-only MP4
                    File videoOnly;
                    if (usePipeEncoding) {
                        // Pipe encoding already created the video file
                        videoOnly = new File(tempDir, "video_only.mp4");
                        publish("Video encoding completed via pipe.");
                        SwingUtilities.invokeLater(() -> progressBar.setValue(80));
                    } else {
                        publish("Encoding MP4 at " + videoW + "×" + videoH + " (CRF " + crf + ")...");
                        SwingUtilities.invokeLater(() -> progressBar.setValue(65));

                        videoOnly = new File(tempDir, "video_only.mp4");
                        java.util.List<String> videoCmd = new java.util.ArrayList<>();
                        videoCmd.add("ffmpeg");
                        videoCmd.add("-y");
                        if (useConcatDemuxer) {
                            // Fast path: use concat demuxer (1 PNG per slide instead of thousands of frames)
                            videoCmd.add("-f");
                            videoCmd.add("concat");
                            videoCmd.add("-safe");
                            videoCmd.add("0");
                            videoCmd.add("-i");
                            videoCmd.add(concatFile.getAbsolutePath());
                        } else {
                            // Frame sequence input
                            videoCmd.add("-framerate");
                            videoCmd.add(String.valueOf(fps));
                            videoCmd.add("-i");
                            videoCmd.add(new File(tempDir, "frame_%05d.png").getAbsolutePath());
                        }
                        if (useConcatDemuxer) {
                            // Set output framerate for concat demuxer input
                            videoCmd.add("-r");
                            videoCmd.add(String.valueOf(fps));
                        }
                        videoCmd.add("-c:v");
                        videoCmd.add("libx264");
                        videoCmd.add("-preset");
                        videoCmd.add("medium");
                        videoCmd.add("-threads");
                        videoCmd.add("0");
                        videoCmd.add("-crf");
                        videoCmd.add(String.valueOf(crf));
                        videoCmd.add("-pix_fmt");
                        videoCmd.add("yuv420p");
                        videoCmd.add("-movflags");
                        videoCmd.add("+faststart");
                        videoCmd.add(videoOnly.getAbsolutePath());

                        publish("Encoding video...");
                        ProcessBuilder pb = new ProcessBuilder(videoCmd);
                        pb.redirectErrorStream(true);
                        Process proc = pb.start();

                        StringBuilder ffmpegLog = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(proc.getInputStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                ffmpegLog.append(line).append("\n");
                                if (line.contains("frame=")) {
                                    publish("Encoding: " + line.trim());
                                }
                            }
                        }

                        int exit = proc.waitFor();
                        if (exit != 0) {
                            String lastLines = ffmpegLog.toString();
                            if (lastLines.length() > 1500) {
                                lastLines = lastLines.substring(lastLines.length() - 1500);
                            }
                            throw new IOException(
                                    "ffmpeg video encoding failed (exit " + exit + ").\n" +
                                            "Ensure ffmpeg is installed with H.264 (libx264) support.\n" +
                                            "Download: https://ffmpeg.org/download.html\n\n" +
                                            "FFmpeg output:\n" + lastLines);
                        }

                        SwingUtilities.invokeLater(() -> progressBar.setValue(80));
                    }

                    // Step 2: Merge individual slide audio files into one track (if any)
                    File mergedSlideAudio = null;
                    boolean hasSlideAudio = false;
                    for (SlideData s : slides) {
                        if (s.audioFile != null && s.audioFile.exists()) {
                            hasSlideAudio = true;
                            break;
                        }
                    }

                    if (hasSlideAudio) {
                        publish("Merging slide audio tracks...");
                        mergedSlideAudio = new File(tempDir, "merged_slide_audio.m4a");

                        // Build ffmpeg filter_complex: delay each slide's audio to its correct offset, then amix
                        java.util.List<String> mergeCmd = new java.util.ArrayList<>();
                        mergeCmd.add("ffmpeg");
                        mergeCmd.add("-y");

                        // Calculate each slide's start time and add audio inputs
                        int inputIdx = 0;
                        java.util.List<Integer> audioInputIndices = new java.util.ArrayList<>();
                        java.util.List<Long> audioDelays = new java.util.ArrayList<>();
                        long offsetMs = 0;

                        for (SlideData s : slides) {
                            int slideDur = (s.audioDurationMs > 0) ? s.audioDurationMs : duration;
                            if (s.audioFile != null && s.audioFile.exists()) {
                                mergeCmd.add("-i");
                                mergeCmd.add(s.audioFile.getAbsolutePath());
                                audioInputIndices.add(inputIdx);
                                audioDelays.add(offsetMs);
                                inputIdx++;
                            }
                            offsetMs += slideDur;
                        }

                        // Build filter_complex string
                        StringBuilder filterComplex = new StringBuilder();
                        for (int ai = 0; ai < audioInputIndices.size(); ai++) {
                            int idx = audioInputIndices.get(ai);
                            long delayMs = audioDelays.get(ai);
                            // adelay takes delay in ms, pad with silence after audio ends
                            filterComplex.append("[").append(idx).append(":a]adelay=")
                                    .append(delayMs).append("|").append(delayMs)
                                    .append("[a").append(ai).append("];");
                        }
                        // amix all delayed streams
                        for (int ai = 0; ai < audioInputIndices.size(); ai++) {
                            filterComplex.append("[a").append(ai).append("]");
                        }
                        filterComplex.append("amix=inputs=").append(audioInputIndices.size())
                                .append(":duration=longest:dropout_transition=0[aout]");

                        mergeCmd.add("-filter_complex");
                        mergeCmd.add(filterComplex.toString());
                        mergeCmd.add("-map");
                        mergeCmd.add("[aout]");
                        mergeCmd.add("-c:a");
                        mergeCmd.add("aac");
                        mergeCmd.add("-b:a");
                        mergeCmd.add("192k");
                        mergeCmd.add(mergedSlideAudio.getAbsolutePath());

                        ProcessBuilder mergePb = new ProcessBuilder(mergeCmd);
                        mergePb.redirectErrorStream(true);
                        Process mergeProc = mergePb.start();

                        StringBuilder mergeLog = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(mergeProc.getInputStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                mergeLog.append(line).append("\n");
                            }
                        }

                        int mergeExit = mergeProc.waitFor();
                        if (mergeExit != 0) {
                            String lastLines = mergeLog.toString();
                            if (lastLines.length() > 1500) {
                                lastLines = lastLines.substring(lastLines.length() - 1500);
                            }
                            publish("Warning: slide audio merge failed, continuing without slide audio.");
                            System.err.println("Slide audio merge failed (exit " + mergeExit + "):\n" + lastLines);
                            mergedSlideAudio = null;
                        }
                    }

                    // Determine the effective audio file to mux into the video
                    // Priority: if both slide audio and global audio exist, mix them together
                    File effectiveAudioFile = null;
                    File mixedAudioFile = null;

                    if (mergedSlideAudio != null && mergedSlideAudio.exists()
                            && finalAudioFile != null && finalAudioFile.exists()) {
                        // Mix slide audio and global audio together
                        publish("Mixing slide audio with global audio...");
                        mixedAudioFile = new File(tempDir, "mixed_audio.m4a");
                        java.util.List<String> mixCmd = new java.util.ArrayList<>();
                        mixCmd.add("ffmpeg");
                        mixCmd.add("-y");
                        mixCmd.add("-i");
                        mixCmd.add(mergedSlideAudio.getAbsolutePath());
                        mixCmd.add("-i");
                        mixCmd.add(finalAudioFile.getAbsolutePath());
                        mixCmd.add("-filter_complex");
                        mixCmd.add("[0:a][1:a]amix=inputs=2:duration=longest:dropout_transition=0[aout]");
                        mixCmd.add("-map");
                        mixCmd.add("[aout]");
                        mixCmd.add("-c:a");
                        mixCmd.add("aac");
                        mixCmd.add("-b:a");
                        mixCmd.add("192k");
                        mixCmd.add(mixedAudioFile.getAbsolutePath());

                        ProcessBuilder mixPb = new ProcessBuilder(mixCmd);
                        mixPb.redirectErrorStream(true);
                        Process mixProc = mixPb.start();
                        StringBuilder mixLog = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(mixProc.getInputStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                mixLog.append(line).append("\n");
                            }
                        }
                        int mixExit = mixProc.waitFor();
                        if (mixExit == 0) {
                            effectiveAudioFile = mixedAudioFile;
                        } else {
                            publish("Warning: audio mixing failed, using slide audio only.");
                            effectiveAudioFile = mergedSlideAudio;
                        }
                    } else if (mergedSlideAudio != null && mergedSlideAudio.exists()) {
                        effectiveAudioFile = mergedSlideAudio;
                    } else if (finalAudioFile != null && finalAudioFile.exists()) {
                        effectiveAudioFile = finalAudioFile;
                    }

                    // Step 3: Mux audio into the video (separate pass)
                    if (effectiveAudioFile != null && effectiveAudioFile.exists()) {
                        publish("Adding audio to video...");
                        java.util.List<String> muxCmd = new java.util.ArrayList<>();
                        muxCmd.add("ffmpeg");
                        muxCmd.add("-y");
                        muxCmd.add("-i");
                        muxCmd.add(videoOnly.getAbsolutePath());
                        muxCmd.add("-i");
                        muxCmd.add(effectiveAudioFile.getAbsolutePath());
                        muxCmd.add("-c:v");
                        muxCmd.add("copy");
                        muxCmd.add("-c:a");
                        muxCmd.add("aac");
                        muxCmd.add("-b:a");
                        muxCmd.add("192k");
                        muxCmd.add("-shortest");
                        muxCmd.add("-movflags");
                        muxCmd.add("+faststart");
                        muxCmd.add(finalOut.getAbsolutePath());

                        ProcessBuilder muxPb = new ProcessBuilder(muxCmd);
                        muxPb.redirectErrorStream(true);
                        Process muxProc = muxPb.start();

                        StringBuilder muxLog = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(muxProc.getInputStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                muxLog.append(line).append("\n");
                            }
                        }

                        int muxExit = muxProc.waitFor();
                        if (muxExit != 0) {
                            String lastLines = muxLog.toString();
                            if (lastLines.length() > 1500) {
                                lastLines = lastLines.substring(lastLines.length() - 1500);
                            }
                            throw new IOException(
                                    "ffmpeg audio muxing failed (exit " + muxExit + ").\n" +
                                            "FFmpeg output:\n" + lastLines);
                        }
                        videoOnly.delete();
                    } else {
                        // No audio — just rename video-only file to final output
                        if (!videoOnly.renameTo(finalOut)) {
                            // renameTo can fail across filesystems, fall back to copy
                            java.nio.file.Files.copy(videoOnly.toPath(), finalOut.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            videoOnly.delete();
                        }
                    }

                    SwingUtilities.invokeLater(() -> progressBar.setValue(90));

                    SwingUtilities.invokeLater(() -> progressBar.setValue(100));

                    long fileSize = finalOut.length();
                    double sizeMB = fileSize / (1024.0 * 1024.0);
                    double totalDurationSec = 0;
                    for (SlideData s : slides) {
                        totalDurationSec += ((s.audioDurationMs > 0) ? s.audioDurationMs : duration) / 1000.0;
                    }

                    int slideAudioCount = 0;
                    for (SlideData s : slides) {
                        if (s.audioFile != null && s.audioFile.exists()) slideAudioCount++;
                    }
                    String audioInfo;
                    if (slideAudioCount > 0 && finalAudioFile != null) {
                        audioInfo = "Audio: " + slideAudioCount + " slide audio(s) + " + finalAudioFile.getName() + " (AAC 192k)\n";
                    } else if (slideAudioCount > 0) {
                        audioInfo = "Audio: " + slideAudioCount + " slide audio(s) (AAC 192k)\n";
                    } else if (finalAudioFile != null) {
                        audioInfo = "Audio: " + finalAudioFile.getName() + " (AAC 192k)\n";
                    } else {
                        audioInfo = "Audio: None\n";
                    }

                    String scrollInfo = scrollEnabled
                            ? "Scroll: " + finalScrollDir + "\n"
                            : "";

                    finalInfo = String.format(
                            "✅ MP4 Video created successfully!\n\n" +
                                    "Resolution: %d×%d\n" +
                                    "Quality: CRF %d\n" +
                                    "Size: %.2f MB\n" +
                                    "Slides: %d (%d frames at %d fps)\n" +
                                    "Duration: %.1f seconds\n" +
                                    "%s%s\n" +
                                    "File: %s\n\n" +
                                    "Upload to Twitter/X for fullscreen playback!",
                            videoW, videoH, crf, sizeMB, slides.size(),
                            totalFrames, fps, totalDurationSec,
                            scrollInfo, audioInfo, finalOut.getAbsolutePath());

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

    private void writeAnimatedGif(List<BufferedImage> frames, List<Integer> delaysMs, File output) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);

            for (int i = 0; i < frames.size(); i++) {
                BufferedImage indexed = convertToIndexed(frames.get(i));
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        new ImageTypeSpecifier(indexed), param);
                int delayMs = delaysMs.get(Math.min(i, delaysMs.size() - 1));
                configureGifMetadata(metadata, delayMs / 10, i == 0);
                writer.writeToSequence(new IIOImage(indexed, null, metadata), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private void writeGifWithFfmpeg(List<BufferedImage> frames, List<Integer> delaysMs, File output) throws IOException {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "gif_frames_" + System.currentTimeMillis());
        if (!tempDir.mkdirs() && !tempDir.exists()) {
            throw new IOException("Failed to create temp directory: " + tempDir);
        }
        try {
            // Use concat demuxer to support per-frame durations
            int ffmpegFps = 25;
            StringBuilder concatList = new StringBuilder();
            int outIdx = 0;
            for (int i = 0; i < frames.size(); i++) {
                String fname = String.format("frame_%04d.png", i);
                ImageIO.write(frames.get(i), "png", new File(tempDir, fname));
                int delayMs = delaysMs.get(Math.min(i, delaysMs.size() - 1));
                double durationSec = delayMs / 1000.0;
                concatList.append("file '").append(fname).append("'\n");
                concatList.append("duration ").append(String.format("%.4f", durationSec)).append("\n");
            }
            // ffmpeg concat requires last file repeated without duration
            if (!frames.isEmpty()) {
                concatList.append("file '").append(String.format("frame_%04d.png", frames.size() - 1)).append("'\n");
            }
            File concatFile = new File(tempDir, "concat.txt");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(concatFile)) {
                pw.print(concatList);
            }
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-f", "concat", "-safe", "0",
                    "-i", concatFile.getAbsolutePath(),
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
            int[][] attempts = isPortrait()
                    ? new int[][] {{720, 1280}, {540, 960}, {360, 640}}
                    : new int[][] {{1280, 720}, {960, 540}, {640, 360}};
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
                                    s.slideNumberSize, s.slideNumberColor,
                                    s.slideTexts,
                                    s.fxRoundCorners, s.fxCornerRadius,
                                    s.fxVignette, s.fxSepia, s.fxGrain,
                                    s.fxWaterRipple, s.fxGlitch, s.fxShake,
                                    s.overlayEnabled,
                                    s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                    s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX);
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

    /**
     * Write a BufferedImage as raw RGB24 bytes to an OutputStream (for piping to FFmpeg).
     * Reuses the provided byte array to avoid allocation per frame.
     */
    static void writeRawRGB(BufferedImage img, int w, int h, byte[] rgbBytes, java.io.OutputStream out) throws IOException {
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < pixels.length; i++) {
            rgbBytes[i * 3]     = (byte) ((pixels[i] >> 16) & 0xFF); // R
            rgbBytes[i * 3 + 1] = (byte) ((pixels[i] >> 8) & 0xFF);  // G
            rgbBytes[i * 3 + 2] = (byte) (pixels[i] & 0xFF);          // B
        }
        out.write(rgbBytes);
    }

    /**
     * Apply only the animated effects (water ripple, glitch, grain, shake) to a frame.
     * Used to avoid re-rendering the entire base image for every animation frame.
     */
    static void applyAnimatedEffects(BufferedImage frame, int targetW, int targetH,
                                     int fxWaterRipple, int fxGlitch, int fxGrain, int fxShake,
                                     int animFrameIndex) {
        if (fxWaterRipple > 0) {
            double strength = fxWaterRipple / 50.0;
            int[] src = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            int[] dst = new int[src.length];
            double amplitude = targetH * 0.006 * strength;
            double frequency = 2.0 * Math.PI / (targetH * 0.12);
            double phase = animFrameIndex * 0.15;
            for (int y = 0; y < targetH; y++) {
                int xOff = (int)(amplitude * Math.sin(frequency * y + phase));
                for (int x = 0; x < targetW; x++) {
                    int sx = Math.max(0, Math.min(targetW - 1, x + xOff));
                    dst[y * targetW + x] = src[y * targetW + sx];
                }
            }
            frame.setRGB(0, 0, targetW, targetH, dst, 0, targetW);
        }

        if (fxGlitch > 0) {
            double strength = fxGlitch / 50.0;
            int[] px = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            Random glitchRng = new Random(137L + animFrameIndex * 31L);
            int numBands = (int)((4 + glitchRng.nextInt(6)) * strength);
            int maxShift = Math.max(1, (int)(targetW / 10 * strength));
            for (int band = 0; band < numBands; band++) {
                int bandY = glitchRng.nextInt(targetH);
                int bandH2 = (int)((2 + glitchRng.nextInt(Math.max(1, targetH / 40))) * strength);
                int shift = glitchRng.nextInt(Math.max(1, maxShift)) - maxShift / 2;
                for (int y = bandY; y < Math.min(targetH, bandY + bandH2); y++) {
                    int[] row = new int[targetW];
                    for (int x = 0; x < targetW; x++) {
                        row[x] = px[y * targetW + ((x + shift + targetW) % targetW)];
                    }
                    System.arraycopy(row, 0, px, y * targetW, targetW);
                }
            }
            int rShift = Math.max(1, (int)(targetW / 80 * strength));
            int channelPhase = (animFrameIndex % 3);
            int[] result = new int[px.length];
            for (int y = 0; y < targetH; y++) {
                for (int x = 0; x < targetW; x++) {
                    int idx = y * targetW + x;
                    int rOff = channelPhase == 0 ? rShift : (channelPhase == 1 ? -rShift : rShift / 2);
                    int bOff = channelPhase == 0 ? -rShift : (channelPhase == 1 ? rShift : -rShift / 2);
                    int rIdx = y * targetW + Math.min(targetW - 1, Math.max(0, x + rOff));
                    int bIdx = y * targetW + Math.min(targetW - 1, Math.max(0, x + bOff));
                    int rv = (px[rIdx] >> 16) & 0xFF;
                    int gv = (px[idx] >> 8) & 0xFF;
                    int bv = px[bIdx] & 0xFF;
                    result[idx] = (0xFF << 24) | (rv << 16) | (gv << 8) | bv;
                }
            }
            frame.setRGB(0, 0, targetW, targetH, result, 0, targetW);
        }

        if (fxGrain > 0) {
            int range = (int)(60 * fxGrain / 50.0);
            int half = range / 2;
            int[] px = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            Random grainRng = new Random(42L + animFrameIndex * 17L);
            for (int i = 0; i < px.length; i++) {
                int noise = grainRng.nextInt(Math.max(1, range)) - half;
                int r = Math.max(0, Math.min(255, ((px[i] >> 16) & 0xFF) + noise));
                int gv = Math.max(0, Math.min(255, ((px[i] >> 8) & 0xFF) + noise));
                int b = Math.max(0, Math.min(255, (px[i] & 0xFF) + noise));
                px[i] = (0xFF << 24) | (r << 16) | (gv << 8) | b;
            }
            frame.setRGB(0, 0, targetW, targetH, px, 0, targetW);
        }

        if (fxShake > 0) {
            double strength = fxShake / 50.0;
            BufferedImage shaken = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = shaken.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            sg.setColor(new Color(21, 32, 43));
            sg.fillRect(0, 0, targetW, targetH);
            double shakeAngle = Math.toRadians(1.8 * strength * Math.sin(animFrameIndex * 0.7));
            double shakeOffX = targetW * 0.01 * strength * Math.sin(animFrameIndex * 1.1);
            double shakeOffY = targetH * 0.008 * strength * Math.cos(animFrameIndex * 0.9);
            AffineTransform at = new AffineTransform();
            at.translate(targetW / 2.0, targetH / 2.0);
            at.rotate(shakeAngle);
            at.translate(shakeOffX, shakeOffY);
            at.translate(-targetW / 2.0, -targetH / 2.0);
            sg.drawImage(frame, at, null);
            sg.dispose();
            Graphics2D fg = frame.createGraphics();
            fg.drawImage(shaken, 0, 0, null);
            fg.dispose();
        }
    }

    private static int probeAudioDurationMs(File audioFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "quiet", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    audioFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line = br.readLine();
                if (line != null && !line.isEmpty()) {
                    double seconds = Double.parseDouble(line.trim());
                    return (int) (seconds * 1000);
                }
            }
            proc.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
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

    // ==================== SlideTextData ====================

    static final String[] TEXT_EFFECTS = {
        "None", "Shadow", "Glow", "Neon", "Outline", "Emboss",
        "Water Ripple", "Fire", "Ice", "Rainbow", "Typewriter", "Stone Engraving"
    };

    static class SlideTextData {
        final boolean show;
        final String text;
        final String fontName;
        final int fontSize;
        final int fontStyle;
        final Color color;
        final int x;
        final int y;
        final int bgOpacity;
        final Color bgColor;
        final boolean justify;
        final int widthPct;
        final int shiftX;
        final int alignment;
        final String textEffect;
        final int textEffectIntensity;

        SlideTextData(boolean show, String text, String fontName, int fontSize,
                      int fontStyle, Color color, int x, int y, int bgOpacity,
                      Color bgColor, boolean justify, int widthPct, int shiftX,
                      int alignment) {
            this(show, text, fontName, fontSize, fontStyle, color, x, y, bgOpacity,
                    bgColor, justify, widthPct, shiftX, alignment, "None", 50);
        }

        SlideTextData(boolean show, String text, String fontName, int fontSize,
                      int fontStyle, Color color, int x, int y, int bgOpacity,
                      Color bgColor, boolean justify, int widthPct, int shiftX,
                      int alignment, String textEffect, int textEffectIntensity) {
            this.show = show;
            this.text = text;
            this.fontName = fontName;
            this.fontSize = fontSize;
            this.fontStyle = fontStyle;
            this.color = color;
            this.x = x;
            this.y = y;
            this.bgOpacity = bgOpacity;
            this.bgColor = bgColor;
            this.justify = justify;
            this.widthPct = widthPct;
            this.shiftX = shiftX;
            this.alignment = alignment;
            this.textEffect = textEffect != null ? textEffect : "None";
            this.textEffectIntensity = textEffectIntensity;
        }
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
        final List<SlideTextData> slideTexts;
        final boolean fxRoundCorners;
        final int fxCornerRadius;
        final int fxVignette;
        final int fxSepia;
        final int fxGrain;
        final int fxWaterRipple;
        final int fxGlitch;
        final int fxShake;
        final boolean overlayEnabled;
        final String overlayShape;
        final String overlayBgMode;
        final Color overlayBgColor;
        final int overlayX;
        final int overlayY;
        final int overlaySize;
        final boolean textJustify;
        final int textWidthPct;
        final String highlightText;
        final Color highlightColor;
        final int textShiftX;
        final int audioDurationMs;
        final File audioFile;

        SlideData(BufferedImage image, String text, String fontName, int fontSize,
                  int fontStyle, Color fontColor, int alignment, boolean showPin, String displayMode,
                  int subtitleY, int subtitleBgOpacity,
                  boolean showSlideNumber, String slideNumberText, String slideNumberFontName,
                  int slideNumberX, int slideNumberY,
                  int slideNumberSize, Color slideNumberColor,
                  List<SlideTextData> slideTexts,
                  boolean fxRoundCorners, int fxCornerRadius,
                  int fxVignette, int fxSepia, int fxGrain,
                  int fxWaterRipple, int fxGlitch, int fxShake,
                  boolean overlayEnabled,
                  String overlayShape, String overlayBgMode, Color overlayBgColor,
                  int overlayX, int overlayY, int overlaySize,
                  boolean textJustify, int textWidthPct,
                  String highlightText, Color highlightColor,
                  int textShiftX,
                  int audioDurationMs, File audioFile) {
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
            this.slideTexts = slideTexts;
            this.fxRoundCorners = fxRoundCorners;
            this.fxCornerRadius = fxCornerRadius;
            this.fxVignette = fxVignette;
            this.fxSepia = fxSepia;
            this.fxGrain = fxGrain;
            this.fxWaterRipple = fxWaterRipple;
            this.fxGlitch = fxGlitch;
            this.fxShake = fxShake;
            this.overlayEnabled = overlayEnabled;
            this.overlayShape = overlayShape;
            this.overlayBgMode = overlayBgMode;
            this.overlayBgColor = overlayBgColor;
            this.overlayX = overlayX;
            this.overlayY = overlayY;
            this.overlaySize = overlaySize;
            this.textJustify = textJustify;
            this.textWidthPct = textWidthPct;
            this.highlightText = highlightText;
            this.highlightColor = highlightColor;
            this.textShiftX = textShiftX;
            this.audioDurationMs = audioDurationMs;
            this.audioFile = audioFile;
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
        private final List<SlideTextData> slideTextItems = new ArrayList<>();
        private int currentSlideTextIndex = 0;
        private boolean isLoadingSlideText = false;
        private final JComboBox<String> slideTextSelector;
        // Shared slide text UI controls
        private final JCheckBox slideTextCheckBox;
        private final JTextArea slideTextArea;
        private final JComboBox<String> slideTextFontCombo;
        private final JSpinner slideTextSizeSpinner;
        private final JToggleButton slideTextBoldBtn;
        private final JToggleButton slideTextItalicBtn;
        private final JButton slideTextColorBtn;
        private Color slideTextColor = Color.YELLOW;
        private final JButton slideTextBgColorBtn;
        private Color slideTextBgColor = Color.BLACK;
        private final JSpinner slideTextXSpinner;
        private final JSpinner slideTextYSpinner;
        private final JSpinner slideTextBgSpinner;
        private final JCheckBox slideTextJustifyCheck;
        private final JSpinner slideTextWidthSpinner;
        private final JSpinner slideTextShiftXSpinner;
        private final JComboBox<String> slideTextAlignCombo;
        private final JComboBox<String> slideTextEffectCombo;
        private final JSpinner slideTextEffectIntensitySpinner;
        private final JSpinner subtitleYSpinner;
        private final JSpinner subtitleBgOpacitySpinner;
        private final JCheckBox fxRoundCornersCheck;
        private final JSpinner fxCornerRadiusSpinner;
        private final JCheckBox fxVignetteCheck;
        private final JSpinner fxVignetteSpinner;
        private final JCheckBox fxSepiaCheck;
        private final JSpinner fxSepiaSpinner;
        private final JCheckBox fxGrainCheck;
        private final JSpinner fxGrainSpinner;
        private final JCheckBox fxWaterRippleCheck;
        private final JSpinner fxWaterRippleSpinner;
        private final JCheckBox fxGlitchCheck;
        private final JSpinner fxGlitchSpinner;
        private final JCheckBox fxShakeCheck;
        private final JSpinner fxShakeSpinner;
        private final JCheckBox overlayCheckBox;
        private final JComboBox<String> overlayShapeCombo;
        private final JComboBox<String> overlayBgCombo;
        private final JButton overlayBgColorBtn;
        private Color overlayBgColor = new Color(21, 32, 43);
        private final JSpinner overlayXSpinner;
        private final JSpinner overlayYSpinner;
        private final JSpinner overlaySizeSpinner;
        private final JCheckBox justifyCheckBox;
        private final JSpinner textWidthSpinner;
        private final JTextField highlightField;
        private final JButton highlightColorBtn;
        private Color highlightColor = new Color(255, 255, 0, 180);
        private final JSpinner textShiftXSpinner;
        private final JLabel livePreviewLabel;
        private BufferedImage loadedImage;
        private Color selectedColor = Color.WHITE;
        private final Timer previewTimer;

        boolean isTitleGridSlide = false;
        private int gridLayoutIndex = 1;
        private List<BufferedImage> gridSourceImages = null;
        private BufferedImage titleBgImage = null;

        private File slideAudioFile;
        private int slideAudioDurationMs = -1;
        private final JButton audioBtn;
        private final JLabel audioFileLabel;
        private final JLabel audioDurationLabel;
        private final JButton audioClearBtn;








        SlideRow(int number) {
            panel = new JPanel(new BorderLayout(10, 0));
            panel.setBackground(new Color(44, 47, 51));
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(60, 63, 68), 1, true),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 480));
            panel.setPreferredSize(new Dimension(1100, 470));

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
            imagePreview.setPreferredSize(new Dimension(160, 70));
            imagePreview.setMinimumSize(new Dimension(120, 50));
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
            livePreviewLabel.setPreferredSize(new Dimension(160, 140));
            livePreviewLabel.setBorder(BorderFactory.createLineBorder(new Color(60, 63, 68)));
            livePreviewLabel.setOpaque(true);
            livePreviewLabel.setBackground(new Color(21, 32, 43));

            centerPanel.add(imagePreview, BorderLayout.NORTH);
            centerPanel.add(livePreviewLabel, BorderLayout.CENTER);

            // RIGHT: formatting + text
            JPanel rightPanel = new JPanel(new BorderLayout(0, 6));
            rightPanel.setBackground(new Color(44, 47, 51));

            JPanel toolbar1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            toolbar1.setBackground(new Color(60, 70, 110));

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

            // ===== Toolbar Row 1b: Justify, Width, Highlight =====
            JPanel toolbar1b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            toolbar1b.setBackground(new Color(60, 70, 110));

            justifyCheckBox = new JCheckBox("Justify", false);
            justifyCheckBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            justifyCheckBox.setForeground(Color.LIGHT_GRAY);
            justifyCheckBox.setBackground(new Color(44, 47, 51));
            justifyCheckBox.setFocusPainted(false);
            justifyCheckBox.setToolTipText("Justify text: make all lines the same width by spacing words");
            justifyCheckBox.addActionListener(e -> onFormatChanged());

            textWidthSpinner = new JSpinner(new SpinnerNumberModel(100, 20, 100, 5));
            textWidthSpinner.setPreferredSize(new Dimension(50, 24));
            textWidthSpinner.setToolTipText("Text width % of frame (narrower = more wrapping)");
            textWidthSpinner.addChangeListener(e -> onFormatChanged());

            highlightField = new JTextField(10);
            highlightField.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            highlightField.setPreferredSize(new Dimension(90, 24));
            highlightField.setToolTipText("Word or phrase to highlight");
            highlightField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { onFormatChanged(); }
                @Override public void removeUpdate(DocumentEvent e) { onFormatChanged(); }
                @Override public void changedUpdate(DocumentEvent e) { onFormatChanged(); }
            });

            highlightColorBtn = new JButton("\u25A0");
            highlightColorBtn.setForeground(highlightColor);
            highlightColorBtn.setFont(new Font("Segoe UI", Font.PLAIN, 16));
            highlightColorBtn.setPreferredSize(new Dimension(32, 24));
            highlightColorBtn.setFocusPainted(false);
            highlightColorBtn.setToolTipText("Highlight color");
            highlightColorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(panel, "Highlight Color", highlightColor);
                if (c != null) {
                    highlightColor = new Color(c.getRed(), c.getGreen(), c.getBlue(), 180);
                    highlightColorBtn.setForeground(highlightColor);
                    onFormatChanged();
                }
            });

            textShiftXSpinner = new JSpinner(new SpinnerNumberModel(0, -50, 50, 1));
            textShiftXSpinner.setPreferredSize(new Dimension(50, 24));
            textShiftXSpinner.setToolTipText("Shift text left/right (% of frame width)");
            textShiftXSpinner.addChangeListener(e -> onFormatChanged());

            toolbar1b.add(styledLabel("      "));
            toolbar1b.add(justifyCheckBox);
            toolbar1b.add(styledLabel("Width%:"));
            toolbar1b.add(textWidthSpinner);
            toolbar1b.add(styledLabel("Shift:"));
            toolbar1b.add(textShiftXSpinner);
            toolbar1b.add(styledLabel("Highlight:"));
            toolbar1b.add(highlightField);
            toolbar1b.add(highlightColorBtn);

            // ===== Toolbar Row 2: Image display mode =====
            JPanel toolbar2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            toolbar2.setBackground(new Color(50, 95, 95));

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
            toolbar3.setBackground(new Color(85, 60, 110));

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

            // ===== Toolbar Row 4: Slide text overlays (multiple via dropdown) =====
            JPanel toolbar4a = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            toolbar4a.setBackground(new Color(50, 95, 60));
            JPanel toolbar4b = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            toolbar4b.setBackground(new Color(50, 95, 60));
            JPanel toolbar4c = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            toolbar4c.setBackground(new Color(50, 95, 60));

            // Initialize with one default slide text item
            slideTextItems.add(new SlideTextData(false, "", loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI",
                    40, Font.PLAIN, Color.YELLOW, 50, 50, 0, Color.BLACK, false, 100, 0, SwingConstants.CENTER));

            slideTextSelector = new JComboBox<>(new String[]{"Text 1"});
            slideTextSelector.setPreferredSize(new Dimension(70, 24));
            slideTextSelector.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextSelector.setToolTipText("Select which slide text to edit");
            slideTextSelector.addActionListener(e -> {
                if (isLoadingSlideText) return;
                int newIndex = slideTextSelector.getSelectedIndex();
                if (newIndex >= 0 && newIndex < slideTextItems.size()) {
                    saveCurrentSlideTextToItem();
                    currentSlideTextIndex = newIndex;
                    loadSlideTextFromItem(currentSlideTextIndex);
                }
            });

            JButton addSlideTextBtn = new JButton("+");
            addSlideTextBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
            addSlideTextBtn.setPreferredSize(new Dimension(28, 24));
            addSlideTextBtn.setFocusPainted(false);
            addSlideTextBtn.setToolTipText("Add another slide text overlay");
            addSlideTextBtn.addActionListener(e -> {
                saveCurrentSlideTextToItem();
                slideTextItems.add(new SlideTextData(false, "", loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI",
                        40, Font.PLAIN, Color.YELLOW, 50, 50, 0, Color.BLACK, false, 100, 0, SwingConstants.CENTER));
                currentSlideTextIndex = slideTextItems.size() - 1;
                isLoadingSlideText = true;
                try {
                    rebuildSlideTextSelector();
                    slideTextSelector.setSelectedIndex(currentSlideTextIndex);
                } finally {
                    isLoadingSlideText = false;
                }
                loadSlideTextFromItem(currentSlideTextIndex);
                onFormatChanged();
            });

            JButton removeSlideTextBtn = new JButton("\u2212");
            removeSlideTextBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
            removeSlideTextBtn.setPreferredSize(new Dimension(28, 24));
            removeSlideTextBtn.setFocusPainted(false);
            removeSlideTextBtn.setToolTipText("Remove current slide text overlay");
            removeSlideTextBtn.addActionListener(e -> {
                if (slideTextItems.size() <= 1) return;
                slideTextItems.remove(currentSlideTextIndex);
                if (currentSlideTextIndex >= slideTextItems.size()) {
                    currentSlideTextIndex = slideTextItems.size() - 1;
                }
                isLoadingSlideText = true;
                try {
                    rebuildSlideTextSelector();
                    slideTextSelector.setSelectedIndex(currentSlideTextIndex);
                } finally {
                    isLoadingSlideText = false;
                }
                loadSlideTextFromItem(currentSlideTextIndex);
                onFormatChanged();
            });

            slideTextCheckBox = new JCheckBox("Slide Text", false);
            slideTextCheckBox.setFont(new Font("Segoe UI", Font.BOLD, 11));
            slideTextCheckBox.setForeground(new Color(100, 220, 100));
            slideTextCheckBox.setBackground(new Color(44, 47, 51));
            slideTextCheckBox.setFocusPainted(false);
            slideTextCheckBox.addActionListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextArea = new JTextArea("", 2, 10);
            slideTextArea.setFont(new Font("Segoe UI", Font.BOLD, 12));
            slideTextArea.setLineWrap(false);
            slideTextArea.setBackground(new Color(35, 38, 42));
            slideTextArea.setForeground(Color.YELLOW);
            slideTextArea.setCaretColor(Color.WHITE);
            slideTextArea.setToolTipText("Text to display on slide (multiline)");
            slideTextArea.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void removeUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void changedUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
            });
            JScrollPane slideTextScroll = new JScrollPane(slideTextArea);
            slideTextScroll.setPreferredSize(new Dimension(140, 48));
            slideTextScroll.setBorder(BorderFactory.createLineBorder(new Color(60, 63, 68)));

            String[] systemFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            java.util.List<String> allFonts = new java.util.ArrayList<>();
            for (String fn : loadedFontNames) allFonts.add(fn);
            for (String fn : systemFonts) {
                if (!allFonts.contains(fn)) allFonts.add(fn);
            }
            slideTextFontCombo = new JComboBox<>(allFonts.toArray(new String[0]));
            slideTextFontCombo.setSelectedItem(loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI");
            slideTextFontCombo.setPreferredSize(new Dimension(105, 28));
            slideTextFontCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextFontCombo.setToolTipText("Slide text font (loaded fonts listed first)");
            slideTextFontCombo.addActionListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextSizeSpinner = new JSpinner(new SpinnerNumberModel(40, 8, 500, 2));
            slideTextSizeSpinner.setPreferredSize(new Dimension(55, 28));
            slideTextSizeSpinner.setToolTipText("Slide text font size");
            slideTextSizeSpinner.addChangeListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextBoldBtn = new JToggleButton("B");
            slideTextBoldBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
            slideTextBoldBtn.setPreferredSize(new Dimension(32, 28));
            slideTextBoldBtn.setFocusPainted(false);
            slideTextBoldBtn.setToolTipText("Bold");
            slideTextBoldBtn.addActionListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextItalicBtn = new JToggleButton("I");
            slideTextItalicBtn.setFont(new Font("Segoe UI", Font.ITALIC, 12));
            slideTextItalicBtn.setPreferredSize(new Dimension(32, 28));
            slideTextItalicBtn.setFocusPainted(false);
            slideTextItalicBtn.setToolTipText("Italic");
            slideTextItalicBtn.addActionListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextColorBtn = new JButton("\u25a0");
            slideTextColorBtn.setForeground(slideTextColor);
            slideTextColorBtn.setFont(new Font("Segoe UI", Font.PLAIN, 18));
            slideTextColorBtn.setPreferredSize(new Dimension(36, 28));
            slideTextColorBtn.setFocusPainted(false);
            slideTextColorBtn.setToolTipText("Slide Text Color");
            slideTextColorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(panel, "Slide Text Color", slideTextColor);
                if (c != null) {
                    slideTextColor = c;
                    slideTextColorBtn.setForeground(c);
                    onFormatChanged();
                }
            });

            toolbar4a.add(slideTextSelector);
            toolbar4a.add(addSlideTextBtn);
            toolbar4a.add(removeSlideTextBtn);
            toolbar4a.add(slideTextCheckBox);
            toolbar4a.add(slideTextScroll);
            toolbar4a.add(slideTextFontCombo);
            toolbar4a.add(styledLabel("Size:"));
            toolbar4a.add(slideTextSizeSpinner);
            toolbar4a.add(slideTextBoldBtn);
            toolbar4a.add(slideTextItalicBtn);
            toolbar4a.add(slideTextColorBtn);

            slideTextXSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
            slideTextXSpinner.setPreferredSize(new Dimension(50, 28));
            slideTextXSpinner.setToolTipText("X position (% of width)");
            slideTextXSpinner.addChangeListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextYSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
            slideTextYSpinner.setPreferredSize(new Dimension(50, 28));
            slideTextYSpinner.setToolTipText("Y position (% of height)");
            slideTextYSpinner.addChangeListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextBgSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 5));
            slideTextBgSpinner.setPreferredSize(new Dimension(50, 28));
            slideTextBgSpinner.setToolTipText("Slide text background opacity (0=transparent, 100=solid)");
            slideTextBgSpinner.addChangeListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextBgColorBtn = new JButton("\u25a0");
            slideTextBgColorBtn.setForeground(slideTextBgColor);
            slideTextBgColorBtn.setFont(new Font("Segoe UI", Font.PLAIN, 18));
            slideTextBgColorBtn.setPreferredSize(new Dimension(36, 28));
            slideTextBgColorBtn.setFocusPainted(false);
            slideTextBgColorBtn.setToolTipText("Slide Text Background Color");
            slideTextBgColorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(panel, "Slide Text BG Color", slideTextBgColor);
                if (c != null) {
                    slideTextBgColor = c;
                    slideTextBgColorBtn.setForeground(c);
                    onFormatChanged();
                }
            });

            slideTextJustifyCheck = new JCheckBox("Justify", false);
            slideTextJustifyCheck.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextJustifyCheck.setForeground(Color.LIGHT_GRAY);
            slideTextJustifyCheck.setBackground(new Color(44, 47, 51));
            slideTextJustifyCheck.setFocusPainted(false);
            slideTextJustifyCheck.setToolTipText("Justify slide text: make all lines same width");
            slideTextJustifyCheck.addActionListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextWidthSpinner = new JSpinner(new SpinnerNumberModel(100, 20, 100, 5));
            slideTextWidthSpinner.setPreferredSize(new Dimension(50, 24));
            slideTextWidthSpinner.setToolTipText("Slide text width % of frame");
            slideTextWidthSpinner.addChangeListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextShiftXSpinner = new JSpinner(new SpinnerNumberModel(0, -50, 50, 1));
            slideTextShiftXSpinner.setPreferredSize(new Dimension(50, 24));
            slideTextShiftXSpinner.setToolTipText("Shift slide text left/right (% of frame width)");
            slideTextShiftXSpinner.addChangeListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            toolbar4b.add(styledLabel("  "));
            toolbar4b.add(styledLabel("X%:"));
            toolbar4b.add(slideTextXSpinner);
            toolbar4b.add(styledLabel("Y%:"));
            toolbar4b.add(slideTextYSpinner);
            toolbar4b.add(styledLabel("BG%:"));
            toolbar4b.add(slideTextBgSpinner);
            toolbar4b.add(slideTextBgColorBtn);
            toolbar4b.add(slideTextJustifyCheck);
            toolbar4b.add(styledLabel("W%:"));
            toolbar4b.add(slideTextWidthSpinner);
            toolbar4b.add(styledLabel("Shift:"));
            toolbar4b.add(slideTextShiftXSpinner);

            slideTextAlignCombo = new JComboBox<>(new String[]{"Center", "Left", "Right"});
            slideTextAlignCombo.setPreferredSize(new Dimension(75, 24));
            slideTextAlignCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextAlignCombo.addActionListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextEffectCombo = new JComboBox<>(TEXT_EFFECTS);
            slideTextEffectCombo.setPreferredSize(new Dimension(105, 24));
            slideTextEffectCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextEffectCombo.setToolTipText("Text visual effect");
            slideTextEffectCombo.addActionListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextEffectIntensitySpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 5));
            slideTextEffectIntensitySpinner.setPreferredSize(new Dimension(50, 24));
            slideTextEffectIntensitySpinner.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextEffectIntensitySpinner.setToolTipText("Effect intensity (0-100)");
            slideTextEffectIntensitySpinner.addChangeListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            toolbar4c.add(styledLabel("      "));
            toolbar4c.add(styledLabel("Align:"));
            toolbar4c.add(slideTextAlignCombo);
            toolbar4c.add(styledLabel("  Effect:"));
            toolbar4c.add(slideTextEffectCombo);
            toolbar4c.add(styledLabel("Power:"));
            toolbar4c.add(slideTextEffectIntensitySpinner);

            // ===== Toolbar Row 5: Image Effects (3 rows) =====
            JPanel toolbar5a = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
            toolbar5a.setBackground(new Color(110, 75, 45));
            JPanel toolbar5b = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
            toolbar5b.setBackground(new Color(110, 75, 45));
            JPanel toolbar5c = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
            toolbar5c.setBackground(new Color(110, 75, 45));

            JLabel fxLabel = styledLabel("\u2728 FX:");
            fxLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
            fxLabel.setForeground(new Color(220, 120, 255));

            fxRoundCornersCheck = new JCheckBox("Corners", false);
            fxRoundCornersCheck.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fxRoundCornersCheck.setForeground(Color.LIGHT_GRAY);
            fxRoundCornersCheck.setBackground(new Color(44, 47, 51));
            fxRoundCornersCheck.setFocusPainted(false);
            fxRoundCornersCheck.addActionListener(e -> onFormatChanged());

            fxCornerRadiusSpinner = new JSpinner(new SpinnerNumberModel(60, 5, 500, 5));
            fxCornerRadiusSpinner.setPreferredSize(new Dimension(50, 24));
            fxCornerRadiusSpinner.setToolTipText("Corner radius (pixels at 1920p)");
            fxCornerRadiusSpinner.addChangeListener(e -> onFormatChanged());

            fxVignetteCheck = new JCheckBox("Vignette", false);
            fxVignetteCheck.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fxVignetteCheck.setForeground(Color.LIGHT_GRAY);
            fxVignetteCheck.setBackground(new Color(44, 47, 51));
            fxVignetteCheck.setFocusPainted(false);
            fxVignetteCheck.addActionListener(e -> onFormatChanged());

            fxVignetteSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 100, 5));
            fxVignetteSpinner.setPreferredSize(new Dimension(45, 24));
            fxVignetteSpinner.setToolTipText("Vignette intensity %");
            fxVignetteSpinner.addChangeListener(e -> onFormatChanged());

            fxSepiaCheck = new JCheckBox("Sepia", false);
            fxSepiaCheck.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fxSepiaCheck.setForeground(Color.LIGHT_GRAY);
            fxSepiaCheck.setBackground(new Color(44, 47, 51));
            fxSepiaCheck.setFocusPainted(false);
            fxSepiaCheck.addActionListener(e -> onFormatChanged());

            fxSepiaSpinner = new JSpinner(new SpinnerNumberModel(100, 1, 100, 5));
            fxSepiaSpinner.setPreferredSize(new Dimension(45, 24));
            fxSepiaSpinner.setToolTipText("Sepia intensity %");
            fxSepiaSpinner.addChangeListener(e -> onFormatChanged());

            fxGrainCheck = new JCheckBox("Grain", false);
            fxGrainCheck.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fxGrainCheck.setForeground(Color.LIGHT_GRAY);
            fxGrainCheck.setBackground(new Color(44, 47, 51));
            fxGrainCheck.setFocusPainted(false);
            fxGrainCheck.addActionListener(e -> onFormatChanged());

            fxGrainSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 100, 5));
            fxGrainSpinner.setPreferredSize(new Dimension(45, 24));
            fxGrainSpinner.setToolTipText("Grain intensity %");
            fxGrainSpinner.addChangeListener(e -> onFormatChanged());

            fxWaterRippleCheck = new JCheckBox("Ripple", false);
            fxWaterRippleCheck.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fxWaterRippleCheck.setForeground(Color.LIGHT_GRAY);
            fxWaterRippleCheck.setBackground(new Color(44, 47, 51));
            fxWaterRippleCheck.setFocusPainted(false);
            fxWaterRippleCheck.addActionListener(e -> onFormatChanged());

            fxWaterRippleSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 100, 5));
            fxWaterRippleSpinner.setPreferredSize(new Dimension(45, 24));
            fxWaterRippleSpinner.setToolTipText("Water ripple intensity %");
            fxWaterRippleSpinner.addChangeListener(e -> onFormatChanged());

            fxGlitchCheck = new JCheckBox("Glitch", false);
            fxGlitchCheck.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fxGlitchCheck.setForeground(Color.LIGHT_GRAY);
            fxGlitchCheck.setBackground(new Color(44, 47, 51));
            fxGlitchCheck.setFocusPainted(false);
            fxGlitchCheck.addActionListener(e -> onFormatChanged());

            fxGlitchSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 100, 5));
            fxGlitchSpinner.setPreferredSize(new Dimension(45, 24));
            fxGlitchSpinner.setToolTipText("Glitch intensity %");
            fxGlitchSpinner.addChangeListener(e -> onFormatChanged());

            fxShakeCheck = new JCheckBox("Shake", false);
            fxShakeCheck.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fxShakeCheck.setForeground(Color.LIGHT_GRAY);
            fxShakeCheck.setBackground(new Color(44, 47, 51));
            fxShakeCheck.setFocusPainted(false);
            fxShakeCheck.addActionListener(e -> onFormatChanged());

            fxShakeSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 100, 5));
            fxShakeSpinner.setPreferredSize(new Dimension(45, 24));
            fxShakeSpinner.setToolTipText("Shake intensity %");
            fxShakeSpinner.addChangeListener(e -> onFormatChanged());

            // Row 5a: Corners + Vignette + Sepia
            toolbar5a.add(fxLabel);
            toolbar5a.add(fxRoundCornersCheck);
            toolbar5a.add(fxCornerRadiusSpinner);
            toolbar5a.add(fxVignetteCheck);
            toolbar5a.add(fxVignetteSpinner);
            toolbar5a.add(fxSepiaCheck);
            toolbar5a.add(fxSepiaSpinner);

            // Row 5b: Grain + Ripple
            toolbar5b.add(styledLabel("      "));
            toolbar5b.add(fxGrainCheck);
            toolbar5b.add(fxGrainSpinner);
            toolbar5b.add(fxWaterRippleCheck);
            toolbar5b.add(fxWaterRippleSpinner);

            // Row 5c: Glitch + Shake
            toolbar5c.add(styledLabel("      "));
            toolbar5c.add(fxGlitchCheck);
            toolbar5c.add(fxGlitchSpinner);
            toolbar5c.add(fxShakeCheck);
            toolbar5c.add(fxShakeSpinner);

            // ===== Toolbar Row 6: Image Shape (2 rows) =====
            JPanel toolbar6a = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
            toolbar6a.setBackground(new Color(110, 55, 60));
            JPanel toolbar6b = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
            toolbar6b.setBackground(new Color(110, 55, 60));

            JLabel overlayLabel = styledLabel("\uD83D\uDDBC Image Shape:");
            overlayLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
            overlayLabel.setForeground(new Color(100, 200, 150));

            overlayCheckBox = new JCheckBox("Enable", false);
            overlayCheckBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            overlayCheckBox.setForeground(Color.LIGHT_GRAY);
            overlayCheckBox.setBackground(new Color(44, 47, 51));
            overlayCheckBox.setFocusPainted(false);
            overlayCheckBox.setToolTipText("Display image as a shaped floating picture on blurred background");
            overlayCheckBox.addActionListener(e -> onFormatChanged());

            overlayShapeCombo = new JComboBox<>(new String[]{"Rectangular", "Circular"});
            overlayShapeCombo.setPreferredSize(new Dimension(95, 24));
            overlayShapeCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            overlayShapeCombo.addActionListener(e -> onFormatChanged());

            overlayXSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
            overlayXSpinner.setPreferredSize(new Dimension(45, 24));
            overlayXSpinner.setToolTipText("Image X position %");
            overlayXSpinner.addChangeListener(e -> onFormatChanged());

            overlayYSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
            overlayYSpinner.setPreferredSize(new Dimension(45, 24));
            overlayYSpinner.setToolTipText("Image Y position %");
            overlayYSpinner.addChangeListener(e -> onFormatChanged());

            overlayBgCombo = new JComboBox<>(new String[]{"Blur", "Color"});
            overlayBgCombo.setPreferredSize(new Dimension(65, 24));
            overlayBgCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            overlayBgCombo.addActionListener(e -> onFormatChanged());

            overlayBgColorBtn = new JButton("\u25A0");
            overlayBgColorBtn.setForeground(overlayBgColor);
            overlayBgColorBtn.setFont(new Font("Segoe UI", Font.PLAIN, 18));
            overlayBgColorBtn.setPreferredSize(new Dimension(36, 24));
            overlayBgColorBtn.setFocusPainted(false);
            overlayBgColorBtn.setToolTipText("Background color");
            overlayBgColorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(panel, "Background Color", overlayBgColor);
                if (c != null) {
                    overlayBgColor = c;
                    overlayBgColorBtn.setForeground(c);
                    onFormatChanged();
                }
            });

            overlaySizeSpinner = new JSpinner(new SpinnerNumberModel(50, 5, 100, 1));
            overlaySizeSpinner.setPreferredSize(new Dimension(45, 24));
            overlaySizeSpinner.setToolTipText("Image size % of frame");
            overlaySizeSpinner.addChangeListener(e -> onFormatChanged());

            // Row 6a: Enable + Shape + BG
            toolbar6a.add(overlayLabel);
            toolbar6a.add(overlayCheckBox);
            toolbar6a.add(styledLabel("Shape:"));
            toolbar6a.add(overlayShapeCombo);
            toolbar6a.add(styledLabel("BG:"));
            toolbar6a.add(overlayBgCombo);
            toolbar6a.add(overlayBgColorBtn);

            // Row 6b: Position + Size
            toolbar6b.add(styledLabel("      "));
            toolbar6b.add(styledLabel("X%:"));
            toolbar6b.add(overlayXSpinner);
            toolbar6b.add(styledLabel("Y%:"));
            toolbar6b.add(overlayYSpinner);
            toolbar6b.add(styledLabel("Size%:"));
            toolbar6b.add(overlaySizeSpinner);

            // ===== Toolbar Row 7: Slide Audio =====
            JPanel toolbar7 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
            toolbar7.setBackground(new Color(100, 85, 55));

            JLabel audioLabel = styledLabel("\uD83D\uDD0A Audio:");
            audioLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
            audioLabel.setForeground(new Color(100, 180, 220));

            audioBtn = new JButton("Browse...");
            audioBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            audioBtn.setPreferredSize(new Dimension(75, 24));
            audioBtn.setFocusPainted(false);
            audioBtn.setToolTipText("Attach audio to this slide (duration overrides global slide duration)");
            audioBtn.addActionListener(e -> browseSlideAudio());

            audioFileLabel = new JLabel("No audio");
            audioFileLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            audioFileLabel.setForeground(Color.GRAY);
            audioFileLabel.setPreferredSize(new Dimension(150, 20));

            audioDurationLabel = new JLabel("");
            audioDurationLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            audioDurationLabel.setForeground(new Color(180, 220, 180));

            audioClearBtn = new JButton("\u2716");
            audioClearBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            audioClearBtn.setPreferredSize(new Dimension(36, 24));
            audioClearBtn.setFocusPainted(false);
            audioClearBtn.setToolTipText("Remove audio from this slide");
            audioClearBtn.setVisible(false);
            audioClearBtn.addActionListener(e -> clearSlideAudio());

            toolbar7.add(audioLabel);
            toolbar7.add(audioBtn);
            toolbar7.add(audioFileLabel);
            toolbar7.add(audioDurationLabel);
            toolbar7.add(audioClearBtn);

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
            toolbarsPanel.add(toolbar1b);
            toolbarsPanel.add(createToolbarSeparator());
            toolbarsPanel.add(toolbar2);
            toolbarsPanel.add(createToolbarSeparator());
            toolbarsPanel.add(toolbar3);
            toolbarsPanel.add(createToolbarSeparator());
            toolbarsPanel.add(toolbar4a);
            toolbarsPanel.add(toolbar4b);
            toolbarsPanel.add(toolbar4c);
            toolbarsPanel.add(createToolbarSeparator());
            toolbarsPanel.add(toolbar5a);
            toolbarsPanel.add(toolbar5b);
            toolbarsPanel.add(toolbar5c);
            toolbarsPanel.add(createToolbarSeparator());
            toolbarsPanel.add(toolbar6a);
            toolbarsPanel.add(toolbar6b);
            toolbarsPanel.add(createToolbarSeparator());
            toolbarsPanel.add(toolbar7);

            rightPanel.add(toolbarsPanel, BorderLayout.NORTH);
            rightPanel.add(textScroll, BorderLayout.CENTER);

            JPanel westPanel = new JPanel(new BorderLayout(4, 0));
            westPanel.setBackground(new Color(44, 47, 51));
            centerPanel.setPreferredSize(new Dimension(170, 0));
            westPanel.add(leftCtrl, BorderLayout.WEST);
            westPanel.add(centerPanel, BorderLayout.CENTER);

            panel.add(westPanel, BorderLayout.WEST);
            panel.add(rightPanel, BorderLayout.CENTER);
        }

        // ===== Slide text dropdown helpers =====

        private void saveCurrentSlideTextToItem() {
            if (currentSlideTextIndex < 0 || currentSlideTextIndex >= slideTextItems.size()) return;
            int fontStyle = Font.PLAIN;
            if (slideTextBoldBtn.isSelected()) fontStyle |= Font.BOLD;
            if (slideTextItalicBtn.isSelected()) fontStyle |= Font.ITALIC;
            int alignment;
            switch (slideTextAlignCombo.getSelectedIndex()) {
                case 1: alignment = SwingConstants.LEFT; break;
                case 2: alignment = SwingConstants.RIGHT; break;
                default: alignment = SwingConstants.CENTER; break;
            }
            slideTextItems.set(currentSlideTextIndex, new SlideTextData(
                    slideTextCheckBox.isSelected(), slideTextArea.getText(),
                    (String) slideTextFontCombo.getSelectedItem(), (int) slideTextSizeSpinner.getValue(),
                    fontStyle, slideTextColor,
                    (int) slideTextXSpinner.getValue(), (int) slideTextYSpinner.getValue(),
                    (int) slideTextBgSpinner.getValue(), slideTextBgColor,
                    slideTextJustifyCheck.isSelected(), (int) slideTextWidthSpinner.getValue(),
                    (int) slideTextShiftXSpinner.getValue(), alignment,
                    (String) slideTextEffectCombo.getSelectedItem(),
                    (int) slideTextEffectIntensitySpinner.getValue()));
        }

        private void loadSlideTextFromItem(int index) {
            if (index < 0 || index >= slideTextItems.size()) return;
            isLoadingSlideText = true;
            try {
                SlideTextData item = slideTextItems.get(index);
                slideTextCheckBox.setSelected(item.show);
                slideTextArea.setText(item.text);
                slideTextFontCombo.setSelectedItem(item.fontName);
                slideTextSizeSpinner.setValue(item.fontSize);
                slideTextBoldBtn.setSelected((item.fontStyle & Font.BOLD) != 0);
                slideTextItalicBtn.setSelected((item.fontStyle & Font.ITALIC) != 0);
                slideTextColor = item.color;
                slideTextColorBtn.setForeground(item.color);
                slideTextXSpinner.setValue(item.x);
                slideTextYSpinner.setValue(item.y);
                slideTextBgSpinner.setValue(item.bgOpacity);
                slideTextBgColor = item.bgColor;
                slideTextBgColorBtn.setForeground(item.bgColor);
                slideTextJustifyCheck.setSelected(item.justify);
                slideTextWidthSpinner.setValue(item.widthPct);
                slideTextShiftXSpinner.setValue(item.shiftX);
                switch (item.alignment) {
                    case SwingConstants.LEFT: slideTextAlignCombo.setSelectedIndex(1); break;
                    case SwingConstants.RIGHT: slideTextAlignCombo.setSelectedIndex(2); break;
                    default: slideTextAlignCombo.setSelectedIndex(0); break;
                }
                slideTextEffectCombo.setSelectedItem(item.textEffect);
                slideTextEffectIntensitySpinner.setValue(item.textEffectIntensity);
            } finally {
                isLoadingSlideText = false;
            }
        }

        private void rebuildSlideTextSelector() {
            slideTextSelector.removeAllItems();
            for (int i = 0; i < slideTextItems.size(); i++) {
                slideTextSelector.addItem("Text " + (i + 1));
            }
        }

        List<SlideTextData> getSlideTextDataList() {
            saveCurrentSlideTextToItem();
            return new ArrayList<>(slideTextItems);
        }

        List<SlideTextData> getSlideTextFormats() {
            return getSlideTextDataList();
        }

        void applySlideTextFormats(List<SlideTextData> formats) {
            if (formats == null || formats.isEmpty()) return;
            while (slideTextItems.size() < formats.size()) {
                slideTextItems.add(new SlideTextData(false, "", loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI",
                        40, Font.PLAIN, Color.YELLOW, 50, 50, 0, Color.BLACK, false, 100, 0, SwingConstants.CENTER));
            }
            while (slideTextItems.size() > formats.size()) {
                slideTextItems.remove(slideTextItems.size() - 1);
            }
            for (int i = 0; i < formats.size(); i++) {
                SlideTextData fmt = formats.get(i);
                String existingText = slideTextItems.get(i).text;
                slideTextItems.set(i, new SlideTextData(fmt.show, existingText, fmt.fontName, fmt.fontSize,
                        fmt.fontStyle, fmt.color, fmt.x, fmt.y, fmt.bgOpacity,
                        fmt.bgColor, fmt.justify, fmt.widthPct, fmt.shiftX, fmt.alignment,
                        fmt.textEffect, fmt.textEffectIntensity));
            }
            if (currentSlideTextIndex >= slideTextItems.size()) {
                currentSlideTextIndex = 0;
            }
            isLoadingSlideText = true;
            try {
                rebuildSlideTextSelector();
                slideTextSelector.setSelectedIndex(currentSlideTextIndex);
            } finally {
                isLoadingSlideText = false;
            }
            loadSlideTextFromItem(currentSlideTextIndex);
            schedulePreview();
        }

        void setSlideText(String text) {
            if (!slideTextItems.isEmpty()) {
                SlideTextData old = slideTextItems.get(0);
                slideTextItems.set(0, new SlideTextData(true, text, old.fontName, old.fontSize,
                        old.fontStyle, old.color, old.x, old.y, old.bgOpacity,
                        old.bgColor, old.justify, old.widthPct, old.shiftX, old.alignment,
                        old.textEffect, old.textEffectIntensity));
                if (currentSlideTextIndex == 0) {
                    loadSlideTextFromItem(0);
                }
            }
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

        private static JPanel createToolbarSeparator() {
            JPanel sep = new JPanel();
            sep.setBackground(new Color(70, 73, 78));
            sep.setPreferredSize(new Dimension(Integer.MAX_VALUE, 1));
            sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            sep.setMinimumSize(new Dimension(0, 1));
            return sep;
        }

        private JLabel styledLabel(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(Color.LIGHT_GRAY);
            l.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            return l;
        }

        void schedulePreview() {
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
                             int slideNumberSize, Color slideNumberColor,
                             List<SlideTextData> slideTextFormats,
                             boolean fxRoundCorners, int fxCornerRadius,
                             boolean fxVignetteOn, int fxVignetteVal,
                             boolean fxSepiaOn, int fxSepiaVal,
                             boolean fxGrainOn, int fxGrainVal,
                             boolean fxWaterRippleOn, int fxWaterRippleVal,
                             boolean fxGlitchOn, int fxGlitchVal,
                             boolean fxShakeOn, int fxShakeVal,
                             String overlayShape, String overlayBgMode, Color overlayBgColor,
                             int overlayX, int overlayY, int overlaySize,
                             boolean textJustify, int textWidthPct,
                             String highlightText, Color highlightColor,
                             int textShiftX) {
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

            applySlideTextFormats(slideTextFormats);

            fxRoundCornersCheck.setSelected(fxRoundCorners);
            fxCornerRadiusSpinner.setValue(fxCornerRadius);
            fxVignetteCheck.setSelected(fxVignetteOn);
            fxVignetteSpinner.setValue(fxVignetteVal);
            fxSepiaCheck.setSelected(fxSepiaOn);
            fxSepiaSpinner.setValue(fxSepiaVal);
            fxGrainCheck.setSelected(fxGrainOn);
            fxGrainSpinner.setValue(fxGrainVal);
            fxWaterRippleCheck.setSelected(fxWaterRippleOn);
            fxWaterRippleSpinner.setValue(fxWaterRippleVal);
            fxGlitchCheck.setSelected(fxGlitchOn);
            fxGlitchSpinner.setValue(fxGlitchVal);
            fxShakeCheck.setSelected(fxShakeOn);
            fxShakeSpinner.setValue(fxShakeVal);

            overlayShapeCombo.setSelectedItem(overlayShape);
            overlayBgCombo.setSelectedItem(overlayBgMode);
            this.overlayBgColor = overlayBgColor;
            overlayBgColorBtn.setForeground(overlayBgColor);
            overlayXSpinner.setValue(overlayX);
            overlayYSpinner.setValue(overlayY);
            overlaySizeSpinner.setValue(overlaySize);

            justifyCheckBox.setSelected(textJustify);
            textWidthSpinner.setValue(textWidthPct);
            highlightField.setText(highlightText);
            this.highlightColor = highlightColor;
            highlightColorBtn.setForeground(highlightColor);
            textShiftXSpinner.setValue(textShiftX);

            updateTextAreaStyle();
            schedulePreview();
        }

        void markAsTitleGrid() {
            isTitleGridSlide = true;
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(60, 160, 200), 2, true),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            numberLabel.setForeground(new Color(60, 160, 200));
            if (titleBgImage == null && loadedImage != null) {
                imagePreview.setText("Drop image here for background");
            }
        }

        BufferedImage regenerateGridImage(int w, int h) {
            if (gridSourceImages == null || gridSourceImages.isEmpty()) return loadedImage;
            return generateGridImage(gridSourceImages, w, h, gridLayoutIndex);
        }

        /**
         * Returns the image to use for the title grid frame.
         * If a background image is set, returns it (display mode effect
         * will be applied by renderFrame). Otherwise returns the grid image.
         */
        BufferedImage composeTitleGridFrame(int w, int h) {
            if (titleBgImage != null) {
                return titleBgImage;
            }
            return regenerateGridImage(w, h);
        }

        private void updateLivePreview() {
            if (loadedImage == null) {
                livePreviewLabel.setIcon(null);
                livePreviewLabel.setText("Live Preview (add image first)");
                return;
            }
            BufferedImage frameImage = loadedImage;
            if (isTitleGridSlide && gridSourceImages != null) {
                frameImage = composeTitleGridFrame(getPreviewWidth(), getPreviewHeight());
            }
            BufferedImage preview = renderFrame(
                    frameImage, textArea.getText(),
                    getSelectedFont(), getFontSize(), getFontStyle(),
                    getFontColor(), getTextAlignment(), isShowPin(),
                    getPreviewWidth(), getPreviewHeight(),
                    (isTitleGridSlide && titleBgImage == null) ? "Direct" : getDisplayMode(), getSubtitleY(),
                    getSubtitleBgOpacity(),
                    isShowSlideNumber(), getSlideNumberText(), getSlideNumberFontName(),
                    getSlideNumberX(), getSlideNumberY(),
                    getSlideNumberSize(), getSlideNumberColor(),
                    getSlideTextDataList(),
                    isFxRoundCorners(), getFxCornerRadius(),
                    getFxVignette(), getFxSepia(), getFxGrain(),
                    getFxWaterRipple(), getFxGlitch(), getFxShake(),
                    isOverlayEnabled(),
                    getOverlayShape(), getOverlayBgMode(), getOverlayBgColor(),
                    getOverlayX(), getOverlayY(), getOverlaySize(), 0,
                    isTextJustify(), getTextWidthPct(),
                    getHighlightText(), getHighlightColor(),
                    getTextShiftX());

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
                BufferedImage img = loadImageFile(file);
                if (img == null) {
                    JOptionPane.showMessageDialog(panel, "Cannot read: " + file.getName(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (isTitleGridSlide) {
                    titleBgImage = img;
                    updateImagePreviewThumb("BG: " + file.getName());
                } else {
                    loadedImage = img;
                    updateImagePreviewThumb(file.getName());
                }
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
            BufferedImage thumbSource = (isTitleGridSlide && titleBgImage != null) ? titleBgImage : loadedImage;
            if (thumbSource == null) return;
            int pw = imagePreview.getWidth() - 16;
            int ph = imagePreview.getHeight() - 16;
            if (pw < 50) pw = 240;
            if (ph < 50) ph = 130;
            double sc = Math.min((double) pw / thumbSource.getWidth(), (double) ph / thumbSource.getHeight());
            Image thumb = thumbSource.getScaledInstance(
                    Math.max(1, (int) (thumbSource.getWidth() * sc)),
                    Math.max(1, (int) (thumbSource.getHeight() * sc)),
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


        boolean isFxRoundCorners() { return fxRoundCornersCheck.isSelected(); }
        int getFxCornerRadius() { return (int) fxCornerRadiusSpinner.getValue(); }
        int getFxVignette() { return fxVignetteCheck.isSelected() ? (int) fxVignetteSpinner.getValue() : 0; }
        int getFxSepia() { return fxSepiaCheck.isSelected() ? (int) fxSepiaSpinner.getValue() : 0; }
        int getFxGrain() { return fxGrainCheck.isSelected() ? (int) fxGrainSpinner.getValue() : 0; }
        int getFxWaterRipple() { return fxWaterRippleCheck.isSelected() ? (int) fxWaterRippleSpinner.getValue() : 0; }
        int getFxGlitch() { return fxGlitchCheck.isSelected() ? (int) fxGlitchSpinner.getValue() : 0; }
        int getFxShake() { return fxShakeCheck.isSelected() ? (int) fxShakeSpinner.getValue() : 0; }
        int getFxVignetteRaw() { return (int) fxVignetteSpinner.getValue(); }
        int getFxSepiaRaw() { return (int) fxSepiaSpinner.getValue(); }
        int getFxGrainRaw() { return (int) fxGrainSpinner.getValue(); }
        int getFxWaterRippleRaw() { return (int) fxWaterRippleSpinner.getValue(); }
        int getFxGlitchRaw() { return (int) fxGlitchSpinner.getValue(); }
        int getFxShakeRaw() { return (int) fxShakeSpinner.getValue(); }
        boolean isOverlayEnabled() { return overlayCheckBox.isSelected(); }
        String getOverlayShape() { return (String) overlayShapeCombo.getSelectedItem(); }
        String getOverlayBgMode() { return (String) overlayBgCombo.getSelectedItem(); }
        Color getOverlayBgColor() { return overlayBgColor; }
        int getOverlayX() { return (int) overlayXSpinner.getValue(); }
        int getOverlayY() { return (int) overlayYSpinner.getValue(); }
        int getOverlaySize() { return (int) overlaySizeSpinner.getValue(); }
        boolean isTextJustify() { return justifyCheckBox.isSelected(); }
        int getTextWidthPct() { return (int) textWidthSpinner.getValue(); }
        String getHighlightText() { return highlightField.getText(); }
        Color getHighlightColor() { return highlightColor; }
        int getTextShiftX() { return (int) textShiftXSpinner.getValue(); }

        File getSlideAudioFile() { return slideAudioFile; }
        int getSlideAudioDurationMs() { return slideAudioDurationMs; }

        private void browseSlideAudio() {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "Audio Files", "mp3", "wav", "aac", "ogg", "m4a", "flac", "wma"));
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                int durationMs = probeAudioDurationMs(file);
                if (durationMs <= 0) {
                    JOptionPane.showMessageDialog(panel,
                            "Could not read audio duration.\nMake sure ffprobe is installed.",
                            "Audio Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                slideAudioFile = file;
                slideAudioDurationMs = durationMs;
                audioFileLabel.setText(file.getName());
                audioFileLabel.setForeground(Color.WHITE);
                audioDurationLabel.setText(String.format("(%d.%ds)",
                        durationMs / 1000, (durationMs % 1000) / 100));
                audioClearBtn.setVisible(true);
            }
        }

        private void clearSlideAudio() {
            slideAudioFile = null;
            slideAudioDurationMs = -1;
            audioFileLabel.setText("No audio");
            audioFileLabel.setForeground(Color.GRAY);
            audioDurationLabel.setText("");
            audioClearBtn.setVisible(false);
        }
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new GifSlideShowApp().setVisible(true));
    }
}
