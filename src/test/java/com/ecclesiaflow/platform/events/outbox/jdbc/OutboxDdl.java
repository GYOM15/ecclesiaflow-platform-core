package com.ecclesiaflow.platform.events.outbox.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the DDL the library ships for modules to copy, so the SQL in the adapters can be
 * checked against it without a database.
 */
final class OutboxDdl {

    static final String RESOURCE = "/db/outbox/outbox_event.sql";

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE outbox_event \\((.*?)\\n\\);", Pattern.DOTALL);
    private static final Pattern COLUMN_LINE = Pattern.compile("^\\s+([a-z_]+)\\s+[a-z]", Pattern.MULTILINE);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_]+");
    private static final Set<String> SQL_WORDS = Set.of(
            "insert", "into", "values", "select", "from", "where", "and", "or", "update", "set",
            "returning", "with", "as", "materialized", "order", "by", "limit", "for", "skip", "locked",
            "delete", "in", "cast", "jsonb", "text", "null", "count", "min", "now", "is", "not", "exists", "of",
            "due", "o", "candidate", "older", "outbox_event");

    private OutboxDdl() {
    }

    static String text() {
        try (InputStream in = OutboxDdl.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Column names declared by {@code CREATE TABLE outbox_event}. */
    static Set<String> columns() {
        Matcher table = CREATE_TABLE.matcher(text());
        if (!table.find()) {
            throw new IllegalStateException("no CREATE TABLE outbox_event in " + RESOURCE);
        }
        Set<String> columns = new LinkedHashSet<>();
        Matcher line = COLUMN_LINE.matcher(table.group(1));
        while (line.find()) {
            if (!line.group(1).equals("constraint")) {
                columns.add(line.group(1));
            }
        }
        return columns;
    }

    /**
     * The operator statement documented in the DDL's comments that starts with {@code prefix},
     * with its {@code <id>} placeholder as a JDBC parameter.
     */
    static String documentedStatement(String prefix) {
        String comments = text().lines()
                .filter(line -> line.startsWith("--"))
                .map(line -> line.substring(2).strip())
                .collect(Collectors.joining(" "));
        int start = comments.indexOf(prefix);
        int end = start < 0 ? -1 : comments.indexOf(';', start);
        if (end < 0) {
            throw new IllegalStateException("no documented statement starting with '" + prefix + "' in " + RESOURCE);
        }
        return comments.substring(start, end).replace("<id>", "?");
    }

    /** Lower-case identifiers of a statement that are neither SQL words nor quoted literals. */
    static Set<String> columnsIn(String sql) {
        String withoutLiterals = sql.replaceAll("'[^']*'", " ").toLowerCase(Locale.ROOT);
        Set<String> identifiers = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(withoutLiterals);
        while (matcher.find()) {
            if (!SQL_WORDS.contains(matcher.group())) {
                identifiers.add(matcher.group());
            }
        }
        return identifiers;
    }
}
