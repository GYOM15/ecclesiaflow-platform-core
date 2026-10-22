package com.ecclesiaflow.platform.security.reauth;

import com.ecclesiaflow.platform.security.reauth.events.RecentAuthEvents;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** One test per row of ARCHITECTURE-MOBILE §3.8's table, and per way a token can fail it. */
@ExtendWith(MockitoExtension.class)
class RecentAuthPolicyTest {

    private static final String SUB = "3f2c1d4e-1111-4222-8333-944455556666";
    // A fraction of a second, which auth_time never has: the limit must still pass at its exact second.
    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00.700Z");
    private static final Duration ACCOUNT_CHANGE = Duration.ofSeconds(300);
    private static final Duration REACTIVATION = Duration.ofSeconds(600);

    private static final SignInMethods PASSWORD_ONLY = new SignInMethods(true, List.of());
    private static final SignInMethods GOOGLE_LINKED = new SignInMethods(false, List.of("google"));
    private static final SignInMethods PASSWORD_AND_GOOGLE = new SignInMethods(true, List.of("google"));

    @Mock
    private SignInMethodsClient signInMethods;

    private final List<Object> events = new ArrayList<>();

    private RecentAuthPolicy policy() {
        return new RecentAuthPolicy(signInMethods, Clock.fixed(NOW, ZoneOffset.UTC), events::add);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static JwtAuthenticationToken token(Consumer<Map<String, Object>> claims) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(SUB)
                .issuedAt(NOW.minusSeconds(30)).expiresAt(NOW.plusSeconds(270))
                .claims(claims).build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES, SUB);
    }

    private static JwtAuthenticationToken signedInSecondsAgo(long seconds) {
        return token(claims -> claims.put("auth_time", NOW.getEpochSecond() - seconds));
    }

    private ReauthenticationRequiredException.Reason refusal(Authentication authentication, Duration maxAge) {
        Throwable thrown = catchThrowable(() -> policy().require(authentication, maxAge));
        assertThat(thrown).isInstanceOf(ReauthenticationRequiredException.class);
        return ((ReauthenticationRequiredException) thrown).getReason();
    }

    @Nested
    @DisplayName("an account linked to a provider")
    class LinkedAccount {

        @Test
        @DisplayName("is sent to confirm out of band, without looking at auth_time")
        void outOfBand() {
            when(signInMethods.find(SUB)).thenReturn(GOOGLE_LINKED);

            assertThat(policy().require(signedInSecondsAgo(5), ACCOUNT_CHANGE))
                    .isEqualTo(RecentAuthVerdict.CONFIRM_OUT_OF_BAND);
            assertThat(policy().require(signedInSecondsAgo(86_400), ACCOUNT_CHANGE))
                    .isEqualTo(RecentAuthVerdict.CONFIRM_OUT_OF_BAND);
            assertThat(policy().require(token(claims -> { }), ACCOUNT_CHANGE))
                    .isEqualTo(RecentAuthVerdict.CONFIRM_OUT_OF_BAND);
        }

        @Test
        @DisplayName("is sent to confirm out of band even with a password and a fresh, unbrokered sign-in")
        void passwordToo() {
            when(signInMethods.find(SUB)).thenReturn(PASSWORD_AND_GOOGLE);

            assertThat(policy().require(signedInSecondsAgo(1), ACCOUNT_CHANGE))
                    .isEqualTo(RecentAuthVerdict.CONFIRM_OUT_OF_BAND);
        }

        @Test
        @DisplayName("is sent to confirm out of band whatever its token says about the broker")
        void brokeredToken() {
            when(signInMethods.find(SUB)).thenReturn(GOOGLE_LINKED);
            JwtAuthenticationToken brokered = token(claims -> {
                claims.put("auth_time", NOW.getEpochSecond() - 10);
                claims.put("identity_provider", "google");
            });

            assertThat(policy().require(brokered, ACCOUNT_CHANGE)).isEqualTo(RecentAuthVerdict.CONFIRM_OUT_OF_BAND);
        }

        @Test
        @DisplayName("counts a provider the caller does not know as a link")
        void unknownProvider() {
            when(signInMethods.find(SUB)).thenReturn(new SignInMethods(true, List.of("some-future-idp")));

            assertThat(policy().require(signedInSecondsAgo(1), ACCOUNT_CHANGE))
                    .isEqualTo(RecentAuthVerdict.CONFIRM_OUT_OF_BAND);
            assertThat(events).containsExactly(new RecentAuthEvents.ConfirmationRequired("3f2c1d4e********",
                    List.of("some-future-idp")));
        }
    }

    @Nested
    @DisplayName("an account without a provider link")
    class UnlinkedAccount {

        @ParameterizedTest(name = "signed in {0} s ago")
        @ValueSource(longs = {0, 1, 299, 300})
        @DisplayName("passes with a sign-in at most 300 s old")
        void recent(long seconds) {
            when(signInMethods.find(SUB)).thenReturn(PASSWORD_ONLY);

            assertThat(policy().require(signedInSecondsAgo(seconds), ACCOUNT_CHANGE))
                    .isEqualTo(RecentAuthVerdict.PASSED);
        }

        @ParameterizedTest(name = "signed in {0} s ago")
        @ValueSource(longs = {301, 600, 86_400})
        @DisplayName("is refused REAUTH_REQUIRED past 300 s")
        void tooOld(long seconds) {
            when(signInMethods.find(SUB)).thenReturn(PASSWORD_ONLY);

            assertThat(refusal(signedInSecondsAgo(seconds), ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.SIGN_IN_TOO_OLD);
        }

        @Test
        @DisplayName("gets 600 s for a reactivation, and not one more")
        void reactivation() {
            when(signInMethods.find(SUB)).thenReturn(PASSWORD_ONLY);

            assertThat(policy().require(signedInSecondsAgo(600), REACTIVATION)).isEqualTo(RecentAuthVerdict.PASSED);
            assertThat(refusal(signedInSecondsAgo(601), REACTIVATION))
                    .isEqualTo(ReauthenticationRequiredException.Reason.SIGN_IN_TOO_OLD);
        }

        @Test
        @DisplayName("is refused when the token says a provider brokered the sign-in, however fresh")
        void brokered() {
            when(signInMethods.find(SUB)).thenReturn(PASSWORD_ONLY);
            JwtAuthenticationToken brokered = token(claims -> {
                claims.put("auth_time", NOW.getEpochSecond());
                claims.put("identity_provider", "google");
            });

            assertThat(refusal(brokered, ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.BROKERED_SIGN_IN);
            assertThat(events).containsExactly(new RecentAuthEvents.Refused("3f2c1d4e********",
                    ReauthenticationRequiredException.Reason.BROKERED_SIGN_IN, 0L, 300));
        }

        @Test
        @DisplayName("is refused when the token does not say when the user signed in")
        void noAuthTime() {
            when(signInMethods.find(SUB)).thenReturn(PASSWORD_ONLY);

            assertThat(refusal(token(claims -> { }), ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.NO_SIGN_IN_TIME);
            assertThat(refusal(token(claims -> claims.put("auth_time", "yesterday")), ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.NO_SIGN_IN_TIME);
            assertThat(refusal(token(claims -> claims.put("auth_time", Long.MAX_VALUE)), ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.NO_SIGN_IN_TIME);
            assertThat(refusal(token(claims -> claims.put("auth_time", Long.MIN_VALUE)), ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.NO_SIGN_IN_TIME);
        }

        @Test
        @DisplayName("reads an auth_time a converter turned into an Instant")
        void instantAuthTime() {
            when(signInMethods.find(SUB)).thenReturn(PASSWORD_ONLY);

            assertThat(policy().require(token(claims -> claims.put("auth_time", NOW.minusSeconds(10))),
                    ACCOUNT_CHANGE)).isEqualTo(RecentAuthVerdict.PASSED);
        }

        @Test
        @DisplayName("is judged the same for a web and a mobile token")
        void anyToken() {
            when(signInMethods.find(SUB)).thenReturn(PASSWORD_ONLY);
            JwtAuthenticationToken web = token(claims -> {
                claims.put("auth_time", NOW.getEpochSecond() - 400);
                claims.put("azp", "ecclesiaflow-frontend");
            });
            JwtAuthenticationToken mobile = token(claims -> {
                claims.put("auth_time", NOW.getEpochSecond() - 400);
                claims.put("azp", "ecclesiaflow-mobile");
            });

            assertThat(refusal(web, ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.SIGN_IN_TOO_OLD);
            assertThat(refusal(mobile, ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.SIGN_IN_TOO_OLD);
        }

        @Test
        @DisplayName("reports its age and limit when it passes")
        void passedEvent() {
            when(signInMethods.find(SUB)).thenReturn(PASSWORD_ONLY);

            policy().require(signedInSecondsAgo(42), ACCOUNT_CHANGE);

            assertThat(events).containsExactly(new RecentAuthEvents.Passed("3f2c1d4e********", 42, 300));
        }
    }

    @Nested
    @DisplayName("sign-in methods that cannot be read")
    class Unavailable {

        @Test
        @DisplayName("refuse the gesture 503, never REAUTH_REQUIRED, never a pass, even for a fresh sign-in")
        void portFailure() {
            StatusRuntimeException down = Status.UNAVAILABLE.withDescription("auth:9090 unreachable")
                    .asRuntimeException();
            when(signInMethods.find(SUB)).thenThrow(down);

            assertThatThrownBy(() -> policy().require(signedInSecondsAgo(1), ACCOUNT_CHANGE))
                    .isInstanceOf(SignInMethodsUnavailableException.class)
                    .hasCause(down);
            assertThat(events)
                    .containsExactly(new RecentAuthEvents.SignInMethodsUnavailable("3f2c1d4e********", down));
        }

        @Test
        @DisplayName("include a deadline or any other failure of the adapter")
        void anyFailure() {
            when(signInMethods.find(anyString())).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException(),
                    new NullPointerException("bug in the adapter"));

            assertThatThrownBy(() -> policy().require(signedInSecondsAgo(1), ACCOUNT_CHANGE))
                    .isInstanceOf(SignInMethodsUnavailableException.class);
            assertThatThrownBy(() -> policy().require(signedInSecondsAgo(1), ACCOUNT_CHANGE))
                    .isInstanceOf(SignInMethodsUnavailableException.class)
                    .hasCauseInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("include an adapter that answers nothing")
        void noAnswer() {
            when(signInMethods.find(SUB)).thenReturn(null);

            assertThatThrownBy(() -> policy().require(signedInSecondsAgo(1), ACCOUNT_CHANGE))
                    .isInstanceOf(SignInMethodsUnavailableException.class)
                    .hasMessage("The account's sign-in methods could not be read")
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(events).singleElement().isInstanceOf(RecentAuthEvents.SignInMethodsUnavailable.class);
        }
    }

    @Nested
    @DisplayName("a request without a user's token")
    class NoUserToken {

        @Test
        @DisplayName("is refused without asking auth")
        void refused() {
            JwtAuthenticationToken noSubject = new JwtAuthenticationToken(Jwt.withTokenValue("t")
                    .header("alg", "RS256").claim("auth_time", NOW.getEpochSecond()).build(),
                    AuthorityUtils.NO_AUTHORITIES);
            JwtAuthenticationToken unauthenticated = signedInSecondsAgo(1);
            unauthenticated.setAuthenticated(false);

            assertThat(refusal(null, ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.NO_USER_TOKEN);
            assertThat(refusal(new TestingAuthenticationToken(SUB, "p", "ROLE_USER"), ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.NO_USER_TOKEN);
            assertThat(refusal(new AnonymousAuthenticationToken("k", "anonymous",
                    AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")), ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.NO_USER_TOKEN);
            assertThat(refusal(noSubject, ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.NO_USER_TOKEN);
            assertThat(refusal(unauthenticated, ACCOUNT_CHANGE))
                    .isEqualTo(ReauthenticationRequiredException.Reason.NO_USER_TOKEN);
            verifyNoInteractions(signInMethods);
        }
    }

    @Test
    @DisplayName("reads the request's authentication when none is given")
    void currentRequest() {
        when(signInMethods.find(SUB)).thenReturn(PASSWORD_ONLY);
        SecurityContextHolder.getContext().setAuthentication(signedInSecondsAgo(10));

        assertThat(policy().require(ACCOUNT_CHANGE)).isEqualTo(RecentAuthVerdict.PASSED);
    }

    @Test
    @DisplayName("refuses a limit that is not a positive duration")
    void invalidLimit() {
        assertThatThrownBy(() -> policy().require(signedInSecondsAgo(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy().require(signedInSecondsAgo(1), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy().require(signedInSecondsAgo(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
