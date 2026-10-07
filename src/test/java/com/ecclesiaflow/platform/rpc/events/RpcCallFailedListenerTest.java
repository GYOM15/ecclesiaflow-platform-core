package com.ecclesiaflow.platform.rpc.events;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listener is the one place an RPC failure becomes a log line, so its level and its
 * masking are behaviour, not decoration.
 */
class RpcCallFailedListenerTest {

    private final RpcCallFailedListener listener = new RpcCallFailedListener();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RpcCallFailedListener.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void attach() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("an INTERNAL failure is one ERROR line with the stack trace and a sanitized message")
    void internalIsError() {
        RuntimeException boom = new RuntimeException("connection to https://db.internal:5432/ef failed");

        listener.onRpcCallFailed(new RpcCallFailed("ef.Members/ProvisionMember", Status.Code.INTERNAL, boom));

        assertThat(appender.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
            assertThat(line.getThrowableProxy()).isNotNull();
            assertThat(line.getFormattedMessage())
                    .contains("ef.Members/ProvisionMember", "INTERNAL", "RuntimeException")
                    .doesNotContain("db.internal");
        });
    }

    @Test
    @DisplayName("an outage is a WARN without a stack trace")
    void unavailableIsWarn() {
        listener.onRpcCallFailed(new RpcCallFailed("ef.Auth/AssignRealmRole", Status.Code.UNAVAILABLE,
                new IllegalStateException("keycloak down")));

        assertThat(appender.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("a caller error is an INFO line")
    void callerErrorIsInfo() {
        listener.onRpcCallFailed(new RpcCallFailed("ef.Members/GetMember", Status.Code.NOT_FOUND,
                new IllegalArgumentException("no member")));

        assertThat(appender.list).singleElement()
                .satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    @DisplayName("an auth refusal stays at DEBUG, the s2s listener already reports it")
    void authRefusalIsDebug() {
        listener.onRpcCallFailed(new RpcCallFailed("ef.Members/GetMember", Status.Code.PERMISSION_DENIED, null));

        assertThat(appender.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(line.getFormattedMessage()).contains("no exception");
        });
    }
}
