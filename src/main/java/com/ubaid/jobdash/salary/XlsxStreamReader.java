package com.ubaid.jobdash.salary;

import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Dependency-free streaming reader for the DOL LCA disclosure {@code .xlsx} files. Uses only the
 * JDK ({@link ZipFile} + StAX): {@code xl/sharedStrings.xml} is loaded into a list, then
 * {@code xl/worksheets/sheet1.xml} is streamed row by row so the (~500&nbsp;MB uncompressed)
 * sheet is never held in memory. Row 1 is treated as the header; each subsequent row is handed
 * to the handler as a {@code HEADER_NAME -> cell text} map.
 */
@Component
public class XlsxStreamReader {

    /** Handles one data row; return {@code false} to stop the scan early. */
    @FunctionalInterface
    public interface RowHandler {
        boolean handle(Map<String, String> row);
    }

    private static final String SHARED_STRINGS = "xl/sharedStrings.xml";
    private static final String SHEET1 = "xl/worksheets/sheet1.xml";

    private final XMLInputFactory xmlInputFactory = newFactory();

    /**
     * Streams every data row of {@code xlsx}, invoking {@code handler} with a map keyed by the
     * header-row names (missing cells map to {@code ""}). Stops when the handler returns
     * {@code false} or the sheet ends.
     */
    public void forEachRow(Path xlsx, RowHandler handler) {
        try (ZipFile zip = new ZipFile(xlsx.toFile())) {
            List<String> sharedStrings = readSharedStrings(zip);
            streamSheet(zip, sharedStrings, handler);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read xlsx " + xlsx, e);
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Malformed xlsx " + xlsx, e);
        }
    }

    private List<String> readSharedStrings(ZipFile zip) throws IOException, XMLStreamException {
        List<String> out = new ArrayList<>();
        ZipEntry entry = zip.getEntry(SHARED_STRINGS);
        if (entry == null) {
            return out;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader r = xmlInputFactory.createXMLStreamReader(in);
            StringBuilder si = null;
            boolean inText = false;
            while (r.hasNext()) {
                int event = r.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String name = r.getLocalName();
                        if ("si".equals(name)) {
                            si = new StringBuilder();
                        } else if ("t".equals(name)) {
                            inText = true;
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (inText && si != null) {
                            si.append(r.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        String name = r.getLocalName();
                        if ("t".equals(name)) {
                            inText = false;
                        } else if ("si".equals(name) && si != null) {
                            out.add(si.toString());
                            si = null;
                        }
                    }
                    default -> {
                    }
                }
            }
            r.close();
        }
        return out;
    }

    private void streamSheet(ZipFile zip, List<String> sharedStrings, RowHandler handler)
            throws IOException, XMLStreamException {
        ZipEntry entry = zip.getEntry(SHEET1);
        if (entry == null) {
            throw new IllegalStateException("xlsx has no " + SHEET1);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader r = xmlInputFactory.createXMLStreamReader(in);

            Map<Integer, String> header = null;
            Map<Integer, String> cells = new HashMap<>();
            int colIndex = -1;
            String cellType = null;
            StringBuilder value = new StringBuilder();
            StringBuilder inline = new StringBuilder();
            boolean inV = false;
            boolean inInlineT = false;

            while (r.hasNext()) {
                int event = r.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        switch (r.getLocalName()) {
                            case "row" -> cells.clear();
                            case "c" -> {
                                colIndex = columnIndex(r.getAttributeValue(null, "r"));
                                cellType = r.getAttributeValue(null, "t");
                                value.setLength(0);
                                inline.setLength(0);
                            }
                            case "v" -> inV = true;
                            case "t" -> inInlineT = true;
                            default -> {
                            }
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (inV) {
                            value.append(r.getText());
                        } else if (inInlineT) {
                            inline.append(r.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        switch (r.getLocalName()) {
                            case "v" -> inV = false;
                            case "t" -> inInlineT = false;
                            case "c" -> {
                                if (colIndex >= 0) {
                                    cells.put(colIndex, resolveCell(cellType, value.toString(),
                                            inline.toString(), sharedStrings));
                                }
                            }
                            case "row" -> {
                                if (header == null) {
                                    header = new LinkedHashMap<>(cells);
                                } else if (!emitRow(header, cells, handler)) {
                                    r.close();
                                    return;
                                }
                            }
                            default -> {
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            r.close();
        }
    }

    private static boolean emitRow(Map<Integer, String> header, Map<Integer, String> cells,
                                   RowHandler handler) {
        Map<String, String> row = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> h : header.entrySet()) {
            String name = h.getValue();
            if (name == null || name.isEmpty()) {
                continue;
            }
            row.put(name, cells.getOrDefault(h.getKey(), ""));
        }
        return handler.handle(row);
    }

    private static String resolveCell(String type, String value, String inline, List<String> shared) {
        if ("s".equals(type)) {
            String idx = value.trim();
            if (idx.isEmpty()) {
                return "";
            }
            int i = Integer.parseInt(idx);
            return i >= 0 && i < shared.size() ? shared.get(i) : "";
        }
        if ("inlineStr".equals(type)) {
            return inline;
        }
        // "str" (formula result), "n"/"b"/"d" and untyped cells all carry text in <v>.
        return value;
    }

    /** Translates the column-letter prefix of a cell reference ({@code "AB12"}) to a 0-based index. */
    static int columnIndex(String cellRef) {
        if (cellRef == null || cellRef.isEmpty()) {
            return -1;
        }
        int col = 0;
        for (int i = 0; i < cellRef.length(); i++) {
            char c = cellRef.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                col = col * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                col = col * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }
        return col - 1;
    }

    private static XMLInputFactory newFactory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        return f;
    }
}
