package com.ecclesiaflow.grpc.events.moderation;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reported content stays in its source module, under its retention rule, so the moderation record and
 * the hourly summary built from these events never hold what was said or shown.
 */
@DisplayName("Moderation events - wire contract")
class ModerationEventsTest {

    private static final Pattern CONTENT_FIELD_NAME = Pattern.compile(
            ".*(text|body|title|name|email|phone|snapshot|details|label|caption|url|image).*",
            Pattern.CASE_INSENSITIVE);

    private static final List<Descriptor> EVENTS = List.of(
            ContentReportedEvent.getDescriptor(), ModerationActionTakenEvent.getDescriptor());

    @Test
    @DisplayName("Declare no field named after the content, the report's details or a person")
    void declareNoContentField() {
        assertThat(EVENTS).allSatisfy(event -> assertThat(event.getFields())
                .extracting(FieldDescriptor::getName)
                .as(event.getName())
                .noneMatch(name -> CONTENT_FIELD_NAME.matcher(name).matches()));
    }

    @Test
    @DisplayName("Declare no nested message, enum or oneof: their vocabularies are open strings")
    void declareNoNestedStructure() {
        assertThat(EVENTS).allSatisfy(event -> {
            assertThat(event.getNestedTypes()).as(event.getName()).isEmpty();
            assertThat(event.getEnumTypes()).as(event.getName()).isEmpty();
            assertThat(event.getOneofs()).as(event.getName()).isEmpty();
        });
    }

    @Test
    @DisplayName("An action without a report reads with an empty report id")
    void actionWithoutReport() throws Exception {
        ModerationActionTakenEvent sent = ModerationActionTakenEvent.newBuilder()
                .setEventId("a1")
                .setOccurredAtEpochMs(1_790_000_000_000L)
                .setAction("ACCOUNT_SUSPENDED")
                .setActorUserId("moderator")
                .build();

        ModerationActionTakenEvent received = ModerationActionTakenEvent.parseFrom(sent.toByteArray());

        assertThat(received.getReportId()).isEmpty();
        assertThat(received.getAction()).isEqualTo("ACCOUNT_SUSPENDED");
    }
}
