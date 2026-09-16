package com.ubaid.jobdash.web.dto;

/** Body of {@code PUT /api/profile/answers/{id}}. A blank {@code answer} sets status back to {@code pending}. */
public record ProfileAnswerUpdateRequest(String answer) {
}
