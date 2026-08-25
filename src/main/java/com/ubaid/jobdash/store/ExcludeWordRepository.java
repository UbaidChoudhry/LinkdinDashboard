package com.ubaid.jobdash.store;

import com.ubaid.jobdash.domain.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for the {@code exclude_word} table. The {@code word} column is
 * {@code collate nocase}, so all lookups/deletes are case-insensitive at the database level.
 */
@Repository
public class ExcludeWordRepository {

    private final JdbcClient client;

    public ExcludeWordRepository(JdbcClient client) {
        this.client = client;
    }

    public List<String> list() {
        return client.sql("select word from exclude_word order by word")
                .query(String.class)
                .list();
    }

    public int add(String word, Instant addedAt) {
        return client.sql("insert into exclude_word (word, added_at) values (:word, :addedAt) on conflict(word) do nothing")
                .param("word", word)
                .param("addedAt", Timestamps.toText(addedAt))
                .update();
    }

    public int delete(String word) {
        return client.sql("delete from exclude_word where word = :word")
                .param("word", word)
                .update();
    }
}
