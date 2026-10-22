package com.ecclesiaflow.platform.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiContractTest {

    private static ContractOperation operation(String method, String template, String operationId) {
        return new ContractOperation(method, template, operationId, false, Map.of(), List.of(), "test");
    }

    private static Optional<String> idOf(ApiContract contract, String method, String path) {
        return contract.find(method, path).map(ContractOperation::operationId);
    }

    @Nested
    @DisplayName("find")
    class Find {

        private final ApiContract contract = ApiContract.of(List.of(
                operation("get", "/members/{memberId}", "Member.Get"),
                operation("get", "/members/me", "Me.Get"),
                operation("get", "/members/{memberId}/photo.{format}", "Photo.Get"),
                operation("get", "/members/{memberId}/{section}", "Section.Get"),
                operation("get", "/members/{memberId}/photo", "PhotoBare.Get"),
                operation("delete", "/members/{memberId}", "Member.Delete"),
                operation("head", "/probe", "Probe.Head")));

        @Test
        @DisplayName("a literal segment beats a variable, the way Spring MVC picks the handler")
        void literalWins() {
            assertThat(idOf(contract, "GET", "/members/me")).contains("Me.Get");
            assertThat(idOf(contract, "GET", "/members/42")).contains("Member.Get");
            assertThat(idOf(contract, "GET", "/members/42/photo")).contains("PhotoBare.Get");
            assertThat(idOf(contract, "GET", "/members/42/photo.png")).contains("Photo.Get");
            assertThat(idOf(contract, "GET", "/members/42/history")).contains("Section.Get");
        }

        @Test
        @DisplayName("the method selects the operation, in any case; an unknown one finds nothing")
        void methodMatters() {
            assertThat(idOf(contract, "delete", "/members/42")).contains("Member.Delete");
            assertThat(idOf(contract, "PATCH", "/members/42")).isEmpty();
            assertThat(idOf(contract, "DELETE", "/members/me")).contains("Member.Delete");
        }

        @Test
        @DisplayName("HEAD falls back to GET unless the contract declares it")
        void headFallsBackToGet() {
            assertThat(idOf(contract, "HEAD", "/members/me")).contains("Me.Get");
            assertThat(idOf(contract, "HEAD", "/probe")).contains("Probe.Head");
            assertThat(idOf(contract, "HEAD", "/nowhere")).isEmpty();
        }

        @Test
        @DisplayName("segments are decoded and matrix parameters dropped before matching, as Spring MVC does")
        void decodesLikeSpringMvc() {
            assertThat(idOf(contract, "GET", "/members/%6De")).contains("Me.Get");
            assertThat(idOf(contract, "GET", "/members/me;jsessionid=1")).contains("Me.Get");
        }

        @Test
        @DisplayName("a trailing slash, an extra segment or a case change matches nothing")
        void strictPaths() {
            assertThat(idOf(contract, "GET", "/members/me/")).isEmpty();
            assertThat(idOf(contract, "GET", "/members/42/photo/large/x")).isEmpty();
            assertThat(idOf(contract, "GET", "/Members/me")).isEmpty();
            assertThat(idOf(contract, "GET", "/")).isEmpty();
        }

        @Test
        @DisplayName("no method or no path finds nothing")
        void nulls() {
            assertThat(contract.find(null, "/members/me")).isEmpty();
            assertThat(contract.find("GET", null)).isEmpty();
        }

        @Test
        @DisplayName("keeps every operation in declaration order")
        void operations() {
            assertThat(contract.operations()).extracting(ContractOperation::operationId)
                    .startsWith("Member.Get", "Me.Get").hasSize(7);
        }
    }

    @Nested
    @DisplayName("indexing")
    class Indexing {

        @Test
        @DisplayName("two templates that differ only by variable names are the same operation")
        void duplicateTemplates() {
            assertThatThrownBy(() -> ApiContract.of(List.of(
                    operation("GET", "/members/{id}", "A"),
                    operation("GET", "/members/{memberId}", "B"))))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasMessageContaining("GET /members/{memberId} is declared twice");
        }

        @Test
        @DisplayName("the same template under two methods is two operations")
        void sameTemplateTwoMethods() {
            ApiContract contract = ApiContract.of(List.of(
                    operation("GET", "/members/{id}", "A"),
                    operation("PUT", "/members/{id}", "B")));

            assertThat(contract.operations()).hasSize(2);
        }

        @Test
        @DisplayName("a template that is not a path is refused")
        void invalidTemplates() {
            assertThatThrownBy(() -> ApiContract.of(List.of(operation("GET", "members", "A"))))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasMessageContaining("does not start with /");
            assertThatThrownBy(() -> ApiContract.of(List.of(operation("GET", "/members/{id", "A"))))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasMessageContaining("not a valid template");
        }

        @Test
        @DisplayName("an operation without operationId is labelled by its method and template")
        void label() {
            assertThat(operation("get", "/a/{b}", null).label()).isEqualTo("GET /a/{b}");
        }

        @Test
        @DisplayName("an operation built without extensions or parameters has empty ones")
        void emptyDefaults() {
            ContractOperation bare = new ContractOperation("get", "/a", null, false, null, null, "test");

            assertThat(bare.extensions()).isEmpty();
            assertThat(bare.parameters()).isEmpty();
            assertThat(bare.parameter("query", "q")).isEmpty();
        }
    }
}
