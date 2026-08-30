package com.ubaid.jobdash.salary;

/**
 * The input to a {@link SalarySource} lookup: the raw company / title / location strings from
 * the job card, plus their pre-normalized {@link CompanyKey} / {@link TitleKey} forms so a
 * source can pick whichever it needs.
 *
 * @param company    raw company name as scraped
 * @param title      raw job title as scraped
 * @param location   raw LinkedIn location string (may be blank)
 * @param companyKey normalized company key ({@link CompanyKey#of}); never null, may be blank
 * @param titleKey   normalized title key ({@link TitleKey#of}); never null, may be blank
 */
public record SalaryLookup(
        String company,
        String title,
        String location,
        String companyKey,
        String titleKey
) {
}
