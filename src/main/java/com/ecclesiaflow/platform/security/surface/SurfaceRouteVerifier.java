package com.ecclesiaflow.platform.security.surface;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Refuses to start a module whose security chain and contract disagree on what is open without a token, in
 * both directions: every {@code security: []} operation must be open to an anonymous caller, and every route
 * open to one, served by the module or declared in its contract, must be a {@code security: []} operation or a
 * {@link TechnicalPaths technical path}.
 *
 * <p>"Open" is asked of the chain itself, through Spring Security's {@link WebInvocationPrivilegeEvaluator}
 * with an anonymous caller, so every matcher type and {@code permitAll}, {@code anonymous()} or no rule at all
 * count alike. A route is probed with each path variable or wildcard replaced by one segment.</p>
 */
public class SurfaceRouteVerifier implements SmartInitializingSingleton {

    static final String SAMPLE_SEGMENT = "0";

    private static final List<String> ANY_METHOD = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private static final Authentication ANONYMOUS = new AnonymousAuthenticationToken(
            "surface-route-check", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private final ApiContract contract;
    private final ObjectProvider<WebInvocationPrivilegeEvaluator> privileges;
    private final ObjectProvider<RequestMappingInfoHandlerMapping> handlerMethodMappings;
    private final ObjectProvider<AbstractUrlHandlerMapping> urlMappings;

    public SurfaceRouteVerifier(ApiContract contract,
                                ObjectProvider<WebInvocationPrivilegeEvaluator> privileges,
                                ObjectProvider<RequestMappingInfoHandlerMapping> handlerMethodMappings,
                                ObjectProvider<AbstractUrlHandlerMapping> urlMappings) {
        this.contract = contract;
        this.privileges = privileges;
        this.handlerMethodMappings = handlerMethodMappings;
        this.urlMappings = urlMappings;
    }

    @Override
    public void afterSingletonsInstantiated() {
        WebInvocationPrivilegeEvaluator evaluator = privileges.getIfUnique();
        if (evaluator == null) {
            throw InvalidSurfaceConfigurationException.noPrivilegeEvaluator();
        }
        List<String> violations = verify(evaluator, servedRoutes());
        if (!violations.isEmpty()) {
            throw InvalidSurfaceConfigurationException.mismatches(violations);
        }
    }

    /** @return one line per disagreement, empty when the chain and the contract agree */
    List<String> verify(WebInvocationPrivilegeEvaluator evaluator, Set<Route> served) {
        Set<String> violations = new LinkedHashSet<>();
        Set<String> probed = new HashSet<>();
        for (ContractOperation operation : contract.operations()) {
            String sample = samplePath(operation.pathTemplate());
            probed.add(operation.method() + " " + sample);
            boolean open = openWithoutToken(evaluator, operation.method(), sample);
            if (operation.tokenless() && !open) {
                violations.add(operation.label() + " (" + operation.method() + " " + operation.pathTemplate()
                        + ") is security: [] in the contract but the security chain requires a token");
            } else if (!operation.tokenless() && open) {
                violations.add(operation.label() + " (" + operation.method() + " " + operation.pathTemplate()
                        + ") requires a token in the contract but the security chain lets it through without one");
            }
        }
        for (Route route : served) {
            String sample = samplePath(route.pattern());
            for (String method : route.methods().isEmpty() ? ANY_METHOD : route.methods()) {
                if (!probed.add(method + " " + sample) || !openWithoutToken(evaluator, method, sample)) {
                    continue;
                }
                Optional<ContractOperation> operation = contract.find(method, sample);
                if (operation.isPresent()) {
                    if (!operation.get().tokenless()) {
                        violations.add(method + " " + route.pattern() + " reaches " + operation.get().label()
                                + ", which requires a token in the contract, but the security chain lets it "
                                + "through without one");
                    }
                } else if (!TechnicalPaths.matches(sample)) {
                    violations.add(method + " " + route.pattern() + " is open without a token but is neither a "
                            + "security: [] operation of the contract nor a technical path");
                }
            }
        }
        return List.copyOf(violations);
    }

    private static boolean openWithoutToken(WebInvocationPrivilegeEvaluator evaluator, String method, String path) {
        return evaluator.isAllowed("", path, method, ANONYMOUS);
    }

    Set<Route> servedRoutes() {
        Set<Route> routes = new LinkedHashSet<>();
        handlerMethodMappings.orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
                .forEach(info -> routesOf(info).forEach(routes::add));
        urlMappings.orderedStream()
                .flatMap(mapping -> mapping.getHandlerMap().keySet().stream())
                .forEach(pattern -> routes.add(new Route(pattern, List.of())));
        return routes;
    }

    private static Stream<Route> routesOf(RequestMappingInfo info) {
        List<String> methods = info.getMethodsCondition().getMethods().stream()
                .map(RequestMethod::name)
                .toList();
        return info.getPatternValues().stream().map(pattern -> new Route(pattern, methods));
    }

    /** One concrete path a pattern matches: each variable, wildcard or capture becomes {@value #SAMPLE_SEGMENT}. */
    static String samplePath(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "/";
        }
        StringBuilder sample = new StringBuilder(pattern.length());
        int depth = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '{') {
                if (depth++ == 0) {
                    sample.append(SAMPLE_SEGMENT);
                }
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                appendLiteral(sample, pattern, i, c);
            }
        }
        String path = sample.toString();
        return path.startsWith("/") ? path : "/" + path;
    }

    private static void appendLiteral(StringBuilder sample, String pattern, int index, char c) {
        if (c == '*') {
            if (index == 0 || pattern.charAt(index - 1) != '*') {
                sample.append(SAMPLE_SEGMENT);
            }
        } else if (c == '?') {
            sample.append(SAMPLE_SEGMENT);
        } else {
            sample.append(c);
        }
    }

    /**
     * A pattern the module serves.
     *
     * @param methods the methods it is restricted to; empty when it answers any method
     */
    record Route(String pattern, List<String> methods) {

        Route {
            methods = List.copyOf(methods);
        }
    }
}
