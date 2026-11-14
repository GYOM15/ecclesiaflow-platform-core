package com.ecclesiaflow.platform.security.reauth.code;

/**
 * The address a code went to, as its holder sees it in the dialog ({@code c•••@gmail.com}): the first character
 * and the domain, so the holder recognises it and a shoulder does not read it.
 */
public final class AddressMask {

    private static final String HIDDEN = "•••";

    private AddressMask() {
    }

    /** {@code null} for anything that does not read as an address: the client then words it without. */
    public static String mask(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.strip();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return null;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)) + HIDDEN + trimmed.substring(at);
    }
}
