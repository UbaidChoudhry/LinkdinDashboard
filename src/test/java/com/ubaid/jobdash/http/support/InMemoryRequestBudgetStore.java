package com.ubaid.jobdash.http.support;

import com.ubaid.jobdash.http.RequestBudgetStore;
import com.ubaid.jobdash.http.RequestRecord;
import com.ubaid.jobdash.http.ResponseOutcome;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** In-memory {@link RequestBudgetStore} for tests. Shared across instances to simulate a restart. */
public final class InMemoryRequestBudgetStore implements RequestBudgetStore {

    private final List<RequestRecord> records = new ArrayList<>();

    @Override
    public synchronized void record(RequestRecord record) {
        records.add(record);
    }

    @Override
    public synchronized int countSince(Instant since) {
        int count = 0;
        for (RequestRecord r : records) {
            if (!r.timestamp().isBefore(since)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public synchronized int countBlockedSince(Instant since) {
        int count = 0;
        for (RequestRecord r : records) {
            if (!r.timestamp().isBefore(since) && r.outcome() == ResponseOutcome.BLOCKED && r.statusCode() >= 0) {
                count++;
            }
        }
        return count;
    }

    public synchronized List<RequestRecord> all() {
        return List.copyOf(records);
    }
}
