package com.ubaid.jobdash.salary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Maps a free-text job title to a US 2018 SOC code, focused on major group 15 (computer
 * occupations). Matching is keyword-based on the longest matching keyword; unknown titles
 * return empty / an empty candidate list.
 * <p>
 * SOC codes referenced: 15-1211 (computer systems analysts), 15-1212 (information security
 * analysts), 15-1242 (database administrators), 15-1244 (network / computer systems
 * administrators), 15-1252 (software developers), 15-1253 (software QA analysts & testers),
 * 15-1254 (web developers), 15-1299 (computer occupations, all other), 15-2051 (data
 * scientists), 11-2021 (marketing managers) / 13-1082 (project management specialists),
 * 17-2061 (computer hardware engineers).
 */
@Component
public final class SocMapper {

    /** Keyword -> primary SOC. Insertion order does not matter; longest keyword wins at match time. */
    private static final Map<String, String> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("machine learning engineer", "15-2051");
        KEYWORDS.put("machine learning", "15-2051");
        KEYWORDS.put("ml engineer", "15-2051");
        KEYWORDS.put("data scientist", "15-2051");
        KEYWORDS.put("data science", "15-2051");
        KEYWORDS.put("data engineer", "15-1252");
        KEYWORDS.put("software engineer", "15-1252");
        KEYWORDS.put("software developer", "15-1252");
        KEYWORDS.put("software development engineer", "15-1252");
        KEYWORDS.put("full stack", "15-1252");
        KEYWORDS.put("backend engineer", "15-1252");
        KEYWORDS.put("frontend engineer", "15-1252");
        KEYWORDS.put("sde", "15-1252");
        KEYWORDS.put("swe", "15-1252");
        KEYWORDS.put("devops", "15-1252");
        KEYWORDS.put("sre", "15-1252");
        KEYWORDS.put("site reliability", "15-1252");
        KEYWORDS.put("platform engineer", "15-1252");
        KEYWORDS.put("infrastructure engineer", "15-1252");
        KEYWORDS.put("mobile developer", "15-1252");
        KEYWORDS.put("ios developer", "15-1252");
        KEYWORDS.put("android developer", "15-1252");
        KEYWORDS.put("systems engineer", "15-1299");
        KEYWORDS.put("systems architect", "15-1299");
        KEYWORDS.put("solutions architect", "15-1299");
        KEYWORDS.put("computer systems engineer", "15-1299");
        KEYWORDS.put("systems analyst", "15-1211");
        KEYWORDS.put("business systems analyst", "15-1211");
        KEYWORDS.put("network engineer", "15-1244");
        KEYWORDS.put("network administrator", "15-1244");
        KEYWORDS.put("systems administrator", "15-1244");
        KEYWORDS.put("database administrator", "15-1242");
        KEYWORDS.put("database engineer", "15-1242");
        KEYWORDS.put("dba", "15-1242");
        KEYWORDS.put("security engineer", "15-1212");
        KEYWORDS.put("security analyst", "15-1212");
        KEYWORDS.put("information security", "15-1212");
        KEYWORDS.put("qa engineer", "15-1253");
        KEYWORDS.put("test engineer", "15-1253");
        KEYWORDS.put("quality assurance", "15-1253");
        KEYWORDS.put("sdet", "15-1253");
        KEYWORDS.put("web developer", "15-1254");
        KEYWORDS.put("product manager", "11-2021");
        KEYWORDS.put("hardware engineer", "17-2061");
        KEYWORDS.put("computer engineer", "17-2061");
    }

    /** Neighbouring SOC codes tried as fallbacks after the primary, in lookup order. */
    private static final List<String> NEIGHBOURS = List.of("15-1252", "15-1211", "15-1299", "15-1253");

    /** The single best SOC code for this title, or empty if no keyword matched. */
    public Optional<String> toSocCode(String jobTitle) {
        return Optional.ofNullable(matchLongest(normalize(jobTitle)));
    }

    /**
     * The primary SOC plus reasonable neighbours for a lookup fallback chain. Empty when no
     * keyword matched. The primary is always first and never duplicated.
     */
    public List<String> candidateSocCodes(String jobTitle) {
        String primary = matchLongest(normalize(jobTitle));
        if (primary == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(primary);
        for (String n : NEIGHBOURS) {
            if (!out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    private static String normalize(String jobTitle) {
        String key = TitleKey.of(jobTitle);
        return key.isEmpty() && jobTitle != null ? jobTitle.toLowerCase() : key;
    }

    private static String matchLongest(String title) {
        String best = null;
        int bestLen = -1;
        for (Map.Entry<String, String> e : KEYWORDS.entrySet()) {
            String kw = e.getKey();
            if (kw.length() > bestLen && containsWord(title, kw)) {
                best = e.getValue();
                bestLen = kw.length();
            }
        }
        return best;
    }

    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                return false;
            }
            boolean leftOk = idx == 0 || haystack.charAt(idx - 1) == ' ';
            int endIdx = idx + needle.length();
            boolean rightOk = endIdx == haystack.length() || haystack.charAt(endIdx) == ' ';
            if (leftOk && rightOk) {
                return true;
            }
            from = idx + 1;
        }
    }
}
