package com.ecclesiaflow.platform.security.reauth.code.web;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.contract.ContractParameter;
import com.ecclesiaflow.platform.contract.InvalidApiContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReauthLinkedIndexTest {

    private static final Map<String, Object> UUID_SCHEMA = Map.of("type", "string", "format", "uuid");
    private static final Map<String, Object> CODE_SCHEMA = Map.of("type", "string", "pattern", "^[0-9]{6}$");

    private static ContractParameter challenge() {
        return new ContractParameter("Reauth-Challenge", "header", false, UUID_SCHEMA);
    }

    private static ContractParameter code() {
        return new ContractParameter("Reauth-Code", "header", false, CODE_SCHEMA);
    }

    private static ContractOperation operation(String operationId, Map<String, Object> extensions, boolean tokenless,
                                               List<ContractParameter> parameters) {
        return new ContractOperation("POST", "/billing/me/" + operationId, operationId, tokenless, extensions,
                parameters, "billing.yaml");
    }

    private static ContractOperation marked(String operationId) {
        return operation(operationId, Map.of("x-ef-reauth", "recent", "x-ef-reauth-linked", "email-code"), false,
                List.of(challenge(), code()));
    }

    private static ReauthLinkedIndex index(ContractOperation... operations) {
        return new ReauthLinkedIndex(ApiContract.of(List.of(operations)));
    }

    @Test
    @DisplayName("names the marked operations, and those only")
    void labels() {
        ContractOperation linkOnly = operation("Members.DeleteMyAccount", Map.of("x-ef-reauth", "recent"), false,
                List.of());

        assertThat(index(marked("Subscription.Cancel"), marked("PaymentMethod.Remove"), linkOnly).labels())
                .containsExactly("PaymentMethod.Remove", "Subscription.Cancel");
        assertThat(index(linkOnly).labels()).isEmpty();
    }

    @Test
    @DisplayName("compares header names case-insensitively, as HTTP does")
    void headerCase() {
        ContractOperation lowerCase = operation("Subscription.Cancel",
                Map.of("x-ef-reauth", "recent", "x-ef-reauth-linked", "email-code"), false,
                List.of(new ContractParameter("reauth-challenge", "header", false, UUID_SCHEMA),
                        new ContractParameter("REAUTH-CODE", "header", false, CODE_SCHEMA)));

        assertThat(index(lowerCase).labels()).containsExactly("Subscription.Cancel");
    }

    @Nested
    @DisplayName("stops the module at startup on a mark")
    class Refusals {

        private void assertRefused(ContractOperation operation, String... expected) {
            assertThatThrownBy(() -> index(operation))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasMessageContainingAll("Subscription.Cancel", "billing.yaml", "x-ef-reauth-linked")
                    .hasMessageContainingAll(expected);
        }

        private ContractOperation with(Map<String, Object> extensions, List<ContractParameter> parameters) {
            return operation("Subscription.Cancel", extensions, false, parameters);
        }

        @Test
        @DisplayName("of another value")
        void otherValue() {
            assertRefused(with(Map.of("x-ef-reauth", "recent", "x-ef-reauth-linked", "totp"),
                    List.of(challenge(), code())), "totp", "email-code");
        }

        @Test
        @DisplayName("without x-ef-reauth: recent beside it")
        void notRecent() {
            assertRefused(with(Map.of("x-ef-reauth-linked", "email-code"), List.of(challenge(), code())),
                    "x-ef-reauth: recent");
        }

        @Test
        @DisplayName("on an operation without a token")
        void tokenless() {
            assertThatThrownBy(() -> index(operation("Subscription.Cancel",
                    Map.of("x-ef-reauth", "recent", "x-ef-reauth-linked", "email-code"), true,
                    List.of(challenge(), code())))).isInstanceOf(InvalidApiContractException.class)
                    .hasMessageContaining("security: []");
        }

        @Test
        @DisplayName("without either header")
        void missingHeader() {
            Map<String, Object> marks = Map.of("x-ef-reauth", "recent", "x-ef-reauth-linked", "email-code");
            assertRefused(with(marks, List.of(code())), "Reauth-Challenge", "found none");
            assertRefused(with(marks, List.of(challenge())), "Reauth-Code", "found none");
        }

        @Test
        @DisplayName("with a required header, which would refuse every first request")
        void requiredHeader() {
            assertRefused(with(Map.of("x-ef-reauth", "recent", "x-ef-reauth-linked", "email-code"),
                    List.of(new ContractParameter("Reauth-Challenge", "header", true, UUID_SCHEMA), code())),
                    "found it required");
        }

        @Test
        @DisplayName("with a header whose schema is not the frozen one")
        void wrongSchema() {
            Map<String, Object> marks = Map.of("x-ef-reauth", "recent", "x-ef-reauth-linked", "email-code");
            Map<String, Object> looseCode = new HashMap<>(CODE_SCHEMA);
            looseCode.put("pattern", "^[0-9]+$");
            List<ContractParameter> parameters = new ArrayList<>(List.of(challenge()));
            parameters.add(new ContractParameter("Reauth-Code", "header", false, looseCode));

            assertRefused(with(marks, parameters), "found the schema");
            assertRefused(with(marks, List.of(new ContractParameter("Reauth-Challenge", "header", false,
                    Map.of("type", "string")), code())), "format: uuid");
            assertRefused(with(marks, List.of(new ContractParameter("Reauth-Challenge", "header", false,
                    Map.of("type", "integer", "format", "uuid")), code())), "found the schema");
        }
    }
}
