package com.ubaid.jobdash.source.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Parses LinkedIn's guest job-detail fragment ({@code /jobs-guest/jobs/api/jobPosting/{id}})
 * into a {@link JobDetail}.
 * <p>
 * Pure DOM parsing, like {@link CardParser} - there is no JSON-LD on these pages (HANDOFF.md §2),
 * so the {@code show-more-less-html__markup} block is the only source of the description. A
 * fragment without that block, or with a blank one, yields {@link Optional#empty()} rather than a
 * half-populated record: an empty description is exactly the case the caller must treat as
 * "unparseable", and hiding it behind a non-null record would be the silent-success failure
 * HANDOFF.md §1 warns about.
 */
public class DetailParser {

    /** Elements whose start and end each break the line. {@code li} is handled separately. */
    private static final Set<String> BLOCK_TAGS = Set.of("p", "div", "ul", "ol", "h1", "h2", "h3", "h4",
            "h5", "h6", "section", "tr", "blockquote");

    /** Parses the fragment; empty when it carries no usable description. */
    public Optional<JobDetail> parse(String html) {
        if (html == null || html.isBlank()) {
            return Optional.empty();
        }
        Document doc = Jsoup.parse(html);
        Element markup = doc.selectFirst("div.show-more-less-html__markup");
        if (markup == null) {
            return Optional.empty();
        }
        String description = toPlainText(markup);
        if (description.isBlank()) {
            return Optional.empty();
        }

        String title = textOf(doc.selectFirst("h2.top-card-layout__title"));
        String company = textOf(doc.selectFirst("a.topcard__org-name-link"));
        String applyKind = applyKindOf(doc);

        String seniority = null;
        String employment = null;
        String function = null;
        String industries = null;
        for (Element item : doc.select("li.description__job-criteria-item")) {
            String label = textOf(item.selectFirst("h3.description__job-criteria-subheader"));
            String value = textOf(item.selectFirst("span.description__job-criteria-text"));
            if (label == null || value == null) {
                continue;
            }
            switch (label.toLowerCase(Locale.ROOT)) {
                case "seniority level" -> seniority = value;
                case "employment type" -> employment = value;
                case "job function" -> function = value;
                case "industries" -> industries = value;
                default -> {
                    // an unknown criterion; ignore rather than fail the parse
                }
            }
        }

        return Optional.of(new JobDetail(title, company, description, hashOf(description),
                seniority, employment, function, industries, applyKind));
    }

    /**
     * {@code onsite} (Easy Apply) when the Apply button's tracking name equals
     * {@code public_jobs_apply-link-onsite}; {@code offsite} when any sign-in modal's
     * impression id starts with {@code public_jobs_apply-link-offsite} - other, unrelated
     * sign-in modals coexist in the same fragment (save, AI button, …), so match the prefix,
     * never assume a single modal; else null.
     */
    private static String applyKindOf(Document doc) {
        for (Element button : doc.select("[data-tracking-control-name]")) {
            if ("public_jobs_apply-link-onsite".equals(button.attr("data-tracking-control-name"))) {
                return "onsite";
            }
        }
        for (Element modal : doc.select("div.contextual-sign-in-modal[data-impression-id]")) {
            if (modal.attr("data-impression-id").startsWith("public_jobs_apply-link-offsite")) {
                return "offsite";
            }
        }
        return null;
    }

    /**
     * The relay-detection key from HANDOFF.md §5: lower-cased, all whitespace runs collapsed to a
     * single space, trimmed, then MD5-hashed. Computed here rather than in SQL so every writer
     * produces byte-identical keys.
     */
    public static String hashOf(String description) {
        String normalised = description.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md5.digest(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is a required JDK algorithm", e);
        }
    }

    /**
     * Renders the description block as plain text with structure kept legible for a reader (and
     * the AI scan): {@code <br>} and block-level boundaries become newlines, list items become
     * {@code "- "} bullets, everything else is flattened, then blank-line runs are squeezed.
     * Jsoup's own {@code text()} would run every bullet point into one line.
     */
    private static String toPlainText(Element markup) {
        StringBuilder sb = new StringBuilder();
        markup.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    sb.append(text.getWholeText().replace(' ', ' '));
                } else if (node instanceof Element el) {
                    String tag = el.normalName();
                    if ("br".equals(tag)) {
                        // Always a break: two in a row is how LinkedIn writes a paragraph gap.
                        sb.append('\n');
                    } else if ("li".equals(tag)) {
                        breakLine(sb);
                        sb.append("- ");
                    } else if (BLOCK_TAGS.contains(tag)) {
                        breakLine(sb);
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                // A list item's end needs no break of its own: the next item's head or the
                // list's own tail supplies it, and adding one here would double-space bullets.
                if (node instanceof Element el && BLOCK_TAGS.contains(el.normalName())) {
                    breakLine(sb);
                }
            }
        });
        // Collapse horizontal whitespace within lines, then squeeze runs of blank lines.
        String[] lines = sb.toString().split("\n");
        StringBuilder out = new StringBuilder();
        int blankRun = 0;
        for (String line : lines) {
            String trimmed = line.replaceAll("[ \\t\\r\\f]+", " ").trim();
            if (trimmed.isEmpty()) {
                blankRun++;
                continue;
            }
            if (!out.isEmpty()) {
                out.append(blankRun > 0 ? "\n\n" : "\n");
            }
            out.append(trimmed);
            blankRun = 0;
        }
        return out.toString();
    }

    /** Ends the current line, unless the buffer is empty or already ends with a newline. */
    private static void breakLine(StringBuilder sb) {
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private static String textOf(Element el) {
        if (el == null) {
            return null;
        }
        String text = el.text().trim();
        return text.isEmpty() ? null : text;
    }
}
