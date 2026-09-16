package com.ubaid.jobdash.apply;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises {@link QuestionKey#normalize} collisions across differently-punctuated phrasings. */
class QuestionKeyTest {

    @Test
    void collidesTrailingAsteriskQuestionMarkAndCasing() {
        String a = QuestionKey.normalize("Do you opt-in to receive WhatsApp messages? *");
        String b = QuestionKey.normalize("do you opt-in to receive whatsapp messages");

        assertThat(a).isEqualTo(b);
        assertThat(a).isEqualTo("do you opt-in to receive whatsapp messages");
    }

    @Test
    void collapsesInternalWhitespaceAndTrims() {
        String key = QuestionKey.normalize("  What   is your   salary  expectation?  ");
        assertThat(key).isEqualTo("what is your salary expectation");
    }

    @Test
    void stripsTrailingColon() {
        assertThat(QuestionKey.normalize("Current location:")).isEqualTo("current location");
    }

    @Test
    void dropsATrailingParentheticalAnnotation() {
        assertThat(QuestionKey.normalize("Gender (voluntary EEO)")).isEqualTo(QuestionKey.normalize("Gender"));
        assertThat(QuestionKey.normalize("Veteran Status (voluntary EEO) *")).isEqualTo("veteran status");
        assertThat(QuestionKey.normalize("Are you Hispanic/Latino? (voluntary EEO)")).isEqualTo("are you hispanic/latino");
    }

    @Test
    void nullNormalizesToEmptyString() {
        assertThat(QuestionKey.normalize(null)).isEmpty();
    }
}
