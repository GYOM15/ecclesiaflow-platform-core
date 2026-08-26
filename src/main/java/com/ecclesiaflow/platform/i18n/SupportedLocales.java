package com.ecclesiaflow.platform.i18n;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The closed set of locales the platform renders, and the ONLY gate through
 * which an externally-supplied locale may pass.
 *
 * <p><strong>Security anchor.</strong> A locale is attacker-influenceable — it
 * arrives from the {@code Accept-Language} header, a browser cookie, a request
 * body, or an AMQP message header. Every such value MUST be run through
 * {@link #parse(String)} (or checked with {@link #contains(Locale)}) before it
 * is used, so a hostile or malformed input collapses to {@link Optional#empty()}
 * and the caller falls back to {@link PlatformDefaults#LOCALE} instead of letting
 * the raw value reach a message lookup, a log line, or the DOM. The set is
 * intentionally tiny, so validation is a set-membership test, not a parser
 * exposed to arbitrary input. Locale never drives an authorization decision.</p>
 */
public final class SupportedLocales {

    /** French — the platform default and today's behaviour. */
    public static final Locale FR = Locale.forLanguageTag("fr");

    /** English. */
    public static final Locale EN = Locale.forLanguageTag("en");

    /** Practical upper bound of a BCP-47 tag (RFC 5646) — guards against a huge
     *  {@code Accept-Language} value before it reaches the JDK parser. */
    private static final int MAX_TAG_LENGTH = 35;

    private static final Set<Locale> SET = Set.of(FR, EN);

    private SupportedLocales() {}

    /** The immutable set of rendered locales. */
    public static Set<Locale> all() {
        return SET;
    }

    /**
     * Whether {@code locale} is one the platform renders, matched on the language
     * subtag only ({@code fr-CA} counts as {@code fr}).
     */
    public static boolean contains(Locale locale) {
        return locale != null && byLanguage(locale.getLanguage()).isPresent();
    }

    /**
     * Parses an untrusted BCP-47 tag to a SUPPORTED locale, or {@link Optional#empty()}
     * when it is null, blank, over-long, malformed or unsupported.
     *
     * <p>Never throws — that is the point: a hostile input yields an empty result,
     * letting the caller fall back to {@link PlatformDefaults#LOCALE} rather than
     * propagate the value. Matching is on the language subtag, so {@code fr-CA}
     * resolves to the supported {@code fr}; the finer linguistic fallback
     * ({@code fr-CA -> fr} inside a bundle) is owned downstream by the
     * MessageSource / next-intl, not here.</p>
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
