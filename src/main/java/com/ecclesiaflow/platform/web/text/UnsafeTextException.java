package com.ecclesiaflow.platform.web.text;

/** A text holds a character that changes how it is read or processed; the request is refused, never repaired. */
public final class UnsafeTextException extends Exception {

    /** The validation code clients read, next to the field's path. */
    public static final String CODE = "UnsafeCharacters";

    UnsafeTextException() {
        super("A text holds a control, bidirectional or other unsafe character.", null, false, false);
    }
}
