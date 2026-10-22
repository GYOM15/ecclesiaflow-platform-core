package com.ecclesiaflow.platform.contract.autoconfigure;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.contract.InvalidApiContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformApiContractAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformApiContractAutoConfiguration.class));

    @Test
    @DisplayName("reads the files every module keeps under api/ when nothing is configured")
    void defaultLocation(@TempDir Path classpathRoot) throws Exception {
        Files.createDirectories(classpathRoot.resolve("api"));
        Files.writeString(classpathRoot.resolve("api/module.yaml"), """
                paths:
                  /module/me:
                    get: {operationId: Me.Get}
                """);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classpathRoot.toUri().toURL()},
                getClass().getClassLoader())) {
            runner.withClassLoader(loader).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ApiContract.class).operations())
                        .extracting(ContractOperation::operationId).containsExactly("Me.Get");
            });
        }
    }

    @Test
    @DisplayName("a module without a contract does not start, and the error names where it looked")
    void missingContract() {
        runner.run(context -> assertThat(context).getFailure()
                .rootCause()
                .isInstanceOf(InvalidApiContractException.class)
                .hasMessageContaining("classpath*:api/*.yaml"));
    }

    @Test
    @DisplayName("ecclesiaflow.contract.locations points at contracts kept elsewhere")
    void configuredLocations() {
        runner.withPropertyValues("ecclesiaflow.contract.locations=classpath:contract/merge/first.yaml,"
                        + "classpath:contract/merge/second.yaml")
                .run(context -> assertThat(context.getBean(ApiContract.class).operations())
                        .extracting(ContractOperation::operationId).containsExactly("First.Get", "Second.Get"));
    }

    @Test
    @DisplayName("a contract bean of the module's own is kept")
    void moduleBeanWins() {
        runner.withUserConfiguration(OwnContract.class)
                .run(context -> assertThat(context.getBean(ApiContract.class).operations()).isEmpty());
    }

    @Test
    @DisplayName("a non-web application reads no contract")
    void nonWeb() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformApiContractAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ApiContract.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnContract {

        @Bean
        ApiContract apiContract() {
            return ApiContract.of(List.of());
        }
    }
}
