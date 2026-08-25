package com.ubaid.jobdash.store.adapter;

import com.ubaid.jobdash.http.RequestBudgetStore;
import com.ubaid.jobdash.http.RequestRecord;
import com.ubaid.jobdash.store.RequestLogRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

/**
 * JDBC-backed {@link RequestBudgetStore}, delegating to {@link RequestLogRepository}.
 * <p>
 * {@code request_log.run_id} is a nullable FK in the schema; this store isn't run-scoped (it
 * exists to answer the rate limiter's rolling 24h budget check across the whole process, per
 * {@code RateLimiter}'s layer C), so it always writes {@code null} for {@code run_id}. A sweep
 * run's own per-page request logging (if any) is a separate concern from this budget store.
 */
@Component
public class JdbcRequestBudgetStore implements RequestBudgetStore {

    private final RequestLogRepository requestLogRepository;

    public JdbcRequestBudgetStore(RequestLogRepository requestLogRepository) {
        this.requestLogRepository = requestLogRepository;
    }

    @Override
    public void record(RequestRecord record) {
        Integer statusCode = record.statusCode() < 0 ? null : record.statusCode();
        int waitedMs = (int) Math.min(record.waitedMs(), Integer.MAX_VALUE);
        requestLogRepository.append(null, record.timestamp(), record.url(), statusCode,
                record.outcome().name().toLowerCase(Locale.ROOT), null, waitedMs);
    }

    @Override
    public int countSince(Instant since) {
        // RequestLogRepository.countSince returns long (a raw SQL count); RequestBudgetStore's
        // port narrows to int. Clamp rather than risk an ArithmeticException from
        // Math.toIntExact — a saturated Integer.MAX_VALUE count will still correctly compare
        // as "over budget" against any realistic perRollingDayCap.
        long count = requestLogRepository.countSince(since);
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }
}
