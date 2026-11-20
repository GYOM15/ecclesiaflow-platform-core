package com.ecclesiaflow.platform.journal;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The shapes a journal value may take: codes and ids, never text. Refusing everything else at the door is what keeps a
 * name, an address or a phone number out of lines kept for two years.
 */
final class JournalValues {

    static final int MAX_DETAILS = 16;

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    // ASCII only, so Java's key order is the database's COLLATE "C" order.
    private static final Pattern DETAIL_KEY = Pattern.compile("[a-z][a-zA-Z0-9_.-]*");
    // No id of the platform is a bare run of digits as long as a phone number; such a value is one.
    private static final Pattern PHONE_LIKE = Pattern.compile("[0-9]{7,15}");

    private static final int MAX_CODE = 64;
    private static final int MAX_DETAIL_KEY = 64;
    private static final int MAX_DETAIL_VALUE = 128;

    private JournalValues() {
    }

    /** An UPPER_SNAKE code; {@code null} stays {@code null} unless required. */
    static String code(String field, String value, int max, boolean required) {
        return checked(field, value, max, required, CODE);
    }

    static String code(String field, String value, boolean required) {
        return code(field, value, MAX_CODE, required);
    }

    /** An id: a UUID, a digest, a prefixed reference. */
    static String ref(String field, String value, int max, boolean required) {
        String checked = checked(field, value, max, required, REF);
        if (checked != null && PHONE_LIKE.matcher(checked).matches()) {
            throw InvalidJournalEntryException.malformed(field);
        }
        return checked;
    }

    static SortedMap<String, String> details(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return Collections.emptySortedMap();
        }
        if (details.size() > MAX_DETAILS) {
            throw InvalidJournalEntryException.tooManyDetails(MAX_DETAILS);
        }
        SortedMap<String, String> copy = new TreeMap<>();
        details.forEach((key, value) -> copy.put(checked("details", key, MAX_DETAIL_KEY, true, DETAIL_KEY),
                ref("details." + key, value, MAX_DETAIL_VALUE, true)));
        return Collections.unmodifiableSortedMap(copy);
    }

    private static String checked(String field, String value, int max, boolean required, Pattern shape) {
        if (value == null) {
            if (required) {
                throw InvalidJournalEntryException.missing(field);
            }
            return null;
        }
        if (value.length() > max) {
            throw InvalidJournalEntryException.tooLong(field, max);
        }
        if (!shape.matcher(value).matches()) {
            throw InvalidJournalEntryException.malformed(field);
        }
        return value;
    }
}
