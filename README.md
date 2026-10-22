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
5. **Domain events on RabbitMQ** — transactional outbox, HMAC signing, sealed fields,
   the protobuf wire format and dead-letter queue depth.
6. **Shared service plumbing** — object storage, upload sanitizing, rate limiting,
   error responses, the clock and the architecture rules (see
   [Other building blocks](#other-building-blocks)), and idempotent creations (see
   [Idempotent creations](#idempotent-creations-openapi-x-ef-idempotency)).

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
| `ecclesiaflow.rest.jwt.jwks.refresh-interval` | `5m` | Age at which the key set is reloaded; a key withdrawn from the realm is trusted until then |
| `ecclesiaflow.rest.jwt.jwks.min-reload-interval` | `30s` | Shortest gap between two reloads, for an unknown `kid` or after a failure |
| `ecclesiaflow.rest.jwt.jwks.outage-tolerance` | `24h` | How long the last loaded set serves while every reload fails, from the first failure |
| `ecclesiaflow.rest.jwt.jwks.connect-timeout`, `read-timeout` | `2s` | Bound on one reload; zero is refused, since it would mean no timeout |

The default audience is the one the modules enforce today. The end state is `ecclesiaflow-app`,
set through the environment once the realm stamps it on end-user tokens. `REST_JWT_AUDIENCE` binds
to `rest.jwt.audience`, not to this prefix, so the module declares the bridge:

```properties
ecclesiaflow.rest.jwt.audience=${REST_JWT_AUDIENCE:ecclesiaflow-internal}
```

A test slice that builds the security chain without the full auto-configuration imports
`PlatformRestJwtDecoderAutoConfiguration` or supplies its own `JwtDecoder`.

**Keys during a Keycloak outage.** Spring's default key cache drops the keys when a reload fails past
their five minutes, which refuses every valid token while Keycloak restarts. The decoder reads them
through `FaultTolerantJwkSource` instead:

- a failed reload keeps the last loaded set in service, up to `outage-tolerance` counted from the
  first failure, and is retried at most every `min-reload-interval`;
- a token whose `kid` is unknown reloads the set without waiting for the refresh, at most once per
  `min-reload-interval`, so a key rotation is picked up within seconds;
- an unknown `kid` that cannot be reloaded, or no key set at all, fails as an infrastructure error
  (a `JwtException` that is not a `BadJwtException`), never as an invalid token: a valid user is
  not signed out by an outage;
- a response without keys, or one that is not a key set, counts as a failed reload and never
  replaces good keys.

`JwksEventListener` logs the first load, rotations and the end of an outage at INFO, a reload that
fails at WARN while the last set serves, and at ERROR once tokens are refused.

## Surface authorization (OpenAPI `x-ef-surface`)

`PlatformApiContractAutoConfiguration` reads the module's OpenAPI contract at startup into an
`ApiContract`: its operations indexed by method and path template, with their effective `security`
and `x-*` extensions, matched the way Spring MVC dispatches. A servlet module whose contract cannot be
found or read does not start.

| Property | Default | Effect |
|---|---|---|
| `ecclesiaflow.contract.locations` | `classpath*:api/*.yaml` | contract files or patterns; each must match a file |

`SurfaceScopeAuthorizationAutoConfiguration` applies the `x-ef-surface` of the operation a request
reaches to every request authenticated by a token (`JwtAuthenticationToken`):

| `x-ef-surface` | The token needs |
|---|---|
| `member` | nothing more; decision points cap admin powers when `SurfaceCaller.isConsoleCaller()` is false |
| absent | `SCOPE_ef:surface:full` (the web console) |
| `platform` | `SCOPE_ef:moderation:act` and `ROLE_PLATFORM_MODERATOR`; `ef:surface:full` never suffices |

A `security: []` operation is never ruled; a path outside the contract is console-only, except the
technical list (`/ws/**`, `/error`, `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui.html`,
`/swagger-ui/**`). A refusal is the module's own 403: the filter runs right after Spring Security's
chain and throws `AccessDeniedException`. At startup the module refuses to start when its chain opens,
to a caller without a token, a route that is neither a `security: []` operation nor technical, or
closes a `security: []` operation; an `x-ef-surface` other than `member` or `platform` stops it too.

| Property | Default | Effect |
|---|---|---|
| `ecclesiaflow.security.surface.mode` | `enforce` | `log-only` logs and counts refusals and lets them through |

Metrics: `surface_mode{mode}` (1 for the mode in force) and
`ef_surface_refused_total{surface,caller,mode}`. No property turns the rule off; a module that must
not run it excludes the auto-configuration in its own code.

## Idempotent creations (OpenAPI `x-ef-idempotency`)

A creation an app may send twice (a message, a comment) runs at most once per author and
`Idempotency-Key`. The key is stored on the created row under a unique index on (author, key): a
retry with the same request answers 200 with `Idempotent-Replayed: true` and the stored resource,
the same key with another request answers 422 `IDEMPOTENCY_CONFLICT`, and when two requests race
the index refuses the second insert, which then answers 200 with the first one's row. There is no
in-flight state and no 409. Without the header the creation runs as before, once per call.

Only `x-ef-idempotency: required` on the operation switches it on, never the header alone: church's
signup (`IdempotencyService`, Redis) and finance keys keep their own semantics, and their operations
carry no extension. Adopting it on an operation:

1. **Contract.** Mark the operation and declare the header optional, as is:

   ```yaml
   post:
     operationId: EventComment.Create
     x-ef-idempotency: required
     parameters:
       - name: Idempotency-Key
         in: header
         required: false
         schema: {type: string, format: uuid}
   ```

   Declare the 200 replay response with its `Idempotent-Replayed` header, the 422, and register
   `IDEMPOTENCY_CONFLICT` (422) and `BAD_REQUEST` (400) in `x-error-codes`. The module refuses to
   start when the extension holds anything but `required`, sits on anything but a POST or on a
   `security: []` operation, or comes without that optional header.
2. **Table.** Two nullable columns, then the index in a migration of its own, built
   `CONCURRENTLY`, with `spring.flyway.postgresql.transactional-lock=false` and its test:

   ```sql
   ALTER TABLE event_comment ADD COLUMN idempotency_key UUID, ADD COLUMN idempotency_fingerprint TEXT;
   ```

   ```sql
   CREATE UNIQUE INDEX CONCURRENTLY ux_event_comment_author_idempotency_key
       ON event_comment (author_user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
   ```
3. **Service.** Implement `IdempotentCreation` over the module's port and run it through the
   `IdempotentCreator` bean, outside any transaction (`IllegalTransactionStateException`
   otherwise): the creator opens its own, and a lost race aborts the one it inserted in. In a
   service annotated `@Transactional`, `NOT_SUPPORTED` on the method suspends it:

   ```java
   @Transactional(propagation = Propagation.NOT_SUPPORTED)
   public CreationOutcome<EventComment> create(UUID author, NewEventComment command,
                                               Optional<IdempotencyKey> key) {
       return idempotentCreator.create(author, command, key, new IdempotentCreation<>() {
           public Optional<IdempotentRecord<EventComment>> find(UUID author, UUID key) {
               return comments.findByAuthorAndIdempotencyKey(author, key);
           }
           public String fingerprint(NewEventComment command) {
               return IdempotencyFingerprint.of(command.eventId(), command.parentId(), command.body());
           }
           public EventComment insert(UUID author, NewEventComment command, IdempotencyStamp stamp) {
               // the existing checks, then the row with stamp.key() and stamp.fingerprint()
           }
       });
   }
   ```

   - Access checks run before the creator, so a replay is authorized like a creation; checks of
     the resource's state stay in `insert`, since a creation made before the state changed still
     replays.
   - `find` returns the row as stored, even edited or hidden since, and never another author's.
   - `fingerprint` covers every field of the request, path parameters included.
     `IdempotencyFingerprint.of` refuses a part whose text could change between two identical
     requests (an unordered collection, a map, an object without its own `toString`).
   - `insert` writes both values as given, `null` without a key. What leaves the database goes
     through the outbox or after the commit: a lost race rolls the insert back.
4. **Delegate.** Take the key from `IdempotencyKeyResolver`, never from the generated
   `idempotencyKey` argument: the resolver hands one over only on a marked operation, and answers
   a value that is not one canonical UUID with 400.

   ```java
   CreationOutcome<EventComment> outcome = comments.create(author, command, idempotencyKeys.current());
   return IdempotentResponses.created(outcome, mapper::toResponse);
   ```

   `IdempotentResponses.status(outcome, createdStatus)` returns the builder for an operation that
   creates with another status or sets a `Location`.

The 422 and the 400 come from `PlatformRestExceptionHandler`; a module that turns it off maps
`IdempotencyKeyConflictException` and `InvalidIdempotencyKeyException` itself. Log lines
(`IDEMPOTENCY:`): a replay at DEBUG, a race settled by the index at INFO, a reused key at WARN.

## Recent sign-in before sensitive gestures (`@RecentAuthRequired`)

A stolen session must not be able to close an account, move it to another address or reactivate it.
`PlatformRecentAuthAutoConfiguration` guards a method annotated `@RecentAuthRequired(action = ...)`,
for every token, web and mobile alike; `RecentAuthPolicy.require(maxAge)` makes the same decision
where a gesture is conditional. The account decides, not the session:

| Account (from the module's `SignInMethodsClient`) | Answer |
|---|---|
| linked to an identity provider | 202 with the body the module's `PendingConfirmationIssuer` returns (`PendingConfirmation`: `status`, `sentTo`, `expiresAt`); `auth_time` is not looked at and the method does not run |
| not linked | the method runs if `auth_time` is at most `maxAgeSeconds` old (300 by default, 600 for a reactivation) and the token carries no `identity_provider`; else 403 `REAUTH_REQUIRED` |
| sign-in methods unreadable (any failure, an empty answer) | 503 `SERVICE_UNAVAILABLE`, never a pass |

A request with no authenticated token naming a subject is refused 403 `REAUTH_REQUIRED`. A linked
account goes out of band because the provider signs the user back in silently (Google ignores
`prompt=login`), so neither a fresh `auth_time` nor a missing `identity_provider` proves anything.

The module provides two beans: a `SignInMethodsClient`, its adapter to auth's `GetSignInMethods`
(with a deadline; a failure throws), and a `PendingConfirmationIssuer`, which holds the gesture
(`SensitiveAction`: the action's name, the subject, the method's arguments), sends the link to the
current address and returns the 202 body. The two refusals are answered in `ApiErrorResponse` by
`RecentAuthExceptionHandler`, ahead of a module catch-all and apart from the platform fallback, so
modules that guard nothing do not see these codes in their error registry.

The aspect runs at the lowest precedence, after the module's own aspects of the same order: a scope
check refuses a caller before auth is asked or a link is sent. The module does not start when an
annotated method would run unchecked or could not answer: no `SignInMethodsClient` or
`PendingConfirmationIssuer`, a private, static or final method, an annotation on an interface method
only, a bean that is not proxied (AOP off), a return type other than `ResponseEntity`, a blank
`action` or a non-positive `maxAgeSeconds`. A call from inside the same bean bypasses the aspect.

`RecentAuthEventListener` logs refusals and confirmations sent at INFO, unreadable sign-in methods
at WARN, with the subject masked.

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
| `events/event_options.proto` | the `(ecclesiaflow.events.routing_keys)` option | `com.ecclesiaflow.grpc.events` |
| `events/auth/v1/domain_events.proto` | auth (`SetupTokenIssuedEvent`, `ExistingAccountNoticeEvent`, `TwoFactorRemovedEvent`, `AccountSessionsRevokedEvent`) | `com.ecclesiaflow.grpc.events.auth` |
| `events/church/v1/church_events.proto` | church (invitation, admission, removal, group events, `NotificationCreatedEvent`) | `com.ecclesiaflow.grpc.events.church` |
| `events/members/v1/members_events.proto` | members (`MemberProfileChangedEvent`, `MemberAnonymizedEvent`, `MemberContactsErasedEvent`, `MemberDeactivatedEvent`, `MemberReactivatedEvent`, `MemberSuspendedEvent`, `MemberSuspensionLiftedEvent`) | `com.ecclesiaflow.grpc.events.members` |
| `events/chat/v1/chat_events.proto` | chat (`ChatMessagePostedEvent`) | `com.ecclesiaflow.grpc.events.chat` |
| `events/moderation/v1/moderation_events.proto` | chat, church and members, each under its own key (`ContentReportedEvent`, `ModerationActionTakenEvent`) | `com.ecclesiaflow.grpc.events.moderation` |

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

Every top-level message under `events/` is a domain event:

- it opens with `string event_id = 1` (unique per producer, the consumers' dedup key) and
  `int64 occurred_at_epoch_ms = 2` (the instant of the fact); `ExistingAccountNoticeEvent`, whose
  `event_id` came later at number 3, is the one exception;
- it declares its exact routing keys with the repeated option `(ecclesiaflow.events.routing_keys)`,
  one per publishing module. A key belongs to one message and never changes; a new key reads
  `<module>.<aggregate>.<fact>.v1`, and the keys published before that rule keep their form;
- a vocabulary that grows (`reason`, `action`, a kind, a type) is an open string, never a proto enum;
- a helper type is nested in its event.

`com.ecclesiaflow.grpc.events.EventRoutes` holds one constant per key. Publishers and bindings use
it, never a literal, and a binding names one exact key, never a wildcard: the consumer of a message
with several producers binds each key.

```java
BindingBuilder.bind(queue).to(exchange).with(EventRoutes.MEMBERS_ACCOUNT_SUSPENDED_V1);
```

`ContractWireCompatibilityTest` pins all of the above, and fails on a `.proto` file it does not load.
When it fails, the change is on the wire: append the new field, RPC or key to its snapshot, never
edit a line already there. Release order: this library first, then the owner of the contract, then
its clients.

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

## Sealed event fields

The signature proves who sent an event; it hides nothing. A secret an event must carry, such as the
setup token of `SetupTokenIssuedEvent`, is sealed by
`com.ecclesiaflow.platform.events.sealing.EventFieldSealer`: AES-256-GCM, a random 12-byte nonce,
the event id as associated data. The sealed bytes are the nonce, then the ciphertext and its tag;
the key id travels in its own field (`sealed_key_id`). A value moved into another event, altered,
or sealed under a key the consumer does not hold throws `SealedFieldUnreadableException` with its
`Reason`, and never the value.

`SealingKeyProperties` holds the keys of one sealed field. The module binds it under that field's
prefix and builds the sealer from it:

```java
@Bean
@ConfigurationProperties("ecclesiaflow.setup-token.sealing")
SealingKeyProperties setupTokenSealingKeys() {
    return new SealingKeyProperties();
}

@Bean
EventFieldSealer setupTokenSealer(SealingKeyProperties setupTokenSealingKeys) {
    return new EventFieldSealer(setupTokenSealingKeys.toKeyring());
}
```

| Property (under the field's prefix) | Default | Effect |
|---|---|---|
| `key-id` | blank | Sent beside every sealed value; a new key takes a new id |
| `key` | blank | Base64 of 32 random bytes (`openssl rand -base64 32`), shared by producer and consumer |
| `retired-keys.<id>` | none | Open-only keys, kept until no value they sealed can still be delivered |

`isConfigured()` is false while the key or its id is blank, and `toKeyring()` then refuses: the
module decides whether that stops its startup. To rotate, deploy the old key under
`retired-keys` with the new one current on every consumer first, then on the producer.

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
