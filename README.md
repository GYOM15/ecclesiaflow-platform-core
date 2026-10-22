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
   the protobuf wire format, dead-letter queue depth and delayed retries.
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
`ef_surface_refused_total{surface,caller,mode}`. No `ecclesiaflow.*` property turns the rule off; a
module that must not run it excludes the auto-configuration.

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
## Moderation term filter

`TermFilter` refuses a text that holds a listed term. `PlatformTermFilterAutoConfiguration` publishes it
once `ecclesiaflow.moderation.terms-file` is present, so a module that has not adopted the filter is
untouched. The deploy mounts the list read-only and passes its path as `MODERATION_TERMS_FILE`, which
does not bind to the property on its own; the module bridges it:

```properties
ecclesiaflow.moderation.terms-file=${MODERATION_TERMS_FILE:}
```

Once bridged, the property is present even when the variable is unset, and its blank value stops the
startup: a deployment that forgot the list fails loudly instead of running unmoderated.

| Property | Default | Effect |
|---|---|---|
| `ecclesiaflow.moderation.terms-file` | absent | UTF-8 file, one term per line; blank lines are skipped and a line starting with `#` is a comment. Absent from the configuration, it creates no filter, so a module that injects one without declaring it fails on the missing bean |

The startup fails, naming the file and the property, when the path is blank, the file does not exist or
is a directory, it is not valid UTF-8, or a line holds no letter or digit and could never match. An
empty list is valid and logs a warning, since nothing is then rejected. The log line gives the number of
terms, never the terms.

```java
termFilter.requireClean("caption", request.getCaption());   // field names the property in the answer
termFilter.requireClean(message.getText());                 // or no field
boolean rejected = termFilter.rejects(text);                // when the caller decides what to do
```

- Case, accents (NFKD, plus `œ` and `æ`), full-width and ligature forms and invisible characters
  such as a zero-width space do not matter, on the text or on the term.
- A term matches only as whole words: a word is a run of letters and digits, so `heck` is found in
  `"oh, heck!"` and `"l'heck"` but not in `"check"` or `"heck2"`. A plural or a conjugation is a term
  of its own. A term of several words matches whatever punctuation or spacing separates them.
- A script written without spaces is matched only as one whole run of characters.

A rejection is `ContentRejectedException`, answered by `PlatformRestExceptionHandler` as 400
`CONTENT_REJECTED` in the usual `ApiErrorResponse`: a sober message, and `errors[0].path` when the
caller named a field. It is `INVALID_ARGUMENT` for the gRPC mapping. The exception carries neither the
text nor the matched term. A module registers `CONTENT_REJECTED` with status 400 in its contract's
`x-error-codes`, and keeps it out of any catch-all handler of its own.

## Trace id in the logs

`PlatformTraceparentAutoConfiguration` registers `TraceparentFilter` on a servlet module, ahead of Spring
Security's chain so the filters behind it log with the id too. It copies the trace id of a valid W3C
`traceparent` request header into the MDC under `traceId` and removes it when the request ends,
whether it succeeds or throws. A module's log pattern prints it with `%X{traceId}`.

A header is valid when it is the only `traceparent` of the request and reads
`00-<32 lowercase hex>-<16 lowercase hex>-<2 lowercase hex>`, with neither id all zeros. Anything
else is ignored and the request goes through: the id is a correlation aid, not a gate. The filter only
reads. It generates no id, forwards none to outgoing calls and keeps neither the parent id nor the
flags.

| Property | Default | Effect |
|---|---|---|
| `ecclesiaflow.platform.web.traceparent.enabled` | `true` | `false` removes the filter |

A `FilterRegistrationBean<TraceparentFilter>` the module declares replaces the one registered here.

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
    outbox.append(domainEventsExchange, EventRoutes.CHURCH_MEMBER_REMOVED_FROM_GROUP_V1, event);
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

## Delayed retries for consumers with an external effect (opt-in)

A consumer that calls Keycloak, sends a message or pushes to chat must outlast an outage of hours,
where the container's in-memory retry gives up in seconds. A `DelayedRetryTopology` bean opts one
queue in; the queue keeps its dead-letter route.

