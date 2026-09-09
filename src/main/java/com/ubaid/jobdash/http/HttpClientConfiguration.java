package com.ubaid.jobdash.http;

import com.ubaid.jobdash.source.linkedin.CardParser;
import com.ubaid.jobdash.source.linkedin.DetailParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the T4 HTTP-safety components ({@link RateLimiter}, {@link CircuitBreaker},
 * {@link PacedHttpClient}, {@link ResponseOutcomeDetector}, {@link Sleeper}) as Spring beans,
 * reading their tuning from {@link SweepProperties}. T4 built these as plain, framework-agnostic
 * classes; this configuration is the only place that connects them to the Spring context.
 */
@Configuration
public class HttpClientConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public HttpClient linkedInHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    public Sleeper sleeper() {
        return Sleeper.real();
    }

    /** T4's parser has no Spring annotations by design; this is its only bean registration. */
    @Bean
    public CardParser cardParser() {
        return new CardParser();
    }

    /** Same convention as {@link #cardParser()}: the job-detail fragment parser is a plain class. */
    @Bean
    public DetailParser detailParser() {
        return new DetailParser();
    }

    /**
     * MUST remain a singleton (the default and only scope used here — do not add
     * {@code @Scope("prototype")} or otherwise cause more than one instance to be created).
     * {@link RateLimiter} holds the process-wide pacing gate ({@code pacingGate}) and the
     * per-run request counter in instance fields; a second instance would let two callers pace
     * and count completely independently of one another, silently doubling the effective
     * outbound request rate and letting the per-run cap be exceeded.
     */
    @Bean
    public RateLimiter rateLimiter(Clock clock, Sleeper sleeper, RequestBudgetStore requestBudgetStore,
                                    SweepProperties properties) {
        SweepProperties.Pacing pacing = properties.pacing();
        SweepProperties.Budget budget = properties.budget();
        return new RateLimiter(clock, sleeper, requestBudgetStore,
                pacing.minDelay(), pacing.maxDelay(), budget.perRun(), budget.perRollingDay());
    }

    /**
     * MUST remain a singleton (the default and only scope used here — do not add
     * {@code @Scope("prototype")} or otherwise cause more than one instance to be created).
     * {@link CircuitBreaker#tryAcquire()} guards its HALF_OPEN state with an in-memory
     * {@code probeInFlight} flag that is deliberately not persisted (see the class javadoc). If
     * more than one instance of this bean existed, each would track its own flag and the
     * "exactly one probe request while half-open" guarantee would silently break: a blocked
     * host would see a burst of concurrent retries instead of a single, careful probe.
     */
    @Bean
    public CircuitBreaker circuitBreaker(CircuitStateStore circuitStateStore, Clock clock,
                                          SweepProperties properties) {
        SweepProperties.Breaker breaker = properties.breaker();
        return new CircuitBreaker(circuitStateStore, clock, breaker.openDuration(),
                breaker.maxOpenDuration(), breaker.softFailureThreshold());
    }

    @Bean
    public ResponseOutcomeDetector responseOutcomeDetector() {
        return new ResponseOutcomeDetector();
    }

    @Bean
    public PacedHttpClient pacedHttpClient(HttpClient linkedInHttpClient, RateLimiter rateLimiter,
                                            CircuitBreaker circuitBreaker, RequestBudgetStore requestBudgetStore,
                                            ResponseOutcomeDetector responseOutcomeDetector, CardParser cardParser,
                                            DetailParser detailParser, Clock clock) {
        return new PacedHttpClient(linkedInHttpClient, rateLimiter, circuitBreaker, requestBudgetStore,
                responseOutcomeDetector, cardParser, detailParser, clock);
    }
}
