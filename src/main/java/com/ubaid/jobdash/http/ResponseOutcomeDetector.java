package com.ubaid.jobdash.http;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Classifies a raw LinkedIn job-search response into one of five {@link ResponseOutcome}s, and
 * (via {@link #classifyDetail}) a job-detail fragment into one of three.
 * <p>
 * Deliberately takes {@code cardCount} and {@code page1Titles} as parameters rather than
 * parsing the body itself, so it stays decoupled from the HTML parser and is trivially
 * unit-testable with hand-built inputs.
 * <p>
 * The trickiest rule is positional: an empty body at {@code start == 0} means the very
 * first page came back empty, which is a block signal worth raising loudly; the same
 * empty body at {@code start > 0} just means a deep page ran past the last result, which
 * is the normal, expected way a sweep ends.
 */
public final class ResponseOutcomeDetector {

    /**
     * LinkedIn's byte-exact end-of-results sentinel: {@code <!DOCTYPE html>\n\n<!---->  }
     * (26 bytes, including two trailing spaces). Matched after trimming both sides so
     * incidental surrounding whitespace from transport doesn't cause a false negative,
     * while still requiring the sentinel's own internal structure to line up exactly.
     */
    private static final String SENTINEL = "<!DOCTYPE html>\n\n<!---->  ";
    private static final String SENTINEL_TRIMMED = SENTINEL.trim();

    private static final int MIN_CARDS_FOR_FULL_PAGE = 10;
    private static final double IRRELEVANT_THRESHOLD = 0.30;

    /**
     * @param outcome        the classified outcome.
     * @param sentinelMatched whether the body matched the end-of-results sentinel exactly.
     * @param unparseable    true for a 200 response with a non-empty, non-sentinel body that
     *                       yielded zero parseable cards — a signal distinct from the outcome
     *                       enum, used by {@code PacedHttpClient} to raise a soft failure to
     *                       the circuit breaker even though the outcome itself is END_OF_RESULTS.
     */
    public record Classification(ResponseOutcome outcome, boolean sentinelMatched, boolean unparseable) {
    }

    /**
     * @param statusCode   the HTTP status code of the response.
     * @param body         the raw response body (possibly null or blank).
     * @param start        the {@code start} query parameter used for this request; 0 is page 1.
     * @param cardCount    number of parseable job cards found in {@code body}.
     * @param page1Titles  job titles found on page 1 (only meaningful/used when {@code start == 0}).
     * @param keyword      the search keyword the sweep is running, used to tokenize for the
     *                     IRRELEVANT check. If null/blank, the IRRELEVANT check is skipped.
     */
    public Classification classify(int statusCode, String body, int start, int cardCount,
                                    List<String> page1Titles, String keyword) {
        boolean bodyBlank = body == null || body.isBlank();

        if (statusCode == 429 || statusCode == 999) {
            return new Classification(ResponseOutcome.BLOCKED, false, false);
        }

        if (bodyBlank) {
            return start == 0
                    ? new Classification(ResponseOutcome.BLOCKED, false, false)
                    : new Classification(ResponseOutcome.END_OF_RESULTS, false, false);
        }

        if (statusCode == 400) {
            return new Classification(ResponseOutcome.PAST_CAP, false, false);
        }

        if (isSentinel(body)) {
            return new Classification(ResponseOutcome.END_OF_RESULTS, true, false);
        }

        if (start == 0 && isIrrelevant(page1Titles, keyword)) {
            return new Classification(ResponseOutcome.IRRELEVANT, false, false);
        }

        if (cardCount < MIN_CARDS_FOR_FULL_PAGE) {
            boolean unparseable = statusCode == 200 && cardCount == 0;
            return new Classification(ResponseOutcome.END_OF_RESULTS, false, unparseable);
        }

        return new Classification(ResponseOutcome.OK, false, false);
    }

    /**
     * Classifies a job-detail fragment response ({@code /jobs-guest/jobs/api/jobPosting/{id}}).
     * Far simpler than a search page - there is no pagination, no sentinel and no relevance
     * question - so only three outcomes exist:
     * <ul>
     *     <li>429 / 999 → {@link ResponseOutcome#BLOCKED}, a hard block signal;</li>
     *     <li>404 → {@link ResponseOutcome#GONE}, the posting was taken down - a normal answer
     *     from LinkedIn, not a failure;</li>
     *     <li>200 with a parsed description → {@link ResponseOutcome#OK};</li>
     *     <li>anything else (200 with no description, an empty body, 5xx) →
     *     {@link ResponseOutcome#BLOCKED} with {@code unparseable} set, so the caller can treat it
     *     as a soft failure rather than a definite block.</li>
     * </ul>
     *
     * @param statusCode        the HTTP status code of the response.
     * @param body              the raw response body (possibly null or blank).
     * @param descriptionParsed whether the caller's parser extracted a non-blank description.
     */
    public Classification classifyDetail(int statusCode, String body, boolean descriptionParsed) {
        if (statusCode == 429 || statusCode == 999) {
            return new Classification(ResponseOutcome.BLOCKED, false, false);
        }
        if (statusCode == 404) {
            return new Classification(ResponseOutcome.GONE, false, false);
        }
        boolean bodyBlank = body == null || body.isBlank();
        if (statusCode == 200 && !bodyBlank && descriptionParsed) {
            return new Classification(ResponseOutcome.OK, false, false);
        }
        return new Classification(ResponseOutcome.BLOCKED, false, true);
    }

    private boolean isSentinel(String body) {
        return SENTINEL_TRIMMED.equals(body.trim());
    }

    private boolean isIrrelevant(List<String> titles, String keyword) {
        if (titles == null || titles.isEmpty() || keyword == null || keyword.isBlank()) {
            return false;
        }
        List<String> tokens = tokenize(keyword);
        if (tokens.isEmpty()) {
            return false;
        }
        long matches = titles.stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(t -> containsAnyToken(t.toLowerCase(Locale.ROOT), tokens))
                .count();
        double fraction = (double) matches / titles.size();
        return fraction < IRRELEVANT_THRESHOLD;
    }

    private boolean containsAnyToken(String lowerTitle, List<String> tokens) {
        for (String token : tokens) {
            if (lowerTitle.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private List<String> tokenize(String keyword) {
        return Arrays.stream(keyword.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(s -> !s.isBlank())
                .toList();
    }
}