```java
@Bean
DelayedRetryTopology memberAccountStatusRetries() {
    return new DelayedRetryTopology("auth.subscriber.member-account-status"); // 1 min, 5 min, 30 min, 2 h
}

// In the listener container factory of those consumers:
factory.setAdviceChain(verifyingListenerAdvice, RetryInterceptorBuilder.stateless()
        .retryPolicy(policy)
        .backOffOptions(1000, 2.0, 10000)
        .recoverer(delayedRetryMessageRecoverer) // instead of new RejectAndDontRequeueRecoverer()
        .build());
```

`RabbitAdmin` declares one durable stage per delay (`<queue>.retry.1m` … `.retry.2h`); a stage's
messages expire back to the queue through the default exchange. Once the in-memory retries are spent,
`DelayedRetryMessageRecoverer` copies the delivery into its next stage, confirmed and mandatory,
before the original is acknowledged. The last stage's failure, an unreadable message, an explicit
`AmqpRejectAndDontRequeueException` and a copy the broker refuses go to the dead-letter queue. A queue
without a topology is dead-lettered at once, as with `RejectAndDontRequeueRecoverer`.

Each copy carries a hop (`x-ef-retry-*`, `x-ef-original-*`) and an HMAC stamp under a key derived from
`ecclesiaflow.events.hmac-secret`. With a topology declared, the auto-configured
`VerifyingListenerAdvice` checks a redelivery's signature against its original route and dates it by
the stamp: within its stage's delay plus 10 minutes. The broker's `x-death` header is never trusted:
RabbitMQ 3.13 takes it from any publisher. A redelivery arrives under the queue name, so a listener
never dispatches on the received routing key.

The startup fails when an opted-in queue has no dead-letter route, when the connection factory lacks
`spring.rabbitmq.publisher-confirm-type=correlated` or `spring.rabbitmq.publisher-returns=true`, or
when events are signed without a secret to stamp retries. `ecclesiaflow.events.delayed-retry.confirm-timeout`
(`PT5S`) bounds the wait for the broker's confirm.

Record `ProcessedDomainEvent` only after the effect succeeded: `ExternalEffectDeduplicator.applyOnce(type,
eventId, effect)` does it over the module's `ProcessedDomainEventStore`. Watch
`ecclesiaflow_domain_events_retry_depth{queue}` and
`ecclesiaflow_domain_events_retry_total{queue,outcome}` beside the dead-letter depth. The design, the
`x-death` findings and the `make dlq-replay` design are in [docs/delayed-retries.md](docs/delayed-retries.md).

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

## Upgrading to 0.6.0

Read this before a module's pom moves to `0.6.0` from 0.5.x (0.5.1 added only the two-factor
contracts). The wire contracts only grew, so an upgraded module and one that has not moved still
talk to each other; what changes is inside the module that upgrades. 0.6.0 is a minor release that
is not drop-in: before 1.0.0, a breaking change bumps the minor and comes with a section like this one.

### What a bump alone changes

**It can stop the build, the tests or the startup.** Fix these in the same upgrade:

