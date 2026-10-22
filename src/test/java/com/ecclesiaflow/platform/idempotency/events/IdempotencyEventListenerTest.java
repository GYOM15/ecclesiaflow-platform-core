package com.ecclesiaflow.platform.idempotency.events;

import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyEventListenerTest {

    private static final String AUTHOR = "7d0c2b1e-6a43-4c55-9a1e-2f4b8c9d0e11";
    private static final UUID KEY = UUID.fromString("5f0e9d8c-7b6a-4e3d-9c2b-1a0f9e8d7c6b");

    private final IdempotencyEventListener listener = new IdempotencyEventListener();
    private final LogCaptor logs = LogCaptor.forClass(IdempotencyEventListener.class);

    @AfterEach
    void tearDown() {
        logs.resetLogLevel();
        logs.close();
    }

    @Test
    @DisplayName("a routine replay is a DEBUG line naming the operation, author and key masked")
    void replay() {
        logs.setLogLevelToDebug();
        listener.onReplayed(new IdempotencyEvents.Replayed("EventComment.Create", AUTHOR, KEY, false));

        assertThat(logs.getInfoLogs()).isEmpty();
        assertThat(logs.getDebugLogs()).singleElement().asString()
                .isEqualTo("IDEMPOTENCY: EventComment.Create replayed (author=7d0c2b1e******** key=5f0e9d8c********)")
                .doesNotContain(AUTHOR)
                .doesNotContain(KEY.toString());
    }

    @Test
    @DisplayName("a race settled by the unique index is an INFO line")
    void race() {
        listener.onReplayed(new IdempotencyEvents.Replayed("Message.Post", AUTHOR, KEY, true));

        assertThat(logs.getInfoLogs()).singleElement().asString()
                .startsWith("IDEMPOTENCY: ✅ Message.Post — concurrent duplicate settled by the unique index")
                .contains("author=7d0c2b1e********", "key=5f0e9d8c********")
                .doesNotContain(AUTHOR);
    }

    @Test
    @DisplayName("a reused key is a WARN line, saying when the other request was still in flight")
    void conflict() {
        listener.onConflicted(new IdempotencyEvents.Conflicted("EventComment.Create", AUTHOR, KEY, false));
        listener.onConflicted(new IdempotencyEvents.Conflicted("EventComment.Create", AUTHOR, KEY, true));

        assertThat(logs.getWarnLogs()).containsExactly(
                "IDEMPOTENCY: ⚠ EventComment.Create refused a key reused for a different request "
                        + "(author=7d0c2b1e******** key=5f0e9d8c********)",
                "IDEMPOTENCY: ⚠ EventComment.Create refused a key reused for a different request in flight "
                        + "(author=7d0c2b1e******** key=5f0e9d8c********)");
    }
}
