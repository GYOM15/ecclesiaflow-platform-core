package com.ecclesiaflow.grpc.events.appointments;

import com.ecclesiaflow.grpc.events.EventOptionsProto;
import com.ecclesiaflow.grpc.events.EventRoutes;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The appointments service serves several projects and publishes to buses it does not own, so the five
 * events share one booking shape and none of them can carry what a booker confided to the host.
 */
@DisplayName("Appointment events - wire contract")
class AppointmentEventsTest {

    private static final int SHARED_FIELDS = 21;

    private static final Map<Descriptor, String> ROUTING_KEYS = Map.of(
            AppointmentBookedEvent.getDescriptor(), EventRoutes.APPOINTMENTS_BOOKING_BOOKED_V1,
            AppointmentConfirmedEvent.getDescriptor(), EventRoutes.APPOINTMENTS_BOOKING_CONFIRMED_V1,
            AppointmentMovedEvent.getDescriptor(), EventRoutes.APPOINTMENTS_BOOKING_MOVED_V1,
            AppointmentCancelledEvent.getDescriptor(), EventRoutes.APPOINTMENTS_BOOKING_CANCELLED_V1,
            AppointmentReminderDueEvent.getDescriptor(), EventRoutes.APPOINTMENTS_BOOKING_REMINDER_DUE_V1);

    // reason_code is the open cancellation code; a free text, a note or an encrypted blob is the booker's reason.
    private static final Pattern REASON_TEXT_FIELD_NAME = Pattern.compile(
            ".*(reason(?!_code$)|motif|note|comment|message|text|body|cipher|description|label).*",
            Pattern.CASE_INSENSITIVE);

    private static List<Descriptor> events() {
        return List.copyOf(ROUTING_KEYS.keySet());
    }

    private static List<FieldDescriptor> sharedFields(Descriptor event) {
        return event.getFields().stream().filter(field -> field.getNumber() <= SHARED_FIELDS).toList();
    }

    private static List<FieldDescriptor> ownFields(Descriptor event) {
        return event.getFields().stream().filter(field -> field.getNumber() > SHARED_FIELDS).toList();
    }

    @Nested
    @DisplayName("Shape")
    class Shape {

