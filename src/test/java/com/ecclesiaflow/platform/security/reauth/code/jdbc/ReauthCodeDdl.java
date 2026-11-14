package com.ecclesiaflow.platform.security.reauth.code.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the DDL the library ships for modules to copy, so the adapter's SQL is checked against it without a database. */
final class ReauthCodeDdl {

    static final String RESOURCE = "/db/reauth/reauth_email_code.sql";

    private static final Pattern COLUMN_LINE = Pattern.compile("^\\s+([a-z_]+)\\s+[a-z]", Pattern.MULTILINE);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_]+");
    private static final Set<String> SQL_WORDS = Set.of(
            "select", "from", "where", "and", "or", "for", "update", "insert", "into", "values", "set", "delete",
            "count", "min", "as", "occurrences", "oldest", "pg_advisory_xact_lock", "reauth_email_code",
            "reauth_email_code_occurrence");

    private ReauthCodeDdl() {
    }

    static String text() {
        try (InputStream in = ReauthCodeDdl.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Set<String> columns(String table) {
        Matcher body = Pattern.compile("CREATE TABLE " + table + " \\((.*?)\\n\\);", Pattern.DOTALL).matcher(text());
        if (!body.find()) {
            throw new IllegalStateException("no CREATE TABLE " + table + " in " + RESOURCE);
        }
        Set<String> columns = new LinkedHashSet<>();
        Matcher line = COLUMN_LINE.matcher(body.group(1));
        while (line.find()) {
            if (!line.group(1).equals("constraint")) {
                columns.add(line.group(1));
            }
        }
        return columns;
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
