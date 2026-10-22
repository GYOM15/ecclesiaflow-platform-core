package com.ecclesiaflow.platform.security.surface;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import com.ecclesiaflow.platform.security.surface.events.SurfaceAuthorizationEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.server.RequestPath;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;

/**
 * Applies the surface of the operation a token-bearing request reaches. Registered right after Spring
 * Security's chain, so the token is already authenticated and the module's own rules have run; a refusal is
 * thrown as an {@link AccessDeniedException}, which the chain's exception handling answers with the module's
 * usual 403. Requests without a {@link JwtAuthenticationToken} are not ruled.
 */
public class SurfaceAuthorizationFilter extends OncePerRequestFilter {

    /** Exported to Prometheus as {@code ef_surface_refused_total}. */
    static final String REFUSED_METRIC = "ef.surface.refused";

    static final String CONSOLE_CALLER = "console";
    static final String MODERATION_CALLER = "moderation";
    static final String OTHER_CALLER = "other";

    private final SurfaceIndex surfaces;
    private final SurfaceMode mode;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;

    /** A {@code null} registry counts nothing; decisions are unchanged. */
    public SurfaceAuthorizationFilter(SurfaceIndex surfaces, SurfaceMode mode, ApplicationEventPublisher events,
                                      MeterRegistry meterRegistry) {
        this.surfaces = surfaces;
        this.mode = mode;
        this.events = events;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        String path = RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication().value();
        SurfaceIndex.Resolution resolution = surfaces.resolve(request.getMethod(), path);
        if (!resolution.ruled() || resolution.surface().permits(token.getAuthorities())) {
            chain.doFilter(request, response);
            return;
        }

        String caller = callerOf(token.getAuthorities());
        events.publishEvent(new SurfaceAuthorizationEvents.Refused(
                request.getMethod(),
                resolution.operation() == null ? null : resolution.operation().label(),
                resolution.operation() == null ? path : null,
                resolution.surface(), mode, caller,
                token.getToken().getClaimAsString("azp"),
                SecurityMaskingUtils.maskId(token.getToken().getSubject())));
        count(resolution.surface(), caller);
        if (mode == SurfaceMode.ENFORCE) {
            throw new AccessDeniedException("The token does not open this operation's surface");
        }
        chain.doFilter(request, response);
    }

    // Tells web refusals, which hold the switch to enforce back, from mobile ones, which are expected.
    static String callerOf(Collection<? extends GrantedAuthority> authorities) {
        if (Surface.CONSOLE.permits(authorities)) {
            return CONSOLE_CALLER;
        }
        boolean moderation = authorities.stream()
                .anyMatch(authority -> Surface.MODERATION_ACT.equals(authority.getAuthority()));
        return moderation ? MODERATION_CALLER : OTHER_CALLER;
    }

    private void count(Surface surface, String caller) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(REFUSED_METRIC)
                .description("Token-bearing requests refused by the operation's surface (counted in log-only too)")
                .tag("surface", surface.tag())
                .tag("caller", caller)
                .tag("mode", mode.tag())
                .register(meterRegistry)
                .increment();
    }
}
