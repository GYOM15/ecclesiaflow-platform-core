package com.ecclesiaflow.platform.i18n;

import java.util.Locale;

/**
 * Platform-wide localization defaults — the anti-regression anchor.
 *
 * <p>{@link #LOCALE} is the render locale the whole platform falls back to when
 * neither the user nor the tenant resolves one. It MUST equal the language the
 * product renders in today ({@code fr}): with no tenant or user setting, output
 * stays byte-identical to the current behaviour. Changing this value changes the
 * default rendering of the entire platform, so it is a single, deliberate knob.</p>
 */
public final class PlatformDefaults {

    /** The render-locale backstop. French — today's behaviour. */
    public static final Locale LOCALE = SupportedLocales.FR;

    private PlatformDefaults() {}
}
