package com.ecclesiaflow.grpc.events.auth;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Communication tells the inbox whenever a second factor goes; the device label is text the member typed,
 * and a producer newer than the consumer may name a removal it does not know.
 */
@DisplayName("TwoFactorRemovedEvent - wire contract")
class TwoFactorRemovedEventTest {

    private static final Pattern TYPED_TEXT_FIELD_NAME = Pattern.compile(
            ".*(label|name|device_|credential|secret|code).*", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("Declares no field for the device label or a credential")
    void declaresNoTypedText() {
        assertThat(TwoFactorRemovedEvent.getDescriptor().getFields())
                .extracting(FieldDescriptor::getName)
                .noneMatch(name -> TYPED_TEXT_FIELD_NAME.matcher(name).matches());
    }

    @Test
    @DisplayName("A removal value the consumer does not know reads as unrecognized, never as a known one")
    void unknownRemovalIsUnrecognized() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeString(1, "5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11");
        out.writeInt64(2, 1_790_000_000_000L);
        out.writeString(3, "member@church.org");
        out.writeEnum(5, 9);
        out.flush();

        TwoFactorRemovedEvent received = TwoFactorRemovedEvent.parseFrom(bytes.toByteArray());

        assertThat(received.getRemoval()).isEqualTo(TwoFactorRemovedEvent.Removal.UNRECOGNIZED);
        assertThat(received.getRemovalValue()).isEqualTo(9);
    }
}
