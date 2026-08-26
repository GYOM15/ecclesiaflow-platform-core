package com.ecclesiaflow.platform.i18n;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the RENDER locale (which language to show) for the current
 * interaction.
 *
 * <p>This is the USER axis, deliberately distinct from the tenant axis (currency
 * / time zone) a later phase will add: a member may read the UI in English while
 * their church's money stays in its own currency, so the two are resolved by
 * different means and never conflated.</p>
 *
 * <p>Order: the user's own preference, then the tenant's default, then the
 * platform backstop ({@link PlatformDefaults#LOCALE}). Both inputs are expected
 * to be already-validated supported locales — run any untrusted tag through
 * {@link SupportedLocales#parse(String)} first, so an unresolvable value arrives
 * here as {@link Optional#empty()} and simply defers to the next tier. The finer
 * linguistic fallback ({@code fr-CA -> fr} within a bundle) is owned by the
 * MessageSource / next-intl, not here.</p>
 */
public final class EffectiveLocaleResolver {

    /**
     * @param userPreference the caller's own validated render locale, if any
     * @param tenantDefault  the tenant's validated default render locale, if any
     * @return the first present of user, tenant, platform default — never null
     */
    public Locale resolve(Optional<Locale> userPreference, Optional<Locale> tenantDefault) {
        return userPreference.or(() -> tenantDefault).orElse(PlatformDefaults.LOCALE);
    }
}
