package com.ecclesiaflow.platform.webhooks.web;

import com.ecclesiaflow.platform.web.error.ApiErrorResponse;
import com.ecclesiaflow.platform.web.trace.TraceparentFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * An anonymous provider route reads at most its bound: a declared length past it is refused unread, an undeclared
 * one once a byte past it arrives, so a forged body costs no more than the bound before its signature is checked.
 * The filter answers the refusal itself, which no controller advice could do once the body is read in a filter.
 * Within the bound the body is handed on from memory.
 */
public class WebhookBodyLimitFilter extends OncePerRequestFilter {

    /** Ahead of Spring Security's chain (order -100), behind the trace id so the refusal logs with it. */
    public static final int ORDER = TraceparentFilter.ORDER + 10;

    public static final String ERROR_CODE = "PAYLOAD_TOO_LARGE";

    private static final int CHUNK = 8_192;

    private static final UrlPathHelper DECODED_PATHS = new UrlPathHelper();
    private static final UrlPathHelper RAW_PATHS = rawPaths();

    private final List<String> pathPrefixes;
    private final int maxBytes;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public WebhookBodyLimitFilter(List<String> pathPrefixes, int maxBytes, Clock clock) {
        if (pathPrefixes == null || pathPrefixes.isEmpty()
                || pathPrefixes.stream().anyMatch(prefix -> prefix == null || !prefix.startsWith("/"))) {
            throw new IllegalArgumentException(
                    "ecclesiaflow.webhooks.body-limit.path-prefixes lists the routes to bound, each starting with /");
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("ecclesiaflow.webhooks.body-limit.max-bytes must be positive");
        }
        this.pathPrefixes = List.copyOf(pathPrefixes);
        this.maxBytes = maxBytes;
        this.clock = clock;
    }

    // Matched on the decoded and the raw path alike, repeated slashes folded: a route the dispatcher would still
    // reach under another spelling must not escape the bound.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return Stream.of(DECODED_PATHS.getPathWithinApplication(request), RAW_PATHS.getPathWithinApplication(request))
                .map(path -> path.replaceAll("/{2,}", "/"))
                .noneMatch(path -> pathPrefixes.stream().anyMatch(path::startsWith));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            refuse(request, response);
            return;
        }
        byte[] body = readAtMost(request.getInputStream(), maxBytes + 1);
        if (body.length > maxBytes) {
            refuse(request, response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    // One byte past the bound is enough to know the body is too large; more is never asked of the stream.
    private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        byte[] chunk = new byte[Math.min(CHUNK, limit)];
        int total = 0;
        while (total < limit) {
            int got = in.read(chunk, 0, Math.min(chunk.length, limit - total));
            if (got < 0) {
                break;
            }
            read.write(chunk, 0, got);
            total += got;
        }
        return read.toByteArray();
    }

    // Built from the shared body so the refusal reads like every other error; the instant is rendered here, as
    // the library cannot count on the module's Jackson time module.
    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiErrorResponse error = ApiErrorResponse.of(clock.instant(), HttpStatus.PAYLOAD_TOO_LARGE, ERROR_CODE,
                "The body exceeds " + maxBytes + " bytes.", request.getRequestURI());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", error.timestamp().toString());
        body.put("status", error.status());
        body.put("error", error.error());
        body.put("errorCode", error.errorCode());
        body.put("message", error.message());
        body.put("path", error.path());
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // The rest of an oversized body is never read, so the connection cannot carry another request.
        response.setHeader("Connection", "close");
        response.getOutputStream().write(json.writeValueAsBytes(body));
    }

    private static UrlPathHelper rawPaths() {
        UrlPathHelper raw = new UrlPathHelper();
        raw.setUrlDecode(false);
        raw.setRemoveSemicolonContent(false);
        return raw;
    }

    static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;
        private final BufferedBodyInputStream stream;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
            this.stream = new BufferedBodyInputStream(body);
        }

        @Override
        public ServletInputStream getInputStream() {
            return stream;
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(stream, charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    static final class BufferedBodyInputStream extends ServletInputStream {

        private final ByteArrayInputStream body;

        BufferedBodyInputStream(byte[] body) {
            this.body = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return body.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return body.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return body.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        // The body is already in memory: it is all available at once.
        @Override
        public void setReadListener(ReadListener listener) {
            try {
                if (!isFinished()) {
                    listener.onDataAvailable();
                }
                listener.onAllDataRead();
            } catch (IOException e) {
                listener.onError(e);
            }
        }
    }
}
