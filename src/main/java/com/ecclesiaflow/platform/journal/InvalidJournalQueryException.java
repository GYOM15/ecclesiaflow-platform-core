package com.ecclesiaflow.platform.journal;

/** A journal page the reader cannot serve: a foreign cursor, a limit out of range, a period that ends first. */
public class InvalidJournalQueryException extends IllegalArgumentException {

    private InvalidJournalQueryException(String message) {
        super(message);
    }

    public static InvalidJournalQueryException cursor() {
        return new InvalidJournalQueryException("The cursor is not one this journal issued");
    }

    public static InvalidJournalQueryException limit(int max) {
        return new InvalidJournalQueryException("A journal page holds 1 to " + max + " lines");
    }

    public static InvalidJournalQueryException period() {
        return new InvalidJournalQueryException("A journal period ends after it starts");
    }

    public static InvalidJournalQueryException targetRefWithoutKind() {
        return new InvalidJournalQueryException("A journal target is named by its kind and its reference");
    }

    public static InvalidJournalQueryException filter(String field) {
        return new InvalidJournalQueryException("The " + field + " filter is not an id or a code the journal holds");
    }
}
