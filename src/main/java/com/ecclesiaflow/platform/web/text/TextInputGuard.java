package com.ecclesiaflow.platform.web.text;

/**
 * What a text sent to the API keeps. Characters that hide content or change how a text is read or processed are
 * dropped, never refused, so a pasted text, or a report quoting an abusive one, always goes through: zero-width
 * spaces and joiners of words, fillers, Unicode tags outside an emoji flag, controls other than tab, CR and LF,
 * bidirectional embeddings, overrides and isolates, unpaired surrogates, noncharacters and interlinear annotations.
 * The line and paragraph separators become line feeds. Joiners and directional marks stay: emoji sequences and
 * right-to-left scripts need them.
 */
public final class TextInputGuard {

    private static final int BLACK_FLAG = 0x1F3F4;
    private static final int CANCEL_TAG = 0xE007F;

    private TextInputGuard() {
    }

    /** The text without what it must not hold, or the text itself when it holds none of it. */
    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder kept = null;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int next = index + Character.charCount(codePoint);
            if (codePoint == BLACK_FLAG && next < text.length() && isTag(text.codePointAt(next))) {
                int end = flagEnd(text, next);
                if (end > 0) {
                    if (kept != null) {
                        kept.append(text, index, end);
                    }
                    index = end;
                    continue;
                }
            }
            boolean separator = codePoint == 0x2028 || codePoint == 0x2029;
            if (separator || isDropped(codePoint)) {
                if (kept == null) {
                    kept = new StringBuilder(text.length()).append(text, 0, index);
                }
                if (separator) {
                    kept.append('\n');
                }
            } else if (kept != null) {
                kept.appendCodePoint(codePoint);
            }
            index = next;
        }
        return kept == null ? text : kept.toString();
    }

    // A subdivision flag (England, Scotland, Wales) is the black flag, tag letters and the cancel tag: the only
    // legitimate use of tags, which otherwise smuggle invisible text. -1 when the run is not such a sequence.
    private static int flagEnd(String text, int start) {
        int index = start;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == CANCEL_TAG) {
                return index - start > Character.charCount(CANCEL_TAG) ? index : -1;
            }
            if (codePoint < 0xE0020 || codePoint > 0xE007E) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isTag(int codePoint) {
        return codePoint >= 0xE0000 && codePoint <= 0xE007F;
    }

    private static boolean isDropped(int codePoint) {
        if (codePoint < 0x20) {
            return codePoint != '\t' && codePoint != '\n' && codePoint != '\r';
        }
        if (codePoint >= 0x7F && codePoint <= 0x9F) {
            return true;
        }
        if (codePoint <= 0xFFFF && Character.isSurrogate((char) codePoint)) {
            return true;
        }
        if ((codePoint >= 0xFDD0 && codePoint <= 0xFDEF) || (codePoint & 0xFFFE) == 0xFFFE) {
            return true;
        }
        return switch (codePoint) {
            // Zero-width space, word joiner and the invisible math operators, byte order mark, Mongolian vowel
            // separator, Hangul fillers; embeddings, overrides and isolates, which reorder what is displayed (Trojan
            // Source); the interlinear annotation marks, which hide text.
            case 0x200B, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0xFEFF, 0x180E, 0x115F, 0x1160, 0x3164, 0xFFA0,
                 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0xFFF9, 0xFFFA, 0xFFFB -> true;
            default -> isTag(codePoint);
        };
    }
}
