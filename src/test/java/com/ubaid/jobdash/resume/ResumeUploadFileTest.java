package com.ubaid.jobdash.resume;

import com.ubaid.jobdash.domain.Resume;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeUploadFileTest {

    @TempDir
    Path storageDir;

    private Resume resume(long id, String originalFilename, Path stored) {
        return new Resume(id, "r", originalFilename, "application/pdf", stored.toString(), "text", 4, true,
                Instant.now());
    }

    @Test
    void attachesACopyCarryingTheOriginalUploadName() throws IOException {
        Path stored = storageDir.resolve("3.pdf");
        Files.write(stored, new byte[] {1, 2, 3});

        Path upload = ResumeUploadFile.forUpload(resume(3, "Choudhry_Resume V19.pdf", stored));

        assertThat(upload.getFileName().toString()).isEqualTo("Choudhry_Resume V19.pdf");
        assertThat(upload.getParent()).isEqualTo(storageDir.resolve("named").resolve("3").toAbsolutePath());
        assertThat(Files.readAllBytes(upload)).containsExactly(1, 2, 3);
        assertThat(upload.isAbsolute()).isTrue();
    }

    @Test
    void refreshesTheCopyWhenTheStoredFileChanges() throws IOException {
        Path stored = storageDir.resolve("3.pdf");
        Files.write(stored, new byte[] {1, 2, 3});
        Resume r = resume(3, "Resume.pdf", stored);
        Path first = ResumeUploadFile.forUpload(r);
        Files.write(stored, new byte[] {9, 9, 9, 9});

        Path second = ResumeUploadFile.forUpload(r);

        assertThat(second).isEqualTo(first);
        assertThat(Files.readAllBytes(second)).containsExactly(9, 9, 9, 9);
    }

    @Test
    void fallsBackToTheStoredPathWhenTheStoredFileIsMissing() {
        Path stored = storageDir.resolve("7.pdf");

        assertThat(ResumeUploadFile.forUpload(resume(7, "Resume.pdf", stored))).isEqualTo(stored.toAbsolutePath());
    }

    @Test
    void sanitizesAHostileOriginalName() throws IOException {
        Path stored = storageDir.resolve("3.pdf");
        Files.write(stored, new byte[] {1});

        assertThat(ResumeUploadFile.safeFilename("../../etc/passwd", stored)).isEqualTo(".._.._etc_passwd");
        assertThat(ResumeUploadFile.safeFilename("..", stored)).isEqualTo("resume.pdf");
        assertThat(ResumeUploadFile.safeFilename("", stored)).isEqualTo("resume.pdf");
        assertThat(ResumeUploadFile.safeFilename(null, stored)).isEqualTo("resume.pdf");
        assertThat(ResumeUploadFile.safeFilename("Choudhry_Resume V19.pdf", stored)).isEqualTo("Choudhry_Resume V19.pdf");
    }
}
