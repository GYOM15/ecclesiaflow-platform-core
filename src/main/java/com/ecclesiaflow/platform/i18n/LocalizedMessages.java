package com.ecclesiaflow.platform.i18n;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves a message key to a rendered string in a given locale, using ICU
 * message syntax with NAMED arguments — the same format next-intl uses on the
 * frontend, so one message convention spans front and back.
 *
 * <p>Named (not positional) arguments are deliberate: {@code {firstName}} and
 * {@code {count, plural, one {# membre} other {# membres}}} are safe for
 * translators to reorder. The French bundle is the complete backstop: a key or
 * argument missing in another locale falls back to French rather than rendering
 * blank.</p>
 */
public interface LocalizedMessages {

    /**
     * @param key    the dotted message key (e.g. {@code notifications.request_accepted.email.subject})
     * @param locale the render locale (falls back through language then the French base bundle)
     * @param args   named ICU arguments; may be empty, never null
     * @return the formatted message, or the {@code key} itself if it is absent even in the base bundle
     */
    String get(String key, Locale locale, Map<String, Object> args);

    /** Convenience overload for a message with no arguments. */
    default String get(String key, Locale locale) {
        return get(key, locale, Map.of());
    }
}
