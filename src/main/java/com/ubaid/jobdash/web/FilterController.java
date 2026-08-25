package com.ubaid.jobdash.web;

import com.ubaid.jobdash.filter.FilterEngine;
import com.ubaid.jobdash.store.CompanyBlocklistRepository;
import com.ubaid.jobdash.store.ExcludeWordRepository;
import com.ubaid.jobdash.web.dto.CompanyBlocklistRequest;
import com.ubaid.jobdash.web.dto.CompanyBlocklistResponse;
import com.ubaid.jobdash.web.dto.ExcludeWordRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/**
 * REST surface over the exclude-word list and company blocklist. Every mutation here calls
 * {@link FilterEngine#bumpVersionAndReevaluate()} so the change takes effect across every
 * already-stored {@code job_listing} row, not just future sweeps — that's the entire point of
 * filter versioning, so it must never be skipped.
 */
@RestController
public class FilterController {

    private final ExcludeWordRepository excludeWordRepository;
    private final CompanyBlocklistRepository companyBlocklistRepository;
    private final FilterEngine filterEngine;
    private final Clock clock;

    public FilterController(ExcludeWordRepository excludeWordRepository,
                             CompanyBlocklistRepository companyBlocklistRepository,
                             FilterEngine filterEngine, Clock clock) {
        this.excludeWordRepository = excludeWordRepository;
        this.companyBlocklistRepository = companyBlocklistRepository;
        this.filterEngine = filterEngine;
        this.clock = clock;
    }

    @GetMapping("/api/filters/words")
    public List<String> listWords() {
        return excludeWordRepository.list();
    }

    @PostMapping("/api/filters/words")
    public ResponseEntity<List<String>> addWord(@RequestBody ExcludeWordRequest body) {
        String word = body == null ? null : body.word();
        if (word == null || word.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "word is required and must not be blank.");
        }
        excludeWordRepository.add(word.trim(), clock.instant());
        filterEngine.bumpVersionAndReevaluate();
        return ResponseEntity.status(HttpStatus.CREATED).body(excludeWordRepository.list());
    }

    @DeleteMapping("/api/filters/words/{word}")
    public List<String> deleteWord(@PathVariable String word) {
        int deleted = excludeWordRepository.delete(word);
        if (deleted == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "'" + word + "' is not on the exclude-word list.");
        }
        filterEngine.bumpVersionAndReevaluate();
        return excludeWordRepository.list();
    }

    @GetMapping("/api/filters/companies")
    public List<CompanyBlocklistResponse> listCompanies() {
        return companyBlocklistRepository.list().stream().map(CompanyBlocklistResponse::from).toList();
    }

    @PostMapping("/api/filters/companies")
    public ResponseEntity<List<CompanyBlocklistResponse>> addCompany(@RequestBody CompanyBlocklistRequest body) {
        String company = body == null ? null : body.company();
        if (company == null || company.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "company is required and must not be blank.");
        }
        String reason = body.reason() == null || body.reason().isBlank() ? "manually blocked" : body.reason();
        companyBlocklistRepository.add(company.trim(), reason, body.evidence(), clock.instant());
        filterEngine.bumpVersionAndReevaluate();
        return ResponseEntity.status(HttpStatus.CREATED).body(listCompanies());
    }

    @DeleteMapping("/api/filters/companies/{company}")
    public List<CompanyBlocklistResponse> deleteCompany(@PathVariable String company) {
        int deleted = companyBlocklistRepository.delete(company);
        if (deleted == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "'" + company + "' is not on the company blocklist.");
        }
        filterEngine.bumpVersionAndReevaluate();
        return listCompanies();
    }
}
