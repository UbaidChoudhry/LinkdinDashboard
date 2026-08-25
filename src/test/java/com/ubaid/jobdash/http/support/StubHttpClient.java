package com.ubaid.jobdash.http.support;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A no-network stand-in for {@link HttpClient}: returns pre-queued canned responses (or throws
 * pre-queued failures) instead of touching a socket, and records every request it was asked
 * to send so tests can assert headers.
 */
public final class StubHttpClient extends HttpClient {

    private interface Answer {
        HttpResponse<String> respond(HttpRequest request) throws IOException, InterruptedException;
    }

    private final Deque<Answer> answers = new ArrayDeque<>();
    private final List<HttpRequest> requestsSeen = new ArrayList<>();

    public void enqueue(HttpResponse<String> response) {
        answers.addLast(req -> response);
    }

    public void enqueueFailure(IOException e) {
        answers.addLast(req -> {
            throw e;
        });
    }

    public List<HttpRequest> requestsSeen() {
        return List.copyOf(requestsSeen);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        requestsSeen.add(request);
        Answer answer = answers.pollFirst();
        if (answer == null) {
            throw new IllegalStateException("no more stubbed responses queued");
        }
        return (HttpResponse<T>) answer.respond(request);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                              HttpResponse.BodyHandler<T> responseBodyHandler) {
        throw new UnsupportedOperationException("PacedHttpClient only uses the synchronous send()");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                              HttpResponse.BodyHandler<T> responseBodyHandler,
                                                              HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        throw new UnsupportedOperationException("PacedHttpClient only uses the synchronous send()");
    }
}
