package com.ubaid.jobdash.source.ats;

import com.ubaid.jobdash.store.AtsCompanyRepository;
import com.ubaid.jobdash.store.AtsCompanyRepository.NewCompany;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Imports a bulk ATS slug catalog into {@code ats_company}, modelled closely on
 * {@code salary.LcaImportService}: parse a source file/URL, keep what's usable, skip what isn't,
 * never abort the batch over one bad entry.
 * <p>
 * The source is a JSON document shaped {@code {"ats": {"greenhouse": [...], "lever": [...],
 * "workday": [...], ...other platforms...}}}. Only the three keys this app actually implements
 * are imported; every other platform in the file is ignored on purpose, so the same catalog file
 * keeps working unmodified as more ATS integrations are added.
 * <p>
 * <b>Imported rows are always {@code enabled=0}, {@code status='unverified'}</b> and inserted
 * with {@code insert or ignore} semantics ({@link AtsCompanyRepository#insertOrIgnore}) — a
 * company the user already added or enabled is never touched by an import, in either direction.
 */
@Service
public class SlugCatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(SlugCatalogImportService.class);

    /** The only ATS platforms this app currently implements adapters for. */
    private static final List<String> SUPPORTED_ATS = List.of("greenhouse", "lever", "workday");

    /** Per-platform outcome of one import. */
    public record PlatformResult(int inserted, int skippedExisting, int malformed) {
    }

    /** Outcome of one import run. */
    public record ImportResult(Map<String, PlatformResult> byAts, int pruned) {

        public int totalInserted() {
            return byAts.values().stream().mapToInt(PlatformResult::inserted).sum();
        }
    }

    private final AtsCompanyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient httpClient;

    public SlugCatalogImportService(AtsCompanyRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Imports {@code location} — a local JSON file path or an http(s) URL. When
     * {@code pruneDead} is true, every row already marked dead is deleted first.
     */
    public ImportResult importFrom(String location, boolean pruneDead) {
        String json = readSource(location);
        JsonNode root = objectMapper.readTree(json);
        JsonNode atsNode = root.get("ats");

        int pruned = pruneDead ? repository.deleteDead() : 0;

        Map<String, PlatformResult> results = new LinkedHashMap<>();
        for (String ats : SUPPORTED_ATS) {
            JsonNode entries = atsNode == null ? null : atsNode.get(ats);
            results.put(ats, importPlatform(ats, entries));
        }

        ImportResult result = new ImportResult(results, pruned);
        log.info("slug catalog import from {}: {} inserted, {} pruned ({})",
                location, result.totalInserted(), pruned, summarize(results));
        return result;
    }

    private String summarize(Map<String, PlatformResult> results) {
        StringBuilder sb = new StringBuilder();
        results.forEach((ats, r) -> sb.append(ats).append('=').append(r.inserted())
                .append(" new/").append(r.skippedExisting()).append(" existing/")
                .append(r.malformed()).append(" malformed; "));
        return sb.toString();
    }

    private PlatformResult importPlatform(String ats, JsonNode entries) {
        if (entries == null || !entries.isArray()) {
            return new PlatformResult(0, 0, 0);
        }

        List<NewCompany> companies = new ArrayList<>();
        int malformed = 0;
        for (JsonNode entry : entries) {
            try {
                NewCompany company = "workday".equals(ats) ? parseWorkdayEntry(entry) : parseSimpleEntry(ats, entry);
                if (company == null) {
                    malformed++;
                } else {
                    companies.add(company);
                }
            } catch (RuntimeException e) {
                log.warn("skipping malformed {} entry {}: {}", ats, entry, e.toString());
                malformed++;
            }
        }

        int attempted = companies.size();
        int inserted = repository.insertOrIgnore(companies, false, "unverified", clock.instant());
        return new PlatformResult(inserted, attempted - inserted, malformed);
    }

    /** Greenhouse/Lever: a bare slug string. */
    private NewCompany parseSimpleEntry(String ats, JsonNode entry) {
        if (!entry.isString()) {
            return null;
        }
        String slug = entry.asString().trim();
        if (slug.isEmpty()) {
            return null;
        }
        return new NewCompany(ats, slug, displayNameFor(slug), null, null);
    }

    /**
     * Workday: a bare hostname (e.g. {@code nvidia.wd5.myworkdayjobs.com}), not a slug. The
     * tenant — the first label — becomes {@code slug}; the whole hostname is kept as
     * {@code host}. {@code site} is left null; it's resolved lazily from robots.txt elsewhere.
     */
    private NewCompany parseWorkdayEntry(JsonNode entry) {
        if (!entry.isString()) {
            return null;
        }
        String host = entry.asString().trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return null;
        }
        int dot = host.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        String slug = host.substring(0, dot);
        return new NewCompany("workday", slug, displayNameFor(slug), host, null);
    }

    /** Title-cased display name derived from a slug when the source gives no explicit name. */
    static String displayNameFor(String slug) {
        String[] words = slug.replace('-', ' ').replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private String readSource(String location) {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(location))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalArgumentException(
                            "fetching " + location + " returned HTTP " + response.statusCode());
                }
                return response.body();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalArgumentException("could not fetch " + location + ": " + e.getMessage(), e);
            }
        }
        try {
            return Files.readString(Path.of(location));
        } catch (IOException e) {
            throw new IllegalArgumentException("could not read " + location + ": " + e.getMessage(), e);
        }
    }
}
