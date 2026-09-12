package com.ubaid.jobdash.web.dto;

import java.time.Instant;

/**
 * The LinkedIn circuit breaker's cooldown as {@code GET /api/runs/cooldown} reports it, so the
 * run panel can show a countdown and enable "Retry" the moment it lapses instead of making the
 * user guess. Only LinkedIn traffic is subject to it - the ATS boards never are.
 *
 * @param active           true while the breaker is OPEN and its reopen time is still ahead
 * @param until            when the cooldown lapses; null when not active
 * @param remainingSeconds seconds left, 0 when not active
 */
public record CooldownResponse(boolean active, Instant until, long remainingSeconds) {

    public static CooldownResponse inactive() {
        return new CooldownResponse(false, null, 0);
    }
}
