package com.ubaid.jobdash.filter;

import com.ubaid.jobdash.domain.FilterVerdict;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Immutable, pre-compiled set of local exclusion rules built once from the current
 * {@code exclude_word} / {@code company_blocklist} repository state.
 *
 * <p>Title matching is case-insensitive and whole-word: each excluded word is compiled to a
 * pattern that requires non-alphanumeric (or string-boundary) characters on both sides, so
 * {@code Lead} matches "Team Lead" but not "Leadership", and {@code Intern} does not match
 * "International". {@code \b} is deliberately not used for this, because Java's {@code \b} is
 * only a boundary when exactly one side is a word character ({@code [a-zA-Z0-9_]}) — a word like
 * {@code C++} ends in non-word characters, so a trailing {@code \b} would never match ("++ "
 * has non-word characters on both sides of the position). The lookaround form used here checks
 * literal alphanumeric-adjacency instead, so it works uniformly for ordinary words and for
 * punctuation-containing ones like {@code C++}.
 *
 * <p>Company matching is a case-insensitive *exact* match against the full company name — never
 * a substring match, so blocking "Meta" must not reject "Metabase".
 */
public final class FilterRuleSet {

    /** Result of evaluating one (title, company) pair against this rule set. */
    public record Verdict(FilterVerdict verdict, String reason) {
        public static Verdict pass() {
            return new Verdict(FilterVerdict.PASS, null);
        }

        public static Verdict reject(String reason) {
            return new Verdict(FilterVerdict.REJECT, reason);
        }
    }

    private record TitleRule(String word, Pattern pattern) {
    }

    private final List<TitleRule> titleRules;
    private final Map<String, String> blockedCompaniesByLowerName;

    private FilterRuleSet(List<TitleRule> titleRules, Map<String, String> blockedCompaniesByLowerName) {
        this.titleRules = titleRules;
        this.blockedCompaniesByLowerName = blockedCompaniesByLowerName;
    }

    /**
     * Builds a rule set from the raw exclude-word list and blocked-company names. Patterns are
     * compiled once here, not per evaluated row.
     */
    public static FilterRuleSet build(List<String> excludeWords, List<String> blockedCompanies) {
        List<TitleRule> rules = excludeWords.stream()
                .map(word -> new TitleRule(word, compileTitlePattern(word)))
                .toList();
        Map<String, String> companies = blockedCompanies.stream()
                .collect(Collectors.toMap(
                        c -> c.toLowerCase(Locale.ROOT),
                        c -> c,
                        (a, b) -> a));
        return new FilterRuleSet(rules, companies);
    }

    private static Pattern compileTitlePattern(String word) {
        if (word.equalsIgnoreCase("sr")) {
            // "Sr" / "Sr." must match, but not "Sri" or the middle of a longer word.
            return Pattern.compile("(?<![A-Za-z0-9])sr\\.?(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
        }
        String quoted = Pattern.quote(word);
        return Pattern.compile("(?<![A-Za-z0-9])" + quoted + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    }

    /** Evaluates a single (title, company) pair. Company blocklist is checked before title words. */
    public Verdict evaluate(String title, String company) {
        if (company != null) {
            String canonical = blockedCompaniesByLowerName.get(company.toLowerCase(Locale.ROOT));
            if (canonical != null) {
                return Verdict.reject("company:" + canonical);
            }
        }
        if (title != null) {
            for (TitleRule rule : titleRules) {
                if (rule.pattern().matcher(title).find()) {
                    return Verdict.reject("title_word:" + rule.word());
                }
            }
        }
        return Verdict.pass();
    }
}