| Change in 0.6.0 | Effect on a module that adopts nothing | Fix |
|---|---|---|
| `AuthService.GetSignInMethods` and `ChurchService.CanModerateGroupContent` are new RPCs, and `S2sScopeRegistry` lists every RPC of a bound service, implemented or not | The module that serves the service does not start: `s2s scope registry is incomplete` | Override the RPC with `@S2sScopeRequired("ef:auth:sign-in-methods:read")` (auth) or `@S2sScopeRequired("ef:church:group-moderation:read")` (church), implemented or as a stub that answers `UNIMPLEMENTED` |
| `EmailTemplateType` gains values 9 to 12 | A `switch` expression over the enum with no `default` no longer compiles | Add the four cases, or a `default` that refuses a template the module has no mail for |
| `PlatformRestExceptionHandler`, on unless `ecclesiaflow.platform.web.errors.enabled=false`, answers three more exceptions: `ContentRejectedException` with 400 `CONTENT_REJECTED`, `IdempotencyKeyConflictException` with 422 `IDEMPOTENCY_CONFLICT`, `InvalidIdempotencyKeyException` with 400 `BAD_REQUEST` | A test that raises every exception the handler handles and holds each answer to the contract's `x-error-codes` fails on the pairs the contract lacks | Register `CONTENT_REJECTED` (400) and `IDEMPOTENCY_CONFLICT` (422) in `x-error-codes`; every registry already holds `BAD_REQUEST` (400). `RecentAuthExceptionHandler`, which answers `REAUTH_REQUIRED`, is a separate advice: add it to the sweep when the module adopts `@RecentAuthRequired` |
| The startup check of [surface authorization](#surface-authorization-openapi-x-ef-surface), below | A module whose security chain opens, to a caller without a token, a route that is neither a `security: []` operation nor a technical path (`/ws/**`, `/error`, `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui.html`, `/swagger-ui/**`), or closes a `security: []` operation, does not start, in `log-only` too | Make the chain and the contract agree; the exception lists every route |
| Surface authorization, in `enforce` | A test that boots the context and calls an operation without `x-ef-surface` with a token lacking `SCOPE_ef:surface:full` gets 403: a MockMvc `jwt()` (default scope `read`), or a real token from a realm that does not grant the scope | Give the test token `SCOPE_ef:surface:full`, or mark the operation `x-ef-surface: member` when it is meant for the app |

**It refuses every token until the realm grants `ef:surface:full`.** Set
`ecclesiaflow.security.surface.mode=log-only` in the same release as the bump (see the surface
paragraph below).

Known cases when 0.6.0 was cut, from each module's unit tests (`mvn test`, no Docker; the tests that
boot a context were not run, so the startup checks in this section are not measured):
communication does not compile (`TemplateResolver`, its two `switch` expressions on
`EmailTemplateType`); auth fails `GrpcServerConfigTest.configuredServerEnforcesS2s` and church
`ChurchServiceGrpcControllerTest.theScopeRegistryAcceptsTheService`, on the RPC without an
annotation; church and chat fail `ErrorCodeRegistryTest.everyAnswerIsRegistered`, church on
`CONTENT_REJECTED` and chat on `CONTENT_REJECTED` and `IDEMPOTENCY_CONFLICT`; members compiles and
its unit tests pass. Read in the code, not run: once it compiles, communication still does not start,
because `provider-callbacks.yaml` declares no `security` while its `SecurityConfig` opens
`/internal/providers/ses/notifications` (give that operation `security: []` in the same upgrade); in
`enforce`, auth's `SecurityConfigurationIntegrationTest` and its two-factor realm tests would get 403.

**It starts by itself on a module that has the classes it needs:**

