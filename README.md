# ecclesiaflow-platform-core

Shared platform library for EcclesiaFlow backend modules. Main concerns:

1. **Server-to-server authentication on gRPC** (s2s) — each module obtains a
   JWT from Keycloak via the `client_credentials` flow, attaches it as a Bearer
   token in gRPC metadata, and the receiving side validates the token and
   enforces the required scope (generic `ef:s2s` floor + per-method ceiling via
   `@S2sScopeRequired`).
2. **Web security helpers** — `KeycloakJwtConverter` turns a Keycloak JWT into
   a Spring `JwtAuthenticationToken`: `SCOPE_*` from the `scope` claim, `ROLE_*`
   from the `roles` claim, `realm_access` and the roles of the token's own client
   in `resource_access` (`azp`). The principal name is `sub`.
3. **Logging helpers** — `SecurityMaskingUtils` masks PII (emails, phone
   numbers, message bodies, JWTs, IDs) and infrastructure details (URLs, hosts,
   socket addresses) before they reach a log line. `maskAny` redacts any value
   it does not recognise.
4. **gRPC and domain-event contracts** — the `.proto` files every module speaks,
   compiled once here into messages and gRPC stubs (see
   [Contracts](#grpc-and-domain-event-contracts)).

- **Artifact**: `com.ecclesiaflow:ecclesiaflow-platform-core` — the current version is declared in [`pom.xml`](pom.xml) (development head as `X.Y.Z-SNAPSHOT`, releases pinned as `X.Y.Z`)
- **Java**: 21
- **Spring Boot**: 3.5.5 (auto-configuration via starter pattern)
- **Renamed in 0.3.0** from `ecclesiaflow-platform-rpc` to reflect the broader
  scope. Existing `com.ecclesiaflow.platform.rpc.*` packages are unchanged for
  backward compatibility.

## What this gives you

- A `S2sTokenProvider` that fetches and caches a JWT: one refresh at a time,
  ahead of expiry, never awaited past the caller's gRPC deadline, and not
  retried before a short backoff after a failure.
- A `S2sAuthClientInterceptor` you plug on every outgoing `ManagedChannel`. A
  token the server answers `UNAUTHENTICATED` to is dropped for the next call.
- A `S2sAuthServerInterceptor` you plug on every gRPC `Server`. It validates
  the token against the Keycloak JWKS endpoint, checks the caller's client id
  against `allowed-azp` when set, and enforces a generic `ef:s2s` scope on
  every call.

Per-method scope enforcement (e.g. `@S2sScopeRequired("ef:members:write")`)
is enforced via `S2sScopeRegistry` since `0.2.0`. The registry refuses to
start when an RPC of a scanned service carries no annotation, or when an
annotation names no RPC of its service.

## Configuration

In any consumer's `application.properties`:

```properties
ecclesiaflow.platform.rpc.s2s.client-id=ecclesiaflow-backend
ecclesiaflow.platform.rpc.s2s.client-secret=${KEYCLOAK_BACKEND_CLIENT_SECRET}
ecclesiaflow.platform.rpc.s2s.token-url=${KEYCLOAK_ISSUER_URI}/protocol/openid-connect/token
ecclesiaflow.platform.rpc.s2s.jwks-uri=${KEYCLOAK_JWKS_URI}
ecclesiaflow.platform.rpc.s2s.issuer=${KEYCLOAK_ISSUER_URI}
# Backend clients allowed to call this module's gRPC server (azp claim).
# Empty = any client of the realm holding ef:s2s; the posture is logged at startup.
ecclesiaflow.platform.rpc.s2s.allowed-azp=${S2S_ALLOWED_AZP:}
# Fail at startup when the list above is empty (set it where the list is deployed).
ecclesiaflow.platform.rpc.s2s.require-allowed-azp=${S2S_REQUIRE_ALLOWED_AZP:false}
# Optional overrides
# ecclesiaflow.platform.rpc.s2s.generic-scope=ef:s2s
# ecclesiaflow.platform.rpc.s2s.refresh-leeway-seconds=30
```

Auto-configuration kicks in as soon as `client-id` is set. Beans are
contributed only when missing, so you can override any of them locally.
No bean of type `JwtDecoder` is published: the s2s decoder is an
`S2sJwtDecoder`, so the module's REST resource server keeps Spring Boot's
decoder, or the one the module declares.

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

## gRPC and domain-event contracts

The canonical `.proto` files live in [`src/main/proto/ecclesiaflow`](src/main/proto/ecclesiaflow).
The build compiles them with `protoc` and `protoc-gen-grpc-java`, and the jar ships both the
generated classes and the `.proto` files.

| File | Owner (serves or publishes) | Java package |
|---|---|---|
| `auth/auth_service.proto` | auth (`AuthService`) | `com.ecclesiaflow.grpc.auth` |
| `church/church_service.proto` | church (`ChurchService`) | `com.ecclesiaflow.grpc.church` |
| `members/members_service.proto` | members (`MembersService`) | `com.ecclesiaflow.grpc.members` |
| `email/email_service.proto` | communication (`EmailService`, `EmailQueueMessage`) | `com.ecclesiaflow.grpc.email` |
| `events/auth/v1/domain_events.proto` | auth (`SetupTokenIssuedEvent`, `ExistingAccountNoticeEvent`) | `com.ecclesiaflow.grpc.events.auth` |
| `events/church/v1/church_events.proto` | church (invitation, admission, removal, group events) | `com.ecclesiaflow.grpc.events.church` |
| `events/members/v1/members_events.proto` | members (`MemberProfileChangedEvent`, `MemberAnonymizedEvent`) | `com.ecclesiaflow.grpc.events.members` |

A module that calls or serves an RPC, or publishes or consumes an event, uses these classes. It
keeps no `.proto` of its own and needs no protobuf plugin. Consumers bring `grpc-protobuf`
themselves when they use a `*Grpc` stub; `protobuf-java` comes with this library.

Services are deployed one at a time, so the old version on the other side must still read what
the new one sends:

- add fields with new numbers; never renumber, retype or reuse an existing field or enum value,
  and `reserved` whatever is removed;
- never change a file's `package`, a service or RPC name (the s2s scope registry keys on
  `<package>.<Service>/<Rpc>`), nor the `java_package` or the name of a message sent over
  RabbitMQ: `ProtobufMessageConverter` stamps the Java class name on the `__TypeId__` header and
  the consumer loads that class.

`ContractWireCompatibilityTest` pins all of the above. When it fails, the change is on the wire:
append the new field or RPC to its snapshot, never edit a line already there. Release order: this
library first, then the owner of the contract, then its clients.

## Protobuf messages over RabbitMQ

`com.ecclesiaflow.platform.events.amqp.ProtobufMessageConverter` is the broker wire format of every
protobuf message: the serialized message as the body, `application/x-protobuf` as the content type,
and the Java class name on a `__TypeId__` header, which the consumer loads and parses.

```java
new ProtobufMessageConverter();                                   // strict
new ProtobufMessageConverter(MemberRemovedFromChurchEvent.class); // with a default type
```

The strict form refuses a message without the header, or naming a class the service does not have.
The other reads such a message as the default type, so it suits only a queue that carries a single
type: the church events share their field numbers, and a removal read as an admission parses
without error. Both refuse a class outside `com.ecclesiaflow.*` or one that is not a protobuf
message, before initializing it. A refusal is a `MessageConversionException`, which the listener
container rejects without requeue: the message parks in its dead-letter queue.

## Dead-letter queue depth

A subscription queue declared with `x-dead-letter-exchange` and `x-dead-letter-routing-key` parks
what its listener rejects in the queue bound under that key, and nothing consumes that queue.
`PlatformDeadLetterMetricsAutoConfiguration` finds those dead-letter queues among the module's
`Queue`, `Binding` and `Declarables` beans and publishes, for each one:

```
ecclesiaflow_domain_events_dlq_depth{queue="<dead-letter queue>"}
```

The value is the broker's count, read on each scrape, or `-1` when the broker cannot be asked or
does not know the queue. A subscription added later is measured without being listed anywhere.
The gauge is a `MeterBinder`, bound by the actuator; it needs `spring-rabbit` and Micrometer, and
`ecclesiaflow.events.dead-letter-metrics.enabled=false` turns it off. A queue that dead-letters
without an explicit routing key is not followed.

## Transactional outbox for domain events (opt-in)

A domain event staged with `OutboxPublisher.append(...)` is written to the module's
`outbox_event` table inside the business transaction, then published by a relay that
waits for the broker's confirm and retries until it gets one. The event leaves if and
only if the business change commits; delivery is at least once.

Adopting it in a module:

1. Copy `src/main/resources/db/outbox/outbox_event.sql` (in this jar) into the module's
   next Flyway migration. The library ships no migration of its own.
2. Make the domain-events template reliable, otherwise the startup fails:
   `spring.rabbitmq.publisher-confirm-type=correlated`, `spring.rabbitmq.publisher-returns=true`,
   `template.setMandatory(true)`, `template.setReturnsCallback(returned -> { })`, and the
   `SigningMessagePostProcessor` registered when `ecclesiaflow.events.hmac-secret` is set.
3. Enable it and stage events where the business transaction runs:

```properties
ecclesiaflow.events.outbox.enabled=true
# ecclesiaflow.events.outbox.rabbit-template=domainEventsRabbitTemplate   (default)
```

```java
@Transactional
public void removeFromGroup(...) {
    // business change ...
    outbox.append(domainEventsExchange, "church.member.removed-from-group.v1", event);
}
```

`append` outside a writable transaction on the primary DataSource throws
`IllegalTransactionStateException`. A message that fails at the broker `max-attempts` times
(10 by default, about half an hour) is parked; an unreachable broker parks nothing. Watch
`ecclesiaflow_outbox_oldest_pending_age_seconds` and `ecclesiaflow_outbox_parked`; the replay
statement for a parked row is in the DDL file.

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
    <version>0.3.1-SNAPSHOT</version>  <!-- development head; see pom.xml / GitHub Packages for the current version, or pin a released X.Y.Z -->
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
mvn -B versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
git commit -am "Release X.Y.Z"

# 2. Tag and push
git tag vX.Y.Z
git push origin vX.Y.Z      # triggers .github/workflows/release.yml

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
