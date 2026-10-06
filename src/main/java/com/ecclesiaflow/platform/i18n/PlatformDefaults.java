package com.ecclesiaflow.platform.i18n;

import java.util.Locale;

/**
 * The render locale used when neither the user nor the tenant resolves one; changing it changes the
 * default rendering of the whole platform.
 */
public final class PlatformDefaults {

    public static final Locale LOCALE = SupportedLocales.FR;

    private PlatformDefaults() {}
}
