package com.ubaid.jobdash.source.linkedin;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LinkedIn public job-search result HTML fragments into {@link JobCard} records.
 * <p>
 * Pure parsing only — no network calls. Malformed cards (no extractable jobId) are
 * skipped rather than raising an exception, and an empty or sentinel fragment yields
 * an empty list.
 */
public class CardParser {

    private static final Pattern URN_ID_PATTERN = Pattern.compile("urn:li:jobPosting:(\\d+)");
    private static final Pattern TRAILING_ID_PATTERN = Pattern.compile("(\\d+)(?:[/?#].*)?$");

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "position", "pageNum", "refId", "trackingId", "trk",
            "currentJobId", "spellCorrectionEnabled"
    );

    /**
     * Parses the given raw HTML fragment (a LinkedIn job-search results page or fragment)
     * into a list of {@link JobCard}s. Never returns null; returns an empty list for
     * unparseable or sentinel input.
     */
    public List<JobCard> parse(String html) {
        List<JobCard> cards = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return cards;
        }

        Document doc = Jsoup.parse(html);
        Elements listItems = doc.select("li:has(div.base-card)");

        for (Element li : listItems) {
            Element base = li.selectFirst("div.base-card");
            if (base == null) {
                continue;
            }
            JobCard card = parseCard(base);
            if (card != null) {
                cards.add(card);
            }
        }

        return cards;
    }

    private JobCard parseCard(Element base) {
        Long jobId = extractJobId(base);
        if (jobId == null) {
            return null;
        }

        String title = textOf(base.selectFirst("h3.base-search-card__title"));

        Element subtitle = base.selectFirst("h4.base-search-card__subtitle");
        String company = textOf(subtitle);
        String companyUrl = null;
        if (subtitle != null) {
            Element companyLink = subtitle.selectFirst("a");
            if (companyLink != null && companyLink.hasAttr("href")) {
                companyUrl = companyLink.attr("abs:href");
                if (companyUrl == null || companyUrl.isBlank()) {
                    companyUrl = companyLink.attr("href");
                }
            }
        }

        String location = textOf(base.selectFirst("span.job-search-card__location"));

        LocalDate postedDate = null;
        Element time = base.selectFirst("time");
        if (time != null && time.hasAttr("datetime")) {
            postedDate = parseDate(time.attr("datetime"));
        }

        String jobUrl = null;
        Element link = base.selectFirst("a.base-card__full-link");
        if (link != null && link.hasAttr("href")) {
            jobUrl = stripTrackingParams(link.attr("href"));
        }

        return new JobCard(jobId, title, company, location, postedDate, jobUrl, companyUrl);
    }

    private Long extractJobId(Element base) {
        String urn = base.attr("data-entity-urn");
        if (urn != null && !urn.isBlank()) {
            Matcher m = URN_ID_PATTERN.matcher(urn);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1));
                } catch (NumberFormatException ignored) {
                    // fall through to href fallback
                }
            }
        }

        Element link = base.selectFirst("a.base-card__full-link");
        if (link != null && link.hasAttr("href")) {
            String href = link.attr("href");
            // strip query/fragment before looking for the trailing numeric id
            String path = href;
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            int h = path.indexOf('#');
            if (h >= 0) {
                path = path.substring(0, h);
            }
            Matcher m = TRAILING_ID_PATTERN.matcher(path);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }

        return null;
    }

    private static String textOf(Element el) {
        if (el == null) {
            return null;
        }
        String text = el.text();
        return text == null ? null : text.trim();
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String stripTrackingParams(String href) {
        if (href == null || href.isBlank()) {
            return href;
        }
        String base = href;
        String query = null;
        int q = href.indexOf('?');
        if (q >= 0) {
            base = href.substring(0, q);
            query = href.substring(q + 1);
        }

        if (query == null || query.isBlank()) {
            return base;
        }

        Set<String> kept = new LinkedHashSet<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            if (!TRACKING_PARAMS.contains(key)) {
                kept.add(pair);
            }
        }

        if (kept.isEmpty()) {
            return base;
        }
        return base + "?" + String.join("&", kept);
    }
}
