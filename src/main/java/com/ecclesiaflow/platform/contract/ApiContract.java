package com.ecclesiaflow.platform.contract;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The module's OpenAPI operations, indexed by HTTP method and path template. Built once at startup by
 * {@link ApiContractLoader}, immutable and safe to share between threads.
 *
 * <p>A request resolves the way Spring MVC dispatches it, so a rule keyed on the operation sees the handler
 * that will run: templates are {@link PathPattern}s, the request path is decoded segment by segment with
 * matrix parameters dropped, the most specific template wins, a trailing slash matches nothing, and
 * {@code HEAD} falls back to {@code GET}. The {@code servers} base paths are ignored: templates are matched as
 * written against the path within the application.</p>
 */
public final class ApiContract {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    private final List<ContractOperation> operations;
    private final Map<String, List<Indexed>> byMethod;

    private record Indexed(PathPattern pattern, ContractOperation operation) {
    }

    private ApiContract(List<ContractOperation> operations, Map<String, List<Indexed>> byMethod) {
        this.operations = operations;
        this.byMethod = byMethod;
    }

    /**
     * Indexes operations already read, e.g. a contract assembled by hand in a test.
     *
     * @throws InvalidApiContractException when a template is not a valid path, or two operations share a method
     *                                     and a template that differ only by variable names
     */
    public static ApiContract of(Collection<ContractOperation> operations) {
        Map<String, List<Indexed>> byMethod = new HashMap<>();
        Map<String, ContractOperation> seen = new HashMap<>();
        for (ContractOperation operation : operations) {
            PathPattern pattern = parse(operation);
            String key = operation.method() + " " + shapeOf(operation.pathTemplate());
            ContractOperation previous = seen.putIfAbsent(key, operation);
            if (previous != null) {
                throw InvalidApiContractException.duplicateOperation(operation.method(), operation.pathTemplate(),
                        previous.source(), operation.source());
            }
            byMethod.computeIfAbsent(operation.method(), method -> new ArrayList<>())
                    .add(new Indexed(pattern, operation));
        }
        Map<String, List<Indexed>> frozen = new LinkedHashMap<>();
        byMethod.forEach((method, indexed) -> frozen.put(method, List.copyOf(indexed)));
        return new ApiContract(List.copyOf(operations), Map.copyOf(frozen));
    }

    /** Every operation, in the order the contract files declare them. */
    public List<ContractOperation> operations() {
        return operations;
    }

    /**
     * The operation a request reaches.
     *
     * @param method the request's HTTP method, any case
     * @param path   the path within the application as received, percent-encoded, without the query string
     * @return empty when no operation of the contract serves that method and path
     */
    public Optional<ContractOperation> find(String method, String path) {
        if (method == null || path == null) {
            return Optional.empty();
        }
        PathContainer container = PathContainer.parsePath(path);
        String upper = method.toUpperCase(Locale.ROOT);
        Optional<ContractOperation> found = match(upper, container);
        // Spring MVC answers HEAD with the GET handler.
        return found.isEmpty() && "HEAD".equals(upper) ? match("GET", container) : found;
    }

    private Optional<ContractOperation> match(String method, PathContainer path) {
        return byMethod.getOrDefault(method, List.of()).stream()
                .filter(indexed -> indexed.pattern().matches(path))
                .min(Comparator.comparing(Indexed::pattern, PathPattern.SPECIFICITY_COMPARATOR))
                .map(Indexed::operation);
    }

    private static PathPattern parse(ContractOperation operation) {
        String template = operation.pathTemplate();
        if (!template.startsWith("/")) {
            throw InvalidApiContractException.malformed(operation.source(),
                    "the path " + template + " does not start with /");
        }
        try {
            return PARSER.parse(template);
        } catch (PatternParseException e) {
            throw InvalidApiContractException.malformed(operation.source(),
                    "the path " + template + " is not a valid template (" + e.getMessage() + ")");
        }
    }

    // /a/{id} and /a/{memberId} serve the same requests: OpenAPI calls them identical.
    private static String shapeOf(String template) {
        return template.replaceAll("\\{[^}/]*}", "{}");
    }
}
