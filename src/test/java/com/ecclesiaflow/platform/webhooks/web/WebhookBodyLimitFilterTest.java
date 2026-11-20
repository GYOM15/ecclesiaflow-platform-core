package com.ecclesiaflow.platform.webhooks.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WebhookBodyLimitFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final int MAX = 64;
    private static final String ROUTE = "/payments/webhooks/stripe/accounts";

    private final WebhookBodyLimitFilter filter = new WebhookBodyLimitFilter(
            List.of("/payments/webhooks/"), MAX, Clock.fixed(NOW, ZoneOffset.UTC));

    /** A body that counts what was asked of it, so a test sees how far the filter read. */
    static final class CountingStream extends ServletInputStream {
        private final ByteArrayInputStream body;
        int bytesRead;
        int reads;

        CountingStream(byte[] body) {
            this.body = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            reads++;
            int next = body.read();
            if (next >= 0) {
                bytesRead++;
            }
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            reads++;
            int got = body.read(buffer, offset, length);
            if (got > 0) {
                bytesRead += got;
            }
            return got;
        }

        @Override
        public boolean isFinished() {
            return body.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException();
        }
    }

    /** A request whose body is the counting stream, with or without a declared length. */
    private static MockHttpServletRequest post(String uri, CountingStream body, long declaredLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public ServletInputStream getInputStream() {
                return body;
            }

            @Override
            public long getContentLengthLong() {
                return declaredLength;
            }
        };
        request.setContentType("application/json");
        return request;
    }

    private static byte[] bytes(int size) {
        byte[] body = new byte[size];
        Arrays.fill(body, (byte) 'a');
        return body;
    }

    private static JsonNode json(MockHttpServletResponse response) throws IOException {
        return new ObjectMapper().readTree(response.getContentAsByteArray());
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("refuses to bound nothing: an enabled filter without a route would protect no route")
        void needsARoute() {
            assertThatThrownBy(() -> new WebhookBodyLimitFilter(List.of(), MAX, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path-prefixes");
            assertThatThrownBy(() -> new WebhookBodyLimitFilter(null, MAX, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses a route that is not a path")
        void needsAbsolutePaths() {
            assertThatThrownBy(() -> new WebhookBodyLimitFilter(List.of("payments/"), MAX, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class);
            List<String> withNull = new ArrayList<>();
            withNull.add(null);
            assertThatThrownBy(() -> new WebhookBodyLimitFilter(withNull, MAX, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses a bound that admits no byte")
        void needsAPositiveBound() {
            assertThatThrownBy(() -> new WebhookBodyLimitFilter(List.of("/payments/"), 0, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-bytes");
        }
    }

    @Nested
    @DisplayName("routes")
    class Routes {

        @Test
        @DisplayName("leaves any other route untouched, its body unread")
        void otherRoutesPassThrough() throws Exception {
            CountingStream body = new CountingStream(bytes(MAX * 4));
            MockHttpServletRequest request = post("/ecclesiaflow/members/me", body, MAX * 4);
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).isSameAs(request);
            assertThat(body.reads).isZero();
        }

        @Test
        @DisplayName("bounds a route spelt with repeated slashes, an encoded letter or a path parameter")
        void boundsEverySpellingOfTheRoute() throws Exception {
            for (String uri : List.of("//payments//webhooks/stripe/accounts", "/payments/%77ebhooks/stripe/accounts",
                    "/payments;x=1/webhooks/stripe/accounts")) {
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilter(post(uri, new CountingStream(bytes(MAX + 1)), MAX + 1), response, new MockFilterChain());

                assertThat(response.getStatus()).as(uri).isEqualTo(413);
            }
        }
    }

    @Nested
    @DisplayName("past the bound")
    class PastTheBound {

        @Test
        @DisplayName("refuses a declared length past the bound without reading a byte")
        void declaredTooLarge() throws Exception {
            CountingStream body = new CountingStream(bytes(MAX + 1));
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(post(ROUTE, body, MAX + 1), response, chain);

            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(body.reads).isZero();
            assertThat(chain.getRequest()).isNull();
        }

        @Test
        @DisplayName("refuses an undeclared body once a byte past the bound arrives, reading no further")
        void undeclaredTooLarge() throws Exception {
            CountingStream body = new CountingStream(bytes(MAX * 100));
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(post(ROUTE, body, -1), response, chain);

            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(body.bytesRead).isEqualTo(MAX + 1);
            assertThat(chain.getRequest()).isNull();
        }

        @Test
        @DisplayName("refuses a body that lied about its length")
        void understatedLength() throws Exception {
            CountingStream body = new CountingStream(bytes(MAX * 2));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(post(ROUTE, body, 10), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(body.bytesRead).isEqualTo(MAX + 1);
        }

        @Test
        @DisplayName("answers in the shared error body, and closes the connection it did not drain")
        void answersTheSharedBody() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(post(ROUTE, new CountingStream(bytes(MAX + 1)), MAX + 1), response, new MockFilterChain());

            JsonNode body = json(response);
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getHeader("Connection")).isEqualTo("close");
            assertThat(body.get("timestamp").asText()).isEqualTo("2026-11-20T12:00:00Z");
            assertThat(body.get("status").asInt()).isEqualTo(413);
            assertThat(body.get("error").asText()).isEqualTo("Payload Too Large");
            assertThat(body.get("errorCode").asText()).isEqualTo(WebhookBodyLimitFilter.ERROR_CODE);
            assertThat(body.get("message").asText()).isEqualTo("The body exceeds 64 bytes.");
            assertThat(body.get("path").asText()).isEqualTo(ROUTE);
        }
    }

    @Nested
    @DisplayName("within the bound")
    class WithinTheBound {

        private ServletRequest passOn(byte[] body, long declaredLength) throws Exception {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(post(ROUTE, new CountingStream(body), declaredLength), new MockHttpServletResponse(), chain);
            return chain.getRequest();
        }

        @Test
        @DisplayName("hands the whole body on, exactly at the bound included")
        void handsTheBodyOn() throws Exception {
            byte[] body = bytes(MAX);

            ServletRequest passed = passOn(body, -1);

            assertThat(passed.getInputStream().readAllBytes()).isEqualTo(body);
            assertThat(passed.getContentLength()).isEqualTo(MAX);
            assertThat(passed.getContentLengthLong()).isEqualTo(MAX);
            assertThat(passed.getInputStream().isFinished()).isTrue();
            assertThat(passed.getInputStream().isReady()).isTrue();
        }

        @Test
        @DisplayName("reads the body byte by byte as well as in blocks")
        void readsByteByByte() throws Exception {
            ServletInputStream in = passOn("{}".getBytes(StandardCharsets.UTF_8), 2).getInputStream();

            assertThat(in.isFinished()).isFalse();
            assertThat(in.read()).isEqualTo('{');
            assertThat(in.read()).isEqualTo('}');
            assertThat(in.read()).isEqualTo(-1);
        }

        @Test
        @DisplayName("gives a reader in the request's encoding, UTF-8 when it names none")
        void givesAReader() throws Exception {
            byte[] body = "{\"note\":\"é\"}".getBytes(StandardCharsets.UTF_8);

            try (BufferedReader reader = ((HttpServletRequest) passOn(body, body.length)).getReader()) {
                assertThat(reader.readLine()).isEqualTo("{\"note\":\"é\"}");
            }

            MockHttpServletRequest latin = post(ROUTE, new CountingStream("é".getBytes(StandardCharsets.ISO_8859_1)), 1);
            latin.setCharacterEncoding("ISO-8859-1");
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(latin, new MockHttpServletResponse(), chain);
            try (BufferedReader reader = ((HttpServletRequest) chain.getRequest()).getReader()) {
                assertThat(reader.readLine()).isEqualTo("é");
            }
        }

        @Test
        @DisplayName("tells a non-blocking reader the body is all there")
        void servesANonBlockingReader() throws Exception {
            ReadListener listener = mock(ReadListener.class);

            passOn(bytes(3), 3).getInputStream().setReadListener(listener);

            verify(listener).onDataAvailable();
            verify(listener).onAllDataRead();
        }

        @Test
        @DisplayName("tells a non-blocking reader of an empty body only that it is finished")
        void servesAnEmptyBody() throws Exception {
            ReadListener listener = mock(ReadListener.class);

            passOn(new byte[0], 0).getInputStream().setReadListener(listener);

            verify(listener, never()).onDataAvailable();
            verify(listener).onAllDataRead();
        }

        @Test
        @DisplayName("hands a reader's own failure back to it")
        void reportsTheReadersFailure() throws Exception {
            ReadListener listener = mock(ReadListener.class);
            IOException failure = new IOException("listener broke");
            doThrow(failure).when(listener).onDataAvailable();

            passOn(bytes(3), 3).getInputStream().setReadListener(listener);

            verify(listener).onError(failure);
        }
    }
}
