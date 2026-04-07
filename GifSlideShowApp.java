
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

    // Presets
    private static final File PRESETS_DIR = new File(new File(".").getAbsoluteFile().getParentFile(), "presets");
    private JComboBox<String> presetCombo;

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
        setSize(1100, 920);
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

        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.setBackground(new Color(30, 30, 30));

        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 3));
        topRow.setBackground(new Color(30, 30, 30));

        JPanel botRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 3));
        botRow.setBackground(new Color(30, 30, 30));

        JButton addBtn = createStyledButton("+ Add Slide", new Color(29, 161, 242));
        addBtn.addActionListener(e -> addSlideRow());

        JButton bulkBtn = createStyledButton("Bulk Images", new Color(160, 100, 220));
        bulkBtn.addActionListener(e -> bulkImport());

        JButton bulkTextBtn = createStyledButton("Bulk Text", new Color(220, 160, 50));
        bulkTextBtn.addActionListener(e -> bulkImportText());

        JButton dictImportBtn = createStyledButton("Dict Import", new Color(50, 180, 160));
        dictImportBtn.setToolTipText("Import CSV/TSV: each row=slide, each column=slide text (A→Text1, B→Text2...)");
        dictImportBtn.addActionListener(e -> dictionaryImport());

        JButton titleGridBtn = createStyledButton("Title Grid", new Color(60, 160, 200));
        titleGridBtn.addActionListener(e -> addTitleGridSlide());

        JButton gifBtn = createStyledButton("Create GIF", new Color(0, 186, 124));
        gifBtn.addActionListener(e -> createGif());

        JButton mp4Btn = createStyledButton("Create MP4", new Color(220, 60, 60));
        mp4Btn.addActionListener(e -> createMp4());

        // Orientation selector
        JLabel orientLabel = new JLabel("Orientation:");
        orientLabel.setForeground(Color.LIGHT_GRAY);
        orientLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        orientationCombo = new JComboBox<>(new String[]{
                "Landscape (1920×1080)", "Portrait (1080×1920)"});
        orientationCombo.setPreferredSize(new Dimension(150, 26));
        orientationCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
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

        topRow.add(orientLabel);
        topRow.add(orientationCombo);
        topRow.add(addBtn);
        topRow.add(bulkBtn);
        topRow.add(bulkTextBtn);
        topRow.add(dictImportBtn);
        topRow.add(titleGridBtn);
        topRow.add(gifBtn);
        topRow.add(mp4Btn);

        // Preset controls
        JLabel presetLabel = new JLabel("Preset:");
        presetLabel.setForeground(Color.LIGHT_GRAY);
        presetLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        presetCombo = new JComboBox<>();
        presetCombo.setPreferredSize(new Dimension(140, 26));
        presetCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        refreshPresetCombo();

        JButton presetSaveBtn = createStyledButton("Save Preset", new Color(80, 140, 60));
        presetSaveBtn.addActionListener(e -> savePreset());

        JButton presetLoadBtn = createStyledButton("Load Preset", new Color(50, 120, 170));
        presetLoadBtn.addActionListener(e -> loadPreset());

        JButton presetDeleteBtn = createStyledButton("Delete Preset", new Color(160, 60, 60));
        presetDeleteBtn.addActionListener(e -> deletePreset());

        botRow.add(presetLabel);
        botRow.add(presetCombo);
        botRow.add(presetSaveBtn);
        botRow.add(presetLoadBtn);
        botRow.add(presetDeleteBtn);

        bottomPanel.add(topRow);
        bottomPanel.add(botRow);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
        addSlideRow();
    }

    // ==================== Presets ====================

    private void refreshPresetCombo() {
        presetCombo.removeAllItems();
        if (!PRESETS_DIR.isDirectory()) return;
        File[] files = PRESETS_DIR.listFiles((d, n) -> n.endsWith(".preset"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File f : files) {
            presetCombo.addItem(f.getName().replace(".preset", ""));
        }
    }

    private void savePreset() {
        SlideRow source = null;
        for (SlideRow row : slideRows) {
            if (!row.isTitleGridSlide) { source = row; break; }
        }
        if (source == null) {
            JOptionPane.showMessageDialog(this, "No slide available to save settings from.", "Save Preset", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String name = JOptionPane.showInputDialog(this, "Enter preset name:", "Save Preset", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) return;
        name = name.trim().replaceAll("[^a-zA-Z0-9 _\\-]", "");
        if (name.isEmpty()) return;

        PRESETS_DIR.mkdirs();
        Properties props = new Properties();

        // Text formatting
        props.setProperty("fontName", source.getSelectedFont());
        props.setProperty("fontSize", String.valueOf(source.getFontSize()));
        props.setProperty("fontStyle", String.valueOf(source.getFontStyle()));
        props.setProperty("fontColor", colorToHex(source.getFontColor()));
        props.setProperty("alignment", String.valueOf(source.getTextAlignment()));
        props.setProperty("showPin", String.valueOf(source.isShowPin()));
        props.setProperty("textJustify", String.valueOf(source.isTextJustify()));
        props.setProperty("textWidthPct", String.valueOf(source.getTextWidthPct()));
        props.setProperty("textShiftX", String.valueOf(source.getTextShiftX()));
        props.setProperty("highlightText", source.getHighlightText());
        props.setProperty("highlightColor", colorToHex(source.getHighlightColor()));

        // Display
        props.setProperty("displayMode", source.getDisplayMode());
        props.setProperty("subtitleY", String.valueOf(source.getSubtitleY()));
        props.setProperty("subtitleBgOpacity", String.valueOf(source.getSubtitleBgOpacity()));

        // Slide number
        props.setProperty("showSlideNumber", String.valueOf(source.isShowSlideNumber()));
        props.setProperty("slideNumberFontName", source.getSlideNumberFontName());
        props.setProperty("slideNumberX", String.valueOf(source.getSlideNumberX()));
        props.setProperty("slideNumberY", String.valueOf(source.getSlideNumberY()));
        props.setProperty("slideNumberSize", String.valueOf(source.getSlideNumberSize()));
        props.setProperty("slideNumberColor", colorToHex(source.getSlideNumberColor()));

        // Effects
        props.setProperty("fxRoundCorners", String.valueOf(source.isFxRoundCorners()));
        props.setProperty("fxCornerRadius", String.valueOf(source.getFxCornerRadius()));
        props.setProperty("fxVignetteOn", String.valueOf(source.fxVignetteCheck.isSelected()));
        props.setProperty("fxVignetteVal", String.valueOf(source.getFxVignetteRaw()));
        props.setProperty("fxSepiaOn", String.valueOf(source.fxSepiaCheck.isSelected()));
        props.setProperty("fxSepiaVal", String.valueOf(source.getFxSepiaRaw()));
        props.setProperty("fxGrainOn", String.valueOf(source.fxGrainCheck.isSelected()));
        props.setProperty("fxGrainVal", String.valueOf(source.getFxGrainRaw()));
        props.setProperty("fxWaterRippleOn", String.valueOf(source.fxWaterRippleCheck.isSelected()));
        props.setProperty("fxWaterRippleVal", String.valueOf(source.getFxWaterRippleRaw()));
        props.setProperty("fxGlitchOn", String.valueOf(source.fxGlitchCheck.isSelected()));
        props.setProperty("fxGlitchVal", String.valueOf(source.getFxGlitchRaw()));
        props.setProperty("fxShakeOn", String.valueOf(source.fxShakeCheck.isSelected()));
        props.setProperty("fxShakeVal", String.valueOf(source.getFxShakeRaw()));
        props.setProperty("fxScanlineOn", String.valueOf(source.fxScanlineCheck.isSelected()));
        props.setProperty("fxScanlineVal", String.valueOf(source.getFxScanlineRaw()));
        props.setProperty("fxRaisedOn", String.valueOf(source.fxRaisedCheck.isSelected()));
        props.setProperty("fxRaisedVal", String.valueOf(source.getFxRaisedRaw()));

        // Overlay
        props.setProperty("overlayEnabled", String.valueOf(source.isOverlayEnabled()));
        props.setProperty("overlayShape", source.getOverlayShape());
        props.setProperty("overlayBgMode", source.getOverlayBgMode());
        props.setProperty("overlayBgColor", colorToHex(source.getOverlayBgColor()));
        props.setProperty("overlayX", String.valueOf(source.getOverlayX()));
        props.setProperty("overlayY", String.valueOf(source.getOverlayY()));
        props.setProperty("overlaySize", String.valueOf(source.getOverlaySize()));

        // Slide text layers
        List<SlideTextData> texts = source.getSlideTextFormats();
        props.setProperty("slideTextCount", String.valueOf(texts.size()));
        for (int i = 0; i < texts.size(); i++) {
            SlideTextData t = texts.get(i);
            String p = "slideText." + i + ".";
            props.setProperty(p + "show", String.valueOf(t.show));
            props.setProperty(p + "fontName", t.fontName);
            props.setProperty(p + "fontSize", String.valueOf(t.fontSize));
            props.setProperty(p + "fontStyle", String.valueOf(t.fontStyle));
            props.setProperty(p + "color", colorToHex(t.color));
            props.setProperty(p + "x", String.valueOf(t.x));
            props.setProperty(p + "y", String.valueOf(t.y));
            props.setProperty(p + "bgOpacity", String.valueOf(t.bgOpacity));
            props.setProperty(p + "bgColor", colorToHex(t.bgColor));
            props.setProperty(p + "justify", String.valueOf(t.justify));
            props.setProperty(p + "widthPct", String.valueOf(t.widthPct));
            props.setProperty(p + "shiftX", String.valueOf(t.shiftX));
            props.setProperty(p + "alignment", String.valueOf(t.alignment));
            props.setProperty(p + "textEffect", t.textEffect);
            props.setProperty(p + "textEffectIntensity", String.valueOf(t.textEffectIntensity));
            props.setProperty(p + "highlightText", t.highlightText);
            props.setProperty(p + "highlightColor", colorToHex(t.highlightColor));
            props.setProperty(p + "highlightStyle", t.highlightStyle);
            props.setProperty(p + "highlightTightness", String.valueOf(t.highlightTightness));
            props.setProperty(p + "underlineStyle", t.underlineStyle);
            props.setProperty(p + "underlineText", t.underlineText);
            props.setProperty(p + "boldText", t.boldText);
            props.setProperty(p + "italicText", t.italicText);
            props.setProperty(p + "colorText", t.colorText);
            props.setProperty(p + "colorTextColor", colorToHex(t.colorTextColor));
        }

        // Orientation
        props.setProperty("orientation", isPortrait() ? "Portrait" : "Landscape");

        File file = new File(PRESETS_DIR, name + ".preset");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "GifSlideShowApp Preset: " + name);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to save preset: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        refreshPresetCombo();
        presetCombo.setSelectedItem(name);
        JOptionPane.showMessageDialog(this, "Preset \"" + name + "\" saved.", "Save Preset", JOptionPane.INFORMATION_MESSAGE);
    }

    private void loadPreset() {
        String name = (String) presetCombo.getSelectedItem();
        if (name == null || name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No preset selected.", "Load Preset", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File file = new File(PRESETS_DIR, name + ".preset");
        if (!file.exists()) {
            JOptionPane.showMessageDialog(this, "Preset file not found.", "Load Preset", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to load preset: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Parse values
        String fontName = props.getProperty("fontName", "Segoe UI");
        int fontSize = Integer.parseInt(props.getProperty("fontSize", "28"));
        int fontStyle = Integer.parseInt(props.getProperty("fontStyle", "0"));
        Color fontColor = hexToColor(props.getProperty("fontColor", "#FFFFFF"));
        int alignment = Integer.parseInt(props.getProperty("alignment", String.valueOf(SwingConstants.LEFT)));
        boolean showPin = Boolean.parseBoolean(props.getProperty("showPin", "false"));
        boolean textJustify = Boolean.parseBoolean(props.getProperty("textJustify", "false"));
        int textWidthPct = Integer.parseInt(props.getProperty("textWidthPct", "90"));
        int textShiftX = Integer.parseInt(props.getProperty("textShiftX", "0"));
        String highlightText = props.getProperty("highlightText", "");
        Color highlightColor = hexToColor(props.getProperty("highlightColor", "#FFFF00B4"));

        String displayMode = props.getProperty("displayMode", "Blur-Fit");
        int subtitleY = Integer.parseInt(props.getProperty("subtitleY", "5"));
        int subtitleBgOpacity = Integer.parseInt(props.getProperty("subtitleBgOpacity", "78"));

        boolean showSlideNumber = Boolean.parseBoolean(props.getProperty("showSlideNumber", "false"));
        String slideNumberFontName = props.getProperty("slideNumberFontName", loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI");
        int slideNumberX = Integer.parseInt(props.getProperty("slideNumberX", "50"));
        int slideNumberY = Integer.parseInt(props.getProperty("slideNumberY", "50"));
        int slideNumberSize = Integer.parseInt(props.getProperty("slideNumberSize", "80"));
        Color slideNumberColor = hexToColor(props.getProperty("slideNumberColor", "#FFFFFF"));

        boolean fxRoundCorners = Boolean.parseBoolean(props.getProperty("fxRoundCorners", "false"));
        int fxCornerRadius = Integer.parseInt(props.getProperty("fxCornerRadius", "40"));
        boolean fxVignetteOn = Boolean.parseBoolean(props.getProperty("fxVignetteOn", "false"));
        int fxVignetteVal = Integer.parseInt(props.getProperty("fxVignetteVal", "50"));
        boolean fxSepiaOn = Boolean.parseBoolean(props.getProperty("fxSepiaOn", "false"));
        int fxSepiaVal = Integer.parseInt(props.getProperty("fxSepiaVal", "50"));
        boolean fxGrainOn = Boolean.parseBoolean(props.getProperty("fxGrainOn", "false"));
        int fxGrainVal = Integer.parseInt(props.getProperty("fxGrainVal", "50"));
        boolean fxWaterRippleOn = Boolean.parseBoolean(props.getProperty("fxWaterRippleOn", "false"));
        int fxWaterRippleVal = Integer.parseInt(props.getProperty("fxWaterRippleVal", "50"));
        boolean fxGlitchOn = Boolean.parseBoolean(props.getProperty("fxGlitchOn", "false"));
        int fxGlitchVal = Integer.parseInt(props.getProperty("fxGlitchVal", "50"));
        boolean fxShakeOn = Boolean.parseBoolean(props.getProperty("fxShakeOn", "false"));
        int fxShakeVal = Integer.parseInt(props.getProperty("fxShakeVal", "50"));
        boolean fxScanlineOn = Boolean.parseBoolean(props.getProperty("fxScanlineOn", "false"));
        int fxScanlineVal = Integer.parseInt(props.getProperty("fxScanlineVal", "50"));
        boolean fxRaisedOn = Boolean.parseBoolean(props.getProperty("fxRaisedOn", "false"));
        int fxRaisedVal = Integer.parseInt(props.getProperty("fxRaisedVal", "50"));

        String overlayShape = props.getProperty("overlayShape", "Rectangular");
        String overlayBgMode = props.getProperty("overlayBgMode", "Blur");
        Color overlayBgColor = hexToColor(props.getProperty("overlayBgColor", "#152B2B"));
        int overlayX = Integer.parseInt(props.getProperty("overlayX", "50"));
        int overlayY = Integer.parseInt(props.getProperty("overlayY", "50"));
        int overlaySize = Integer.parseInt(props.getProperty("overlaySize", "80"));

        // Slide text layers
        int slideTextCount = Integer.parseInt(props.getProperty("slideTextCount", "1"));
        List<SlideTextData> slideTextFormats = new ArrayList<>();
        for (int i = 0; i < slideTextCount; i++) {
            String p = "slideText." + i + ".";
            slideTextFormats.add(new SlideTextData(
                    Boolean.parseBoolean(props.getProperty(p + "show", "false")),
                    "",  // text content not saved in presets
                    props.getProperty(p + "fontName", loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI"),
                    Integer.parseInt(props.getProperty(p + "fontSize", "40")),
                    Integer.parseInt(props.getProperty(p + "fontStyle", "0")),
                    hexToColor(props.getProperty(p + "color", "#FFFF00")),
                    Integer.parseInt(props.getProperty(p + "x", "50")),
                    Integer.parseInt(props.getProperty(p + "y", "50")),
                    Integer.parseInt(props.getProperty(p + "bgOpacity", "0")),
                    hexToColor(props.getProperty(p + "bgColor", "#000000")),
                    Boolean.parseBoolean(props.getProperty(p + "justify", "false")),
                    Integer.parseInt(props.getProperty(p + "widthPct", "100")),
                    Integer.parseInt(props.getProperty(p + "shiftX", "0")),
                    Integer.parseInt(props.getProperty(p + "alignment", String.valueOf(SwingConstants.CENTER))),
                    props.getProperty(p + "textEffect", "None"),
                    Integer.parseInt(props.getProperty(p + "textEffectIntensity", "50")),
                    props.getProperty(p + "highlightText", ""),
                    hexToColor(props.getProperty(p + "highlightColor", "#FF6496B4")),
                    props.getProperty(p + "highlightStyle", "Regular"),
                    Integer.parseInt(props.getProperty(p + "highlightTightness", "50")),
                    props.getProperty(p + "underlineStyle", "None"),
                    props.getProperty(p + "underlineText", ""),
                    props.getProperty(p + "boldText", ""),
                    props.getProperty(p + "italicText", ""),
                    props.getProperty(p + "colorText", ""),
                    hexToColor(props.getProperty(p + "colorTextColor", "#FF5050"))
            ));
        }

        // Orientation
        String orientation = props.getProperty("orientation", "Landscape");
        if ("Portrait".equals(orientation)) {
            orientationCombo.setSelectedItem("Portrait (1080×1920)");
        } else {
            orientationCombo.setSelectedItem("Landscape (1920×1080)");
        }

        // Apply to all non-title-grid slides
        isSyncingFormat = true;
        try {
            for (SlideRow row : slideRows) {
                if (row.isTitleGridSlide) continue;
                row.applyFormatting(fontName, fontSize, fontStyle, fontColor, alignment, showPin, displayMode,
                        subtitleY, subtitleBgOpacity,
                        showSlideNumber, slideNumberFontName, slideNumberX, slideNumberY, slideNumberSize, slideNumberColor,
                        slideTextFormats, null,
                        fxRoundCorners, fxCornerRadius,
                        fxVignetteOn, fxVignetteVal, fxSepiaOn, fxSepiaVal,
                        fxGrainOn, fxGrainVal, fxWaterRippleOn, fxWaterRippleVal,
                        fxGlitchOn, fxGlitchVal, fxShakeOn, fxShakeVal,
                        fxScanlineOn, fxScanlineVal, fxRaisedOn, fxRaisedVal,
                        overlayShape, overlayBgMode, overlayBgColor, overlayX, overlayY, overlaySize,
                        textJustify, textWidthPct, highlightText, highlightColor,
                        textShiftX,
                        null, -1, 50, 25, 30,
                        0.0);
            }
        } finally {
            isSyncingFormat = false;
        }

        JOptionPane.showMessageDialog(this, "Preset \"" + name + "\" loaded.", "Load Preset", JOptionPane.INFORMATION_MESSAGE);
    }

    private void deletePreset() {
        String name = (String) presetCombo.getSelectedItem();
        if (name == null || name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No preset selected.", "Delete Preset", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete preset \"" + name + "\"?", "Delete Preset", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        File file = new File(PRESETS_DIR, name + ".preset");
        if (file.exists()) file.delete();
        refreshPresetCombo();
    }

    private static String colorToHex(Color c) {
        if (c.getAlpha() == 255) {
            return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
        }
        return String.format("#%02X%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    private static Color hexToColor(String hex) {
        if (hex == null || hex.isEmpty()) return Color.WHITE;
        hex = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            if (hex.length() == 6) {
                return new Color(Integer.parseInt(hex, 16));
            } else if (hex.length() == 8) {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                int a = Integer.parseInt(hex.substring(6, 8), 16);
                return new Color(r, g, b, a);
            }
        } catch (NumberFormatException ignored) {}
        return Color.WHITE;
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
                null,
                false, 60, false, 50, false, 100, false, 50, false, 50, false, 50, false, 50,
                false, 50, false, 50,
                "Rectangular", "Blur", new Color(21, 32, 43), 50, 50, 20,
                false, 100, "", new Color(255, 255, 0, 180), 0,
                null, -1, 50, 25, 30,
                0.0);

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
                byte[] fileBytes = Files.readAllBytes(fc.getSelectedFile().toPath());
                int off = 0;
                String content = null;
                // Strip UTF-8 BOM if present
                if (fileBytes.length >= 3 && (fileBytes[0] & 0xFF) == 0xEF
                        && (fileBytes[1] & 0xFF) == 0xBB && (fileBytes[2] & 0xFF) == 0xBF) {
                    off = 3;
                }
                // UTF-16 LE BOM
                else if (fileBytes.length >= 2 && (fileBytes[0] & 0xFF) == 0xFF && (fileBytes[1] & 0xFF) == 0xFE) {
                    content = new String(fileBytes, java.nio.charset.Charset.forName("UTF-16LE"));
                    if (content.length() > 0 && content.charAt(0) == '\uFEFF') content = content.substring(1);
                }
                // UTF-16 BE BOM
                else if (fileBytes.length >= 2 && (fileBytes[0] & 0xFF) == 0xFE && (fileBytes[1] & 0xFF) == 0xFF) {
                    content = new String(fileBytes, java.nio.charset.Charset.forName("UTF-16BE"));
                    if (content.length() > 0 && content.charAt(0) == '\uFEFF') content = content.substring(1);
                }
                if (content == null) {
                    content = new String(fileBytes, off, fileBytes.length - off, StandardCharsets.UTF_8);
                    // If UTF-8 produced replacement chars, try Windows-1252
                    if (content.contains("\uFFFD")) {
                        content = new String(fileBytes, off, fileBytes.length - off,
                                java.nio.charset.Charset.forName("windows-1252"));
                    }
                }
                lines = Arrays.asList(content.split("\\r?\\n"));
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

    // ==================== Dictionary Import ====================

    /**
     * Splits raw CSV/TSV content into logical rows, respecting quoted fields
     * that may contain newlines (multiline cells). A quoted field like
     * "line1\nline2\nline3" is kept as a single field value.
     */
    private List<String> splitCsvRows(String content) {
        List<String> rows = new ArrayList<>();
        StringBuilder currentRow = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        // Escaped quote
                        currentRow.append('"').append('"');
                        i++;
                    } else {
                        inQuotes = false;
                        currentRow.append(c);
                    }
                } else {
                    currentRow.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    currentRow.append(c);
                } else if (c == '\r') {
                    // Skip \r, handle \n next
                } else if (c == '\n') {
                    rows.add(currentRow.toString());
                    currentRow.setLength(0);
                } else {
                    currentRow.append(c);
                }
            }
        }
        // Add last row if non-empty
        if (currentRow.length() > 0) {
            rows.add(currentRow.toString());
        }
        return rows;
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',' || c == '\t') {
                    fields.add(sb.toString().trim());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        fields.add(sb.toString().trim());
        return fields;
    }

    private void dictionaryImport() {
        // Choose source: file or clipboard
        String[] options = {"From File (CSV/TSV)", "From Clipboard / Paste"};
        int choice = JOptionPane.showOptionDialog(this,
                "Import dictionary: each row = one slide, each column = one slide text.\n"
                + "Column A → Text 1, Column B → Text 2, Column C → Text 3, etc.\n"
                + "Supports CSV (comma) and TSV (tab) delimited files.\n"
                + "Tip: For Unicode/IPA characters, save from Excel as \"CSV UTF-8\" format.\n"
                + "Optional columns: X-AXIS, Y-AXIS, TEXT-SIZE (comma-separated per text item).\n"
                + "(Title grid slides are skipped)",
                "Dictionary Import", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);

        if (choice < 0) return;

        List<String> rawLines = null;
        File importSourceDir = null; // directory of imported file, for resolving relative paths

        if (choice == 0) {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter(
                    "CSV / TSV files (*.csv, *.tsv, *.txt)", "csv", "tsv", "txt"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            importSourceDir = fc.getSelectedFile().getParentFile();

            try {
                // Try UTF-8 first (handles BOM automatically via readAllLines)
                byte[] fileBytes = Files.readAllBytes(fc.getSelectedFile().toPath());
                // Strip UTF-8 BOM if present
                int offset = 0;
                if (fileBytes.length >= 3 && (fileBytes[0] & 0xFF) == 0xEF
                        && (fileBytes[1] & 0xFF) == 0xBB && (fileBytes[2] & 0xFF) == 0xBF) {
                    offset = 3;
                }
                // Strip UTF-16 LE BOM
                else if (fileBytes.length >= 2 && (fileBytes[0] & 0xFF) == 0xFF && (fileBytes[1] & 0xFF) == 0xFE) {
                    String content = new String(fileBytes, java.nio.charset.Charset.forName("UTF-16LE"));
                    // Remove BOM character
                    if (content.length() > 0 && content.charAt(0) == '\uFEFF') content = content.substring(1);
                    rawLines = splitCsvRows(content);
                    offset = -1; // signal already decoded
                }
                // Strip UTF-16 BE BOM
                else if (fileBytes.length >= 2 && (fileBytes[0] & 0xFF) == 0xFE && (fileBytes[1] & 0xFF) == 0xFF) {
                    String content = new String(fileBytes, java.nio.charset.Charset.forName("UTF-16BE"));
                    if (content.length() > 0 && content.charAt(0) == '\uFEFF') content = content.substring(1);
                    rawLines = splitCsvRows(content);
                    offset = -1;
                }
                if (offset >= 0) {
                    String content = new String(fileBytes, offset, fileBytes.length - offset, StandardCharsets.UTF_8);
                    // Check if UTF-8 decoded cleanly (no replacement chars)
                    if (content.contains("\uFFFD")) {
                        // Try Windows-1252 (common Excel encoding) which supports accented chars
                        content = new String(fileBytes, offset, fileBytes.length - offset,
                                java.nio.charset.Charset.forName("windows-1252"));
                    }
                    rawLines = splitCsvRows(content);
                }
            } catch (IOException ex) {
                try {
                    rawLines = Files.readAllLines(fc.getSelectedFile().toPath());
                } catch (IOException ex2) {
                    JOptionPane.showMessageDialog(this,
                            "Failed to read file:\n" + ex2.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        } else {
            JTextArea pasteArea = new JTextArea(12, 50);
            pasteArea.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            pasteArea.setLineWrap(false);
            JScrollPane sp = new JScrollPane(pasteArea);
            sp.setPreferredSize(new Dimension(600, 300));

            int result = JOptionPane.showConfirmDialog(this, sp,
                    "Paste CSV/TSV data (rows = slides, columns = text items):",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;

            String text = pasteArea.getText();
            if (text.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "No data entered.", "Empty", JOptionPane.WARNING_MESSAGE);
                return;
            }
            rawLines = splitCsvRows(text);
        }

        // Remove trailing empty lines
        ArrayList<String> trimmed = new ArrayList<>(rawLines);
        while (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1).trim().isEmpty()) {
            trimmed.remove(trimmed.size() - 1);
        }

        if (trimmed.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No data rows found.", "Empty", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Ask whether first row is a header
        int headerChoice = JOptionPane.showOptionDialog(this,
                "Does the first row contain column headers?\n(If yes, it will be skipped.\nUse HL/UL/BOLD/ITALIC/COLOR headers for formatting,\nAUDIOLINK for slide audio, AUDIO1/AUDIO2/... for multi-audio per text.\nX-AXIS/Y-AXIS/TEXT-SIZE for position & size per text item.)",
                "Dictionary Import", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, new String[]{"Yes, skip first row", "No, first row is data"}, "No, first row is data");

        // Detect HL/UL/BOLD/ITALIC/COLOR/AUDIOLINK columns from header row
        int hlColIndex = -1;
        int ulColIndex = -1;
        int boldColIndex = -1;
        int italicColIndex = -1;
        int colorColIndex = -1;
        int audioLinkColIndex = -1;
        int xAxisColIndex = -1;
        int yAxisColIndex = -1;
        int textSizeColIndex = -1;
        // Multi-audio: AUDIO1, AUDIO2, AUDIOLINK1, AUDIOLINK2, etc.
        java.util.Map<Integer, Integer> audioColByTextIndex = new java.util.TreeMap<>();
        List<String> headerFields = null;

        List<String> dataLines;
        if (headerChoice == 0) {
            headerFields = parseCsvLine(trimmed.get(0));
            // Scan headers for HL, UL, BOLD, ITALIC, COLOR, and AUDIOLINK columns (case-insensitive)
            for (int c = 0; c < headerFields.size(); c++) {
                String h = headerFields.get(c).trim().toUpperCase();
                if (h.equals("HL") || h.equals("HIGHLIGHT")) {
                    hlColIndex = c;
                } else if (h.equals("UL") || h.equals("UNDERLINE")) {
                    ulColIndex = c;
                } else if (h.equals("BOLD") || h.equals("B")) {
                    boldColIndex = c;
                } else if (h.equals("ITALIC") || h.equals("I")) {
                    italicColIndex = c;
                } else if (h.equals("COLOR") || h.equals("CLR") || h.equals("COLOUR")) {
                    colorColIndex = c;
                } else if (h.equals("AUDIOLINK") || h.equals("AUDIO") || h.equals("AUDIO_LINK")) {
                    audioLinkColIndex = c;
                } else if (h.equals("X-AXIS") || h.equals("XAXIS") || h.equals("X_AXIS")) {
                    xAxisColIndex = c;
                } else if (h.equals("Y-AXIS") || h.equals("YAXIS") || h.equals("Y_AXIS")) {
                    yAxisColIndex = c;
                } else if (h.equals("TEXT-SIZE") || h.equals("TEXTSIZE") || h.equals("TEXT_SIZE") || h.equals("SIZE")) {
                    textSizeColIndex = c;
                } else if (h.matches("(AUDIOLINK|AUDIO|AUDIO_LINK)\\d+")) {
                    // Multi-audio columns: AUDIO1, AUDIO2, AUDIOLINK1, AUDIOLINK2, etc.
                    String numPart = h.replaceAll("^(AUDIOLINK|AUDIO_LINK|AUDIO)", "");
                    int textIdx = Integer.parseInt(numPart) - 1; // 1-based to 0-based
                    if (textIdx >= 0) {
                        audioColByTextIndex.put(textIdx, c);
                    }
                }
            }
            dataLines = trimmed.subList(1, trimmed.size());
        } else {
            dataLines = trimmed;
        }

        if (dataLines.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No data rows after header.", "Empty", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Build list of text column indices (excluding HL/UL)
        // Parse all rows first to determine max columns
        List<List<String>> allRows = new ArrayList<>();
        int maxCols = 0;
        for (String line : dataLines) {
            List<String> fields = parseCsvLine(line);
            allRows.add(fields);
            maxCols = Math.max(maxCols, fields.size());
        }

        // Determine which columns are text columns (not HL/UL/BOLD/ITALIC/COLOR/AUDIOLINK)
        java.util.Set<Integer> audioColIndices = new java.util.HashSet<>(audioColByTextIndex.values());
        List<Integer> textColIndices = new ArrayList<>();
        for (int c = 0; c < maxCols; c++) {
            if (c != hlColIndex && c != ulColIndex && c != boldColIndex
                    && c != italicColIndex && c != colorColIndex && c != audioLinkColIndex
                    && c != xAxisColIndex && c != yAxisColIndex && c != textSizeColIndex
                    && !audioColIndices.contains(c)) {
                textColIndices.add(c);
            }
        }

        // Collect non-title-grid slides
        List<SlideRow> targetSlides = new ArrayList<>();
        for (SlideRow row : slideRows) {
            if (!row.isTitleGridSlide) {
                targetSlides.add(row);
            }
        }

        int assigned = 0;
        List<String> missingAudioFiles = new ArrayList<>();
        for (int i = 0; i < allRows.size(); i++) {
            List<String> fields = allRows.get(i);
            SlideRow slide;
            if (i < targetSlides.size()) {
                slide = targetSlides.get(i);
            } else {
                // Create new slide
                slide = new SlideRow(slideRows.size() + 1);
                slideRows.add(slide);
                slidesPanel.add(slide.getPanel());
                slidesPanel.add(Box.createRigidArea(new Dimension(0, 10)));
                targetSlides.add(slide);
            }

            // Assign text columns to slide text items (skipping HL/UL columns)
            for (int ti = 0; ti < textColIndices.size(); ti++) {
                int col = textColIndices.get(ti);
                if (col < fields.size()) {
                    String cellText = fields.get(col);
                    if (!cellText.isEmpty()) {
                        slide.setSlideTextAt(ti, cellText);
                    }
                }
            }

            // Apply HL text from CSV if column exists
            if (hlColIndex >= 0 && hlColIndex < fields.size()) {
                String hlText = fields.get(hlColIndex).trim();
                slide.setHighlightText(hlText);
                slide.setSlideTextHighlightText(hlText);
            }

            // Apply UL text from CSV if column exists
            if (ulColIndex >= 0 && ulColIndex < fields.size()) {
                String ulText = fields.get(ulColIndex).trim();
                slide.setSlideTextUnderlineText(ulText);
            }

            // Apply Bold text from CSV if column exists
            if (boldColIndex >= 0 && boldColIndex < fields.size()) {
                String boldText = fields.get(boldColIndex).trim();
                slide.setSlideTextBoldText(boldText);
            }

            // Apply Italic text from CSV if column exists
            if (italicColIndex >= 0 && italicColIndex < fields.size()) {
                String italicText = fields.get(italicColIndex).trim();
                slide.setSlideTextItalicText(italicText);
            }

            // Apply Color text from CSV if column exists
            if (colorColIndex >= 0 && colorColIndex < fields.size()) {
                String colorText = fields.get(colorColIndex).trim();
                slide.setSlideTextColorText(colorText);
            }

            // Apply X-AXIS values from CSV if column exists (comma-separated per text item)
            if (xAxisColIndex >= 0 && xAxisColIndex < fields.size()) {
                String xVals = fields.get(xAxisColIndex).trim();
                if (!xVals.isEmpty()) {
                    String[] parts = xVals.split(",");
                    for (int ti = 0; ti < parts.length; ti++) {
                        String val = parts[ti].trim();
                        if (!val.isEmpty()) {
                            try {
                                int xVal = Integer.parseInt(val);
                                slide.setSlideTextXAt(ti, xVal);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            // Apply Y-AXIS values from CSV if column exists (comma-separated per text item)
            if (yAxisColIndex >= 0 && yAxisColIndex < fields.size()) {
                String yVals = fields.get(yAxisColIndex).trim();
                if (!yVals.isEmpty()) {
                    String[] parts = yVals.split(",");
                    for (int ti = 0; ti < parts.length; ti++) {
                        String val = parts[ti].trim();
                        if (!val.isEmpty()) {
                            try {
                                int yVal = Integer.parseInt(val);
                                slide.setSlideTextYAt(ti, yVal);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            // Apply TEXT-SIZE values from CSV if column exists (comma-separated per text item)
            if (textSizeColIndex >= 0 && textSizeColIndex < fields.size()) {
                String sizeVals = fields.get(textSizeColIndex).trim();
                if (!sizeVals.isEmpty()) {
                    String[] parts = sizeVals.split(",");
                    for (int ti = 0; ti < parts.length; ti++) {
                        String val = parts[ti].trim();
                        if (!val.isEmpty()) {
                            try {
                                int sizeVal = Integer.parseInt(val);
                                slide.setSlideTextSizeAt(ti, sizeVal);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            // Apply audio link from CSV if column exists (single AUDIOLINK → text index 0)
            if (audioLinkColIndex >= 0 && audioLinkColIndex < fields.size()) {
                String audioPath = fields.get(audioLinkColIndex).trim();
                if (!audioPath.isEmpty()) {
                    File audioFile = new File(audioPath);
                    if (!audioFile.isAbsolute() && importSourceDir != null) {
                        audioFile = new File(importSourceDir, audioPath);
                    }
                    if (audioFile.exists()) {
                        int durationMs = probeAudioDurationMs(audioFile);
                        if (durationMs <= 0) durationMs = 3000; // fallback 3s if probe fails
                        slide.setSlideAudio(0, audioFile, durationMs);
                    } else {
                        missingAudioFiles.add("Slide " + (i + 1) + ": " + audioPath);
                    }
                }
            }

            // Apply multi-audio columns (AUDIO1, AUDIO2, etc.)
            for (java.util.Map.Entry<Integer, Integer> entry : audioColByTextIndex.entrySet()) {
                int textIdx = entry.getKey();
                int colIdx = entry.getValue();
                if (colIdx < fields.size()) {
                    String audioPath = fields.get(colIdx).trim();
                    if (!audioPath.isEmpty()) {
                        File audioFile = new File(audioPath);
                        if (!audioFile.isAbsolute() && importSourceDir != null) {
                            audioFile = new File(importSourceDir, audioPath);
                        }
                        if (audioFile.exists()) {
                            int durationMs = probeAudioDurationMs(audioFile);
                            if (durationMs <= 0) durationMs = 3000; // fallback 3s if probe fails
                            slide.setSlideAudio(textIdx, audioFile, durationMs);
                        } else {
                            missingAudioFiles.add("Slide " + (i + 1) + " Text" + (textIdx + 1) + ": " + audioPath);
                        }
                    }
                }
            }

            assigned++;
        }

        applyFirstSlideFormattingToAll();
        slidesPanel.revalidate();
        slidesPanel.repaint();

        String importMsg = assigned + " rows imported across " + textColIndices.size() + " text columns.";
        if (hlColIndex >= 0) importMsg += "\nHL column detected — highlight words imported per slide.";
        if (ulColIndex >= 0) importMsg += "\nUL column detected — underline words imported per slide.";
        if (boldColIndex >= 0) importMsg += "\nBOLD column detected — bold words imported per slide.";
        if (italicColIndex >= 0) importMsg += "\nITALIC column detected — italic words imported per slide.";
        if (colorColIndex >= 0) importMsg += "\nCOLOR column detected — color words imported per slide.";
        if (audioLinkColIndex >= 0) importMsg += "\nAUDIOLINK column detected — audio files imported per slide.";
        if (!audioColByTextIndex.isEmpty()) importMsg += "\nMulti-audio columns detected (AUDIO1-" + (audioColByTextIndex.size()) + ") — audio files mapped to text items.";
        if (xAxisColIndex >= 0) importMsg += "\nX-AXIS column detected — X positions imported per text item.";
        if (yAxisColIndex >= 0) importMsg += "\nY-AXIS column detected — Y positions imported per text item.";
        if (textSizeColIndex >= 0) importMsg += "\nTEXT-SIZE column detected — text sizes imported per text item.";
        importMsg += "\nSlides: " + slideRows.size() + " total.";
        if (!missingAudioFiles.isEmpty()) {
            importMsg += "\n\nWARNING: " + missingAudioFiles.size() + " audio file(s) not found:";
            for (String missing : missingAudioFiles) {
                importMsg += "\n  " + missing;
            }
            importMsg += "\n\nMake sure audio files exist relative to the CSV file location.";
        }
        JOptionPane.showMessageDialog(this, importMsg, "Dictionary Import",
                missingAudioFiles.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
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
        List<SlidePictureData> slidePictureFormats = source.getSlidePictureFormats();
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
        boolean fxScanlineOn = source.fxScanlineCheck.isSelected();
        int fxScanlineVal = source.getFxScanlineRaw();
        boolean fxRaisedOn = source.fxRaisedCheck.isSelected();
        int fxRaisedVal = source.getFxRaisedRaw();
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
        File voFile = source.getSlideVideoOverlayFile();
        int voDurationMs = source.getSlideVideoOverlayDurationMs();
        int voX = source.getSlideVideoOverlayX();
        int voY = source.getSlideVideoOverlayY();
        int voSize = source.getSlideVideoOverlaySize();
        double audioGapSeconds = ((Number) source.audioGapSpinner.getValue()).doubleValue();

        isSyncingFormat = true;
        try {
            for (SlideRow row : slideRows) {
                if (row == source || row.isTitleGridSlide) continue;
                row.applyFormatting(fontName, fontSize, fontStyle, fontColor, alignment, showPin, displayMode, subtitleY, subtitleBgOpacity,
                        showSlideNumber, slideNumberFontName, slideNumberX, slideNumberY, slideNumberSize, slideNumberColor,
                        slideTextFormats, slidePictureFormats,
                        fxRoundCorners, fxCornerRadius,
                        fxVignetteOn, fxVignetteVal, fxSepiaOn, fxSepiaVal,
                        fxGrainOn, fxGrainVal, fxWaterRippleOn, fxWaterRippleVal,
                        fxGlitchOn, fxGlitchVal, fxShakeOn, fxShakeVal,
                        fxScanlineOn, fxScanlineVal, fxRaisedOn, fxRaisedVal,
                        overlayShape, overlayBgMode, ovBgColor, overlayX, overlayY, overlaySize,
                        textJustify, textWidthPct, highlightText, hlColor,
                        textShiftX,
                        voFile, voDurationMs, voX, voY, voSize,
                        audioGapSeconds);
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
        btn.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btn.setPreferredSize(new Dimension(110, 30));
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
                false, 0, 0, 0, 0, 0, 0, 0, 0, 0,
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
                false, 0, 0, 0, 0, 0, 0, 0, 0, 0,
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
                                     int fxGlitch, int fxShake,
                                     int fxScanline, int fxRaised) {
        return renderFrame(image, text, fontName, fontSize, fontStyle,
                fontColor, alignment, showPin, targetW, targetH, displayMode,
                subtitleY, subtitleBgOpacity,
                showSlideNumber, slideNumberText, slideNumberFontName,
                slideNumberX, slideNumberY, slideNumberSize, slideNumberColor,
                slideTexts,
                fxRoundCorners, fxCornerRadius, fxVignette, fxSepia,
                fxGrain, fxWaterRipple, fxGlitch, fxShake,
                fxScanline, fxRaised,
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
                                     int fxScanline, int fxRaised,
                                     boolean overlayEnabled,
                                     String overlayShape, String overlayBgMode, Color overlayBgColor,
                                     int overlayX, int overlayY, int overlaySize,
                                     int animFrameIndex,
                                     boolean textJustify, int textWidthPct,
                                     String highlightText, Color highlightColor,
                                     int textShiftX) {
        return renderFrame(image, text, fontName, fontSize, fontStyle, fontColor, alignment,
                showPin, targetW, targetH, displayMode, subtitleY, subtitleBgOpacity,
                showSlideNumber, slideNumberText, slideNumberFontName,
                slideNumberX, slideNumberY, slideNumberSize, slideNumberColor,
                slideTexts, fxRoundCorners, fxCornerRadius,
                fxVignette, fxSepia, fxGrain, fxWaterRipple, fxGlitch, fxShake,
                fxScanline, fxRaised, overlayEnabled,
                overlayShape, overlayBgMode, overlayBgColor, overlayX, overlayY, overlaySize,
                animFrameIndex, textJustify, textWidthPct, highlightText, highlightColor,
                textShiftX, null);
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
                                     int fxScanline, int fxRaised,
                                     boolean overlayEnabled,
                                     String overlayShape, String overlayBgMode, Color overlayBgColor,
                                     int overlayX, int overlayY, int overlaySize,
                                     int animFrameIndex,
                                     boolean textJustify, int textWidthPct,
                                     String highlightText, Color highlightColor,
                                     int textShiftX,
                                     List<SlidePictureData> slidePictures) {
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

        // CRT Scanlines — smooth sine-wave darkening like real CRT phosphor rows
        if (fxScanline > 0) {
            double strength = fxScanline / 100.0;
            int[] px = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            // Line period scales: 2px at max intensity, 6px at min
            double period = 6.0 - 4.0 * strength;
            // How dark the dark lines get: 0.15 (very dark) at max, 0.6 (subtle) at min
            double minBright = 0.6 - 0.45 * strength;
            double freq = 2.0 * Math.PI / period;
            for (int y = 0; y < targetH; y++) {
                // Sine gives smooth brightness oscillation between minBright and 1.0
                double brightness = minBright + (1.0 - minBright) * (0.5 + 0.5 * Math.sin(freq * y));
                int rowOff = y * targetW;
                for (int x = 0; x < targetW; x++) {
                    int idx = rowOff + x;
                    int r = (int)(((px[idx] >> 16) & 0xFF) * brightness);
                    int gv = (int)(((px[idx] >> 8) & 0xFF) * brightness);
                    int b = (int)((px[idx] & 0xFF) * brightness);
                    px[idx] = (0xFF << 24) | (r << 16) | (gv << 8) | b;
                }
            }
            frame.setRGB(0, 0, targetW, targetH, px, 0, targetW);
        }

        // Raised — 3D emboss/bevel effect using directional lighting on edges
        if (fxRaised > 0) {
            double strength = fxRaised / 100.0;
            int[] px = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            int[] result = new int[px.length];

            // Light direction: top-left (angle ~135 degrees)
            // We compare each pixel with its neighbors to detect surface slope,
            // then brighten slopes facing the light and darken slopes facing away.
            double bevelAmount = 80 + 175 * strength; // how strong the 3D effect is

            for (int y = 0; y < targetH; y++) {
                for (int x = 0; x < targetW; x++) {
                    int idx = y * targetW + x;
                    int c = px[idx];
                    int cr = (c >> 16) & 0xFF;
                    int cg = (c >> 8) & 0xFF;
                    int cb = c & 0xFF;

                    // Get luminance of neighbors for edge/slope detection
                    int lumC = (cr * 299 + cg * 587 + cb * 114) / 1000;

                    // Top-left neighbor (light side)
                    int tlIdx = (y > 0 && x > 0) ? (y - 1) * targetW + (x - 1) : idx;
                    int tl = px[tlIdx];
                    int lumTL = (((tl >> 16) & 0xFF) * 299 + ((tl >> 8) & 0xFF) * 587 + (tl & 0xFF) * 114) / 1000;

                    // Bottom-right neighbor (shadow side)
                    int brIdx = (y < targetH - 1 && x < targetW - 1) ? (y + 1) * targetW + (x + 1) : idx;
                    int br = px[brIdx];
                    int lumBR = (((br >> 16) & 0xFF) * 299 + ((br >> 8) & 0xFF) * 587 + (br & 0xFF) * 114) / 1000;

                    // Slope: positive = facing light (highlight), negative = facing away (shadow)
                    double slope = (lumTL - lumBR) / 255.0;
                    int adjustment = (int)(slope * bevelAmount);

                    int nr = Math.max(0, Math.min(255, cr + adjustment));
                    int ng = Math.max(0, Math.min(255, cg + adjustment));
                    int nb = Math.max(0, Math.min(255, cb + adjustment));
                    result[idx] = (0xFF << 24) | (nr << 16) | (ng << 8) | nb;
                }
            }
            frame.setRGB(0, 0, targetW, targetH, result, 0, targetW);
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

                // When xLeftAligned (set via CSV X-AXIS import), treat X as edge-aligned:
                // For RTL text (Arabic/Hebrew), X is measured from the right side.
                // For LTR text, X is the left edge where the first letter starts.
                if (st.xLeftAligned) {
                    boolean isRTL = false;
                    String rawText = st.text != null ? st.text : "";
                    for (int ci = 0; ci < rawText.length(); ci++) {
                        int dir = Character.getDirectionality(rawText.charAt(ci));
                        if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                                || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                            isRTL = true;
                            break;
                        }
                    }
                    if (isRTL) {
                        // X measures from right: right edge of text starts at X% from right
                        stCenterX = targetW - (int) (st.x / 100.0 * targetW) - stMaxLineWidth / 2;
                    } else {
                        stCenterX += stMaxLineWidth / 2;
                    }
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

                // Use wrap width as fixed reference for LEFT/RIGHT alignment
                // so position doesn't shift with text content length.
                int stAlignWidth = stMaxWrapWidth;
                int stAlignLeft = stCenterX - stAlignWidth / 2;
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
                        lineX = stAlignLeft;
                    } else if (st.alignment == SwingConstants.RIGHT) {
                        lineX = stAlignLeft + stAlignWidth - lineW;
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
                            double avgGap = (double) (stMaxLineWidth - totalWordsWidth) / (justifyWords.length - 1);
                            double normalSpace = stFm.stringWidth(" ");
                            // Only justify if gaps won't exceed 3x normal space width
                            if (avgGap <= normalSpace * 3) {
                                justifyExtraSpace = avgGap;
                                justified = true;
                            }
                        }
                    }

                    // === Typewriter: limit visible characters ===
                    String visibleLine = line;
                    if (effect.equals("Typewriter") && animFrameIndex > 0) {
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
                                double twNormalSpace = stFm.stringWidth(" ");
                                if (justifyExtraSpace > twNormalSpace * 3) justified = false;
                            }
                        }
                    }

                    // === Odometer: characters roll through random letters before landing ===
                    if (st.odometer && animFrameIndex >= 0) {
                        // Calculate total chars across all lines for global char index
                        int odomCharsBefore = 0;
                        for (int tli = 0; tli < li; tli++) odomCharsBefore += stWrappedLines.get(tli).length();
                        // Speed: 0 = very slow (many frames to settle), 100 = instant
                        double odomSpeed = st.odometerSpeed / 100.0;
                        int settleBase = (int) (3 + 25 * (1.0 - odomSpeed));  // frames before first char lands
                        int settleStagger = Math.max(1, (int) (1 + 8 * (1.0 - odomSpeed)));  // frames between each char landing
                        char[] odomChars = visibleLine.toCharArray();
                        boolean allLanded = true;
                        for (int ci = 0; ci < odomChars.length; ci++) {
                            char origChar = odomChars[ci];
                            if (origChar == ' ') continue;  // don't roll spaces
                            int globalIdx = odomCharsBefore + ci;
                            int landFrame = settleBase + globalIdx * settleStagger;
                            if (animFrameIndex < landFrame) {
                                allLanded = false;
                                // Cycling character: changes every frame, seeded by position for variety
                                boolean isUpper = Character.isUpperCase(origChar);
                                boolean isDigit = Character.isDigit(origChar);
                                if (isDigit) {
                                    odomChars[ci] = (char) ('0' + (animFrameIndex * 3 + globalIdx * 7) % 10);
                                } else {
                                    int roll = (animFrameIndex * 3 + globalIdx * 7) % 26;
                                    odomChars[ci] = isUpper ? (char) ('A' + roll) : (char) ('a' + roll);
                                }
                            }
                        }
                        visibleLine = new String(odomChars);
                        // On frame 0, show all rolling (no chars landed yet unless settleBase is 0)
                    }

                    // === Build TextLayout for correct bidi/RTL visual positioning ===
                    java.awt.font.TextLayout stTextLayout = null;
                    if (!line.isEmpty()) {
                        stTextLayout = new java.awt.font.TextLayout(line, stFont, ((Graphics2D) g).getFontRenderContext());
                    }

                    // === Slide text highlight (supports comma-separated words) ===
                    if (st.highlightText != null && !st.highlightText.isEmpty()) {
                        String lineLower = line.toLowerCase();
                        // Tightness: positive = padding around text, negative = reduce height
                        int tightness = st.highlightTightness;
                        int hlPadX, hlPadY;
                        int heightShrink = 0;
                        if (tightness >= 0) {
                            float padFactor = tightness / 100.0f;
                            hlPadX = (int) (padFactor * 8 * stScaleFactor);
                            hlPadY = hlPadX;
                        } else {
                            hlPadX = 0;
                            hlPadY = 0;
                            float shrinkFactor = Math.abs(tightness) / 50.0f;
                            heightShrink = (int) (stFm.getHeight() * 0.7f * shrinkFactor);
                        }
                        String[] hlTerms = st.highlightText.split(",");
                        for (String hlTerm : hlTerms) {
                            hlTerm = hlTerm.trim();
                            if (hlTerm.isEmpty()) continue;
                            String hlLower = hlTerm.toLowerCase();
                            int searchFrom = 0;
                            while (searchFrom < lineLower.length()) {
                                int hlIdx = lineLower.indexOf(hlLower, searchFrom);
                                if (hlIdx < 0) break;
                                // Use TextLayout for correct visual position (handles RTL/bidi text)
                                int hlX, hlW;
                                if (stTextLayout != null) {
                                    java.awt.Shape hlVisShape = stTextLayout.getLogicalHighlightShape(hlIdx, hlIdx + hlTerm.length());
                                    java.awt.geom.Rectangle2D hlVisBounds = hlVisShape.getBounds2D();
                                    hlX = lineX + (int) hlVisBounds.getX();
                                    hlW = (int) Math.ceil(hlVisBounds.getWidth());
                                } else {
                                    String before = line.substring(0, hlIdx);
                                    String match = line.substring(hlIdx, hlIdx + hlTerm.length());
                                    hlX = lineX + stFm.stringWidth(before);
                                    hlW = stFm.stringWidth(match);
                                }
                            Color hlC = st.highlightColor != null ? st.highlightColor : new Color(255, 100, 150, 180);
                            Graphics2D gHL = (Graphics2D) g.create();
                            gHL.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                            int fullH = stFm.getHeight() + hlPadY * 2;
                            int hlRectH = Math.max(2, fullH - heightShrink);
                            int hlRectX = hlX - hlPadX;
                            // Center the reduced-height rect vertically on the text
                            int hlRectY = lineY - stFm.getAscent() - hlPadY + (fullH - hlRectH) / 2;
                            int hlRectW = Math.max(1, hlW + hlPadX * 2);
                            int arc = (int) (4 * stScaleFactor);
                            String hlStyle = st.highlightStyle != null ? st.highlightStyle : "Regular";

                            switch (hlStyle) {
                                case "Brush": {
                                    int absPad = Math.max(0, hlPadX);
                                    int bh = hlRectH;
                                    int by = hlRectY;
                                    int bx = hlRectX - absPad;
                                    int bw = hlRectW + absPad * 2;
                                    long seed = (long) hlIdx * 31 + li * 997;
                                    Random brushRng = new Random(seed);
                                    int bands = Math.max(2, 3 + (int) (bh / (6 * stScaleFactor)));
                                    float bandH = bh / (float) bands;
                                    for (int bi = 0; bi < bands; bi++) {
                                        float yOff = by + bi * bandH;
                                        int edgeVar = Math.max(1, (int) (bw * 0.08));
                                        int x1 = bx + brushRng.nextInt(edgeVar) - edgeVar / 2;
                                        int x2 = bx + bw + brushRng.nextInt(edgeVar) - edgeVar / 2;
                                        int yVar = Math.max(1, (int) (bandH * 0.3));
                                        float y1 = yOff + brushRng.nextInt(yVar);
                                        int thisAlpha = Math.min(255, Math.max(0, hlC.getAlpha() + brushRng.nextInt(40) - 20));
                                        gHL.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), thisAlpha));
                                        int cpx = (x1 + x2) / 2 + brushRng.nextInt(Math.max(1, edgeVar * 2)) - edgeVar;
                                        float cpy = y1 + bandH / 2 + brushRng.nextInt(yVar + 1) - yVar / 2;
                                        float strokeW = bandH * (0.7f + brushRng.nextFloat() * 0.6f);
                                        gHL.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                        java.awt.geom.QuadCurve2D curve = new java.awt.geom.QuadCurve2D.Float(
                                                x1, y1 + bandH / 2, cpx, cpy, x2, y1 + bandH / 2);
                                        gHL.draw(curve);
                                    }
                                    int splatters = 4 + brushRng.nextInt(6);
                                    for (int si = 0; si < splatters; si++) {
                                        float sx, sy;
                                        if (brushRng.nextBoolean()) {
                                            sx = brushRng.nextBoolean() ? bx - brushRng.nextInt((int)(6 * stScaleFactor) + 1)
                                                    : bx + bw + brushRng.nextInt((int)(6 * stScaleFactor) + 1);
                                            sy = by + brushRng.nextInt(Math.max(1, bh));
                                        } else {
                                            sx = bx + brushRng.nextInt(Math.max(1, bw));
                                            sy = brushRng.nextBoolean() ? by - brushRng.nextInt((int)(3 * stScaleFactor) + 1)
                                                    : by + bh + brushRng.nextInt((int)(3 * stScaleFactor) + 1);
                                        }
                                        float dotR = 1 + brushRng.nextFloat() * 3 * stScaleFactor;
                                        int dotAlpha = Math.min(255, (int) (hlC.getAlpha() * (0.3 + brushRng.nextFloat() * 0.5)));
                                        gHL.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), dotAlpha));
                                        gHL.fill(new java.awt.geom.Ellipse2D.Float(sx - dotR, sy - dotR, dotR * 2, dotR * 2));
                                    }
                                    break;
                                }
                                case "Brush2": {
                                    // Smooth, single-pass dry brush — fewer, wider strokes with
                                    // feathered edges and slight wobble for a calligraphy feel
                                    int bh = hlRectH;
                                    int by = hlRectY;
                                    int bx = hlRectX;
                                    int bw = hlRectW;
                                    long seed = (long) hlIdx * 53 + li * 1301;
                                    Random b2Rng = new Random(seed);

                                    // 2-3 wide sweeping strokes that cover the whole area
                                    int strokes = 2 + (bh > (int)(20 * stScaleFactor) ? 1 : 0);
                                    float strokeH = bh / (float) strokes;
                                    for (int si = 0; si < strokes; si++) {
                                        float cy = by + si * strokeH + strokeH / 2;
                                        // Thick calligraphic stroke
                                        float sw = strokeH * (0.85f + b2Rng.nextFloat() * 0.3f);
                                        gHL.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                        int thisAlpha = Math.min(255, Math.max(0, hlC.getAlpha() - 20 + b2Rng.nextInt(40)));
                                        gHL.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), thisAlpha));

                                        // Slight wobble via cubic curve
                                        float wobble = strokeH * 0.15f;
                                        float x1 = bx - (int)(2 * stScaleFactor);
                                        float x2 = bx + bw + (int)(2 * stScaleFactor);
                                        float cp1x = bx + bw * 0.3f + b2Rng.nextFloat() * bw * 0.1f;
                                        float cp1y = cy + (b2Rng.nextFloat() - 0.5f) * wobble * 2;
                                        float cp2x = bx + bw * 0.7f - b2Rng.nextFloat() * bw * 0.1f;
                                        float cp2y = cy + (b2Rng.nextFloat() - 0.5f) * wobble * 2;
                                        java.awt.geom.CubicCurve2D curve = new java.awt.geom.CubicCurve2D.Float(
                                                x1, cy, cp1x, cp1y, cp2x, cp2y, x2, cy);
                                        gHL.draw(curve);
                                    }

                                    // Feathered edge: a thin semi-transparent stroke at top and bottom
                                    int featherAlpha = Math.min(255, hlC.getAlpha() / 3);
                                    gHL.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), featherAlpha));
                                    float featherStroke = Math.max(1, 2 * stScaleFactor);
                                    gHL.setStroke(new BasicStroke(featherStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                    float featherWobble = 2 * stScaleFactor;
                                    // Top feather
                                    float fcp1 = bx + bw * 0.25f;
                                    float fcp2 = bx + bw * 0.75f;
                                    gHL.draw(new java.awt.geom.CubicCurve2D.Float(
                                            bx, by, fcp1, by - featherWobble * (0.5f + b2Rng.nextFloat()),
                                            fcp2, by + featherWobble * (0.5f + b2Rng.nextFloat()),
                                            bx + bw, by));
                                    // Bottom feather
                                    gHL.draw(new java.awt.geom.CubicCurve2D.Float(
                                            bx, by + bh, fcp1, by + bh + featherWobble * (0.5f + b2Rng.nextFloat()),
                                            fcp2, by + bh - featherWobble * (0.5f + b2Rng.nextFloat()),
                                            bx + bw, by + bh));
                                    break;
                                }
                                case "Pill": {
                                    gHL.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), hlC.getAlpha()));
                                    int pillArc = hlRectH;
                                    gHL.fillRoundRect(hlRectX, hlRectY, hlRectW, hlRectH, pillArc, pillArc);
                                    break;
                                }
                                case "Gradient": {
                                    java.awt.GradientPaint gp = new java.awt.GradientPaint(
                                            hlRectX, hlRectY, new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), hlC.getAlpha()),
                                            hlRectX + hlRectW, hlRectY, new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), 0));
                                    gHL.setPaint(gp);
                                    gHL.fillRoundRect(hlRectX, hlRectY, hlRectW, hlRectH, arc, arc);
                                    break;
                                }
                                case "Glow": {
                                    int glowLayers = 7;
                                    for (int gl = glowLayers; gl >= 0; gl--) {
                                        int expand = gl * (int) (4 * stScaleFactor);
                                        int alpha;
                                        if (gl == 0) {
                                            alpha = Math.min(255, hlC.getAlpha() + 30);
                                        } else {
                                            // Stronger outer glow with smoother falloff
                                            double t = (double) gl / glowLayers;
                                            alpha = Math.max(8, (int)(hlC.getAlpha() * 0.6 * (1.0 - t * t)));
                                        }
                                        gHL.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), alpha));
                                        gHL.fillRoundRect(hlRectX - expand, hlRectY - expand,
                                                hlRectW + expand * 2, hlRectH + expand * 2,
                                                arc + expand, arc + expand);
                                    }
                                    break;
                                }
                                case "Box": {
                                    float strokeW = Math.max(1, 2 * stScaleFactor);
                                    gHL.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), hlC.getAlpha()));
                                    gHL.setStroke(new BasicStroke(strokeW));
                                    gHL.drawRoundRect(hlRectX, hlRectY, hlRectW, hlRectH, arc, arc);
                                    gHL.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), Math.min(255, hlC.getAlpha() / 4)));
                                    gHL.fillRoundRect(hlRectX, hlRectY, hlRectW, hlRectH, arc, arc);
                                    break;
                                }
                                default: { // "Regular"
                                    gHL.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), hlC.getAlpha()));
                                    gHL.fillRoundRect(hlRectX, hlRectY, hlRectW, hlRectH, arc, arc);
                                    break;
                                }
                            }
                                gHL.dispose();
                                searchFrom = hlIdx + hlTerm.length();
                            }
                        }
                    }

                    // === Slide text underline (independent word matching) ===
                    String ulStyle = st.underlineStyle != null ? st.underlineStyle : "None";
                    if (!"None".equals(ulStyle)) {
                        // Use underlineText if set, otherwise fall back to highlightText
                        String ulTextRaw = (st.underlineText != null && !st.underlineText.isEmpty())
                                ? st.underlineText : st.highlightText;
                        if (ulTextRaw != null && !ulTextRaw.isEmpty()) {
                            String lineLower = line.toLowerCase();
                            Color hlC = st.highlightColor != null ? st.highlightColor : new Color(255, 100, 150, 180);
                            String[] ulTerms = ulTextRaw.split(",");
                            for (String ulTerm : ulTerms) {
                                ulTerm = ulTerm.trim();
                                if (ulTerm.isEmpty()) continue;
                                String ulLower = ulTerm.toLowerCase();
                                int ulSearchFrom = 0;
                                while (ulSearchFrom < lineLower.length()) {
                                    int ulIdx = lineLower.indexOf(ulLower, ulSearchFrom);
                                    if (ulIdx < 0) break;
                                    // Use TextLayout for correct visual position (handles RTL/bidi text)
                                    int ulMatchX, ulMatchW;
                                    if (stTextLayout != null) {
                                        java.awt.Shape ulVisShape = stTextLayout.getLogicalHighlightShape(ulIdx, ulIdx + ulTerm.length());
                                        java.awt.geom.Rectangle2D ulVisBounds = ulVisShape.getBounds2D();
                                        ulMatchX = lineX + (int) ulVisBounds.getX();
                                        ulMatchW = (int) Math.ceil(ulVisBounds.getWidth());
                                    } else {
                                        String ulBefore = line.substring(0, ulIdx);
                                        String ulMatch = line.substring(ulIdx, ulIdx + ulTerm.length());
                                        ulMatchX = lineX + stFm.stringWidth(ulBefore);
                                        ulMatchW = stFm.stringWidth(ulMatch);
                                    }
                                Graphics2D gUL = (Graphics2D) g.create();
                                gUL.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                                int ulY = lineY + stFm.getDescent() + (int) (1 * stScaleFactor);
                                int ulX1 = ulMatchX;
                                int ulX2 = ulMatchX + ulMatchW;
                                float baseStroke = Math.max(1, 2 * stScaleFactor);
                                gUL.setColor(new Color(hlC.getRed(), hlC.getGreen(), hlC.getBlue(), Math.min(255, hlC.getAlpha() + 40)));

                                switch (ulStyle) {
                                    case "Straight": {
                                        gUL.setStroke(new BasicStroke(baseStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                        gUL.drawLine(ulX1, ulY, ulX2, ulY);
                                        break;
                                    }
                                    case "Wavy": {
                                        gUL.setStroke(new BasicStroke(baseStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                        java.awt.geom.GeneralPath wavePath = new java.awt.geom.GeneralPath();
                                        float waveAmp = 3 * stScaleFactor;
                                        float waveLen = 8 * stScaleFactor;
                                        wavePath.moveTo(ulX1, ulY);
                                        for (float wx = ulX1; wx < ulX2; wx += waveLen) {
                                            float endX = Math.min(wx + waveLen, ulX2);
                                            float midX = (wx + endX) / 2;
                                            float dir = ((int) ((wx - ulX1) / waveLen) % 2 == 0) ? -waveAmp : waveAmp;
                                            wavePath.quadTo(midX, ulY + dir, endX, ulY);
                                        }
                                        gUL.draw(wavePath);
                                        break;
                                    }
                                    case "Double": {
                                        float gap = 3 * stScaleFactor;
                                        gUL.setStroke(new BasicStroke(baseStroke * 0.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                        gUL.drawLine(ulX1, ulY, ulX2, ulY);
                                        gUL.drawLine(ulX1, (int) (ulY + gap), ulX2, (int) (ulY + gap));
                                        break;
                                    }
                                    case "Dotted": {
                                        float dotSize = 2 * stScaleFactor;
                                        float dotGap = 4 * stScaleFactor;
                                        gUL.setStroke(new BasicStroke(baseStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                                                10.0f, new float[]{dotSize, dotGap}, 0.0f));
                                        gUL.drawLine(ulX1, ulY, ulX2, ulY);
                                        break;
                                    }
                                    case "Dashed": {
                                        float dashLen = 8 * stScaleFactor;
                                        float dashGap = 4 * stScaleFactor;
                                        gUL.setStroke(new BasicStroke(baseStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                                                10.0f, new float[]{dashLen, dashGap}, 0.0f));
                                        gUL.drawLine(ulX1, ulY, ulX2, ulY);
                                        break;
                                    }
                                    case "Thick": {
                                        gUL.setStroke(new BasicStroke(baseStroke * 3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                        gUL.drawLine(ulX1, ulY, ulX2, ulY);
                                        break;
                                    }
                                    case "Zigzag": {
                                        gUL.setStroke(new BasicStroke(baseStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                                        java.awt.geom.GeneralPath zigPath = new java.awt.geom.GeneralPath();
                                        float zigAmp = 3 * stScaleFactor;
                                        float zigLen = 6 * stScaleFactor;
                                        zigPath.moveTo(ulX1, ulY);
                                        boolean up = true;
                                        for (float zx = ulX1; zx < ulX2; zx += zigLen) {
                                            float endX = Math.min(zx + zigLen, ulX2);
                                            zigPath.lineTo(endX, ulY + (up ? -zigAmp : zigAmp));
                                            up = !up;
                                        }
                                        gUL.draw(zigPath);
                                        break;
                                    }
                                }
                                    gUL.dispose();
                                    ulSearchFrom = ulIdx + ulTerm.length();
                                }
                            }
                        }
                    }

                    // === Pre-collect per-word override regions and save background pixels ===
                    boolean hasBoldWords = st.boldText != null && !st.boldText.isEmpty();
                    boolean hasItalicWords = st.italicText != null && !st.italicText.isEmpty();
                    boolean hasColorWords = st.colorText != null && !st.colorText.isEmpty();
                    // List of: [ovIdx, termLen, ovX, ovW, boldFlag, italicFlag, colorFlag, savedPixels[]]
                    java.util.List<Object[]> overrideRegions = new java.util.ArrayList<>();
                    if (hasBoldWords || hasItalicWords || hasColorWords) {
                        java.util.LinkedHashMap<String, int[]> overrideTerms = new java.util.LinkedHashMap<>();
                        if (hasBoldWords) {
                            for (String bt : st.boldText.split(",")) {
                                bt = bt.trim();
                                if (!bt.isEmpty()) {
                                    int[] flags = overrideTerms.computeIfAbsent(bt.toLowerCase(), k -> new int[3]);
                                    flags[0] = 1;
                                }
                            }
                        }
                        if (hasItalicWords) {
                            for (String it : st.italicText.split(",")) {
                                it = it.trim();
                                if (!it.isEmpty()) {
                                    int[] flags = overrideTerms.computeIfAbsent(it.toLowerCase(), k -> new int[3]);
                                    flags[1] = 1;
                                }
                            }
                        }
                        if (hasColorWords) {
                            for (String ct : st.colorText.split(",")) {
                                ct = ct.trim();
                                if (!ct.isEmpty()) {
                                    int[] flags = overrideTerms.computeIfAbsent(ct.toLowerCase(), k -> new int[3]);
                                    flags[2] = 1;
                                }
                            }
                        }

                        String ovLineLower = visibleLine.toLowerCase();
                        for (java.util.Map.Entry<String, int[]> entry : overrideTerms.entrySet()) {
                            String termLower = entry.getKey();
                            int[] flags = entry.getValue();
                            int ovSearchFrom = 0;
                            while (ovSearchFrom < ovLineLower.length()) {
                                int ovIdx = ovLineLower.indexOf(termLower, ovSearchFrom);
                                if (ovIdx < 0) break;
                                int ovX, ovW;
                                if (stTextLayout != null && ovIdx + termLower.length() <= line.length()) {
                                    java.awt.Shape ovShape = stTextLayout.getLogicalHighlightShape(ovIdx, ovIdx + termLower.length());
                                    java.awt.geom.Rectangle2D ovBounds = ovShape.getBounds2D();
                                    ovX = lineX + (int) ovBounds.getX();
                                    ovW = (int) Math.ceil(ovBounds.getWidth());
                                } else {
                                    String ovBefore = visibleLine.substring(0, ovIdx);
                                    ovX = lineX + stFm.stringWidth(ovBefore);
                                    ovW = stFm.stringWidth(visibleLine.substring(ovIdx, ovIdx + termLower.length()));
                                }
                                // Save background pixels before text is drawn
                                int saveY = lineY - stFm.getAscent() - 2;
                                int saveH = stFm.getHeight() + 4;
                                int sx = Math.max(0, ovX);
                                int sy = Math.max(0, saveY);
                                int sw = Math.min(ovW, frame.getWidth() - sx);
                                int sh = Math.min(saveH, frame.getHeight() - sy);
                                int[] savedPixels = null;
                                if (sw > 0 && sh > 0) {
                                    savedPixels = frame.getRGB(sx, sy, sw, sh, null, 0, sw);
                                }
                                overrideRegions.add(new Object[]{
                                    ovIdx, termLower.length(), ovX, ovW, flags[0], flags[1], flags[2],
                                    savedPixels, sx, sy, sw, sh
                                });
                                ovSearchFrom = ovIdx + termLower.length();
                            }
                        }
                    }

                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setFont(stFont);

                    switch (effect) {
                        case "Shadow": {
                            // Multi-layer soft drop shadow for professional look
                            int baseOff = Math.max(1, (int) (scaledStSize * 0.07 * intensity));
                            int shadowLayers = 3 + (int) (3 * intensity);
                            for (int sl = shadowLayers; sl >= 1; sl--) {
                                float layerOff = baseOff * sl / (float) shadowLayers;
                                int alpha = (int) (60 * intensity / sl);
                                float blur = sl * scaledStSize * 0.015f * (float) intensity;
                                g2.setColor(new Color(0, 0, 0, Math.min(255, alpha)));
                                Font sf = stFont.deriveFont(scaledStSize + blur);
                                g2.setFont(sf);
                                FontMetrics sfm = g2.getFontMetrics();
                                int sOffY = (int) (lineY + layerOff) - (sfm.getAscent() - stAscent) / 2;
                                if (justified) {
                                    double dx = stBlockLeft;
                                    for (String w : justifyWords) {
                                        int origW = stFm.stringWidth(w);
                                        int sOffX = (int) (dx + layerOff) - (sfm.stringWidth(w) - origW) / 2;
                                        g2.drawString(w, sOffX, sOffY);
                                        dx += origW + justifyExtraSpace;
                                    }
                                } else {
                                    int sLineW = sfm.stringWidth(visibleLine);
                                    int sOffX = (int) (lineX + layerOff) - (sLineW - lineW) / 2;
                                    g2.drawString(visibleLine, sOffX, sOffY);
                                }
                            }
                            g2.setFont(stFont);
                            g2.setColor(stColor);
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        case "Glow": {
                            int layers = 4 + (int) (6 * intensity);
                            for (int gl = layers; gl >= 1; gl--) {
                                float spread = gl * scaledStSize * 0.05f * (float) intensity;
                                // Smoother alpha falloff with quadratic decay
                                double t = (double) gl / layers;
                                int alpha = (int) (70 * intensity * t * t);
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
                            // Bright inner core
                            g2.setColor(new Color(255, 255, 255, (int) (220 + 35 * intensity)));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        case "Neon": {
                            int layers = 5 + (int) (5 * intensity);
                            // Outer glow layers (colored)
                            for (int nl = layers; nl >= 1; nl--) {
                                float spread = nl * scaledStSize * 0.06f * (float) intensity;
                                double t = (double) nl / layers;
                                int alpha = (int) (80 * intensity * t);
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
                            // Inner bright white-hot core with slight color tint
                            g2.setFont(stFont);
                            int coreR = Math.min(255, stColor.getRed() + (int) (100 * intensity));
                            int coreG = Math.min(255, stColor.getGreen() + (int) (100 * intensity));
                            int coreB = Math.min(255, stColor.getBlue() + (int) (100 * intensity));
                            g2.setColor(new Color(coreR, coreG, coreB));
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
                            int layers = 4 + (int) (5 * intensity);
                            Color[] fireColors = {
                                new Color(180, 30, 0, (int) (50 * intensity)),    // deep red
                                new Color(220, 60, 0, (int) (70 * intensity)),    // red-orange
                                new Color(255, 120, 0, (int) (90 * intensity)),   // orange
                                new Color(255, 180, 30, (int) (110 * intensity)), // gold
                                new Color(255, 220, 80, (int) (130 * intensity)), // yellow
                                new Color(255, 245, 160, (int) (150 * intensity)) // bright yellow
                            };
                            for (int fl = layers; fl >= 1; fl--) {
                                float rise = fl * scaledStSize * 0.04f * (float) intensity;
                                double flicker = rise * 0.5 * Math.sin(animFrameIndex * 0.35 + fl * 1.7)
                                        + rise * 0.2 * Math.cos(animFrameIndex * 0.5 + fl * 2.3);
                                Color fc = fireColors[Math.min(fl - 1, fireColors.length - 1)];
                                g2.setColor(fc);
                                float fSize = scaledStSize + fl * scaledStSize * 0.025f * (float) intensity;
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
                            g2.setColor(new Color(255, 255, 220));
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                        case "Ice": {
                            int layers = 4 + (int) (4 * intensity);
                            Color[] iceColors = {
                                new Color(100, 160, 255),   // deep ice blue
                                new Color(130, 190, 255),   // medium blue
                                new Color(170, 210, 255),   // light blue
                                new Color(200, 230, 255),   // pale blue
                                new Color(230, 245, 255)    // near white frost
                            };
                            for (int il = layers; il >= 1; il--) {
                                float spread = il * scaledStSize * 0.04f * (float) intensity;
                                double shimmer = spread * 0.25 * Math.sin(animFrameIndex * 0.18 + il * 1.2);
                                double t = (double) il / layers;
                                int alpha = (int) (60 * intensity * t);
                                Color ic = iceColors[Math.min(il - 1, iceColors.length - 1)];
                                g2.setColor(new Color(ic.getRed(), ic.getGreen(), ic.getBlue(), Math.min(255, alpha)));
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
                            g2.setColor(new Color(230, 245, 255));
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
                        case "Shake": {
                            // Per-character random shake/jitter effect
                            double shakeAmp = scaledStSize * 0.12 * intensity;
                            Random shakeRng = new Random(animFrameIndex * 7L);
                            if (justified) {
                                double dx = stBlockLeft;
                                for (String w : justifyWords) {
                                    for (int ci = 0; ci < w.length(); ci++) {
                                        String ch = String.valueOf(w.charAt(ci));
                                        int cx = (int) dx + stFm.stringWidth(w.substring(0, ci));
                                        double sx = shakeAmp * (shakeRng.nextDouble() * 2 - 1);
                                        double sy = shakeAmp * (shakeRng.nextDouble() * 2 - 1);
                                        g2.setColor(stColor);
                                        g2.drawString(ch, (int) (cx + sx), (int) (lineY + sy));
                                    }
                                    dx += stFm.stringWidth(w) + justifyExtraSpace;
                                }
                            } else {
                                for (int ci = 0; ci < visibleLine.length(); ci++) {
                                    String ch = String.valueOf(visibleLine.charAt(ci));
                                    int cx = lineX + stFm.stringWidth(visibleLine.substring(0, ci));
                                    double sx = shakeAmp * (shakeRng.nextDouble() * 2 - 1);
                                    double sy = shakeAmp * (shakeRng.nextDouble() * 2 - 1);
                                    g2.setColor(stColor);
                                    g2.drawString(ch, (int) (cx + sx), (int) (lineY + sy));
                                }
                            }
                            break;
                        }
                        case "Pulse": {
                            // Text pulses in size and opacity
                            double pulsePhase = animFrameIndex * 0.15;
                            double pulseFactor = 1.0 + 0.15 * intensity * Math.sin(pulsePhase);
                            int pulseAlpha = (int) (255 * (0.6 + 0.4 * (0.5 + 0.5 * Math.sin(pulsePhase))));
                            float pulseSize = (float) (scaledStSize * pulseFactor);
                            Font pulseFont = stFont.deriveFont(pulseSize);
                            g2.setFont(pulseFont);
                            FontMetrics pfm = g2.getFontMetrics();
                            int pOffY = lineY - (pfm.getAscent() - stAscent) / 2;
                            g2.setColor(new Color(stColor.getRed(), stColor.getGreen(), stColor.getBlue(),
                                    Math.max(0, Math.min(255, pulseAlpha))));
                            if (justified) {
                                double dx = stBlockLeft;
                                for (String w : justifyWords) {
                                    int origW = stFm.stringWidth(w);
                                    int offX = (int) dx - (pfm.stringWidth(w) - origW) / 2;
                                    g2.drawString(w, offX, pOffY);
                                    dx += origW + justifyExtraSpace;
                                }
                            } else {
                                int pLineW = pfm.stringWidth(visibleLine);
                                int pOffX = lineX - (pLineW - lineW) / 2;
                                g2.drawString(visibleLine, pOffX, pOffY);
                            }
                            g2.setFont(stFont);
                            break;
                        }
                        default: { // "None" and "Typewriter" (typewriter just limits chars above)
                            g2.setColor(stColor);
                            if (justified) drawJustified(g2, justifyWords, stBlockLeft, lineY, justifyExtraSpace, stFm);
                            else g2.drawString(visibleLine, lineX, lineY);
                            break;
                        }
                    }

                    // === Per-word bold, italic, and color overrides ===
                    // Restore saved background pixels, then draw override word on clean background
                    for (Object[] region : overrideRegions) {
                        int ovIdx = (int) region[0];
                        int termLen = (int) region[1];
                        int ovX = (int) region[2];
                        int ovW = (int) region[3];
                        int boldFlag = (int) region[4];
                        int italicFlag = (int) region[5];
                        int colorFlag = (int) region[6];
                        int[] savedPixels = (int[]) region[7];
                        int sx = (int) region[8];
                        int sy = (int) region[9];
                        int sw = (int) region[10];
                        int sh = (int) region[11];

                        // Restore the background pixels (erases the original text in this region)
                        if (savedPixels != null && sw > 0 && sh > 0) {
                            frame.setRGB(sx, sy, sw, sh, savedPixels, 0, sw);
                        }

                        // Draw override word with modified font/color
                        int wordStyle = st.fontStyle;
                        if (boldFlag == 1) wordStyle |= Font.BOLD;
                        if (italicFlag == 1) wordStyle |= Font.ITALIC;
                        Font wordFont = new Font(st.fontName, wordStyle, (int) scaledStSize);
                        Color wordColor = (colorFlag == 1) ? st.colorTextColor : stColor;
                        String wordText = visibleLine.substring(ovIdx, ovIdx + termLen);

                        Graphics2D gOv = (Graphics2D) g.create();
                        gOv.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        gOv.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        gOv.setFont(wordFont);
                        gOv.setColor(wordColor);
                        // Scale override word to fit in the original word's space
                        FontMetrics ovFm = gOv.getFontMetrics();
                        int overrideW = ovFm.stringWidth(wordText);
                        if (overrideW > 0 && ovW > 0 && overrideW != ovW) {
                            java.awt.geom.AffineTransform savedTx = gOv.getTransform();
                            double scaleX = (double) ovW / overrideW;
                            gOv.translate(ovX, 0);
                            gOv.scale(scaleX, 1.0);
                            gOv.drawString(wordText, 0, lineY);
                            gOv.setTransform(savedTx);
                        } else {
                            gOv.drawString(wordText, ovX, lineY);
                        }
                        gOv.dispose();
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
                        double normalSpace = fm.stringWidth(" ");
                        // Skip justify if gaps would be too large (looks unprofessional)
                        if (extraSpace <= normalSpace * 3) {
                            double drawX = lx;
                            for (int wi = 0; wi < words.length; wi++) {
                                g.drawString(words[wi], (int) drawX, lineY);
                                drawX += fm.stringWidth(words[wi]) + extraSpace;
                            }
                            lineY += lineHeight;
                            continue;
                        }
                    }
                }

                g.drawString(line, lx, lineY);
                lineY += lineHeight;
            }
        }

        // ========== SLIDE PICTURE OVERLAY(S) ==========
        if (slidePictures != null) {
            for (SlidePictureData pic : slidePictures) {
                if (!pic.show || pic.image == null) continue;
                int picW = Math.max(1, (int) (targetW * pic.widthPct / 100.0));
                double aspect = (double) pic.image.getHeight() / pic.image.getWidth();
                int picH = Math.max(1, (int) (picW * aspect));
                int picX = (int) (targetW * pic.x / 100.0) - picW / 2;
                int picY = (int) (targetH * pic.y / 100.0) - picH / 2;

                if ("Circle".equals(pic.shape)) {
                    int diameter = Math.min(picW, picH);
                    int cx = (int) (targetW * pic.x / 100.0) - diameter / 2;
                    int cy = (int) (targetH * pic.y / 100.0) - diameter / 2;
                    Graphics2D pg = (Graphics2D) g.create();
                    pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    pg.setClip(new java.awt.geom.Ellipse2D.Double(cx, cy, diameter, diameter));
                    // Cover-crop the image to fill the circle
                    double coverScale = Math.max((double) diameter / pic.image.getWidth(),
                            (double) diameter / pic.image.getHeight());
                    int drawW = (int) (pic.image.getWidth() * coverScale);
                    int drawH = (int) (pic.image.getHeight() * coverScale);
                    pg.drawImage(pic.image, cx + (diameter - drawW) / 2, cy + (diameter - drawH) / 2, drawW, drawH, null);
                    pg.dispose();
                } else {
                    Graphics2D pg = (Graphics2D) g.create();
                    pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    if (pic.cornerRadius > 0) {
                        float rcScale = Math.max(targetW, targetH) / 1920.0f;
                        int radius = Math.max(1, (int) (pic.cornerRadius * Math.max(rcScale, 0.5f)));
                        pg.setClip(new RoundRectangle2D.Double(picX, picY, picW, picH, radius * 2, radius * 2));
                    }
                    pg.drawImage(pic.image, picX, picY, picW, picH, null);
                    pg.dispose();
                }
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
                    row.getSlidePictureDataList(),
                    row.isFxRoundCorners(), row.getFxCornerRadius(),
                    row.getFxVignette(), row.getFxSepia(), row.getFxGrain(),
                    row.getFxWaterRipple(), row.getFxGlitch(), row.getFxShake(),
                    row.getFxScanline(), row.getFxRaised(),
                    row.isOverlayEnabled(),
                    row.getOverlayShape(), row.getOverlayBgMode(), row.getOverlayBgColor(),
                    row.getOverlayX(), row.getOverlayY(),
                    row.getOverlaySize(),
                    row.isTextJustify(), row.getTextWidthPct(),
                    row.getHighlightText(), row.getHighlightColor(),
                    row.getTextShiftX(),
                    row.getSlideAudioDurationsMsList(), row.getSlideAudioFilesList(),
                    row.getAudioGapMs(), row.getAudioHlColor(), row.getAudioHlEffects(),
                    row.getSlideVideoOverlayFile(), row.getSlideVideoOverlayX(),
                    row.getSlideVideoOverlayY(), row.getSlideVideoOverlaySize(),
                    row.getSlideVideoOverlayDurationMs()));
        }
        if (slides.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Add at least one slide.", "No Slides", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return slides;
    }

    private static int computeSlideDuration(SlideData s, int baseDuration) {
        int dur = baseDuration;
        if (s.videoOverlayDurationMs > 0 && s.videoOverlayDurationMs > dur) {
            dur = s.videoOverlayDurationMs;
        }
        if (s.totalAudioDurationMs > 0 && s.totalAudioDurationMs > dur) {
            dur = s.totalAudioDurationMs;
        }
        return dur;
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
                    s.fxScanline, s.fxRaised,
                    s.overlayEnabled,
                    s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                    s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
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
                                int delay = (s.totalAudioDurationMs > 0) ? Math.max(s.totalAudioDurationMs, duration) : duration;
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
        // First dialog: choose export mode
        int modeChoice = JOptionPane.showOptionDialog(this,
                "How would you like to export?",
                "MP4 Export Mode", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, new String[]{"Normal Slideshow MP4", "Separate MP4 per Slide"}, "Normal Slideshow MP4");
        if (modeChoice < 0) return;

        if (modeChoice == 1) {
            createMp4PerSlide();
            return;
        }

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
        final String orientLabel = isPortrait() ? "portrait" : "landscape";

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

        String baseName = outFile.getName().replaceAll("(?i)\\.mp4$", "");
        // Limit to first 10 words
        String[] words = baseName.trim().split("\\s+");
        if (words.length > 10) {
            StringBuilder sb = new StringBuilder();
            for (int wi = 0; wi < 10; wi++) {
                if (wi > 0) sb.append(" ");
                sb.append(words[wi]);
            }
            baseName = sb.toString();
        }
        String parentDir = outFile.getParent() != null ? outFile.getParent() : ".";
        String timestamp = new java.text.SimpleDateFormat("mmss").format(new java.util.Date());

        final File finalOut = new File(parentDir, orientLabel + "-" + baseName + "-" + timestamp + ".mp4");

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

                    // Per-slide effective duration: each slide's overlay video overrides duration
                    int effectiveDuration = duration; // base duration for slides without overlay
                    int defaultFramesPerSlide = Math.max(1, (int) Math.round(effectiveDuration / 1000.0 * fps));
                    boolean useConcatDemuxer = false;
                    boolean usePipeEncoding = false;
                    File concatFile = null;

                    publish("Rendering " + slides.size() + " slides at " + videoW + "×" + videoH + "...");

                    int totalFrames = 0;
                    for (SlideData s : slides) {
                        int slideDur = computeSlideDuration(s, duration);
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
                                    s.fxScanline, s.fxRaised,
                                    s.overlayEnabled,
                                    s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                    s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures));
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
                            boolean hasAnim = s.fxGrain > 0 || s.fxWaterRipple > 0 || s.fxGlitch > 0 || s.fxShake > 0 || s.fxScanline > 0 || s.fxRaised > 0;
                            if (!hasAnim && s.slideTexts != null) {
                                for (SlideTextData stx : s.slideTexts) {
                                    if (stx.show && stx.odometer) { hasAnim = true; break; }
                                    if (stx.show && stx.textEffect != null) {
                                        String fx = stx.textEffect;
                                        if (fx.equals("Water Ripple") || fx.equals("Fire") || fx.equals("Ice")
                                                || fx.equals("Rainbow") || fx.equals("Typewriter")
                                                || fx.equals("Shake") || fx.equals("Pulse")) {
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
                            // Render one PNG per slide (or per audio segment for multi-audio) and use FFmpeg concat demuxer.
                            useConcatDemuxer = true;
                            concatFile = new File(tempDir, "concat.txt");
                            StringBuilder concatContent = new StringBuilder();
                            String lastConcatPng = null;

                            for (int i = 0; i < slides.size(); i++) {
                                SlideData s = slides.get(i);
                                int slideDur = computeSlideDuration(s, duration);

                                // Count valid audio files for this slide
                                int validAudioCount = 0;
                                for (int ai = 0; ai < s.audioFiles.size(); ai++) {
                                    File af = s.audioFiles.get(ai);
                                    int adur = ai < s.audioDurationsMs.size() ? s.audioDurationsMs.get(ai) : 0;
                                    if (af != null && af.exists() && adur > 0) validAudioCount++;
                                }

                                if (validAudioCount >= 2) {
                                    // MULTI-AUDIO: render one PNG per audio segment with active text highlighted
                                    int segIdx = 0;
                                    int audioTimeUsed = 0;
                                    int audioOrdinal = 0;
                                    // Pre-render a base (no highlight) frame for gaps
                                    BufferedImage gapFrame = null;
                                    if (s.audioGapMs > 0) {
                                        gapFrame = renderFrame(
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
                                                s.fxScanline, s.fxRaised,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                    }
                                    for (int ai = 0; ai < s.audioFiles.size(); ai++) {
                                        File af = s.audioFiles.get(ai);
                                        int adur = ai < s.audioDurationsMs.size() ? s.audioDurationsMs.get(ai) : 0;
                                        if (af == null || !af.exists() || adur <= 0) continue;

                                        // Insert gap segment between audios
                                        if (audioOrdinal > 0 && s.audioGapMs > 0 && gapFrame != null) {
                                            File gapFile = new File(tempDir, String.format("slide_%03d_gap%02d.png", i, segIdx));
                                            ImageIO.write(gapFrame, "png", gapFile);
                                            String gapPath = gapFile.getAbsolutePath().replace("'", "'\\''");
                                            concatContent.append("file '").append(gapPath).append("'\n");
                                            concatContent.append("duration ").append(String.format("%.3f", s.audioGapMs / 1000.0)).append("\n");
                                            lastConcatPng = gapPath;
                                            audioTimeUsed += s.audioGapMs;
                                            segIdx++;
                                        }

                                        List<SlideTextData> highlightedTexts = applyActiveTextHighlight(s.slideTexts, ai, s.audioHlColor, s.audioHlEffects, -1);
                                        BufferedImage frame = renderFrame(
                                                s.image, s.text, s.fontName, s.fontSize,
                                                s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                                videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                                s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                                s.slideNumberX, s.slideNumberY,
                                                s.slideNumberSize, s.slideNumberColor,
                                                highlightedTexts,
                                                s.fxRoundCorners, s.fxCornerRadius,
                                                s.fxVignette, s.fxSepia, s.fxGrain,
                                                s.fxWaterRipple, s.fxGlitch, s.fxShake,
                                                s.fxScanline, s.fxRaised,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);

                                        File slideFile = new File(tempDir, String.format("slide_%03d_seg%02d.png", i, segIdx));
                                        ImageIO.write(frame, "png", slideFile);

                                        double segDurSec = adur / 1000.0;
                                        String filePath = slideFile.getAbsolutePath().replace("'", "'\\''");
                                        concatContent.append("file '").append(filePath).append("'\n");
                                        concatContent.append("duration ").append(String.format("%.3f", segDurSec)).append("\n");
                                        lastConcatPng = filePath;
                                        audioTimeUsed += adur;
                                        audioOrdinal++;
                                        segIdx++;
                                    }
                                    // Render a clean frame (no highlight) for remaining time and final entry
                                    BufferedImage cleanFrame = renderFrame(
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
                                            s.fxScanline, s.fxRaised,
                                            s.overlayEnabled,
                                            s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                            s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                    File restFile = new File(tempDir, String.format("slide_%03d_rest.png", i));
                                    ImageIO.write(cleanFrame, "png", restFile);
                                    String cleanFilePath = restFile.getAbsolutePath().replace("'", "'\\''");
                                    if (slideDur > audioTimeUsed) {
                                        double restSec = (slideDur - audioTimeUsed) / 1000.0;
                                        concatContent.append("file '").append(cleanFilePath).append("'\n");
                                        concatContent.append("duration ").append(String.format("%.3f", restSec)).append("\n");
                                    }
                                    lastConcatPng = cleanFilePath;
                                } else {
                                    // SINGLE/NO AUDIO: original behavior — one PNG per slide
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
                                            s.fxScanline, s.fxRaised,
                                            s.overlayEnabled,
                                            s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                            s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);

                                    File slideFile = new File(tempDir, String.format("slide_%03d.png", i));
                                    ImageIO.write(frame, "png", slideFile);

                                    String filePath = slideFile.getAbsolutePath().replace("'", "'\\''");
                                    concatContent.append("file '").append(filePath).append("'\n");
                                    concatContent.append("duration ").append(String.format("%.3f", slideDurSec)).append("\n");
                                    lastConcatPng = filePath;
                                }

                                int pct = (int) ((i + 1.0) / slides.size() * 60);
                                final int p = pct;
                                SwingUtilities.invokeLater(() -> progressBar.setValue(p));
                                publish("Rendered slide " + (i + 1) + "/" + slides.size());
                            }
                            // Concat demuxer requires last file repeated without duration
                            if (lastConcatPng != null) {
                                concatContent.append("file '").append(lastConcatPng).append("'\n");
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
                                int slideDur = computeSlideDuration(s, duration);
                                int slideFrames = Math.max(1, (int) Math.round(slideDur / 1000.0 * fps));
                                boolean hasAnimatedFx = s.fxGrain > 0 || s.fxWaterRipple > 0 || s.fxGlitch > 0 || s.fxShake > 0 || s.fxScanline > 0 || s.fxRaised > 0;
                                boolean hasAnimatedText = false;
                                if (s.slideTexts != null) {
                                    for (SlideTextData stx : s.slideTexts) {
                                        if (stx.show && stx.odometer) { hasAnimatedText = true; break; }
                                        if (stx.show && stx.textEffect != null) {
                                            String fx = stx.textEffect;
                                            if (fx.equals("Water Ripple") || fx.equals("Fire") || fx.equals("Ice")
                                                    || fx.equals("Rainbow") || fx.equals("Typewriter")
                                                || fx.equals("Shake") || fx.equals("Pulse")) {
                                                hasAnimatedText = true;
                                                break;
                                            }
                                        }
                                    }
                                }

                                // Check for multi-audio (2+ valid audio files)
                                int vaCount = 0;
                                for (File af : s.audioFiles) { if (af != null && af.exists()) vaCount++; }
                                boolean hasMultiAudio = vaCount >= 2;

                                if (hasMultiAudio && (hasAnimatedFx || hasAnimatedText)) {
                                    // Multi-audio with animated effects: per-frame rendering with active text highlight
                                    publish("Rendering slide " + (i + 1) + " with " + slideFrames + " multi-audio animated frames...");
                                    for (int d = 0; d < slideFrames; d++) {
                                        long elapsedMs = (long)(d * 1000.0 / fps);
                                        int activeIdx = getActiveAudioTextIndex(s, elapsedMs);
                                        List<SlideTextData> hlTexts = applyActiveTextHighlight(s.slideTexts, activeIdx, s.audioHlColor, s.audioHlEffects, d);
                                        BufferedImage frame = renderFrame(
                                                s.image, s.text, s.fontName, s.fontSize,
                                                s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                                videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                                s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                                s.slideNumberX, s.slideNumberY,
                                                s.slideNumberSize, s.slideNumberColor,
                                                hlTexts,
                                                s.fxRoundCorners, s.fxCornerRadius,
                                                s.fxVignette, s.fxSepia, s.fxGrain,
                                                s.fxWaterRipple, s.fxGlitch, s.fxShake,
                                                s.fxScanline, s.fxRaised,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, d,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                        writeRawRGB(frame, videoW, videoH, rgbBytes, ffmpegStdin);
                                        frameIndex++;
                                    }
                                } else if (hasMultiAudio) {
                                    // Multi-audio, no animated effects: per-segment cached rendering
                                    publish("Rendering slide " + (i + 1) + " with " + vaCount + " audio segments...");
                                    int segFrameStart = 0;
                                    int animAudioOrd = 0;
                                    // Pre-render gap frame (no highlight) for gaps between audios
                                    BufferedImage animGapFrame = null;
                                    if (s.audioGapMs > 0) {
                                        animGapFrame = renderFrame(
                                                s.image, s.text, s.fontName, s.fontSize,
                                                s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                                videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                                s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                                s.slideNumberX, s.slideNumberY,
                                                s.slideNumberSize, s.slideNumberColor,
                                                s.slideTexts,
                                                s.fxRoundCorners, s.fxCornerRadius,
                                                s.fxVignette, s.fxSepia, 0, 0, 0, 0, 0, 0,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                    }
                                    for (int ai = 0; ai < s.audioFiles.size(); ai++) {
                                        File af = s.audioFiles.get(ai);
                                        int adur = ai < s.audioDurationsMs.size() ? s.audioDurationsMs.get(ai) : 0;
                                        if (af == null || !af.exists() || adur <= 0) continue;

                                        // Insert gap frames between audios
                                        if (animAudioOrd > 0 && s.audioGapMs > 0 && animGapFrame != null) {
                                            int gapFrameCount = Math.max(1, (int) Math.round(s.audioGapMs / 1000.0 * fps));
                                            writeRawRGB(animGapFrame, videoW, videoH, rgbBytes, ffmpegStdin);
                                            for (int gg = 1; gg < gapFrameCount; gg++) {
                                                ffmpegStdin.write(rgbBytes);
                                                frameIndex++;
                                            }
                                            frameIndex++;
                                            segFrameStart += gapFrameCount;
                                        }

                                        int segFrameCount = Math.max(1, (int) Math.round(adur / 1000.0 * fps));
                                        List<SlideTextData> hlTexts = applyActiveTextHighlight(s.slideTexts, ai, s.audioHlColor, s.audioHlEffects, -1);
                                        BufferedImage segFrame = renderFrame(
                                                s.image, s.text, s.fontName, s.fontSize,
                                                s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                                videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                                s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                                s.slideNumberX, s.slideNumberY,
                                                s.slideNumberSize, s.slideNumberColor,
                                                hlTexts,
                                                s.fxRoundCorners, s.fxCornerRadius,
                                                s.fxVignette, s.fxSepia, 0, 0, 0, 0, 0, 0,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                        writeRawRGB(segFrame, videoW, videoH, rgbBytes, ffmpegStdin);
                                        for (int dd = 1; dd < segFrameCount; dd++) {
                                            ffmpegStdin.write(rgbBytes);
                                            frameIndex++;
                                        }
                                        frameIndex++;
                                        segFrameStart += segFrameCount;
                                        animAudioOrd++;
                                    }
                                    int remainingFrames = Math.max(1, slideFrames - segFrameStart);
                                    // Render clean frame (no highlight) for remaining time after last audio
                                    BufferedImage restFrame = renderFrame(
                                            s.image, s.text, s.fontName, s.fontSize,
                                            s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                            videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                            s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                            s.slideNumberX, s.slideNumberY,
                                            s.slideNumberSize, s.slideNumberColor,
                                            s.slideTexts,
                                            s.fxRoundCorners, s.fxCornerRadius,
                                            s.fxVignette, s.fxSepia, 0, 0, 0, 0, 0, 0,
                                            s.overlayEnabled,
                                            s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                            s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                    writeRawRGB(restFrame, videoW, videoH, rgbBytes, ffmpegStdin);
                                    for (int dd = 1; dd < remainingFrames; dd++) {
                                        ffmpegStdin.write(rgbBytes);
                                        frameIndex++;
                                    }
                                    frameIndex++;
                                } else if (hasAnimatedFx && !hasAnimatedText) {
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
                                            s.fxVignette, s.fxSepia, 0, 0, 0, 0, 0, 0,  // zero out animated fx
                                            s.overlayEnabled,
                                            s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                            s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                    // Cache base pixel data for fast cloning
                                    int[] basePixels = baseFrame.getRGB(0, 0, videoW, videoH, null, 0, videoW);

                                    for (int d = 0; d < slideFrames; d++) {
                                        // Clone base pixels into frame
                                        BufferedImage animFrame = new BufferedImage(videoW, videoH, BufferedImage.TYPE_INT_RGB);
                                        animFrame.setRGB(0, 0, videoW, videoH, basePixels, 0, videoW);
                                        // Apply only animated effects
                                        applyAnimatedEffects(animFrame, videoW, videoH,
                                                s.fxWaterRipple, s.fxGlitch, s.fxGrain, s.fxShake, s.fxScanline, s.fxRaised, d);
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
                                                s.fxScanline, s.fxRaised,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, d,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
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
                                            s.fxVignette, s.fxSepia, 0, 0, 0, 0, 0, 0,
                                            s.overlayEnabled,
                                            s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                            s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
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
                        for (File af : s.audioFiles) {
                            if (af != null && af.exists()) {
                                hasSlideAudio = true;
                                break;
                            }
                        }
                        if (hasSlideAudio) break;
                    }

                    if (hasSlideAudio) {
                        publish("Merging slide audio tracks...");
                        mergedSlideAudio = new File(tempDir, "merged_slide_audio.m4a");

                        // Build ffmpeg filter_complex: delay each audio to its correct offset, then amix
                        java.util.List<String> mergeCmd = new java.util.ArrayList<>();
                        mergeCmd.add("ffmpeg");
                        mergeCmd.add("-y");

                        // Calculate each audio's start time and add inputs
                        int inputIdx = 0;
                        java.util.List<Integer> audioInputIndices = new java.util.ArrayList<>();
                        java.util.List<Long> audioDelays = new java.util.ArrayList<>();
                        long slideOffsetMs = 0;

                        for (SlideData s : slides) {
                            int slideDur = computeSlideDuration(s, duration);
                            // Each audio within the slide plays sequentially
                            long intraSlideOffset = 0;
                            int mergeAudioOrd = 0;
                            for (int ai = 0; ai < s.audioFiles.size(); ai++) {
                                File af = s.audioFiles.get(ai);
                                int adur = ai < s.audioDurationsMs.size() ? s.audioDurationsMs.get(ai) : 0;
                                if (af != null && af.exists() && adur > 0) {
                                    // Add gap before this audio (except the first)
                                    if (mergeAudioOrd > 0 && s.audioGapMs > 0) {
                                        intraSlideOffset += s.audioGapMs;
                                    }
                                    mergeCmd.add("-i");
                                    mergeCmd.add(af.getAbsolutePath());
                                    audioInputIndices.add(inputIdx);
                                    audioDelays.add(slideOffsetMs + intraSlideOffset);
                                    inputIdx++;
                                    intraSlideOffset += adur;
                                    mergeAudioOrd++;
                                }
                            }
                            slideOffsetMs += slideDur;
                        }

                        // Build filter_complex string
                        StringBuilder filterComplex = new StringBuilder();
                        for (int ai = 0; ai < audioInputIndices.size(); ai++) {
                            int idx = audioInputIndices.get(ai);
                            long delayMs = audioDelays.get(ai);
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

                    // Step 4: Apply per-slide video overlays if any
                    boolean anyVideoOverlay = false;
                    for (SlideData s : slides) {
                        if (s.videoOverlayFile != null && s.videoOverlayFile.exists()) {
                            anyVideoOverlay = true;
                            break;
                        }
                    }

                    if (anyVideoOverlay) {
                        publish("Applying per-slide video overlays...");

                        // Compute each slide's start time and duration in seconds
                        double[] slideStartSec = new double[slides.size()];
                        double[] slideDurSec = new double[slides.size()];
                        double timeOffset = 0;
                        for (int i = 0; i < slides.size(); i++) {
                            slideStartSec[i] = timeOffset;
                            slideDurSec[i] = computeSlideDuration(slides.get(i), duration) / 1000.0;
                            timeOffset += slideDurSec[i];
                        }

                        // Build FFmpeg command with all overlay inputs and time-gated filters
                        java.util.List<String> ovCmd = new java.util.ArrayList<>();
                        ovCmd.add("ffmpeg"); ovCmd.add("-y");
                        ovCmd.add("-i"); ovCmd.add(finalOut.getAbsolutePath());

                        // Add overlay video inputs and track which slides have overlays
                        java.util.List<Integer> ovSlideIdx = new java.util.ArrayList<>();
                        java.util.List<Integer> ovInputIdx = new java.util.ArrayList<>();
                        int ovInIdx = 1;
                        for (int i = 0; i < slides.size(); i++) {
                            SlideData s = slides.get(i);
                            if (s.videoOverlayFile != null && s.videoOverlayFile.exists()) {
                                ovCmd.add("-i"); ovCmd.add(s.videoOverlayFile.getAbsolutePath());
                                ovSlideIdx.add(i);
                                ovInputIdx.add(ovInIdx);
                                ovInIdx++;
                            }
                        }

                        // Build video filter chain: scale + time-gated overlay for each
                        StringBuilder vFilter = new StringBuilder();
                        String currentVid = "[0:v]";
                        for (int j = 0; j < ovSlideIdx.size(); j++) {
                            int si = ovSlideIdx.get(j);
                            int ii = ovInputIdx.get(j);
                            SlideData s = slides.get(si);

                            int ovW = (int)(videoW * s.videoOverlaySize / 100.0);
                            if (ovW % 2 != 0) ovW++;
                            int ovPxX = (int)(videoW * s.videoOverlayX / 100.0) - ovW / 2;
                            int ovPxY = (int)(videoH * s.videoOverlayY / 100.0);

                            double tStart = slideStartSec[si];
                            double tEnd = tStart + slideDurSec[si];
                            String scaledLbl = "[ov" + j + "]";
                            String outLbl = (j == ovSlideIdx.size() - 1) ? "[outv]" : "[tmp" + j + "]";

                            vFilter.append("[").append(ii).append(":v]scale=").append(ovW).append(":-2").append(scaledLbl).append(";");
                            vFilter.append(currentVid).append(scaledLbl).append("overlay=").append(ovPxX).append(":").append(ovPxY)
                                    .append(":enable='between(t,").append(String.format("%.3f", tStart)).append(",").append(String.format("%.3f", tEnd)).append(")'")
                                    .append(":eof_action=pass").append(outLbl).append(";");
                            currentVid = outLbl;
                        }
                        // Remove trailing semicolon
                        if (vFilter.length() > 0 && vFilter.charAt(vFilter.length() - 1) == ';') {
                            vFilter.setLength(vFilter.length() - 1);
                        }

                        // Check which overlay inputs have audio
                        java.util.List<Integer> ovAudioInputIdx = new java.util.ArrayList<>();
                        java.util.List<Double> ovAudioDelay = new java.util.ArrayList<>();
                        for (int j = 0; j < ovSlideIdx.size(); j++) {
                            int si = ovSlideIdx.get(j);
                            int ii = ovInputIdx.get(j);
                            SlideData s = slides.get(si);
                            // Probe if overlay video has audio
                            if (probeHasAudio(s.videoOverlayFile)) {
                                ovAudioInputIdx.add(ii);
                                ovAudioDelay.add(slideStartSec[si] * 1000.0);
                            }
                        }

                        // Build audio filter: mix base audio with overlay audio
                        boolean baseHasAudio = probeHasAudio(finalOut);
                        String audioMap = null;
                        if (baseHasAudio && !ovAudioInputIdx.isEmpty()) {
                            StringBuilder aFilter = new StringBuilder();
                            for (int j = 0; j < ovAudioInputIdx.size(); j++) {
                                int ii = ovAudioInputIdx.get(j);
                                long delayMs = Math.round(ovAudioDelay.get(j));
                                aFilter.append("[").append(ii).append(":a]adelay=").append(delayMs).append("|").append(delayMs)
                                        .append("[oa").append(j).append("];");
                            }
                            aFilter.append("[0:a]");
                            for (int j = 0; j < ovAudioInputIdx.size(); j++) {
                                aFilter.append("[oa").append(j).append("]");
                            }
                            aFilter.append("amix=inputs=").append(1 + ovAudioInputIdx.size())
                                    .append(":duration=first:dropout_transition=0[outa]");
                            vFilter.append(";").append(aFilter);
                            audioMap = "[outa]";
                        } else if (!baseHasAudio && !ovAudioInputIdx.isEmpty()) {
                            StringBuilder aFilter = new StringBuilder();
                            for (int j = 0; j < ovAudioInputIdx.size(); j++) {
                                int ii = ovAudioInputIdx.get(j);
                                long delayMs = Math.round(ovAudioDelay.get(j));
                                aFilter.append("[").append(ii).append(":a]adelay=").append(delayMs).append("|").append(delayMs)
                                        .append("[oa").append(j).append("];");
                            }
                            if (ovAudioInputIdx.size() > 1) {
                                for (int j = 0; j < ovAudioInputIdx.size(); j++) {
                                    aFilter.append("[oa").append(j).append("]");
                                }
                                aFilter.append("amix=inputs=").append(ovAudioInputIdx.size())
                                        .append(":duration=longest:dropout_transition=0[outa]");
                                audioMap = "[outa]";
                            } else {
                                audioMap = "[oa0]";
                            }
                            vFilter.append(";").append(aFilter);
                        } else if (baseHasAudio) {
                            audioMap = "0:a";
                        }

                        ovCmd.add("-filter_complex"); ovCmd.add(vFilter.toString());
                        ovCmd.add("-map"); ovCmd.add("[outv]");
                        if (audioMap != null) {
                            ovCmd.add("-map"); ovCmd.add(audioMap);
                        }
                        ovCmd.add("-c:v"); ovCmd.add("libx264");
                        ovCmd.add("-preset"); ovCmd.add("medium");
                        ovCmd.add("-crf"); ovCmd.add(String.valueOf(crf));
                        ovCmd.add("-pix_fmt"); ovCmd.add("yuv420p");
                        if (audioMap != null) {
                            ovCmd.add("-c:a"); ovCmd.add("aac");
                            ovCmd.add("-b:a"); ovCmd.add("192k");
                        }
                        ovCmd.add("-movflags"); ovCmd.add("+faststart");

                        File overlaidOut = new File(tempDir, "overlaid_final.mp4");
                        ovCmd.add(overlaidOut.getAbsolutePath());

                        publish("Encoding video with overlays...");
                        runFfmpeg(ovCmd);

                        if (overlaidOut.exists() && overlaidOut.length() > 0) {
                            finalOut.delete();
                            if (!overlaidOut.renameTo(finalOut)) {
                                java.nio.file.Files.copy(overlaidOut.toPath(), finalOut.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                overlaidOut.delete();
                            }
                        }
                    }

                    SwingUtilities.invokeLater(() -> progressBar.setValue(90));

                    SwingUtilities.invokeLater(() -> progressBar.setValue(100));

                    long fileSize = finalOut.length();
                    double sizeMB = fileSize / (1024.0 * 1024.0);
                    double totalDurationSec = 0;
                    for (SlideData s : slides) {
                        totalDurationSec += (computeSlideDuration(s, duration)) / 1000.0;
                    }

                    int slideAudioCount = 0;
                    for (SlideData s : slides) {
                        for (File af : s.audioFiles) {
                            if (af != null && af.exists()) slideAudioCount++;
                        }
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

                    int voCount = 0;
                    for (SlideData s : slides) {
                        if (s.videoOverlayFile != null && s.videoOverlayFile.exists()) voCount++;
                    }
                    String videoOverlayInfo = (voCount > 0)
                            ? "Video Overlays: " + voCount + " slide(s)\n"
                            : "";

                    finalInfo = String.format(
                            "✅ MP4 Video created successfully!\n\n" +
                                    "Resolution: %d×%d (%s)\n" +
                                    "Quality: CRF %d\n" +
                                    "Size: %.2f MB\n" +
                                    "Slides: %d (%d frames at %d fps)\n" +
                                    "Duration: %.1f seconds\n" +
                                    "%s%s%s\n" +
                                    "File: %s\n\n" +
                                    "Upload to Twitter/X for fullscreen playback!",
                            videoW, videoH, orientLabel, crf, sizeMB, slides.size(),
                            totalFrames, fps, totalDurationSec,
                            scrollInfo, videoOverlayInfo, audioInfo, finalOut.getAbsolutePath());

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

    // ==================== Per-Slide MP4 Export ====================

    private void createMp4PerSlide() {
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
        final String orientLabel = isPortrait() ? "portrait" : "landscape";

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

        // Choose output folder
        JFileChooser folderChooser = new JFileChooser();
        folderChooser.setDialogTitle("Select Output Folder for Slide Videos");
        folderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        folderChooser.setAcceptAllFileFilterUsed(false);
        if (folderChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        final File outFolder = folderChooser.getSelectedFile();
        if (!outFolder.exists()) outFolder.mkdirs();

        JDialog progressDialog = createProgressDialog("Exporting Slides as MP4...");
        JProgressBar progressBar = getProgressBar(progressDialog);
        JLabel progressLabel = getProgressLabel(progressDialog);

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            String errorMsg = null;
            String finalInfo = null;

            @Override
            protected Void doInBackground() {
                try {
                    int fps = 30;
                    int totalSlides = slides.size();
                    String perSlideTimestamp = new java.text.SimpleDateFormat("mmss").format(new java.util.Date());

                    for (int si = 0; si < totalSlides; si++) {
                        SlideData s = slides.get(si);
                        int slideDur = computeSlideDuration(s, duration);
                        int slideFrames = Math.max(1, (int) Math.round(slideDur / 1000.0 * fps));

                        String slideTextPart;
                        String slideTextName = null;
                        if (s.slideTexts != null) {
                            for (SlideTextData st : s.slideTexts) {
                                if (st.text != null && !st.text.trim().isEmpty()) {
                                    slideTextName = st.text.trim();
                                    break;
                                }
                            }
                        }
                        if (slideTextName == null && s.text != null && !s.text.trim().isEmpty()) {
                            slideTextName = s.text.trim();
                        }
                        if (slideTextName != null && !slideTextName.isEmpty()) {
                            String safeName = slideTextName.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "");
                            // Limit to first 10 words
                            String[] slideWords = safeName.trim().split("\\s+");
                            if (slideWords.length > 10) {
                                StringBuilder swb = new StringBuilder();
                                for (int wi = 0; wi < 10; wi++) {
                                    if (wi > 0) swb.append(" ");
                                    swb.append(slideWords[wi]);
                                }
                                safeName = swb.toString();
                            }
                            if (safeName.length() > 200) safeName = safeName.substring(0, 200);
                            if (safeName.isEmpty()) safeName = String.format("slide_%03d", si + 1);
                            slideTextPart = safeName;
                        } else {
                            slideTextPart = String.format("slide_%03d", si + 1);
                        }
                        String slideFileName = orientLabel + "-" + slideTextPart + "-" + perSlideTimestamp + ".mp4";
                        File slideOutFile = new File(outFolder, slideFileName);

                        publish("Exporting slide " + (si + 1) + "/" + totalSlides + "...");

                        File tempDir = new File(System.getProperty("java.io.tmpdir"),
                                "gifslideshow_perslide_" + System.currentTimeMillis());
                        if (!tempDir.mkdirs()) {
                            throw new IOException("Failed to create temp directory: " + tempDir);
                        }

                        try {
                            boolean hasAnimatedFx = s.fxGrain > 0 || s.fxWaterRipple > 0 || s.fxGlitch > 0 || s.fxShake > 0 || s.fxScanline > 0 || s.fxRaised > 0;
                            boolean hasAnimatedText = false;
                            if (s.slideTexts != null) {
                                for (SlideTextData stx : s.slideTexts) {
                                    if (stx.show && stx.odometer) { hasAnimatedText = true; break; }
                                    if (stx.show && stx.textEffect != null) {
                                        String fx = stx.textEffect;
                                        if (fx.equals("Water Ripple") || fx.equals("Fire") || fx.equals("Ice")
                                                || fx.equals("Rainbow") || fx.equals("Typewriter")
                                                || fx.equals("Shake") || fx.equals("Pulse")) {
                                            hasAnimatedText = true;
                                            break;
                                        }
                                    }
                                }
                            }

                            if (!hasAnimatedFx && !hasAnimatedText) {
                                // Static slide — use concat demuxer
                                // Check for multi-audio
                                int perSlideVaCount = 0;
                                for (File af : s.audioFiles) { if (af != null && af.exists()) perSlideVaCount++; }

                                File concatFile = new File(tempDir, "concat.txt");
                                if (perSlideVaCount >= 2) {
                                    // Multi-audio: render per-segment PNGs with active text highlighted
                                    StringBuilder concatContent = new StringBuilder();
                                    String lastFile = null;
                                    int segIdx = 0;
                                    int audioTimeUsed = 0;
                                    int psAudioOrdinal = 0;
                                    // Pre-render gap frame (no highlight) for gaps between audios
                                    BufferedImage psGapFrame = null;
                                    if (s.audioGapMs > 0) {
                                        psGapFrame = renderFrame(
                                                s.image, s.text, s.fontName, s.fontSize,
                                                s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                                videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                                s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                                s.slideNumberX, s.slideNumberY,
                                                s.slideNumberSize, s.slideNumberColor,
                                                s.slideTexts,
                                                s.fxRoundCorners, s.fxCornerRadius,
                                                s.fxVignette, s.fxSepia, 0, 0, 0, 0, 0, 0,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                    }
                                    for (int ai = 0; ai < s.audioFiles.size(); ai++) {
                                        File af = s.audioFiles.get(ai);
                                        int adur = ai < s.audioDurationsMs.size() ? s.audioDurationsMs.get(ai) : 0;
                                        if (af == null || !af.exists() || adur <= 0) continue;

                                        // Insert gap segment between audios
                                        if (psAudioOrdinal > 0 && s.audioGapMs > 0 && psGapFrame != null) {
                                            File gapFile = new File(tempDir, String.format("seg_gap%02d.png", segIdx));
                                            ImageIO.write(psGapFrame, "png", gapFile);
                                            String gp = gapFile.getAbsolutePath().replace("'", "'\\''");
                                            concatContent.append("file '").append(gp).append("'\n");
                                            concatContent.append("duration ").append(String.format("%.3f", s.audioGapMs / 1000.0)).append("\n");
                                            lastFile = gp;
                                            audioTimeUsed += s.audioGapMs;
                                            segIdx++;
                                        }

                                        List<SlideTextData> hlTexts = applyActiveTextHighlight(s.slideTexts, ai, s.audioHlColor, s.audioHlEffects, -1);
                                        BufferedImage frame = renderFrame(
                                                s.image, s.text, s.fontName, s.fontSize,
                                                s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                                videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                                s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                                s.slideNumberX, s.slideNumberY,
                                                s.slideNumberSize, s.slideNumberColor,
                                                hlTexts,
                                                s.fxRoundCorners, s.fxCornerRadius,
                                                s.fxVignette, s.fxSepia, 0, 0, 0, 0, 0, 0,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);

                                        File segFile = new File(tempDir, String.format("seg_%02d.png", segIdx));
                                        ImageIO.write(frame, "png", segFile);
                                        String fp = segFile.getAbsolutePath().replace("'", "'\\''");
                                        concatContent.append("file '").append(fp).append("'\n");
                                        concatContent.append("duration ").append(String.format("%.3f", adur / 1000.0)).append("\n");
                                        lastFile = fp;
                                        audioTimeUsed += adur;
                                        psAudioOrdinal++;
                                        segIdx++;
                                    }
                                    // Remaining time with no highlight
                                    // Render a clean frame (no highlight) for remaining time and final entry
                                    BufferedImage cleanFrame = renderFrame(
                                            s.image, s.text, s.fontName, s.fontSize,
                                            s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                            videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                            s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                            s.slideNumberX, s.slideNumberY,
                                            s.slideNumberSize, s.slideNumberColor,
                                            s.slideTexts,
                                            s.fxRoundCorners, s.fxCornerRadius,
                                            s.fxVignette, s.fxSepia, 0, 0, 0, 0, 0, 0,
                                            s.overlayEnabled,
                                            s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                            s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                    File restFile = new File(tempDir, "seg_rest.png");
                                    ImageIO.write(cleanFrame, "png", restFile);
                                    String cleanFp = restFile.getAbsolutePath().replace("'", "'\\''");
                                    if (slideDur > audioTimeUsed) {
                                        concatContent.append("file '").append(cleanFp).append("'\n");
                                        concatContent.append("duration ").append(String.format("%.3f", (slideDur - audioTimeUsed) / 1000.0)).append("\n");
                                    }
                                    // Last entry without duration — always use the clean (no highlight) frame
                                    concatContent.append("file '").append(cleanFp).append("'\n");
                                    try (java.io.FileWriter fw = new java.io.FileWriter(concatFile)) {
                                        fw.write(concatContent.toString());
                                    }
                                } else {
                                    // Single/no audio: one PNG
                                    BufferedImage frame = renderFrame(
                                            s.image, s.text, s.fontName, s.fontSize,
                                            s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                            videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                            s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                            s.slideNumberX, s.slideNumberY,
                                            s.slideNumberSize, s.slideNumberColor,
                                            s.slideTexts,
                                            s.fxRoundCorners, s.fxCornerRadius,
                                            s.fxVignette, s.fxSepia, 0, 0, 0, 0, 0, 0,
                                            s.overlayEnabled,
                                            s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                            s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);

                                    File imgFile = new File(tempDir, "slide.png");
                                    ImageIO.write(frame, "png", imgFile);

                                    double durSec = slideDur / 1000.0;
                                    try (java.io.FileWriter fw = new java.io.FileWriter(concatFile)) {
                                        fw.write("file '" + imgFile.getAbsolutePath().replace("'", "'\\''") + "'\n");
                                        fw.write("duration " + String.format("%.3f", durSec) + "\n");
                                        fw.write("file '" + imgFile.getAbsolutePath().replace("'", "'\\''") + "'\n");
                                    }
                                }

                                java.util.List<String> cmd = new java.util.ArrayList<>();
                                cmd.add("ffmpeg"); cmd.add("-y");
                                cmd.add("-f"); cmd.add("concat");
                                cmd.add("-safe"); cmd.add("0");
                                cmd.add("-i"); cmd.add(concatFile.getAbsolutePath());
                                cmd.add("-r"); cmd.add(String.valueOf(fps));
                                cmd.add("-c:v"); cmd.add("libx264");
                                cmd.add("-preset"); cmd.add("medium");
                                cmd.add("-threads"); cmd.add("0");
                                cmd.add("-crf"); cmd.add(String.valueOf(crf));
                                cmd.add("-pix_fmt"); cmd.add("yuv420p");
                                cmd.add("-movflags"); cmd.add("+faststart");

                                // Mux slide audio if present
                                File videoOnly = new File(tempDir, "video_only.mp4");
                                cmd.add(videoOnly.getAbsolutePath());
                                runFfmpeg(cmd);

                                File perSlideAudio = concatSlideAudios(s, tempDir);
                                if (perSlideAudio != null && perSlideAudio.exists()) {
                                    java.util.List<String> muxCmd = new java.util.ArrayList<>();
                                    muxCmd.add("ffmpeg"); muxCmd.add("-y");
                                    muxCmd.add("-i"); muxCmd.add(videoOnly.getAbsolutePath());
                                    muxCmd.add("-i"); muxCmd.add(perSlideAudio.getAbsolutePath());
                                    muxCmd.add("-c:v"); muxCmd.add("copy");
                                    muxCmd.add("-c:a"); muxCmd.add("aac");
                                    muxCmd.add("-b:a"); muxCmd.add("192k");
                                    muxCmd.add("-movflags"); muxCmd.add("+faststart");
                                    muxCmd.add(slideOutFile.getAbsolutePath());
                                    runFfmpeg(muxCmd);
                                } else {
                                    java.nio.file.Files.copy(videoOnly.toPath(), slideOutFile.toPath(),
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                }
                            } else {
                                // Animated slide — pipe frames to FFmpeg
                                File videoOnly = new File(tempDir, "video_only.mp4");
                                java.util.List<String> cmd = new java.util.ArrayList<>();
                                cmd.add("ffmpeg"); cmd.add("-y");
                                cmd.add("-f"); cmd.add("rawvideo");
                                cmd.add("-pixel_format"); cmd.add("rgb24");
                                cmd.add("-video_size"); cmd.add(videoW + "x" + videoH);
                                cmd.add("-framerate"); cmd.add(String.valueOf(fps));
                                cmd.add("-i"); cmd.add("-");
                                cmd.add("-c:v"); cmd.add("libx264");
                                cmd.add("-preset"); cmd.add("medium");
                                cmd.add("-threads"); cmd.add("0");
                                cmd.add("-crf"); cmd.add(String.valueOf(crf));
                                cmd.add("-pix_fmt"); cmd.add("yuv420p");
                                cmd.add("-movflags"); cmd.add("+faststart");
                                cmd.add(videoOnly.getAbsolutePath());

                                ProcessBuilder pb = new ProcessBuilder(cmd);
                                pb.redirectErrorStream(true);
                                Process proc = pb.start();
                                java.io.OutputStream ffmpegStdin = new java.io.BufferedOutputStream(proc.getOutputStream(), 1024 * 1024);

                                Thread convergenceReader = new Thread(() -> {
                                    try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                                        while (br.readLine() != null) {}
                                    } catch (IOException ignored) {}
                                });
                                convergenceReader.setDaemon(true);
                                convergenceReader.start();

                                byte[] rgbBytes = new byte[videoW * videoH * 3];

                                // Check for multi-audio in per-slide animated export
                                int perSlideAnimVaCount = 0;
                                for (File af : s.audioFiles) { if (af != null && af.exists()) perSlideAnimVaCount++; }
                                boolean perSlideMultiAudio = perSlideAnimVaCount >= 2;

                                if (perSlideMultiAudio) {
                                    // Multi-audio with animated effects: per-frame with active text highlight
                                    for (int d = 0; d < slideFrames; d++) {
                                        long elapsedMs = (long)(d * 1000.0 / fps);
                                        int activeIdx = getActiveAudioTextIndex(s, elapsedMs);
                                        List<SlideTextData> hlTexts = applyActiveTextHighlight(s.slideTexts, activeIdx, s.audioHlColor, s.audioHlEffects, d);
                                        BufferedImage frame = renderFrame(
                                                s.image, s.text, s.fontName, s.fontSize,
                                                s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                                videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                                s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                                s.slideNumberX, s.slideNumberY,
                                                s.slideNumberSize, s.slideNumberColor,
                                                hlTexts,
                                                s.fxRoundCorners, s.fxCornerRadius,
                                                s.fxVignette, s.fxSepia, s.fxGrain,
                                                s.fxWaterRipple, s.fxGlitch, s.fxShake,
                                                s.fxScanline, s.fxRaised,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, d,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                        writeRawRGB(frame, videoW, videoH, rgbBytes, ffmpegStdin);
                                    }
                                } else if (hasAnimatedFx && !hasAnimatedText) {
                                    // Render base once, apply only effects per-frame
                                    BufferedImage baseFrame = renderFrame(
                                            s.image, s.text, s.fontName, s.fontSize,
                                            s.fontStyle, s.fontColor, s.alignment, s.showPin,
                                            videoW, videoH, s.displayMode, s.subtitleY, s.subtitleBgOpacity,
                                            s.showSlideNumber, s.slideNumberText, s.slideNumberFontName,
                                            s.slideNumberX, s.slideNumberY,
                                            s.slideNumberSize, s.slideNumberColor,
                                            s.slideTexts,
                                            s.fxRoundCorners, s.fxCornerRadius,
                                            s.fxVignette, s.fxSepia, 0, 0, 0, 0, 0, 0,
                                            s.overlayEnabled,
                                            s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                            s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                    int[] basePixels = baseFrame.getRGB(0, 0, videoW, videoH, null, 0, videoW);

                                    for (int d = 0; d < slideFrames; d++) {
                                        BufferedImage animFrame = new BufferedImage(videoW, videoH, BufferedImage.TYPE_INT_RGB);
                                        animFrame.setRGB(0, 0, videoW, videoH, basePixels, 0, videoW);
                                        applyAnimatedEffects(animFrame, videoW, videoH,
                                                s.fxWaterRipple, s.fxGlitch, s.fxGrain, s.fxShake, s.fxScanline, s.fxRaised, d);
                                        writeRawRGB(animFrame, videoW, videoH, rgbBytes, ffmpegStdin);
                                    }
                                } else {
                                    // Full render per-frame
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
                                                s.fxScanline, s.fxRaised,
                                                s.overlayEnabled,
                                                s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, d,
                                                s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
                                        writeRawRGB(frame, videoW, videoH, rgbBytes, ffmpegStdin);
                                    }
                                }

                                ffmpegStdin.close();
                                int exitCode = proc.waitFor();
                                convergenceReader.join(5000);
                                if (exitCode != 0) throw new IOException("FFmpeg encoding failed for slide " + (si + 1));

                                File perSlideAudio2 = concatSlideAudios(s, tempDir);
                                if (perSlideAudio2 != null && perSlideAudio2.exists()) {
                                    java.util.List<String> muxCmd = new java.util.ArrayList<>();
                                    muxCmd.add("ffmpeg"); muxCmd.add("-y");
                                    muxCmd.add("-i"); muxCmd.add(videoOnly.getAbsolutePath());
                                    muxCmd.add("-i"); muxCmd.add(perSlideAudio2.getAbsolutePath());
                                    muxCmd.add("-c:v"); muxCmd.add("copy");
                                    muxCmd.add("-c:a"); muxCmd.add("aac");
                                    muxCmd.add("-b:a"); muxCmd.add("192k");
                                    muxCmd.add("-movflags"); muxCmd.add("+faststart");
                                    muxCmd.add(slideOutFile.getAbsolutePath());
                                    runFfmpeg(muxCmd);
                                } else {
                                    java.nio.file.Files.copy(videoOnly.toPath(), slideOutFile.toPath(),
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                }
                            }

                            // Apply per-slide video overlay if set
                            if (s.videoOverlayFile != null && s.videoOverlayFile.exists()
                                    && slideOutFile.exists()) {
                                publish("Applying video overlay to slide " + (si + 1) + "...");
                                applyVideoOverlay(slideOutFile, s.videoOverlayFile,
                                        videoW, videoH, s.videoOverlayX, s.videoOverlayY, s.videoOverlaySize, crf, tempDir);
                            }
                        } finally {
                            // Clean up temp directory
                            File[] tempFiles = tempDir.listFiles();
                            if (tempFiles != null) for (File tf : tempFiles) tf.delete();
                            tempDir.delete();
                        }

                        int pct = (int) ((si + 1.0) / totalSlides * 100);
                        final int p = pct;
                        SwingUtilities.invokeLater(() -> progressBar.setValue(p));
                    }

                    finalInfo = "Exported " + totalSlides + " slide(s) as " + orientLabel + " MP4 files to:\n" + outFolder.getAbsolutePath();
                } catch (Exception ex) {
                    errorMsg = ex.getMessage();
                    ex.printStackTrace();
                }
                return null;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                if (!chunks.isEmpty()) progressLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                if (errorMsg != null) {
                    JOptionPane.showMessageDialog(GifSlideShowApp.this,
                            "Error: " + errorMsg, "MP4 Export Error", JOptionPane.ERROR_MESSAGE);
                } else if (finalInfo != null) {
                    int open = JOptionPane.showConfirmDialog(GifSlideShowApp.this,
                            finalInfo + "\n\nOpen output folder?",
                            "MP4 Export Complete", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                    if (open == JOptionPane.YES_OPTION) {
                        try {
                            Desktop.getDesktop().open(outFolder);
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

    /** Run an FFmpeg command and throw IOException on failure. */
    private static void runFfmpeg(java.util.List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder log = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) log.append(line).append("\n");
        }
        int exit = proc.waitFor();
        if (exit != 0) {
            String lastLines = log.toString();
            if (lastLines.length() > 1500) lastLines = lastLines.substring(lastLines.length() - 1500);
            throw new IOException("FFmpeg failed (exit " + exit + "):\n" + lastLines);
        }
    }

    /**
     * Apply a video overlay on top of a base video file using FFmpeg.
     * The overlay video is scaled and positioned, and its audio is mixed with existing audio.
     * @param baseVideo the slideshow video file (will be replaced)
     * @param overlayVideo the overlay video file
     * @param videoW output video width
     * @param videoH output video height
     * @param voX overlay center X position (% of width)
     * @param voY overlay top Y position (% of height, max 75)
     * @param voSize overlay width (% of output width)
     * @param crf encoding quality
     * @param tempDir temporary directory for intermediate files
     */
    private static void applyVideoOverlay(File baseVideo, File overlayVideo,
                                          int videoW, int videoH,
                                          int voX, int voY, int voSize, int crf,
                                          File tempDir) throws IOException, InterruptedException {
        File preOverlay = new File(tempDir, "pre_vo_" + System.currentTimeMillis() + ".mp4");
        if (!baseVideo.renameTo(preOverlay)) {
            java.nio.file.Files.copy(baseVideo.toPath(), preOverlay.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            baseVideo.delete();
        }

        // Ensure even width for h264 compatibility
        int ovW = (int)(videoW * voSize / 100.0);
        if (ovW % 2 != 0) ovW++;
        int ovPxX = (int)(videoW * voX / 100.0) - ovW / 2;
        int ovPxY = (int)(videoH * voY / 100.0);

        // Build filter: scale overlay video, place it on base video
        // The overlay video plays normally frame-by-frame on top of the slideshow
        String videoFilter = "[1:v]scale=" + ovW + ":-2[ov];[0:v][ov]overlay=" + ovPxX + ":" + ovPxY + ":eof_action=pass[outv]";

        // Strategy 1: Both audio streams exist — mix them (overlay audio at full volume)
        {
            String filter = videoFilter + ";[0:a][1:a]amix=inputs=2:duration=first:dropout_transition=0[outa]";
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("ffmpeg"); cmd.add("-y");
            cmd.add("-i"); cmd.add(preOverlay.getAbsolutePath());
            cmd.add("-i"); cmd.add(overlayVideo.getAbsolutePath());
            cmd.add("-filter_complex"); cmd.add(filter);
            cmd.add("-map"); cmd.add("[outv]");
            cmd.add("-map"); cmd.add("[outa]");
            cmd.add("-c:v"); cmd.add("libx264");
            cmd.add("-preset"); cmd.add("medium");
            cmd.add("-crf"); cmd.add(String.valueOf(crf));
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add("192k");
            cmd.add("-pix_fmt"); cmd.add("yuv420p");
            cmd.add("-movflags"); cmd.add("+faststart");
            cmd.add(baseVideo.getAbsolutePath());
            try {
                runFfmpeg(cmd);
                preOverlay.delete();
                return;
            } catch (IOException ignored) {}
        }

        // Strategy 2: Base has no audio — use overlay video's audio directly
        {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("ffmpeg"); cmd.add("-y");
            cmd.add("-i"); cmd.add(preOverlay.getAbsolutePath());
            cmd.add("-i"); cmd.add(overlayVideo.getAbsolutePath());
            cmd.add("-filter_complex"); cmd.add(videoFilter);
            cmd.add("-map"); cmd.add("[outv]");
            cmd.add("-map"); cmd.add("1:a");
            cmd.add("-c:v"); cmd.add("libx264");
            cmd.add("-preset"); cmd.add("medium");
            cmd.add("-crf"); cmd.add(String.valueOf(crf));
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add("192k");
            cmd.add("-pix_fmt"); cmd.add("yuv420p");
            cmd.add("-movflags"); cmd.add("+faststart");
            cmd.add(baseVideo.getAbsolutePath());
            try {
                runFfmpeg(cmd);
                preOverlay.delete();
                return;
            } catch (IOException ignored) {}
        }

        // Strategy 3: No audio at all — video overlay only
        {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("ffmpeg"); cmd.add("-y");
            cmd.add("-i"); cmd.add(preOverlay.getAbsolutePath());
            cmd.add("-i"); cmd.add(overlayVideo.getAbsolutePath());
            cmd.add("-filter_complex"); cmd.add(videoFilter);
            cmd.add("-map"); cmd.add("[outv]");
            cmd.add("-an");
            cmd.add("-c:v"); cmd.add("libx264");
            cmd.add("-preset"); cmd.add("medium");
            cmd.add("-crf"); cmd.add(String.valueOf(crf));
            cmd.add("-pix_fmt"); cmd.add("yuv420p");
            cmd.add("-movflags"); cmd.add("+faststart");
            cmd.add(baseVideo.getAbsolutePath());
            runFfmpeg(cmd);
        }
        preOverlay.delete();
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
                                    s.fxScanline, s.fxRaised,
                                    s.overlayEnabled,
                                    s.overlayShape, s.overlayBgMode, s.overlayBgColor, s.overlayX, s.overlayY, s.overlaySize, 0,
                                    s.textJustify, s.textWidthPct, s.highlightText, s.highlightColor, s.textShiftX, s.slidePictures);
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
                                     int fxScanline, int fxRaised,
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

        if (fxScanline > 0) {
            double strength = fxScanline / 100.0;
            int[] px = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            double period = 6.0 - 4.0 * strength;
            double minBright = 0.6 - 0.45 * strength;
            double freq = 2.0 * Math.PI / period;
            for (int y = 0; y < targetH; y++) {
                double brightness = minBright + (1.0 - minBright) * (0.5 + 0.5 * Math.sin(freq * y));
                int rowOff = y * targetW;
                for (int x = 0; x < targetW; x++) {
                    int idx = rowOff + x;
                    int r = (int)(((px[idx] >> 16) & 0xFF) * brightness);
                    int gv = (int)(((px[idx] >> 8) & 0xFF) * brightness);
                    int b = (int)((px[idx] & 0xFF) * brightness);
                    px[idx] = (0xFF << 24) | (r << 16) | (gv << 8) | b;
                }
            }
            frame.setRGB(0, 0, targetW, targetH, px, 0, targetW);
        }

        if (fxRaised > 0) {
            double strength = fxRaised / 100.0;
            int[] px = frame.getRGB(0, 0, targetW, targetH, null, 0, targetW);
            int[] result = new int[px.length];
            double bevelAmount = 80 + 175 * strength;
            for (int y = 0; y < targetH; y++) {
                for (int x = 0; x < targetW; x++) {
                    int idx = y * targetW + x;
                    int c = px[idx];
                    int cr = (c >> 16) & 0xFF;
                    int cg = (c >> 8) & 0xFF;
                    int cb = c & 0xFF;
                    int lumC = (cr * 299 + cg * 587 + cb * 114) / 1000;
                    int tlIdx = (y > 0 && x > 0) ? (y - 1) * targetW + (x - 1) : idx;
                    int tl = px[tlIdx];
                    int lumTL = (((tl >> 16) & 0xFF) * 299 + ((tl >> 8) & 0xFF) * 587 + (tl & 0xFF) * 114) / 1000;
                    int brIdx = (y < targetH - 1 && x < targetW - 1) ? (y + 1) * targetW + (x + 1) : idx;
                    int br = px[brIdx];
                    int lumBR = (((br >> 16) & 0xFF) * 299 + ((br >> 8) & 0xFF) * 587 + (br & 0xFF) * 114) / 1000;
                    double slope = (lumTL - lumBR) / 255.0;
                    int adjustment = (int)(slope * bevelAmount);
                    int nr = Math.max(0, Math.min(255, cr + adjustment));
                    int ng = Math.max(0, Math.min(255, cg + adjustment));
                    int nb = Math.max(0, Math.min(255, cb + adjustment));
                    result[idx] = (0xFF << 24) | (nr << 16) | (ng << 8) | nb;
                }
            }
            frame.setRGB(0, 0, targetW, targetH, result, 0, targetW);
        }
    }

    /**
     * Concatenate all audio files for a slide (audio1, audio2, ...) into a single audio file.
     * Returns null if the slide has no audio files.
     */
    private static File concatSlideAudios(SlideData s, File tempDir) {
        java.util.List<File> validAudios = new java.util.ArrayList<>();
        for (File af : s.audioFiles) {
            if (af != null && af.exists()) validAudios.add(af);
        }
        if (validAudios.isEmpty()) return null;
        if (validAudios.size() == 1) return validAudios.get(0);
        try {
            // If gap is needed, use filter_complex with adelay+amix instead of concat
            if (s.audioGapMs > 0) {
                File outFile = new File(tempDir, "slide_audio_merged_" + System.nanoTime() + ".m4a");
                java.util.List<String> cmd = new java.util.ArrayList<>();
                cmd.add("ffmpeg"); cmd.add("-y");
                for (File af : validAudios) {
                    cmd.add("-i"); cmd.add(af.getAbsolutePath());
                }
                // Build filter: delay each audio by cumulative offset including gaps
                StringBuilder fc = new StringBuilder();
                long offset = 0;
                for (int ai = 0; ai < validAudios.size(); ai++) {
                    if (ai > 0) offset += s.audioGapMs;
                    fc.append("[").append(ai).append(":a]adelay=").append(offset).append("|").append(offset)
                            .append("[a").append(ai).append("];");
                    // Add this audio's duration for next offset
                    int adur = 0;
                    for (int si = 0; si < s.audioFiles.size(); si++) {
                        File af = s.audioFiles.get(si);
                        if (af != null && af.exists()) {
                            if (af.equals(validAudios.get(ai))) {
                                adur = si < s.audioDurationsMs.size() ? s.audioDurationsMs.get(si) : 0;
                                break;
                            }
                        }
                    }
                    offset += adur;
                }
                for (int ai = 0; ai < validAudios.size(); ai++) {
                    fc.append("[a").append(ai).append("]");
                }
                fc.append("amix=inputs=").append(validAudios.size())
                        .append(":duration=longest:dropout_transition=0[out]");
                cmd.add("-filter_complex"); cmd.add(fc.toString());
                cmd.add("-map"); cmd.add("[out]");
                cmd.add("-c:a"); cmd.add("aac");
                cmd.add("-b:a"); cmd.add("192k");
                cmd.add(outFile.getAbsolutePath());
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    while (br.readLine() != null) {}
                }
                int exit = proc.waitFor();
                if (exit == 0 && outFile.exists()) return outFile;
            }
            // No gap: simple concat
            File concatList = new File(tempDir, "audio_concat_" + System.nanoTime() + ".txt");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(concatList)) {
                for (File af : validAudios) {
                    pw.println("file '" + af.getAbsolutePath().replace("'", "'\\''") + "'");
                }
            }
            File outFile = new File(tempDir, "slide_audio_merged_" + System.nanoTime() + ".m4a");
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("ffmpeg"); cmd.add("-y");
            cmd.add("-f"); cmd.add("concat");
            cmd.add("-safe"); cmd.add("0");
            cmd.add("-i"); cmd.add(concatList.getAbsolutePath());
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add("192k");
            cmd.add(outFile.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                while (br.readLine() != null) {}
            }
            int exit = proc.waitFor();
            if (exit == 0 && outFile.exists()) return outFile;
        } catch (Exception e) { e.printStackTrace(); }
        return validAudios.get(0);
    }

    /**
     * Create a modified copy of slideTexts with the active text (by index) fully highlighted.
     * Supports multiple combinable effects: Glow, Enlarge, Bold, Underline, Color, Shake, Pulse.
     * @param animFrame frame index for animated effects (Shake/Pulse); use -1 for static rendering.
     */
    private static List<SlideTextData> applyActiveTextHighlight(
            List<SlideTextData> origTexts, int activeIndex,
            Color hlColor, String effects, int animFrame) {
        if (activeIndex < 0 || origTexts == null || origTexts.isEmpty()) return origTexts;
        java.util.Set<String> fx = new java.util.HashSet<>();
        if (effects != null && !effects.isEmpty()) {
            for (String e : effects.split(",")) fx.add(e.trim());
        }
        if (fx.isEmpty()) fx.add("Glow");
        if (hlColor == null) hlColor = new Color(255, 200, 50, 160);

        List<SlideTextData> result = new ArrayList<>();
        for (int i = 0; i < origTexts.size(); i++) {
            SlideTextData st = origTexts.get(i);
            if (i == activeIndex && st.show && st.text != null && !st.text.trim().isEmpty()) {
                String allText = st.text.replace("\n", ",").replace("\r", "");

                // Glow: highlight all text with glow style + boost highlight layers
                String useHlText = fx.contains("Glow") ? allText : st.highlightText;
                Color useHlColor = fx.contains("Glow") ? hlColor : st.highlightColor;
                String useHlStyle = fx.contains("Glow") ? "Glow" : st.highlightStyle;

                // Enlarge / Pulse: modify font size
                int fontSize = st.fontSize;
                if (fx.contains("Enlarge")) fontSize = (int)(fontSize * 1.30);
                if (fx.contains("Pulse")) {
                    double baseMul = fx.contains("Enlarge") ? 1.30 : 1.0;
                    if (animFrame >= 0) {
                        double pulse = 1.0 + 0.25 * Math.sin(animFrame * 0.3);
                        fontSize = (int)(st.fontSize * baseMul * pulse);
                    } else {
                        // Static path: apply a noticeable scale-up to indicate pulse is active
                        fontSize = (int)(st.fontSize * baseMul * 1.15);
                    }
                }

                // Bold
                int fontStyle = st.fontStyle;
                if (fx.contains("Bold")) fontStyle |= Font.BOLD;

                // Color: change text color to opaque version of highlight color
                Color textColor = st.color;
                if (fx.contains("Color")) {
                    textColor = new Color(hlColor.getRed(), hlColor.getGreen(), hlColor.getBlue(), 255);
                }

                // Underline
                String ulStyle = st.underlineStyle;
                String ulText = st.underlineText;
                if (fx.contains("Underline")) {
                    ulStyle = "Thick";
                    ulText = allText;
                }

                // Shake: offset x and y position for visible shaking
                int x = st.x;
                int y = st.y;
                if (fx.contains("Shake")) {
                    if (animFrame >= 0) {
                        x = st.x + (int)(6 * Math.sin(animFrame * 1.2));
                        y = st.y + (int)(4 * Math.cos(animFrame * 0.9 + 1.5));
                    } else {
                        // Static path: apply a fixed offset to show shake is active
                        x = st.x + 2;
                    }
                }

                result.add(new SlideTextData(st.show, st.text, st.fontName, fontSize,
                        fontStyle, textColor, x, y, st.bgOpacity, st.bgColor,
                        st.justify, st.widthPct, st.shiftX, st.alignment,
                        st.textEffect, st.textEffectIntensity,
                        useHlText, useHlColor, useHlStyle,
                        st.highlightTightness, ulStyle, ulText,
                        st.boldText, st.italicText, st.colorText, st.colorTextColor, st.xLeftAligned, st.odometer, st.odometerSpeed));
            } else {
                result.add(st);
            }
        }
        return result;
    }

    /**
     * Determine which audio segment (text index) is active at a given elapsed time within a slide.
     * Returns -1 if no audio is active at that time.
     */
    private static int getActiveAudioTextIndex(SlideData s, long elapsedMs) {
        if (s.audioDurationsMs == null || s.audioDurationsMs.isEmpty()) return -1;
        long cumulative = 0;
        int audioIdx = 0;
        for (int i = 0; i < s.audioDurationsMs.size(); i++) {
            int dur = s.audioDurationsMs.get(i);
            if (dur <= 0 || i >= s.audioFiles.size() || s.audioFiles.get(i) == null) {
                continue;
            }
            if (audioIdx > 0) cumulative += s.audioGapMs; // gap before this audio
            if (elapsedMs >= cumulative && elapsedMs < cumulative + dur) {
                return i;
            }
            cumulative += dur;
            audioIdx++;
        }
        return -1;
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

    private static boolean probeHasAudio(File file) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "quiet", "-select_streams", "a",
                    "-show_entries", "stream=codec_type",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line = br.readLine();
                proc.waitFor();
                return line != null && line.trim().equals("audio");
            }
        } catch (Exception e) {
            return false;
        }
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
        "Water Ripple", "Fire", "Ice", "Rainbow", "Typewriter", "Stone Engraving",
        "Shake", "Pulse"
    };

    static final String[] HIGHLIGHT_STYLES = { "Regular", "Brush", "Brush2", "Pill", "Gradient", "Glow", "Box" };
    static final String[] UNDERLINE_STYLES = { "None", "Straight", "Wavy", "Double", "Dotted", "Dashed", "Thick", "Zigzag" };

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
        final String highlightText;
        final Color highlightColor;
        final String highlightStyle;
        final int highlightTightness;
        final String underlineStyle;
        final String underlineText;
        final String boldText;
        final String italicText;
        final String colorText;
        final Color colorTextColor;
        final boolean xLeftAligned;
        final boolean odometer;
        final int odometerSpeed;

        SlideTextData(boolean show, String text, String fontName, int fontSize,
                      int fontStyle, Color color, int x, int y, int bgOpacity,
                      Color bgColor, boolean justify, int widthPct, int shiftX,
                      int alignment) {
            this(show, text, fontName, fontSize, fontStyle, color, x, y, bgOpacity,
                    bgColor, justify, widthPct, shiftX, alignment, "None", 50,
                    "", new Color(255, 100, 150, 180), "Regular", 50, "None", "",
                    "", "", "", null, false, false, 50);
        }

        SlideTextData(boolean show, String text, String fontName, int fontSize,
                      int fontStyle, Color color, int x, int y, int bgOpacity,
                      Color bgColor, boolean justify, int widthPct, int shiftX,
                      int alignment, String textEffect, int textEffectIntensity) {
            this(show, text, fontName, fontSize, fontStyle, color, x, y, bgOpacity,
                    bgColor, justify, widthPct, shiftX, alignment, textEffect, textEffectIntensity,
                    "", new Color(255, 100, 150, 180), "Regular", 50, "None", "",
                    "", "", "", null, false, false, 50);
        }

        SlideTextData(boolean show, String text, String fontName, int fontSize,
                      int fontStyle, Color color, int x, int y, int bgOpacity,
                      Color bgColor, boolean justify, int widthPct, int shiftX,
                      int alignment, String textEffect, int textEffectIntensity,
                      String highlightText, Color highlightColor, String highlightStyle) {
            this(show, text, fontName, fontSize, fontStyle, color, x, y, bgOpacity,
                    bgColor, justify, widthPct, shiftX, alignment, textEffect, textEffectIntensity,
                    highlightText, highlightColor, highlightStyle, 50, "None", "",
                    "", "", "", null, false, false, 50);
        }

        SlideTextData(boolean show, String text, String fontName, int fontSize,
                      int fontStyle, Color color, int x, int y, int bgOpacity,
                      Color bgColor, boolean justify, int widthPct, int shiftX,
                      int alignment, String textEffect, int textEffectIntensity,
                      String highlightText, Color highlightColor, String highlightStyle,
                      int highlightTightness, String underlineStyle) {
            this(show, text, fontName, fontSize, fontStyle, color, x, y, bgOpacity,
                    bgColor, justify, widthPct, shiftX, alignment, textEffect, textEffectIntensity,
                    highlightText, highlightColor, highlightStyle, highlightTightness, underlineStyle, "",
                    "", "", "", null, false, false, 50);
        }

        SlideTextData(boolean show, String text, String fontName, int fontSize,
                      int fontStyle, Color color, int x, int y, int bgOpacity,
                      Color bgColor, boolean justify, int widthPct, int shiftX,
                      int alignment, String textEffect, int textEffectIntensity,
                      String highlightText, Color highlightColor, String highlightStyle,
                      int highlightTightness, String underlineStyle, String underlineText) {
            this(show, text, fontName, fontSize, fontStyle, color, x, y, bgOpacity,
                    bgColor, justify, widthPct, shiftX, alignment, textEffect, textEffectIntensity,
                    highlightText, highlightColor, highlightStyle, highlightTightness, underlineStyle, underlineText,
                    "", "", "", null, false, false, 50);
        }

        SlideTextData(boolean show, String text, String fontName, int fontSize,
                      int fontStyle, Color color, int x, int y, int bgOpacity,
                      Color bgColor, boolean justify, int widthPct, int shiftX,
                      int alignment, String textEffect, int textEffectIntensity,
                      String highlightText, Color highlightColor, String highlightStyle,
                      int highlightTightness, String underlineStyle, String underlineText,
                      String boldText, String italicText, String colorText, Color colorTextColor) {
            this(show, text, fontName, fontSize, fontStyle, color, x, y, bgOpacity,
                    bgColor, justify, widthPct, shiftX, alignment, textEffect, textEffectIntensity,
                    highlightText, highlightColor, highlightStyle, highlightTightness, underlineStyle, underlineText,
                    boldText, italicText, colorText, colorTextColor, false, false, 50);
        }

        SlideTextData(boolean show, String text, String fontName, int fontSize,
                      int fontStyle, Color color, int x, int y, int bgOpacity,
                      Color bgColor, boolean justify, int widthPct, int shiftX,
                      int alignment, String textEffect, int textEffectIntensity,
                      String highlightText, Color highlightColor, String highlightStyle,
                      int highlightTightness, String underlineStyle, String underlineText,
                      String boldText, String italicText, String colorText, Color colorTextColor,
                      boolean xLeftAligned, boolean odometer, int odometerSpeed) {
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
            this.highlightText = highlightText != null ? highlightText : "";
            this.highlightColor = highlightColor != null ? highlightColor : new Color(255, 100, 150, 180);
            this.highlightStyle = highlightStyle != null ? highlightStyle : "Regular";
            this.highlightTightness = highlightTightness;
            this.underlineStyle = underlineStyle != null ? underlineStyle : "None";
            this.underlineText = underlineText != null ? underlineText : "";
            this.boldText = boldText != null ? boldText : "";
            this.italicText = italicText != null ? italicText : "";
            this.colorText = colorText != null ? colorText : "";
            this.colorTextColor = colorTextColor != null ? colorTextColor : new Color(255, 80, 80);
            this.xLeftAligned = xLeftAligned;
            this.odometer = odometer;
            this.odometerSpeed = odometerSpeed;
        }
    }

    // ==================== SlidePictureData ====================

    static class SlidePictureData {
        final boolean show;
        final transient BufferedImage image;
        final File imageFile;
        final int x;       // center X as % of frame width
        final int y;       // center Y as % of frame height
        final int widthPct; // picture width as % of frame width
        final String shape; // "Rectangle" or "Circle"
        final int cornerRadius; // corner radius for Rectangle (0 = sharp)

        SlidePictureData(boolean show, BufferedImage image, File imageFile,
                         int x, int y, int widthPct, String shape, int cornerRadius) {
            this.show = show;
            this.image = image;
            this.imageFile = imageFile;
            this.x = x;
            this.y = y;
            this.widthPct = widthPct;
            this.shape = shape != null ? shape : "Rectangle";
            this.cornerRadius = cornerRadius;
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
        final List<SlidePictureData> slidePictures;
        final boolean fxRoundCorners;
        final int fxCornerRadius;
        final int fxVignette;
        final int fxSepia;
        final int fxGrain;
        final int fxWaterRipple;
        final int fxGlitch;
        final int fxShake;
        final int fxScanline;
        final int fxRaised;
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
        final List<File> audioFiles;
        final List<Integer> audioDurationsMs;
        final int audioGapMs;
        final Color audioHlColor;
        final String audioHlEffects;
        final int totalAudioDurationMs;
        final File videoOverlayFile;
        final int videoOverlayX;
        final int videoOverlayY;
        final int videoOverlaySize;
        final int videoOverlayDurationMs;

        SlideData(BufferedImage image, String text, String fontName, int fontSize,
                  int fontStyle, Color fontColor, int alignment, boolean showPin, String displayMode,
                  int subtitleY, int subtitleBgOpacity,
                  boolean showSlideNumber, String slideNumberText, String slideNumberFontName,
                  int slideNumberX, int slideNumberY,
                  int slideNumberSize, Color slideNumberColor,
                  List<SlideTextData> slideTexts,
                  List<SlidePictureData> slidePictures,
                  boolean fxRoundCorners, int fxCornerRadius,
                  int fxVignette, int fxSepia, int fxGrain,
                  int fxWaterRipple, int fxGlitch, int fxShake,
                  int fxScanline, int fxRaised,
                  boolean overlayEnabled,
                  String overlayShape, String overlayBgMode, Color overlayBgColor,
                  int overlayX, int overlayY, int overlaySize,
                  boolean textJustify, int textWidthPct,
                  String highlightText, Color highlightColor,
                  int textShiftX,
                  List<Integer> audioDurationsMs, List<File> audioFiles,
                  int audioGapMs, Color audioHlColor, String audioHlEffects,
                  File videoOverlayFile, int videoOverlayX, int videoOverlayY,
                  int videoOverlaySize, int videoOverlayDurationMs) {
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
            this.slidePictures = slidePictures != null ? slidePictures : new ArrayList<>();
            this.fxRoundCorners = fxRoundCorners;
            this.fxCornerRadius = fxCornerRadius;
            this.fxVignette = fxVignette;
            this.fxSepia = fxSepia;
            this.fxGrain = fxGrain;
            this.fxWaterRipple = fxWaterRipple;
            this.fxGlitch = fxGlitch;
            this.fxShake = fxShake;
            this.fxScanline = fxScanline;
            this.fxRaised = fxRaised;
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
            this.audioFiles = audioFiles != null ? audioFiles : new java.util.ArrayList<>();
            this.audioDurationsMs = audioDurationsMs != null ? audioDurationsMs : new java.util.ArrayList<>();
            this.audioGapMs = audioGapMs;
            this.audioHlColor = audioHlColor != null ? audioHlColor : new Color(255, 200, 50, 160);
            this.audioHlEffects = audioHlEffects != null ? audioHlEffects : "Glow";
            int totalMs = 0;
            int numValid = 0;
            for (int d : this.audioDurationsMs) { if (d > 0) { totalMs += d; numValid++; } }
            if (numValid > 1 && audioGapMs > 0) totalMs += (numValid - 1) * audioGapMs;
            this.totalAudioDurationMs = totalMs;
            this.videoOverlayFile = videoOverlayFile;
            this.videoOverlayX = videoOverlayX;
            this.videoOverlayY = videoOverlayY;
            this.videoOverlaySize = videoOverlaySize;
            this.videoOverlayDurationMs = videoOverlayDurationMs;
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
        private final JCheckBox slideTextOdometerCheck;
        private final JSpinner slideTextOdometerSpeedSpinner;
        private final JTextField slideTextHighlightField;
        private final JButton slideTextHighlightColorBtn;
        private Color slideTextHighlightColor = new Color(255, 100, 150, 180);
        private final JComboBox<String> slideTextHighlightStyleCombo;
        private final JSpinner slideTextHighlightTightnessSpinner;
        private final JComboBox<String> slideTextUnderlineCombo;
        private final JTextField slideTextUnderlineTextField;
        private final JTextField slideTextBoldField;
        private final JTextField slideTextItalicField;
        private final JTextField slideTextColorTextField;
        private final JButton slideTextColorTextColorBtn;
        private Color slideTextColorTextColor = new Color(255, 80, 80);
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
        private final JCheckBox fxScanlineCheck;
        private final JSpinner fxScanlineSpinner;
        private final JCheckBox fxRaisedCheck;
        private final JSpinner fxRaisedSpinner;
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

        private final java.util.Map<Integer, File> slideAudioFiles = new java.util.HashMap<>();
        private final java.util.Map<Integer, Integer> slideAudioDurationsMs = new java.util.HashMap<>();
        private final JButton audioBtn;
        private final JLabel audioFileLabel;
        private final JLabel audioDurationLabel;
        private final JButton audioClearBtn;
        private final JLabel audioLabel;
        // Audio highlight effect controls
        private final JSpinner audioGapSpinner;
        private final JButton audioHlColorBtn;
        private Color audioHlColor = new Color(255, 200, 50, 160);
        private final JToggleButton audioFxGlow, audioFxEnlarge, audioFxBold,
                audioFxUnderline, audioFxColor, audioFxShake, audioFxPulse;

        // Slide picture overlay items
        private final List<SlidePictureData> slidePictureItems = new ArrayList<>();
        private int currentSlidePictureIndex = 0;
        private boolean isLoadingSlidePicture = false;
        private final JComboBox<String> slidePicSelector;
        private final JCheckBox slidePicShowCheck;
        private final JLabel slidePicPreviewLabel;
        private BufferedImage slidePicLoadedImage;
        private File slidePicLoadedFile;
        private final JSpinner slidePicXSpinner;
        private final JSpinner slidePicYSpinner;
        private final JSpinner slidePicWidthSpinner;
        private final JComboBox<String> slidePicShapeCombo;
        private final JSpinner slidePicCornerSpinner;

        private File slideVideoOverlayFile;
        private int slideVideoOverlayDurationMs = -1;
        private final JButton videoOverlayBtn;
        private final JLabel videoOverlayFileLbl;
        private final JLabel videoOverlayDurLbl;
        private final JButton videoOverlayClearButton;
        private final JSpinner videoOverlayXSp;
        private final JSpinner videoOverlayYSp;
        private final JSpinner videoOverlaySizeSp;








        SlideRow(int number) {
            panel = new JPanel(new BorderLayout(10, 0));
            panel.setBackground(new Color(44, 47, 51));
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(60, 63, 68), 1, true),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)));
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 680));
            panel.setPreferredSize(new Dimension(1100, 660));

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
            JPanel toolbar4d = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            toolbar4d.setBackground(new Color(50, 95, 60));

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
                    updateAudioUI();
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
                int removedIdx = currentSlideTextIndex;
                slideTextItems.remove(removedIdx);
                // Remove audio for the deleted text and shift higher indices down
                slideAudioFiles.remove(removedIdx);
                slideAudioDurationsMs.remove(removedIdx);
                java.util.Map<Integer, File> shiftedFiles = new java.util.HashMap<>();
                java.util.Map<Integer, Integer> shiftedDurations = new java.util.HashMap<>();
                for (java.util.Map.Entry<Integer, File> entry : slideAudioFiles.entrySet()) {
                    int key = entry.getKey();
                    shiftedFiles.put(key > removedIdx ? key - 1 : key, entry.getValue());
                }
                for (java.util.Map.Entry<Integer, Integer> entry : slideAudioDurationsMs.entrySet()) {
                    int key = entry.getKey();
                    shiftedDurations.put(key > removedIdx ? key - 1 : key, entry.getValue());
                }
                slideAudioFiles.clear();
                slideAudioFiles.putAll(shiftedFiles);
                slideAudioDurationsMs.clear();
                slideAudioDurationsMs.putAll(shiftedDurations);
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
                updateAudioUI();
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

            slideTextOdometerCheck = new JCheckBox("Odometer");
            slideTextOdometerCheck.setFont(new Font("Segoe UI", Font.BOLD, 11));
            slideTextOdometerCheck.setForeground(new Color(255, 200, 100));
            slideTextOdometerCheck.setBackground(new Color(50, 95, 60));
            slideTextOdometerCheck.setToolTipText("Odometer reveal — characters roll through random letters before landing on the correct one");
            slideTextOdometerCheck.addActionListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextOdometerSpeedSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 5));
            slideTextOdometerSpeedSpinner.setPreferredSize(new Dimension(50, 24));
            slideTextOdometerSpeedSpinner.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextOdometerSpeedSpinner.setToolTipText("Odometer speed (0=very slow, 50=normal, 100=very fast)");
            slideTextOdometerSpeedSpinner.addChangeListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextHighlightField = new JTextField(8);
            slideTextHighlightField.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextHighlightField.setPreferredSize(new Dimension(80, 24));
            slideTextHighlightField.setToolTipText("Words to highlight (comma-separated for multiple, e.g. hello,world)");
            slideTextHighlightField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void removeUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void changedUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
            });

            slideTextHighlightColorBtn = new JButton("\u25A0");
            slideTextHighlightColorBtn.setForeground(slideTextHighlightColor);
            slideTextHighlightColorBtn.setFont(new Font("Segoe UI", Font.PLAIN, 16));
            slideTextHighlightColorBtn.setPreferredSize(new Dimension(32, 24));
            slideTextHighlightColorBtn.setFocusPainted(false);
            slideTextHighlightColorBtn.setToolTipText("Highlight color");
            slideTextHighlightColorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(panel, "Slide Text Highlight Color", slideTextHighlightColor);
                if (c != null) {
                    slideTextHighlightColor = new Color(c.getRed(), c.getGreen(), c.getBlue(), 180);
                    slideTextHighlightColorBtn.setForeground(slideTextHighlightColor);
                    onFormatChanged();
                }
            });

            slideTextHighlightStyleCombo = new JComboBox<>(HIGHLIGHT_STYLES);
            slideTextHighlightStyleCombo.setPreferredSize(new Dimension(80, 24));
            slideTextHighlightStyleCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextHighlightStyleCombo.setToolTipText("Highlight style");
            slideTextHighlightStyleCombo.addActionListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextHighlightTightnessSpinner = new JSpinner(new SpinnerNumberModel(50, -50, 100, 5));
            slideTextHighlightTightnessSpinner.setPreferredSize(new Dimension(52, 24));
            slideTextHighlightTightnessSpinner.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextHighlightTightnessSpinner.setToolTipText("Highlight tightness (-50=overlap/shrink, 0=tight, 50=normal, 100=loose)");
            slideTextHighlightTightnessSpinner.addChangeListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextUnderlineCombo = new JComboBox<>(UNDERLINE_STYLES);
            slideTextUnderlineCombo.setPreferredSize(new Dimension(80, 24));
            slideTextUnderlineCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextUnderlineCombo.setToolTipText("Underline style for matched text");
            slideTextUnderlineCombo.addActionListener(e -> { if (!isLoadingSlideText) onFormatChanged(); });

            slideTextUnderlineTextField = new JTextField(8);
            slideTextUnderlineTextField.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextUnderlineTextField.setPreferredSize(new Dimension(80, 24));
            slideTextUnderlineTextField.setToolTipText("Words to underline, comma-separated (leave empty to use highlight text)");
            slideTextUnderlineTextField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void removeUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void changedUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
            });

            slideTextBoldField = new JTextField(8);
            slideTextBoldField.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextBoldField.setPreferredSize(new Dimension(80, 24));
            slideTextBoldField.setToolTipText("Words to make bold, comma-separated");
            slideTextBoldField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void removeUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void changedUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
            });

            slideTextItalicField = new JTextField(8);
            slideTextItalicField.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextItalicField.setPreferredSize(new Dimension(80, 24));
            slideTextItalicField.setToolTipText("Words to make italic, comma-separated");
            slideTextItalicField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void removeUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void changedUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
            });

            slideTextColorTextField = new JTextField(8);
            slideTextColorTextField.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slideTextColorTextField.setPreferredSize(new Dimension(80, 24));
            slideTextColorTextField.setToolTipText("Words to colorize, comma-separated");
            slideTextColorTextField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void removeUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
                @Override public void changedUpdate(DocumentEvent e) { if (!isLoadingSlideText) onFormatChanged(); }
            });

            slideTextColorTextColorBtn = new JButton("\u25A0");
            slideTextColorTextColorBtn.setForeground(slideTextColorTextColor);
            slideTextColorTextColorBtn.setFont(new Font("Segoe UI", Font.PLAIN, 16));
            slideTextColorTextColorBtn.setPreferredSize(new Dimension(32, 24));
            slideTextColorTextColorBtn.setFocusPainted(false);
            slideTextColorTextColorBtn.setToolTipText("Word color");
            slideTextColorTextColorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(panel, "Word Color", slideTextColorTextColor);
                if (c != null) {
                    slideTextColorTextColor = c;
                    slideTextColorTextColorBtn.setForeground(c);
                    onFormatChanged();
                }
            });

            JLabel tc4cAlignLbl = styledLabel("      \u2B82 Align:");
            tc4cAlignLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4cAlignLbl.setForeground(new Color(140, 210, 160));
            JLabel tc4cEffectLbl = styledLabel("  \u2728 Effect:");
            tc4cEffectLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4cEffectLbl.setForeground(new Color(140, 210, 160));
            JLabel tc4cPowerLbl = styledLabel("Power:");
            tc4cPowerLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4cPowerLbl.setForeground(new Color(140, 210, 160));
            JLabel tc4cHlLbl = styledLabel("  \uD83D\uDD8D HL:");
            tc4cHlLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4cHlLbl.setForeground(new Color(140, 210, 160));
            JLabel tc4cTightLbl = styledLabel("Tight:");
            tc4cTightLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4cTightLbl.setForeground(new Color(140, 210, 160));
            JLabel tc4cUlLbl = styledLabel("UL:");
            tc4cUlLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4cUlLbl.setForeground(new Color(140, 210, 160));

            toolbar4c.add(tc4cAlignLbl);
            toolbar4c.add(slideTextAlignCombo);
            toolbar4c.add(tc4cEffectLbl);
            toolbar4c.add(slideTextEffectCombo);
            toolbar4c.add(tc4cPowerLbl);
            toolbar4c.add(slideTextEffectIntensitySpinner);
            toolbar4c.add(tc4cHlLbl);
            toolbar4c.add(slideTextHighlightField);
            toolbar4c.add(slideTextHighlightColorBtn);
            toolbar4c.add(slideTextHighlightStyleCombo);
            toolbar4c.add(tc4cTightLbl);
            toolbar4c.add(slideTextHighlightTightnessSpinner);
            toolbar4c.add(tc4cUlLbl);
            toolbar4c.add(slideTextUnderlineCombo);
            toolbar4c.add(slideTextUnderlineTextField);

            // --- Odometer toolbar row ---
            JPanel toolbar4c2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            toolbar4c2.setBackground(new Color(50, 95, 60));
            JLabel tc4c2OdoLbl = styledLabel("      \u2699 Odometer:");
            tc4c2OdoLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4c2OdoLbl.setForeground(new Color(255, 200, 100));
            JLabel tc4c2SpdLbl = styledLabel("Speed:");
            tc4c2SpdLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4c2SpdLbl.setForeground(new Color(255, 200, 100));
            JLabel tc4c2HintLbl = styledLabel("(0=slow roll, 50=normal, 100=instant)");
            tc4c2HintLbl.setFont(new Font("Segoe UI", Font.ITALIC, 10));
            tc4c2HintLbl.setForeground(new Color(180, 180, 140));
            toolbar4c2.add(tc4c2OdoLbl);
            toolbar4c2.add(slideTextOdometerCheck);
            toolbar4c2.add(tc4c2SpdLbl);
            toolbar4c2.add(slideTextOdometerSpeedSpinner);
            toolbar4c2.add(tc4c2HintLbl);

            JLabel tc4dBLbl = styledLabel("      \uD835\uDC01 Bold:");
            tc4dBLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4dBLbl.setForeground(new Color(140, 210, 160));
            JLabel tc4dILbl = styledLabel("\uD835\uDC3C Italic:");
            tc4dILbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4dILbl.setForeground(new Color(140, 210, 160));
            JLabel tc4dClrLbl = styledLabel("\uD83C\uDFA8 Color:");
            tc4dClrLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4dClrLbl.setForeground(new Color(140, 210, 160));

            toolbar4d.add(tc4dBLbl);
            toolbar4d.add(slideTextBoldField);
            toolbar4d.add(tc4dILbl);
            toolbar4d.add(slideTextItalicField);
            toolbar4d.add(tc4dClrLbl);
            toolbar4d.add(slideTextColorTextField);
            toolbar4d.add(slideTextColorTextColorBtn);

            // ===== Toolbar Row 4e: Slide Pictures =====
            JPanel toolbar4e = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            toolbar4e.setBackground(new Color(45, 80, 95));

            slidePictureItems.add(new SlidePictureData(false, null, null, 50, 50, 20, "Rectangle", 0));

            slidePicSelector = new JComboBox<>(new String[]{"Pic 1"});
            slidePicSelector.setPreferredSize(new Dimension(65, 24));
            slidePicSelector.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slidePicSelector.setToolTipText("Select which slide picture to edit");
            slidePicSelector.addActionListener(e -> {
                if (isLoadingSlidePicture) return;
                int newIndex = slidePicSelector.getSelectedIndex();
                if (newIndex >= 0 && newIndex < slidePictureItems.size()) {
                    saveCurrentSlidePictureToItem();
                    currentSlidePictureIndex = newIndex;
                    loadSlidePictureFromItem(currentSlidePictureIndex);
                }
            });

            JButton addSlidePicBtn = new JButton("+");
            addSlidePicBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
            addSlidePicBtn.setPreferredSize(new Dimension(28, 24));
            addSlidePicBtn.setFocusPainted(false);
            addSlidePicBtn.setToolTipText("Add another slide picture overlay");
            addSlidePicBtn.addActionListener(e -> {
                saveCurrentSlidePictureToItem();
                slidePictureItems.add(new SlidePictureData(false, null, null, 50, 50, 20, "Rectangle", 0));
                currentSlidePictureIndex = slidePictureItems.size() - 1;
                isLoadingSlidePicture = true;
                try {
                    rebuildSlidePicSelector();
                    slidePicSelector.setSelectedIndex(currentSlidePictureIndex);
                } finally {
                    isLoadingSlidePicture = false;
                }
                loadSlidePictureFromItem(currentSlidePictureIndex);
                onFormatChanged();
            });

            JButton removeSlidePicBtn = new JButton("\u2212");
            removeSlidePicBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
            removeSlidePicBtn.setPreferredSize(new Dimension(28, 24));
            removeSlidePicBtn.setFocusPainted(false);
            removeSlidePicBtn.setToolTipText("Remove current slide picture overlay");
            removeSlidePicBtn.addActionListener(e -> {
                if (slidePictureItems.size() <= 1) return;
                slidePictureItems.remove(currentSlidePictureIndex);
                if (currentSlidePictureIndex >= slidePictureItems.size()) {
                    currentSlidePictureIndex = slidePictureItems.size() - 1;
                }
                isLoadingSlidePicture = true;
                try {
                    rebuildSlidePicSelector();
                    slidePicSelector.setSelectedIndex(currentSlidePictureIndex);
                } finally {
                    isLoadingSlidePicture = false;
                }
                loadSlidePictureFromItem(currentSlidePictureIndex);
                onFormatChanged();
            });

            slidePicShowCheck = new JCheckBox("Slide Pic", false);
            slidePicShowCheck.setFont(new Font("Segoe UI", Font.BOLD, 11));
            slidePicShowCheck.setForeground(new Color(100, 200, 220));
            slidePicShowCheck.setBackground(new Color(44, 47, 51));
            slidePicShowCheck.setFocusPainted(false);
            slidePicShowCheck.addActionListener(e -> { if (!isLoadingSlidePicture) onFormatChanged(); });

            slidePicPreviewLabel = new JLabel("No image");
            slidePicPreviewLabel.setPreferredSize(new Dimension(48, 36));
            slidePicPreviewLabel.setBorder(BorderFactory.createLineBorder(new Color(80, 130, 150)));
            slidePicPreviewLabel.setHorizontalAlignment(SwingConstants.CENTER);
            slidePicPreviewLabel.setForeground(Color.GRAY);
            slidePicPreviewLabel.setFont(new Font("Segoe UI", Font.PLAIN, 9));
            slidePicPreviewLabel.setToolTipText("Click to browse or drag an image");

            JButton slidePicBrowseBtn = new JButton("Browse");
            slidePicBrowseBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slidePicBrowseBtn.setPreferredSize(new Dimension(65, 24));
            slidePicBrowseBtn.setFocusPainted(false);
            slidePicBrowseBtn.setToolTipText("Browse for a picture to overlay on slide");
            slidePicBrowseBtn.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "Images", "jpg", "jpeg", "png", "gif", "bmp", "webp", "avif", "heif", "heic"));
                if (fc.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                    try {
                        File f = fc.getSelectedFile();
                        BufferedImage img = loadImageFile(f);
                        if (img != null) {
                            slidePicLoadedImage = img;
                            slidePicLoadedFile = f;
                            updateSlidePicPreview();
                            slidePicShowCheck.setSelected(true);
                            onFormatChanged();
                        }
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(panel, "Failed to load image: " + ex.getMessage());
                    }
                }
            });

            JButton slidePicClearBtn = new JButton("\u2715");
            slidePicClearBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slidePicClearBtn.setPreferredSize(new Dimension(28, 24));
            slidePicClearBtn.setFocusPainted(false);
            slidePicClearBtn.setToolTipText("Remove picture from this slot");
            slidePicClearBtn.addActionListener(e -> {
                slidePicLoadedImage = null;
                slidePicLoadedFile = null;
                slidePicPreviewLabel.setIcon(null);
                slidePicPreviewLabel.setText("No image");
                slidePicShowCheck.setSelected(false);
                onFormatChanged();
            });

            slidePicXSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
            slidePicXSpinner.setPreferredSize(new Dimension(50, 24));
            slidePicXSpinner.setToolTipText("Picture center X position (% of width)");
            slidePicXSpinner.addChangeListener(e -> { if (!isLoadingSlidePicture) onFormatChanged(); });

            slidePicYSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
            slidePicYSpinner.setPreferredSize(new Dimension(50, 24));
            slidePicYSpinner.setToolTipText("Picture center Y position (% of height)");
            slidePicYSpinner.addChangeListener(e -> { if (!isLoadingSlidePicture) onFormatChanged(); });

            slidePicWidthSpinner = new JSpinner(new SpinnerNumberModel(20, 1, 100, 1));
            slidePicWidthSpinner.setPreferredSize(new Dimension(50, 24));
            slidePicWidthSpinner.setToolTipText("Picture width (% of frame width)");
            slidePicWidthSpinner.addChangeListener(e -> { if (!isLoadingSlidePicture) onFormatChanged(); });

            slidePicShapeCombo = new JComboBox<>(new String[]{"Rectangle", "Circle"});
            slidePicShapeCombo.setPreferredSize(new Dimension(85, 24));
            slidePicShapeCombo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            slidePicShapeCombo.setToolTipText("Picture shape: Rectangle or Circle");
            slidePicShapeCombo.addActionListener(e -> { if (!isLoadingSlidePicture) onFormatChanged(); });

            slidePicCornerSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 200, 5));
            slidePicCornerSpinner.setPreferredSize(new Dimension(50, 24));
            slidePicCornerSpinner.setToolTipText("Corner radius for rectangular pictures (0 = sharp corners)");
            slidePicCornerSpinner.addChangeListener(e -> { if (!isLoadingSlidePicture) onFormatChanged(); });

            JLabel tc4ePicLbl = styledLabel("\uD83D\uDDBC Pic:");
            tc4ePicLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            tc4ePicLbl.setForeground(new Color(100, 200, 220));

            toolbar4e.add(slidePicSelector);
            toolbar4e.add(addSlidePicBtn);
            toolbar4e.add(removeSlidePicBtn);
            toolbar4e.add(slidePicShowCheck);
            toolbar4e.add(slidePicPreviewLabel);
            toolbar4e.add(slidePicBrowseBtn);
            toolbar4e.add(slidePicClearBtn);
            toolbar4e.add(styledLabel("X%:"));
            toolbar4e.add(slidePicXSpinner);
            toolbar4e.add(styledLabel("Y%:"));
            toolbar4e.add(slidePicYSpinner);
            toolbar4e.add(styledLabel("W%:"));
            toolbar4e.add(slidePicWidthSpinner);
            toolbar4e.add(styledLabel("Shape:"));
            toolbar4e.add(slidePicShapeCombo);
            toolbar4e.add(styledLabel("Radius:"));
            toolbar4e.add(slidePicCornerSpinner);

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

            fxScanlineCheck = new JCheckBox("Scanline", false);
            fxScanlineCheck.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fxScanlineCheck.setForeground(Color.LIGHT_GRAY);
            fxScanlineCheck.setBackground(new Color(44, 47, 51));
            fxScanlineCheck.setFocusPainted(false);
            fxScanlineCheck.addActionListener(e -> onFormatChanged());

            fxScanlineSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 100, 5));
            fxScanlineSpinner.setPreferredSize(new Dimension(45, 24));
            fxScanlineSpinner.setToolTipText("CRT scanline intensity %");
            fxScanlineSpinner.addChangeListener(e -> onFormatChanged());

            fxRaisedCheck = new JCheckBox("Raised", false);
            fxRaisedCheck.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fxRaisedCheck.setForeground(Color.LIGHT_GRAY);
            fxRaisedCheck.setBackground(new Color(44, 47, 51));
            fxRaisedCheck.setFocusPainted(false);
            fxRaisedCheck.addActionListener(e -> onFormatChanged());

            fxRaisedSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 100, 5));
            fxRaisedSpinner.setPreferredSize(new Dimension(45, 24));
            fxRaisedSpinner.setToolTipText("Raised 3D pop-out intensity %");
            fxRaisedSpinner.addChangeListener(e -> onFormatChanged());

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
            toolbar5c.add(fxScanlineCheck);
            toolbar5c.add(fxScanlineSpinner);

            // Row 5d: Raised
            JPanel toolbar5d = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
            toolbar5d.setBackground(new Color(110, 55, 60));
            toolbar5d.add(styledLabel("      "));
            toolbar5d.add(fxRaisedCheck);
            toolbar5d.add(fxRaisedSpinner);

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
            JPanel toolbar7 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
            toolbar7.setBackground(new Color(28, 38, 56));
            toolbar7.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(55, 75, 110)),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));

            audioLabel = styledLabel("\uD83C\uDFB5 Audio (Text 1):");
            audioLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
            audioLabel.setForeground(new Color(100, 180, 255));

            audioBtn = new JButton("\uD83D\uDCC2 Browse");
            audioBtn.setFont(new Font("Segoe UI", Font.BOLD, 11));
            audioBtn.setPreferredSize(new Dimension(95, 26));
            audioBtn.setFocusPainted(false);
            audioBtn.setBackground(new Color(45, 100, 170));
            audioBtn.setForeground(Color.WHITE);
            audioBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 130, 200), 1),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
            audioBtn.setToolTipText("Attach audio to this slide (duration overrides global slide duration)");
            audioBtn.addActionListener(e -> browseSlideAudio());
            audioBtn.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

            audioFileLabel = new JLabel("No audio");
            audioFileLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
            audioFileLabel.setForeground(new Color(120, 125, 145));
            audioFileLabel.setPreferredSize(new Dimension(160, 22));

            audioDurationLabel = new JLabel("");
            audioDurationLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
            audioDurationLabel.setForeground(new Color(80, 210, 140));

            audioClearBtn = new JButton("\u2716");
            audioClearBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
            audioClearBtn.setPreferredSize(new Dimension(32, 26));
            audioClearBtn.setFocusPainted(false);
            audioClearBtn.setBackground(new Color(160, 45, 45));
            audioClearBtn.setForeground(new Color(255, 200, 200));
            audioClearBtn.setBorder(BorderFactory.createLineBorder(new Color(200, 70, 70), 1));
            audioClearBtn.setToolTipText("Remove audio from this slide");
            audioClearBtn.setVisible(false);
            audioClearBtn.addActionListener(e -> clearSlideAudio());
            audioClearBtn.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

            toolbar7.add(audioLabel);
            toolbar7.add(audioBtn);
            toolbar7.add(audioFileLabel);
            toolbar7.add(audioDurationLabel);
            toolbar7.add(audioClearBtn);

            // ===== Toolbar Row 7b: Audio Highlight Effects =====
            JPanel toolbar7b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            toolbar7b.setBackground(new Color(35, 42, 58));
            toolbar7b.setBorder(BorderFactory.createEmptyBorder(1, 4, 2, 4));

            audioGapSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 10.0, 0.1));
            audioGapSpinner.setPreferredSize(new Dimension(58, 26));
            audioGapSpinner.setFont(new Font("Segoe UI", Font.BOLD, 11));
            audioGapSpinner.setToolTipText("Silence gap between sequential audios (seconds)");
            audioGapSpinner.addChangeListener(e -> onFormatChanged());

            audioHlColorBtn = new JButton("\u25A0");
            audioHlColorBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
            audioHlColorBtn.setForeground(audioHlColor);
            audioHlColorBtn.setPreferredSize(new Dimension(32, 26));
            audioHlColorBtn.setFocusPainted(false);
            audioHlColorBtn.setBackground(new Color(50, 55, 70));
            audioHlColorBtn.setBorder(BorderFactory.createLineBorder(new Color(80, 85, 100), 1));
            audioHlColorBtn.setToolTipText("Audio highlight color");
            audioHlColorBtn.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            audioHlColorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(panel, "Audio Highlight Color", audioHlColor);
                if (c != null) {
                    audioHlColor = new Color(c.getRed(), c.getGreen(), c.getBlue(), 160);
                    audioHlColorBtn.setForeground(audioHlColor);
                }
            });

            // Professional styled toggle buttons with clear selected/unselected states
            Font fxBtnFont = new Font("Segoe UI", Font.BOLD, 10);
            Color fxBtnOffBg = new Color(50, 55, 68);
            Color fxBtnOffFg = new Color(140, 145, 160);
            Color fxBtnOnBg = new Color(55, 120, 200);
            Color fxBtnOnFg = Color.WHITE;
            Color fxBtnBorder = new Color(70, 75, 90);
            Color fxBtnOnBorder = new Color(80, 150, 230);

            java.awt.event.ItemListener fxToggleStyler = evt -> {
                JToggleButton btn = (JToggleButton) evt.getSource();
                if (btn.isSelected()) {
                    btn.setBackground(fxBtnOnBg);
                    btn.setForeground(fxBtnOnFg);
                    btn.setBorder(BorderFactory.createLineBorder(fxBtnOnBorder, 1));
                } else {
                    btn.setBackground(fxBtnOffBg);
                    btn.setForeground(fxBtnOffFg);
                    btn.setBorder(BorderFactory.createLineBorder(fxBtnBorder, 1));
                }
            };

            audioFxGlow = new JToggleButton("Glow", true);
            audioFxGlow.setFont(fxBtnFont); audioFxGlow.setPreferredSize(new Dimension(52, 24));
            audioFxGlow.setFocusPainted(false); audioFxGlow.setToolTipText("Background glow highlight on active text");
            audioFxGlow.setBackground(fxBtnOnBg); audioFxGlow.setForeground(fxBtnOnFg);
            audioFxGlow.setBorder(BorderFactory.createLineBorder(fxBtnOnBorder, 1));
            audioFxGlow.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            audioFxGlow.addItemListener(fxToggleStyler);

            audioFxEnlarge = new JToggleButton("Big");
            audioFxEnlarge.setFont(fxBtnFont); audioFxEnlarge.setPreferredSize(new Dimension(42, 24));
            audioFxEnlarge.setFocusPainted(false); audioFxEnlarge.setToolTipText("Enlarge active text by 25%");
            audioFxEnlarge.setBackground(fxBtnOffBg); audioFxEnlarge.setForeground(fxBtnOffFg);
            audioFxEnlarge.setBorder(BorderFactory.createLineBorder(fxBtnBorder, 1));
            audioFxEnlarge.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            audioFxEnlarge.addItemListener(fxToggleStyler);

            audioFxBold = new JToggleButton("Bold");
            audioFxBold.setFont(fxBtnFont); audioFxBold.setPreferredSize(new Dimension(48, 24));
            audioFxBold.setFocusPainted(false); audioFxBold.setToolTipText("Make active text bold");
            audioFxBold.setBackground(fxBtnOffBg); audioFxBold.setForeground(fxBtnOffFg);
            audioFxBold.setBorder(BorderFactory.createLineBorder(fxBtnBorder, 1));
            audioFxBold.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            audioFxBold.addItemListener(fxToggleStyler);

            audioFxUnderline = new JToggleButton("UL");
            audioFxUnderline.setFont(fxBtnFont); audioFxUnderline.setPreferredSize(new Dimension(36, 24));
            audioFxUnderline.setFocusPainted(false); audioFxUnderline.setToolTipText("Underline active text");
            audioFxUnderline.setBackground(fxBtnOffBg); audioFxUnderline.setForeground(fxBtnOffFg);
            audioFxUnderline.setBorder(BorderFactory.createLineBorder(fxBtnBorder, 1));
            audioFxUnderline.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            audioFxUnderline.addItemListener(fxToggleStyler);

            audioFxColor = new JToggleButton("Clr");
            audioFxColor.setFont(fxBtnFont); audioFxColor.setPreferredSize(new Dimension(38, 24));
            audioFxColor.setFocusPainted(false); audioFxColor.setToolTipText("Change active text color to highlight color");
            audioFxColor.setBackground(fxBtnOffBg); audioFxColor.setForeground(fxBtnOffFg);
            audioFxColor.setBorder(BorderFactory.createLineBorder(fxBtnBorder, 1));
            audioFxColor.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            audioFxColor.addItemListener(fxToggleStyler);

            audioFxShake = new JToggleButton("Shake");
            audioFxShake.setFont(fxBtnFont); audioFxShake.setPreferredSize(new Dimension(52, 24));
            audioFxShake.setFocusPainted(false); audioFxShake.setToolTipText("Shake active text (animated video only)");
            audioFxShake.setBackground(fxBtnOffBg); audioFxShake.setForeground(fxBtnOffFg);
            audioFxShake.setBorder(BorderFactory.createLineBorder(fxBtnBorder, 1));
            audioFxShake.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            audioFxShake.addItemListener(fxToggleStyler);

            audioFxPulse = new JToggleButton("Pulse");
            audioFxPulse.setFont(fxBtnFont); audioFxPulse.setPreferredSize(new Dimension(52, 24));
            audioFxPulse.setFocusPainted(false); audioFxPulse.setToolTipText("Pulse active text size (animated video only)");
            audioFxPulse.setBackground(fxBtnOffBg); audioFxPulse.setForeground(fxBtnOffFg);
            audioFxPulse.setBorder(BorderFactory.createLineBorder(fxBtnBorder, 1));
            audioFxPulse.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            audioFxPulse.addItemListener(fxToggleStyler);

            JLabel gapLbl = styledLabel("  \u23F1 Gap:");
            gapLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            gapLbl.setForeground(new Color(140, 170, 220));
            JLabel fxLbl = styledLabel("  \u2728 FX:");
            fxLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            fxLbl.setForeground(new Color(140, 170, 220));
            JLabel secLbl = styledLabel("s");
            secLbl.setForeground(new Color(140, 170, 220));
            JLabel hlLbl = styledLabel("  \uD83C\uDFA8 HL:");
            hlLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            hlLbl.setForeground(new Color(140, 170, 220));

            toolbar7b.add(styledLabel("   "));
            toolbar7b.add(gapLbl);
            toolbar7b.add(audioGapSpinner);
            toolbar7b.add(secLbl);
            toolbar7b.add(hlLbl);
            toolbar7b.add(audioHlColorBtn);
            toolbar7b.add(fxLbl);
            toolbar7b.add(audioFxGlow);
            toolbar7b.add(audioFxEnlarge);
            toolbar7b.add(audioFxBold);
            toolbar7b.add(audioFxUnderline);
            toolbar7b.add(audioFxColor);
            toolbar7b.add(audioFxShake);
            toolbar7b.add(audioFxPulse);

            // ===== Toolbar Row 8: Per-Slide Video Overlay =====
            JPanel toolbar8 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
            toolbar8.setBackground(new Color(42, 32, 22));
            toolbar8.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(90, 70, 45)),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));

            JLabel voLabel8 = styledLabel("\uD83C\uDFA5 Video Overlay:");
            voLabel8.setFont(new Font("Segoe UI", Font.BOLD, 12));
            voLabel8.setForeground(new Color(255, 185, 60));

            videoOverlayBtn = new JButton("\uD83D\uDCC2 Browse");
            videoOverlayBtn.setFont(new Font("Segoe UI", Font.BOLD, 11));
            videoOverlayBtn.setPreferredSize(new Dimension(95, 26));
            videoOverlayBtn.setFocusPainted(false);
            videoOverlayBtn.setBackground(new Color(130, 90, 35));
            videoOverlayBtn.setForeground(new Color(255, 240, 210));
            videoOverlayBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(170, 120, 50), 1),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
            videoOverlayBtn.setToolTipText("Upload a video to overlay on this slide (plays fully, overrides slide duration)");
            videoOverlayBtn.addActionListener(e -> browseSlideVideoOverlay());
            videoOverlayBtn.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

            videoOverlayFileLbl = new JLabel("No video");
            videoOverlayFileLbl.setFont(new Font("Segoe UI", Font.ITALIC, 11));
            videoOverlayFileLbl.setForeground(new Color(120, 110, 95));
            videoOverlayFileLbl.setPreferredSize(new Dimension(120, 22));

            videoOverlayDurLbl = new JLabel("");
            videoOverlayDurLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
            videoOverlayDurLbl.setForeground(new Color(240, 190, 90));

            videoOverlayClearButton = new JButton("\u2716");
            videoOverlayClearButton.setFont(new Font("Segoe UI", Font.BOLD, 12));
            videoOverlayClearButton.setPreferredSize(new Dimension(32, 26));
            videoOverlayClearButton.setFocusPainted(false);
            videoOverlayClearButton.setBackground(new Color(160, 45, 45));
            videoOverlayClearButton.setForeground(new Color(255, 200, 200));
            videoOverlayClearButton.setBorder(BorderFactory.createLineBorder(new Color(200, 70, 70), 1));
            videoOverlayClearButton.setToolTipText("Remove video overlay from this slide");
            videoOverlayClearButton.setVisible(false);
            videoOverlayClearButton.addActionListener(e -> clearSlideVideoOverlay());
            videoOverlayClearButton.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

            JLabel voX8 = styledLabel("X%:");
            voX8.setFont(new Font("Segoe UI", Font.BOLD, 11));
            voX8.setForeground(new Color(200, 170, 120));
            videoOverlayXSp = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
            videoOverlayXSp.setPreferredSize(new Dimension(52, 26));
            videoOverlayXSp.setFont(new Font("Segoe UI", Font.BOLD, 11));
            videoOverlayXSp.setToolTipText("Horizontal center position (% of video width)");
            videoOverlayXSp.addChangeListener(e -> onFormatChanged());

            JLabel voY8 = styledLabel("Y%:");
            voY8.setFont(new Font("Segoe UI", Font.BOLD, 11));
            voY8.setForeground(new Color(200, 170, 120));
            videoOverlayYSp = new JSpinner(new SpinnerNumberModel(25, 0, 75, 1));
            videoOverlayYSp.setPreferredSize(new Dimension(52, 26));
            videoOverlayYSp.setFont(new Font("Segoe UI", Font.BOLD, 11));
            videoOverlayYSp.setToolTipText("Vertical center position (% of video height, max 75%)");
            videoOverlayYSp.addChangeListener(e -> onFormatChanged());

            JLabel voSize8 = styledLabel("Size%:");
            voSize8.setFont(new Font("Segoe UI", Font.BOLD, 11));
            voSize8.setForeground(new Color(200, 170, 120));
            videoOverlaySizeSp = new JSpinner(new SpinnerNumberModel(30, 5, 100, 5));
            videoOverlaySizeSp.setPreferredSize(new Dimension(52, 26));
            videoOverlaySizeSp.setFont(new Font("Segoe UI", Font.BOLD, 11));
            videoOverlaySizeSp.setToolTipText("Size of overlay video (% of output width)");
            videoOverlaySizeSp.addChangeListener(e -> onFormatChanged());

            toolbar8.add(voLabel8);
            toolbar8.add(videoOverlayBtn);
            toolbar8.add(videoOverlayFileLbl);
            toolbar8.add(videoOverlayDurLbl);
            toolbar8.add(videoOverlayClearButton);
            toolbar8.add(voX8);
            toolbar8.add(videoOverlayXSp);
            toolbar8.add(voY8);
            toolbar8.add(videoOverlayYSp);
            toolbar8.add(voSize8);
            toolbar8.add(videoOverlaySizeSp);

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
            toolbarsPanel.add(toolbar4c2);
            toolbarsPanel.add(toolbar4d);
            toolbarsPanel.add(toolbar4e);
            toolbarsPanel.add(createToolbarSeparator());
            toolbarsPanel.add(toolbar5a);
            toolbarsPanel.add(toolbar5b);
            toolbarsPanel.add(toolbar5c);
            toolbarsPanel.add(toolbar5d);
            toolbarsPanel.add(createToolbarSeparator());
            toolbarsPanel.add(toolbar6a);
            toolbarsPanel.add(toolbar6b);
            toolbarsPanel.add(createToolbarSeparator());
            toolbarsPanel.add(toolbar7);
            toolbarsPanel.add(toolbar7b);
            toolbarsPanel.add(createToolbarSeparator());
            toolbarsPanel.add(toolbar8);

            rightPanel.add(toolbarsPanel, BorderLayout.NORTH);
            rightPanel.add(textScroll, BorderLayout.CENTER);

            JPanel westPanel = new JPanel(new BorderLayout(4, 0));
            westPanel.setBackground(new Color(44, 47, 51));
            centerPanel.setPreferredSize(new Dimension(500, 0));
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
            boolean prevXLeftAligned = currentSlideTextIndex < slideTextItems.size()
                    ? slideTextItems.get(currentSlideTextIndex).xLeftAligned : false;
            slideTextItems.set(currentSlideTextIndex, new SlideTextData(
                    slideTextCheckBox.isSelected(), slideTextArea.getText(),
                    (String) slideTextFontCombo.getSelectedItem(), (int) slideTextSizeSpinner.getValue(),
                    fontStyle, slideTextColor,
                    (int) slideTextXSpinner.getValue(), (int) slideTextYSpinner.getValue(),
                    (int) slideTextBgSpinner.getValue(), slideTextBgColor,
                    slideTextJustifyCheck.isSelected(), (int) slideTextWidthSpinner.getValue(),
                    (int) slideTextShiftXSpinner.getValue(), alignment,
                    (String) slideTextEffectCombo.getSelectedItem(),
                    (int) slideTextEffectIntensitySpinner.getValue(),
                    slideTextHighlightField.getText(), slideTextHighlightColor,
                    (String) slideTextHighlightStyleCombo.getSelectedItem(),
                    (int) slideTextHighlightTightnessSpinner.getValue(),
                    (String) slideTextUnderlineCombo.getSelectedItem(),
                    slideTextUnderlineTextField.getText(),
                    slideTextBoldField.getText(), slideTextItalicField.getText(),
                    slideTextColorTextField.getText(), slideTextColorTextColor, prevXLeftAligned,
                    slideTextOdometerCheck.isSelected(),
                    (int) slideTextOdometerSpeedSpinner.getValue()));
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
                slideTextHighlightField.setText(item.highlightText);
                slideTextHighlightColor = item.highlightColor;
                slideTextHighlightColorBtn.setForeground(item.highlightColor);
                slideTextHighlightStyleCombo.setSelectedItem(item.highlightStyle);
                slideTextHighlightTightnessSpinner.setValue(item.highlightTightness);
                slideTextUnderlineCombo.setSelectedItem(item.underlineStyle);
                slideTextUnderlineTextField.setText(item.underlineText);
                slideTextBoldField.setText(item.boldText);
                slideTextItalicField.setText(item.italicText);
                slideTextColorTextField.setText(item.colorText);
                slideTextColorTextColor = item.colorTextColor;
                slideTextColorTextColorBtn.setForeground(item.colorTextColor);
                slideTextOdometerCheck.setSelected(item.odometer);
                slideTextOdometerSpeedSpinner.setValue(item.odometerSpeed);
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
            // Ensure we have at least as many items as the source.
            // Do NOT remove extra items — this slide may have more text items
            // (e.g. from dictionary import) than the first slide.
            while (slideTextItems.size() < formats.size()) {
                slideTextItems.add(new SlideTextData(false, "", loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI",
                        40, Font.PLAIN, Color.YELLOW, 50, 50, 0, Color.BLACK, false, 100, 0, SwingConstants.CENTER));
            }
            // Apply formatting from source to each matching text item.
            // Preserve this slide's own text content, show state, highlight text, and underline text.
            // All formatting/styling syncs from slide 1 (master).
            // HL/UL text is per-slide (each slide highlights different words).
            // HL/UL style/color/tightness syncs from master.
            for (int i = 0; i < formats.size(); i++) {
                SlideTextData fmt = formats.get(i);
                SlideTextData existing = slideTextItems.get(i);
                String existingText = existing.text;
                boolean show = (existingText != null && !existingText.isEmpty()) ? existing.show : fmt.show;
                // Preserve per-slide HL/UL/B/I/CLR text if this slide has text content
                String hlText = (existingText != null && !existingText.isEmpty()) ? existing.highlightText : fmt.highlightText;
                String ulText = (existingText != null && !existingText.isEmpty()) ? existing.underlineText : fmt.underlineText;
                String bText = (existingText != null && !existingText.isEmpty()) ? existing.boldText : fmt.boldText;
                String iText = (existingText != null && !existingText.isEmpty()) ? existing.italicText : fmt.italicText;
                String cText = (existingText != null && !existingText.isEmpty()) ? existing.colorText : fmt.colorText;
                slideTextItems.set(i, new SlideTextData(show, existingText, fmt.fontName, fmt.fontSize,
                        fmt.fontStyle, fmt.color, fmt.x, fmt.y, fmt.bgOpacity,
                        fmt.bgColor, fmt.justify, fmt.widthPct, fmt.shiftX, fmt.alignment,
                        fmt.textEffect, fmt.textEffectIntensity,
                        hlText, fmt.highlightColor, fmt.highlightStyle,
                        fmt.highlightTightness, fmt.underlineStyle, ulText,
                        bText, iText, cText, fmt.colorTextColor, existing.xLeftAligned, fmt.odometer, fmt.odometerSpeed));
            }
            // For extra items beyond what the source has, apply formatting
            // from the last source item so they get consistent styling.
            // Text effect is reset to "None" so the last effect does not persist.
            if (formats.size() > 0 && slideTextItems.size() > formats.size()) {
                SlideTextData lastFmt = formats.get(formats.size() - 1);
                for (int i = formats.size(); i < slideTextItems.size(); i++) {
                    SlideTextData existing = slideTextItems.get(i);
                    String existingText = existing.text;
                    boolean show = (existingText != null && !existingText.isEmpty()) ? existing.show : false;
                    String hlText = (existingText != null && !existingText.isEmpty()) ? existing.highlightText : lastFmt.highlightText;
                    String ulText = (existingText != null && !existingText.isEmpty()) ? existing.underlineText : lastFmt.underlineText;
                    String bText = (existingText != null && !existingText.isEmpty()) ? existing.boldText : lastFmt.boldText;
                    String iText = (existingText != null && !existingText.isEmpty()) ? existing.italicText : lastFmt.italicText;
                    String cText = (existingText != null && !existingText.isEmpty()) ? existing.colorText : lastFmt.colorText;
                    slideTextItems.set(i, new SlideTextData(show, existingText, lastFmt.fontName, lastFmt.fontSize,
                            lastFmt.fontStyle, lastFmt.color, lastFmt.x, lastFmt.y, lastFmt.bgOpacity,
                            lastFmt.bgColor, lastFmt.justify, lastFmt.widthPct, lastFmt.shiftX, lastFmt.alignment,
                            "None", 50,
                            hlText, lastFmt.highlightColor, lastFmt.highlightStyle,
                            lastFmt.highlightTightness, lastFmt.underlineStyle, ulText,
                            bText, iText, cText, lastFmt.colorTextColor, existing.xLeftAligned, false, 50));
                }
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

        void setSlideTextAt(int textIndex, String text) {
            // Ensure we have enough slide text items
            while (slideTextItems.size() <= textIndex) {
                slideTextItems.add(new SlideTextData(false, "",
                        loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI",
                        40, Font.PLAIN, Color.YELLOW, 50, 50, 0, Color.BLACK,
                        false, 100, 0, SwingConstants.CENTER));
            }
            SlideTextData old = slideTextItems.get(textIndex);
            slideTextItems.set(textIndex, new SlideTextData(true, text, old.fontName, old.fontSize,
                    old.fontStyle, old.color, old.x, old.y, old.bgOpacity,
                    old.bgColor, old.justify, old.widthPct, old.shiftX, old.alignment,
                    old.textEffect, old.textEffectIntensity,
                    old.highlightText, old.highlightColor, old.highlightStyle,
                    old.highlightTightness, old.underlineStyle, old.underlineText));
            // Rebuild the selector dropdown to show all items
            isLoadingSlideText = true;
            try {
                rebuildSlideTextSelector();
                slideTextSelector.setSelectedIndex(currentSlideTextIndex);
            } finally {
                isLoadingSlideText = false;
            }
            if (currentSlideTextIndex == textIndex) {
                loadSlideTextFromItem(textIndex);
            }
        }

        /** Set X position for a specific text item by index. */
        void setSlideTextXAt(int textIndex, int xVal) {
            while (slideTextItems.size() <= textIndex) {
                slideTextItems.add(new SlideTextData(false, "",
                        loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI",
                        40, Font.PLAIN, Color.YELLOW, 50, 50, 0, Color.BLACK,
                        false, 100, 0, SwingConstants.CENTER));
            }
            SlideTextData old = slideTextItems.get(textIndex);
            slideTextItems.set(textIndex, new SlideTextData(old.show, old.text, old.fontName, old.fontSize,
                    old.fontStyle, old.color, xVal, old.y, old.bgOpacity,
                    old.bgColor, old.justify, old.widthPct, old.shiftX, old.alignment,
                    old.textEffect, old.textEffectIntensity,
                    old.highlightText, old.highlightColor, old.highlightStyle,
                    old.highlightTightness, old.underlineStyle, old.underlineText,
                    old.boldText, old.italicText, old.colorText, old.colorTextColor, true, old.odometer, old.odometerSpeed));
            if (currentSlideTextIndex == textIndex) loadSlideTextFromItem(textIndex);
        }

        /** Set Y position for a specific text item by index. */
        void setSlideTextYAt(int textIndex, int yVal) {
            while (slideTextItems.size() <= textIndex) {
                slideTextItems.add(new SlideTextData(false, "",
                        loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI",
                        40, Font.PLAIN, Color.YELLOW, 50, 50, 0, Color.BLACK,
                        false, 100, 0, SwingConstants.CENTER));
            }
            SlideTextData old = slideTextItems.get(textIndex);
            slideTextItems.set(textIndex, new SlideTextData(old.show, old.text, old.fontName, old.fontSize,
                    old.fontStyle, old.color, old.x, yVal, old.bgOpacity,
                    old.bgColor, old.justify, old.widthPct, old.shiftX, old.alignment,
                    old.textEffect, old.textEffectIntensity,
                    old.highlightText, old.highlightColor, old.highlightStyle,
                    old.highlightTightness, old.underlineStyle, old.underlineText,
                    old.boldText, old.italicText, old.colorText, old.colorTextColor, old.xLeftAligned, old.odometer, old.odometerSpeed));
            if (currentSlideTextIndex == textIndex) loadSlideTextFromItem(textIndex);
        }

        /** Set font size for a specific text item by index. */
        void setSlideTextSizeAt(int textIndex, int sizeVal) {
            while (slideTextItems.size() <= textIndex) {
                slideTextItems.add(new SlideTextData(false, "",
                        loadedFontNames.length > 0 ? loadedFontNames[0] : "Segoe UI",
                        40, Font.PLAIN, Color.YELLOW, 50, 50, 0, Color.BLACK,
                        false, 100, 0, SwingConstants.CENTER));
            }
            SlideTextData old = slideTextItems.get(textIndex);
            slideTextItems.set(textIndex, new SlideTextData(old.show, old.text, old.fontName, sizeVal,
                    old.fontStyle, old.color, old.x, old.y, old.bgOpacity,
                    old.bgColor, old.justify, old.widthPct, old.shiftX, old.alignment,
                    old.textEffect, old.textEffectIntensity,
                    old.highlightText, old.highlightColor, old.highlightStyle,
                    old.highlightTightness, old.underlineStyle, old.underlineText,
                    old.boldText, old.italicText, old.colorText, old.colorTextColor, old.xLeftAligned, old.odometer, old.odometerSpeed));
            if (currentSlideTextIndex == textIndex) loadSlideTextFromItem(textIndex);
        }

        /** Set the main overlay highlight text field for this slide. */
        void setHighlightText(String text) {
            highlightField.setText(text != null ? text : "");
        }

        /** Set highlight text on all slide text items for this slide. */
        void setSlideTextHighlightText(String text) {
            for (int i = 0; i < slideTextItems.size(); i++) {
                SlideTextData old = slideTextItems.get(i);
                slideTextItems.set(i, new SlideTextData(old.show, old.text, old.fontName, old.fontSize,
                        old.fontStyle, old.color, old.x, old.y, old.bgOpacity,
                        old.bgColor, old.justify, old.widthPct, old.shiftX, old.alignment,
                        old.textEffect, old.textEffectIntensity,
                        text != null ? text : "", old.highlightColor, old.highlightStyle,
                        old.highlightTightness, old.underlineStyle, old.underlineText,
                        old.boldText, old.italicText, old.colorText, old.colorTextColor, old.xLeftAligned, old.odometer, old.odometerSpeed));
            }
            if (currentSlideTextIndex < slideTextItems.size()) {
                loadSlideTextFromItem(currentSlideTextIndex);
            }
        }

        /** Set underline text on all slide text items for this slide. */
        void setSlideTextUnderlineText(String text) {
            for (int i = 0; i < slideTextItems.size(); i++) {
                SlideTextData old = slideTextItems.get(i);
                slideTextItems.set(i, new SlideTextData(old.show, old.text, old.fontName, old.fontSize,
                        old.fontStyle, old.color, old.x, old.y, old.bgOpacity,
                        old.bgColor, old.justify, old.widthPct, old.shiftX, old.alignment,
                        old.textEffect, old.textEffectIntensity,
                        old.highlightText, old.highlightColor, old.highlightStyle,
                        old.highlightTightness, old.underlineStyle, text != null ? text : "",
                        old.boldText, old.italicText, old.colorText, old.colorTextColor, old.xLeftAligned, old.odometer, old.odometerSpeed));
            }
            if (currentSlideTextIndex < slideTextItems.size()) {
                loadSlideTextFromItem(currentSlideTextIndex);
            }
        }

        /** Set bold text on all slide text items for this slide. */
        void setSlideTextBoldText(String text) {
            for (int i = 0; i < slideTextItems.size(); i++) {
                SlideTextData old = slideTextItems.get(i);
                slideTextItems.set(i, new SlideTextData(old.show, old.text, old.fontName, old.fontSize,
                        old.fontStyle, old.color, old.x, old.y, old.bgOpacity,
                        old.bgColor, old.justify, old.widthPct, old.shiftX, old.alignment,
                        old.textEffect, old.textEffectIntensity,
                        old.highlightText, old.highlightColor, old.highlightStyle,
                        old.highlightTightness, old.underlineStyle, old.underlineText,
                        text != null ? text : "", old.italicText, old.colorText, old.colorTextColor, old.xLeftAligned, old.odometer, old.odometerSpeed));
            }
            if (currentSlideTextIndex < slideTextItems.size()) {
                loadSlideTextFromItem(currentSlideTextIndex);
            }
        }

        /** Set italic text on all slide text items for this slide. */
        void setSlideTextItalicText(String text) {
            for (int i = 0; i < slideTextItems.size(); i++) {
                SlideTextData old = slideTextItems.get(i);
                slideTextItems.set(i, new SlideTextData(old.show, old.text, old.fontName, old.fontSize,
                        old.fontStyle, old.color, old.x, old.y, old.bgOpacity,
                        old.bgColor, old.justify, old.widthPct, old.shiftX, old.alignment,
                        old.textEffect, old.textEffectIntensity,
                        old.highlightText, old.highlightColor, old.highlightStyle,
                        old.highlightTightness, old.underlineStyle, old.underlineText,
                        old.boldText, text != null ? text : "", old.colorText, old.colorTextColor, old.xLeftAligned, old.odometer, old.odometerSpeed));
            }
            if (currentSlideTextIndex < slideTextItems.size()) {
                loadSlideTextFromItem(currentSlideTextIndex);
            }
        }

        /** Set color text on all slide text items for this slide. */
        void setSlideTextColorText(String text) {
            for (int i = 0; i < slideTextItems.size(); i++) {
                SlideTextData old = slideTextItems.get(i);
                slideTextItems.set(i, new SlideTextData(old.show, old.text, old.fontName, old.fontSize,
                        old.fontStyle, old.color, old.x, old.y, old.bgOpacity,
                        old.bgColor, old.justify, old.widthPct, old.shiftX, old.alignment,
                        old.textEffect, old.textEffectIntensity,
                        old.highlightText, old.highlightColor, old.highlightStyle,
                        old.highlightTightness, old.underlineStyle, old.underlineText,
                        old.boldText, old.italicText, text != null ? text : "", old.colorTextColor, old.xLeftAligned, old.odometer, old.odometerSpeed));
            }
            if (currentSlideTextIndex < slideTextItems.size()) {
                loadSlideTextFromItem(currentSlideTextIndex);
            }
        }

        // ===== Slide picture dropdown helpers =====

        private void saveCurrentSlidePictureToItem() {
            if (currentSlidePictureIndex < 0 || currentSlidePictureIndex >= slidePictureItems.size()) return;
            slidePictureItems.set(currentSlidePictureIndex, new SlidePictureData(
                    slidePicShowCheck.isSelected(), slidePicLoadedImage, slidePicLoadedFile,
                    (int) slidePicXSpinner.getValue(), (int) slidePicYSpinner.getValue(),
                    (int) slidePicWidthSpinner.getValue(),
                    (String) slidePicShapeCombo.getSelectedItem(),
                    (int) slidePicCornerSpinner.getValue()));
        }

        private void loadSlidePictureFromItem(int index) {
            if (index < 0 || index >= slidePictureItems.size()) return;
            isLoadingSlidePicture = true;
            try {
                SlidePictureData item = slidePictureItems.get(index);
                slidePicShowCheck.setSelected(item.show);
                slidePicLoadedImage = item.image;
                slidePicLoadedFile = item.imageFile;
                slidePicXSpinner.setValue(item.x);
                slidePicYSpinner.setValue(item.y);
                slidePicWidthSpinner.setValue(item.widthPct);
                slidePicShapeCombo.setSelectedItem(item.shape);
                slidePicCornerSpinner.setValue(item.cornerRadius);
                updateSlidePicPreview();
            } finally {
                isLoadingSlidePicture = false;
            }
        }

        private void rebuildSlidePicSelector() {
            slidePicSelector.removeAllItems();
            for (int i = 0; i < slidePictureItems.size(); i++) {
                slidePicSelector.addItem("Pic " + (i + 1));
            }
        }

        private void updateSlidePicPreview() {
            if (slidePicLoadedImage != null) {
                int pw = slidePicPreviewLabel.getPreferredSize().width;
                int ph = slidePicPreviewLabel.getPreferredSize().height;
                double sc = Math.min((double) pw / slidePicLoadedImage.getWidth(),
                        (double) ph / slidePicLoadedImage.getHeight());
                Image scaled = slidePicLoadedImage.getScaledInstance(
                        Math.max(1, (int) (slidePicLoadedImage.getWidth() * sc)),
                        Math.max(1, (int) (slidePicLoadedImage.getHeight() * sc)),
                        Image.SCALE_SMOOTH);
                slidePicPreviewLabel.setIcon(new ImageIcon(scaled));
                slidePicPreviewLabel.setText(null);
            } else {
                slidePicPreviewLabel.setIcon(null);
                slidePicPreviewLabel.setText("No image");
            }
        }

        List<SlidePictureData> getSlidePictureDataList() {
            saveCurrentSlidePictureToItem();
            return new ArrayList<>(slidePictureItems);
        }

        List<SlidePictureData> getSlidePictureFormats() {
            return getSlidePictureDataList();
        }

        void applySlidePictureFormats(List<SlidePictureData> formats) {
            if (formats == null || formats.isEmpty()) return;
            // Ensure we have at least as many items as the source.
            while (slidePictureItems.size() < formats.size()) {
                slidePictureItems.add(new SlidePictureData(false, null, null, 50, 50, 20, "Rectangle", 0));
            }
            // Sync position/size/shape from master, preserve each slide's own image and show state.
            for (int i = 0; i < formats.size(); i++) {
                SlidePictureData fmt = formats.get(i);
                SlidePictureData existing = slidePictureItems.get(i);
                boolean show = existing.image != null ? existing.show : fmt.show;
                slidePictureItems.set(i, new SlidePictureData(show,
                        existing.image, existing.imageFile,
                        fmt.x, fmt.y, fmt.widthPct, fmt.shape, fmt.cornerRadius));
            }
            if (currentSlidePictureIndex >= slidePictureItems.size()) {
                currentSlidePictureIndex = 0;
            }
            isLoadingSlidePicture = true;
            try {
                rebuildSlidePicSelector();
                slidePicSelector.setSelectedIndex(currentSlidePictureIndex);
            } finally {
                isLoadingSlidePicture = false;
            }
            loadSlidePictureFromItem(currentSlidePictureIndex);
            schedulePreview();
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
                             List<SlidePictureData> slidePictureFormats,
                             boolean fxRoundCorners, int fxCornerRadius,
                             boolean fxVignetteOn, int fxVignetteVal,
                             boolean fxSepiaOn, int fxSepiaVal,
                             boolean fxGrainOn, int fxGrainVal,
                             boolean fxWaterRippleOn, int fxWaterRippleVal,
                             boolean fxGlitchOn, int fxGlitchVal,
                             boolean fxShakeOn, int fxShakeVal,
                             boolean fxScanlineOn, int fxScanlineVal,
                             boolean fxRaisedOn, int fxRaisedVal,
                             String overlayShape, String overlayBgMode, Color overlayBgColor,
                             int overlayX, int overlayY, int overlaySize,
                             boolean textJustify, int textWidthPct,
                             String highlightText, Color highlightColor,
                             int textShiftX,
                             File voFile, int voDurationMs, int voX, int voY, int voSize,
                             double audioGapSeconds) {
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
            applySlidePictureFormats(slidePictureFormats);

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
            fxScanlineCheck.setSelected(fxScanlineOn);
            fxScanlineSpinner.setValue(fxScanlineVal);
            fxRaisedCheck.setSelected(fxRaisedOn);
            fxRaisedSpinner.setValue(fxRaisedVal);

            overlayShapeCombo.setSelectedItem(overlayShape);
            overlayBgCombo.setSelectedItem(overlayBgMode);
            this.overlayBgColor = overlayBgColor;
            overlayBgColorBtn.setForeground(overlayBgColor);
            overlayXSpinner.setValue(overlayX);
            overlayYSpinner.setValue(overlayY);
            overlaySizeSpinner.setValue(overlaySize);

            justifyCheckBox.setSelected(textJustify);
            textWidthSpinner.setValue(textWidthPct);
            // Don't overwrite per-slide highlight text — each slide highlights different words.
            // Only sync the highlight color/style from master.
            this.highlightColor = highlightColor;
            highlightColorBtn.setForeground(highlightColor);
            textShiftXSpinner.setValue(textShiftX);

            // Sync video overlay from master slide
            if (voFile != null && voFile.exists()) {
                setSlideVideoOverlay(voFile, voDurationMs, voX, voY, voSize);
            } else {
                clearSlideVideoOverlay();
            }

            audioGapSpinner.setValue(audioGapSeconds);

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
                    getFxScanline(), getFxRaised(),
                    isOverlayEnabled(),
                    getOverlayShape(), getOverlayBgMode(), getOverlayBgColor(),
                    getOverlayX(), getOverlayY(), getOverlaySize(), 0,
                    isTextJustify(), getTextWidthPct(),
                    getHighlightText(), getHighlightColor(),
                    getTextShiftX(), getSlidePictureDataList());

            // Draw video overlay indicator if this slide has a video overlay
            if (slideVideoOverlayFile != null) {
                int pw = preview.getWidth();
                int ph = preview.getHeight();
                BufferedImage argbPreview = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
                Graphics2D copyG = argbPreview.createGraphics();
                copyG.drawImage(preview, 0, 0, null);
                copyG.dispose();
                preview = argbPreview;

                int vox = (int) videoOverlayXSp.getValue();
                int voy = (int) videoOverlayYSp.getValue();
                int vos = (int) videoOverlaySizeSp.getValue();
                int ovW = Math.max(20, (int)(pw * vos / 100.0));
                int ovH = Math.max(12, (int)(ovW * 9.0 / 16.0));
                int ovPxX = (int)(pw * vox / 100.0) - ovW / 2;
                int ovPxY = (int)(ph * voy / 100.0);
                Graphics2D pg = preview.createGraphics();
                pg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
                pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                pg.setColor(new Color(255, 180, 50, 60));
                pg.fillRoundRect(ovPxX, ovPxY, ovW, ovH, 8, 8);
                pg.setColor(new Color(255, 180, 50, 220));
                pg.setStroke(new BasicStroke(2));
                pg.drawRoundRect(ovPxX, ovPxY, ovW, ovH, 8, 8);
                pg.setFont(new Font("Segoe UI", Font.BOLD, Math.max(9, ovW / 10)));
                pg.setColor(Color.WHITE);
                String voText = "Video";
                FontMetrics fm = pg.getFontMetrics();
                int tx = ovPxX + (ovW - fm.stringWidth(voText)) / 2;
                int ty = ovPxY + (ovH + fm.getAscent()) / 2;
                pg.drawString(voText, tx, ty);
                pg.dispose();
            }

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
        int getFxScanline() { return fxScanlineCheck.isSelected() ? (int) fxScanlineSpinner.getValue() : 0; }
        int getFxRaised() { return fxRaisedCheck.isSelected() ? (int) fxRaisedSpinner.getValue() : 0; }
        int getFxVignetteRaw() { return (int) fxVignetteSpinner.getValue(); }
        int getFxSepiaRaw() { return (int) fxSepiaSpinner.getValue(); }
        int getFxGrainRaw() { return (int) fxGrainSpinner.getValue(); }
        int getFxWaterRippleRaw() { return (int) fxWaterRippleSpinner.getValue(); }
        int getFxGlitchRaw() { return (int) fxGlitchSpinner.getValue(); }
        int getFxShakeRaw() { return (int) fxShakeSpinner.getValue(); }
        int getFxScanlineRaw() { return (int) fxScanlineSpinner.getValue(); }
        int getFxRaisedRaw() { return (int) fxRaisedSpinner.getValue(); }
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

        File getSlideAudioFile() { return slideAudioFiles.get(0); }
        int getSlideAudioDurationMs() {
            int total = 0;
            for (int d : slideAudioDurationsMs.values()) {
                if (d > 0) total += d;
            }
            return total > 0 ? total : -1;
        }

        java.util.List<File> getSlideAudioFilesList() {
            int maxIdx = -1;
            for (int k : slideAudioFiles.keySet()) if (k > maxIdx) maxIdx = k;
            if (maxIdx < 0) return new java.util.ArrayList<>();
            java.util.List<File> result = new java.util.ArrayList<>();
            for (int i = 0; i <= maxIdx; i++) {
                result.add(slideAudioFiles.get(i));
            }
            return result;
        }

        java.util.List<Integer> getSlideAudioDurationsMsList() {
            int maxIdx = -1;
            for (int k : slideAudioDurationsMs.keySet()) if (k > maxIdx) maxIdx = k;
            if (maxIdx < 0) return new java.util.ArrayList<>();
            java.util.List<Integer> result = new java.util.ArrayList<>();
            for (int i = 0; i <= maxIdx; i++) {
                Integer d = slideAudioDurationsMs.get(i);
                result.add(d != null ? d : 0);
            }
            return result;
        }

        int getAudioGapMs() { return (int)(((Number) audioGapSpinner.getValue()).doubleValue() * 1000); }
        Color getAudioHlColor() { return audioHlColor; }
        String getAudioHlEffects() {
            StringBuilder sb = new StringBuilder();
            if (audioFxGlow.isSelected()) sb.append("Glow,");
            if (audioFxEnlarge.isSelected()) sb.append("Enlarge,");
            if (audioFxBold.isSelected()) sb.append("Bold,");
            if (audioFxUnderline.isSelected()) sb.append("Underline,");
            if (audioFxColor.isSelected()) sb.append("Color,");
            if (audioFxShake.isSelected()) sb.append("Shake,");
            if (audioFxPulse.isSelected()) sb.append("Pulse,");
            if (sb.length() > 0) sb.setLength(sb.length() - 1);
            return sb.toString();
        }

        File getSlideVideoOverlayFile() { return slideVideoOverlayFile; }
        int getSlideVideoOverlayDurationMs() { return slideVideoOverlayDurationMs; }
        int getSlideVideoOverlayX() { return (int) videoOverlayXSp.getValue(); }
        int getSlideVideoOverlayY() { return (int) videoOverlayYSp.getValue(); }
        int getSlideVideoOverlaySize() { return (int) videoOverlaySizeSp.getValue(); }

        private void browseSlideVideoOverlay() {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "Video Files (mp4, avi, mov, mkv, webm, flv, wmv)",
                    "mp4", "avi", "mov", "mkv", "webm", "flv", "wmv"));
            chooser.setDialogTitle("Select Video Overlay for This Slide");
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                int durationMs = probeAudioDurationMs(file);
                if (durationMs <= 0) {
                    JOptionPane.showMessageDialog(panel,
                            "Could not read video duration.\nMake sure ffprobe is installed.",
                            "Video Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                slideVideoOverlayFile = file;
                slideVideoOverlayDurationMs = durationMs;
                videoOverlayFileLbl.setText(file.getName());
                videoOverlayFileLbl.setForeground(Color.WHITE);
                videoOverlayDurLbl.setText(String.format("(%d.%ds)",
                        durationMs / 1000, (durationMs % 1000) / 100));
                videoOverlayClearButton.setVisible(true);
                onFormatChanged();
            }
        }

        private void clearSlideVideoOverlay() {
            slideVideoOverlayFile = null;
            slideVideoOverlayDurationMs = -1;
            videoOverlayFileLbl.setText("No video");
            videoOverlayFileLbl.setForeground(Color.GRAY);
            videoOverlayDurLbl.setText("");
            videoOverlayClearButton.setVisible(false);
            onFormatChanged();
        }

        void setSlideVideoOverlay(File file, int durationMs, int x, int y, int size) {
            slideVideoOverlayFile = file;
            slideVideoOverlayDurationMs = durationMs;
            videoOverlayFileLbl.setText(file.getName());
            videoOverlayFileLbl.setForeground(Color.WHITE);
            videoOverlayDurLbl.setText(String.format("(%d.%ds)",
                    durationMs / 1000, (durationMs % 1000) / 100));
            videoOverlayClearButton.setVisible(true);
            videoOverlayXSp.setValue(x);
            videoOverlayYSp.setValue(y);
            videoOverlaySizeSp.setValue(size);
        }

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
                slideAudioFiles.put(currentSlideTextIndex, file);
                slideAudioDurationsMs.put(currentSlideTextIndex, durationMs);
                updateAudioUI();
            }
        }

        private void clearSlideAudio() {
            slideAudioFiles.remove(currentSlideTextIndex);
            slideAudioDurationsMs.remove(currentSlideTextIndex);
            updateAudioUI();
        }

        void setSlideAudio(File file, int durationMs) {
            setSlideAudio(0, file, durationMs);
        }

        void setSlideAudio(int textIndex, File file, int durationMs) {
            slideAudioFiles.put(textIndex, file);
            slideAudioDurationsMs.put(textIndex, durationMs);
            if (currentSlideTextIndex == textIndex) {
                updateAudioUI();
            }
        }

        private void updateAudioUI() {
            File file = slideAudioFiles.get(currentSlideTextIndex);
            Integer durationMs = slideAudioDurationsMs.get(currentSlideTextIndex);
            audioLabel.setText("\uD83C\uDFB5 Audio (Text " + (currentSlideTextIndex + 1) + "):");
            if (file != null && durationMs != null && durationMs > 0) {
                audioFileLabel.setText("\u266B " + file.getName());
                audioFileLabel.setFont(audioFileLabel.getFont().deriveFont(Font.BOLD));
                audioFileLabel.setForeground(new Color(200, 230, 255));
                audioDurationLabel.setText(String.format("\u23F1 %d.%ds",
                        durationMs / 1000, (durationMs % 1000) / 100));
                audioClearBtn.setVisible(true);
            } else {
                audioFileLabel.setText("No audio");
                audioFileLabel.setFont(audioFileLabel.getFont().deriveFont(Font.ITALIC));
                audioFileLabel.setForeground(new Color(140, 140, 160));
                audioDurationLabel.setText("");
                audioClearBtn.setVisible(false);
            }
        }
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new GifSlideShowApp().setVisible(true));
    }
}
