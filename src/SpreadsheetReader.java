import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads a spreadsheet into plain rows of strings — the one place the app knows
 * how to open a workbook.
 *
 * <p>Three formats, no third-party libraries (the whole project builds with a
 * bare JDK, and that must stay true):
 * <ul>
 *   <li><b>.xlsx / .xlsm / .xltx</b> — a ZIP of XML parts, read with
 *       {@link ZipFile} and the JDK's StAX parser. Shared strings, inline
 *       strings, cached formula results and date-formatted numbers all come
 *       back as the text Excel shows on screen.</li>
 *   <li><b>.xls</b> — the legacy binary workbook: an OLE2 compound file whose
 *       "Workbook" stream holds BIFF8 (Excel 97-2003) or BIFF5 (Excel 5/95)
 *       records. Parsed here directly.</li>
 *   <li><b>.csv / .tsv / .txt</b> — delimited text, with the delimiter sniffed
 *       from the first line and the encoding from the byte-order mark (falling
 *       back to Windows-1252 when the bytes are not valid UTF-8, which is what
 *       a plain "Save as CSV" out of Excel produces).</li>
 * </ul>
 *
 * <p>The format is decided by the file's magic bytes, not its extension, so a
 * .csv that is really a workbook (or the other way round) still opens.
 *
 * <p>Every cell arrives as a {@code String}: numbers are trimmed of the
 * trailing ".0" Excel never shows, dates become {@code yyyy-MM-dd}, and blank
 * cells become "". Rows are ragged — a row is only as long as its last
 * non-empty cell — so callers must index defensively.
 */
final class SpreadsheetReader {

    /** File extensions the importers advertise, for the file chooser's filter. */
    static final String[] EXTENSIONS = {"xlsx", "xlsm", "xltx", "xls", "csv", "tsv", "txt"};

    /** One worksheet: its tab name and its rows. */
    static final class Sheet {
        final String name;
        final List<List<String>> rows;

        Sheet(String name, List<List<String>> rows) {
            this.name = name == null ? "" : name;
            this.rows = rows == null ? new ArrayList<>() : rows;
        }

        /** True when the sheet holds no cell with any text in it. */
        boolean isBlank() {
            for (List<String> row : rows) {
                for (String c : row) {
                    if (c != null && !c.trim().isEmpty()) return false;
                }
            }
            return true;
        }

        /** Rows that hold at least one non-empty cell — trailing blank rows and
         *  the empty spacer rows Excel leaves behind are dropped. */
        int nonEmptyRowCount() {
            int n = 0;
            for (List<String> row : rows) {
                for (String c : row) {
                    if (c != null && !c.trim().isEmpty()) { n++; break; }
                }
            }
            return n;
        }

        @Override public String toString() {
            return name + " (" + nonEmptyRowCount() + " rows)";
        }
    }

    private SpreadsheetReader() { }

    // ==================== Entry point ====================

