package com.ecclesiaflow.platform.journal.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The SQL of the adapters, checked against the DDL the library ships, without a database. */
class JournalDdlTest {

    static final String RESOURCE = "/db/journal/platform_journal.sql";

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Set<String> SQL_WORDS = Set.of("select", "insert", "into", "values", "from", "where", "and",
            "order", "by", "desc", "limit", "returning", "as", "cast", "text", "jsonb", "extract", "epoch", "bigint",
            "encode", "hex", "platform_journal", "journal_line", "current_head", "purge_expired", "occurred_micros");

    static String ddl() throws IOException {
        try (InputStream in = JournalDdlTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The file as a module's Flyway line runs it, with the application role in its placeholder. */
    static String ddlFor(String writer) throws IOException {
        return ddl().replace("${journal_writer}", writer);
    }

    private static Set<String> columnsOf(String table) throws IOException {
        Matcher body = Pattern.compile("CREATE TABLE platform_journal\\." + table + " \\((.*?)\\n\\);",
                Pattern.DOTALL).matcher(ddl());
        assertThat(body.find()).as("CREATE TABLE " + table).isTrue();
        Set<String> columns = new LinkedHashSet<>();
        Matcher line = Pattern.compile("^\\s+([a-z_0-9]+)\\s+[a-z]", Pattern.MULTILINE).matcher(body.group(1));
        while (line.find()) {
            if (!line.group(1).equals("constraint")) {
                columns.add(line.group(1));
            }
        }
        return columns;
    }

    private static Set<String> identifiersIn(String sql) {
        String bare = sql.replaceAll("'[^']*'", " ").toLowerCase(Locale.ROOT);
        Set<String> identifiers = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(bare);
        while (matcher.find()) {
            if (!SQL_WORDS.contains(matcher.group())) {
                identifiers.add(matcher.group());
            }
        }
        return identifiers;
    }

    @Test
    @DisplayName("a line holds ids, codes and the hashes; the head its two positions")
    void columns() throws IOException {
        assertThat(columnsOf("journal_line")).containsExactly("seq", "occurred_at", "actor_subject", "actor_roles",
                "actor_session", "request_id", "gesture", "outcome", "refusal_code", "target_kind", "target_ref",
                "case_ref", "reason_code", "details", "prev_hash", "row_hash");
        assertThat(columnsOf("journal_head")).containsExactly("singleton", "last_seq", "last_hash", "start_seq",
                "start_hash", "updated_at");
    }

    @Test
    @DisplayName("every statement of the adapters names only columns the shipped tables have")
    void statementsMatchTheTables() throws IOException {
        Set<String> columns = new LinkedHashSet<>(columnsOf("journal_line"));
        columns.addAll(columnsOf("journal_head"));

        List<String> statements = List.of(JdbcPlatformJournal.INSERT_SQL, JdbcJournalReader.SELECT_SQL,
                JdbcJournalRepository.HEAD_SQL, JdbcJournalRepository.LINES_SQL, JdbcJournalRepository.ROW_HASH_SQL,
                JdbcJournalRepository.PURGE_SQL);

        assertThat(statements).allSatisfy(sql -> assertThat(columns).as(sql).containsAll(identifiersIn(sql)));
    }

    @Test
    @DisplayName("UPDATE, DELETE and TRUNCATE are refused on the lines and on the head")
    void appendOnly() throws IOException {
        assertThat(ddl())
                .contains("BEFORE UPDATE ON platform_journal.journal_line")
                .contains("BEFORE DELETE ON platform_journal.journal_line")
                .contains("BEFORE TRUNCATE ON platform_journal.journal_line")
                .contains("BEFORE UPDATE ON platform_journal.journal_head")
                .contains("BEFORE DELETE ON platform_journal.journal_head")
                .contains("BEFORE TRUNCATE ON platform_journal.journal_head");
    }

    @Test
    @DisplayName("the application role gets INSERT and SELECT on the lines and the two functions, nothing on the head")
    void grants() throws IOException {
        List<String> grants = ddlFor("members_app").lines().filter(line -> line.startsWith("GRANT")).toList();

        assertThat(grants).containsExactly(
                "GRANT USAGE ON SCHEMA platform_journal TO members_app;",
                "GRANT SELECT, INSERT ON platform_journal.journal_line TO members_app;",
                "GRANT EXECUTE ON FUNCTION platform_journal.details_are_flat(jsonb) TO members_app;",
                "GRANT EXECUTE ON FUNCTION platform_journal.current_head() TO members_app;",
                "GRANT EXECUTE ON FUNCTION platform_journal.purge_expired() TO members_app;");
    }

    @Test
    @DisplayName("the functions that read or move the head run as the owner, on a fixed search path")
    void definerFunctions() throws IOException {
        Matcher definers = Pattern.compile("CREATE FUNCTION (platform_journal\\.[a-z_]+)\\([^)]*\\)[^$]*?SECURITY "
                + "DEFINER SET search_path = pg_catalog, pg_temp", Pattern.DOTALL).matcher(ddl());
        Set<String> names = new LinkedHashSet<>();
        while (definers.find()) {
            names.add(definers.group(1));
        }

        assertThat(names).containsExactly("platform_journal.guard_head", "platform_journal.chain_line",
                "platform_journal.advance_head", "platform_journal.current_head", "platform_journal.purge_expired");
    }

    @Test
    @DisplayName("the retention lives in the SQL, 24 months, and the purge takes no term from its caller")
    void retention() throws IOException {
        assertThat(ddl()).contains("CREATE FUNCTION platform_journal.purge_expired() RETURNS bigint")
                .contains("now() - interval '24 months'");
    }

    @Test
    @DisplayName("the canonical form names every column but the hashes, in the order JournalCanonicalForm renders")
    void canonicalOrder() throws IOException {
        String ddl = ddl();
        String body = ddl.substring(ddl.indexOf("CREATE FUNCTION platform_journal.canonical"),
                ddl.indexOf("$$;", ddl.indexOf("CREATE FUNCTION platform_journal.canonical")));
        Matcher fields = Pattern.compile("line\\.([a-z_]+)").matcher(body);
        List<String> order = new ArrayList<>();
        while (fields.find()) {
            order.add(fields.group(1));
        }

        assertThat(order).containsExactly("seq", "occurred_at", "actor_subject", "actor_roles", "actor_session",
                "request_id", "gesture", "outcome", "refusal_code", "target_kind", "target_ref", "case_ref",
                "reason_code", "details");
    }
}
