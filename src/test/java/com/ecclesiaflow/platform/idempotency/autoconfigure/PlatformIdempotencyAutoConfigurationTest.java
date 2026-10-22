package com.ecclesiaflow.platform.idempotency.autoconfigure;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ApiContractLoader;
import com.ecclesiaflow.platform.contract.InvalidApiContractException;
import com.ecclesiaflow.platform.contract.autoconfigure.PlatformApiContractAutoConfiguration;
import com.ecclesiaflow.platform.idempotency.CommentTable;
import com.ecclesiaflow.platform.idempotency.IdempotentCreator;
import com.ecclesiaflow.platform.idempotency.RecordingTransactionManager;
import com.ecclesiaflow.platform.idempotency.events.IdempotencyEventListener;
import com.ecclesiaflow.platform.idempotency.web.IdempotencyIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformIdempotencyAutoConfigurationTest {

    /** Shaped like church's contract once it adopts the primitive: one marked creation, one legacy keyed one. */
    static final String CONTRACT = """
            openapi: 3.0.3
            info: {title: module, version: '1'}
            paths:
              /module/events/{eventId}/comments:
                post:
                  operationId: EventComment.Create
                  x-ef-idempotency: required
                  parameters:
                    - name: eventId
                      in: path
                      required: true
                      schema: {type: string, format: uuid}
                    - $ref: '#/components/parameters/IdempotencyKey'
              /module/signups:
                post:
                  operationId: Signup.Create
                  description: Legacy idempotency, no new use
                  parameters:
                    - name: Idempotency-Key
                      in: header
                      required: true
                      schema: {type: string}
            components:
              parameters:
                IdempotencyKey:
                  name: Idempotency-Key
                  in: header
                  required: false
                  schema: {type: string, format: uuid}
            """;

    static ApiContract contract(String yaml) {
        return ApiContract.of(ApiContractLoader.read(
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8), "module.yaml")));
    }

    @Configuration(proxyBeanMethods = false)
    static class ModuleBeans {

        @Bean
        ApiContract apiContract() {
            return contract(CONTRACT);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        CommentTable commentTable() {
            return new CommentTable();
        }
    }

    private static final AutoConfigurations PLATFORM = AutoConfigurations.of(
            PlatformApiContractAutoConfiguration.class, PlatformIdempotencyAutoConfiguration.class);

    @Nested
    @DisplayName("wiring")
    class Wiring {

        private final WebApplicationContextRunner web = new WebApplicationContextRunner()
                .withConfiguration(PLATFORM);

        @Test
        @DisplayName("a servlet module with a transaction manager gets the creator, the listener and its contract checked")
        void servletModule() {
            web.withUserConfiguration(ModuleBeans.class).run(context -> {
                assertThat(context).hasSingleBean(IdempotentCreator.class)
                        .hasSingleBean(IdempotencyEventListener.class);
                assertThat(context.getBean(IdempotencyIndex.class).count()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("a module whose contract marks an operation without the optional header does not start")
        void startupRefusal() {
            String withoutHeader = CONTRACT.replace("- $ref: '#/components/parameters/IdempotencyKey'",
                    "- {name: X-Trace, in: header, schema: {type: string}}");
            web.withBean(ApiContract.class, () -> contract(withoutHeader)).run(context ->
                    assertThat(context).getFailure().rootCause()
                            .isInstanceOf(InvalidApiContractException.class)
                            .hasMessageContainingAll("EventComment.Create", "module.yaml",
                                    "x-ef-idempotency: required", "an optional Idempotency-Key header", "found none"));
        }

        @Test
        @DisplayName("a module whose contract marks the header required does not start either")
        void requiredHeaderRefused() {
            String requiredHeader = CONTRACT.replace("required: false", "required: true");
            web.withBean(ApiContract.class, () -> contract(requiredHeader)).run(context ->
                    assertThat(context).getFailure().rootCause()
                            .isInstanceOf(InvalidApiContractException.class)
                            .hasMessageContaining("found it required"));
        }

        @Test
        @DisplayName("without a transaction manager the contract is still checked, and no creator exists")
        void noTransactionManager() {
            web.withBean(ApiContract.class, () -> contract(CONTRACT)).run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(IdempotentCreator.class);
                assertThat(context).hasSingleBean(IdempotencyIndex.class);
            });
        }

        @Test
        @DisplayName("outside a servlet application only the creator is offered")
        void nonWeb() {
            new ApplicationContextRunner().withConfiguration(PLATFORM)
                    .withBean(PlatformTransactionManager.class, RecordingTransactionManager::new)
                    .run(context -> {
                        assertThat(context).hasSingleBean(IdempotentCreator.class);
                        assertThat(context).doesNotHaveBean(IdempotencyIndex.class);
                    });
        }

        @Test
        @DisplayName("a module's own creator is kept")
        void moduleCreator() {
            IdempotentCreator own = new IdempotentCreator(new RecordingTransactionManager(), event -> { });
            web.withUserConfiguration(ModuleBeans.class)
                    .withBean("ownCreator", IdempotentCreator.class, () -> own)
                    .run(context -> assertThat(context.getBean(IdempotentCreator.class)).isSameAs(own));
        }
    }
}
