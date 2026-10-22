package com.ecclesiaflow.platform.moderation;

import java.nio.file.Path;

/**
 * The module's term list is not configured, missing, unreadable or malformed. Thrown at startup only: a module
 * that starts with a list it cannot read would moderate nothing and say nothing.
 */
public class InvalidTermListException extends IllegalStateException {

    private InvalidTermListException(String message) {
        super(message);
    }

    private InvalidTermListException(String message, Throwable cause) {
        super(message, cause);
    }

    public static InvalidTermListException notConfigured() {
        return new InvalidTermListException(TermFilterProperties.TERMS_FILE
                + " is blank: set it (MODERATION_TERMS_FILE) to the path of the term list");
    }

    public static InvalidTermListException notAFile(Path file) {
        return new InvalidTermListException("The moderation term list " + file + " does not exist or is not a file: set "
                + TermFilterProperties.TERMS_FILE + " (MODERATION_TERMS_FILE) to a mounted UTF-8 file");
    }

    public static InvalidTermListException unreadable(Path file, Throwable cause) {
        return new InvalidTermListException("The moderation term list " + file + " cannot be read as UTF-8", cause);
    }

    public static InvalidTermListException noWord(Path file, int lineNumber) {
        return new InvalidTermListException("The moderation term list " + file + " line " + lineNumber
                + " holds no letter or digit, so it could never match; remove it or comment it with #");
    }
}
