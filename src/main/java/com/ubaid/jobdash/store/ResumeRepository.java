package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@code resume} table: uploaded resumes, their extracted text, and which
 * one (if any) is the default used for AI matching. See {@link com.ubaid.jobdash.resume.ResumeService}
 * for the upload/delete/default-promotion logic built on top of this.
 */
@Repository
public class ResumeRepository {

    private final JdbcClient client;

    public ResumeRepository(JdbcClient client) {
        this.client = client;
    }

    /** Inserts a new resume row and returns its generated id. */
    public long insert(Resume r) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        client.sql("""
                        insert into resume
                            (name, original_filename, content_type, stored_path, content_text,
                             char_count, is_default, uploaded_at)
                        values
                            (:name, :originalFilename, :contentType, :storedPath, :contentText,
                             :charCount, :isDefault, :uploadedAt)
                        """)
                .param("name", r.name())
                .param("originalFilename", r.originalFilename())
                .param("contentType", r.contentType())
                .param("storedPath", r.storedPath())
                .param("contentText", r.contentText())
                .param("charCount", r.charCount())
                .param("isDefault", r.isDefault() ? 1 : 0)
                .param("uploadedAt", Timestamps.toText(r.uploadedAt()))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** Looks up a resume by id, including its full extracted text. */
    public Optional<Resume> findById(long id) {
        return client.sql("select * from resume where id = :id")
                .param("id", id)
                .query(ResumeRepository::mapRow)
                .optional();
    }

    /** All resumes, newest first, without the (potentially large) extracted text body. */
    public List<ResumeSummary> list() {
        return client.sql("""
                        select id, name, original_filename, content_type, char_count, is_default, uploaded_at
                        from resume
                        order by uploaded_at desc, id desc
                        """)
                .query(ResumeRepository::mapSummaryRow)
                .list();
    }

    /**
     * Updates just the {@code stored_path} column. Needed because the on-disk filename is
     * derived from the row's generated id (see ResumeService#upload), so it can only be known
     * after the initial insert.
     */
    public void updateStoredPath(long id, String storedPath) {
        client.sql("update resume set stored_path = :storedPath where id = :id")
                .param("storedPath", storedPath)
                .param("id", id)
                .update();
    }

    /** Deletes a resume row. Does not touch the file on disk - see ResumeService. */
    public void delete(long id) {
        client.sql("delete from resume where id = :id").param("id", id).update();
    }

    /**
     * Makes {@code id} the default resume, clearing the flag on every other row first, all in
     * one transaction - so at most one row is ever the default.
     */
    @Transactional
    public void setDefault(long id) {
        client.sql("update resume set is_default = 0").update();
        client.sql("update resume set is_default = 1 where id = :id").param("id", id).update();
    }

    /** The current default resume, if one has been set. */
    public Optional<Resume> findDefault() {
        return client.sql("select * from resume where is_default = 1")
                .query(ResumeRepository::mapRow)
                .optional();
    }

    /** The narrow projection used by {@link #list()} - deliberately excludes {@code content_text}. */
    public record ResumeSummary(
            long id,
            String name,
            String originalFilename,
            String contentType,
            int charCount,
            boolean isDefault,
            Instant uploadedAt
    ) {
    }

    static Resume mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Resume(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getString("stored_path"),
                rs.getString("content_text"),
                rs.getInt("char_count"),
                rs.getInt("is_default") != 0,
                Timestamps.parse(rs.getString("uploaded_at"))
        );
    }

    static ResumeSummary mapSummaryRow(ResultSet rs, int rowNum) throws SQLException {
        return new ResumeSummary(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getInt("char_count"),
                rs.getInt("is_default") != 0,
                Timestamps.parse(rs.getString("uploaded_at"))
        );
    }
}
