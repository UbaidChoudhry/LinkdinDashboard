package com.ubaid.jobdash.http;

import com.ubaid.jobdash.source.linkedin.CardParser;
import com.ubaid.jobdash.source.linkedin.JobCard;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseOutcomeDetectorTest {

    private final ResponseOutcomeDetector detector = new ResponseOutcomeDetector();
    private final CardParser parser = new CardParser();

    private static String loadFixture(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = ResponseOutcomeDetectorTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- OK ------------------------------------------------------------

    @Test
    void tenParseableCardsIsOk() {
        String body = loadFixture("search_start_0.html");
        List<JobCard> cards = parser.parse(body);
        List<String> titles = cards.stream().map(JobCard::title).toList();

        ResponseOutcomeDetector.Classification c =
                detector.classify(200, body, 0, cards.size(), titles, "software engineer");

        assertEquals(ResponseOutcome.OK, c.outcome());
    }

    // --- END_OF_RESULTS --------------------------------------------------

    @Test
    void deep999SentinelFixtureIsEndOfResults() {
        String body = loadFixture("deep_999.html");
        ResponseOutcomeDetector.Classification c = detector.classify(200, body, 40, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.END_OF_RESULTS, c.outcome());
        assertTrue(c.sentinelMatched());
    }

    @Test
    void ny990SentinelFixtureIsEndOfResults() {
        String body = loadFixture("ny990.html");
        ResponseOutcomeDetector.Classification c = detector.classify(200, body, 40, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.END_OF_RESULTS, c.outcome());
        assertTrue(c.sentinelMatched());
    }

    @Test
    void sentinelMatchIsRobustToSurroundingWhitespace() {
        String body = "  \n" + loadFixture("deep_999.html") + "\n  ";
        ResponseOutcomeDetector.Classification c = detector.classify(200, body, 40, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.END_OF_RESULTS, c.outcome());
        assertTrue(c.sentinelMatched());
    }

    @Test
    void pageWithFewerThanTenCardsIsEndOfResults() {
        ResponseOutcomeDetector.Classification c =
                detector.classify(200, "<html>some page with 3 cards</html>", 90, 3, List.of(), "engineer");
        assertEquals(ResponseOutcome.END_OF_RESULTS, c.outcome());
    }

    @Test
    void emptyBodyAtStartGreaterThanZeroIsEndOfResultsNotBlocked() {
        ResponseOutcomeDetector.Classification c = detector.classify(200, "", 40, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.END_OF_RESULTS, c.outcome());
    }

    @Test
    void blankBodyAtStartGreaterThanZeroIsEndOfResults() {
        ResponseOutcomeDetector.Classification c = detector.classify(200, "   \n  ", 10, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.END_OF_RESULTS, c.outcome());
    }

    // --- PAST_CAP ----------------------------------------------------------

    @Test
    void http400IsPastCap() {
        ResponseOutcomeDetector.Classification c =
                detector.classify(400, "<html>bad request</html>", 1000, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.PAST_CAP, c.outcome());
    }

    // --- BLOCKED -------------------------------------------------------------

    @Test
    void http429IsBlocked() {
        ResponseOutcomeDetector.Classification c =
                detector.classify(429, "whatever", 0, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.BLOCKED, c.outcome());
    }

    @Test
    void http999IsBlocked() {
        ResponseOutcomeDetector.Classification c =
                detector.classify(999, "whatever", 0, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.BLOCKED, c.outcome());
    }

    @Test
    void emptyBodyAtStartZeroIsBlocked() {
        ResponseOutcomeDetector.Classification c = detector.classify(200, "", 0, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.BLOCKED, c.outcome());
    }

    @Test
    void blankBodyAtStartZeroIsBlocked() {
        ResponseOutcomeDetector.Classification c = detector.classify(200, "   ", 0, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.BLOCKED, c.outcome());
    }

    @Test
    void nullBodyAtStartZeroIsBlocked() {
        ResponseOutcomeDetector.Classification c = detector.classify(200, null, 0, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.BLOCKED, c.outcome());
    }

    @Test
    void nullBodyAtStartGreaterThanZeroIsEndOfResults() {
        ResponseOutcomeDetector.Classification c = detector.classify(200, null, 20, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.END_OF_RESULTS, c.outcome());
    }

    // --- IRRELEVANT ----------------------------------------------------------

    @Test
    void unrelatedTitlesArePastThirtyPercentThresholdIsIrrelevant() {
        // A nonsense keyword search that nonetheless returned 10 well-formed but unrelated jobs.
        List<String> titles = List.of(
                "Cake Decorator", "Production Associate", "Warehouse Worker", "Retail Sales Associate",
                "Delivery Driver", "Line Cook", "Custodian", "Security Guard", "Cashier", "Housekeeper");
        ResponseOutcomeDetector.Classification c =
                detector.classify(200, "<html>10 cards here</html>", 0, 10, titles, "quantum flux capacitor");
        assertEquals(ResponseOutcome.IRRELEVANT, c.outcome());
    }

    @Test
    void mostlyMatchingTitlesAreNotIrrelevant() {
        List<String> titles = List.of(
                "Senior Software Engineer", "Software Engineer II", "Staff Software Engineer",
                "Backend Software Engineer", "Software Engineer, Platform", "Cake Decorator",
                "Software Engineer - Infra", "Software Engineer Intern", "Lead Software Engineer",
                "Software Engineer, Growth");
        ResponseOutcomeDetector.Classification c =
                detector.classify(200, "<html>10 cards here</html>", 0, 10, titles, "software engineer");
        assertEquals(ResponseOutcome.OK, c.outcome());
    }

    @Test
    void irrelevantCheckOnlyAppliesToPageOne() {
        // Same unrelated titles, but this isn't page 1 (start > 0) - deep pages legitimately
        // drift topically and must not be misclassified as IRRELEVANT.
        List<String> titles = List.of("Cake Decorator", "Production Associate");
        ResponseOutcomeDetector.Classification c =
                detector.classify(200, "<html>10 cards here</html>", 50, 10, titles, "quantum flux capacitor");
        assertEquals(ResponseOutcome.OK, c.outcome());
    }

    @Test
    void blankKeywordSkipsIrrelevantCheck() {
        List<String> titles = List.of("Cake Decorator", "Production Associate");
        ResponseOutcomeDetector.Classification c =
                detector.classify(200, "<html>10 cards here</html>", 0, 10, titles, "  ");
        assertEquals(ResponseOutcome.OK, c.outcome());
    }

    // --- unparseable flag (used by the circuit breaker, distinct from outcome) ---------------

    @Test
    void twoHundredWithZeroCardsIsEndOfResultsButFlaggedUnparseable() {
        ResponseOutcomeDetector.Classification c =
                detector.classify(200, "<html>no recognizable cards</html>", 20, 0, List.of(), "engineer");
        assertEquals(ResponseOutcome.END_OF_RESULTS, c.outcome());
        assertTrue(c.unparseable());
    }

    @Test
    void legitimateShortLastPageIsNotFlaggedUnparseable() {
        ResponseOutcomeDetector.Classification c =
                detector.classify(200, "<html>3 real cards</html>", 90, 3, List.of(), "engineer");
        assertEquals(ResponseOutcome.END_OF_RESULTS, c.outcome());
        assertTrue(!c.unparseable());
    }

    // --- detail fragments ---------------------------------------------------

    @Test
    void detailWithParsedDescriptionIsOk() {
        ResponseOutcomeDetector.Classification c = detector.classifyDetail(200, "<div>body</div>", true);
        assertEquals(ResponseOutcome.OK, c.outcome());
        assertFalse(c.unparseable());
    }

    @Test
    void detail404IsGoneNotAFailure() {
        ResponseOutcomeDetector.Classification c = detector.classifyDetail(404, "", false);
        assertEquals(ResponseOutcome.GONE, c.outcome());
        assertFalse(c.unparseable());
    }

    @Test
    void detail429And999AreHardBlocks() {
        assertEquals(ResponseOutcome.BLOCKED, detector.classifyDetail(429, "", false).outcome());
        assertEquals(ResponseOutcome.BLOCKED, detector.classifyDetail(999, "", false).outcome());
        assertFalse(detector.classifyDetail(429, "", false).unparseable());
    }

    @Test
    void detail200WithoutADescriptionIsBlockedButFlaggedUnparseable() {
        ResponseOutcomeDetector.Classification c = detector.classifyDetail(200, "<html>sign in wall</html>", false);
        assertEquals(ResponseOutcome.BLOCKED, c.outcome());
        assertTrue(c.unparseable());
    }

    @Test
    void detailEmptyBodyAndServerErrorAreSoftBlocks() {
        assertTrue(detector.classifyDetail(200, "", true).unparseable());
        assertTrue(detector.classifyDetail(503, "<html>oops</html>", false).unparseable());
    }
}
