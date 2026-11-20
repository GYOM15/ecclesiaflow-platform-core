package com.ecclesiaflow.grpc.events.church;

import com.ecclesiaflow.grpc.events.EventOptionsProto;
import com.ecclesiaflow.grpc.events.EventRoutes;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A church's suspension tells its consumers which church and why, as a code: nothing about a person, no free text.
 * Asserted on the descriptor, so a field added to the .proto fails here before any consumer sees it.
 */
@DisplayName("ChurchSuspendedEvent and ChurchSuspensionLiftedEvent - wire contract")
class ChurchSuspensionEventsTest {

    private static final Pattern PERSONAL_OR_TEXT = Pattern.compile(
            ".*(name|email|phone|address|text|comment|note|message|detail).*", Pattern.CASE_INSENSITIVE);

    private static final List<Descriptor> EVENTS =
            List.of(ChurchSuspendedEvent.getDescriptor(), ChurchSuspensionLiftedEvent.getDescriptor());

    @Test
    @DisplayName("Each carries exactly the envelope, the church and an open reason code")
    void fields() {
        assertThat(EVENTS).allSatisfy(event -> assertThat(event.getFields())
                .extracting(FieldDescriptor::getName, FieldDescriptor::getNumber, FieldDescriptor::getJavaType)
                .containsExactly(
                        tuple("event_id", 1, JavaType.STRING),
                        tuple("occurred_at_epoch_ms", 2, JavaType.LONG),
                        tuple("church_id", 3, JavaType.STRING),
                        tuple("reason_code", 4, JavaType.STRING)));
    }

    @Test
    @DisplayName("Neither declares a personal or free-text field, a nested type, an enum or a oneof")
    void nothingElse() {
        assertThat(EVENTS).allSatisfy(event -> {
            assertThat(event.getFields()).extracting(FieldDescriptor::getName)
                    .noneMatch(name -> PERSONAL_OR_TEXT.matcher(name).matches());
            assertThat(event.getNestedTypes()).isEmpty();
            assertThat(event.getEnumTypes()).isEmpty();
            assertThat(event.getOneofs()).isEmpty();
        });
    }

    @Test
    @DisplayName("Each declares one key of church's, the one EventRoutes names")
    void routingKeys() {
        assertThat(ChurchSuspendedEvent.getDescriptor().getOptions().getExtension(EventOptionsProto.routingKeys))
                .containsExactly(EventRoutes.CHURCH_CHURCH_SUSPENDED_V1);
        assertThat(ChurchSuspensionLiftedEvent.getDescriptor().getOptions()
                .getExtension(EventOptionsProto.routingKeys))
                .containsExactly(EventRoutes.CHURCH_CHURCH_SUSPENSION_LIFTED_V1);
    }
}