        @Test
        @DisplayName("Every event reads the same booking in fields 1 to 21")
        void sharesTheBookingShape() {
            assertThat(sharedFields(AppointmentBookedEvent.getDescriptor()))
                    .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getJavaType)
                    .containsExactly(
                            tuple(1, "event_id", JavaType.STRING),
                            tuple(2, "occurred_at_epoch_ms", JavaType.LONG),
                            tuple(3, "space_id", JavaType.STRING),
                            tuple(4, "booking_id", JavaType.STRING),
                            tuple(5, "origin_booking_id", JavaType.STRING),
                            tuple(6, "sequence", JavaType.INT),
                            tuple(7, "calendar_id", JavaType.STRING),
                            tuple(8, "type_id", JavaType.STRING),
                            tuple(9, "host_subject", JavaType.STRING),
                            tuple(10, "booker_subject", JavaType.STRING),
                            tuple(11, "starts_at_epoch_ms", JavaType.LONG),
                            tuple(12, "ends_at_epoch_ms", JavaType.LONG),
                            tuple(13, "host_time_zone", JavaType.STRING),
                            tuple(14, "booker_time_zone", JavaType.STRING),
                            tuple(15, "type_title", JavaType.STRING),
                            tuple(16, "host_display_name", JavaType.STRING),
                            tuple(17, "booker_display_name", JavaType.STRING),
                            tuple(18, "location_kind", JavaType.STRING),
                            tuple(19, "location_place", JavaType.STRING),
                            tuple(20, "location_link", JavaType.STRING),
                            tuple(21, "status", JavaType.STRING));
            assertThat(events()).allSatisfy(event -> assertThat(sharedFields(event))
                    .as(event.getFullName())
                    .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getType)
                    .containsExactlyElementsOf(sharedFields(AppointmentBookedEvent.getDescriptor()).stream()
                            .map(field -> tuple(field.getNumber(), field.getName(), field.getType())).toList()));
        }

        @Test
        @DisplayName("A move names the booking it replaces and the instants it left, the others add what they need")
        void addsTheirOwnFieldsFromTwentyTwo() {
            assertThat(ownFields(AppointmentBookedEvent.getDescriptor())).isEmpty();
            assertThat(ownFields(AppointmentConfirmedEvent.getDescriptor())).isEmpty();
            assertThat(ownFields(AppointmentMovedEvent.getDescriptor()))
                    .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getJavaType)
                    .containsExactly(
                            tuple(22, "previous_booking_id", JavaType.STRING),
                            tuple(23, "previous_starts_at_epoch_ms", JavaType.LONG),
                            tuple(24, "previous_ends_at_epoch_ms", JavaType.LONG),
                            tuple(25, "actor", JavaType.STRING));
            assertThat(ownFields(AppointmentCancelledEvent.getDescriptor()))
                    .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getJavaType)
                    .containsExactly(
                            tuple(22, "actor", JavaType.STRING),
                            tuple(23, "reason_code", JavaType.STRING));
            assertThat(ownFields(AppointmentReminderDueEvent.getDescriptor()))
                    .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getJavaType)
                    .containsExactly(tuple(22, "offset", JavaType.STRING));
        }

        @Test
        @DisplayName("Holds scalars only: a vocabulary is an open string, never an enum or a nested structure")
        void holdsOnlyScalars() {
            assertThat(events()).allSatisfy(event -> {
                assertThat(event.getFields()).as(event.getFullName())
                        .allSatisfy(field -> {
                            assertThat(field.isRepeated()).as(field.getName()).isFalse();
                            assertThat(field.getJavaType()).as(field.getName())
                                    .isIn(JavaType.STRING, JavaType.LONG, JavaType.INT);
                        });
                assertThat(event.getNestedTypes()).isEmpty();
                assertThat(event.getEnumTypes()).isEmpty();
                assertThat(event.getOneofs()).isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("Confidentiality")
    class Confidentiality {

        @Test
        @DisplayName("Carries no field that could hold the reason a booker gave, whatever its name")
        void carriesNoReasonText() {
            assertThat(events()).allSatisfy(event -> assertThat(event.getFields())
                    .as(event.getFullName())
                    .extracting(FieldDescriptor::getName)
                    .noneMatch(name -> REASON_TEXT_FIELD_NAME.matcher(name).matches()));
        }

        @Test
        @DisplayName("Only the cancelled event states why, as a code")
        void onlyCancellationStatesACode() {
            assertThat(events().stream()
                    .filter(event -> event.findFieldByName("reason_code") != null)
                    .map(Descriptor::getFullName))
                    .containsExactly("ecclesiaflow.events.appointments.v1.AppointmentCancelledEvent");
        }

        @Test
        @DisplayName("Carries no address, phone or language of a person: the hosting project resolves them by subject")
        void carriesNoContactDetail() {
            Pattern contact = Pattern.compile(".*(email|phone|address|locale|language|photo).*",
                    Pattern.CASE_INSENSITIVE);

            assertThat(events()).allSatisfy(event -> assertThat(event.getFields())
                    .as(event.getFullName())
                    .extracting(FieldDescriptor::getName)
                    .noneMatch(name -> contact.matcher(name).matches()));
        }
    }

    @Nested
    @DisplayName("Routing")
    class Routing {

        @Test
        @DisplayName("Each event declares exactly the EventRoutes constant of its fact")
        void declaresItsOwnKey() {
            assertThat(ROUTING_KEYS).allSatisfy((event, key) -> assertThat(
                    event.getOptions().getExtension(EventOptionsProto.routingKeys))
                    .as(event.getFullName()).containsExactly(key));
        }

        @Test
        @DisplayName("The five facts a booking goes through have five distinct keys")
        void keysAreDistinct() {
            assertThat(ROUTING_KEYS.values()).containsExactlyInAnyOrder(
                    "appointments.booking.booked.v1",
                    "appointments.booking.confirmed.v1",
                    "appointments.booking.moved.v1",
                    "appointments.booking.cancelled.v1",
                    "appointments.booking.reminder-due.v1");
        }
    }

    @Nested
    @DisplayName("Round trip")
    class RoundTrip {

        @Test
        @DisplayName("A booking survives the wire with its two zones and the place of the meeting")
        void bookedSurvives() throws Exception {
            AppointmentBookedEvent sent = AppointmentBookedEvent.newBuilder()
                    .setEventId("appt:b2:booked")
                    .setOccurredAtEpochMs(1_790_000_000_000L)
                    .setSpaceId("space-1")
                    .setBookingId("b2")
                    .setOriginBookingId("b2")
                    .setSequence(0)
                    .setCalendarId("cal-1")
                    .setTypeId("type-1")
                    .setHostSubject("host-sub")
                    .setBookerSubject("booker-sub")
                    .setStartsAtEpochMs(1_790_100_000_000L)
                    .setEndsAtEpochMs(1_790_101_800_000L)
                    .setHostTimeZone("America/Montreal")
                    .setBookerTimeZone("Africa/Conakry")
                    .setTypeTitle("Pastoral meeting")
                    .setHostDisplayName("A. Host")
                    .setBookerDisplayName("B. Booker")
                    .setLocationKind("IN_PERSON")
                    .setLocationPlace("1 Main Street")
                    .setStatus("PENDING")
                    .build();

            AppointmentBookedEvent received = AppointmentBookedEvent.parseFrom(sent.toByteArray());

            assertThat(received).isEqualTo(sent);
            assertThat(received.getLocationLink()).isEmpty();
        }

        @Test
        @DisplayName("A move keeps what it replaces beside the new booking")
        void movedSurvives() throws Exception {
            AppointmentMovedEvent sent = AppointmentMovedEvent.newBuilder()
                    .setEventId("appt:b3:moved")
                    .setBookingId("b3")
                    .setOriginBookingId("b2")
                    .setSequence(1)
                    .setStartsAtEpochMs(1_790_200_000_000L)
                    .setEndsAtEpochMs(1_790_201_800_000L)
                    .setPreviousBookingId("b2")
                    .setPreviousStartsAtEpochMs(1_790_100_000_000L)
                    .setPreviousEndsAtEpochMs(1_790_101_800_000L)
                    .setActor("BOOKER")
                    .build();

            AppointmentMovedEvent received = AppointmentMovedEvent.parseFrom(sent.toByteArray());

            assertThat(received).isEqualTo(sent);
            assertThat(received.getPreviousStartsAtEpochMs()).isLessThan(received.getStartsAtEpochMs());
        }

        @Test
        @DisplayName("A cancellation of an erased booker reads with empty names, not as an error")
        void cancelledOfAnErasedBookerSurvives() throws Exception {
            AppointmentCancelledEvent sent = AppointmentCancelledEvent.newBuilder()
                    .setEventId("appt:b2:cancelled")
                    .setBookingId("b2")
                    .setOriginBookingId("b2")
                    .setHostDisplayName("A. Host")
                    .setStatus("CANCELLED")
                    .setActor("SYSTEM")
                    .setReasonCode("BOOKER_UNAVAILABLE")
                    .build();

            AppointmentCancelledEvent received = AppointmentCancelledEvent.parseFrom(sent.toByteArray());

            assertThat(received.getBookerDisplayName()).isEmpty();
            assertThat(received.getActor()).isEqualTo("SYSTEM");
            assertThat(received.getReasonCode()).isEqualTo("BOOKER_UNAVAILABLE");
        }

        @Test
        @DisplayName("A reminder names its offset as an ISO 8601 duration")
        void reminderSurvives() throws Exception {
            AppointmentReminderDueEvent sent = AppointmentReminderDueEvent.newBuilder()
                    .setEventId("appt:b2:reminder:PT1H")
                    .setBookingId("b2")
                    .setStatus("CONFIRMED")
                    .setOffset("PT1H")
                    .build();

            assertThat(AppointmentReminderDueEvent.parseFrom(sent.toByteArray()).getOffset()).isEqualTo("PT1H");
        }

        @Test
        @DisplayName("A confirmation reads as the booking it confirms")
        void confirmedSurvives() throws Exception {
            AppointmentConfirmedEvent sent = AppointmentConfirmedEvent.newBuilder()
                    .setEventId("appt:b2:confirmed")
                    .setBookingId("b2")
                    .setOriginBookingId("b2")
                    .setStatus("CONFIRMED")
                    .build();

            assertThat(AppointmentConfirmedEvent.parseFrom(sent.toByteArray())).isEqualTo(sent);
        }
    }
}
