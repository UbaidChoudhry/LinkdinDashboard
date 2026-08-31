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

    /** What happened to one spreadsheet in a batch. */
    public enum FileStatus {
        /** Rows were imported and the file was deleted. */
        IMPORTED_AND_DELETED,
        /** Rows were imported; the file was left in place (deletion disabled, or the delete failed). */
        IMPORTED_KEPT,
        /**
         * Parsed cleanly but contributed zero rows — every row was filtered out (all past the
         * staleness cutoff, or the column layout changed). <b>Never deleted</b>: the file is the
         * only evidence for diagnosing which of those it was.
         */
        NOTHING_IMPORTED,
        /** The import threw. File left in place. */
        FAILED
    }

    /** One spreadsheet's outcome. {@code result} is null when {@code status} is {@link FileStatus#FAILED}. */
    public record FileOutcome(Path file, FileStatus status, ImportResult result, String error) {
    }

    /** Outcome of a whole directory (or single-file) import. */
    public record BatchResult(List<FileOutcome> outcomes) {

        public long countOf(FileStatus status) {
            return outcomes.stream().filter(o -> o.status() == status).count();
        }

        /** True if any file threw — the caller should exit non-zero. */
        public boolean anyFailed() {
            return countOf(FileStatus.FAILED) > 0;
        }
    }

    /**
     * Imports {@code target} — either one {@code .xlsx} or every {@code .xlsx} directly inside a
     * directory, oldest filename first — and optionally deletes each file that actually
     * contributed rows.
     *
     * <p>A file is deleted <b>only</b> when the import both completed and kept at least one row.
     * A file that parses to zero kept rows is reported as {@link FileStatus#NOTHING_IMPORTED} and
     * left alone: these spreadsheets are ~80&nbsp;MB downloads, and "imported nothing" is far more
     * likely to mean a changed column layout than a genuinely empty quarter. Failures never abort
     * the batch — every remaining file is still attempted.
     */
    public BatchResult importAll(Path target, boolean deleteAfterImport) {
        List<Path> files = spreadsheetsIn(target);
        if (files.isEmpty()) {
            log.warn("no .xlsx files to import at {}", target);
            return new BatchResult(List.of());
        }

        List<FileOutcome> outcomes = new ArrayList<>(files.size());
        for (Path file : files) {
            log.info("importing {} ({})", file.getFileName(), humanSize(file));
            try {
                ImportResult result = importFrom(file);
                if (result.rowsKept() == 0) {
                    log.warn("{} contributed no rows (read {}) - keeping the file so the cause "
                            + "can be diagnosed; check the column layout or the staleness cutoff",
                            file.getFileName(), result.rowsRead());
                    outcomes.add(new FileOutcome(file, FileStatus.NOTHING_IMPORTED, result, null));
                    continue;
                }
                outcomes.add(new FileOutcome(file, deleteQuietly(file, deleteAfterImport), result, null));
            } catch (RuntimeException e) {
                log.error("import failed for {}: {}", file.getFileName(), e.toString());
                outcomes.add(new FileOutcome(file, FileStatus.FAILED, null, e.toString()));
            }
        }
        return new BatchResult(List.copyOf(outcomes));
    }

    /** Deletes an imported file when asked; a delete failure downgrades the status, never throws. */
    private FileStatus deleteQuietly(Path file, boolean deleteAfterImport) {
        if (!deleteAfterImport) {
            return FileStatus.IMPORTED_KEPT;
        }
        try {
            java.nio.file.Files.delete(file);
            log.info("deleted {} after a successful import", file.getFileName());
            return FileStatus.IMPORTED_AND_DELETED;
        } catch (java.io.IOException e) {
            log.warn("imported {} but could not delete it: {}", file.getFileName(), e.toString());
            return FileStatus.IMPORTED_KEPT;
        }
    }

    /** One file, or every {@code .xlsx} directly inside a directory, sorted by filename. */
    private static List<Path> spreadsheetsIn(Path target) {
        if (!java.nio.file.Files.isDirectory(target)) {
            return java.nio.file.Files.isRegularFile(target) ? List.of(target) : List.of();
        }
        try (var entries = java.nio.file.Files.list(target)) {
            return entries
                    .filter(java.nio.file.Files::isRegularFile)
                    // "~$..." are Excel lock files, not spreadsheets.
                    .filter(p -> !p.getFileName().toString().startsWith("~$"))
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx"))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("could not list " + target + ": " + e.getMessage(), e);
        }
    }

    private static String humanSize(Path file) {
        try {
            return (java.nio.file.Files.size(file) / (1024 * 1024)) + " MB";
        } catch (java.io.IOException e) {
            return "unknown size";
        }
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

            String rawEmployer = row.get("EMPLOYER_NAME");
            String employerKey = CompanyKey.of(rawEmployer);
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

            groups.computeIfAbsent(new Key(employerKey, soc, ""), k -> new Group()).add(annual, date, rawEmployer);
            if (!state.isEmpty()) {
                groups.computeIfAbsent(new Key(employerKey, soc, state), k -> new Group()).add(annual, date, rawEmployer);
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
                    sorted[sorted.length - 1], sorted.length, g.latest.toString(), g.display));
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
        /** First raw {@code EMPLOYER_NAME} seen for this group — the human-readable matched entity. */
        private String display;

        void add(double wage, LocalDate date, String rawEmployer) {
            wages.add(wage);
            if (latest == null || date.isAfter(latest)) {
                latest = date;
            }
            if (display == null && rawEmployer != null && !rawEmployer.isBlank()) {
                display = rawEmployer.trim();
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
