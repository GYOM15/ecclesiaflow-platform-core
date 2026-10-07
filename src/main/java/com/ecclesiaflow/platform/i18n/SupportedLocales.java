package com.ecclesiaflow.platform.i18n;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The only gate for an externally supplied locale (Accept-Language, cookie, body, AMQP header): run it
 * through {@link #parse(String)} or {@link #contains(Locale)} so a hostile value collapses to empty and
 * the caller falls back to {@link PlatformDefaults#LOCALE}. Validation is set membership, not a parser
 * exposed to arbitrary input.
 */
public final class SupportedLocales {

    public static final Locale FR = Locale.forLanguageTag("fr");

    public static final Locale EN = Locale.forLanguageTag("en");

    /** Practical BCP-47 bound (RFC 5646), checked before a huge Accept-Language reaches the JDK parser. */
    private static final int MAX_TAG_LENGTH = 35;

    private static final Set<Locale> SET = Set.of(FR, EN);

    private SupportedLocales() {}

    public static Set<Locale> all() {
        return SET;
    }

    /** Matched on the language subtag only: {@code fr-CA} counts as {@code fr}. */
    public static boolean contains(Locale locale) {
        return locale != null && byLanguage(locale.getLanguage()).isPresent();
    }

    /**
     * Never throws: a null, blank, over-long, malformed or unsupported tag yields empty. Matched on the
     * language subtag; finer fallback inside a bundle is left to the MessageSource or next-intl.
     */
    public static Optional<Locale> parse(String tag) {
        if (tag == null) {
            return Optional.empty();
        }
        String trimmed = tag.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TAG_LENGTH) {
            return Optional.empty();
        }
        // Locale.forLanguageTag never throws: garbage yields an empty language.
        return byLanguage(Locale.forLanguageTag(trimmed).getLanguage());
    }

    private static Optional<Locale> byLanguage(String language) {
        if (language == null || language.isEmpty()) {
            return Optional.empty();
        }
        return SET.stream().filter(s -> s.getLanguage().equals(language)).findFirst();
    }
}
