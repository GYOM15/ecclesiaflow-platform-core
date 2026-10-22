package com.ecclesiaflow.platform.web.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceparentFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String VALID = "00-" + TRACE_ID + "-b7ad6b7169203331-01";

    private final TraceparentFilter filter = new TraceparentFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** The MDC as the rest of the chain sees it, one snapshot per call. */
    private static final class RecordingChain implements FilterChain {

        final List<Map<String, String>> seen = new ArrayList<>();

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            Map<String, String> context = MDC.getCopyOfContextMap();
            seen.add(context == null ? Map.of() : context);
        }

        String traceIdSeen() {
            assertThat(seen).hasSize(1);
            return seen.get(0).get(TraceparentFilter.MDC_KEY);
        }
    }

    private RecordingChain run(MockHttpServletRequest request) throws Exception {
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);
        return chain;
    }

    private static MockHttpServletRequest requestWith(String... traceparents) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/members/me");
        for (String value : traceparents) {
            request.addHeader("traceparent", value);
        }
        return request;
    }

    @Nested
    @DisplayName("a valid traceparent")
    class Valid {

        @Test
        @DisplayName("puts its trace id in the MDC for the rest of the chain")
        void copiesTheTraceId() throws Exception {
            RecordingChain chain = run(requestWith(VALID));

            assertThat(chain.traceIdSeen()).isEqualTo(TRACE_ID);
        }

        @Test
        @DisplayName("keeps nothing but the trace id: not the parent id, the flags or the header")
        void keepsOnlyTheTraceId() throws Exception {
            RecordingChain chain = run(requestWith(VALID));

            assertThat(chain.seen.get(0)).containsOnlyKeys("traceId");
        }

        @Test
        @DisplayName("removes it once the request is served")
        void removedAfterTheRequest() throws Exception {
            run(requestWith(VALID));

            assertThat(MDC.get("traceId")).isNull();
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }

        @Test
        @DisplayName("removes it when the rest of the chain throws, and lets the failure through")
        void removedAfterAFailure() {
            FilterChain failing = (request, response) -> {
                throw new ServletException("boom");
            };

            assertThatThrownBy(() -> filter.doFilter(requestWith(VALID), response, failing))
                    .isInstanceOf(ServletException.class).hasMessage("boom");
            assertThat(MDC.get("traceId")).isNull();
        }

        @Test
        @DisplayName("leaves the other keys of the MDC as they were")
        void otherKeysUntouched() throws Exception {
            MDC.put("tenant", "church-1");

            RecordingChain chain = run(requestWith(VALID));

            assertThat(chain.seen.get(0)).containsEntry("tenant", "church-1").containsEntry("traceId", TRACE_ID);
            assertThat(MDC.get("tenant")).isEqualTo("church-1");
            assertThat(MDC.get("traceId")).isNull();
        }

        @Test
        @DisplayName("is read whatever the case of the header name")
        void headerNameCase() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
            request.addHeader("Traceparent", VALID);

            assertThat(run(request).traceIdSeen()).isEqualTo(TRACE_ID);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "00-00000000000000000000000000000001-0000000000000001-00",
                "00-ffffffffffffffffffffffffffffffff-ffffffffffffffff-ff",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00"})
        @DisplayName("is accepted at the edges of the format, a trace that is not sampled included")
        void edgesOfTheFormat(String header) throws Exception {
            assertThat(run(requestWith(header)).traceIdSeen()).isEqualTo(header.substring(3, 35));
        }

        @Test
        @DisplayName("is read once when the request passes the filter twice, as a forward does")
        void servedOnce() throws Exception {
            RecordingChain chain = new RecordingChain();
            FilterChain again = (request, response) -> filter.doFilter(request, response, chain);

            filter.doFilter(requestWith(VALID), response, again);

            assertThat(chain.traceIdSeen()).isEqualTo(TRACE_ID);
            assertThat(MDC.get("traceId")).isNull();
        }
    }

    @Nested
    @DisplayName("an invalid or absent traceparent")
    class Ignored {

        @Test
        @DisplayName("is ignored without a header")
        void noHeader() throws Exception {
            RecordingChain chain = run(requestWith());

            assertThat(chain.traceIdSeen()).isNull();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {
                "garbage",
                "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "ff-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "0-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "000-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319c-B7AD6B7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0A",
                "00-0af7651916cd43dd8448eb211c80319-b7ad6b7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319cc-b7ad6b7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b716920333-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b71692033311-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-1",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-001",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-",
                "00-0af7651916cd43dd8448eb211c80319g-b7ad6b7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b716920333z-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0g",
                "00-00000000000000000000000000000000-b7ad6b7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01-extra",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01-",
                " 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01 ",
                "00_0af7651916cd43dd8448eb211c80319c_b7ad6b7169203331_01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\n00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"})
        @DisplayName("is ignored and the request still goes through")
        void invalid(String header) throws Exception {
            RecordingChain chain = run(requestWith(header == null ? new String[0] : new String[]{header}));

            assertThat(chain.traceIdSeen()).isNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("is ignored when the header is repeated, even if every value is valid")
        void repeatedHeader() throws Exception {
            RecordingChain chain = run(requestWith(VALID, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));

            assertThat(chain.traceIdSeen()).isNull();
        }

        @Test
        @DisplayName("is ignored when the container hides its headers")
        void containerHidesHeaders() throws Exception {
            MockHttpServletRequest hidden = mock(MockHttpServletRequest.class);
            when(hidden.getHeaders("traceparent")).thenReturn(null);

            assertThat(run(hidden).traceIdSeen()).isNull();
        }

        @Test
        @DisplayName("leaves what the MDC already held untouched")
        void leavesTheMdcAlone() throws Exception {
            MDC.put("traceId", "set-by-someone-else");

            RecordingChain chain = run(requestWith("not-a-traceparent"));

            assertThat(chain.traceIdSeen()).isEqualTo("set-by-someone-else");
            assertThat(MDC.get("traceId")).isEqualTo("set-by-someone-else");
        }

        @Test
        @DisplayName("is ignored when the container lists no value")
        void containerListsNothing() throws Exception {
            MockHttpServletRequest empty = mock(MockHttpServletRequest.class);
            when(empty.getHeaders("traceparent")).thenReturn(Collections.emptyEnumeration());

            assertThat(run(empty).traceIdSeen()).isNull();
        }
    }
}
