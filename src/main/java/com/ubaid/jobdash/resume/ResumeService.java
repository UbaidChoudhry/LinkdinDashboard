package com.ubaid.jobdash.resume;

import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.store.AiMatchRepository;
import com.ubaid.jobdash.store.ResumeRepository;
import com.ubaid.jobdash.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * Ties together upload validation, text extraction, on-disk storage, and the {@code resume}
 * table: the whole pipeline behind {@code POST /api/resumes}. The AI-match comparison against
 * job descriptions is built by a later task and does not live here.
 */
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    private final ResumeRepository resumeRepository;
    private final AiMatchRepository aiMatchRepository;
    private final ResumeTextExtractor textExtractor;
    private final Clock clock;
    private final Path storageDir;

    // @Autowired is required, not decorative: this class has two constructors, so Spring has no
    // single unambiguous candidate and refuses to build the bean without being told which one.
    @Autowired
    public ResumeService(ResumeRepository resumeRepository, AiMatchRepository aiMatchRepository,
                          ResumeTextExtractor textExtractor, Clock clock) {
        this(resumeRepository, aiMatchRepository, textExtractor, clock, Path.of("data/resumes"));
    }

    /** Test seam: lets tests point storage at a {@code @TempDir} instead of the real {@code data/}. */
    ResumeService(ResumeRepository resumeRepository, AiMatchRepository aiMatchRepository,
                   ResumeTextExtractor textExtractor, Clock clock, Path storageDir) {
        this.resumeRepository = resumeRepository;
        this.aiMatchRepository = aiMatchRepository;
        this.textExtractor = textExtractor;
        this.clock = clock;
        this.storageDir = storageDir;
    }

    /**
     * Validates and stores an uploaded resume: extracts its text, writes the original file under
     * the storage directory (created if absent - the SQLite driver won't create directories for
     * us, and neither does anything else here), and inserts the row. The very first resume ever
     * uploaded automatically becomes the default.
     */
    public Resume upload(MultipartFile file, String requestedName) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No file was uploaded.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "That file is too large (" + file.getSize() + " bytes). Resumes are capped at 5 MB.");
        }

        String originalFilename = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        String contentType = file.getContentType() == null ? "" : file.getContentType();

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file. Please try again.");
        }

        String contentText = textExtractor.extract(bytes, originalFilename, contentType);
        String name = (requestedName == null || requestedName.isBlank())
                ? stripExtension(originalFilename)
                : requestedName;

        boolean isFirst = resumeRepository.list().isEmpty();

        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create resume storage directory " + storageDir, e);
        }

        Resume toInsert = new Resume(0, name, originalFilename, normalizedContentType(contentType, originalFilename),
                "", contentText, contentText.length(), isFirst, clock.instant());
        long id = resumeRepository.insert(toInsert);

        // Store under a name derived from the generated id, never the user-supplied filename -
        // it is attacker-controlled in principle and could contain path separators.
        String extension = extension(originalFilename);
        Path storedPath = storageDir.resolve(id + (extension.isEmpty() ? "" : "." + extension));
        try {
            Files.write(storedPath, bytes);
        } catch (IOException e) {
            resumeRepository.delete(id);
            throw new UncheckedIOException("Could not write resume file " + storedPath, e);
        }
        resumeRepository.updateStoredPath(id, storedPath.toString());

        return resumeRepository.findById(id).orElseThrow();
    }

    /** All resumes, newest first, without the extracted text body. */
    public List<ResumeRepository.ResumeSummary> list() {
        return resumeRepository.list();
    }

    /** The extracted text for one resume - what the UI shows as "what Claude will see". */
    public String text(long id) {
        return resumeRepository.findById(id)
                .map(Resume::contentText)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No resume with id " + id + "."));
    }

    /** Makes the given resume the default used for AI matching. */
    public void setDefault(long id) {
        resumeRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No resume with id " + id + "."));
        resumeRepository.setDefault(id);
    }

    /**
     * Deletes a resume's row and its file on disk. If it was the default, promotes the newest
     * remaining resume (if any) to default.
     */
    public void delete(long id) {
        Resume resume = resumeRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No resume with id " + id + "."));

        // Delete the cached verdicts EXPLICITLY. `ai_match.resume_id` declares
        // `on delete cascade`, but SQLite ships with foreign-key enforcement OFF
        // (`pragma foreign_keys` is 0 unless switched on per connection), so that clause does
        // nothing here - the delete succeeds and silently leaves orphaned rows behind. Verified
        // against the real database. This is the classic "bug that looks like success" from
        // HANDOFF.md §1, so do not replace this call with a reliance on the cascade.
        aiMatchRepository.deleteByResume(id);
        resumeRepository.delete(id);
        try {
            Files.deleteIfExists(Path.of(resume.storedPath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete resume file " + resume.storedPath(), e);
        }
        deleteNamedCopy(resume);

        if (resume.isDefault()) {
            resumeRepository.list().stream()
                    .findFirst()
                    .ifPresent(newest -> resumeRepository.setDefault(newest.id()));
        }
    }

    /**
     * Removes the original-name copy {@link ResumeUploadFile} makes for the apply flow (the file
     * and its per-resume directory). Best-effort: a leftover copy is harmless, so a failure here
     * is logged rather than failing the delete that already succeeded.
     */
    private static void deleteNamedCopy(Resume resume) {
        Path dir = ResumeUploadFile.namedDir(resume);
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("could not remove original-name resume copy under {}: {}", dir, e.toString());
        }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizedContentType(String contentType, String originalFilename) {
        String lowerName = originalFilename.toLowerCase(Locale.ROOT);
        if ("application/pdf".equalsIgnoreCase(contentType) || lowerName.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lowerName.endsWith(".md")) {
            return "text/markdown";
        }
        return "text/plain";
    }
}
