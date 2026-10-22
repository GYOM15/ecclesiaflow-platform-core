package com.ecclesiaflow.platform.moderation;

/**
 * Text the {@link TermFilter} refuses. It carries neither the text nor the matched term: both would reach
 * logs and error bodies, and a client told which term matched could probe the list.
 */
public class ContentRejectedException extends RuntimeException {

    private final String field;

    private ContentRejectedException(String field) {
        super("The content contains a term that is not allowed");
        this.field = field;
    }

    public static ContentRejectedException rejected() {
        return new ContentRejectedException(null);
    }

    /** {@code field} names the request property the answer points at, so a form with several can mark the right one. */
    public static ContentRejectedException rejected(String field) {
        return new ContentRejectedException(field);
    }

    /** The request property that held the text, or {@code null} when the caller named none. */
    public String getField() {
        return field;
    }
}
