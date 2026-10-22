package com.ecclesiaflow.platform.idempotency.web;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.contract.ContractParameter;
import com.ecclesiaflow.platform.contract.InvalidApiContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ecclesiaflow.platform.idempotency.web.IdempotencyTestContracts.UUID_SCHEMA;
import static com.ecclesiaflow.platform.idempotency.web.IdempotencyTestContracts.legacy;
import static com.ecclesiaflow.platform.idempotency.web.IdempotencyTestContracts.marked;
import static com.ecclesiaflow.platform.idempotency.web.IdempotencyTestContracts.operation;
import static com.ecclesiaflow.platform.idempotency.web.IdempotencyTestContracts.optionalKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyIndexTest {

    private static IdempotencyIndex index(ContractOperation... operations) {
        return new IdempotencyIndex(ApiContract.of(List.of(operations)));
    }

    private static void assertRefused(ContractOperation operation, String... expected) {
        assertThatThrownBy(() -> index(operation))
                .isInstanceOf(InvalidApiContractException.class)
                .hasMessageContainingAll("Comment.Create", "test.yaml", "x-ef-idempotency")
                .hasMessageContainingAll(expected);
    }

    @Nested
    @DisplayName("only the extension switches the primitive on")
    class Activation {

        private final IdempotencyIndex index = index(
                marked("/events/{eventId}/comments", "Comment.Create"),
                legacy("/signups", "Signup.Create"),
                operation("GET", "/events/{eventId}/comments", "Comment.List", false, null, List.of()));

        @Test
        @DisplayName("a marked operation is found by the requests it serves")
        void markedOperation() {
            assertThat(index.find("POST", "/events/42/comments"))
                    .get().extracting(ContractOperation::operationId).isEqualTo("Comment.Create");
            assertThat(index.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("an operation that declares the header without the extension is not, nor a read of the "
                + "same path")
        void headerAlone() {
            assertThat(index.find("POST", "/signups")).isEmpty();
            assertThat(index.find("GET", "/events/42/comments")).isEmpty();
        }

        @Test
        @DisplayName("a path outside the contract is not")
        void outside() {
            assertThat(index.find("POST", "/elsewhere")).isEmpty();
        }

        @Test
        @DisplayName("the header compares case-insensitively, as HTTP does")
        void headerCase() {
            ContractOperation lowerCase = operation("POST", "/comments", "Comment.Create", false, "required",
                    List.of(new ContractParameter("idempotency-key", "header", false, UUID_SCHEMA)));

            assertThat(index(lowerCase).count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a misdeclared mark stops the module at startup")
    class Refusals {

        @Test
        @DisplayName("a value other than required")
        void otherValue() {
            assertRefused(operation("POST", "/comments", "Comment.Create", false, true, List.of(optionalKey())),
                    "x-ef-idempotency: true", "expected required");
            assertRefused(operation("POST", "/comments", "Comment.Create", false, "optional", List.of(optionalKey())),
                    "expected required");
        }

        @Test
        @DisplayName("on anything but a POST creation")
        void notPost() {
            assertRefused(operation("PUT", "/comments/{id}", "Comment.Create", false, "required",
                    List.of(optionalKey())), "POST creation only, found on PUT");
        }

        @Test
        @DisplayName("on an operation without a token, whose key no author scopes")
        void tokenless() {
            assertRefused(operation("POST", "/comments", "Comment.Create", true, "required", List.of(optionalKey())),
                    "takes a token");
        }

        @Test
        @DisplayName("without the Idempotency-Key header, or with it elsewhere than in the headers")
        void missingHeader() {
            assertRefused(operation("POST", "/comments", "Comment.Create", false, "required", List.of()),
                    "an optional Idempotency-Key header", "found none");
            assertRefused(operation("POST", "/comments", "Comment.Create", false, "required",
                            List.of(new ContractParameter(IdempotencyHeaders.KEY, "query", false, UUID_SCHEMA))),
                    "found none");
        }

        @Test
        @DisplayName("with the header required, which would refuse the web and every installed app")
        void requiredHeader() {
            assertRefused(operation("POST", "/comments", "Comment.Create", false, "required",
                            List.of(new ContractParameter(IdempotencyHeaders.KEY, "header", true, UUID_SCHEMA))),
                    "found it required");
        }

        @Test
        @DisplayName("with a header that is not a string of format uuid")
        void schema() {
            assertRefused(operation("POST", "/comments", "Comment.Create", false, "required",
                            List.of(new ContractParameter(IdempotencyHeaders.KEY, "header", false,
                                    Map.of("type", "string")))),
                    "found the schema");
            assertRefused(operation("POST", "/comments", "Comment.Create", false, "required",
                            List.of(new ContractParameter(IdempotencyHeaders.KEY, "header", false,
                                    Map.of("type", "integer", "format", "uuid")))),
                    "found the schema");
        }
    }
}
