package com.ubaid.jobdash.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AiProperties}, mirroring {@code SalaryConfiguration}'s explicit
 * {@code @EnableConfigurationProperties} pattern so this package self-registers without touching
 * the application's main class.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
}
