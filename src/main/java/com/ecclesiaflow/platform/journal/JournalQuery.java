package com.ecclesiaflow.platform.journal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * A page of the journal, newest first, filtered by period, operator, target and gesture; every filter is optional.
 *
 * @param from   inclusive
 * @param to     exclusive
 * @param cursor the {@code nextCursor} of the previous page, {@code null} for the first
 * @param limit  1 to 200
 */
public record JournalQuery(Instant from, Instant to, String actorSubject, String targetKind, String targetRef,
                           String gesture, String cursor, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private static final String CURSOR_PREFIX = "pj1:";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    // At most 18 digits: always a long.
    private static final Pattern POSITION = Pattern.compile("[1-9][0-9]{0,17}");

    public JournalQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw InvalidJournalQueryException.limit(MAX_LIMIT);
        }
        if (from != null && to != null && !to.isAfter(from)) {
            throw InvalidJournalQueryException.period();
        }
        if (targetRef != null && targetKind == null) {
            throw InvalidJournalQueryException.targetRefWithoutKind();
        }
        actorSubject = refFilter("actorSubject", actorSubject, 64);
        targetKind = codeFilter("targetKind", targetKind, 32);
        targetRef = refFilter("targetRef", targetRef, 128);
        gesture = codeFilter("gesture", gesture, 64);
        if (cursor != null) {
            beforeSeqOf(cursor);
        }
    }

    /** The newest page of the whole journal. */
    public static JournalQuery newest(int limit) {
        return new JournalQuery(null, null, null, null, null, null, null, limit);
    }

    /** The lines this page may hold lie strictly before this position. */
    public long beforeSeq() {
        return cursor == null ? Long.MAX_VALUE : beforeSeqOf(cursor);
    }

    /** The cursor of the page after one that ended on this position; opaque to every caller. */
    public static String cursorAfter(long lastSeqOfPage) {
        return ENCODER.encodeToString((CURSOR_PREFIX + lastSeqOfPage).getBytes(StandardCharsets.US_ASCII));
    }

    private static long beforeSeqOf(String cursor) {
        String decoded;
        try {
            decoded = new String(DECODER.decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException notBase64) {
            throw InvalidJournalQueryException.cursor();
        }
        if (!decoded.startsWith(CURSOR_PREFIX)
                || !POSITION.matcher(decoded.substring(CURSOR_PREFIX.length())).matches()) {
            throw InvalidJournalQueryException.cursor();
        }
        return Long.parseLong(decoded.substring(CURSOR_PREFIX.length()));
    }

    private static String refFilter(String field, String value, int max) {
        try {
            return JournalValues.ref(field, value, max, false);
        } catch (InvalidJournalEntryException refused) {
            throw InvalidJournalQueryException.filter(field);
        }
    }

    private static String codeFilter(String field, String value, int max) {
        try {
            return JournalValues.code(field, value, max, false);
        } catch (InvalidJournalEntryException refused) {
            throw InvalidJournalQueryException.filter(field);
        }
    }
}
