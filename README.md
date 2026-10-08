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
5. **Domain events on RabbitMQ** — transactional outbox, HMAC signing, the protobuf
   wire format and dead-letter queue depth.
6. **Shared service plumbing** — object storage, upload sanitizing, rate limiting,
   error responses, the clock and the architecture rules (see
   [Other building blocks](#other-building-blocks)).

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
  the token against the Keycloak JWKS endpoint with the issuer and the audience
  pinned, checks the caller's client id against `allowed-azp` when set, and
  enforces a generic `ef:s2s` scope on every call.

Per-method scope enforcement (e.g. `@S2sScopeRequired("ef:members:write")`)
is enforced via `S2sScopeRegistry` since `0.2.0`. The registry refuses to
start when an RPC of a scanned service carries no annotation, or when an
annotation names no RPC of its service.

## Configuration

In any consumer's `application.properties`:

```properties
ecclesiaflow.platform.rpc.s2s.client-id=${S2S_CLIENT_ID}
ecclesiaflow.platform.rpc.s2s.client-secret=${S2S_CLIENT_SECRET}
ecclesiaflow.platform.rpc.s2s.token-url=${S2S_TOKEN_URL}
ecclesiaflow.platform.rpc.s2s.jwks-uri=${KEYCLOAK_JWKS_URI}
ecclesiaflow.platform.rpc.s2s.issuer=${KEYCLOAK_ISSUER_URI}
# Backend clients allowed to call this module's gRPC server (azp claim).
# Empty = any client of the realm holding ef:s2s; the posture is logged at startup.
ecclesiaflow.platform.rpc.s2s.allowed-azp=${S2S_ALLOWED_AZP:}
# Fail at startup when the list above is empty (set it where the list is deployed).
ecclesiaflow.platform.rpc.s2s.require-allowed-azp=${S2S_REQUIRE_ALLOWED_AZP:false}
# Optional overrides
# ecclesiaflow.platform.rpc.s2s.expected-audience=ecclesiaflow-internal   (blank skips the aud check)
# ecclesiaflow.platform.rpc.s2s.generic-scope=ef:s2s
# ecclesiaflow.platform.rpc.s2s.refresh-leeway-seconds=30
```

Auto-configuration kicks in as soon as `client-id` is set. Beans are
contributed only when missing, so you can override any of them locally.
The s2s decoder is an `S2sJwtDecoder`, not a `JwtDecoder`, so it never
stands in for the REST resource server's decoder (see
[REST resource-server decoder](#rest-resource-server-decoder)).

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
                         GrpcExceptionServerInterceptor grpcExceptionInterceptor,
                         @Value("${grpc.server.port}") int port) throws IOException {
    return ServerBuilder.forPort(port)
        .addService(impl)
        .intercept(s2sAuth)
        .intercept(grpcExceptionInterceptor)   // added last, runs first: maps exceptions to statuses
        .build()
        .start();
}
```

## REST resource-server decoder

`PlatformRestJwtDecoderAutoConfiguration` publishes the REST plane's `JwtDecoder`, named
`restJwtDecoder`: Keycloak's keys from `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, the
issuer pinned to `spring.security.oauth2.resourceserver.jwt.issuer-uri`, and a required audience. It
is registered ahead of Spring Boot's resource-server decoder, which checks no audience, and backs
off when the module declares its own `JwtDecoder`. It loads only when `jwk-set-uri` is set, and
refuses to start without `issuer-uri`. It reads the audience from the property below, never from
Spring Boot's `spring.security.oauth2.resourceserver.jwt.audiences`.

| Property | Default | Effect |
|---|---|---|
| `ecclesiaflow.rest.jwt.enabled` | `true` | `false` leaves the decoder to the module or to Spring Boot |
| `ecclesiaflow.rest.jwt.audience` | `ecclesiaflow-internal` | `aud` entry every REST token must carry; blank skips the check, the issuer stays pinned |

The default audience is the one the modules enforce today. The end state is `ecclesiaflow-app`,
set through the environment once the realm stamps it on end-user tokens. `REST_JWT_AUDIENCE` binds
to `rest.jwt.audience`, not to this prefix, so the module declares the bridge:

```properties
ecclesiaflow.rest.jwt.audience=${REST_JWT_AUDIENCE:ecclesiaflow-internal}
```

A test slice that builds the security chain without the full auto-configuration imports
`PlatformRestJwtDecoderAutoConfiguration` or supplies its own `JwtDecoder`.

## Actuator endpoints on the management port

`PlatformManagementSecurityAutoConfiguration` adds the filter chain `managementSecurityFilterChain`,
ordered first, for a servlet module that serves its actuator endpoints on their own port
(`management.server.port` different from `server.port`). It claims `/actuator/**` and:

- opens `/actuator/prometheus`, `/actuator/info`, `/actuator/health` and `/actuator/health/**` to
  callers with no token: the Prometheus container and the orchestrator's probes;
- refuses every other actuator endpoint, `/actuator/metrics` included, since the chain has no
  login mechanism;
- creates no session, and has no CSRF, CORS, basic or form login.

The port, which the compose file never publishes, is the boundary. On a shared port the chain is
not loaded and the module's own chain answers the endpoints, so the scrape is never opened at the
edge. It needs `spring-boot-actuator-autoconfigure` and `spring-security-config`, which every
module with a management port already has.

| Property | Default | Effect |
|---|---|---|
| `ecclesiaflow.platform.management.security.enabled` | `true` | `false` removes the chain |

A module bean named `managementSecurityFilterChain` takes precedence. The chain needs
`management.server.port` different from `server.port` in every profile that runs, and
`management.endpoints.web.base-path` at its default, `/actuator`, the path it matches.

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
| `events/members/v1/members_events.proto` | members (`MemberProfileChangedEvent`, `MemberAnonymizedEvent`, `MemberContactsErasedEvent`, `MemberDeactivatedEvent`, `MemberReactivatedEvent`) | `com.ecclesiaflow.grpc.events.members` |

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

## Erased addresses

When a member is erased, the modules that keep something about them by user id find it from the
Keycloak sub on `MemberAnonymizedEvent`. A module that keeps addresses only, as communication keeps
sent mail and campaign recipients, gets `MemberContactsErasedEvent` instead, staged in the same
transaction: the sub, and one digest per address the member held.

`com.ecclesiaflow.platform.events.erasure.RecipientDigest` makes those digests: the SHA-256, in
lowercase hex, of the address as the platform writes it (an email trimmed and lowercased, a phone
number `+` and its digits). The address never travels in clear, so the event, the outbox row and a
dead-lettered copy tell nothing to whoever does not already hold the address. A PostgreSQL consumer
rebuilds the digest of a stored address with
`encode(sha256(convert_to(lower(btrim(address)), 'UTF8')), 'hex')`.

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

### Dead-lettering by policy

RabbitMQ never changes the arguments of a durable queue: a new dead-letter key on a queue that
declares one means deleting and recreating that queue. A broker policy carries the same keys and
changes in place, but an argument declared by the module overrides it. A subscription that moves to
a policy is declared without the two arguments, and the module declares a `DeadLetterRoute` bean
naming it and its dead-letter queue:

```java
@Bean
DeadLetterRoute setupTokenIssuedDeadLetterRoute() {
    return new DeadLetterRoute("comm.subscriber.setup-token-issued", "comm.subscriber.setup-token-issued.dlq");
}
```

The route gets the dead-letter queue measured like one found by its arguments; a module may mix both
while it moves. `DeadLetterQueues.policies(topology, routes)` turns the routes into the policies the
broker needs, one per subscription: its dead letters go to the exchange and key the dead-letter
queue is bound under, or through the default exchange when it is bound nowhere. Each
`DeadLetterPolicy` renders its `rabbitmqctl set_policy` arguments, applied to queues only. A route
whose dead-letter queue is not declared or is bound more than once has no policy, and the call
throws. The policies must be on the broker before the queue is declared without its arguments,
otherwise a rejected message is dropped.

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

Events staged under the same `aggregateKey` (the four-argument `append`) leave one at a time, in
staging order; an event without a key leaves as soon as it is due.

`append` outside a writable transaction on the primary DataSource throws
`IllegalTransactionStateException`. A message that fails at the broker `max-attempts` times
(10 by default, about half an hour) is parked, and holds back the later events of its key; an
unreachable broker parks nothing. Watch `ecclesiaflow_outbox_oldest_pending_age_seconds` and
`ecclesiaflow_outbox_parked`.

### Retention and erasure

A relayed row still carries the event's personal data, so the relay deletes it
`sent-retention` after the broker's confirm (`PT1H` by default), checking every
`purge-interval` (`PT5M`). Modules keep these defaults.

A module that erases a person calls `discardDelivered(aggregateKey)` in the erasure
transaction, before it stages the erasure events under the same key. It deletes the relayed
and parked rows of that key, so the person's past events leave the table and a parked one no
longer holds back the erasure events; pending rows stay and are relayed first. Like `append`,
it throws `IllegalTransactionStateException` outside a writable transaction.

### Parked rows

A parked row is never retried nor purged by age. Once its cause is fixed (no queue bound to
the routing key, a message the broker refuses), replay it, or drop it to let its key move on:

```sql
-- Replay: the relay picks it up on its next run.
UPDATE outbox_event
SET status = 'PENDING', parked_at = NULL, attempts = 0, next_attempt_at = now()
WHERE status = 'PARKED' AND id = <id>;

-- Drop: the event is lost; the later events of its key leave.
DELETE FROM outbox_event WHERE status = 'PARKED' AND id = <id>;
```

## Domain-event signing

With `ecclesiaflow.events.hmac-secret` set, `SigningMessagePostProcessor` stamps an HMAC-SHA256
signature on every published event (`x-ef-signature`, `x-ef-signed-at`,
`x-ef-signature-version`), and `VerifyingListenerAdvice` checks it before the listener converts
the message. Register the first with `addBeforePublishPostProcessors` on the domain-events template,
the second in the listener container factory's advice chain.

| Property | Default | Effect |
|---|---|---|
| `ecclesiaflow.events.hmac-secret` | blank | The same value in every module (`EVENTS_HMAC_SECRET`); blank signs nothing and accepts everything |
| `ecclesiaflow.events.verify-signatures` | `false` | `false` accepts a missing, invalid or stale signature with a warning; `true` rejects it without requeue, so it lands in the dead-letter queue |

A signature more than 5 minutes away from the consumer's clock is stale. Turn on
`verify-signatures` once `ecclesiaflow_domain_events_signature_total{decision="accept_unverified"}`
stays at zero on every consumer. The counter cannot reveal an unsigned fleet: with a blank secret
every message counts as `accept`.

## Other building blocks

| Concern | Entry points | Configuration |
|---|---|---|
| Object storage | `ObjectStorage` port; `FilesystemObjectStorage`, `S3ObjectStorage` (Cloudflare R2) | `ecclesiaflow.object-storage.provider` = `filesystem` or `s3`; unset creates no adapter. Under the `prod` profile, `filesystem` requires `filesystem.base-path`. Keys under `s3.private-key-prefixes` (default `member-photos`) are written `Cache-Control: private, no-store` and get no public URL |
| Uploads | `ImageSanitizer` (real type sniffed, pixel count bounded, every image redrawn), `FileSanitizer` (CSV, XLSX), `TextUploadDecoder` (UTF-8 or Windows-1252) | `ecclesiaflow.platform.upload.image.max-concurrent-decodes`, `ecclesiaflow.platform.upload.image.decode-wait` |
| Rate limiting | `@RateLimited` on a handler, Redis fixed window, `RateLimitExceededException` | The module supplies `RateLimitRuleRegistry` and `RateLimitSubjectResolver`; startup fails when a `@RateLimited` handler would not be limited. `ecclesiaflow.rate-limit.enabled=false` is for tests without Redis |
| Error responses | `PlatformRestExceptionHandler` (lowest precedence, `ApiErrorResponse` with `errorCode`), `GrpcExceptionServerInterceptor`, `ExceptionClassifier` beans for module exceptions | `ecclesiaflow.platform.web.errors.enabled` |
| Clock | A UTC `Clock` bean, unless the module declares one | — |
| Architecture | `HexagonalArchitectureRules.check("<base package>")`, run from a module test with ArchUnit in test scope | — |

## Consuming the library (downstream modules)

The artifact is published on **GitHub Packages**, in two flavours:

| Flavour | Coordinates | Published |
|---|---|---|
| **Release** | `com.ecclesiaflow:ecclesiaflow-platform-core:X.Y.Z` | From a `vX.Y.Z` tag. Immutable. |
| **Snapshot** | `com.ecclesiaflow:ecclesiaflow-platform-core:X.Y.Z-SNAPSHOT` | On every push to `main`, replacing the previous build. |

The modules' poms declare `0.4.0-SNAPSHOT`. Publish this library before a module that needs a
change in it: the module's CI resolves the version from GitHub Packages.

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
    <version>0.4.0-SNAPSHOT</version>  <!-- development head; see pom.xml / GitHub Packages for the current version, or pin a released X.Y.Z -->
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

### Branch CI

`.github/workflows/ci.yml` runs `mvn -B verify` (unit tests, the Testcontainers integration tests
and the coverage gate) on every push to a `feature/**` or `fix/**` branch and on every pull request
to `main`. It publishes nothing. `main` itself is verified by `snapshot.yml` before it publishes.

### Snapshots (continuous integration)

Every push to `main` triggers `.github/workflows/snapshot.yml`, which publishes
the SNAPSHOT version currently declared in `pom.xml` to GitHub Packages.

Workflow:

1. Open a PR from a `feature/` or `fix/` branch → `main`
2. Merge to `main`
3. Snapshot is automatically published as `X.Y.Z-SNAPSHOT`
4. Consumers declaring that version pick it up on their next build

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
mvn -B versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Bump to X.Y.(Z+1)-SNAPSHOT"
git push origin main
```

`.github/workflows/release.yml` refuses a tag whose commit does not declare
that exact version in `pom.xml` (a SNAPSHOT pom included), and any version that
is not `X.Y.Z`. It also accepts a manual `workflow_dispatch` with a custom
`version` input if you need to publish out-of-band; that path sets the pom
version itself.

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
mvn test                # unit tests, no Docker
mvn clean verify        # adds the *IntegrationTest classes and the 90% line and branch gate
mvn clean install       # verify + install to ~/.m2 (for local consumers)
mvn -DskipTests install # install without tests (when iterating on consumers)
```

The integration tests start PostgreSQL, Redis, RabbitMQ and SeaweedFS with Testcontainers, so
`verify` and `install` need Docker.

The build runs `protoc-gen-grpc-java`. For gRPC 1.65.1 its `osx-aarch_64` artifact is an x86_64
executable, so on Apple silicon it runs only under Rosetta 2 (`softwareupdate --install-rosetta`);
without it, `protoc` reports `protoc-gen-grpc-java: program not found or is not executable`.
`-DprotocPluginExecutable=<path>` points the build at another `protoc-gen-grpc-java` 1.65.1
executable instead. The modules compile no `.proto` and are not affected.

## License

MIT
