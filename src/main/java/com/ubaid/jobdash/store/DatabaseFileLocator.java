package com.ubaid.jobdash.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves the on-disk SQLite file(s) backing {@code spring.datasource.url} and reports their
 * combined size.
 *
 * <p>In WAL mode, recently written pages live in a {@code -wal} side file until a checkpoint
 * folds them back into the main database file, and a {@code -shm} shared-memory index file
 * accompanies it. All three must be summed for the reported size to reflect what's actually on
 * disk rather than understating it right after a write.
 */
@Component
public class DatabaseFileLocator {

    private static final String JDBC_SQLITE_PREFIX = "jdbc:sqlite:";

    private final Path databaseFile;

    public DatabaseFileLocator(@Value("${spring.datasource.url}") String jdbcUrl) {
        if (!jdbcUrl.startsWith(JDBC_SQLITE_PREFIX)) {
            throw new IllegalStateException("Expected a jdbc:sqlite: datasource url, got: " + jdbcUrl);
        }
        String withoutPrefix = jdbcUrl.substring(JDBC_SQLITE_PREFIX.length());
        int queryStart = withoutPrefix.indexOf('?');
        String path = queryStart >= 0 ? withoutPrefix.substring(0, queryStart) : withoutPrefix;
        this.databaseFile = Path.of(path).toAbsolutePath().normalize();
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /** Combined size in bytes of the main database file plus its WAL and SHM side files. */
    public long sizeBytes() {
        long total = 0;
        for (Path p : List.of(databaseFile, sibling("-wal"), sibling("-shm"))) {
            total += sizeOrZero(p);
        }
        return total;
    }

    private Path sibling(String suffix) {
        return databaseFile.resolveSibling(databaseFile.getFileName() + suffix);
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            // Doesn't exist yet (fresh database) or was already checkpointed away - contributes 0.
            return 0;
        }
    }
}
