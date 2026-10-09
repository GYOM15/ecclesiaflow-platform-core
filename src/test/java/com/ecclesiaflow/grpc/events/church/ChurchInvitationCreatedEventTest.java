package com.ecclesiaflow.grpc.events.church;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Communication writes the invitation in the inviting side's language, and must still read an event
 * staged by a church build that predates the field.
 */
@DisplayName("ChurchInvitationCreatedEvent - locale")
class ChurchInvitationCreatedEventTest {

    @Test
    @DisplayName("Carries the invitation's locale beside the recipient")
    void carriesTheLocale() throws InvalidProtocolBufferException {
        ChurchInvitationCreatedEvent sent = ChurchInvitationCreatedEvent.newBuilder()
                .setEventId("5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11")
                .setRecipientEmail("guest@church.org")
                .setLocale("en")
                .build();

        ChurchInvitationCreatedEvent received = ChurchInvitationCreatedEvent.parseFrom(sent.toByteArray());

        assertThat(received.getLocale()).isEqualTo("en");
        assertThat(received.getRecipientEmail()).isEqualTo("guest@church.org");
    }

    @Test
    @DisplayName("An event staged before the field existed reads with an empty locale")
    void olderEventHasNoLocale() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeString(1, "5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11");
        out.writeString(3, "guest@church.org");
        out.writeInt32(7, 604800);
        out.flush();

        ChurchInvitationCreatedEvent received = ChurchInvitationCreatedEvent.parseFrom(bytes.toByteArray());

        assertThat(received.getLocale()).isEmpty();
        assertThat(received.getExpiresInSeconds()).isEqualTo(604800);
    }
}
