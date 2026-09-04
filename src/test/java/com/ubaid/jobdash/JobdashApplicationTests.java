package com.ubaid.jobdash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * Boots the whole application context - the one test that proves every bean in the app wires
 * together, and the first thing to fail when a new component is misconfigured.
 * <p>
 * It points the datasource at a temporary file, the same way {@link com.ubaid.jobdash.web.DataControllerTest}
 * and {@link com.ubaid.jobdash.web.JobDashApiTest} do. Without that override it inherits
 * {@code application.yml} and boots against the real {@code data/jobdash.db} - which both mutates
 * the developer's own data and makes the test fail for reasons that have nothing to do with the
 * code (a half-applied migration history in that file will fail Flyway's validation here even
 * though a fresh clone migrates perfectly).
 */
@SpringBootTest
class JobdashApplicationTests {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("jobdash-context-test.db"));
	}

	@Test
	void contextLoads() {
	}

}
