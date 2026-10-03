package com.ecclesiaflow.platform.events.amqp;

import com.ecclesiaflow.grpc.events.church.ChurchInvitationCreatedEvent;
import com.ecclesiaflow.grpc.events.church.MemberAdmittedToChurchEvent;
import com.ecclesiaflow.grpc.events.church.MemberRemovedFromChurchEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtobufMessageConverterTest {

    private static final String TYPE_ID_HEADER = "__TypeId__";

    private static final MemberRemovedFromChurchEvent REMOVAL = MemberRemovedFromChurchEvent.newBuilder()
            .setEventId("e-1").setChurchId("c-1").setMemberUserId("m-1").build();

    private static final ChurchInvitationCreatedEvent INVITATION = ChurchInvitationCreatedEvent.newBuilder()
            .setEventId("evt-1").setRecipientEmail("invitee@example.com").setChurchName("Bethel").build();

    @Nested
    @DisplayName("Writing")
    class Writing {

        private final ProtobufMessageConverter converter = new ProtobufMessageConverter();

        @Test
        @DisplayName("Stamps the content type and the concrete class name the consumer loads")
        void stampsTheWireHeaders() {
            Message message = converter.toMessage(INVITATION, new MessageProperties());

            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/x-protobuf");
            assertThat((String) message.getMessageProperties().getHeader(TYPE_ID_HEADER))
                    .isEqualTo("com.ecclesiaflow.grpc.events.church.ChurchInvitationCreatedEvent");
            assertThat(message.getBody()).isEqualTo(INVITATION.toByteArray());
        }

        @Test
        @DisplayName("Only converts protobuf messages")
        void refusesAnythingElse() {
            assertThatThrownBy(() -> converter.toMessage("not-a-proto", new MessageProperties()))
                    .isInstanceOf(MessageConversionException.class)
                    .hasMessageContaining("Protobuf Message");
        }
    }

    @Nested
    @DisplayName("Reading, strict")
    class Strict {

        private final ProtobufMessageConverter converter = new ProtobufMessageConverter();

        @Test
        @DisplayName("Round-trips every event through the type header")
        void roundTrips() {
            MemberAdmittedToChurchEvent admission = MemberAdmittedToChurchEvent.newBuilder().setEventId("e-2").build();

            assertThat(converter.fromMessage(converter.toMessage(REMOVAL, new MessageProperties()))).isEqualTo(REMOVAL);
            assertThat(converter.fromMessage(converter.toMessage(admission, new MessageProperties())))
                    .isEqualTo(admission);
        }

        // The church events share their field numbers: a removal read as another type parses as an admission.
        @Test
        @DisplayName("Refuses a type it does not know instead of reading it as another event")
        void refusesAnUnknownType() {
            Message message = withType(REMOVAL, "com.ecclesiaflow.grpc.events.church.v2.MemberRemovedFromChurchEvent");

            assertThatThrownBy(() -> converter.fromMessage(message))
                    .isInstanceOf(MessageConversionException.class)
                    .hasMessageContaining("Unknown message type");
        }

        @Test
        @DisplayName("Refuses a message without a type")
        void refusesAMessageWithoutAType() {
            Message message = new Message(REMOVAL.toByteArray(), new MessageProperties());

            assertThatThrownBy(() -> converter.fromMessage(message))
                    .isInstanceOf(MessageConversionException.class)
                    .hasMessageContaining("no __TypeId__");
        }
    }

    @Nested
    @DisplayName("Reading, with a default type")
    class WithDefaultType {

        private final ProtobufMessageConverter converter = new ProtobufMessageConverter(ChurchInvitationCreatedEvent.class);

        @Test
        @DisplayName("Parses the class named by the header, not the default")
        void prefersTheHeader() {
            assertThat(converter.fromMessage(converter.toMessage(REMOVAL, new MessageProperties()))).isEqualTo(REMOVAL);
        }

        @Test
        @DisplayName("Reads a message without a type as the default type")
        void readsAnUntypedMessageAsTheDefault() {
            Message message = new Message(INVITATION.toByteArray(), new MessageProperties());

            assertThat(converter.fromMessage(message)).isEqualTo(INVITATION);
        }

        @Test
        @DisplayName("Reads a platform type it does not know as the default type")
        void readsAnUnknownTypeAsTheDefault() {
            Message message = withType(INVITATION, "com.ecclesiaflow.grpc.events.church.DoesNotExist");

            assertThat(converter.fromMessage(message)).isEqualTo(INVITATION);
        }

        @Test
        @DisplayName("Still refuses a type outside the platform's packages")
        void stillRefusesAnUntrustedType() {
            Message message = withType(INVITATION, "java.lang.String");

            assertThatThrownBy(() -> converter.fromMessage(message))
                    .isInstanceOf(MessageConversionException.class)
                    .hasMessageContaining("Untrusted message type");
        }
    }

    @Nested
    @DisplayName("Reading, in both modes")
    class BothModes {

        private final ProtobufMessageConverter strict = new ProtobufMessageConverter();
        private final ProtobufMessageConverter lenient = new ProtobufMessageConverter(ChurchInvitationCreatedEvent.class);

        @Test
        @DisplayName("Refuses a type outside the platform's packages")
        void refusesAnUntrustedType() {
            assertRefused(withType(REMOVAL, "java.lang.Runtime"), "Untrusted message type");
        }

        @Test
        @DisplayName("Refuses a blank type")
        void refusesABlankType() {
            assertRefused(withType(REMOVAL, " "), "Untrusted message type");
        }

        @Test
        @DisplayName("Refuses a header that is not a class name")
        void refusesANonStringHeader() {
            MessageProperties properties = new MessageProperties();
            properties.setHeader(TYPE_ID_HEADER, 42);

            assertRefused(new Message(REMOVAL.toByteArray(), properties), "not a class name");
        }

        @Test
        @DisplayName("Refuses a platform class that is not a protobuf message, without calling it")
        void refusesANonMessageType() {
            assertRefused(withType(REMOVAL, ParseableButNotAMessage.class.getName()), "Not a protobuf message type");
        }

        @Test
        @DisplayName("Refuses a body that is not the message its type names")
        void refusesAnUnreadableBody() {
            MessageProperties properties = new MessageProperties();
            properties.setHeader(TYPE_ID_HEADER, MemberRemovedFromChurchEvent.class.getName());
            Message message = new Message(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, properties);

            assertRefused(message, "Failed to convert Protobuf message");
        }

        private void assertRefused(Message message, String reason) {
            assertThatThrownBy(() -> strict.fromMessage(message))
                    .isInstanceOf(MessageConversionException.class)
                    .hasMessageContaining(reason);
            assertThatThrownBy(() -> lenient.fromMessage(message))
                    .isInstanceOf(MessageConversionException.class)
                    .hasMessageContaining(reason);
        }
    }

    @Test
    @DisplayName("A default type is required when one is asked for")
    void requiresTheDefaultType() {
        assertThatThrownBy(() -> new ProtobufMessageConverter(null)).isInstanceOf(NullPointerException.class);
    }

    private static Message withType(com.google.protobuf.Message event, String typeId) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(TYPE_ID_HEADER, typeId);
        return new Message(event.toByteArray(), properties);
    }

    /** A trusted class with the factory a protobuf message has, that a header must not reach. */
    public static final class ParseableButNotAMessage {

        private ParseableButNotAMessage() {
        }

        public static Object parseFrom(byte[] body) {
            throw new AssertionError("a non-protobuf class was invoked from a message header");
        }
    }
}
