package com.ubaid.jobdash.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the Workday careers-URL site extraction in {@link SourceController}. Workday serves the
 * same board under paths with and without a locale segment, so the site id is not simply "the
 * second path segment" - getting this wrong silently stores a null site, and the company then
 * looks configured while never returning a single job.
 */
class SourceControllerSiteParsingTest {

    @Test
    void extractsSiteFromAPathWithALocaleSegment() {
        assertEquals("NVIDIAExternalCareerSite",
                SourceController.siteFromCareersPath("/en-US/NVIDIAExternalCareerSite/job/Israel-Yokneam/Software-Engineer_JR2015623"));
    }

    @Test
    void extractsSiteFromAPathWithNoLocaleSegment() {
        assertEquals("NVIDIAExternalCareerSite",
                SourceController.siteFromCareersPath("/NVIDIAExternalCareerSite"));
    }

    @Test
    void extractsSiteWhenTheLocaleIsBareLanguageOnly() {
        assertEquals("External_Career_Site", SourceController.siteFromCareersPath("/en/External_Career_Site/"));
    }

    /**
     * "Search" and "Visa" are both real, verified Workday site ids. Neither is a locale, so a
     * naive "skip the first segment" rule would discard the site itself and return null.
     */
    @Test
    void doesNotMistakeARealSiteIdForALocale() {
        assertEquals("Search", SourceController.siteFromCareersPath("/Search/"));
        assertEquals("Visa", SourceController.siteFromCareersPath("/Visa"));
        assertEquals("jobs", SourceController.siteFromCareersPath("/jobs"));
    }

    @Test
    void returnsNullWhenThereIsNoUsablePath() {
        assertNull(SourceController.siteFromCareersPath(null));
        assertNull(SourceController.siteFromCareersPath(""));
        assertNull(SourceController.siteFromCareersPath("/"));
        assertNull(SourceController.siteFromCareersPath("/en-US/"));
    }
}
