package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.store.LcaWageRepository;
import com.ubaid.jobdash.store.LcaWageRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Imports a DOL LCA disclosure {@code .xlsx} into the {@code lca_wage} aggregate table:
 * streams the sheet, keeps only {@code Certified*} rows within the {@code salary.max-data-age}
 * window, annualizes each wage offer, then groups by {@code (employerKey, socCode, state)} plus
 * a national {@code (employerKey, socCode, "")} rollup and stores per-group wage percentiles.
 */
@Service
public class LcaImportService {

    private static final Logger log = LoggerFactory.getLogger(LcaImportService.class);

    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    private static final DateTimeFormatter US_SLASH = DateTimeFormatter.ofPattern("M/d/yyyy");
    private static final int FLUSH_EVERY = 5000;
    private static final double MIN_WAGE = 15_000;
    private static final double MAX_WAGE = 1_000_000;

    /** Outcome of one import run. Dates are ISO-8601 ({@code yyyy-MM-dd}) or null when nothing was kept. */
    public record ImportResult(long rowsRead, long rowsKept, int groupsWritten,
                               String oldestKept, String newestKept) {
    }

    private final XlsxStreamReader reader;
    private final LcaWageRepository lcaWageRepository;
    private final SalaryProperties properties;
    private final Clock clock;

