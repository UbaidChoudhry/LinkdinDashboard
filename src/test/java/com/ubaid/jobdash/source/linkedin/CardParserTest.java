package com.ubaid.jobdash.source.linkedin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardParserTest {

    private final CardParser parser = new CardParser();

    private static String loadFixture(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = CardParserTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parsesFirstCardOfSearchStart0Exactly() {
        List<JobCard> cards = parser.parse(loadFixture("search_start_0.html"));
        assertFalse(cards.isEmpty());

        JobCard first = cards.get(0);
        assertEquals(4458099074L, first.jobId());
        assertEquals("Software Engineer", first.title());
        assertEquals("Stealth Startup", first.company());
        assertEquals("New York, United States", first.location());
        assertEquals(LocalDate.of(2026, 8, 28), first.postedDate());

        assertTrue(first.jobUrl().startsWith(
                "https://www.linkedin.com/jobs/view/software-engineer-at-stealth-startup-4458099074"));
        assertFalse(first.jobUrl().contains("position="));
        assertFalse(first.jobUrl().contains("pageNum="));
        assertFalse(first.jobUrl().contains("refId="));
        assertFalse(first.jobUrl().contains("trackingId="));
    }

    @Test
    void searchStart0And10EachYieldTenDisjointCards() {
        List<JobCard> start0 = parser.parse(loadFixture("search_start_0.html"));
        List<JobCard> start10 = parser.parse(loadFixture("search_start_10.html"));

        assertEquals(10, start0.size());
        assertEquals(10, start10.size());

        Set<Long> ids0 = new HashSet<>();
        start0.forEach(c -> ids0.add(c.jobId()));
        Set<Long> ids10 = new HashSet<>();
        start10.forEach(c -> ids10.add(c.jobId()));

        Set<Long> intersection = new HashSet<>(ids0);
        intersection.retainAll(ids10);
        assertTrue(intersection.isEmpty(), "expected disjoint jobId sets, but found: " + intersection);
    }

    @Test
    void deepSentinelFixtureYieldsEmptyList() {
        List<JobCard> cards = parser.parse(loadFixture("deep_999.html"));
        assertTrue(cards.isEmpty());
    }

    @Test
    void nySentinelFixtureYieldsEmptyList() {
        List<JobCard> cards = parser.parse(loadFixture("ny990.html"));
        assertTrue(cards.isEmpty());
    }

    @Test
    void everyCardFromMultiCardFixturesHasValidCoreFields() {
        for (String fixture : List.of(
                "search_start_0.html", "search_start_10.html",
                "cap990.html", "r3600.html", "firehose_irrelevant.html")) {
            List<JobCard> cards = parser.parse(loadFixture(fixture));
            assertFalse(cards.isEmpty(), fixture + " should yield cards");
            for (JobCard card : cards) {
                assertTrue(card.jobId() > 0, fixture + ": jobId must be > 0");
                assertTrue(card.title() != null && !card.title().isBlank(),
                        fixture + ": title must not be blank");
                assertTrue(card.company() != null && !card.company().isBlank(),
                        fixture + ": company must not be blank");
            }
        }
    }

    @Test
    void capFixtureContainsSeniorOrPrincipalTitles() {
        List<JobCard> cards = parser.parse(loadFixture("cap990.html"));
        Pattern seniorOrPrincipal = Pattern.compile("(?i)\\b(senior|principal)\\b");

        boolean found = cards.stream()
                .map(JobCard::title)
                .anyMatch(t -> t != null && seniorOrPrincipal.matcher(t).find());

        assertTrue(found, "expected at least one senior/principal title in cap990.html");
    }

    @Test
    void allTenCardFixturesYieldExactlyTenCards() {
        for (String fixture : List.of("search_start_0.html", "search_start_10.html",
                "cap990.html", "r3600.html", "firehose_irrelevant.html")) {
            List<JobCard> cards = parser.parse(loadFixture(fixture));
            assertEquals(10, cards.size(), fixture + " should yield exactly 10 cards");
        }
    }
}
