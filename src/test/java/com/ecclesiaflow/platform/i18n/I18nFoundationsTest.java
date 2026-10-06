package com.ecclesiaflow.platform.i18n;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class I18nFoundationsTest {

    @Nested
    class SupportedLocalesParse {

        @Test
        void resolvesTheTwoSupportedTags() {
            assertThat(SupportedLocales.parse("fr")).contains(SupportedLocales.FR);
            assertThat(SupportedLocales.parse("en")).contains(SupportedLocales.EN);
        }

        @Test
        void foldsRegionalTagsOntoTheLanguage() {
            // Linguistic fallback anchor: fr-CA and en-GB are "supported" via fr / en.
            assertThat(SupportedLocales.parse("fr-CA")).contains(SupportedLocales.FR);
            assertThat(SupportedLocales.parse("en-GB")).contains(SupportedLocales.EN);
        }

        @Test
        void trimsSurroundingWhitespace() {
            assertThat(SupportedLocales.parse("  fr  ")).contains(SupportedLocales.FR);
        }

        @Test
        void rejectsNullBlankAndUnsupported() {
            // Every hostile or unknown input collapses to empty and never throws.
            assertThat(SupportedLocales.parse(null)).isEmpty();
            assertThat(SupportedLocales.parse("")).isEmpty();
            assertThat(SupportedLocales.parse("   ")).isEmpty();
            assertThat(SupportedLocales.parse("de")).isEmpty();
            assertThat(SupportedLocales.parse("es-MX")).isEmpty();
        }

        @Test
        void rejectsGarbageAndOverlongInputWithoutThrowing() {
            assertThat(SupportedLocales.parse("!!!")).isEmpty();
            assertThat(SupportedLocales.parse("../../etc/passwd")).isEmpty();
            assertThat(SupportedLocales.parse("fr\n<script>")).isEmpty();
            // A huge Accept-Language value must be rejected before the JDK parser.
            assertThat(SupportedLocales.parse("f".repeat(5000))).isEmpty();
        }
    }

    @Nested
    class SupportedLocalesContains {

        @Test
        void tracksTheRenderedSet() {
            assertThat(SupportedLocales.contains(SupportedLocales.FR)).isTrue();
            assertThat(SupportedLocales.contains(Locale.forLanguageTag("fr-CA"))).isTrue();
            assertThat(SupportedLocales.contains(SupportedLocales.EN)).isTrue();
            assertThat(SupportedLocales.contains(Locale.GERMAN)).isFalse();
            assertThat(SupportedLocales.contains(null)).isFalse();
        }

        @Test
        void exposesExactlyFrAndEn() {
            assertThat(SupportedLocales.all()).containsExactlyInAnyOrder(
                    SupportedLocales.FR, SupportedLocales.EN);
        }
    }

    @Nested
    class Defaults {

        @Test
        void platformDefaultIsFrench() {
            // The fallback locale drives the default rendering of the whole platform.
            assertThat(PlatformDefaults.LOCALE).isEqualTo(SupportedLocales.FR);
            assertThat(PlatformDefaults.LOCALE.getLanguage()).isEqualTo("fr");
        }
    }
}
