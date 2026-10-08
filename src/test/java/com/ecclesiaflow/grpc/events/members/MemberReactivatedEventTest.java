package com.ecclesiaflow.grpc.events.members;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Consumers restore the person by key and by address digest; nothing that could be read back as the person. */
@DisplayName("MemberReactivatedEvent - wire contract")
class MemberReactivatedEventTest {

    private static final Pattern PII_FIELD_NAME = Pattern.compile(
            ".*(name|email|phone|address|birth|caption|baptism|avatar|locale).*",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("Carries the identity fields and the recipient digests, nothing else")
    void carriesTheKeyAndTheDigests() {
        Descriptor descriptor = MemberReactivatedEvent.getDescriptor();

        assertThat(descriptor.getFullName())
                .isEqualTo("ecclesiaflow.events.members.v1.MemberReactivatedEvent");
        assertThat(descriptor.getFields())
                .extracting(FieldDescriptor::getName, FieldDescriptor::getNumber,
                        FieldDescriptor::getJavaType, FieldDescriptor::isRepeated)
                .containsExactly(
                        tuple("event_id", 1, JavaType.STRING, false),
                        tuple("occurred_at_epoch_ms", 2, JavaType.LONG, false),
                        tuple("keycloak_user_id", 3, JavaType.STRING, false),
                        tuple("recipient_digests", 4, JavaType.STRING, true));
    }

    @Test
    @DisplayName("Declares no field named after the data it must not carry")
    void declaresNoPiiField() {
        assertThat(MemberReactivatedEvent.getDescriptor().getFields())
                .extracting(FieldDescriptor::getName)
                .noneMatch(name -> PII_FIELD_NAME.matcher(name).matches());
    }

    @Test
    @DisplayName("Declares no nested message, enum or oneof that could carry data")
    void declaresNoNestedStructure() {
        Descriptor descriptor = MemberReactivatedEvent.getDescriptor();

        assertThat(descriptor.getNestedTypes()).isEmpty();
        assertThat(descriptor.getEnumTypes()).isEmpty();
        assertThat(descriptor.getOneofs()).isEmpty();
    }
}
