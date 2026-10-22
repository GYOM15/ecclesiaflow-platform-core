package com.ecclesiaflow.platform.security.surface;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * The paths a module serves outside its contract, exempt from the surface rule and allowed to be open without
 * a token: the chat socket, the error page, the actuator and the API documentation. The list is closed; a new
 * entry is a platform change, so an open route cannot be added module by module without review.
 */
public final class TechnicalPaths {

    public static final List<String> PATTERNS = List.of(
            "/ws/**", "/error", "/actuator/**", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**");

    private static final List<PathPattern> PARSED = PATTERNS.stream()
            .map(PathPatternParser.defaultInstance::parse)
            .toList();

    private TechnicalPaths() {
    }

    /** @param path the path within the application, as received */
    public static boolean matches(String path) {
        if (path == null) {
            return false;
        }
        PathContainer container = PathContainer.parsePath(path);
        return PARSED.stream().anyMatch(pattern -> pattern.matches(container));
    }
}