    /**
     * Read every sheet in {@code file}. Delimited text files come back as a
     * single sheet named after the file.
     *
     * @throws IOException when the file cannot be read, is password-protected,
     *         or is in a format this reader does not speak — the message is
     *         written for the person who picked the file, not for a log.
     */
    static List<Sheet> read(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("File not found: " + (file == null ? "(none)" : file.getPath()));
        }
        byte[] magic = readHead(file, 8);
        if (magic.length >= 4 && magic[0] == 'P' && magic[1] == 'K'
                && (magic[2] == 3 || magic[2] == 5 || magic[2] == 7)) {
            return readXlsx(file);
        }
        if (magic.length >= 8 && (magic[0] & 0xFF) == 0xD0 && (magic[1] & 0xFF) == 0xCF
                && (magic[2] & 0xFF) == 0x11 && (magic[3] & 0xFF) == 0xE0
                && (magic[4] & 0xFF) == 0xA1 && (magic[5] & 0xFF) == 0xB1
                && (magic[6] & 0xFF) == 0x1A && (magic[7] & 0xFF) == 0xE1) {
            return readXls(file);
        }
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".xlsm")
                || lower.endsWith(".xltx")) {
            // Excel can save an .xls that is really HTML or an XML spreadsheet;
            // say so plainly instead of printing tag soup onto the slides.
            String peek = new String(readHead(file, 512), StandardCharsets.ISO_8859_1)
                    .trim().toLowerCase(Locale.ROOT);
            if (peek.startsWith("<?xml") || peek.startsWith("<html") || peek.startsWith("<!doctype")) {
                throw new IOException("This file has an Excel name but holds HTML/XML, not a workbook.\n"
                        + "Open it in Excel and re-save it as \"Excel Workbook (*.xlsx)\" "
                        + "or \"CSV UTF-8\", then import that.");
            }
        }
        return Collections.singletonList(new Sheet(stripExtension(file.getName()),
                readDelimited(Files.readAllBytes(file.toPath()))));
    }

    /** The first {@code n} bytes of a file (fewer when the file is shorter). */
    private static byte[] readHead(File file, int n) throws IOException {
        byte[] buf = new byte[n];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            int read = 0;
            while (read < n) {
                int r = in.read(buf, read, n - read);
                if (r < 0) break;
                read += r;
            }
            return read == n ? buf : Arrays.copyOf(buf, read);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ==================== Delimited text (CSV / TSV) ====================

    /**
     * Decode and split delimited text. The byte-order mark picks the charset;
     * without one the bytes are read as UTF-8 and, if that produces replacement
     * characters, re-read as Windows-1252 (Excel's plain "CSV" export).
     */
    static List<List<String>> readDelimited(byte[] bytes) {
        String content = decodeText(bytes);
        List<String> lines = splitRows(content);
        char delim = sniffDelimiter(lines);
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) rows.add(splitFields(line, delim));
        // Drop trailing blank rows so "5 per slide" is not thrown off by the
        // empty lines a spreadsheet export leaves at the bottom.
        while (!rows.isEmpty() && isBlankRow(rows.get(rows.size() - 1))) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    private static boolean isBlankRow(List<String> row) {
        for (String c : row) {
            if (c != null && !c.trim().isEmpty()) return false;
        }
        return true;
    }

    /** Bytes to text: BOM-aware, with a Windows-1252 fallback for non-UTF-8. */
    static String decodeText(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return stripBom(new String(bytes, StandardCharsets.UTF_16LE));
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return stripBom(new String(bytes, StandardCharsets.UTF_16BE));
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('�') >= 0) {
            try {
                return new String(bytes, Charset.forName("windows-1252"));
            } catch (Exception ex) {
                return utf8;
            }
        }
        return utf8;
    }

    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
    }

    /** Split into physical rows, keeping newlines that sit inside quotes. */
    private static List<String> splitRows(String content) {
        List<String> rows = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        cur.append('"').append('"');
                        i++;
                    } else {
                        inQuotes = false;
                        cur.append(c);
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                cur.append(c);
            } else if (c == '\r') {
                // handled by the \n that follows
            } else if (c == '\n') {
                rows.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) rows.add(cur.toString());
        return rows;
    }

    /**
     * Work out the delimiter from the first rows that have one: tab, then
     * semicolon (Excel's export in comma-decimal locales), then comma. Counting
     * happens outside quotes so a comma inside a quoted definition does not vote.
     */
    private static char sniffDelimiter(List<String> lines) {
        int commas = 0, tabs = 0, semis = 0;
        int scanned = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            boolean inQuotes = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') inQuotes = !inQuotes;
                else if (inQuotes) continue;
                else if (c == ',') commas++;
                else if (c == '\t') tabs++;
                else if (c == ';') semis++;
            }
            if (++scanned >= 5) break;
        }
        if (tabs > commas && tabs >= semis) return '\t';
        if (semis > commas && semis > tabs) return ';';
        return ',';
    }

    /** Split one row on {@code delim}, honouring "quoted ""cells""". */
    private static List<String> splitFields(String line, char delim) {
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
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delim) {
                fields.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString().trim());
        return fields;
    }

    // ==================== .xlsx / .xlsm (Office Open XML) ====================

    /** Style-driven formatting: which cell styles are dates, and the workbook's
     *  date epoch. Everything else about a style is irrelevant to plain text. */
    private static final class XlsxStyles {
        /** style index → true when that style formats its number as a date/time. */
        final List<Boolean> dateStyles = new ArrayList<>();
        /** style index → the format code, for telling a date from a date+time. */
        final List<String> formatCodes = new ArrayList<>();

        boolean isDate(int styleIdx) {
            return styleIdx >= 0 && styleIdx < dateStyles.size() && dateStyles.get(styleIdx);
        }

        String formatCode(int styleIdx) {
            return styleIdx >= 0 && styleIdx < formatCodes.size() ? formatCodes.get(styleIdx) : "";
        }
    }

    private static XMLStreamReader newXmlReader(InputStream in) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // The app never wants a workbook reaching out to the network or the disk.
        trySetProperty(factory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        trySetProperty(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        trySetProperty(factory, XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        return factory.createXMLStreamReader(in);
    }

    private static void trySetProperty(XMLInputFactory f, String name, Object value) {
        try { f.setProperty(name, value); } catch (Exception ignored) { }
    }

    private static List<Sheet> readXlsx(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            Map<String, ZipEntry> entries = new HashMap<>();
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                entries.put(ze.getName().toLowerCase(Locale.ROOT), ze);
            }
            String workbookPath = resolveWorkbookPath(zip, entries);
            if (workbookPath == null) {
                throw new IOException("This .xlsx has no workbook part inside it — the file looks damaged.\n"
                        + "Open it in Excel and re-save it, then import again.");
            }
            String base = directoryOf(workbookPath);
            Map<String, String> rels = readRelationships(zip, entries,
                    base + "_rels/" + fileNameOf(workbookPath) + ".rels", base);

            boolean[] date1904 = new boolean[1];
            List<String[]> defs = readWorkbookSheets(zip, entries, workbookPath, date1904);
            List<String> sst = readSharedStrings(zip, entries, base + "sharedStrings.xml");
            XlsxStyles styles = readXlsxStyles(zip, entries, base + "styles.xml");

            List<Sheet> sheets = new ArrayList<>();
            for (String[] def : defs) {
                String target = def[1] == null ? null : rels.get(def[1]);
                ZipEntry entry = target == null ? null : entries.get(target.toLowerCase(Locale.ROOT));
                if (entry == null) continue; // a sheet whose part is missing: skip, don't fail
                sheets.add(new Sheet(def[0], readXlsxSheet(zip, entry, sst, styles, date1904[0])));
            }
            if (sheets.isEmpty()) {
                throw new IOException("No worksheets found in " + file.getName() + ".");
            }
            return sheets;
        } catch (java.util.zip.ZipException ex) {
            throw new IOException(file.getName() + " is not a readable .xlsx — the file looks damaged "
                    + "or only partly copied.\nOpen it in Excel and save it again, then import that.", ex);
        } catch (XMLStreamException ex) {
            throw new IOException("Could not read " + file.getName() + " — its XML is malformed ("
                    + ex.getMessage() + ").", ex);
        }
    }

    /** Follow _rels/.rels to the workbook part, falling back to the usual path. */
    private static String resolveWorkbookPath(ZipFile zip, Map<String, ZipEntry> entries)
            throws IOException, XMLStreamException {
        ZipEntry rootRels = entries.get("_rels/.rels");
        if (rootRels != null) {
            try (InputStream in = zip.getInputStream(rootRels)) {
                XMLStreamReader r = newXmlReader(in);
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT
                            && "Relationship".equals(r.getLocalName())) {
                        String type = r.getAttributeValue(null, "Type");
                        String target = r.getAttributeValue(null, "Target");
                        if (type != null && target != null && type.endsWith("/officeDocument")) {
                            String path = normalizePath(target, "");
                            if (entries.containsKey(path.toLowerCase(Locale.ROOT))) return path;
                        }
                    }
                }
                r.close();
            }
        }
        for (String candidate : new String[]{"xl/workbook.xml", "xl/workbook.bin", "workbook.xml"}) {
            ZipEntry e = entries.get(candidate);
            if (e != null) return e.getName();
        }
        return null;
    }

    /** rId → part path, resolved against the workbook's own folder. */
    private static Map<String, String> readRelationships(ZipFile zip, Map<String, ZipEntry> entries,
            String relsPath, String base) throws IOException, XMLStreamException {
        Map<String, String> map = new HashMap<>();
        ZipEntry entry = entries.get(relsPath.toLowerCase(Locale.ROOT));
        if (entry == null) return map;
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader r = newXmlReader(in);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT
                        && "Relationship".equals(r.getLocalName())) {
                    String id = r.getAttributeValue(null, "Id");
                    String target = r.getAttributeValue(null, "Target");
                    String mode = r.getAttributeValue(null, "TargetMode");
                    if (id != null && target != null && !"External".equalsIgnoreCase(mode)) {
                        map.put(id, normalizePath(target, base));
                    }
                }
            }
            r.close();
        }
        return map;
    }

    /** Sheet tab names in workbook order, each with the rId of its part. */
    private static List<String[]> readWorkbookSheets(ZipFile zip, Map<String, ZipEntry> entries,
            String workbookPath, boolean[] date1904) throws IOException, XMLStreamException {
        List<String[]> defs = new ArrayList<>();
        ZipEntry entry = entries.get(workbookPath.toLowerCase(Locale.ROOT));
        if (entry == null) return defs;
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader r = newXmlReader(in);
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                String name = r.getLocalName();
                if ("sheet".equals(name)) {
                    String tab = r.getAttributeValue(null, "name");
                    String rid = null;
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        if ("id".equals(r.getAttributeLocalName(i))) { rid = r.getAttributeValue(i); break; }
                    }
                    defs.add(new String[]{tab == null ? "Sheet" + (defs.size() + 1) : tab, rid});
                } else if ("workbookPr".equals(name)) {
                    String v = r.getAttributeValue(null, "date1904");
                    if (v == null) v = r.getAttributeValue(null, "dateCompatibility");
                    date1904[0] = "1".equals(v) || "true".equalsIgnoreCase(v);
                }
            }
            r.close();
        }
        return defs;
    }

    /** The shared-string table, in index order. Phonetic runs are left out —
     *  they are a pronunciation hint, not part of the cell's text. */
    private static List<String> readSharedStrings(ZipFile zip, Map<String, ZipEntry> entries,
            String path) throws IOException, XMLStreamException {
        List<String> strings = new ArrayList<>();
        ZipEntry entry = entries.get(path.toLowerCase(Locale.ROOT));
        if (entry == null) return strings;
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader r = newXmlReader(in);
            StringBuilder si = null;
            int phoneticDepth = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if ("si".equals(n)) { si = new StringBuilder(); phoneticDepth = 0; }
                    else if ("rPh".equals(n) || "phoneticPr".equals(n)) phoneticDepth++;
                } else if (ev == XMLStreamConstants.CHARACTERS && si != null && phoneticDepth == 0) {
                    // Only <t> content ever carries text inside an <si>.
                    si.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if ("si".equals(n) && si != null) { strings.add(si.toString()); si = null; }
                    else if (("rPh".equals(n) || "phoneticPr".equals(n)) && phoneticDepth > 0) phoneticDepth--;
                }
            }
            r.close();
        }
        return strings;
    }

    /** Which cell styles format their number as a date, and with what code. */
    private static XlsxStyles readXlsxStyles(ZipFile zip, Map<String, ZipEntry> entries, String path)
            throws IOException, XMLStreamException {
        XlsxStyles styles = new XlsxStyles();
        ZipEntry entry = entries.get(path.toLowerCase(Locale.ROOT));
        if (entry == null) return styles;
        Map<Integer, String> customFormats = new HashMap<>();
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader r = newXmlReader(in);
            boolean inCellXfs = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if ("numFmt".equals(n)) {
                        String id = r.getAttributeValue(null, "numFmtId");
                        String code = r.getAttributeValue(null, "formatCode");
                        if (id != null && code != null) {
                            try { customFormats.put(Integer.parseInt(id.trim()), code); }
                            catch (NumberFormatException ignored) { }
                        }
                    } else if ("cellXfs".equals(n)) {
                        inCellXfs = true;
                    } else if (inCellXfs && "xf".equals(n)) {
                        int fmtId = parseIntOr(r.getAttributeValue(null, "numFmtId"), 0);
                        String code = customFormats.containsKey(fmtId)
                                ? customFormats.get(fmtId) : builtinFormatCode(fmtId);
                        styles.dateStyles.add(isDateFormat(fmtId, code));
                        styles.formatCodes.add(code == null ? "" : code);
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && "cellXfs".equals(r.getLocalName())) {
                    inCellXfs = false;
                }
            }
            r.close();
        }
        return styles;
    }

    /** Read one worksheet part into ragged rows of display text. */
    private static List<List<String>> readXlsxSheet(ZipFile zip, ZipEntry entry, List<String> sst,
            XlsxStyles styles, boolean date1904) throws IOException, XMLStreamException {
        // Sparse first: a sheet may skip rows and columns entirely.
        TreeMap<Integer, TreeMap<Integer, String>> grid = new TreeMap<>();
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader r = newXmlReader(in);
            int rowIdx = -1;
            int colIdx = -1;
            String cellType = null;
            int styleIdx = -1;
            StringBuilder value = null;
            boolean inValue = false;
            boolean inInline = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if ("row".equals(n)) {
                        int declared = parseIntOr(r.getAttributeValue(null, "r"), 0);
                        rowIdx = declared > 0 ? declared - 1 : rowIdx + 1;
                        colIdx = -1;
                    } else if ("c".equals(n)) {
                        String ref = r.getAttributeValue(null, "r");
                        int declared = ref == null ? -1 : columnFromRef(ref);
                        colIdx = declared >= 0 ? declared : colIdx + 1;
                        cellType = r.getAttributeValue(null, "t");
                        styleIdx = parseIntOr(r.getAttributeValue(null, "s"), -1);
                        value = new StringBuilder();
                        inInline = false;
                    } else if ("v".equals(n)) {
                        inValue = true;
                    } else if ("is".equals(n)) {
                        inInline = true;
                    } else if ("t".equals(n) && inInline) {
                        inValue = true;
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS) {
                    if (inValue && value != null) value.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if ("v".equals(n) || ("t".equals(n) && inInline)) {
                        inValue = false;
                    } else if ("is".equals(n)) {
                        inInline = false;
                    } else if ("c".equals(n)) {
                        String text = renderXlsxCell(value == null ? "" : value.toString(),
                                cellType, styleIdx, sst, styles, date1904);
                        if (!text.isEmpty() && rowIdx >= 0 && colIdx >= 0) {
                            grid.computeIfAbsent(rowIdx, k -> new TreeMap<>()).put(colIdx, text);
                        }
                        value = null;
                        cellType = null;
                    }
                }
            }
            r.close();
        }
        return materialize(grid);
    }

    /** One cell's on-screen text, from its raw value plus its type and style. */
    private static String renderXlsxCell(String raw, String type, int styleIdx, List<String> sst,
            XlsxStyles styles, boolean date1904) {
        String v = raw == null ? "" : raw;
        if ("s".equals(type)) {
            int idx = parseIntOr(v.trim(), -1);
            return idx >= 0 && idx < sst.size() ? sst.get(idx) : "";
        }
        if ("inlineStr".equals(type) || "str".equals(type)) return v;
        if ("b".equals(type)) {
            String t = v.trim();
            return t.isEmpty() ? "" : ("0".equals(t) ? "FALSE" : "TRUE");
        }
        if ("e".equals(type)) return v.trim();
        String t = v.trim();
        if (t.isEmpty()) return "";
        double d;
        try {
            d = Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            return t; // an unexpected literal: hand it over untouched
        }
        if (styles.isDate(styleIdx)) {
            return formatSerialDateTime(d, date1904, styles.formatCode(styleIdx));
        }
        return formatNumber(d, t);
    }

    /** "BC12" → 54 (0-based). -1 when the reference has no column letters. */
    private static int columnFromRef(String ref) {
        int col = 0;
        int i = 0;
        while (i < ref.length()) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') col = col * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') col = col * 26 + (c - 'a' + 1);
            else break;
            i++;
        }
        return i == 0 ? -1 : col - 1;
    }

    /** Resolve a relationship target ("worksheets/sheet1.xml", "/xl/x.xml",
     *  "../media/a.png") to a path inside the zip. */
    private static String normalizePath(String target, String base) {
        String path = target.replace('\\', '/');
        if (path.startsWith("/")) {
            path = path.substring(1);
        } else {
            path = base + path;
        }
        // Collapse "." and ".." the way a zip path must be collapsed.
        List<String> parts = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(seg);
            }
        }
        return String.join("/", parts);
    }

    private static String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    private static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static int parseIntOr(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ex) { return fallback; }
    }

    // ==================== Values: numbers, dates, grids ====================

    /** Sparse grid → ragged rows, with trailing blank rows dropped. */
    private static List<List<String>> materialize(TreeMap<Integer, TreeMap<Integer, String>> grid) {
        List<List<String>> rows = new ArrayList<>();
        if (grid.isEmpty()) return rows;
        int lastRow = grid.lastKey();
        for (int r = 0; r <= lastRow; r++) {
            TreeMap<Integer, String> line = grid.get(r);
            if (line == null || line.isEmpty()) {
                rows.add(new ArrayList<>());
                continue;
            }
            int lastCol = line.lastKey();
            List<String> row = new ArrayList<>(lastCol + 1);
            for (int c = 0; c <= lastCol; c++) {
                String v = line.get(c);
                row.add(v == null ? "" : v);
            }
            rows.add(row);
        }
        while (!rows.isEmpty() && isBlankRow(rows.get(rows.size() - 1))) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    /**
     * A number as Excel shows it rather than as Java prints it: whole numbers
     * lose the ".0" (a "5" in the sheet must not import as "5.0"), and anything
     * else keeps the exact text the file stored when there is one.
     */
    static String formatNumber(double d, String raw) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return raw == null ? "" : raw;
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        if (raw != null && !raw.trim().isEmpty()) return raw.trim();
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** The built-in number formats every workbook shares (ECMA-376 §18.8.30). */
    private static String builtinFormatCode(int id) {
        switch (id) {
            case 14: return "m/d/yy";
            case 15: return "d-mmm-yy";
            case 16: return "d-mmm";
            case 17: return "mmm-yy";
            case 18: return "h:mm AM/PM";
            case 19: return "h:mm:ss AM/PM";
            case 20: return "h:mm";
            case 21: return "h:mm:ss";
            case 22: return "m/d/yy h:mm";
            case 45: return "mm:ss";
            case 46: return "[h]:mm:ss";
            case 47: return "mm:ss.0";
            default: return "";
        }
    }

    /**
     * True when a number carrying this format should read as a date or a time.
     * Mirrors the rule every spreadsheet reader uses: strip the parts of a
     * format code that are not format characters (colours, conditions, quoted
     * literals, escapes) and see whether what remains is built only from
     * date/time characters.
     */
    static boolean isDateFormat(int formatId, String code) {
        if (formatId == 0) return false;                       // General
        if ((formatId >= 14 && formatId <= 22)
                || (formatId >= 27 && formatId <= 36)
                || (formatId >= 45 && formatId <= 47)
                || (formatId >= 50 && formatId <= 58)
                || (formatId >= 71 && formatId <= 81)) {
            return true;
        }
        if (code == null || code.trim().isEmpty()) return false;
        String fs = cleanFormatCode(code);
        if (fs.isEmpty()) return false;
        if (!fs.matches("^[\\[\\]yYmMdDhHsS\\-T/年月日,. :\\\\]+[0-9]*[ampAMP/]*$")) return false;
        return fs.matches(".*[yYdDhHsS].*") || fs.matches(".*[mM].*");
    }

    /** Drop the pieces of a number format that say nothing about date-ness:
     *  the section separators, colours/conditions in brackets, quoted literals
     *  and backslash escapes. */
    private static String cleanFormatCode(String code) {
        String fs = code;
        int semi = fs.indexOf(';');
        if (semi >= 0) fs = fs.substring(0, semi);             // positive section only
        fs = fs.replaceAll("\\\\.", "");                        // \- escapes
        fs = fs.replaceAll("\"[^\"]*\"", "");                   // "literal text"
        fs = fs.replaceAll("\\[\\$[^\\]]*\\]", "");             // [$-409] locale tags
        fs = fs.replaceAll("\\[(?!h|H|m|M|s|S)[^\\]]*\\]", ""); // [Red], [>100]
        fs = fs.replaceAll("[*_].", "");                        // fill / skip-width
        return fs.trim();
    }

    /**
     * An Excel serial number as text. The 1900 workbook epoch carries the famous
     * phantom 29 February 1900, so serials from 61 onward count from
     * 1899-12-30 and earlier ones from 1899-12-31 — that is what makes
     * "1 January 1900" come back as 1 January 1900.
     */
    static String formatSerialDateTime(double serial, boolean date1904, String code) {
        String fs = cleanFormatCode(code == null ? "" : code);
        boolean hasTime = fs.matches(".*[hHsS].*");
        boolean hasDate = fs.matches(".*[yYdD].*") || (!hasTime && fs.matches(".*[mM].*"));
        if (!hasDate && !hasTime) hasDate = true;

        double days = Math.floor(serial);
        double frac = serial - days;
        // Round to the nearest second: 10:30 must not come back as 10:29:59.
        long secondsOfDay = Math.round(frac * 86400.0);
        if (secondsOfDay >= 86400L) { secondsOfDay -= 86400L; days += 1; }

        LocalDateTime when;
        if (date1904) {
            when = LocalDateTime.of(1904, 1, 1, 0, 0).plusDays((long) days);
        } else {
            LocalDateTime epoch = days >= 61
                    ? LocalDateTime.of(1899, 12, 30, 0, 0)
                    : LocalDateTime.of(1899, 12, 31, 0, 0);
            when = epoch.plusDays((long) days);
        }
        when = when.plusSeconds(secondsOfDay);

        if (hasDate && hasTime) {
            return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d",
                    when.getYear(), when.getMonthValue(), when.getDayOfMonth(),
                    when.getHour(), when.getMinute(), when.getSecond());
        }
        if (hasTime) {
            long totalSeconds = Math.round(serial * 86400.0);
            long h = totalSeconds / 3600, m = (totalSeconds % 3600) / 60, s = totalSeconds % 60;
            return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                when.getYear(), when.getMonthValue(), when.getDayOfMonth());
    }

    // ============ .xls (OLE2 compound file + BIFF5/BIFF8 records) ============

    private static int u8(byte[] b, int off) {
        return off >= 0 && off < b.length ? b[off] & 0xFF : 0;
    }

    private static int u16(byte[] b, int off) {
        return off + 1 < b.length ? (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) : 0;
    }

    private static int i32(byte[] b, int off) {
        return off + 3 < b.length
                ? (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                        | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24)
                : 0;
    }

    private static List<Sheet> readXls(File file) throws IOException {
        byte[] raw = Files.readAllBytes(file.toPath());
        byte[] workbook = extractOleStream(raw, "workbook", "book");
        if (workbook == null) {
            throw new IOException(file.getName() + " is a compound file but holds no Excel workbook.\n"
                    + "If this is a Word or PowerPoint file, pick a spreadsheet instead.");
        }
        return parseBiff(workbook, file.getName());
    }

    /**
     * Pull one named stream out of an OLE2 (compound binary) file — the
     * container an .xls lives in. Sectors are chained through the FAT; small
     * streams live inside the root entry's mini stream, chained through the
     * mini FAT. Returns null when no entry matches any of {@code names}.
     */
    private static byte[] extractOleStream(byte[] f, String... names) throws IOException {
        if (f.length < 512) throw new IOException("The file is too small to be a workbook.");
        int sectorSize = 1 << u16(f, 30);
        int miniSectorSize = 1 << u16(f, 32);
        if (sectorSize < 128 || sectorSize > 1 << 20 || miniSectorSize < 8 || miniSectorSize > sectorSize) {
            throw new IOException("The workbook's internal layout is not readable (bad sector size).");
        }
        int numFatSectors = i32(f, 44);
        int dirStart = i32(f, 48);
        int miniCutoff = i32(f, 56);
        int miniFatStart = i32(f, 60);
        int numMiniFat = i32(f, 64);
        int difatStart = i32(f, 68);
        int numDifat = i32(f, 72);
        if (miniCutoff <= 0) miniCutoff = 4096;

        int[] fat = buildFat(f, sectorSize, numFatSectors, difatStart, numDifat);
        byte[] dir = readSectorChain(f, fat, dirStart, sectorSize, -1);
        if (dir.length < 128) throw new IOException("The workbook has no directory — the file looks damaged.");

        // Entry 0 is the root: its chain IS the mini stream.
        long rootSize = i32(dir, 120) & 0xFFFFFFFFL;
        int rootStart = i32(dir, 116);
        byte[] miniStream = null;
        int[] miniFat = null;

        for (int off = 0; off + 128 <= dir.length; off += 128) {
            int type = u8(dir, off + 66);
            if (type != 2) continue;                            // 2 = stream
            int nameLen = u16(dir, off + 64);
            if (nameLen <= 2 || nameLen > 64) continue;
            String name = new String(dir, off, nameLen - 2, StandardCharsets.UTF_16LE).trim();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean match = false;
            for (String want : names) if (lower.equals(want)) { match = true; break; }
            if (!match) continue;

            long size = i32(dir, off + 120) & 0xFFFFFFFFL;
            int start = i32(dir, off + 116);
            if (size < miniCutoff) {
                if (miniStream == null) {
                    miniStream = readSectorChain(f, fat, rootStart, sectorSize, rootSize);
                    byte[] miniFatBytes = readSectorChain(f, fat, miniFatStart, sectorSize,
                            (long) numMiniFat * sectorSize);
                    miniFat = toIntArray(miniFatBytes);
                }
                return readMiniChain(miniStream, miniFat, start, miniSectorSize, size);
            }
            return readSectorChain(f, fat, start, sectorSize, size);
        }
        return null;
    }

    /** The file allocation table, gathered from the header's DIFAT and any
     *  DIFAT continuation sectors. */
    private static int[] buildFat(byte[] f, int sectorSize, int numFatSectors,
            int difatStart, int numDifat) {
        List<Integer> fatSectors = new ArrayList<>();
        for (int i = 0; i < 109 && fatSectors.size() < numFatSectors; i++) {
            int s = i32(f, 76 + i * 4);
            if (s < 0) break;
            fatSectors.add(s);
        }
        int sector = difatStart;
        int perSector = sectorSize / 4 - 1;
        int guard = 0;
        while (sector >= 0 && fatSectors.size() < numFatSectors && guard++ <= numDifat + 8) {
            long off = ((long) sector + 1) * sectorSize;
            if (off + sectorSize > f.length) break;
            for (int i = 0; i < perSector && fatSectors.size() < numFatSectors; i++) {
                int s = i32(f, (int) off + i * 4);
                if (s < 0) continue;
                fatSectors.add(s);
            }
            sector = i32(f, (int) off + perSector * 4);
        }
        int[] fat = new int[fatSectors.size() * (sectorSize / 4)];
        int w = 0;
        for (int fs : fatSectors) {
            long off = ((long) fs + 1) * sectorSize;
            for (int i = 0; i < sectorSize / 4; i++, w++) {
                fat[w] = (off + (long) i * 4 + 4 <= f.length) ? i32(f, (int) off + i * 4) : -1;
            }
        }
        return fat;
    }

    private static int[] toIntArray(byte[] b) {
        int[] out = new int[b.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = i32(b, i * 4);
        return out;
    }

    /** Walk a normal sector chain. A negative sector number ends it (the spec's
     *  ENDOFCHAIN/FREESECT/FATSECT markers are all negative as signed ints). */
    private static byte[] readSectorChain(byte[] f, int[] fat, int start, int sectorSize, long size) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int sector = start;
        int guard = 0;
        while (sector >= 0 && guard++ <= fat.length + 1) {
            long off = ((long) sector + 1) * sectorSize;
            if (off < 0 || off + sectorSize > f.length) break;
            out.write(f, (int) off, sectorSize);
            if (sector >= fat.length) break;
            sector = fat[sector];
        }
        byte[] data = out.toByteArray();
        if (size > 0 && size < data.length) data = Arrays.copyOf(data, (int) size);
        return data;
    }

    /** Walk a mini-stream chain (streams under the 4 KB cutoff). */
    private static byte[] readMiniChain(byte[] miniStream, int[] miniFat, int start,
            int miniSectorSize, long size) {
        if (miniStream == null || miniFat == null) return new byte[0];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int sector = start;
        int guard = 0;
        while (sector >= 0 && guard++ <= miniFat.length + 1) {
            long off = (long) sector * miniSectorSize;
            if (off < 0 || off + miniSectorSize > miniStream.length) break;
            out.write(miniStream, (int) off, miniSectorSize);
            if (sector >= miniFat.length) break;
            sector = miniFat[sector];
        }
        byte[] data = out.toByteArray();
        if (size > 0 && size < data.length) data = Arrays.copyOf(data, (int) size);
        return data;
    }

    // ---- BIFF records ----

    private static final int REC_FORMULA = 0x0006, REC_EOF = 0x000A, REC_DATEMODE = 0x0022,
            REC_FILEPASS = 0x002F, REC_XF = 0x00E0, REC_BOUNDSHEET = 0x0085, REC_CONTINUE = 0x003C,
            REC_MULRK = 0x00BD, REC_RSTRING = 0x00D6, REC_SST = 0x00FC, REC_LABELSST = 0x00FD,
            REC_NUMBER = 0x0203, REC_LABEL = 0x0204, REC_BOOLERR = 0x0205, REC_STRING = 0x0207,
            REC_FORMAT = 0x041E, REC_RK = 0x027E, REC_BOF = 0x0809;

    /** One BIFF record: its type plus its payload, with any CONTINUE payloads
     *  that follow kept as further chunks (a long shared-string table is split
     *  across them, and a string may straddle the seam). */
    private static final class BiffRecord {
        final int type;
        final List<byte[]> chunks = new ArrayList<>();
        BiffRecord(int type) { this.type = type; }
        byte[] data() { return chunks.get(0); }
    }

    private static BiffRecord nextRecord(byte[] wb, int[] pos) {
        int p = pos[0];
        if (p + 4 > wb.length) return null;
        int type = u16(wb, p);
        int len = u16(wb, p + 2);
        p += 4;
        if (p + len > wb.length) len = Math.max(0, wb.length - p);
        BiffRecord rec = new BiffRecord(type);
        rec.chunks.add(Arrays.copyOfRange(wb, p, p + len));
        p += len;
        while (type != REC_CONTINUE && p + 4 <= wb.length && u16(wb, p) == REC_CONTINUE) {
            int clen = u16(wb, p + 2);
            p += 4;
            if (p + clen > wb.length) clen = Math.max(0, wb.length - p);
            rec.chunks.add(Arrays.copyOfRange(wb, p, p + clen));
            p += clen;
        }
        pos[0] = p;
        return rec;
    }

    /** A byte cursor that walks a record and its CONTINUE chunks as one run. */
    private static final class ChunkCursor {
        private final List<byte[]> chunks;
        private int chunk = 0;
        private int pos = 0;

        ChunkCursor(List<byte[]> chunks) { this.chunks = chunks; }

        private void settle() {
            while (chunk < chunks.size() && pos >= chunks.get(chunk).length) { chunk++; pos = 0; }
        }

        boolean exhausted() { settle(); return chunk >= chunks.size(); }

        int remainingInChunk() { settle(); return chunk < chunks.size() ? chunks.get(chunk).length - pos : 0; }

        /** Step to the next chunk even when the current one still has bytes —
         *  how a string continues past a CONTINUE seam. */
        boolean nextChunk() {
            if (chunk + 1 < chunks.size()) { chunk++; pos = 0; return true; }
            return false;
        }

        int readU8() { settle(); if (chunk >= chunks.size()) return 0; return chunks.get(chunk)[pos++] & 0xFF; }

        int readU16() { int a = readU8(); return a | (readU8() << 8); }

        int readI32() { return readU16() | (readU16() << 16); }

        void skip(int n) {
            while (n > 0) {
                settle();
                if (chunk >= chunks.size()) return;
                int avail = chunks.get(chunk).length - pos;
                int take = Math.min(avail, n);
                pos += take;
                n -= take;
            }
        }

        /** {@code count} characters out of the CURRENT chunk only. */
        String readChars(int count, boolean wide) {
            settle();
            if (chunk >= chunks.size() || count <= 0) return "";
            byte[] b = chunks.get(chunk);
            if (wide) {
                int bytes = Math.min(count * 2, b.length - pos);
                String s = new String(b, pos, bytes, StandardCharsets.UTF_16LE);
                pos += bytes;
                return s;
            }
            int bytes = Math.min(count, b.length - pos);
            char[] cs = new char[bytes];
            for (int i = 0; i < bytes; i++) cs[i] = (char) (b[pos + i] & 0xFF);
            pos += bytes;
            return new String(cs);
        }

        /** A pre-BIFF8 byte string, read in the workbook's ANSI code page. */
        String readByteString(int len) {
            StringBuilder sb = new StringBuilder(len);
            int left = len;
            while (left > 0 && !exhausted()) {
                int take = Math.min(left, remainingInChunk());
                sb.append(readChars(take, false));
                left -= take;
                if (left > 0 && !nextChunk()) break;
            }
            String latin = sb.toString();
            try {
                return new String(latin.getBytes(StandardCharsets.ISO_8859_1),
                        Charset.forName("windows-1252"));
            } catch (Exception ex) {
                return latin;
            }
        }
    }

    /** A BIFF8 string whose length is a 16-bit count (SST entries, LABEL, STRING). */
    private static String readUnicodeString(ChunkCursor c) {
        int cch = c.readU16();
        return readUnicodeStringBody(c, cch);
    }

    /**
     * The body of a BIFF8 string: an options byte says whether the characters
     * are compressed (one byte each) or 16-bit, and whether rich-text runs and
     * phonetic data trail it. When the string crosses a CONTINUE seam the next
     * chunk starts with a fresh options byte — that is the one thing that makes
     * this format awkward, and the one thing every naive reader gets wrong.
     */
    private static String readUnicodeStringBody(ChunkCursor c, int cch) {
        if (cch <= 0) return "";
        int opts = c.readU8();
        boolean wide = (opts & 0x01) != 0;
        boolean farEast = (opts & 0x04) != 0;
        boolean rich = (opts & 0x08) != 0;
        int runs = rich ? c.readU16() : 0;
        int phonetic = farEast ? c.readI32() : 0;

        StringBuilder sb = new StringBuilder(cch);
        int read = 0;
        while (read < cch) {
            int avail = c.remainingInChunk();
            if (avail <= 0) break;
            int canRead = wide ? avail / 2 : avail;
            if (canRead <= 0) {
                if (!c.nextChunk()) break;
                opts = c.readU8();
                wide = (opts & 0x01) != 0;
                continue;
            }
            int take = Math.min(cch - read, canRead);
            sb.append(c.readChars(take, wide));
            read += take;
            if (read < cch) {
                if (!c.nextChunk()) break;
                opts = c.readU8();
                wide = (opts & 0x01) != 0;
            }
        }
        if (runs > 0) c.skip(runs * 4);
        if (phonetic > 0) c.skip(phonetic);
        return sb.toString();
    }

    /** Decode an RK number: a double squeezed into 32 bits, optionally as a
     *  30-bit integer and optionally divided by 100. */
    private static double decodeRk(int rk) {
        double v;
        if ((rk & 0x02) != 0) {
            v = rk >> 2;                                  // signed 30-bit integer
        } else {
            v = Double.longBitsToDouble(((long) (rk & 0xFFFFFFFC)) << 32);
        }
        return (rk & 0x01) != 0 ? v / 100.0 : v;
    }

    private static String errorText(int code) {
        switch (code) {
            case 0x00: return "#NULL!";
            case 0x07: return "#DIV/0!";
            case 0x0F: return "#VALUE!";
            case 0x17: return "#REF!";
            case 0x1D: return "#NAME?";
            case 0x24: return "#NUM!";
            case 0x2A: return "#N/A";
            default:   return "#ERR!";
        }
    }

    /** Read the workbook stream: globals first (names, shared strings, formats),
     *  then one pass per worksheet substream. */
    private static List<Sheet> parseBiff(byte[] wb, String fileName) throws IOException {
        int[] pos = {0};
        BiffRecord rec = nextRecord(wb, pos);
        if (rec == null || rec.type != REC_BOF) {
            throw new IOException(fileName + " does not start with a workbook record — "
                    + "the file may be damaged.");
        }
        int version = u16(rec.data(), 0);
        if (version != 0 && version < 0x0500) {
            throw new IOException(fileName + " was written by Excel 4 or older, which this app "
                    + "cannot read.\nOpen it in Excel and save it as .xlsx or CSV UTF-8 first.");
        }
        boolean biff8 = version >= 0x0600;

        boolean date1904 = false;
        List<String> sst = new ArrayList<>();
        List<Integer> xfFormats = new ArrayList<>();
        Map<Integer, String> formats = new HashMap<>();
        List<String> sheetNames = new ArrayList<>();
        List<Integer> sheetOffsets = new ArrayList<>();

        while ((rec = nextRecord(wb, pos)) != null) {
            if (rec.type == REC_EOF) break;
            byte[] d = rec.data();
            switch (rec.type) {
                case REC_FILEPASS:
                    throw new IOException(fileName + " is password-protected, so its contents "
                            + "cannot be read.\nRemove the password in Excel (File → Info → "
                            + "Protect Workbook) and save it again.");
                case REC_DATEMODE:
                    date1904 = u16(d, 0) != 0;
                    break;
                case REC_XF:
                    xfFormats.add(u16(d, 2));
                    break;
                case REC_FORMAT: {
                    ChunkCursor c = new ChunkCursor(rec.chunks);
                    int id = c.readU16();
                    String code = biff8 ? readUnicodeString(c) : c.readByteString(c.readU8());
                    formats.put(id, code);
                    break;
                }
                case REC_BOUNDSHEET: {
                    int offset = i32(d, 0);
                    ChunkCursor c = new ChunkCursor(rec.chunks);
                    c.skip(6);
                    int cch = c.readU8();
                    String name = biff8 ? readUnicodeStringBody(c, cch) : c.readByteString(cch);
                    int sheetType = u8(d, 5) & 0x0F;
                    if (sheetType == 0) {                    // 0 = worksheet (not chart/macro)
                        sheetNames.add(name);
                        sheetOffsets.add(offset);
                    }
                    break;
                }
                case REC_SST: {
                    ChunkCursor c = new ChunkCursor(rec.chunks);
                    c.skip(4);
                    int unique = c.readI32();
                    for (int i = 0; i < unique && !c.exhausted(); i++) {
                        sst.add(readUnicodeString(c));
                    }
                    break;
                }
                default:
                    break;
            }
        }

        List<Sheet> sheets = new ArrayList<>();
        if (sheetOffsets.isEmpty()) {
            // No BOUNDSHEET records: whatever substream follows the globals is it.
            sheets.add(new Sheet(stripExtension(fileName),
                    parseBiffSheet(wb, pos[0], biff8, sst, xfFormats, formats, date1904)));
        } else {
            for (int i = 0; i < sheetOffsets.size(); i++) {
                int offset = sheetOffsets.get(i);
                if (offset < 0 || offset >= wb.length) continue;
                sheets.add(new Sheet(sheetNames.get(i),
                        parseBiffSheet(wb, offset, biff8, sst, xfFormats, formats, date1904)));
            }
        }
        if (sheets.isEmpty()) throw new IOException("No worksheets found in " + fileName + ".");
        return sheets;
    }

    /** One worksheet substream: every cell record it carries, as display text. */
    private static List<List<String>> parseBiffSheet(byte[] wb, int offset, boolean biff8,
            List<String> sst, List<Integer> xfFormats, Map<Integer, String> formats,
            boolean date1904) {
        TreeMap<Integer, TreeMap<Integer, String>> grid = new TreeMap<>();
        int[] pos = {offset};
        int depth = 0;
        int pendingRow = -1, pendingCol = -1;                  // a formula awaiting its STRING
        BiffRecord rec;
        while ((rec = nextRecord(wb, pos)) != null) {
            if (rec.type == REC_BOF) { depth++; continue; }
            if (rec.type == REC_EOF) { if (--depth <= 0) break; continue; }
            if (depth != 1) continue;                          // inside an embedded chart/object
            byte[] d = rec.data();
            switch (rec.type) {
                case REC_LABELSST: {
                    int idx = i32(d, 6);
                    if (idx >= 0 && idx < sst.size()) put(grid, u16(d, 0), u16(d, 2), sst.get(idx));
                    break;
                }
                case REC_LABEL:
                case REC_RSTRING: {
                    ChunkCursor c = new ChunkCursor(rec.chunks);
                    c.skip(6);
                    String text = biff8 ? readUnicodeString(c) : c.readByteString(c.readU16());
                    put(grid, u16(d, 0), u16(d, 2), text);
                    break;
                }
                case REC_NUMBER: {
                    double v = Double.longBitsToDouble(
                            (i32(d, 6) & 0xFFFFFFFFL) | ((long) i32(d, 10) << 32));
                    put(grid, u16(d, 0), u16(d, 2),
                            numberToText(v, u16(d, 4), xfFormats, formats, date1904));
                    break;
                }
                case REC_RK: {
                    double v = decodeRk(i32(d, 6));
                    put(grid, u16(d, 0), u16(d, 2),
                            numberToText(v, u16(d, 4), xfFormats, formats, date1904));
                    break;
                }
                case REC_MULRK: {
                    int row = u16(d, 0), first = u16(d, 2);
                    int count = (d.length - 6) / 6;
                    for (int i = 0; i < count; i++) {
                        int xf = u16(d, 4 + i * 6);
                        double v = decodeRk(i32(d, 6 + i * 6));
                        put(grid, row, first + i, numberToText(v, xf, xfFormats, formats, date1904));
                    }
                    break;
                }
                case REC_BOOLERR: {
                    int val = u8(d, 6);
                    boolean isError = u8(d, 7) != 0;
                    put(grid, u16(d, 0), u16(d, 2), isError ? errorText(val) : (val != 0 ? "TRUE" : "FALSE"));
                    break;
                }
                case REC_FORMULA: {
                    int row = u16(d, 0), col = u16(d, 2), xf = u16(d, 4);
                    if (u16(d, 12) == 0xFFFF) {
                        int kind = u8(d, 6);
                        if (kind == 0) {                       // string, in the next STRING record
                            pendingRow = row; pendingCol = col;
                        } else if (kind == 1) {
                            put(grid, row, col, u8(d, 8) != 0 ? "TRUE" : "FALSE");
                        } else if (kind == 2) {
                            put(grid, row, col, errorText(u8(d, 8)));
                        }
                        // kind 3 = empty string: nothing to show
                    } else {
                        double v = Double.longBitsToDouble(
                                (i32(d, 6) & 0xFFFFFFFFL) | ((long) i32(d, 10) << 32));
                        put(grid, row, col, numberToText(v, xf, xfFormats, formats, date1904));
                    }
                    break;
                }
                case REC_STRING: {
                    if (pendingRow >= 0) {
                        ChunkCursor c = new ChunkCursor(rec.chunks);
                        String text = biff8 ? readUnicodeString(c) : c.readByteString(c.readU16());
                        put(grid, pendingRow, pendingCol, text);
                        pendingRow = pendingCol = -1;
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return materialize(grid);
    }

    private static void put(TreeMap<Integer, TreeMap<Integer, String>> grid, int row, int col, String text) {
        if (text == null || text.isEmpty() || row < 0 || col < 0) return;
        grid.computeIfAbsent(row, k -> new TreeMap<>()).put(col, text);
    }

    /** A BIFF number as text, dates included, using the cell's own format. */
    private static String numberToText(double d, int xfIndex, List<Integer> xfFormats,
            Map<Integer, String> formats, boolean date1904) {
        int fmtId = xfIndex >= 0 && xfIndex < xfFormats.size() ? xfFormats.get(xfIndex) : 0;
        String code = formats.containsKey(fmtId) ? formats.get(fmtId) : builtinFormatCode(fmtId);
        if (isDateFormat(fmtId, code)) return formatSerialDateTime(d, date1904, code);
        return formatNumber(d, null);
    }
}
