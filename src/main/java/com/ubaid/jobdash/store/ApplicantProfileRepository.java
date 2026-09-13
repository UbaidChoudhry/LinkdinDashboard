package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.ApplicantProfile;
import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Repository for the single-row {@code applicant_profile} table: the free-text answers used to
 * fill application-form fields the resume itself doesn't answer. Always id=1 - {@link #save}
 * is an upsert on that fixed id, so there is never more than one row.
 */
@Repository
public class ApplicantProfileRepository {

    private final JdbcClient client;

    public ApplicantProfileRepository(JdbcClient client) {
        this.client = client;
    }

    /** The applicant profile, if one has ever been saved. */
    public Optional<ApplicantProfile> find() {
        return client.sql("select * from applicant_profile where id = 1")
                .query(ApplicantProfileRepository::mapRow)
                .optional();
    }

    /** Inserts or replaces the single profile row (id is always 1). */
    public void save(ApplicantProfile profile) {
        client.sql("""
                        insert into applicant_profile
                            (id, full_name, email, phone, location, linkedin_url, portfolio_url,
                             work_authorization, requires_sponsorship, salary_expectation, extra_answers, updated_at)
                        values
                            (1, :fullName, :email, :phone, :location, :linkedinUrl, :portfolioUrl,
                             :workAuthorization, :requiresSponsorship, :salaryExpectation, :extraAnswers, :updatedAt)
                        on conflict(id) do update set
                            full_name = excluded.full_name,
                            email = excluded.email,
                            phone = excluded.phone,
                            location = excluded.location,
                            linkedin_url = excluded.linkedin_url,
                            portfolio_url = excluded.portfolio_url,
                            work_authorization = excluded.work_authorization,
                            requires_sponsorship = excluded.requires_sponsorship,
                            salary_expectation = excluded.salary_expectation,
                            extra_answers = excluded.extra_answers,
                            updated_at = excluded.updated_at
                        """)
                .param("fullName", profile.fullName())
                .param("email", profile.email())
                .param("phone", profile.phone())
                .param("location", profile.location())
                .param("linkedinUrl", profile.linkedinUrl())
                .param("portfolioUrl", profile.portfolioUrl())
                .param("workAuthorization", profile.workAuthorization())
                .param("requiresSponsorship", profile.requiresSponsorship() ? 1 : 0)
                .param("salaryExpectation", profile.salaryExpectation())
                .param("extraAnswers", profile.extraAnswers())
                .param("updatedAt", Timestamps.toText(profile.updatedAt()))
                .update();
    }

    static ApplicantProfile mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ApplicantProfile(
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("location"),
                rs.getString("linkedin_url"),
                rs.getString("portfolio_url"),
                rs.getString("work_authorization"),
                rs.getInt("requires_sponsorship") != 0,
                rs.getString("salary_expectation"),
                rs.getString("extra_answers"),
                Timestamps.parse(rs.getString("updated_at"))
        );
    }
}
