# ecclesiaflow-platform-core

Shared platform library for EcclesiaFlow backend modules. Three concerns:

1. **Server-to-server authentication on gRPC** (s2s) — each module obtains a
   JWT from Keycloak via the `client_credentials` flow, attaches it as a Bearer
   token in gRPC metadata, and the receiving side validates the token and
   enforces the required scope (generic `ef:s2s` floor + per-method ceiling via
   `@S2sScopeRequired`).
2. **Web security helpers** — `KeycloakJwtConverter` extracts roles from a
   Keycloak JWT (direct claim, `realm_access`, `resource_access`) and produces
   a Spring `JwtAuthenticationToken`.
3. **Logging helpers** — `SecurityMaskingUtils` masks PII (emails, JWTs, IDs)
   and infrastructure details (URLs, hosts) before they reach a log line.

- **Artifact**: `com.ecclesiaflow:ecclesiaflow-platform-core:0.3.0-SNAPSHOT`
- **Java**: 21
- **Spring Boot**: 3.5.5 (auto-configuration via starter pattern)
- **Renamed in 0.3.0** from `ecclesiaflow-platform-rpc` to reflect the broader
  scope. Existing `com.ecclesiaflow.platform.rpc.*` packages are unchanged for
  backward compatibility.

## What this gives you

- A `S2sTokenProvider` that fetches and caches a JWT, refreshing it
  proactively before expiry.
- A `S2sAuthClientInterceptor` you plug on every outgoing `ManagedChannel`.
- A `S2sAuthServerInterceptor` you plug on every gRPC `Server`. It validates
  the token against the Keycloak JWKS endpoint and enforces a generic
  `ef:s2s` scope on every call.

Per-method scope enforcement (e.g. `@S2sScopeRequired("ef:members:write")`)
is enforced via `S2sScopeRegistry` since `0.2.0`.

## Configuration

In any consumer's `application.properties`:

```properties
ecclesiaflow.platform.rpc.s2s.client-id=ecclesiaflow-backend
ecclesiaflow.platform.rpc.s2s.client-secret=${KEYCLOAK_BACKEND_CLIENT_SECRET}
ecclesiaflow.platform.rpc.s2s.token-url=${KEYCLOAK_ISSUER_URI}/protocol/openid-connect/token
ecclesiaflow.platform.rpc.s2s.jwks-uri=${KEYCLOAK_JWKS_URI}
ecclesiaflow.platform.rpc.s2s.issuer=${KEYCLOAK_ISSUER_URI}
# Optional overrides
# ecclesiaflow.platform.rpc.s2s.generic-scope=ef:s2s
# ecclesiaflow.platform.rpc.s2s.refresh-leeway-seconds=30
```

Auto-configuration kicks in as soon as `client-id` is set. Beans are
contributed only when missing, so you can override any of them locally.

## Wiring the interceptors

### Client side (outgoing channel)

```java
@Bean
public ManagedChannel membersChannel(S2sAuthClientInterceptor s2sAuth,
                                     @Value("${grpc.members.host}") String host,
                                     @Value("${grpc.members.port}") int port) {
    return ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .intercept(s2sAuth)
        .build();
}
```

### Server side (incoming RPCs)

```java
@Bean
public Server grpcServer(BindableService impl, S2sAuthServerInterceptor s2sAuth,
                         @Value("${grpc.server.port}") int port) throws IOException {
    return ServerBuilder.forPort(port)
        .addService(impl)
        .intercept(s2sAuth)
        .build()
        .start();
}
```

## Consuming the library (downstream modules)

The artifact is published on **GitHub Packages**, in two flavours:

| Flavour | Coordinates | When to use |
|---|---|---|
| **Release** | `com.ecclesiaflow:ecclesiaflow-platform-core:X.Y.Z` | On consumer's `main` branch (production builds). Immutable. |
| **Snapshot** | `com.ecclesiaflow:ecclesiaflow-platform-core:X.Y.Z-SNAPSHOT` | On consumer's `*-dev` branches (rolling integration with the lib's `main`). |

### Maven settings (consumer side)

In the consumer's `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github-platform-core</id>
    <name>GitHub Packages — ecclesiaflow-platform-core</name>
    <url>https://maven.pkg.github.com/GYOM15/ecclesiaflow-platform-core</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.ecclesiaflow</groupId>
    <artifactId>ecclesiaflow-platform-core</artifactId>
    <version>0.3.0-SNAPSHOT</version>  <!-- or 0.3.0 on main -->
  </dependency>
</dependencies>
```

Authentication is via `~/.m2/settings.xml` (local builds) or via
`actions/setup-java` with `server-id: github-platform-core` (CI).
Required token scope: `read:packages`.

### Local development against an unreleased lib

When iterating on platform-core itself, use `mvn install` to publish to your
local `~/.m2`:

```bash
mvn -DskipTests install
```

Consumers then resolve the local snapshot before reaching GitHub Packages.

---

## Releasing the library (maintainers)

### Snapshots (continuous integration)

Every push to `main` triggers `.github/workflows/snapshot.yml`, which publishes
the SNAPSHOT version currently declared in `pom.xml` to GitHub Packages.

Workflow:

1. Open PR from `platform-core-dev` (or feature branch) → `main`
2. Merge to `main`
3. Snapshot is automatically published as `X.Y.Z-SNAPSHOT`
4. Consumer's `*-dev` branches pick it up on their next build

### Releases (tagged)

Releases are immutable, semver-pinned versions consumed by production code.

```bash
# 1. Make sure pom version is set to the release (no -SNAPSHOT)
mvn -B versions:set -DnewVersion=0.3.0 -DgenerateBackupPoms=false
git commit -am "Release 0.3.0"

# 2. Tag and push
git tag v0.3.0
git push origin v0.3.0      # triggers .github/workflows/release.yml

# 3. Bump pom back to the next SNAPSHOT for ongoing dev
mvn -B versions:set -DnewVersion=0.3.1-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Bump to 0.3.1-SNAPSHOT"
git push origin main
```

`.github/workflows/release.yml` also accepts a manual `workflow_dispatch` with
a custom `version` input if you need to publish out-of-band.

### Semver discipline

| Change kind | Bump |
|---|---|
| Bug fix, doc, internal refactor | patch (`0.3.0 → 0.3.1`) |
| New backward-compatible feature | minor (`0.3.0 → 0.4.0`) |
| Breaking change in any public API | major (`0.3.0 → 1.0.0`) |

Public API = anything under `com.ecclesiaflow.platform.*` that consumers
import (interceptors, properties, beans declared by auto-configurations,
events records).

---

## Local build commands

```bash
mvn clean verify       # tests only (no install)
mvn clean install      # tests + install to ~/.m2 (for local consumers)
mvn -DskipTests install # install without tests (when iterating on consumers)
```

## License

MIT
