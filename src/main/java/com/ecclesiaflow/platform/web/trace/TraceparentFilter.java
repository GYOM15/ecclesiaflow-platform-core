package com.ecclesiaflow.platform.web.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts the trace id of a valid W3C {@code traceparent} header in the MDC under {@value #MDC_KEY} for the
 * duration of the request, so every log line of the request can carry it. A missing or invalid header is
 * ignored: the id is a correlation aid, never a reason to refuse a request. It is only read, never generated
 * or sent on, and the parent id and the flags are not kept.
 */
public class TraceparentFilter extends OncePerRequestFilter {

    public static final String HEADER = "traceparent";
    public static final String MDC_KEY = "traceId";

    /** Ahead of Spring Security's chain (order -100), so the filters behind it log with the id too. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    // Version 00 is the only one defined, and its header is exactly this long: a later version's layout is not ours to read.
    private static final Pattern VERSION_00 = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");
    private static final String NO_TRACE_ID = "0".repeat(32);
    private static final String NO_PARENT_ID = "0".repeat(16);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = traceIdOf(request);
        if (traceId == null) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    // A repeated header is ambiguous: none of its values is trusted.
    private static String traceIdOf(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(HEADER);
        // The servlet API lets a container that hides its headers answer null.
        List<String> values = headers == null ? List.of() : Collections.list(headers);
        if (values.size() != 1) {
            return null;
        }
        Matcher header = VERSION_00.matcher(values.get(0));
        if (!header.matches() || NO_TRACE_ID.equals(header.group(1)) || NO_PARENT_ID.equals(header.group(2))) {
            return null;
        }
        return header.group(1);
    }
}
