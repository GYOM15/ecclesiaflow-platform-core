package com.ecclesiaflow.platform.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The terms are harmless stand-ins: the matching rules do not depend on what a term means. */
class TermFilterTest {

    private static TermFilter filterOf(String... terms) {
        return new TermFilter(Arrays.asList(terms));
    }

    @Nested
    @DisplayName("case")
    class Case {

        @ParameterizedTest
        @ValueSource(strings = {"heck", "HECK", "Heck", "hECk", "What the HECK?"})
        @DisplayName("is ignored on the text")
        void textCase(String text) {
            assertThat(filterOf("heck").rejects(text)).isTrue();
        }

        @Test
        @DisplayName("is ignored on the term")
        void termCase() {
            assertThat(filterOf("HeCk").rejects("what the heck")).isTrue();
        }

        @Test
        @DisplayName("is ignored for the capital of a ligature letter")
        void ligatureCapital() {
            assertThat(filterOf("coeur").rejects("CŒUR")).isTrue();
        }
    }

    @Nested
    @DisplayName("accents")
    class Accents {

        @Test
        @DisplayName("an accented term matches the text without its accents, in any case")
        void accentedTerm() {
            TermFilter filter = filterOf("crétin");

            assertThat(filter.rejects("cretin")).isTrue();
            assertThat(filter.rejects("CRETIN")).isTrue();
            assertThat(filter.rejects("Crétin")).isTrue();
        }

        @Test
        @DisplayName("an unaccented term matches the accented text")
        void accentedText() {
            TermFilter filter = filterOf("flute");

            assertThat(filter.rejects("une flûte")).isTrue();
            assertThat(filter.rejects("FLÛTE")).isTrue();
        }

        @Test
        @DisplayName("a letter written as a base letter plus a combining mark is the accented letter")
        void decomposedText() {
            String decomposed = "cre\u0301tin";

            assertThat(filterOf("crétin").rejects(decomposed)).isTrue();
            assertThat(filterOf("cretin").rejects(decomposed)).isTrue();
        }

        @Test
        @DisplayName("the ligature letters of French read as their two letters")
        void ligatures() {
            assertThat(filterOf("soeur").rejects("ma sœur")).isTrue();
            assertThat(filterOf("sœur").rejects("MA SOEUR")).isTrue();
            assertThat(filterOf("aegis").rejects("Ægis")).isTrue();
        }

        @Test
        @DisplayName("an accent does not make two different words one")
        void differentWords() {
            assertThat(filterOf("heck").rejects("hèque")).isFalse();
        }
    }

    @Nested
    @DisplayName("look-alike forms")
    class LookAlikes {

        @Test
        @DisplayName("full-width letters are the plain letters")
        void fullWidth() {
            assertThat(filterOf("heck").rejects("ｈｅｃｋ")).isTrue();
        }

        @Test
        @DisplayName("a ligature code point is its letters")
        void compatibilityLigature() {
            assertThat(filterOf("office").rejects("o\uFB03ce")).isTrue();
        }

        @Test
        @DisplayName("an invisible character inside a word does not split it")
        void invisibleCharacters() {
            assertThat(filterOf("heck").rejects("he\u200Bck")).isTrue();
            assertThat(filterOf("heck").rejects("he\u00ADck")).isTrue();
            assertThat(filterOf("heck").rejects("\uFEFFheck")).isTrue();
        }

        @Test
        @DisplayName("a dotted capital I is an i, whatever the JVM's locale")
        void dottedCapital() {
            assertThat(filterOf("idiot").rejects("İDİOT")).isTrue();
        }
    }

    @Nested
    @DisplayName("word boundaries")
    class Boundaries {

        @ParameterizedTest
        @ValueSource(strings = {"check", "checkout", "wheck", "Scheck", "heckle", "hecks", "heck2", "2heck", "héckle"})
        @DisplayName("a term inside a longer word is not a match")
        void insideALongerWord(String text) {
            assertThat(filterOf("heck").rejects(text)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"heck", " heck ", "heck!", "(heck)", "oh, heck.", "\"heck\"", "l'heck", "oh-heck",
                "heck_x", "what\nheck", "what\theck", "what the heck", "1 heck 2", "heck?!"})
        @DisplayName("a whole word is a match whatever surrounds it")
        void wholeWord(String text) {
            assertThat(filterOf("heck").rejects(text)).isTrue();
        }

        @Test
        @DisplayName("a plural is its own term")
        void plural() {
            TermFilter singular = filterOf("flûte");

            assertThat(singular.rejects("des flûtes")).isFalse();
            assertThat(filterOf("flûte", "flûtes").rejects("des flûtes")).isTrue();
        }

        @Test
        @DisplayName("a term that is a number matches that number only")
        void digits() {
            TermFilter filter = filterOf("666");

            assertThat(filter.rejects("le 666")).isTrue();
            assertThat(filter.rejects("6666")).isFalse();
        }

