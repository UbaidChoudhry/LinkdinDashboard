package com.ubaid.jobdash.web;

import com.ubaid.jobdash.domain.AtsCompany;
import com.ubaid.jobdash.store.AtsCompanyRepository;
import com.ubaid.jobdash.web.dto.AtsCompanyResponse;
import com.ubaid.jobdash.web.dto.CreateSourceRequest;
import com.ubaid.jobdash.web.dto.SetSourceEnabledRequest;
import com.ubaid.jobdash.web.dto.SourcePageResponse;
import com.ubaid.jobdash.web.dto.SourceSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * REST surface over the ATS company catalog ({@code ats_company}), backing the dashboard's
 * Sources tab: browsing/searching the (potentially >15,000 row) catalog, toggling which
 * companies a run actually visits, and manually adding a company the auto-discovery/import path
 * doesn't cover.
 */
@RestController
public class SourceController {

    private static final Set<String> VALID_ATS = Set.of("greenhouse", "lever", "workday");
    private static final int MAX_PAGE_SIZE = 500;
    /** A leading path segment like "en" or "en-US" is a locale, not the site id. */
    private static final Pattern LOCALE_SEGMENT = Pattern.compile("[a-z]{2}(-[A-Za-z0-9]{2,8})?");

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AtsCompanyRepository repository;
    private final Clock clock;

    public SourceController(AtsCompanyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/api/sources")
    public SourcePageResponse list(@RequestParam(required = false) String ats,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(required = false, defaultValue = "false") boolean enabledOnly,
                                    @RequestParam(required = false, defaultValue = "50") int limit,
                                    @RequestParam(required = false, defaultValue = "0") int offset) {
        String normalizedAts = normalizeAtsFilter(ats);
        int boundedLimit = boundLimit(limit);
        int boundedOffset = Math.max(offset, 0);

        List<AtsCompanyResponse> items = repository.list(normalizedAts, search, enabledOnly, boundedLimit, boundedOffset)
                .stream().map(AtsCompanyResponse::from).toList();
        long total = repository.count(normalizedAts, search, enabledOnly);
        return new SourcePageResponse(items, total);
    }

    @GetMapping("/api/sources/summary")
    public SourceSummaryResponse summary() {
        return SourceSummaryResponse.from(repository.counts());
    }

    @PostMapping("/api/sources/{id}/enabled")
    public AtsCompanyResponse setEnabled(@PathVariable long id, @RequestBody SetSourceEnabledRequest body) {
        if (body == null || body.enabled() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "enabled is required.");
        }
        AtsCompany existing = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No source with id " + id + "."));
        repository.setEnabled(id, body.enabled());
        return AtsCompanyResponse.from(repository.findById(id).orElse(existing));
    }

    @PostMapping("/api/sources")
    public ResponseEntity<AtsCompanyResponse> create(@RequestBody CreateSourceRequest body) {
        if (body == null || body.ats() == null || body.ats().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ats is required.");
        }
        String ats = body.ats().trim().toLowerCase();
        if (!VALID_ATS.contains(ats)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ats must be one of " + VALID_ATS + ", got '" + body.ats() + "'.");
        }
        if (body.company() == null || body.company().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "company is required.");
        }

        String slug;
        String host = null;
        String site = null;

        if ("workday".equals(ats)) {
            WorkdayTarget target = resolveWorkdayTarget(body);
            slug = target.slug();
            host = target.host();
            site = target.site();
        } else {
            if (body.slug() == null || body.slug().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "slug is required for " + ats + ".");
            }
            slug = body.slug().trim();
        }

        var id = repository.insertOne(ats, slug, body.company().trim(), host, site,
                true, "unverified", clock.instant());
        if (id.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    ats + "/" + slug + " is already in the catalog.");
        }
        AtsCompany created = repository.findById(id.get())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Inserted source " + id.get() + " could not be re-read."));
        return ResponseEntity.status(HttpStatus.CREATED).body(AtsCompanyResponse.from(created));
    }

    @DeleteMapping("/api/sources/{id}")
    public void delete(@PathVariable long id) {
        int deleted = repository.delete(id);
        if (deleted == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No source with id " + id + ".");
        }
    }

    private record WorkdayTarget(String slug, String host, String site) {
    }

    /**
     * Workday accepts either an explicit {@code host} (+ optional {@code site}), or a full
     * careers URL such as {@code https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/...},
     * which is parsed into host and site.
     */
    private WorkdayTarget resolveWorkdayTarget(CreateSourceRequest body) {
        String host = body.host();
        String site = body.site();

        if ((host == null || host.isBlank()) && body.careersUrl() != null && !body.careersUrl().isBlank()) {
            URI uri;
            try {
                uri = new URI(body.careersUrl().trim());
            } catch (URISyntaxException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "careersUrl is not a valid URL: " + e.getMessage());
            }
            host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "careersUrl has no host: " + body.careersUrl());
            }
            site = siteFromCareersPath(uri.getPath());
        }

        if (host == null || host.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "workday requires either host (+ optional site) or careersUrl.");
        }
        host = host.trim().toLowerCase();
        int dot = host.indexOf('.');
        if (dot <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "host does not look like a Workday tenant host: " + host);
        }
        String slug = host.substring(0, dot);
        return new WorkdayTarget(slug, host, site == null || site.isBlank() ? null : site.trim());
    }

    /**
     * Pulls the site id out of a Workday careers URL path. The locale segment is OPTIONAL - both
     * {@code /en-US/NVIDIAExternalCareerSite/job/...} and {@code /NVIDIAExternalCareerSite} are
     * forms Workday actually serves, so the site cannot simply be taken as the second segment.
     * A leading segment shaped like a locale ({@code en}, {@code en-US}) is skipped; whatever
     * follows is the site id.
     */
    static String siteFromCareersPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        List<String> segments = Arrays.stream(path.split("/"))
                .filter(seg -> !seg.isBlank())
                .toList();
        if (segments.isEmpty()) {
            return null;
        }
        String first = segments.get(0);
        if (LOCALE_SEGMENT.matcher(first).matches()) {
            return segments.size() > 1 ? segments.get(1) : null;
        }
        return first;
    }

    private static String normalizeAtsFilter(String ats) {
        if (ats == null || ats.isBlank()) {
            return null;
        }
        String normalized = ats.trim().toLowerCase();
        if (!VALID_ATS.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ats must be one of " + VALID_ATS + ", got '" + ats + "'.");
        }
        return normalized;
    }

    private static int boundLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }
}
