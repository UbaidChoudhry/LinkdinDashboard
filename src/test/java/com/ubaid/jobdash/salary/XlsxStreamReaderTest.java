package com.ubaid.jobdash.salary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxStreamReaderTest {

    @TempDir
    Path tempDir;

    private final XlsxStreamReader reader = new XlsxStreamReader();

    @Test
    void readsRowsResolvingSharedInlineNumericAndColumnGaps() {
        Path xlsx = XlsxFixtures.write(tempDir.resolve("tiny.xlsx"),
                List.of("NAME", "STATE", "WAGE", "NOTE"),
                List.of(
                        Arrays.asList("Acme Corp", "CA", "120000", "inline:point wage"),
                        Arrays.asList("Beta LLC", null, "95000", "shared note"),
                        Arrays.asList("Acme Corp", "NY", "130000", "shared note"),
                        Arrays.asList("Gamma Inc", "TX", "88000", null)));

        List<Map<String, String>> rows = new ArrayList<>();
        reader.forEachRow(xlsx, row -> {
            rows.add(Map.copyOf(row));
            return true;
        });

        assertThat(rows).hasSize(4);
        assertThat(rows.get(0))
                .containsEntry("NAME", "Acme Corp")
                .containsEntry("STATE", "CA")
                .containsEntry("WAGE", "120000")
                .containsEntry("NOTE", "point wage");
        // Column gap: STATE omitted entirely for row 2 -> empty string, not a shifted value.
        assertThat(rows.get(1)).containsEntry("STATE", "").containsEntry("WAGE", "95000");
        assertThat(rows.get(3)).containsEntry("NOTE", "").containsEntry("STATE", "TX");
    }

    @Test
    void stopsEarlyWhenHandlerReturnsFalse() {
        Path xlsx = XlsxFixtures.write(tempDir.resolve("stop.xlsx"),
                List.of("A"),
                List.of(List.of("one"), List.of("two"), List.of("three")));

        List<String> seen = new ArrayList<>();
        reader.forEachRow(xlsx, row -> {
            seen.add(row.get("A"));
            return seen.size() < 2;
        });

        assertThat(seen).containsExactly("one", "two");
    }

    @Test
    void translatesMultiLetterColumnReferences() {
        assertThat(XlsxStreamReader.columnIndex("A1")).isZero();
        assertThat(XlsxStreamReader.columnIndex("Z1")).isEqualTo(25);
        assertThat(XlsxStreamReader.columnIndex("AA1")).isEqualTo(26);
        assertThat(XlsxStreamReader.columnIndex("AB12")).isEqualTo(27);
    }
}
