package com.ecclesiaflow.grpc.auth;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth gives the new account the signup's interface language, and must still serve a members build
 * that predates the field.
 */
@DisplayName("TemporaryTokenRequest - locale")
class TemporaryTokenRequestTest {

    @Test
    @DisplayName("Carries the signup's locale beside the email")
    void carriesTheLocale() throws InvalidProtocolBufferException {
        TemporaryTokenRequest sent = TemporaryTokenRequest.newBuilder()
                .setEmail("member@church.org")
                .setMemberId("5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11")
                .setLocale("en")
                .build();

        TemporaryTokenRequest received = TemporaryTokenRequest.parseFrom(sent.toByteArray());

        assertThat(received.getLocale()).isEqualTo("en");
        assertThat(received.getEmail()).isEqualTo("member@church.org");
    }

    @Test
    @DisplayName("A request sent before the field existed reads with an empty locale")
    void olderRequestHasNoLocale() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeString(1, "member@church.org");
        out.writeString(2, "5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11");
        out.flush();

        TemporaryTokenRequest received = TemporaryTokenRequest.parseFrom(bytes.toByteArray());

        assertThat(received.getLocale()).isEmpty();
        assertThat(received.getMemberId()).isEqualTo("5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11");
    }
}
