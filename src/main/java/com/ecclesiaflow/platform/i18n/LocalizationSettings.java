package com.ecclesiaflow.platform.i18n;

import java.util.Locale;
import java.util.Objects;

/**
 * A tenant's localization settings.
 *
 * <p>Phase 0 carries the render {@code locale} only. The tenant-axis concerns
 * (currency and time zone) are deliberately deferred to a later phase and will
 * be added here as additional record components when a feature actually needs
 * them — kept separate from the user-axis render locale, since conflating the
 * two is a classic i18n bug.</p>
 */
public record LocalizationSettings(Locale locale) {

    public LocalizationSettings {
        Objects.requireNonNull(locale, "locale");
    }
}
