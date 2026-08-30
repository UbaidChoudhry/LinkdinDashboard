package com.ubaid.jobdash.salary;

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
 * Registers {@link SalaryProperties} and wires the salary-enrichment beans: the
 * {@link SalaryRateLimiter} and a dedicated {@link HttpClient} for the external salary APIs.
 * <p>
 * Kept as an explicit {@code @EnableConfigurationProperties} registration (mirroring
 * {@code SweepPropertiesConfiguration}) so this package is self-contained and the application's
 * main class is untouched.
 */
@Configuration
@EnableConfigurationProperties(SalaryProperties.class)
public class SalaryConfiguration {

    /**
     * MUST remain a singleton (the default scope — do not add {@code @Scope("prototype")}).
     * {@link SalaryRateLimiter} keeps the process-wide pacing gate ({@code lastCallAt}) in an
     * instance field, exactly like {@code RateLimiter} in {@code HttpClientConfiguration}; a
     * second instance would let two callers pace independently and silently double the outbound
     * request rate against the external salary APIs.
     */
    @Bean
    public SalaryRateLimiter salaryRateLimiter(Clock clock, Sleeper sleeper,
                                               ExternalRequestLogRepository externalRequestLogRepository,
                                               SalaryProperties properties) {
        Map<String, Integer> dailyCaps = Map.of(
                "adzuna", properties.dailyCap().adzuna(),
                "h1bapi", properties.dailyCap().h1bapi());
        return new SalaryRateLimiter(clock, sleeper, externalRequestLogRepository,
                properties.pacing().minDelay(), dailyCaps);
    }

    /**
     * A separate client from {@code linkedInHttpClient}: the salary APIs are ordinary JSON
     * endpoints that use redirects normally, unlike the LinkedIn guest scrape.
     */
    @Bean
    public HttpClient salaryHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
