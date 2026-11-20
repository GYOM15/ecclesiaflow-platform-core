package com.ecclesiaflow.platform.webhooks.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The SQL of the adapters is checked against the DDL the library ships, without a database. */
class InboxDdlTest {

    static final String RESOURCE = "/db/webhooks/inbound_notification.sql";

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Set<String> SQL_WORDS = Set.of(
            "insert", "into", "values", "select", "from", "where", "and", "or", "update", "set", "returning", "with",
            "as", "materialized", "order", "by", "limit", "for", "skip", "locked", "in", "null", "count", "min",
            "is", "not", "of", "on", "conflict", "do", "nothing", "join", "due", "candidate", "w", "n", "id",
            "inbound_notification", "inbound_notification_work");

    static String ddl() throws IOException {
        try (InputStream in = InboxDdlTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> columnsOf(String table) throws IOException {
        Matcher body = Pattern.compile("CREATE TABLE " + table + " \\((.*?)\\n\\);", Pattern.DOTALL).matcher(ddl());
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
    @DisplayName("keeps the identifiers and a digest of each notification, never its body")
    void keepsNoBody() throws IOException {
        assertThat(columnsOf("inbound_notification")).containsExactly("id", "provider", "account", "event_id",
                "event_type", "live", "object_ref", "api_version", "payload_sha256", "occurred_at", "received_at");
        assertThat(ddl()).contains("UNIQUE (provider, account, event_id)")
                .contains("BEFORE UPDATE OR DELETE ON inbound_notification");
    }

    @Test
    @DisplayName("every statement of the adapters names only columns the shipped tables have")
    void statementsMatchTheTables() throws IOException {
        Set<String> columns = new LinkedHashSet<>(columnsOf("inbound_notification"));
        columns.addAll(columnsOf("inbound_notification_work"));

        List<String> statements = List.of(JdbcNotificationInbox.INSERT_NOTIFICATION_SQL,
                JdbcNotificationInbox.INSERT_WORK_SQL, JdbcNotificationInbox.REPLAY_PARKED_SQL,
                JdbcNotificationInbox.REPLAY_ONE_SQL, JdbcInboxRepository.CLAIM_SQL, JdbcInboxRepository.MARK_DONE_SQL,
                JdbcInboxRepository.RESCHEDULE_SQL, JdbcInboxRepository.PARK_SQL,
                JdbcInboxRepository.COUNT_BY_STATUS_SQL, JdbcInboxRepository.OLDEST_PENDING_SQL);

        assertThat(statements).allSatisfy(sql -> assertThat(columns).as(sql).containsAll(identifiersIn(sql)));
    }

    @Test
    @DisplayName("the operator's replay statement in the DDL's comments matches what replay does")
    void documentedReplayMatches() throws IOException {
        String documented = ddl().lines()
                .filter(line -> line.startsWith("--"))
                .map(line -> line.substring(2).strip())
                .reduce("", (left, right) -> left + " " + right);

        assertThat(documented).contains("WHERE status IN ('PARKED', 'UNMATCHED') AND notification_id = <id>;");
        assertThat(identifiersIn(JdbcNotificationInbox.REPLAY_ONE_SQL)).containsExactlyInAnyOrder("status",
                "attempts", "next_attempt_at", "outcome", "last_error", "done_at", "parked_at", "notification_id");
    }
}
