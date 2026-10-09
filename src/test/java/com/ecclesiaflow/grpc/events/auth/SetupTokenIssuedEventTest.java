package com.ecclesiaflow.grpc.events.auth;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * communication is deployed before auth seals, so during the rollout it must read both the sealed
 * token and one staged by an auth build that only knew the clear field. It writes the setup email in
 * the account's language, and an event from an auth build that predates the locale carries none.
 */
@DisplayName("SetupTokenIssuedEvent - sealed token and locale")
class SetupTokenIssuedEventTest {

    @Test
    @DisplayName("Carries the sealed token and the id of the key that sealed it")
    void carriesTheSealedToken() throws InvalidProtocolBufferException {
        ByteString sealed = ByteString.copyFrom(new byte[]{1, 2, 3, 4});
        SetupTokenIssuedEvent sent = SetupTokenIssuedEvent.newBuilder()
                .setEventId("5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11")
                .setEmail("pastor@church.org")
                .setSealedRawToken(sealed)
                .setSealedKeyId("2026-10")
                .build();

        SetupTokenIssuedEvent received = SetupTokenIssuedEvent.parseFrom(sent.toByteArray());

        assertThat(received.getSealedRawToken()).isEqualTo(sealed);
        assertThat(received.getSealedKeyId()).isEqualTo("2026-10");
    }

    @Test
    @SuppressWarnings("deprecation")
    @DisplayName("An event staged before sealing reads with no sealed token and its clear one")
    void olderEventHasNoSealedToken() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeString(1, "5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11");
        out.writeString(3, "pastor@church.org");
        out.writeString(4, "raw-token");
        out.flush();

        SetupTokenIssuedEvent received = SetupTokenIssuedEvent.parseFrom(bytes.toByteArray());

        assertThat(received.getSealedRawToken().isEmpty()).isTrue();
        assertThat(received.getSealedKeyId()).isEmpty();
        assertThat(received.getRawToken()).isEqualTo("raw-token");
    }

    @Test
    @DisplayName("Carries the account's locale beside the recipient")
    void carriesTheLocale() throws InvalidProtocolBufferException {
        SetupTokenIssuedEvent sent = SetupTokenIssuedEvent.newBuilder()
                .setEventId("5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11")
                .setEmail("pastor@church.org")
                .setLocale("en")
                .build();

        SetupTokenIssuedEvent received = SetupTokenIssuedEvent.parseFrom(sent.toByteArray());

        assertThat(received.getLocale()).isEqualTo("en");
        assertThat(received.getEmail()).isEqualTo("pastor@church.org");
    }

    @Test
    @DisplayName("An event staged before the locale existed reads with an empty locale")
    void olderEventHasNoLocale() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeString(1, "5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11");
        out.writeString(3, "pastor@church.org");
        out.writeString(5, "church_signup");
        out.flush();

        SetupTokenIssuedEvent received = SetupTokenIssuedEvent.parseFrom(bytes.toByteArray());

        assertThat(received.getLocale()).isEmpty();
        assertThat(received.getFlowId()).isEqualTo("church_signup");
    }
}
