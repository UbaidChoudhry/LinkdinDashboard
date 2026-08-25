package com.ubaid.jobdash.sweep;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SweepShardsProperties} for binding. Mirrors T4's
 * {@code SweepPropertiesConfiguration} pattern: an explicit {@code @EnableConfigurationProperties}
 * registration so this package stays self-contained and doesn't require editing the
 * application's main class.
 */
@Configuration
@EnableConfigurationProperties(SweepShardsProperties.class)
public class SweepConfiguration {
}
