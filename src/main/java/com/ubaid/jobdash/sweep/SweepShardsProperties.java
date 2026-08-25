package com.ubaid.jobdash.sweep;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds the optional {@code sweep.shards} list — the ordered metro shard list used when a run
 * opts into metro sharding. Kept as its own {@code @ConfigurationProperties} class (rather than
 * adding a field to T4's {@code SweepProperties}) so this task doesn't need to modify a file
 * T4 owns; Spring Boot happily binds multiple {@code @ConfigurationProperties} types to the same
 * {@code sweep} prefix as long as each only claims the keys it declares.
 * <p>
 * Only "New York City Metropolitan Area" is empirically verified to return results; the rest of
 * the default list is unverified (task 9 checks the others live). Sharding is always opt-in per
 * run — this list is never consulted unless a run explicitly enables it.
 */
@ConfigurationProperties(prefix = "sweep")
public record SweepShardsProperties(List<String> shards) {

    public static final List<String> DEFAULT_SHARDS = List.of(
            "New York City Metropolitan Area",
            "Los Angeles Metropolitan Area",
            "Seattle, Washington, United States",
            "Austin, Texas Metropolitan Area"
    );

    /** The configured shard list, falling back to {@link #DEFAULT_SHARDS} if unset/empty. */
    public List<String> shardsOrDefault() {
        return (shards == null || shards.isEmpty()) ? DEFAULT_SHARDS : shards;
    }
}