        @Test
        @DisplayName("a term is not found across two words")
        void acrossWords() {
            assertThat(filterOf("heck").rejects("he ck")).isFalse();
        }
    }

    @Nested
    @DisplayName("terms of several words")
    class Phrases {

        @ParameterizedTest
        @ValueSource(strings = {"oh heck", "Oh   HECK", "oh-heck", "oh, heck!", "OH\nHECK", "well oh heck then"})
        @DisplayName("match whatever separates their words")
        void separators(String text) {
            assertThat(filterOf("oh heck").rejects(text)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"ohheck", "oh heckle", "ooh heck", "heck oh", "oh", "heck", "oh well, heck"})
        @DisplayName("need all their words, whole and in order")
        void incomplete(String text) {
            assertThat(filterOf("oh heck").rejects(text)).isFalse();
        }

        @Test
        @DisplayName("share their first word with a shorter term")
        void sharedFirstWord() {
            TermFilter filter = filterOf("oh heck", "oh");

            assertThat(filter.rejects("oh")).isTrue();
            assertThat(filter.rejects("oh my")).isTrue();
            assertThat(filterOf("oh heck", "oh dear").rejects("oh dear")).isTrue();
            assertThat(filterOf("oh heck", "oh dear").rejects("oh my")).isFalse();
        }

        @Test
        @DisplayName("punctuation inside a term is only a separator")
        void punctuationInTerm() {
            assertThat(filterOf("oh-heck").rejects("oh heck")).isTrue();
        }
    }

    @Nested
    @DisplayName("an empty list")
    class EmptyList {

        private final TermFilter empty = new TermFilter(List.of());

        @Test
        @DisplayName("rejects nothing")
        void rejectsNothing() {
            assertThat(empty.rejects("anything at all")).isFalse();
            assertThatCode(() -> empty.requireClean("anything at all")).doesNotThrowAnyException();
            assertThatCode(() -> empty.requireClean("title", "anything at all")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("counts no term")
        void sizeZero() {
            assertThat(empty.size()).isZero();
        }
    }

    @Nested
    @DisplayName("the text")
    class Text {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\n\t", "!!! ???", "😀"})
        @DisplayName("without a word is clean")
        void noWord(String text) {
            TermFilter filter = filterOf("heck");

            assertThat(filter.rejects(text)).isFalse();
            assertThatCode(() -> filter.requireClean(text)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a long text with the term at its very end is found")
        void termAtTheEnd() {
            String text = "bonjour ".repeat(5_000) + "heck";

            assertThat(filterOf("heck").rejects(text)).isTrue();
        }
    }

    @Nested
    @DisplayName("requireClean")
    class RequireClean {

        private final TermFilter filter = filterOf("heck");

        @Test
        @DisplayName("lets a clean text through")
        void clean() {
            assertThatCode(() -> filter.requireClean("hello")).doesNotThrowAnyException();
            assertThatCode(() -> filter.requireClean("title", "hello")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a rejected text without naming a field")
        void rejected() {
            assertThatThrownBy(() -> filter.requireClean("what the heck"))
                    .isInstanceOf(ContentRejectedException.class)
                    .satisfies(error -> assertThat(((ContentRejectedException) error).getField()).isNull());
        }

        @Test
        @DisplayName("names the field of a rejected text")
        void rejectedField() {
            assertThatThrownBy(() -> filter.requireClean("title", "what the heck"))
                    .isInstanceOf(ContentRejectedException.class)
                    .satisfies(error -> assertThat(((ContentRejectedException) error).getField()).isEqualTo("title"));
        }

        @Test
        @DisplayName("keeps the text and the term out of the exception")
        void leaksNothing() {
            assertThatThrownBy(() -> filter.requireClean("title", "what the HECK is this"))
                    .hasMessageNotContainingAny("heck", "HECK", "what the")
                    .hasNoCause();
        }
    }

    @Nested
    @DisplayName("building a filter")
    class Building {

        @Test
        @DisplayName("counts a term once however it is written")
        void distinctTerms() {
            assertThat(filterOf("Crétin", "cretin", "CRÉTIN", "idiot").size()).isEqualTo(2);
        }

        @Test
        @DisplayName("refuses a term with no letter or digit, which could never match")
        void refusesWordlessTerm() {
            assertThatThrownBy(() -> filterOf("heck", "***"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("***");
            assertThatThrownBy(() -> filterOf("  ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> filterOf((String) null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("is not changed by the collection it was built from")
        void independentOfSource() {
            List<String> source = new java.util.ArrayList<>(List.of("heck"));
            TermFilter filter = new TermFilter(source);
            source.clear();
            source.add("other");

            assertThat(filter.rejects("heck")).isTrue();
            assertThat(filter.rejects("other")).isFalse();
        }
    }
}
