package com.ecclesiaflow.grpc.members;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Church writes a member's notice in the language read here, and must still read a members build that
 * predates the field.
 */
@DisplayName("GetMemberProfileResponse - email_locale")
class GetMemberProfileResponseTest {

    @Test
    @DisplayName("Carries the language of the member's emails beside the address")
    void carriesTheLocale() throws InvalidProtocolBufferException {
        GetMemberProfileResponse sent = GetMemberProfileResponse.newBuilder()
                .setFound(true)
                .setEmail("member@church.org")
                .setEmailLocale("en")
                .build();

        GetMemberProfileResponse received = GetMemberProfileResponse.parseFrom(sent.toByteArray());

        assertThat(received.getEmailLocale()).isEqualTo("en");
        assertThat(received.getEmail()).isEqualTo("member@church.org");
    }

    @Test
    @DisplayName("A response sent before the field existed reads with an empty locale")
    void olderResponseHasNoLocale() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeBool(1, true);
        out.writeString(2, "Marie");
        out.writeString(3, "Tremblay");
        out.writeString(4, "member@church.org");
        out.writeEnum(9, BaptismDeclaration.BAPTISM_DECLARATION_BAPTISED_VALUE);
        out.flush();

        GetMemberProfileResponse received = GetMemberProfileResponse.parseFrom(bytes.toByteArray());

        assertThat(received.getEmailLocale()).isEmpty();
        assertThat(received.getFound()).isTrue();
        assertThat(received.getBaptismDeclared()).isEqualTo(BaptismDeclaration.BAPTISM_DECLARATION_BAPTISED);
    }
}
