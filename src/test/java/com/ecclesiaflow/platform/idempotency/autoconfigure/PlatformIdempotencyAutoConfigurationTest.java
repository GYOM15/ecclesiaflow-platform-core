package com.ecclesiaflow.platform.idempotency.autoconfigure;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ApiContractLoader;
import com.ecclesiaflow.platform.contract.InvalidApiContractException;
import com.ecclesiaflow.platform.contract.autoconfigure.PlatformApiContractAutoConfiguration;
import com.ecclesiaflow.platform.error.autoconfigure.PlatformErrorAutoConfiguration;
import com.ecclesiaflow.platform.idempotency.CommentTable;
import com.ecclesiaflow.platform.idempotency.CommentTable.Comment;
import com.ecclesiaflow.platform.idempotency.CommentTable.NewComment;
import com.ecclesiaflow.platform.idempotency.CreationOutcome;
import com.ecclesiaflow.platform.idempotency.IdempotentCreator;
import com.ecclesiaflow.platform.idempotency.RecordingTransactionManager;
import com.ecclesiaflow.platform.idempotency.events.IdempotencyEventListener;
import com.ecclesiaflow.platform.idempotency.web.IdempotencyIndex;
import com.ecclesiaflow.platform.idempotency.web.IdempotencyKeyResolver;
import com.ecclesiaflow.platform.idempotency.web.IdempotentResponses;
import com.ecclesiaflow.platform.time.autoconfigure.PlatformClockAutoConfiguration;
import com.ecclesiaflow.platform.web.error.autoconfigure.PlatformWebErrorAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    record CommentBody(String body) {
    }

    record CommentView(UUID id, String body) {

        static CommentView of(Comment comment) {
            return new CommentView(comment.id(), comment.body());
        }
    }

    /** A delegate as the guide tells adopters to write it; the author header stands in for the token. */
    @RestController
    static class CommentController {

        private final IdempotentCreator creator;
        private final IdempotencyKeyResolver keys;
        private final CommentTable comments;

        CommentController(IdempotentCreator creator, IdempotencyKeyResolver keys, CommentTable comments) {
            this.creator = creator;
            this.keys = keys;
            this.comments = comments;
        }

        @PostMapping("/module/events/{eventId}/comments")
        ResponseEntity<CommentView> create(@PathVariable UUID eventId, @RequestHeader("X-Author") String author,
                                           @RequestBody CommentBody body) {
            CreationOutcome<Comment> outcome = creator.create(author, new NewComment(eventId, body.body()),
                    keys.current(), comments);
            return IdempotentResponses.created(outcome, CommentView::of);
        }

        @PostMapping("/module/signups")
        ResponseEntity<CommentView> signup(@RequestHeader("X-Author") String author, @RequestBody CommentBody body) {
            CreationOutcome<Comment> outcome = creator.create(author, new NewComment(null, body.body()),
                    keys.current(), comments);
            return IdempotentResponses.created(outcome, CommentView::of);
        }
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
        @DisplayName("a servlet module with a transaction manager gets the creator, the resolver and the listener")
        void servletModule() {
            web.withUserConfiguration(ModuleBeans.class).run(context -> {
                assertThat(context).hasSingleBean(IdempotentCreator.class)
                        .hasSingleBean(IdempotencyKeyResolver.class)
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
                        assertThat(context).doesNotHaveBean(IdempotencyIndex.class)
                                .doesNotHaveBean(IdempotencyKeyResolver.class);
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

    @Nested
    @DisplayName("a module that adopts it, end to end through Spring MVC and the platform's error advice")
    class Adopted {

        private static final String KEY = "5f0e9d8c-7b6a-4e3d-9c2b-1a0f9e8d7c6b";
        private static final String ALICE = "7d0c2b1e-6a43-4c55-9a1e-2f4b8c9d0e11";
        private static final String BOB = "1e2d3c4b-5a69-4788-9a0b-c1d2e3f40516";
        private static final String COMMENTS = "/module/events/0b6a8d4e-3c21-4f7a-8e90-5d1c2b3a4f56/comments";

        private final WebApplicationContextRunner module = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformApiContractAutoConfiguration.class, PlatformIdempotencyAutoConfiguration.class,
                        PlatformClockAutoConfiguration.class, PlatformErrorAutoConfiguration.class,
                        PlatformWebErrorAutoConfiguration.class, WebMvcAutoConfiguration.class,
                        DispatcherServletAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                        JacksonAutoConfiguration.class))
                .withUserConfiguration(ModuleBeans.class)
                .withUserConfiguration(Controllers.class);

        @Configuration(proxyBeanMethods = false)
        @Import(CommentController.class)
        static class Controllers {
        }

        private MockMvc mvc;
        private CommentTable table;

        private void start(WebApplicationContext context) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
            table = context.getBean(CommentTable.class);
        }

        private ResultActions send(String path, String author, String key, String body) throws Exception {
            var request = post(path).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Author", author)
                    .content("{\"body\":\"" + body + "\"}");
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
            return mvc.perform(request);
        }

        @Test
        @DisplayName("a retry replays: 201 then 200 with Idempotent-Replayed: true, the same comment, one row")
        void retryReplays() {
            module.run(context -> {
                start(context);
                send(COMMENTS, ALICE, KEY, "Hello")
                        .andExpect(status().isCreated())
                        .andExpect(header().doesNotExist("Idempotent-Replayed"));
                String id = table.rows().get(0).id().toString();

                send(COMMENTS, ALICE, KEY, "Hello")
                        .andExpect(status().isOk())
                        .andExpect(header().string("Idempotent-Replayed", "true"))
                        .andExpect(jsonPath("$.id").value(id))
                        .andExpect(jsonPath("$.body").value("Hello"));

                assertThat(table.rows()).hasSize(1);
            });
        }

        @Test
        @DisplayName("the same key with another body is 422 IDEMPOTENCY_CONFLICT in the shared error body")
        void otherBodyConflicts() {
            module.run(context -> {
                start(context);
                send(COMMENTS, ALICE, KEY, "Hello").andExpect(status().isCreated());

                send(COMMENTS, ALICE, KEY, "Goodbye")
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(header().doesNotExist("Idempotent-Replayed"))
                        .andExpect(jsonPath("$.status").value(422))
                        .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"))
                        .andExpect(jsonPath("$.path").value(COMMENTS));

                assertThat(table.rows()).hasSize(1);
            });
        }

        @Test
        @DisplayName("another author's key never reaches the first author's comment")
        void keyIsPerAuthor() {
            module.run(context -> {
                start(context);
                send(COMMENTS, ALICE, KEY, "Hello").andExpect(status().isCreated());
                send(COMMENTS, BOB, KEY, "Hello").andExpect(status().isCreated());

                assertThat(table.rows()).extracting(Comment::author).containsExactly(ALICE, BOB);
            });
        }

        @Test
        @DisplayName("without a key the creation runs as before, once per call")
        void withoutKey() {
            module.run(context -> {
                start(context);
                send(COMMENTS, ALICE, null, "Hello").andExpect(status().isCreated());
                send(COMMENTS, ALICE, null, "Hello").andExpect(status().isCreated());

                assertThat(table.rows()).hasSize(2);
            });
        }

        @Test
        @DisplayName("a malformed key is 400 and creates nothing")
        void malformedKey() {
            module.run(context -> {
                start(context);
                send(COMMENTS, ALICE, "1-2-3-4-5", "Hello")
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                        .andExpect(jsonPath("$.message").value("Parameter 'Idempotency-Key' has an invalid value."));

                assertThat(table.rows()).isEmpty();
            });
        }

        @Test
        @DisplayName("the header alone, on an operation without the extension, switches nothing on")
        void headerAloneDoesNothing() {
            module.run(context -> {
                start(context);
                send("/module/signups", ALICE, KEY, "Hello")
                        .andExpect(status().isCreated())
                        .andExpect(header().doesNotExist("Idempotent-Replayed"));
                send("/module/signups", ALICE, KEY, "Hello")
                        .andExpect(status().isCreated())
                        .andExpect(header().doesNotExist("Idempotent-Replayed"));

                assertThat(table.rows()).hasSize(2).allSatisfy(row -> assertThat(row.key()).isNull());
            });
        }
    }
}
