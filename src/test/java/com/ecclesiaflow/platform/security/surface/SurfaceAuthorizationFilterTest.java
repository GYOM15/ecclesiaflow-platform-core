package com.ecclesiaflow.platform.security.surface;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.security.surface.events.SurfaceAuthorizationEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SurfaceAuthorizationFilterTest {

    private static final SurfaceIndex SURFACES = new SurfaceIndex(ApiContract.of(List.of(
            new ContractOperation("GET", "/members/me", "Me.Get", false, Map.of(Surface.EXTENSION, "member"),
                    List.of(), "test"),
            new ContractOperation("GET", "/members", "Members.List", false, Map.of(), List.of(), "test"),
            new ContractOperation("POST", "/members", "Signup", true, Map.of(), List.of(), "test"))));

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private FilterChain chain;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String azp, String... authorities) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none")
                .subject("3f2c1d4e-1111-4222-8333-944455556666")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (azp != null) {
            jwt.claim("azp", azp);
        }
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt.build(), AuthorityUtils.createAuthorityList(authorities)));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private SurfaceAuthorizationFilter filter(SurfaceMode mode) {
        return new SurfaceAuthorizationFilter(SURFACES, mode, events, meters);
    }

    private double refused(String surface, String caller, String mode) {
        var counter = meters.find("ef.surface.refused").tags("surface", surface, "caller", caller, "mode", mode)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Nested
    @DisplayName("enforce")
    class Enforce {

        @Test
        @DisplayName("refuses a token that does not open the surface, publishes and counts the refusal")
        void refuses() throws Exception {
            authenticate("ecclesiaflow-mobile", "SCOPE_openid");
            MockHttpServletRequest request = request("GET", "/members");

            assertThatThrownBy(() -> filter(SurfaceMode.ENFORCE).doFilter(request, response, chain))
                    .isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(chain);
            ArgumentCaptor<SurfaceAuthorizationEvents.Refused> event =
                    ArgumentCaptor.forClass(SurfaceAuthorizationEvents.Refused.class);
            verify(events).publishEvent(event.capture());
            assertThat(event.getValue()).isEqualTo(new SurfaceAuthorizationEvents.Refused("GET", "Members.List",
                    null, Surface.CONSOLE, SurfaceMode.ENFORCE, "other", "ecclesiaflow-mobile", "3f2c1d4e********"));
            assertThat(refused("console", "other", "enforce")).isEqualTo(1);
        }

        @Test
        @DisplayName("lets a token through when it opens the surface")
        void permits() throws Exception {
            authenticate("ecclesiaflow-web", Surface.SURFACE_FULL);
            MockHttpServletRequest request = request("GET", "/members");

            filter(SurfaceMode.ENFORCE).doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("outside the contract, the event carries the raw path for the listener to mask")
        void outsideTheContract() {
            authenticate(null, Surface.MODERATION_ACT);
            MockHttpServletRequest request = request("GET", "/unlisted/42");

            assertThatThrownBy(() -> filter(SurfaceMode.ENFORCE).doFilter(request, response, chain))
                    .isInstanceOf(AccessDeniedException.class);

            ArgumentCaptor<SurfaceAuthorizationEvents.Refused> event =
                    ArgumentCaptor.forClass(SurfaceAuthorizationEvents.Refused.class);
            verify(events).publishEvent(event.capture());
            assertThat(event.getValue().operation()).isNull();
            assertThat(event.getValue().path()).isEqualTo("/unlisted/42");
            assertThat(event.getValue().caller()).isEqualTo("moderation");
            assertThat(event.getValue().azp()).isNull();
        }

        @Test
        @DisplayName("matches the path within the application, past the context path")
        void contextPath() throws Exception {
            authenticate("ecclesiaflow-mobile");
            MockHttpServletRequest request = request("GET", "/api/members/me");
            request.setContextPath("/api");

            filter(SurfaceMode.ENFORCE).doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("without a meter registry the decision is unchanged")
        void withoutMetrics() {
            authenticate("ecclesiaflow-mobile");
            SurfaceAuthorizationFilter uncounted = new SurfaceAuthorizationFilter(SURFACES, SurfaceMode.ENFORCE,
                    events, null);

            assertThatThrownBy(() -> uncounted.doFilter(request("GET", "/members"), response, chain))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("log-only")
    class LogOnly {

        @Test
        @DisplayName("publishes and counts the refusal, then lets the request go on")
        void letsThrough() throws Exception {
            authenticate("ecclesiaflow-mobile");
            MockHttpServletRequest request = request("GET", "/members");

            filter(SurfaceMode.LOG_ONLY).doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            verify(events).publishEvent(any(SurfaceAuthorizationEvents.Refused.class));
            assertThat(refused("console", "other", "log-only")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("not ruled")
    class NotRuled {

        @Test
        @DisplayName("a request without a token, or authenticated otherwise, is left to the module's chain")
        void noToken() throws Exception {
            MockHttpServletRequest request = request("GET", "/members");
            filter(SurfaceMode.ENFORCE).doFilter(request, response, chain);

            SecurityContextHolder.getContext().setAuthentication(
                    new TestingAuthenticationToken("user", "password", "SCOPE_openid"));
            MockHttpServletRequest other = request("GET", "/members");
            filter(SurfaceMode.ENFORCE).doFilter(other, response, chain);

            verify(chain).doFilter(request, response);
            verify(chain).doFilter(other, response);
            verify(events, never()).publishEvent(any());
        }

        @Test
        @DisplayName("a security: [] operation and a technical path are left alone, token or not")
        void tokenlessAndTechnical() throws Exception {
            authenticate("ecclesiaflow-mobile");
            MockHttpServletRequest signup = request("POST", "/members");
            MockHttpServletRequest health = request("GET", "/actuator/health");

            filter(SurfaceMode.ENFORCE).doFilter(signup, response, chain);
            filter(SurfaceMode.ENFORCE).doFilter(health, response, chain);

            verify(chain).doFilter(signup, response);
            verify(chain).doFilter(health, response);
            verifyNoInteractions(events);
        }
    }

    @Test
    @DisplayName("the caller tag tells console, moderation and other tokens apart")
    void callerTags() {
        assertThat(SurfaceAuthorizationFilter.callerOf(AuthorityUtils.createAuthorityList(Surface.SURFACE_FULL,
                Surface.MODERATION_ACT))).isEqualTo("console");
        assertThat(SurfaceAuthorizationFilter.callerOf(AuthorityUtils.createAuthorityList(Surface.MODERATION_ACT)))
                .isEqualTo("moderation");
        assertThat(SurfaceAuthorizationFilter.callerOf(AuthorityUtils.createAuthorityList("SCOPE_openid")))
                .isEqualTo("other");
    }
}
