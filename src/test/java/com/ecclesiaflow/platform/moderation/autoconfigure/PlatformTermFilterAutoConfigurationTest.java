package com.ecclesiaflow.platform.moderation.autoconfigure;

import com.ecclesiaflow.platform.moderation.InvalidTermListException;
import com.ecclesiaflow.platform.moderation.TermFilter;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTermFilterAutoConfigurationTest {

    private static final String PROPERTY = "ecclesiaflow.moderation.terms-file=";

    @TempDir
    Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformTermFilterAutoConfiguration.class));

    private Path termList(String content) throws IOException {
        return Files.writeString(directory.resolve("terms.txt"), content);
    }

    @Test
    @DisplayName("is registered for Spring Boot to find")
    void registered() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();

        assertThat(candidates).contains(PlatformTermFilterAutoConfiguration.class.getName());
    }

    @Test
    @DisplayName("a module that sets no term list gets no filter and is not touched")
    void unsetCreatesNothing() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TermFilter.class));
    }

    @Test
    @DisplayName("builds the filter from the configured file")
    void buildsTheFilter() throws IOException {
        Path file = termList("crétin\nheck\n");

        runner.withPropertyValues(PROPERTY + file).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TermFilter.class);
            TermFilter filter = context.getBean(TermFilter.class);
            assertThat(filter.size()).isEqualTo(2);
            assertThat(filter.rejects("quel CRETIN")).isTrue();
            assertThat(filter.rejects("checkpoint")).isFalse();
        });
    }

    @Test
    @DisplayName("announces the number of terms, never the terms or the path")
    void announcesTheCount() throws IOException {
        Path file = termList("crétin\nheck\n");

        try (LogCaptor logs = LogCaptor.forClass(PlatformTermFilterAutoConfiguration.class)) {
            runner.withPropertyValues(PROPERTY + file).run(context -> assertThat(context).hasNotFailed());

            assertThat(logs.getInfoLogs()).singleElement().asString()
                    .isEqualTo("MODERATION: term filter active, 2 term(s) loaded");
            assertThat(logs.getWarnLogs()).isEmpty();
        }
    }

    @Test
    @DisplayName("warns when the list is empty, since nothing is then rejected")
    void warnsOnAnEmptyList() throws IOException {
        Path file = termList("# to fill\n");

        try (LogCaptor logs = LogCaptor.forClass(PlatformTermFilterAutoConfiguration.class)) {
            runner.withPropertyValues(PROPERTY + file).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(TermFilter.class).size()).isZero();
            });

            assertThat(logs.getWarnLogs()).singleElement().asString()
                    .isEqualTo("MODERATION: ⚠ the term list is empty, no text will be rejected");
            assertThat(logs.getInfoLogs()).isEmpty();
        }
    }

    @Test
    @DisplayName("a term list that does not exist stops the startup")
    void missingFileStopsTheStartup() {
        Path missing = directory.resolve("absent.txt");

        runner.withPropertyValues(PROPERTY + missing).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(InvalidTermListException.class);
            assertThat(context.getStartupFailure().getMessage()).contains("TermFilter");
            assertThat(rootMessage(context.getStartupFailure()))
                    .contains(missing.toString())
                    .contains("ecclesiaflow.moderation.terms-file")
                    .contains("MODERATION_TERMS_FILE");
        });
    }

    @Test
    @DisplayName("a blank path, as an unset MODERATION_TERMS_FILE bridged with an empty default gives, stops the startup")
    void blankPathStopsTheStartup() {
        runner.withPropertyValues(PROPERTY).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(InvalidTermListException.class);
            assertThat(rootMessage(context.getStartupFailure())).contains("ecclesiaflow.moderation.terms-file is blank");
        });
        runner.withPropertyValues(PROPERTY + "   ").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a malformed line stops the startup")
    void malformedLineStopsTheStartup() throws IOException {
        Path file = termList("heck\n***\n");

        runner.withPropertyValues(PROPERTY + file).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("line 2");
        });
    }

    @Test
    @DisplayName("tolerates blanks around the configured path")
    void trimsThePath() throws IOException {
        Path file = termList("heck\n");

        runner.withPropertyValues(PROPERTY + "  " + file + "  ")
                .run(context -> assertThat(context.getBean(TermFilter.class).size()).isEqualTo(1));
    }

    @Test
    @DisplayName("a filter the module declares replaces ours, and its file is not read")
    void moduleFilterWins() {
        runner.withUserConfiguration(OwnFilter.class)
                .withPropertyValues(PROPERTY + directory.resolve("absent.txt"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TermFilter.class)).isSameAs(OwnFilter.FILTER);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnFilter {

        static final TermFilter FILTER = new TermFilter(List.of("heck"));

        @Bean
        TermFilter ownTermFilter() {
            return FILTER;
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
