package com.ecclesiaflow.platform.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IcuMessagesTest {

    private final LocalizedMessages messages = new IcuMessages("i18n/test-messages");

    @Test
    void resolvesNamedPlaceholdersPerLocale() {
        assertThat(messages.get("greeting.hello", SupportedLocales.FR, Map.of("firstName", "David")))
                .isEqualTo("Bonjour David");
        assertThat(messages.get("greeting.hello", SupportedLocales.EN, Map.of("firstName", "David")))
                .isEqualTo("Hello David");
    }

    @Test
    void appliesIcuPluralRules() {
        assertThat(messages.get("members.count", SupportedLocales.FR, Map.of("count", 1))).isEqualTo("1 membre");
        assertThat(messages.get("members.count", SupportedLocales.FR, Map.of("count", 3))).isEqualTo("3 membres");
        assertThat(messages.get("members.count", SupportedLocales.EN, Map.of("count", 1))).isEqualTo("1 member");
        assertThat(messages.get("members.count", SupportedLocales.EN, Map.of("count", 3))).isEqualTo("3 members");
    }

    @Test
    void fallsBackToFrenchBaseForAKeyMissingInTheLocale() {
        // common.only_in_fr is absent from the _en overlay -> resolves to French.
        assertThat(messages.get("common.only_in_fr", SupportedLocales.EN))
                .isEqualTo("Seulement en français");
    }

    @Test
    void foldsRegionalLocaleOntoItsLanguageBundle() {
        // fr-CA has no bundle -> falls through fr(base). Proves the linguistic fallback.
        assertThat(messages.get("greeting.hello", Locale.forLanguageTag("fr-CA"), Map.of("firstName", "David")))
                .isEqualTo("Bonjour David");
    }

    @Test
    void neverLeaksTheJvmDefaultLocale() {
        // An unsupported request locale must resolve via the French base, not the
        // JVM default — deterministic regardless of the server's own locale.
        assertThat(messages.get("greeting.hello", Locale.GERMAN, Map.of("firstName", "David")))
                .isEqualTo("Bonjour David");
    }

    @Test
    void returnsTheKeyWhenTrulyAbsent() {
        assertThat(messages.get("does.not.exist", SupportedLocales.FR)).isEqualTo("does.not.exist");
    }

    @Test
    void nullLocaleUsesThePlatformDefault() {
        assertThat(messages.get("greeting.hello", null, Map.of("firstName", "David")))
                .isEqualTo("Bonjour David");
    }
}
