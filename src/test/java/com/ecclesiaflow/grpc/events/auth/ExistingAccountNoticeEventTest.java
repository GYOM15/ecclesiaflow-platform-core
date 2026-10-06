package com.ecclesiaflow.grpc.events.auth;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox delivers at least once, so communication dedupes on the event id, and it must still read a
 * notice from an auth build that predates the field.
 */
@DisplayName("ExistingAccountNoticeEvent - event id")
class ExistingAccountNoticeEventTest {

    @Test
    @DisplayName("Carries the event id beside the recipient")
    void carriesTheEventId() throws InvalidProtocolBufferException {
        ExistingAccountNoticeEvent sent = ExistingAccountNoticeEvent.newBuilder()
                .setEventId("5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11")
                .setEmail("pastor@church.org")
                .setLocale("fr")
                .build();

        ExistingAccountNoticeEvent received = ExistingAccountNoticeEvent.parseFrom(sent.toByteArray());

        assertThat(received.getEventId()).isEqualTo("5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11");
        assertThat(received.getEmail()).isEqualTo("pastor@church.org");
    }

    @Test
    @DisplayName("A notice written before the field existed reads with an empty event id")
    void olderNoticeHasNoEventId() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeString(1, "pastor@church.org");
        out.writeString(2, "en");
        out.flush();

        ExistingAccountNoticeEvent received = ExistingAccountNoticeEvent.parseFrom(bytes.toByteArray());

        assertThat(received.getEventId()).isEmpty();
        assertThat(received.getEmail()).isEqualTo("pastor@church.org");
        assertThat(received.getLocale()).isEqualTo("en");
    }
}
