package com.ecclesiaflow.grpc.auth;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth keeps the stored second-factor obligation when a church build older than the field calls, so an
 * absent value must stay distinguishable from false.
 */
@DisplayName("SetChurchClaimsRequest - two_factor_required")
class SetChurchClaimsRequestTest {

    @Test
    @DisplayName("An explicit false is present on the receiving side")
    void explicitFalseIsPresent() throws InvalidProtocolBufferException {
        SetChurchClaimsRequest sent = SetChurchClaimsRequest.newBuilder()
                .setKeycloakUserId("u1")
                .setChurchId("c1")
                .setTwoFactorRequired(false)
                .build();

        SetChurchClaimsRequest received = SetChurchClaimsRequest.parseFrom(sent.toByteArray());

        assertThat(received.hasTwoFactorRequired()).isTrue();
        assertThat(received.getTwoFactorRequired()).isFalse();
    }

    @Test
    @DisplayName("A request sent before the field existed reads with the obligation absent")
    void olderRequestHasNoObligation() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeString(1, "u1");
        out.writeString(2, "c1");
        out.writeBool(3, true);
        out.flush();

        SetChurchClaimsRequest received = SetChurchClaimsRequest.parseFrom(bytes.toByteArray());

        assertThat(received.hasTwoFactorRequired()).isFalse();
        assertThat(received.getChurchAdmin()).isTrue();
    }
}
