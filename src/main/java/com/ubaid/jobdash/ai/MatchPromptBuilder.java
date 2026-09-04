package com.ubaid.jobdash.ai;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Renders a batch of {@link JobForMatching} plus a resume into the prompt text sent to the
 * Claude CLI, and holds the JSON schema the CLI is told to shape its response into.
 * <p>
 * ATS boards hand back HTML descriptions in two different disguises: Greenhouse escapes the HTML
 * itself (so the raw text contains literal {@code &lt;p&gt;}), while Lever sends real HTML tags.
 * {@link #stripHtml(String)} unescapes first, then strips tags, so both forms end up as the same
 * plain text — and plain-text input (no tags, no entities) passes through unchanged.
 */
@Component
public class MatchPromptBuilder {

    /**
     * JSON schema for {@code --json-schema}: a required {@code results} array of objects, each
     * with required {@code ref}/{@code recommended}/{@code reason}.
     */
    public static final String RESULT_JSON_SCHEMA = """
            {
              "type": "object",
              "required": ["results"],
              "properties": {
                "results": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["ref", "recommended", "reason"],
                    "properties": {
                      "ref": {"type": "string"},
                      "recommended": {"type": "boolean"},
                      "reason": {"type": "string"}
                    }
                  }
                }
              }
            }
            """;

    private static final String TRUNCATION_MARKER = " …[truncated]";

    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public MatchPromptBuilder(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Renders the resume and one batch of jobs into the full prompt text sent on stdin. */
    public String build(String resume, List<JobForMatching> jobs) {
        ArrayNode array = objectMapper.createArrayNode();
        for (JobForMatching job : jobs) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("ref", nullToEmpty(job.ref()));
            node.put("title", nullToEmpty(job.title()));
            node.put("company", nullToEmpty(job.company()));
            node.put("location", nullToEmpty(job.location()));
            node.put("description", truncate(stripHtml(job.description()), properties.maxDescriptionChars()));
            array.add(node);
        }
        String jobsJson = objectMapper.writeValueAsString(array);

        return """
                You are screening job postings against a candidate's resume.

                For each job in the JSON array below, decide whether it is a good match for the
                resume ("recommended": true or false) and give exactly ONE concise sentence of
                reasoning that names specific evidence — a matching or missing skill, a seniority
                mismatch, or a domain mismatch — rather than a generic statement.

                Echo each job's "ref" value back exactly in your corresponding result so the
                verdicts can be matched to the jobs.

                RESUME:
                %s

                JOBS:
                %s
                """.formatted(resume, jobsJson);
    }

    /** Strips HTML, handling both real tags (Lever) and entity-escaped tags (Greenhouse). */
    private static String stripHtml(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String unescaped = Parser.unescapeEntities(raw, false);
        return Jsoup.parse(unescaped).text();
    }

    /** Cuts on a word boundary at {@code maxChars} and appends an explicit truncation marker. */
    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String cut = text.substring(0, maxChars);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > 0) {
            cut = cut.substring(0, lastSpace);
        }
        return cut + TRUNCATION_MARKER;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
