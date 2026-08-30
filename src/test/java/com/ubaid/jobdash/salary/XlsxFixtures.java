package com.ubaid.jobdash.salary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test helper: writes a minimal but valid {@code .xlsx} (a ZIP of {@code [Content_Types].xml},
 * {@code xl/workbook.xml}, {@code xl/worksheets/sheet1.xml}, {@code xl/sharedStrings.xml}) so
 * {@link XlsxStreamReader} can be exercised without Apache POI.
 * <p>
 * Row 1 is the header. Cell conventions in the {@code rows} data:
 * <ul>
 *   <li>{@code null} &rarr; the cell is omitted entirely (a column gap);</li>
 *   <li>a bare number &rarr; written as a numeric {@code <v>} cell;</li>
 *   <li>a value prefixed {@code "inline:"} &rarr; written as an {@code inlineStr} cell;</li>
 *   <li>anything else &rarr; written as a shared-string cell.</li>
 * </ul>
 */
final class XlsxFixtures {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private XlsxFixtures() {
    }

    static Path write(Path file, List<String> headers, List<List<String>> rows) {
        List<String> shared = new java.util.ArrayList<>();

        StringBuilder sheet = new StringBuilder();
        sheet.append("<?xml version=\"1.0\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetData>");

        appendRow(sheet, shared, 1, headers);
        for (int i = 0; i < rows.size(); i++) {
            appendRow(sheet, shared, i + 2, rows.get(i));
        }
        sheet.append("</sheetData></worksheet>");

        StringBuilder sst = new StringBuilder();
        sst.append("<?xml version=\"1.0\"?>")
                .append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
                .append(shared.size()).append("\" uniqueCount=\"").append(shared.size()).append("\">");
        for (String s : shared) {
            sst.append("<si><t>").append(escape(s)).append("</t></si>");
        }
        sst.append("</sst>");

        String contentTypes = "<?xml version=\"1.0\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"
                + "</Types>";

        String workbook = "<?xml version=\"1.0\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "[Content_Types].xml", contentTypes);
            put(zip, "xl/workbook.xml", workbook);
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            put(zip, "xl/sharedStrings.xml", sst.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    private static void appendRow(StringBuilder sheet, List<String> shared, int rowNum, List<String> values) {
        sheet.append("<row r=\"").append(rowNum).append("\">");
        for (int c = 0; c < values.size(); c++) {
            String v = values.get(c);
            if (v == null) {
                continue;
            }
            String ref = columnName(c) + rowNum;
            if (v.startsWith("inline:")) {
                sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>")
                        .append(escape(v.substring("inline:".length()))).append("</t></is></c>");
            } else if (NUMBER.matcher(v).matches()) {
                sheet.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
            } else {
                int idx = shared.indexOf(v);
                if (idx < 0) {
                    idx = shared.size();
                    shared.add(v);
                }
                sheet.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(idx).append("</v></c>");
            }
        }
        sheet.append("</row>");
    }

    static String columnName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
