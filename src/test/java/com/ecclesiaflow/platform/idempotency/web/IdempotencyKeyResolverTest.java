package com.ecclesiaflow.platform.idempotency.web;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.idempotency.IdempotencyKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

import static com.ecclesiaflow.platform.idempotency.web.IdempotencyTestContracts.legacy;
import static com.ecclesiaflow.platform.idempotency.web.IdempotencyTestContracts.marked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyResolverTest {

    private static final String KEY = "5f0e9d8c-7b6a-4e3d-9c2b-1a0f9e8d7c6b";

    private final IdempotencyKeyResolver resolver = new IdempotencyKeyResolver(new IdempotencyIndex(ApiContract.of(
            List.of(marked("/events/{eventId}/comments", "Comment.Create"), legacy("/signups", "Signup.Create")))));

    private static MockHttpServletRequest post(String path, String... keys) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        for (String key : keys) {
            request.addHeader(IdempotencyHeaders.KEY, key);
        }
        return request;
    }

    // Another test class of the run may leave a request bound to this thread.
    @BeforeEach
    @AfterEach
    void unbind() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Nested
    @DisplayName("on an operation marked x-ef-idempotency")
    class Marked {

        @Test
        @DisplayName("hands over the key with the operation it was sent to")
        void key() {
            assertThat(resolver.resolve(post("/events/42/comments", KEY)))
                    .contains(new IdempotencyKey(UUID.fromString(KEY), "Comment.Create"));
        }

        @Test
        @DisplayName("takes a key in upper case, and the path within the application")
        void upperCaseAndContextPath() {
            MockHttpServletRequest request = post("/church/events/42/comments", KEY.toUpperCase());
            request.setContextPath("/church");

            assertThat(resolver.resolve(request)).get().extracting(IdempotencyKey::value)
                    .isEqualTo(UUID.fromString(KEY));
        }

        @Test
        @DisplayName("hands over nothing when the request carries no key: the creation runs as before")
        void noKey() {
            assertThat(resolver.resolve(post("/events/42/comments"))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  ", "not-a-uuid", "1-2-3-4-5", "5f0e9d8c7b6a4e3d9c2b1a0f9e8d7c6b",
                "{5f0e9d8c-7b6a-4e3d-9c2b-1a0f9e8d7c6b}", KEY + "," + KEY})
        @DisplayName("refuses a value that is not one canonical UUID")
        void malformed(String value) {
            assertThatThrownBy(() -> resolver.resolve(post("/events/42/comments", value)))
                    .isInstanceOf(InvalidIdempotencyKeyException.class)
                    .hasMessage("The Idempotency-Key header must hold exactly one UUID");
        }

        @Test
        @DisplayName("refuses two keys, even equal ones")
        void twoKeys() {
            assertThatThrownBy(() -> resolver.resolve(post("/events/42/comments", KEY, KEY)))
                    .isInstanceOf(InvalidIdempotencyKeyException.class);
        }
    }

    @Nested
    @DisplayName("anywhere else the header is ignored")
    class Unmarked {

        @Test
        @DisplayName("on an operation that declares the header without the extension")
        void legacyOperation() {
            assertThat(resolver.resolve(post("/signups", KEY))).isEmpty();
            assertThat(resolver.resolve(post("/signups", "not-a-uuid"))).isEmpty();
        }

        @Test
        @DisplayName("on another method of a marked path, or a path outside the contract")
        void otherRequests() {
            MockHttpServletRequest read = new MockHttpServletRequest("GET", "/events/42/comments");
            read.addHeader(IdempotencyHeaders.KEY, KEY);

            assertThat(resolver.resolve(read)).isEmpty();
            assertThat(resolver.resolve(post("/elsewhere", KEY))).isEmpty();
        }
    }

    @Nested
    @DisplayName("current() reads the request the thread serves")
    class Current {

        @Test
        @DisplayName("inside a request")
        void bound() {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(post("/events/42/comments", KEY)));

            assertThat(resolver.current()).get().extracting(IdempotencyKey::operation).isEqualTo("Comment.Create");
        }

        @Test
        @DisplayName("and fails outside one, rather than quietly creating without the primitive")
        void unbound() {
            assertThatThrownBy(resolver::current).isInstanceOf(IllegalStateException.class);
        }
    }
}
