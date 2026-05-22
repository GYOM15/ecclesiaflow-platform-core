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

Method-level scope enforcement (e.g. `ef:members:write:internal` on
`MembersService/NotifyAccountActivated`) is planned for v0.2.0.

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

## Build

```bash
mvn clean install      # publishes to ~/.m2/repository (consumed locally)
mvn clean verify       # runs tests without installing
```

## License

MIT
