package com.ecclesiaflow.platform.web.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TextInputGuardTest {

    private static String text(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    @DisplayName("a plain text, accents, emoji and line breaks included, comes back as the same instance")
    void keepsPlainText() {
        String plain = "Réunion à 10 h\tsalle 2\r\nMerci 🙏";
        assertThat(TextInputGuard.clean(plain)).isSameAs(plain);
        assertThat(TextInputGuard.clean(null)).isNull();
        assertThat(TextInputGuard.clean("")).isEmpty();
    }

    @Test
    @DisplayName("joiners, directional marks and variation selectors stay: emoji sequences and right-to-left scripts need them")
    void keepsWhatScriptsAndEmojiNeed() {
        String family = text(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467);
        String persian = text(0x0645, 0x06CC, 0x200C, 0x062E, 0x0648, 0x0627, 0x0647, 0x0645);
        String marked = text('a', 0x200E, 'b', 0x200F, 'c', 0x061C);
        String heart = text(0x2764, 0xFE0F);
        for (String text : new String[] {family, persian, marked, heart}) {
            assertThat(TextInputGuard.clean(text)).isSameAs(text);
        }
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(ints = {0x200B, 0x2060, 0x2061, 0x2064, 0xFEFF, 0x180E, 0x115F, 0x1160, 0x3164, 0xFFA0, 0xE0041, 0xE007F})
    @DisplayName("an invisible character that only hides content is dropped")
    void dropsHiddenCharacters(int hidden) {
        assertThat(TextInputGuard.clean(text('P', 'r', hidden, 'i', 0x00E8, 'r', 'e', hidden))).isEqualTo("Prière");
    }

    @Test
    @DisplayName("a text smuggled in Unicode tags disappears whole")
    void dropsTagSmuggling() {
        String smuggled = "Bonjour" + text(0xE0049, 0xE0067, 0xE006E, 0xE006F, 0xE0072, 0xE0065);
        assertThat(TextInputGuard.clean(smuggled)).isEqualTo("Bonjour");
    }

    @Test
    @DisplayName("a subdivision flag keeps its tags, before or after a dropped character")
    void keepsSubdivisionFlags() {
        String scotland = text(0x1F3F4, 0xE0067, 0xE0062, 0xE0073, 0xE0063, 0xE0074, 0xE007F);
        assertThat(TextInputGuard.clean(scotland)).isSameAs(scotland);
        assertThat(TextInputGuard.clean(text(0x200B) + scotland + "!")).isEqualTo(scotland + "!");
        assertThat(TextInputGuard.clean(scotland + text(0x200B))).isEqualTo(scotland);
    }

    @Test
    @DisplayName("a black flag followed by tags that never end is a plain flag, its tags dropped")
    void dropsBrokenFlagTags() {
        String flag = text(0x1F3F4);
        assertThat(TextInputGuard.clean(flag + text(0xE0067, 0xE0062))).isEqualTo(flag);
        assertThat(TextInputGuard.clean(flag + text(0xE007F))).isEqualTo(flag);
        assertThat(TextInputGuard.clean(flag + text(0xE0067, 0x41, 0xE007F))).isEqualTo(flag + "A");
        assertThat(TextInputGuard.clean(flag + "x")).isEqualTo(flag + "x");
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(ints = {0x00, 0x07, 0x0B, 0x0C, 0x1B, 0x1F, 0x7F, 0x85, 0x9F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2066, 0x2067, 0x2068, 0x2069, 0xFFF9, 0xFFFA, 0xFFFB, 0xFDD0, 0xFDEF, 0xFFFE, 0xFFFF, 0x1FFFE, 0x10FFFF})
    @DisplayName("a character that changes how a text is read or processed is dropped too, never refused")
    void dropsUnsafeCharacters(int unsafe) {
        assertThat(TextInputGuard.clean(text('a', unsafe, 'b'))).isEqualTo("ab");
    }

    @Test
    @DisplayName("a reversed file name loses its override and reads as it is")
    void dropsTrojanSourceReordering() {
        assertThat(TextInputGuard.clean("invoice" + text(0x202E) + "fdp.exe")).isEqualTo("invoicefdp.exe");
    }

    @Test
    @DisplayName("an unpaired surrogate is dropped")
    void dropsUnpairedSurrogates() {
        assertThat(TextInputGuard.clean("a" + (char) 0xD83D + "b")).isEqualTo("ab");
        assertThat(TextInputGuard.clean("a" + (char) 0xDE00)).isEqualTo("a");
    }

    @Test
    @DisplayName("the line and paragraph separators become line feeds")
    void turnsSeparatorsIntoLineFeeds() {
        assertThat(TextInputGuard.clean(text('a', 0x2028, 'b', 0x2029, 'c'))).isEqualTo("a\nb\nc");
    }
}
