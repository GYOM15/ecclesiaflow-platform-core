package com.ecclesiaflow.platform.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiContractLoaderTest {

    private static List<ContractOperation> read(String yaml) {
        return ApiContractLoader.read(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8), "test contract"));
    }

    private static ContractOperation only(String yaml) {
        List<ContractOperation> operations = read(yaml);
        assertThat(operations).hasSize(1);
        return operations.get(0);
    }

    @Nested
    @DisplayName("security")
    class Security {

        @Test
        @DisplayName("an operation's own security: [] makes it tokenless, whatever the root declares")
        void ownEmptySecurity() {
            ContractOperation signup = only("""
                    security:
                      - BearerAuth: []
                    paths:
                      /signup:
                        post:
                          security: []
                    """);

            assertThat(signup.tokenless()).isTrue();
        }

        @Test
        @DisplayName("an operation inherits the root security")
        void inheritsRoot() {
            List<ContractOperation> operations = read("""
                    security: []
                    paths:
                      /open:
                        get: {}
                      /closed:
                        get:
                          security:
                            - BearerAuth: []
                    """);

            assertThat(operations).extracting(ContractOperation::pathTemplate, ContractOperation::tokenless)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("/open", true),
                            org.assertj.core.groups.Tuple.tuple("/closed", false));
        }

        @Test
        @DisplayName("no security anywhere means a token is required, not that the operation is open")
        void undeclaredNeedsAToken() {
            assertThat(only("""
                    paths:
                      /members:
                        get: {}
                    """).tokenless()).isFalse();
        }

        @Test
        @DisplayName("an optional requirement {} is not security: []")
        void optionalRequirementIsNotTokenless() {
            assertThat(only("""
                    paths:
                      /members:
                        get:
                          security:
                            - {}
                    """).tokenless()).isFalse();
        }

        @Test
        @DisplayName("a security that is not a list is refused, at the root or on an operation")
        void securityMustBeAList() {
            assertThatThrownBy(() -> read("""
                    security: none
                    paths:
                      /a:
                        get: {}
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("the root");
            assertThatThrownBy(() -> read("""
                    paths:
                      /a:
                        get:
                          security:
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("GET /a");
        }
    }

    @Nested
    @DisplayName("operations")
    class Operations {

        @Test
        @DisplayName("reads every lower-case HTTP method of a path item, its operationId and its x-* extensions only")
        void readsMethodsAndExtensions() {
            List<ContractOperation> operations = read("""
                    paths:
                      /members/me:
                        summary: not an operation
                        x-path-level: ignored
                        get:
                          operationId: GetMyProfile
                          summary: ignored
                          x-ef-surface: member
                          x-ef-flags: [a, b]
                          x-ef-null: ~
                        Delete:
                          operationId: NotAnOperation
                        delete:
                          operationId: DeleteMyAccount
                    """);

            assertThat(operations).extracting(ContractOperation::method).containsExactly("GET", "DELETE");
            ContractOperation get = operations.get(0);
            assertThat(get.operationId()).isEqualTo("GetMyProfile");
            assertThat(get.label()).isEqualTo("GetMyProfile");
            assertThat(get.extensions()).containsOnlyKeys("x-ef-surface", "x-ef-flags", "x-ef-null");
            assertThat(get.extension("x-ef-surface")).contains("member");
            assertThat(get.extension("x-ef-flags")).contains(List.of("a", "b"));
            assertThat(get.extension("x-ef-null")).isEmpty();
            assertThat(get.extension("x-ef-absent")).isEmpty();
            assertThat(get.source()).isEqualTo("Byte array resource [test contract]");
        }

        @Test
        @DisplayName("what it reads cannot be altered afterwards")
        void extensionsAreImmutable() {
            ContractOperation get = only("""
                    paths:
                      /a:
                        get:
                          x-ef-list: [a]
                          x-ef-map: {k: v}
                    """);

            assertThatThrownBy(() -> get.extensions().put("x-other", 1))
                    .isInstanceOf(UnsupportedOperationException.class);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) get.extension("x-ef-list").orElseThrow();
            assertThatThrownBy(() -> list.add("b")).isInstanceOf(UnsupportedOperationException.class);
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) get.extension("x-ef-map").orElseThrow();
            assertThatThrownBy(() -> map.put("k", "w")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a contract without paths has no operations")
        void noPaths() {
            assertThat(read("openapi: 3.1.1\n")).isEmpty();
        }

        @Test
        @DisplayName("a path item may be a local reference")
        void pathItemReference() {
            ContractOperation aliased = only("""
                    paths:
                      /aliased:
                        $ref: '#/components/pathItems/Aliased'
                    components:
                      pathItems:
                        Aliased:
                          get:
                            operationId: Aliased.Get
                    """);

            assertThat(aliased.operationId()).isEqualTo("Aliased.Get");
            assertThat(aliased.pathTemplate()).isEqualTo("/aliased");
        }
    }

    @Nested
    @DisplayName("parameters")
    class Parameters {

        private static final String CONTRACT = """
                paths:
                  /members/{memberId}:
                    parameters:
                      - $ref: '#/components/parameters/MemberId'
                      - name: X-Trace
                        in: header
                        schema: {type: string}
                    delete:
                      parameters:
                        - $ref: '#/components/parameters/IdempotencyKey'
                        - name: X-Trace
                          in: header
                          required: true
                components:
                  parameters:
                    MemberId:
                      name: memberId
                      in: path
                      required: true
                      schema: {type: string, format: uuid}
                    IdempotencyKey:
                      $ref: '#/components/parameters/IdempotencyKeyTarget'
                    IdempotencyKeyTarget:
                      name: Idempotency-Key
                      in: header
                      required: false
                      schema:
                        $ref: '#/components/schemas/Key'
                  schemas:
                    Key: {type: string, format: uuid}
                """;

        @Test
        @DisplayName("path-item parameters are merged, the operation's own winning, references resolved")
        void mergedAndResolved() {
            ContractOperation delete = only(CONTRACT);

            assertThat(delete.parameters()).extracting(ContractParameter::name)
                    .containsExactlyInAnyOrder("Idempotency-Key", "X-Trace", "memberId");
            assertThat(delete.parameter("header", "x-trace")).get()
                    .extracting(ContractParameter::required).isEqualTo(true);
            ContractParameter key = delete.parameter("header", "idempotency-key").orElseThrow();
            assertThat(key.required()).isFalse();
            assertThat(key.schema()).containsEntry("type", "string").containsEntry("format", "uuid");
            assertThat(delete.parameter("path", "memberId")).get()
                    .extracting(ContractParameter::required).isEqualTo(true);
        }

        @Test
        @DisplayName("only header names compare case-insensitively")
        void nameComparison() {
            ContractParameter path = new ContractParameter("memberId", "path", true, null);

            assertThat(path.is("path", "memberId")).isTrue();
            assertThat(path.is("path", "memberid")).isFalse();
            assertThat(path.is("query", "memberId")).isFalse();
            assertThat(path.schema()).isEmpty();
        }

        @Test
        @DisplayName("a parameter without name and location, or a list that is not one, is refused")
        void malformedParameters() {
            assertThatThrownBy(() -> read("""
                    paths:
                      /a:
                        get:
                          parameters:
                            - in: query
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("name and in");
            assertThatThrownBy(() -> read("""
                    paths:
                      /a:
                        get:
                          parameters: {name: x}
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("not a list");
        }
    }

    @Nested
    @DisplayName("references")
    class References {

        @Test
        @DisplayName("a reference into a list is followed")
        void listIndex() {
            ContractOperation get = only("""
                    paths:
                      /a:
                        get:
                          parameters:
                            - $ref: '#/x-shared/0'
                    x-shared:
                      - {name: q, in: query}
                    """);

            assertThat(get.parameter("query", "q")).isPresent();
        }

        @Test
        @DisplayName("a reference to another file is refused: what is not read cannot be enforced")
        void externalReference() {
            assertThatThrownBy(() -> read("""
                    paths:
                      /a:
                        $ref: 'other.yaml#/paths/~1a'
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("only local");
        }

        @Test
        @DisplayName("a dangling reference, a bad list index or a cycle is refused")
        void brokenReferences() {
            assertThatThrownBy(() -> read("""
                    paths:
                      /a:
                        $ref: '#/components/pathItems/Missing'
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("does not exist");
            assertThatThrownBy(() -> read("""
                    paths:
                      /a:
                        get:
                          parameters:
                            - $ref: '#/x-shared/7'
                    x-shared: []
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("does not exist");
            assertThatThrownBy(() -> read("""
                    paths:
                      /a:
                        $ref: '#/components/pathItems/A'
                    components:
                      pathItems:
                        A: {$ref: '#/components/pathItems/B'}
                        B: {$ref: '#/components/pathItems/A'}
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("cycle");
        }

        @Test
        @DisplayName("a JSON pointer decodes ~1 and ~0")
        void escapedPointer() {
            ContractOperation get = only("""
                    paths:
                      /a:
                        $ref: '#/x-items/~1a~0b'
                    x-items:
                      /a~b:
                        get: {operationId: Escaped}
                    """);

            assertThat(get.operationId()).isEqualTo("Escaped");
        }
    }

    @Nested
    @DisplayName("malformed documents")
    class Malformed {

        @Test
        @DisplayName("a document, paths or operation that is not a mapping is refused")
        void notMappings() {
            assertThatThrownBy(() -> read("- a\n- b\n"))
                    .isInstanceOf(InvalidApiContractException.class).hasMessageContaining("not a mapping");
            assertThatThrownBy(() -> read("paths: []\n"))
                    .isInstanceOf(InvalidApiContractException.class).hasMessageContaining("paths is not a mapping");
            assertThatThrownBy(() -> read("""
                    paths:
                      /a:
                        get: nothing
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("GET /a");
        }

        @Test
        @DisplayName("a non-string path key or operationId is refused")
        void nonStrings() {
            assertThatThrownBy(() -> read("""
                    paths:
                      42:
                        get: {}
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("path key 42");
            assertThatThrownBy(() -> read("""
                    paths:
                      /a:
                        get: {operationId: 7}
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("operationId");
        }

        @Test
        @DisplayName("invalid YAML and duplicate keys are refused, not read halfway")
        void invalidYaml() {
            assertThatThrownBy(() -> read("paths: [unclosed\n"))
                    .isInstanceOf(InvalidApiContractException.class).hasMessageContaining("cannot be read");
            assertThatThrownBy(() -> read("""
                    paths:
                      /a:
                        get: {}
                      /a:
                        post: {}
                    """)).isInstanceOf(InvalidApiContractException.class).hasMessageContaining("cannot be read");
        }

        @Test
        @DisplayName("a YAML tag that would build a Java object is refused")
        void unsafeTag() {
            assertThatThrownBy(() -> read("paths: !!java.net.URL [\"http://example.com\"]\n"))
                    .isInstanceOf(InvalidApiContractException.class).hasMessageContaining("cannot be read");
        }

        @Test
        @DisplayName("an unreadable resource is refused with its cause")
        void unreadableResource() {
            Resource broken = new ByteArrayResource(new byte[0], "broken") {
                @Override
                public InputStream getInputStream() throws IOException {
                    throw new IOException("disk gone");
                }
            };

            assertThatThrownBy(() -> ApiContractLoader.read(broken))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasRootCauseMessage("disk gone");
        }
    }

    @Nested
    @DisplayName("locations")
    class Locations {

        private final ResourcePatternResolver classpath = new PathMatchingResourcePatternResolver();

        @Test
        @DisplayName("merges every file a pattern finds, and reads a file reached twice once")
        void mergesFiles() {
            ApiContract contract = ApiContractLoader.load(classpath, List.of(
                    "classpath*:contract/merge/*.yaml", "classpath:contract/merge/first.yaml", " "));

            assertThat(contract.operations()).extracting(ContractOperation::operationId)
                    .containsExactlyInAnyOrder("First.Get", "Second.Get");
        }

        @Test
        @DisplayName("a location that matches nothing fails, even next to one that does")
        void missingLocation() {
            assertThatThrownBy(() -> ApiContractLoader.load(classpath, List.of(
                    "classpath*:contract/merge/*.yaml", "classpath:contract/merge/misspelt.yaml")))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasMessageContaining("No OpenAPI contract found at classpath:contract/merge/misspelt.yaml");
        }

        @Test
        @DisplayName("no location at all fails")
        void noLocation() {
            assertThatThrownBy(() -> ApiContractLoader.load(classpath, List.of()))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasMessageContaining("ecclesiaflow.contract.locations");
            assertThatThrownBy(() -> ApiContractLoader.load(classpath, Arrays.asList(null, " ")))
                    .isInstanceOf(InvalidApiContractException.class);
            assertThatThrownBy(() -> ApiContractLoader.load(classpath, null))
                    .isInstanceOf(InvalidApiContractException.class);
        }

        @Test
        @DisplayName("a location the resolver cannot scan fails with its cause")
        void unscannableLocation() throws IOException {
            ResourcePatternResolver broken = mock(ResourcePatternResolver.class);
            when(broken.getResources(anyString())).thenThrow(new IOException("jar unreadable"));

            assertThatThrownBy(() -> ApiContractLoader.load(broken, List.of("classpath*:api/*.yaml")))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasRootCauseMessage("jar unreadable");
        }

        @Test
        @DisplayName("a resource without a URL is still read once")
        void resourceWithoutUrl() throws IOException {
            ResourcePatternResolver inMemory = mock(ResourcePatternResolver.class);
            Resource yaml = new ByteArrayResource("""
                    paths:
                      /a:
                        get: {operationId: InMemory}
                    """.getBytes(StandardCharsets.UTF_8), "in memory");
            when(inMemory.getResources(anyString())).thenReturn(new Resource[]{yaml, yaml});

            ApiContract contract = ApiContractLoader.load(inMemory, List.of("memory:*"));

            assertThat(contract.operations()).extracting(ContractOperation::operationId).containsExactly("InMemory");
        }
    }
}
