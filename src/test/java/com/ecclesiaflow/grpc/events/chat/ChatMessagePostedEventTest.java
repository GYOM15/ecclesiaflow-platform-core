package com.ecclesiaflow.grpc.events.chat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The push it feeds crosses Expo, APNs and FCM in clear, so the event carries identifiers only, never
 * the message, a name or a conversation title.
 */
@DisplayName("ChatMessagePostedEvent - wire contract")
class ChatMessagePostedEventTest {

    private static final Pattern CONTENT_FIELD_NAME = Pattern.compile(
            ".*(text|body|title|name|email|phone|content|label|caption|image|preview).*",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("Declares no field named after the message or a person")
    void declaresNoContentField() {
        assertThat(ChatMessagePostedEvent.getDescriptor().getFields())
                .extracting(FieldDescriptor::getName)
                .noneMatch(name -> CONTENT_FIELD_NAME.matcher(name).matches());
    }

    @Test
    @DisplayName("Declares no nested message, enum or oneof that could carry data")
    void declaresNoNestedStructure() {
        Descriptor descriptor = ChatMessagePostedEvent.getDescriptor();

        assertThat(descriptor.getNestedTypes()).isEmpty();
        assertThat(descriptor.getEnumTypes()).isEmpty();
        assertThat(descriptor.getOneofs()).isEmpty();
    }

    @Test
    @DisplayName("Survives the wire with every recipient of the batch")
    void roundTripsTheBatch() throws Exception {
        ChatMessagePostedEvent sent = ChatMessagePostedEvent.newBuilder()
                .setEventId("chat-msg:m1:0")
                .setOccurredAtEpochMs(1_790_000_000_000L)
                .setChurchId("c1")
                .setConversationId("conv1")
                .setMessageId("m1")
                .setSenderUserId("u0")
                .setConversationKind("GROUP")
                .setIsRequest(false)
                .addRecipientUserIds("u1")
                .addRecipientUserIds("u2")
                .build();

        ChatMessagePostedEvent received = ChatMessagePostedEvent.parseFrom(sent.toByteArray());

        assertThat(received).isEqualTo(sent);
        assertThat(received.getRecipientUserIdsList()).containsExactly("u1", "u2");
    }
}
