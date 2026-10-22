package com.ecclesiaflow.platform.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TermFilterLoaderTest {

    @TempDir
    Path directory;

    private Path write(String content) throws IOException {
        return Files.writeString(directory.resolve("terms.txt"), content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("reads one term per line, in French and English, accents and capitals included")
    void readsTerms() throws IOException {
        TermFilter filter = TermFilterLoader.load(write("crétin\nHECK\noh heck\n"));

        assertThat(filter.size()).isEqualTo(3);
        assertThat(filter.rejects("quel CRETIN")).isTrue();
        assertThat(filter.rejects("what the heck")).isTrue();
        assertThat(filter.rejects("oh, heck")).isTrue();
        assertThat(filter.rejects("bonjour")).isFalse();
    }

    @Test
    @DisplayName("skips blank lines and comments, and trims the terms")
    void skipsBlankAndComments() throws IOException {
        TermFilter filter = TermFilterLoader.load(write("""
                # French
                crétin

                   # indented comment
                  heck\t
                \t
                """));

        assertThat(filter.size()).isEqualTo(2);
        assertThat(filter.rejects("comment")).isFalse();
        assertThat(filter.rejects("indented")).isFalse();
        assertThat(filter.rejects("heck")).isTrue();
    }

    @Test
    @DisplayName("reads Windows line endings")
    void windowsLineEndings() throws IOException {
        TermFilter filter = TermFilterLoader.load(write("crétin\r\nheck\r\n"));

        assertThat(filter.size()).isEqualTo(2);
        assertThat(filter.rejects("heck")).isTrue();
    }

    @Test
    @DisplayName("ignores a byte order mark, even in front of a comment")
    void byteOrderMark() throws IOException {
        byte[] mark = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = "# French\nheck\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[mark.length + content.length];
        System.arraycopy(mark, 0, bytes, 0, mark.length);
        System.arraycopy(content, 0, bytes, mark.length, content.length);
        Path file = Files.write(directory.resolve("terms.txt"), bytes);

        TermFilter filter = TermFilterLoader.load(file);

        assertThat(filter.size()).isEqualTo(1);
        assertThat(filter.rejects("French")).isFalse();
        assertThat(filter.rejects("heck")).isTrue();
    }

    @Test
    @DisplayName("a mark alone on the first line is skipped")
    void byteOrderMarkAlone() throws IOException {
        byte[] bytes = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '\n', 'h', 'e', 'c', 'k', '\n'};

        TermFilter filter = TermFilterLoader.load(Files.write(directory.resolve("terms.txt"), bytes));

        assertThat(filter.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty file, or one of comments, is a filter with no term")
    void emptyList() throws IOException {
        assertThat(TermFilterLoader.load(write("")).size()).isZero();
        assertThat(TermFilterLoader.load(write("# nothing yet\n\n")).size()).isZero();
        assertThat(TermFilterLoader.load(write("anything")).rejects("heck")).isFalse();
    }

    @Test
    @DisplayName("a missing file stops the startup and names the file and the property")
    void missingFile() {
        Path missing = directory.resolve("absent.txt");

        assertThatThrownBy(() -> TermFilterLoader.load(missing))
                .isInstanceOf(InvalidTermListException.class)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(missing.toString())
                .hasMessageContaining("ecclesiaflow.moderation.terms-file")
                .hasMessageContaining("MODERATION_TERMS_FILE");
    }

    @Test
    @DisplayName("a directory is not a term list")
    void directoryIsNotAFile() {
        assertThatThrownBy(() -> TermFilterLoader.load(directory))
                .isInstanceOf(InvalidTermListException.class)
                .hasMessageContaining(directory.toString());
    }

    @Test
    @DisplayName("a file that is not UTF-8 is refused, not read with the wrong accents")
    void notUtf8() throws IOException {
        Path latin1 = Files.write(directory.resolve("terms.txt"), "crétin\n".getBytes(StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> TermFilterLoader.load(latin1))
                .isInstanceOf(InvalidTermListException.class)
                .hasMessageContaining("UTF-8")
                .hasMessageContaining(latin1.toString())
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a line with no letter or digit is refused with its number, without echoing it")
    void wordlessLine() throws IOException {
        Path file = write("heck\n\n  ***  \nidiot\n");

        assertThatThrownBy(() -> TermFilterLoader.load(file))
                .isInstanceOf(InvalidTermListException.class)
                .hasMessageContaining("line 3")
                .hasMessageContaining(file.toString())
                .hasMessageNotContaining("***");
    }
}
