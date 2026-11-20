package com.ecclesiaflow.platform.journal;

/**
 * A journal entry the library refuses to write. The message names the field, never its value: a refused value may be
 * the very personal data the journal must not hold.
 */
public class InvalidJournalEntryException extends IllegalArgumentException {

    private final String field;

    private InvalidJournalEntryException(String field, String message) {
        super(message);
        this.field = field;
    }

    public static InvalidJournalEntryException missing(String field) {
        return new InvalidJournalEntryException(field, "A journal entry needs its " + field);
    }

    public static InvalidJournalEntryException malformed(String field) {
        return new InvalidJournalEntryException(field, "The " + field + " of a journal entry is not an id or a code "
                + "the journal holds (letters, digits and ._:- only; never a name, an address, a phone number or "
                + "text)");
    }

    public static InvalidJournalEntryException tooLong(String field, int max) {
        return new InvalidJournalEntryException(field, "The " + field + " of a journal entry exceeds " + max
                + " characters");
    }

    public static InvalidJournalEntryException tooManyDetails(int max) {
        return new InvalidJournalEntryException("details", "A journal entry holds at most " + max + " details");
    }

    public static InvalidJournalEntryException refusalWithoutRefusal() {
        return new InvalidJournalEntryException("refusalCode", "Only a refused gesture carries a refusal code");
    }

    /** The field the entry was refused for. */
    public String field() {
        return field;
    }
}
