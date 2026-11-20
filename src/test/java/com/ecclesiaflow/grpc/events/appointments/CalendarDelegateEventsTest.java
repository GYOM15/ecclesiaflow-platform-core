package com.ecclesiaflow.grpc.events.appointments;

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
 * A designation grants a right outside the service, so its two events name the calendar and the two people
 * by opaque id and nothing else: the name the host gave a delegate stays in the service.
 */
@DisplayName("Calendar delegate events - wire contract")
class CalendarDelegateEventsTest {

    private static final List<Descriptor> EVENTS = List.of(
            CalendarDelegateAddedEvent.getDescriptor(), CalendarDelegateRemovedEvent.getDescriptor());

    @Test
    @DisplayName("Both events name the space, the calendar, its host and the delegate at the same numbers")
    void shareTheDesignationShape() {
        assertThat(EVENTS).allSatisfy(event -> assertThat(event.getFields().subList(0, 6))
                .as(event.getFullName())
                .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getJavaType)
                .containsExactly(
                        tuple(1, "event_id", JavaType.STRING),
                        tuple(2, "occurred_at_epoch_ms", JavaType.LONG),
                        tuple(3, "space_id", JavaType.STRING),
                        tuple(4, "calendar_id", JavaType.STRING),
                        tuple(5, "host_subject", JavaType.STRING),
                        tuple(6, "delegate_subject", JavaType.STRING)));
        assertThat(CalendarDelegateAddedEvent.getDescriptor().getFields()).hasSize(6);
        assertThat(CalendarDelegateRemovedEvent.getDescriptor().getFields()).hasSize(7);
        assertThat(CalendarDelegateRemovedEvent.getDescriptor().findFieldByNumber(7))
                .extracting(FieldDescriptor::getName, FieldDescriptor::getJavaType)
                .containsExactly("cause", JavaType.STRING);
    }

    @Test
    @DisplayName("Carries no name nor contact detail of either person")
    void carriesNoPersonDetail() {
        Pattern detail = Pattern.compile(".*(name|email|phone|address|locale|language|photo).*",
                Pattern.CASE_INSENSITIVE);

        assertThat(EVENTS).allSatisfy(event -> assertThat(event.getFields())
                .as(event.getFullName())
                .extracting(FieldDescriptor::getName)
                .noneMatch(name -> detail.matcher(name).matches()));
    }

    @Test
    @DisplayName("Each event declares exactly the EventRoutes constant of its fact")
    void declaresItsOwnKey() {
        assertThat(CalendarDelegateAddedEvent.getDescriptor().getOptions()
                .getExtension(EventOptionsProto.routingKeys))
                .containsExactly(EventRoutes.APPOINTMENTS_CALENDAR_DELEGATE_ADDED_V1);
        assertThat(CalendarDelegateRemovedEvent.getDescriptor().getOptions()
                .getExtension(EventOptionsProto.routingKeys))
                .containsExactly(EventRoutes.APPOINTMENTS_CALENDAR_DELEGATE_REMOVED_V1);
    }

    @Test
    @DisplayName("A removal by the system survives the wire with its cause")
    void removalSurvives() throws Exception {
        CalendarDelegateRemovedEvent sent = CalendarDelegateRemovedEvent.newBuilder()
                .setEventId("appt:delegate:d1:removed")
                .setOccurredAtEpochMs(1_790_000_000_000L)
                .setSpaceId("space-1")
                .setCalendarId("cal-1")
                .setHostSubject("host-sub")
                .setDelegateSubject("delegate-sub")
                .setCause("SYSTEM")
                .build();

        assertThat(CalendarDelegateRemovedEvent.parseFrom(sent.toByteArray())).isEqualTo(sent);
    }
}
