package com.ecclesiaflow.grpc.events.church;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The push it feeds crosses Expo, APNs and FCM in clear, so the event names recipients and a target,
 * never what the notification says.
 */
@DisplayName("NotificationCreatedEvent - wire contract")
class NotificationCreatedEventTest {

    private static final Pattern CONTENT_FIELD_NAME = Pattern.compile(
            ".*(param|text|body|title|name|email|phone|message|content|label|caption).*",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("Declares no field named after the text of the notification or a person")
    void declaresNoContentField() {
        assertThat(allFields(NotificationCreatedEvent.getDescriptor()))
                .extracting(FieldDescriptor::getName)
                .noneMatch(name -> CONTENT_FIELD_NAME.matcher(name).matches());
    }

    @Test
    @DisplayName("Nests its helper types, with no enum or oneof")
    void nestsOnlyItsHelperTypes() {
        Descriptor descriptor = NotificationCreatedEvent.getDescriptor();

        assertThat(descriptor.getNestedTypes()).extracting(Descriptor::getName)
                .containsExactly("Target", "Recipient");
        assertThat(descriptor.getEnumTypes()).isEmpty();
        assertThat(descriptor.getOneofs()).isEmpty();
    }

    @Test
    @DisplayName("Survives the wire with its target and every recipient of the batch")
    void roundTripsTheBatch() throws Exception {
        NotificationCreatedEvent sent = NotificationCreatedEvent.newBuilder()
                .setEventId("e6b0c1f2-6f43-4a8e-9a57-0c5a3c1f2b10")
                .setOccurredAtEpochMs(1_790_000_000_000L)
                .setChurchId("c1")
                .setType("EVENT_CREATED")
                .setTarget(NotificationCreatedEvent.Target.newBuilder().setKind("event").setId("ev1"))
                .addRecipients(NotificationCreatedEvent.Recipient.newBuilder()
                        .setKeycloakUserId("u1").setNotificationId("n1"))
                .addRecipients(NotificationCreatedEvent.Recipient.newBuilder()
                        .setKeycloakUserId("u2").setNotificationId("n2"))
                .build();

        NotificationCreatedEvent received = NotificationCreatedEvent.parseFrom(sent.toByteArray());

        assertThat(received).isEqualTo(sent);
        assertThat(received.getRecipientsList())
                .extracting(NotificationCreatedEvent.Recipient::getNotificationId)
                .containsExactly("n1", "n2");
    }

    private static List<FieldDescriptor> allFields(Descriptor descriptor) {
        List<FieldDescriptor> fields = new ArrayList<>(descriptor.getFields());
        descriptor.getNestedTypes().forEach(nested -> fields.addAll(allFields(nested)));
        return fields;
    }
}
