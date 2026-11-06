package com.ecclesiaflow.platform.web.text;

/**
 * What a text sent to the API may hold. Invisible characters that only hide content (zero-width spaces, word joiners,
 * fillers, Unicode tags outside an emoji flag) are dropped, so a pasted text still goes through; characters that
 * change how a text is read or processed (controls, bidirectional overrides and isolates, unpaired surrogates,
 * noncharacters, line and paragraph separators) refuse the request. Joiners and directional marks stay: emoji
 * sequences and right-to-left scripts need them.
 */
public final class TextInputGuard {

    private static final int BLACK_FLAG = 0x1F3F4;
    private static final int CANCEL_TAG = 0xE007F;

    private TextInputGuard() {
    }

    /** The text without its hidden characters, or the text itself when it holds none. */
    public static String clean(String text) throws UnsafeTextException {
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
            if (isRefused(codePoint)) {
                throw new UnsafeTextException();
            }
            if (isHidden(codePoint)) {
                if (kept == null) {
                    kept = new StringBuilder(text.length()).append(text, 0, index);
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

    private static boolean isHidden(int codePoint) {
        return switch (codePoint) {
            // Zero-width space, word joiner and the invisible math operators, byte order mark, Mongolian vowel
            // separator, Hangul fillers.
            case 0x200B, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0xFEFF, 0x180E, 0x115F, 0x1160, 0x3164, 0xFFA0 -> true;
            default -> isTag(codePoint);
        };
    }

    private static boolean isRefused(int codePoint) {
        if (codePoint < 0x20) {
            return codePoint != '\t' && codePoint != '\n' && codePoint != '\r';
        }
        if (codePoint >= 0x7F && codePoint <= 0x9F) {
            return true;
        }
        if (Character.isSurrogate((char) codePoint) && codePoint <= 0xFFFF) {
            return true;
        }
        if ((codePoint >= 0xFDD0 && codePoint <= 0xFDEF) || (codePoint & 0xFFFE) == 0xFFFE) {
            return true;
        }
        return switch (codePoint) {
            // Embeddings, overrides and isolates reorder what is displayed (Trojan Source); the interlinear annotation
            // marks hide text; the line and paragraph separators break lines where scripts and logs do not expect it.
            case 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0xFFF9, 0xFFFA, 0xFFFB,
                 0x2028, 0x2029 -> true;
            default -> false;
        };
    }
}
