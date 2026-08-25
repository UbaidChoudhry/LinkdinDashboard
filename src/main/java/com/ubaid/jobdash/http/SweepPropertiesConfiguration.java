package com.ubaid.jobdash.http;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SweepProperties} for binding. Kept as an explicit
 * {@code @EnableConfigurationProperties} registration (rather than {@code @Component} on the
 * properties record, or {@code @ConfigurationPropertiesScan} on the application class) so this
 * package is fully self-contained and doesn't require editing the application's main class.
 */
@Configuration
@EnableConfigurationProperties(SweepProperties.class)
public class SweepPropertiesConfiguration {
}