    public LcaImportService(XlsxStreamReader reader, LcaWageRepository lcaWageRepository,
                            SalaryProperties properties, Clock clock) {
        this.reader = reader;
        this.lcaWageRepository = lcaWageRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Streams {@code xlsxFile}, aggregates, and upserts into {@code lca_wage}. */
    public ImportResult importFrom(Path xlsxFile) {
        LocalDate cutoff = LocalDate.ofInstant(
                clock.instant().minus(properties.maxDataAge()), ZoneOffset.UTC);

        Map<Key, Group> groups = new HashMap<>();
        long[] counters = new long[2]; // [0] rowsRead, [1] rowsKept
        LocalDate[] span = new LocalDate[2]; // [0] oldest, [1] newest

        reader.forEachRow(xlsxFile, row -> {
            counters[0]++;

            String status = row.getOrDefault("CASE_STATUS", "").trim().toLowerCase();
            if (!status.startsWith("certified")) {
                return true;
            }

            OptionalDouble wage = annualizedWage(
                    row.get("WAGE_RATE_OF_PAY_FROM"),
                    row.get("WAGE_RATE_OF_PAY_TO"),
                    row.getOrDefault("WAGE_UNIT_OF_PAY", ""));
            if (wage.isEmpty()) {
                return true;
            }

            Optional<LocalDate> dataDate = firstDate(row, "DECISION_DATE", "BEGIN_DATE", "RECEIVED_DATE");
            if (dataDate.isEmpty() || dataDate.get().isBefore(cutoff)) {
                return true;
            }

            String employerKey = CompanyKey.of(row.get("EMPLOYER_NAME"));
            if (employerKey.isEmpty()) {
                return true;
            }
            String soc = normalizeSoc(row.get("SOC_CODE"));
            if (soc.isEmpty()) {
                return true;
            }
            String state = row.getOrDefault("WORKSITE_STATE", "").trim().toUpperCase();

            LocalDate date = dataDate.get();
            double annual = wage.getAsDouble();

            groups.computeIfAbsent(new Key(employerKey, soc, ""), k -> new Group()).add(annual, date);
            if (!state.isEmpty()) {
                groups.computeIfAbsent(new Key(employerKey, soc, state), k -> new Group()).add(annual, date);
            }

            counters[1]++;
            if (span[0] == null || date.isBefore(span[0])) {
                span[0] = date;
            }
            if (span[1] == null || date.isAfter(span[1])) {
                span[1] = date;
            }
            return true;
        });

        int written = flush(groups);

        ImportResult result = new ImportResult(counters[0], counters[1], written,
                span[0] == null ? null : span[0].toString(),
                span[1] == null ? null : span[1].toString());
        log.info("LCA import from {}: {} rows read, {} kept, {} groups written ({}..{})",
                xlsxFile, result.rowsRead(), result.rowsKept(), result.groupsWritten(),
                result.oldestKept(), result.newestKept());
        return result;
    }

    private int flush(Map<Key, Group> groups) {
        List<LcaWageRow> batch = new ArrayList<>(FLUSH_EVERY);
        int written = 0;
        for (Map.Entry<Key, Group> e : groups.entrySet()) {
            Key k = e.getKey();
            Group g = e.getValue();
            double[] sorted = g.sortedWages();
            batch.add(new LcaWageRow(k.employerKey(), k.soc(), k.state(),
                    percentile(sorted, 25), percentile(sorted, 50), percentile(sorted, 75),
                    sorted[sorted.length - 1], sorted.length, g.latest.toString()));
            written++;
            if (batch.size() >= FLUSH_EVERY) {
                lcaWageRepository.upsertAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            lcaWageRepository.upsertAll(batch);
        }
        return written;
    }

    /** Nearest-rank percentile over an ascending array. */
    static double percentile(double[] sorted, double p) {
        int n = sorted.length;
        int rank = (int) Math.ceil(p / 100.0 * n);
        if (rank < 1) {
            rank = 1;
        }
        if (rank > n) {
            rank = n;
        }
        return sorted[rank - 1];
    }

    static String normalizeSoc(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int dot = s.indexOf('.');
        return dot >= 0 ? s.substring(0, dot) : s;
    }

    private OptionalDouble annualizedWage(String from, String to, String unit) {
        double mult = switch (unit == null ? "" : unit.trim().toLowerCase()) {
            case "year", "yr", "yearly", "annual" -> 1;
            case "hour", "hr", "hourly" -> 2080;
            case "week", "weekly" -> 52;
            case "bi-weekly", "biweekly", "bi weekly" -> 26;
            case "month", "monthly" -> 12;
            default -> -1;
        };
        if (mult < 0) {
            return OptionalDouble.empty();
        }
        OptionalDouble fromRate = positiveNumber(from);
        if (fromRate.isEmpty()) {
            return OptionalDouble.empty();
        }
        double annual = fromRate.getAsDouble() * mult;
        OptionalDouble toRate = positiveNumber(to);
        if (toRate.isPresent()) {
            annual = (annual + toRate.getAsDouble() * mult) / 2.0;
        }
        if (annual < MIN_WAGE || annual > MAX_WAGE) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(annual);
    }

    private static OptionalDouble positiveNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return OptionalDouble.empty();
        }
        String cleaned = raw.replace(",", "").replace("$", "").trim();
        try {
            double v = Double.parseDouble(cleaned);
            return v > 0 ? OptionalDouble.of(v) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private static Optional<LocalDate> firstDate(Map<String, String> row, String... columns) {
        for (String col : columns) {
            Optional<LocalDate> d = parseDate(row.get(col));
            if (d.isPresent()) {
                return d;
            }
        }
        return Optional.empty();
    }

    static Optional<LocalDate> parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim();
        try {
            double serial = Double.parseDouble(s);
            if (serial > 0) {
                return Optional.of(EXCEL_EPOCH.plusDays((long) serial));
            }
        } catch (NumberFormatException ignored) {
            // not a serial number - try text forms below
        }
        try {
            return Optional.of(LocalDate.parse(s.substring(0, Math.min(10, s.length()))));
        } catch (RuntimeException ignored) {
            // not ISO
        }
        try {
            return Optional.of(LocalDate.parse(s, US_SLASH));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private record Key(String employerKey, String soc, String state) {
    }

    private static final class Group {
        private final List<Double> wages = new ArrayList<>();
        private LocalDate latest;

        void add(double wage, LocalDate date) {
            wages.add(wage);
            if (latest == null || date.isAfter(latest)) {
                latest = date;
            }
        }

        double[] sortedWages() {
            double[] out = new double[wages.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = wages.get(i);
            }
            java.util.Arrays.sort(out);
            return out;
        }
    }
}
