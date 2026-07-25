package com.ecclesiaflow.platform.i18n;

import com.ibm.icu.text.MessageFormat;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * {@link LocalizedMessages} backed by {@code .properties} resource bundles and
 * ICU4J {@link MessageFormat} (named arguments, plurals, gender).
 *
 * <p>Each consuming module constructs one with its own bundle base name (e.g.
 * {@code "i18n/notifications"}); this class owns only the lookup + fallback +
 * ICU formatting, not the strings.</p>
 *
 * <p><strong>Deterministic French backstop.</strong> The base bundle
 * ({@code <baseName>.properties}) holds the French strings — the complete
 * backstop. Other locales are overlays ({@code <baseName>_en.properties}); any
 * key missing there resolves to French. The fallback chain is fixed to
 * {@code requested-locale -> language -> base(fr)} with the JVM default locale
 * deliberately removed, so a server whose default happens to be, say, German
 * can never leak German resolution into a request.</p>
 *
 * <p>UTF-8 property files are read natively (Java 9+), so accented French keys
 * need no escaping. Missing keys return the key itself rather than throwing —
 * a defect the golden tests catch, but never a crashed email or a blank line.</p>
 */
public final class IcuMessages implements LocalizedMessages {

    /**
     * Loads {@code .properties} only, and pins the fallback chain to
     * {@code requested -> language -> base}, skipping the JVM default locale so
     * resolution is deterministic regardless of the server's own locale.
     */
    private static final ResourceBundle.Control CONTROL = new ResourceBundle.Control() {
        @Override
        public java.util.List<String> getFormats(String baseName) {
            return ResourceBundle.Control.FORMAT_PROPERTIES;
        }

        @Override
        public Locale getFallbackLocale(String baseName, Locale locale) {
            return null;
        }
    };

    private final String baseName;

    /**
     * @param baseName the resource-bundle base name on the classpath, using
     *                 {@code /} or {@code .} separators (e.g. {@code "i18n/emails"})
     */
    public IcuMessages(String baseName) {
        this.baseName = baseName;
    }

    @Override
    public String get(String key, Locale locale, Map<String, Object> args) {
        Locale target = locale != null ? locale : PlatformDefaults.LOCALE;
        String pattern;
        try {
            pattern = ResourceBundle.getBundle(baseName, target, CONTROL).getString(key);
        } catch (MissingResourceException e) {
            // No bundle, or the key is absent even in the French base bundle.
            return key;
        }
        return new MessageFormat(pattern, target).format(args != null ? args : Map.of());
    }
}
