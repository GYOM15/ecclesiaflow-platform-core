package com.ecclesiaflow.grpc.auth;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth gives the provisioned account the church signup's interface language, and must still serve a
 * church build that predates the field.
 */
@DisplayName("ProvisionUserAndIssueSetupTokenRequest - locale")
class ProvisionUserAndIssueSetupTokenRequestTest {

    @Test
    @DisplayName("Carries the signup's locale beside the email")
    void carriesTheLocale() throws InvalidProtocolBufferException {
        ProvisionUserAndIssueSetupTokenRequest sent = ProvisionUserAndIssueSetupTokenRequest.newBuilder()
                .setEmail("pastor@church.org")
                .setFirstName("Jane")
                .setLastName("Doe")
                .setLocale("en")
                .build();

        ProvisionUserAndIssueSetupTokenRequest received =
                ProvisionUserAndIssueSetupTokenRequest.parseFrom(sent.toByteArray());

        assertThat(received.getLocale()).isEqualTo("en");
        assertThat(received.getEmail()).isEqualTo("pastor@church.org");
    }

    @Test
    @DisplayName("A request sent before the field existed reads with an empty locale")
    void olderRequestHasNoLocale() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeString(1, "pastor@church.org");
        out.writeString(4, "{\"church_id\":\"5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11\"}");
        out.flush();

        ProvisionUserAndIssueSetupTokenRequest received =
                ProvisionUserAndIssueSetupTokenRequest.parseFrom(bytes.toByteArray());

        assertThat(received.getLocale()).isEmpty();
        assertThat(received.getMetadata()).isEqualTo("{\"church_id\":\"5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11\"}");
    }
}
