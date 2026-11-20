package com.ecclesiaflow.platform.journal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Java mirror of {@code platform_journal.canonical()}, frozen: the bytes a line's hash covers. The verifier recomputes
 * here rather than trusting a function the schema's owner could redefine. Every column but the hashes, in a fixed
 * order; text escaped, backslash first, so no value slides into the next field; instants in epoch microseconds, the
 * column's resolution, since a formatted instant depends on a session's settings.
 */
public final class JournalCanonicalForm {

    static final String SEPARATOR = "|";

    private static final String ESCAPE = "\\";
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final int NANOS_PER_MICRO = 1_000;

    private JournalCanonicalForm() {
    }

    public static String render(JournalLine line) {
        StringJoiner canonical = new StringJoiner(SEPARATOR);
        canonical.add(Long.toString(line.seq()));
        canonical.add(micros(line.occurredAt()));
        canonical.add(escape(line.actorSubject()));
        canonical.add(escape(roles(line.actorRoles())));
        canonical.add(escape(line.actorSession()));
        canonical.add(escape(line.requestId()));
        canonical.add(escape(line.gesture()));
        canonical.add(escape(line.outcome()));
        canonical.add(escape(line.refusalCode()));
        canonical.add(escape(line.targetKind()));
        canonical.add(escape(line.targetRef()));
        canonical.add(escape(line.caseRef()));
        canonical.add(escape(line.reasonCode()));
        canonical.add(escape(details(line.details())));
        return canonical.toString();
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(ESCAPE, ESCAPE + ESCAPE).replace(SEPARATOR, ESCAPE + SEPARATOR);
    }

    static String micros(Instant instant) {
        return Long.toString(Math.multiplyExact(instant.getEpochSecond(), MICROS_PER_SECOND)
                + instant.getNano() / NANOS_PER_MICRO);
    }

    // In the order stored, which the writer sorts.
    private static String roles(List<String> roles) {
        StringJoiner joined = new StringJoiner(SEPARATOR);
        roles.forEach(role -> joined.add(escape(role)));
        return joined.toString();
    }

    // Byte order, as the function's COLLATE "C": the writer admits ASCII keys only, where UTF-16 order is the same.
    private static String details(Map<String, String> details) {
        StringJoiner pairs = new StringJoiner(SEPARATOR);
        new TreeMap<>(details).forEach((key, value) -> {
            pairs.add(escape(key));
            pairs.add(escape(value));
        });
        return pairs.toString();
    }
}
