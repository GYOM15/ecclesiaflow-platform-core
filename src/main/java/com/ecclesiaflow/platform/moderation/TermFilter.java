package com.ecclesiaflow.platform.moderation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses a text that holds a listed term as a whole word or word sequence. Case, accents and compatibility
 * forms do not matter, and punctuation between the words of a term does not either; a term inside a longer
 * word does not match ({@code "class"} is not {@code "ass"}), so a plural or a conjugation is a term of its own.
 * Words are runs of letters and digits, which fits French and English; a script written without spaces is
 * matched only as one whole run.
 *
 * <p>Immutable and safe to share. A filter with no term rejects nothing.</p>
 */
public final class TermFilter {

    private final Map<String, List<List<String>>> termsByFirstWord;
    private final int size;

    /** Throws {@link IllegalArgumentException} for a term with no letter or digit, which could never match. */
    public TermFilter(Collection<String> terms) {
        Set<List<String>> distinct = new LinkedHashSet<>();
        for (String term : terms) {
            List<String> words = TermNormalizer.words(term);
            if (words.isEmpty()) {
                throw new IllegalArgumentException("A term must hold at least one letter or digit");
            }
            distinct.add(words);
        }
        Map<String, List<List<String>>> index = new HashMap<>();
        for (List<String> words : distinct) {
            index.computeIfAbsent(words.get(0), firstWord -> new ArrayList<>()).add(words);
        }
        this.termsByFirstWord = index;
        this.size = distinct.size();
    }

    /** The number of distinct terms once reduced, so {@code "Crétin"} and {@code "cretin"} count once. */
    public int size() {
        return size;
    }

    public boolean rejects(String text) {
        if (size == 0) {
            return false;
        }
        List<String> words = TermNormalizer.words(text);
        for (int start = 0; start < words.size(); start++) {
            List<List<String>> candidates = termsByFirstWord.get(words.get(start));
            if (candidates == null) {
                continue;
            }
            for (List<String> term : candidates) {
                if (startsWith(words, start, term)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void requireClean(String text) {
        if (rejects(text)) {
            throw ContentRejectedException.rejected();
        }
    }

    public void requireClean(String field, String text) {
        if (rejects(text)) {
            throw ContentRejectedException.rejected(field);
        }
    }

    private static boolean startsWith(List<String> words, int start, List<String> term) {
        return words.size() - start >= term.size() && words.subList(start, start + term.size()).equals(term);
    }
}
