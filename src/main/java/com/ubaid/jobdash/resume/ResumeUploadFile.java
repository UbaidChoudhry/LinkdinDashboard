package com.ubaid.jobdash.resume;

import com.ubaid.jobdash.domain.Resume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * The file handed to a browser's file input when Claude attaches a resume to an application.
 * <p>
 * {@link ResumeService} stores an upload under a name derived from its row id ({@code 3.pdf}),
 * on purpose - the original filename is user-controlled and could carry path separators. But the
 * name of the file you attach is the name the employer sees in their ATS, and a recruiter opening
 * "3.pdf" is not the impression anyone wants. So the apply flow attaches a <b>copy</b> of the
 * stored file carrying the original upload name ({@code Choudhry_Resume V19.pdf}), kept under
 * {@code <storage dir>/named/<id>/}, refreshed whenever the stored file is newer or a different
 * size. The id segment keeps two resumes with the same original name apart.
 * <p>
 * Never throws: if the copy cannot be made (unwritable directory, stored file missing) the stored
 * path is returned unchanged, so an apply batch degrades to the old id-named attachment rather
 * than failing.
 */
public final class ResumeUploadFile {

    private static final Logger log = LoggerFactory.getLogger(ResumeUploadFile.class);

    /** Directory, under the storage dir, holding the original-name copies. */
    static final String NAMED_DIR = "named";

    private ResumeUploadFile() {
    }

    /** Absolute path of the file to attach for {@code resume} - the original-name copy when possible. */
    public static Path forUpload(Resume resume) {
        Path stored = Path.of(resume.storedPath()).toAbsolutePath();
        Path dir = namedDir(resume);
        if (dir == null || !Files.isRegularFile(stored)) {
            return stored;
        }
        Path target = dir.resolve(safeFilename(resume.originalFilename(), stored));
        try {
            Files.createDirectories(dir);
            if (!Files.isRegularFile(target)
                    || Files.size(target) != Files.size(stored)
                    || Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(stored)) < 0) {
                Files.copy(stored, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            log.warn("could not create original-name copy of resume {} at {}: {} - attaching {} instead",
                    resume.id(), target, e.toString(), stored.getFileName());
            return stored;
        }
    }

    /** The per-resume directory holding its original-name copy, or null when the stored path has no parent. */
    public static Path namedDir(Resume resume) {
        Path parent = Path.of(resume.storedPath()).toAbsolutePath().getParent();
        return parent == null ? null : parent.resolve(NAMED_DIR).resolve(String.valueOf(resume.id()));
    }

    /**
     * The original filename reduced to a single safe path segment: path separators and control
     * characters become {@code _}, and a blank result falls back to {@code resume.<ext>} using the
     * stored file's extension. Spaces are kept - "Choudhry_Resume V19.pdf" should stay readable.
     */
    static String safeFilename(String originalFilename, Path stored) {
        String name = originalFilename == null ? "" : originalFilename.trim();
        name = name.replaceAll("[/\\\\\\p{Cntrl}]", "_");
        // A name that is only dots or underscores (".." after the replacement above, say) is not a usable file name.
        if (name.isEmpty() || name.chars().allMatch(c -> c == '.' || c == '_')) {
            String storedName = stored.getFileName() == null ? "" : stored.getFileName().toString();
            int dot = storedName.lastIndexOf('.');
            String ext = dot < 0 ? "" : storedName.substring(dot).toLowerCase(Locale.ROOT);
            return "resume" + ext;
        }
        return name;
    }
}
