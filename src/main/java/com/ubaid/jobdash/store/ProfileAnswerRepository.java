package com.ubaid.jobdash.store;

import com.ubaid.jobdash.apply.QuestionKey;
import com.ubaid.jobdash.domain.ProfileAnswer;
import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@code profile_answer} table: the question/answer rows that replace the old
 * free-text {@code applicant_profile.extra_answers} field. See
 * {@code src/main/resources/db/migration/V14__profile_answers.sql} and
 * {@code apply.ApplyOrchestrator}/{@code apply.ApplyPromptBuilder} for how these feed the apply
 * prompt and get populated from a run's {@code unanswered} list.
 */
@Repository
public class ProfileAnswerRepository {

    private final JdbcClient client;

    public ProfileAnswerRepository(JdbcClient client) {
        this.client = client;
    }

    /** Every row, pending first, then alphabetically by question - the order the UI list and the prompt use. */
    public List<ProfileAnswer> list() {
        return client.sql("""
                        select * from profile_answer
                        order by case status when 'pending' then 0 else 1 end, question collate nocase asc
                        """)
                .query(ProfileAnswerRepository::mapRow)
                .list();
    }

    /** Looks up a row by its normalized question key. */
    public Optional<ProfileAnswer> findByKey(String key) {
        return client.sql("select * from profile_answer where question_key = :key")
                .param("key", key)
                .query(ProfileAnswerRepository::mapRow)
                .optional();
    }

    /** Looks up a row by id. */
    public Optional<ProfileAnswer> findById(long id) {
        return client.sql("select * from profile_answer where id = :id")
                .param("id", id)
                .query(ProfileAnswerRepository::mapRow)
                .optional();
    }

    /** Inserts a new row and returns its generated id. {@code status} is the caller's choice (answered/pending). */
    public long insert(String question, String answer, String status, Long jobId, String company, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        client.sql("""
                        insert into profile_answer
                            (question, question_key, answer, status, asked_count, last_job_id, last_company,
                             created_at, updated_at)
                        values
                            (:question, :questionKey, :answer, :status, 0, :jobId, :company, :now, :now)
                        """)
                .param("question", question)
                .param("questionKey", QuestionKey.normalize(question))
                .param("answer", answer)
                .param("status", status)
                .param("jobId", jobId)
                .param("company", company == null ? "" : company)
                .param("now", Timestamps.toText(now))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** Sets a row's answer text; status becomes {@code answered} unless the new answer is blank ({@code pending}). */
    public void setAnswer(long id, String answer, Instant now) {
        String status = (answer == null || answer.isBlank()) ? "pending" : "answered";
        client.sql("update profile_answer set answer = :answer, status = :status, updated_at = :now where id = :id")
                .param("answer", answer == null ? "" : answer)
                .param("status", status)
                .param("now", Timestamps.toText(now))
                .param("id", id)
                .update();
    }

    /** Deletes a row. A no-op if the id doesn't exist. */
    public void delete(long id) {
        client.sql("delete from profile_answer where id = :id").param("id", id).update();
    }

    /**
     * Records that a form question could not be answered during an apply run: inserts a new
     * {@code pending} row keyed by the question's normalized text, or - if a row for this question
     * already exists - bumps its {@code asked_count}/{@code last_job_id}/{@code last_company}/
     * {@code updated_at} without touching its answer or status. An already-{@code answered} row is
     * never flipped back to {@code pending} just because Claude asked about it again; the point of
     * this table is that once the user answers a question, it stays answered.
     */
    public Recorded recordUnanswered(String question, long jobId, String company, Instant now) {
        String key = QuestionKey.normalize(question);
        Optional<ProfileAnswer> existing = findByKey(key);
        if (existing.isEmpty()) {
            long id = insert(question, "", "pending", jobId, company, now);
            return new Recorded(findById(id).orElseThrow(), true);
        }
        ProfileAnswer row = existing.get();
        client.sql("""
                        update profile_answer
                        set asked_count = asked_count + 1, last_job_id = :jobId, last_company = :company,
                            updated_at = :now
                        where id = :id
                        """)
                .param("jobId", jobId)
                .param("company", company == null ? "" : company)
                .param("now", Timestamps.toText(now))
                .param("id", row.id())
                .update();
        return new Recorded(findById(row.id()).orElseThrow(), false);
    }

    /** The outcome of {@link #recordUnanswered}: the row as it now stands, and whether it was newly created. */
    public record Recorded(ProfileAnswer row, boolean created) {
    }

    static ProfileAnswer mapRow(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id");
        String question = rs.getString("question");
        String answer = rs.getString("answer");
        String status = rs.getString("status");
        int askedCount = rs.getInt("asked_count");
        long lastJobIdValue = rs.getLong("last_job_id");
        Long lastJobId = rs.wasNull() ? null : lastJobIdValue;
        String lastCompany = rs.getString("last_company");
        Instant createdAt = Timestamps.parse(rs.getString("created_at"));
        Instant updatedAt = Timestamps.parse(rs.getString("updated_at"));
        return new ProfileAnswer(id, question, answer, status, askedCount, lastJobId, lastCompany,
                createdAt, updatedAt);
    }
}
