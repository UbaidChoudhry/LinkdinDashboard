package com.ubaid.jobdash.salary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SocMapperTest {

    private final SocMapper mapper = new SocMapper();

    @Test
    void mapsSoftwareEngineerTo151252() {
        assertThat(mapper.toSocCode("Senior Software Engineer")).contains("15-1252");
    }

    @Test
    void mapsDataScientistTo152051() {
        assertThat(mapper.toSocCode("Data Scientist II")).contains("15-2051");
    }

    @Test
    void mapsDatabaseAdministratorTo151242() {
        assertThat(mapper.toSocCode("Database Administrator")).contains("15-1242");
    }

    @Test
    void longestKeywordWins() {
        // "machine learning engineer" must beat the shorter "software engineer".
        assertThat(mapper.toSocCode("Machine Learning Engineer")).contains("15-2051");
    }

    @Test
    void unknownTitleReturnsEmpty() {
        assertThat(mapper.toSocCode("Underwater Basket Weaver")).isEmpty();
        assertThat(mapper.candidateSocCodes("Underwater Basket Weaver")).isEmpty();
    }

    @Test
    void candidatesLeadWithPrimaryAndIncludeNeighbours() {
        var candidates = mapper.candidateSocCodes("Backend Software Engineer");
        assertThat(candidates).first().isEqualTo("15-1252");
        assertThat(candidates).contains("15-1211", "15-1299", "15-1253");
        assertThat(candidates).doesNotHaveDuplicates();
    }
}
