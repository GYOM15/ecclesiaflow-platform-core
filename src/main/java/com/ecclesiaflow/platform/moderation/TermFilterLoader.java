package com.ecclesiaflow.platform.moderation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the term list the deploy mounts: UTF-8, one term per line, blank lines skipped, a line starting with
 * {@code #} a comment. A term may hold several words.
 */
public final class TermFilterLoader {

    private static final int BYTE_ORDER_MARK = 0xFEFF;
    private static final String COMMENT = "#";

    private TermFilterLoader() {
    }

    public static TermFilter load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw InvalidTermListException.notAFile(file);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw InvalidTermListException.unreadable(file, e);
        }
        List<String> terms = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = (index == 0 ? withoutByteOrderMark(lines.get(index)) : lines.get(index)).strip();
            if (line.isEmpty() || line.startsWith(COMMENT)) {
                continue;
            }
            if (TermNormalizer.words(line).isEmpty()) {
                throw InvalidTermListException.noWord(file, index + 1);
            }
            terms.add(line);
        }
        return new TermFilter(terms);
    }

    // An editor's mark would otherwise hide a comment on the first line behind a character.
    private static String withoutByteOrderMark(String line) {
        return !line.isEmpty() && line.charAt(0) == BYTE_ORDER_MARK ? line.substring(1) : line;
    }
}
