package com.ubaid.jobdash.apply;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ApplyProperties}, mirroring
 * {@code ai.AiConfiguration}'s explicit {@code @EnableConfigurationProperties} pattern so this
 * package self-registers without touching the application's main class.
 */
@Configuration
@EnableConfigurationProperties(ApplyProperties.class)
public class ApplyConfiguration {
}
