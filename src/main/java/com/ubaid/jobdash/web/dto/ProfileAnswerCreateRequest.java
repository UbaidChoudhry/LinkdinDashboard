package com.ubaid.jobdash.web.dto;

/** Body of {@code POST /api/profile/answers}. A blank {@code question} is a 400. */
public record ProfileAnswerCreateRequest(String question, String answer) {
}
