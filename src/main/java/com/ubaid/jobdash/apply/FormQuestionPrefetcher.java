package com.ubaid.jobdash.apply;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-reads an application form's questions on the server, before Claude ever opens a browser
 * tab - the fix for "Applications are slow" (HANDOFF §12 follow-up): a Greenhouse direct-form URL
 * is a server-rendered page, so a plain HTTP GET plus jsoup gets every field label for a fraction
 * of the cost of a browser-driven {@code read_page} call, and the result is handed to Claude in
 * the prompt so it can decide every answer before touching the browser at all.
 * <p>
 * Best-effort by design, exactly like {@link ApplyUrlResolver}: {@link #prefetch} never throws -
 * a network failure, a timeout, or a page that doesn't parse the way this class expects just
 * yields an empty list, logged at debug, and the apply flow falls back to reading the form live
 * through the browser as before.
 */
@Component
public class FormQuestionPrefetcher {

    private static final Logger log = LoggerFactory.getLogger(FormQuestionPrefetcher.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_OPTIONS = 8;
    private static final int MIN_LABEL_LENGTH = 2;
    private static final int MAX_LABEL_LENGTH = 200;

    /** Whole-label texts that are never real questions - upload-button and manual-entry affordances. */
    private static final Set<String> SKIP_EXACT = Set.of("attach", "enter manually");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    /**
     * Matches one Greenhouse "question" object embedded in the page's client-side state JSON:
     * {@code "label":"...","description":null-or-"...","fields":[{"name":"...",
     * "type":"multi_value_(single|multi)_select","values":[...]}]}. Greenhouse's newer board pages
     * render these dropdowns entirely client-side (no {@code <select>}/{@code <option>} tags ever
     * land in the HTML), so the only way to recover their option lists from a single GET is this
     * embedded state - jsoup's DOM extraction below is the primary path and covers every ATS whose
     * pages render real form controls (Lever, older Greenhouse boards).
     */
    private static final Pattern JSON_QUESTION = Pattern.compile(
            "\"label\":\"((?:\\\\.|[^\"\\\\])*)\",\"description\":(?:null|\"(?:\\\\.|[^\"\\\\])*\"),"
                    + "\"fields\":\\[\\{\"name\":\"[^\"]*\",\"type\":\"multi_value_(?:single|multi)_select\","
                    + "\"values\":\\[([^\\]]*)\\]");

    private static final Pattern JSON_OPTION_LABEL = Pattern.compile("\"label\":\"((?:\\\\.|[^\"\\\\])*)\"");

    private final HttpClient httpClient;

    public FormQuestionPrefetcher() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches {@code directFormUrl} and extracts its questions, one line per field. Never throws -
     * any failure (network, timeout, unexpected content) is logged at debug and yields an empty
     * list.
     */
    public List<String> prefetch(String directFormUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(directFormUrl))
                    .timeout(TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String html = readCapped(response.body(), MAX_BODY_BYTES);
            return extract(html, directFormUrl);
        } catch (Exception e) {
            log.debug("failed to prefetch form questions from {}: {}", directFormUrl, e.toString());
            return List.of();
        }
    }

    private static String readCapped(InputStream in, int maxBytes) throws IOException {
        try (in) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            int total = 0;
            while (total < maxBytes && (read = in.read(buffer, 0, Math.min(buffer.length, maxBytes - total))) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts one line per form question from raw HTML - package-visible so tests can exercise it
     * directly against fixture HTML without a network call. Collects {@code label}, {@code legend}
     * and Lever's {@code .application-label} elements as candidate questions; skips any whose
     * associated control is a radio/checkbox (those are individual options within a group whose
     * real question text is its enclosing legend/application-label, not a question of their own -
     * this is what keeps a country checklist from producing one spurious line per country).
     * Never throws.
     */
    static List<String> extract(String html, String baseUrl) {
        try {
            Document doc = Jsoup.parse(html, baseUrl);
            Map<String, String> jsonSelectOptions = extractEmbeddedJsonOptions(html);

            List<Element> candidates = new ArrayList<>();
            candidates.addAll(doc.select("label"));
            candidates.addAll(doc.select("legend"));
            candidates.addAll(doc.select(".application-label"));

            Map<String, String> lines = new LinkedHashMap<>();
            for (Element el : candidates) {
                if (isOptionLabel(el, doc)) {
                    continue;
                }

                Element clone = el.clone();
                boolean requiredMarker = stripRequiredMarkers(clone);
                String text = cleanText(clone.text());
                if (text.length() < MIN_LABEL_LENGTH || text.length() > MAX_LABEL_LENGTH) {
                    continue;
                }
                String normalized = QuestionKey.normalize(text);
                if (normalized.isEmpty() || SKIP_EXACT.contains(normalized) || lines.containsKey(normalized)) {
                    continue;
                }

                Element control = associatedControl(el, doc);
                boolean required = requiredMarker || isRequiredControl(control);
                String selectSuffix = selectSuffix(el, control, jsonSelectOptions.get(normalized));

                StringBuilder line = new StringBuilder(text);
                if (required) {
                    line.append(" [required]");
                }
                if (selectSuffix != null) {
                    line.append(selectSuffix);
                }
                lines.put(normalized, line.toString());
            }
            return new ArrayList<>(lines.values());
        } catch (RuntimeException e) {
            log.debug("failed to extract form questions: {}", e.toString());
            return List.of();
        }
    }

    /** True when {@code el}'s associated control is a radio/checkbox - one option within a group, not its own question. */
    private static boolean isOptionLabel(Element el, Document doc) {
        Element control = associatedControl(el, doc);
        if (control == null) {
            return false;
        }
        String type = control.attr("type").toLowerCase();
        return control.tagName().equalsIgnoreCase("input") && (type.equals("radio") || type.equals("checkbox"));
    }

    /** The form control this label is "for", either by {@code for=id} or by nesting the control inside it. */
    private static Element associatedControl(Element el, Document doc) {
        String forId = el.attr("for");
        if (!forId.isBlank()) {
            Element byId = doc.getElementById(forId);
            if (byId != null) {
                return byId;
            }
        }
        Element nested = el.selectFirst("input, select, textarea");
        return nested;
    }

    /**
     * Removes "this field is required" marker spans (a lone {@code *}/{@code ✱} glyph, however the
     * ATS styles it) from a cloned element in place, returning whether one was found - so the
     * marker never pollutes the visible label text but still sets {@code [required]}.
     */
    private static boolean stripRequiredMarkers(Element clone) {
        boolean found = false;
        for (Element marker : clone.select("span.required, span[aria-hidden=true]")) {
            String text = marker.text().trim();
            if (text.equals("*") || text.equals("✱") || text.isEmpty()) {
                marker.remove();
                found = true;
            }
        }
        return found;
    }

    private static boolean isRequiredControl(Element control) {
        if (control == null) {
            return false;
        }
        return control.hasAttr("required") || "true".equalsIgnoreCase(control.attr("aria-required"));
    }

    /**
     * A {@code " [select: opt1 | opt2 | … (+N more)]"} suffix for a genuine {@code <select>}
     * control (found directly, nested in the label, or in the label's immediate parent - covers
     * Lever's {@code <label><div class="application-label">Gender</div><select>...} shape), or, if
     * no {@code <select>} exists, from the page's embedded JSON question state (Greenhouse's
     * client-rendered dropdowns). Returns null when neither source has an answer.
     */
    private static String selectSuffix(Element labelEl, Element control, String jsonOptions) {
        Element select = null;
        if (control != null && control.tagName().equalsIgnoreCase("select")) {
            select = control;
        }
        if (select == null) {
            select = labelEl.selectFirst("select");
        }
        if (select == null && labelEl.parent() != null) {
            select = labelEl.parent().selectFirst("select");
        }
        if (select != null) {
            List<String> options = new ArrayList<>();
            for (Element option : select.select("option")) {
                String value = option.attr("value");
                String text = cleanText(option.text());
                if (value.isBlank() || text.isBlank()) {
                    continue;
                }
                options.add(text);
            }
            if (!options.isEmpty()) {
                return formatOptions(options);
            }
        }
        return jsonOptions;
    }

    private static String formatOptions(List<String> options) {
        int shown = Math.min(MAX_OPTIONS, options.size());
        String joined = String.join(" | ", options.subList(0, shown));
        String more = options.size() > MAX_OPTIONS ? " (+" + (options.size() - MAX_OPTIONS) + " more)" : "";
        return " [select: " + joined + more + "]";
    }

    /**
     * Scans the whole page for embedded {@code "label"/"values"} question blocks (see
     * {@link #JSON_QUESTION}) and returns a map from the question's normalized text to a ready-made
     * {@code [select: ...]} suffix - consulted only when no real {@code <select>} element exists for
     * a matching DOM label.
     */
    private static Map<String, String> extractEmbeddedJsonOptions(String html) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = JSON_QUESTION.matcher(html);
        while (matcher.find()) {
            String label = unescapeJson(matcher.group(1));
            String normalized = QuestionKey.normalize(label);
            if (normalized.isEmpty() || result.containsKey(normalized)) {
                continue;
            }
            List<String> options = new ArrayList<>();
            Matcher optionMatcher = JSON_OPTION_LABEL.matcher(matcher.group(2));
            while (optionMatcher.find()) {
                options.add(unescapeJson(optionMatcher.group(1)));
            }
            if (!options.isEmpty()) {
                result.put(normalized, formatOptions(options));
            }
        }
        return result;
    }

    private static String unescapeJson(String raw) {
        return raw.replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\").trim();
    }

    private static String cleanText(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }
}
