package com.ecclesiaflow.platform.security.surface;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SurfaceRouteVerifierTest {

    private static final ApiContract CONTRACT = ApiContract.of(List.of(
            new ContractOperation("GET", "/members/{memberId}", "Member.Get", false, Map.of(), List.of(), "test"),
            new ContractOperation("GET", "/members/me", "Me.Get", false, Map.of(), List.of(), "test"),
            new ContractOperation("POST", "/members", "Signup", true, Map.of(), List.of(), "test")));

    /** A chain open to anonymous callers on exactly these {@code METHOD /path} probes. */
    private static final class OpenOn implements WebInvocationPrivilegeEvaluator {

        private final Set<String> open;
        private final List<String> probes = new ArrayList<>();

        OpenOn(String... open) {
            this.open = Set.of(open);
        }

        @Override
        public boolean isAllowed(String uri, Authentication authentication) {
            return isAllowed("", uri, "GET", authentication);
        }

        @Override
        public boolean isAllowed(String contextPath, String uri, String method, Authentication authentication) {
            assertThat(authentication).isInstanceOf(AnonymousAuthenticationToken.class);
            probes.add(method + " " + uri);
            return open.contains(method + " " + uri);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(Stream<T> beans, T unique) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(beans);
        when(provider.getIfUnique()).thenReturn(unique);
        return provider;
    }

    private static SurfaceRouteVerifier verifier(WebInvocationPrivilegeEvaluator evaluator,
                                                 Stream<RequestMappingInfoHandlerMapping> handlerMappings,
                                                 Stream<AbstractUrlHandlerMapping> urlMappings) {
        return new SurfaceRouteVerifier(CONTRACT, provider(Stream.empty(), evaluator),
                provider(handlerMappings, null), provider(urlMappings, null));
    }

    private static Set<SurfaceRouteVerifier.Route> routes(SurfaceRouteVerifier.Route... routes) {
        return new LinkedHashSet<>(List.of(routes));
    }

    private static SurfaceRouteVerifier.Route route(String pattern, String... methods) {
        return new SurfaceRouteVerifier.Route(pattern, List.of(methods));
    }

    private static List<String> verify(OpenOn chain, Set<SurfaceRouteVerifier.Route> served) {
        return verifier(chain, Stream.empty(), Stream.empty()).verify(chain, served);
    }

    @Nested
    @DisplayName("contract operations")
    class ContractOperations {

        @Test
        @DisplayName("a chain that opens exactly the security: [] operations passes")
        void agreement() {
            assertThat(verify(new OpenOn("POST /members"), routes())).isEmpty();
        }

        @Test
        @DisplayName("a security: [] operation the chain closes is reported")
        void tokenlessClosed() {
            assertThat(verify(new OpenOn(), routes())).singleElement().asString()
                    .contains("Signup (POST /members) is security: []");
        }

        @Test
        @DisplayName("an operation that needs a token, open in the chain, is reported once")
        void tokenOperationOpen() {
            OpenOn chain = new OpenOn("POST /members", "GET /members/0");

            assertThat(verify(chain, routes(route("/members/{id}", "GET")))).singleElement().asString()
                    .contains("Member.Get (GET /members/{memberId}) requires a token");
        }

        @Test
        @DisplayName("each operation is probed with its variables replaced by one segment")
        void probes() {
            OpenOn chain = new OpenOn("POST /members");

            verify(chain, routes());

            assertThat(chain.probes).containsExactly("GET /members/0", "GET /members/me", "POST /members");
        }
    }

    @Nested
    @DisplayName("served routes")
    class ServedRoutes {

        @Test
        @DisplayName("a served route that reaches an operation needing a token, open in the chain, is reported")
        void reachesTokenOperation() {
            OpenOn chain = new OpenOn("POST /members", "GET /members/self");

            assertThat(verify(chain, routes(route("/members/self", "GET")))).singleElement().asString()
                    .contains("GET /members/self reaches Member.Get");
        }

        @Test
        @DisplayName("an open route outside the contract is reported unless it is technical")
        void outsideTheContract() {
            OpenOn chain = new OpenOn("POST /members", "GET /legacy/0", "GET /error", "GET /ws/0");

            List<String> violations = verify(chain, routes(route("/legacy/{id}", "GET"), route("/error"),
                    route("/ws/**")));

            assertThat(violations).singleElement().asString()
                    .contains("GET /legacy/{id} is open without a token");
        }

        @Test
        @DisplayName("a route for any method is probed with each common method")
        void anyMethod() {
            OpenOn chain = new OpenOn("POST /members", "DELETE /legacy");

            assertThat(verify(chain, routes(route("/legacy")))).singleElement().asString()
                    .startsWith("DELETE /legacy");
            assertThat(chain.probes).contains("GET /legacy", "POST /legacy", "PUT /legacy", "PATCH /legacy",
                    "DELETE /legacy");
        }

        @Test
        @DisplayName("a served route open as the security: [] operation it reaches passes")
        void tokenlessOperation() {
            OpenOn chain = new OpenOn("POST /members", "POST /members/");

            assertThat(verify(chain, routes(route("/members", "POST")))).isEmpty();
        }

        @Test
        @DisplayName("lists the routes of request mappings and URL mappings")
        void collectsRoutes() {
            RequestMappingInfoHandlerMapping handlers = mock(RequestMappingInfoHandlerMapping.class);
            when(handlers.getHandlerMethods()).thenReturn(Map.of(
                    RequestMappingInfo.paths("/members/{id}", "/people/{id}").methods(RequestMethod.GET).build(),
                    mock(HandlerMethod.class)));
            AbstractUrlHandlerMapping urls = mock(AbstractUrlHandlerMapping.class);
            when(urls.getHandlerMap()).thenReturn(Map.of("/ws/**", new Object()));

            Set<SurfaceRouteVerifier.Route> served = verifier(new OpenOn(), Stream.of(handlers), Stream.of(urls))
                    .servedRoutes();

            assertThat(served).containsExactlyInAnyOrder(route("/members/{id}", "GET"),
                    route("/people/{id}", "GET"), route("/ws/**"));
        }
    }

    @Nested
    @DisplayName("at startup")
    class AtStartup {

        @Test
        @DisplayName("a disagreement stops the module with every violation listed")
        void failsOnViolations() {
            SurfaceRouteVerifier verifier = verifier(new OpenOn(), Stream.empty(), Stream.empty());

            assertThatThrownBy(verifier::afterSingletonsInstantiated)
                    .isInstanceOfSatisfying(InvalidSurfaceConfigurationException.class, failure ->
                            assertThat(failure.violations()).singleElement().asString().contains("Signup"))
                    .hasMessageContaining("disagree");
        }

        @Test
        @DisplayName("an agreeing module starts")
        void passes() {
            verifier(new OpenOn("POST /members"), Stream.empty(), Stream.empty()).afterSingletonsInstantiated();
        }

        @Test
        @DisplayName("without Spring Security's privilege evaluator nothing can be checked, so the module stops")
        void noEvaluator() {
            SurfaceRouteVerifier verifier = verifier(null, Stream.empty(), Stream.empty());

            assertThatThrownBy(verifier::afterSingletonsInstantiated)
                    .isInstanceOfSatisfying(InvalidSurfaceConfigurationException.class, failure ->
                            assertThat(failure.violations()).hasSize(1))
                    .hasMessageContaining("WebInvocationPrivilegeEvaluator");
        }
    }

    @Nested
    @DisplayName("samplePath")
    class SamplePath {

        @Test
        @DisplayName("variables, captures and wildcards become one sample segment")
        void replaces() {
            assertThat(SurfaceRouteVerifier.samplePath("/a/{id}")).isEqualTo("/a/0");
            assertThat(SurfaceRouteVerifier.samplePath("/a/{id:[a-z]{2}}/b")).isEqualTo("/a/0/b");
            assertThat(SurfaceRouteVerifier.samplePath("/files/**")).isEqualTo("/files/0");
            assertThat(SurfaceRouteVerifier.samplePath("/a/*.json")).isEqualTo("/a/0.json");
            assertThat(SurfaceRouteVerifier.samplePath("/a/?x")).isEqualTo("/a/0x");
            assertThat(SurfaceRouteVerifier.samplePath("/{*rest}")).isEqualTo("/0");
            assertThat(SurfaceRouteVerifier.samplePath("/a}")).isEqualTo("/a}");
        }

        @Test
        @DisplayName("an empty or relative pattern still gives a path")
        void normalises() {
            assertThat(SurfaceRouteVerifier.samplePath("")).isEqualTo("/");
            assertThat(SurfaceRouteVerifier.samplePath(null)).isEqualTo("/");
            assertThat(SurfaceRouteVerifier.samplePath("a/b")).isEqualTo("/a/b");
        }
    }
}
