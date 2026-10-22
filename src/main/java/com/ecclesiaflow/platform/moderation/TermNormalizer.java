package com.ecclesiaflow.platform.moderation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Reduces a text to the lower-case words a term can be compared with. The term list goes through the same
 * reduction as the text, so an accent or a capital on either side never decides the outcome.
 */
final class TermNormalizer {

    private TermNormalizer() {
    }

    /** The letter-or-digit runs of the text; every other character, an apostrophe or a hyphen included, separates. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        StringBuilder word = new StringBuilder();
        // NFKD also folds the compatibility forms (full-width letters, ligatures) that would slip past a plain lower-casing.
        Normalizer.normalize(text, Normalizer.Form.NFKD).codePoints().forEach(codePoint -> {
            if (ignorable(codePoint)) {
                return;
            }
            int lower = Character.toLowerCase(codePoint);
            if (Character.isLetterOrDigit(lower)) {
                append(word, lower);
            } else if (!word.isEmpty()) {
                words.add(word.toString());
                word.setLength(0);
            }
        });
        if (!word.isEmpty()) {
            words.add(word.toString());
        }
        return words;
    }

    // Marks are the accents NFKD split off; format characters (zero-width space, soft hyphen, BOM) are invisible,
    // so a letter split by one still reads as the word.
    private static boolean ignorable(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
                 Character.FORMAT -> true;
            default -> false;
        };
    }

    // NFKD leaves these two ligature letters whole, and French writes them (cœur, sœur).
    private static void append(StringBuilder word, int lower) {
        switch (lower) {
            case 'œ' -> word.append("oe");
            case 'æ' -> word.append("ae");
            default -> word.appendCodePoint(lower);
        }
    }
}
