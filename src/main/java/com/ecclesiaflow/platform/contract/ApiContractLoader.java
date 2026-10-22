package com.ecclesiaflow.platform.contract;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads OpenAPI 3.x contracts (YAML or JSON) into an {@link ApiContract}. Only what the platform enforces is
 * read: each operation's method, path, {@code operationId}, effective {@code security}, {@code x-*} extensions
 * and parameters. Local {@code $ref}s ({@code #/...}) are followed for path items, parameters and parameter
 * schemas; any other reference fails, since an unread declaration cannot be enforced.
 */
public final class ApiContractLoader {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private static final String EXTENSION_PREFIX = "x-";
    private static final String REF = "$ref";
    private static final String LOCAL_REF_PREFIX = "#/";

    // A reference to a reference is legal; a cycle is not, and this bounds it.
    private static final int MAX_REF_HOPS = 16;

    // SnakeYAML refuses documents above 3 MB by default; the largest contract is a tenth of that today.
    private static final int CODE_POINT_LIMIT = 32 * 1024 * 1024;

    private ApiContractLoader() {
    }

    /**
     * Resolves every location and reads the operations of all the files found. A location may be a pattern
     * ({@code classpath*:api/*.yaml}); each one must match at least one file, so a misspelt location fails
     * rather than silently dropping operations. A file reached by two locations is read once.
     *
     * @throws InvalidApiContractException when no location is given, a location matches nothing, a file
     *                                     cannot be read or parsed, or two operations collide
     */
    public static ApiContract load(ResourcePatternResolver resolver, Collection<String> locations) {
        if (locations == null || locations.stream().allMatch(location -> location == null || location.isBlank())) {
            throw InvalidApiContractException.noLocation();
        }
        Map<String, Resource> resources = new LinkedHashMap<>();
        for (String location : locations) {
            if (location == null || location.isBlank()) {
                continue;
            }
            List<Resource> found = resolve(resolver, location);
            if (found.isEmpty()) {
                throw InvalidApiContractException.missing(location);
            }
            for (Resource resource : found) {
                resources.putIfAbsent(identityOf(resource), resource);
            }
        }
        List<ContractOperation> operations = new ArrayList<>();
        for (Resource resource : resources.values()) {
            operations.addAll(read(resource));
        }
        return ApiContract.of(operations);
    }

    /** Reads one contract file. */
    public static List<ContractOperation> read(Resource resource) {
        String source = resource.getDescription();
        Object document;
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            document = yaml().load(reader);
        } catch (IOException | YAMLException e) {
            throw InvalidApiContractException.unreadable(source, e);
        }
        return new DocumentReader(source, document).operations();
    }

    private static List<Resource> resolve(ResourcePatternResolver resolver, String location) {
        try {
            List<Resource> existing = new ArrayList<>();
            for (Resource resource : resolver.getResources(location.trim())) {
                if (resource.exists() && resource.isReadable()) {
                    existing.add(resource);
                }
            }
            return existing;
        } catch (IOException e) {
            throw InvalidApiContractException.unreadable(location, e);
        }
    }

    private static String identityOf(Resource resource) {
        try {
            return resource.getURL().toExternalForm();
        } catch (IOException e) {
            return resource.getDescription();
        }
    }

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(CODE_POINT_LIMIT);
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options));
    }

    /** Copies YAML values into unmodifiable maps and lists, so an operation cannot be altered once indexed. */
    @SuppressWarnings("unchecked")
    static <T> T immutable(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(key, immutable(entry)));
            return (T) Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(entry -> copy.add(immutable(entry)));
            return (T) Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static final class DocumentReader {

        private final String source;
        private final Map<?, ?> root;

        DocumentReader(String source, Object document) {
            this.source = source;
            if (!(document instanceof Map<?, ?> map)) {
                throw InvalidApiContractException.malformed(source, "the document is not a mapping");
            }
            this.root = map;
        }

        List<ContractOperation> operations() {
            Object paths = root.get("paths");
            if (paths == null) {
                return List.of();
            }
            Map<?, ?> pathItems = mapping(paths, "paths");
            List<?> rootSecurity = securityOf(root, "the root");
            List<ContractOperation> operations = new ArrayList<>();
            for (Map.Entry<?, ?> entry : pathItems.entrySet()) {
                if (!(entry.getKey() instanceof String template)) {
                    throw InvalidApiContractException.malformed(source, "the path key " + entry.getKey()
                            + " is not a string");
                }
                Map<?, ?> pathItem = mapping(dereference(entry.getValue(), template), template);
                List<ContractParameter> shared = parametersOf(pathItem, template);
                for (Map.Entry<?, ?> field : pathItem.entrySet()) {
                    if (field.getKey() instanceof String key && HTTP_METHODS.contains(key)) {
                        String where = key.toUpperCase(Locale.ROOT) + " " + template;
                        operations.add(operation(key, template, mapping(field.getValue(), where), shared,
                                rootSecurity));
                    }
                }
            }
            return operations;
        }

        private ContractOperation operation(String method, String template, Map<?, ?> operation,
                                            List<ContractParameter> shared, List<?> rootSecurity) {
            String where = method.toUpperCase(Locale.ROOT) + " " + template;
            Object operationId = operation.get("operationId");
            if (operationId != null && !(operationId instanceof String)) {
                throw InvalidApiContractException.malformed(source, where + " has a non-string operationId");
            }
            List<?> security = operation.containsKey("security") ? securityOf(operation, where) : rootSecurity;
            Map<String, Object> extensions = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : operation.entrySet()) {
                if (field.getKey() instanceof String key && key.startsWith(EXTENSION_PREFIX)) {
                    extensions.put(key, field.getValue());
                }
            }
            return new ContractOperation(method, template, (String) operationId,
                    security != null && security.isEmpty(), extensions,
                    merge(shared, parametersOf(operation, where)), source);
        }

        private List<?> securityOf(Map<?, ?> owner, String where) {
            if (!owner.containsKey("security")) {
                return null;
            }
            if (!(owner.get("security") instanceof List<?> requirements)) {
                throw InvalidApiContractException.malformed(source, where + " has a security that is not a list");
            }
            return requirements;
        }

        private List<ContractParameter> parametersOf(Map<?, ?> owner, String where) {
            Object declared = owner.get("parameters");
            if (declared == null) {
                return List.of();
            }
            if (!(declared instanceof List<?> list)) {
                throw InvalidApiContractException.malformed(source, where + " has parameters that are not a list");
            }
            List<ContractParameter> parameters = new ArrayList<>();
            for (Object item : list) {
                Map<?, ?> parameter = mapping(dereference(item, where), where + " parameter");
                if (!(parameter.get("name") instanceof String name) || !(parameter.get("in") instanceof String in)) {
                    throw InvalidApiContractException.malformed(source, where + " has a parameter without a "
                            + "string name and in");
                }
                Object schema = parameter.get("schema");
                Map<?, ?> resolvedSchema = schema == null ? Map.of() : mapping(dereference(schema, where), where
                        + " parameter " + name + " schema");
                parameters.add(new ContractParameter(name, in, Boolean.TRUE.equals(parameter.get("required")),
                        stringKeyed(resolvedSchema)));
            }
            return parameters;
        }

        // OpenAPI: an operation parameter overrides the path-item one with the same name and location.
        private static List<ContractParameter> merge(List<ContractParameter> shared, List<ContractParameter> own) {
            List<ContractParameter> merged = new ArrayList<>(own);
            for (ContractParameter parameter : shared) {
                if (own.stream().noneMatch(candidate -> candidate.is(parameter.in(), parameter.name()))) {
                    merged.add(parameter);
                }
            }
            return merged;
        }

        private Object dereference(Object value, String where) {
            Object current = value;
            Set<String> visited = new LinkedHashSet<>();
            for (int hop = 0; hop < MAX_REF_HOPS; hop++) {
                if (!(current instanceof Map<?, ?> map) || !map.containsKey(REF)) {
                    return current;
                }
                if (!(map.get(REF) instanceof String ref) || !ref.startsWith(LOCAL_REF_PREFIX)) {
                    throw InvalidApiContractException.malformed(source, where + " uses the reference "
                            + map.get(REF) + "; only local references (#/...) are supported");
                }
                if (!visited.add(ref)) {
                    break;
                }
                current = pointer(ref, where);
            }
            throw InvalidApiContractException.malformed(source, where + " has a reference cycle or chain longer "
                    + "than " + MAX_REF_HOPS + " hops");
        }

        // RFC 6901: ~1 stands for / and ~0 for ~, decoded in that order.
        private Object pointer(String ref, String where) {
            Object node = root;
            for (String token : ref.substring(LOCAL_REF_PREFIX.length()).split("/", -1)) {
                String key = token.replace("~1", "/").replace("~0", "~");
                node = switch (node) {
                    case Map<?, ?> map -> map.get(key);
                    case List<?> list when key.matches("\\d{1,9}") && Integer.parseInt(key) < list.size() ->
                            list.get(Integer.parseInt(key));
                    default -> null;
                };
                if (node == null) {
                    throw InvalidApiContractException.malformed(source, where + " references " + ref
                            + ", which does not exist");
                }
            }
            return node;
        }

        private Map<?, ?> mapping(Object value, String where) {
            if (!(value instanceof Map<?, ?> map)) {
                throw InvalidApiContractException.malformed(source, where + " is not a mapping");
            }
            return map;
        }

        private static Map<String, Object> stringKeyed(Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
    }
}
