package com.ubaid.jobdash.source.ats;

import com.ubaid.jobdash.http.Sleeper;
import com.ubaid.jobdash.store.ExternalRequestLogRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Registers {@link AtsProperties} and wires the ATS job-board beans: the {@link AtsRateLimiter}
 * and a dedicated {@link HttpClient} for the board APIs.
 */
@Configuration
@EnableConfigurationProperties(AtsProperties.class)
public class AtsConfiguration {

    /**
     * MUST remain a singleton (the default scope — do not add {@code @Scope("prototype")}).
     * {@link AtsRateLimiter} keeps the process-wide pacing gate in an instance field, exactly
     * like {@code SalaryRateLimiter} and {@code http.RateLimiter}; a second instance would let
     * two callers pace independently and silently double the outbound request rate.
     */
    @Bean
    public AtsRateLimiter atsRateLimiter(Clock clock, Sleeper sleeper,
                                         ExternalRequestLogRepository externalRequestLogRepository,
                                         AtsProperties properties) {
        Map<String, Integer> dailyCaps = Map.of(
                "greenhouse", properties.dailyCap().greenhouse(),
                "lever", properties.dailyCap().lever(),
                "workday", properties.dailyCap().workday());
        return new AtsRateLimiter(clock, sleeper, externalRequestLogRepository,
                properties.pacing().minDelay(), dailyCaps);
    }

    /**
     * A separate client from {@code linkedInHttpClient} and {@code salaryHttpClient}. The board
     * APIs are ordinary JSON endpoints that follow redirects normally, and their payloads are
     * large (Palantir's Lever board is ~6MB, Veeva's ~12MB), so the read timeout is generous.
     */
    @Bean
    public HttpClient atsHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
