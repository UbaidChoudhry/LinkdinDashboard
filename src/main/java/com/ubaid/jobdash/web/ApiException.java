package com.ubaid.jobdash.web;

import org.springframework.http.HttpStatus;

/**
 * A REST-layer error with a specific HTTP status and a message meant to be shown to the caller
 * as-is: what went wrong and, where useful, what to do about it. Caught by
 * {@link GlobalExceptionHandler} and rendered as {@code {"message": ...}} — never a stack trace.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
