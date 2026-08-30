package com.ubaid.jobdash.salary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleKeyTest {

    @Test
    void stripsLeadingSeniorityAndTrailingLevel() {
        assertThat(TitleKey.of("Sr. Software Engineer II")).isEqualTo("software engineer");
    }

    @Test
    void stripsLeadingSeniorWord() {
        assertThat(TitleKey.of("SENIOR ORACLE APPLICATION DEVELOPER"))
                .isEqualTo("oracle application developer");
    }

    @Test
    void keepsPlusSignsForCpp() {
        assertThat(TitleKey.of("Senior C++ Developer")).isEqualTo("c++ developer");
    }

    @Test
    void dropsJcTagAndKeepsRoleWords() {
        assertThat(TitleKey.of("Associate II JC65 - Computer Systems Engineers/Architects"))
                .isEqualTo("associate ii computer systems engineers architects");
    }

    @Test
    void dropsTrailingDashRegionSuffix() {
        assertThat(TitleKey.of("Data Engineer - 2 - US")).isEqualTo("data engineer");
    }

    @Test
    void dropsTrailingReqId() {
        assertThat(TitleKey.of("Software Engineer - REQ12345")).isEqualTo("software engineer");
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertThat(TitleKey.of(null)).isEmpty();
        assertThat(TitleKey.of("  ")).isEmpty();
    }
}