| Component | Active when | After a bare bump |
|---|---|---|
| API contract index (`PlatformApiContractAutoConfiguration`) | a servlet application | **On.** Reads `classpath*:api/*.yaml` at startup. The module does not start when a location matches no file, a file cannot be parsed, a path item, a parameter or a parameter schema uses a `$ref` that is not local (`#/…`), a key is repeated or two operations share a method and path. `ecclesiaflow.contract.locations` points at a contract kept elsewhere |
| Surface authorization (`SurfaceScopeAuthorizationAutoConfiguration`) | a servlet application with `JwtAuthenticationToken`, `WebInvocationPrivilegeEvaluator` and `DispatcherServlet` on the classpath | **On, in `enforce` mode** (see below) |
| Idempotency (`PlatformIdempotencyAutoConfiguration`) | always; `IdempotentCreator` needs one `PlatformTransactionManager`, the key resolver a servlet application | On, inert. It acts on an operation marked `x-ef-idempotency` only, and refuses to start on a mark that is misdeclared. A contract without the mark is untouched |
| Recent sign-in (`PlatformRecentAuthAutoConfiguration`) | a servlet application with `JwtAuthenticationToken`, `DispatcherServlet` and AspectJ on the classpath | On, inert. Nothing is guarded until a method carries `@RecentAuthRequired`; the module then declares a `SignInMethodsClient` and a `PendingConfirmationIssuer`, or does not start |
| JWKS cache (`FaultTolerantJwkSource`, in the REST decoder) | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` set, `ecclesiaflow.rest.jwt.enabled` not `false`, no module `JwtDecoder` bean | On for a module that uses the shared decoder; a module with its own `JwtDecoder` is unchanged. Keys are still reloaded every 5 minutes, but a failed reload now keeps the last set for up to 24 hours instead of refusing every token, an unknown `kid` reloads at once (at most every 30 seconds), and a reload waits at most 2 seconds to connect and 2 to read. No property is required; a zero or negative duration refuses the startup |
| Term filter (`PlatformTermFilterAutoConfiguration`) | `ecclesiaflow.moderation.terms-file` is present | Off until the property is set. Present but blank, or naming a missing file, refuses the startup: bridge `${MODERATION_TERMS_FILE:}` only where the deploy always sets the variable |
| Traceparent filter (`PlatformTraceparentAutoConfiguration`) | a servlet application, `ecclesiaflow.platform.web.traceparent.enabled` not `false` | On. A valid `traceparent` header puts its trace id in the MDC as `traceId`; nothing shows until the log pattern prints it, and nothing else changes |
| Delayed retries (`PlatformDelayedRetryAutoConfiguration`) | the module declares a `DelayedRetryTopology` bean | Off; the signing advice is unchanged |

**Surface authorization refuses requests from the first start.** An operation without
`x-ef-surface` is console-only: a request whose token does not carry `ef:surface:full` gets the
module's 403, and so does one for a path outside the contract that is not technical (`/ws/**`,
`/error`, `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui.html`, `/swagger-ui/**`). Requests without
a token and `security: []` operations are never ruled. A contract gains `x-ef-surface` only when the
module adopts it, so after a bare bump every operation that is not `security: []` is console-only,
and only a token carrying `ef:surface:full` passes. When 0.6.0 was cut no client of the realm was
granted that scope, so in `enforce` every token is refused, the web console's included. Set
`ecclesiaflow.security.surface.mode=log-only` in the same release as the bump, and keep it until the
realm grants `ef:surface:full` to the web client and the refusal log shows no web token: refusals
are then logged (`SURFACE: ⚠ Would refuse`), counted (`ef_surface_refused_total`) and let through,
and the contract is still read and checked at startup. No `ecclesiaflow.*` property turns the rule
off; only excluding `SurfaceScopeAuthorizationAutoConfiguration` does.

### What a module opts into, and how

| To use | Do | Documented in |
|---|---|---|
| A surface per operation | `x-ef-surface: member` or `platform` on the operation | [Surface authorization](#surface-authorization-openapi-x-ef-surface) |
| An idempotent creation | `x-ef-idempotency: required` on a POST, the optional `Idempotency-Key` header, `IdempotentCreator` in the service | [Idempotent creations](#idempotent-creations-openapi-x-ef-idempotency) |
| A recent sign-in before a gesture | `@RecentAuthRequired` on the method, a `SignInMethodsClient` and a `PendingConfirmationIssuer` | [Recent sign-in](#recent-sign-in-before-sensitive-gestures-recentauthrequired) |
| The term filter | `ecclesiaflow.moderation.terms-file`, bridged from `MODERATION_TERMS_FILE` | [Moderation term filter](#moderation-term-filter) |
| Delayed retries of an external effect | a `DelayedRetryTopology` bean per queue and `DelayedRetryMessageRecoverer` in the listener's retry interceptor | [Delayed retries](#delayed-retries-for-consumers-with-an-external-effect-opt-in) |
| Exact bindings | the `EventRoutes` constant of each key, never a wildcard | [Contracts](#grpc-and-domain-event-contracts) |
| The trace id in the logs | `%X{traceId}` in the log pattern | [Trace id](#trace-id-in-the-logs) |
| Other key reload timings | `ecclesiaflow.rest.jwt.jwks.*` | [REST decoder](#rest-resource-server-decoder) |

## Consuming the library (downstream modules)

The artifact is published on **GitHub Packages**, in two flavours:

| Flavour | Coordinates | Published |
|---|---|---|
| **Release** | `com.ecclesiaflow:ecclesiaflow-platform-core:X.Y.Z` | From a `vX.Y.Z` tag. Immutable. |
| **Snapshot** | `com.ecclesiaflow:ecclesiaflow-platform-core:X.Y.Z-SNAPSHOT` | On every push to `main`, replacing the previous build. |

A module pins a released `X.Y.Z`; a `-SNAPSHOT` is for iterating locally (see
[Local development against an unreleased lib](#local-development-against-an-unreleased-lib)).
Publish this library before a module that needs a change in it: the module's build resolves the
pinned version from GitHub Packages.

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
    <version>X.Y.Z</version>  <!-- a released version (a vX.Y.Z tag); a -SNAPSHOT only while iterating locally -->
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
| Breaking change in any public API, before `1.0.0` | minor (`0.5.2 → 0.6.0`), with an Upgrading section in this README |
| Breaking change in any public API, from `1.0.0` | major (`1.2.0 → 2.0.0`) |

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
