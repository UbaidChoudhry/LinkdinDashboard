package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.store.LcaWageRepository;
import com.ubaid.jobdash.store.LcaWageRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Salary source backed by the locally imported DOL LCA disclosure aggregates
 * ({@code lca_wage}). No network call — this is the first and cheapest step of the cascade.
 * <p>
 * Maps the job title to candidate SOC codes and the location to a state, then asks
 * {@link LcaWageRepository} for the best matching wage aggregate (state-specific first, then
 * the national aggregate, across the SOC candidates in order).
 */
@Component
public class LcaSalarySource implements SalarySource {

    private static final Logger log = LoggerFactory.getLogger(LcaSalarySource.class);

    private final LcaWageRepository lcaWageRepository;
    private final SocMapper socMapper;
    private final Clock clock;

    public LcaSalarySource(LcaWageRepository lcaWageRepository, SocMapper socMapper, Clock clock) {
        this.lcaWageRepository = lcaWageRepository;
        this.socMapper = socMapper;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "lca";
    }

    @Override
    public Optional<SalaryResult> lookup(SalaryLookup q) {
        if (q.companyKey() == null || q.companyKey().isBlank()) {
            return Optional.empty();
        }
        List<String> socCodes = socMapper.candidateSocCodes(q.title());
        if (socCodes.isEmpty()) {
            return Optional.empty();
        }
        String state = UsState.fromLocation(q.location());

        Optional<LcaWageRow> hit = lcaWageRepository.lookup(q.companyKey(), socCodes, state);
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        LcaWageRow row = hit.get();

        // p25..p75 rather than min..max: the LCA "max" is a single outlier filing and the p50
        // alone hides the spread. The p25/p75 interquartile band is the most defensible
        // "typical range" for this employer+occupation, and its top (p75) is what the job sort
        // orders on.
        LocalDate dataDate = parseDate(row.latestDataDate());
        return Optional.of(new SalaryResult(
                row.wageP25(), row.wageP75(), "USD", dataDate, row.sampleCount(), "lca"));
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (RuntimeException e) {
            log.debug("unparseable lca latest_data_date: {}", text);
            return null;
        }
    }
}
