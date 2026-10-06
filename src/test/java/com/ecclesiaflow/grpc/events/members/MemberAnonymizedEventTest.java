package com.ecclesiaflow.grpc.events.members;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Contract test for the {@code MemberAnonymizedEvent} wire format: the whole
 * point of the event is that the person's data is gone, so the message must
 * carry the cross-module key and nothing about the person. Asserted on the
 * protobuf descriptor rather than on an instance, so a field added to the
 * .proto fails here before any consumer ever sees it.
 */
@DisplayName("MemberAnonymizedEvent - wire contract")
class MemberAnonymizedEventTest {

    /** Field names that would smuggle profile data back into the event. */
    private static final Pattern PII_FIELD_NAME = Pattern.compile(
            ".*(name|email|phone|address|birth|caption|baptism|avatar|locale).*",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("Carries exactly event_id, occurred_at_epoch_ms and keycloak_user_id")
    void carriesExactlyTheThreeIdentityFields() {
        Descriptor descriptor = MemberAnonymizedEvent.getDescriptor();

        assertThat(descriptor.getFullName())
                .isEqualTo("ecclesiaflow.events.members.v1.MemberAnonymizedEvent");
        assertThat(descriptor.getFields())
                .extracting(FieldDescriptor::getName, FieldDescriptor::getNumber,
                        FieldDescriptor::getJavaType)
                .containsExactly(
                        tuple("event_id", 1, JavaType.STRING),
                        tuple("occurred_at_epoch_ms", 2, JavaType.LONG),
                        tuple("keycloak_user_id", 3, JavaType.STRING));
    }

    @Test
    @DisplayName("Declares no PII field, ever")
    void declaresNoPiiField() {
        assertThat(MemberAnonymizedEvent.getDescriptor().getFields())
                .extracting(FieldDescriptor::getName)
                .noneMatch(name -> PII_FIELD_NAME.matcher(name).matches());
    }

    @Test
    @DisplayName("Declares no nested message, enum or oneof that could carry data")
    void declaresNoNestedStructure() {
        Descriptor descriptor = MemberAnonymizedEvent.getDescriptor();

        assertThat(descriptor.getNestedTypes()).isEmpty();
        assertThat(descriptor.getEnumTypes()).isEmpty();
        assertThat(descriptor.getOneofs()).isEmpty();
    }
}
