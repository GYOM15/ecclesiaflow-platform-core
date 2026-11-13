package com.ecclesiaflow.grpc.events.billing;

import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumers enforce an access status from this snapshot, so a limit billing leaves open must not read as a
 * zero limit; and billing keeps no list of people, so the snapshot names none.
 */
@DisplayName("ChurchEntitlementChangedEvent - wire contract")
class ChurchEntitlementChangedEventTest {

    private static final Pattern PERSON_FIELD_NAME = Pattern.compile(
            ".*(user|member|name|phone|address|recipient).*", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("A snapshot without a people ceiling or an end reads them absent, not zero")
    void openLimitsStayAbsent() throws Exception {
        ChurchEntitlementChangedEvent sent = ChurchEntitlementChangedEvent.newBuilder()
                .setEventId("entitlement:c1:7")
                .setOccurredAtEpochMs(1_790_000_000_000L)
                .setChurchId("c1")
                .setVersion(7)
                .setAccessStatus("ACTIVE")
                .setEmailMonthlyQuota(5_000)
                .setSmsOpen(true)
                .build();

        ChurchEntitlementChangedEvent received = ChurchEntitlementChangedEvent.parseFrom(sent.toByteArray());

        assertThat(received).isEqualTo(sent);
        assertThat(received.hasPeopleLimit()).isFalse();
        assertThat(received.hasValidUntilEpochMs()).isFalse();
    }

    @Test
    @DisplayName("A ceiling of zero and an end at the epoch still read as set")
    void zeroLimitsStaySet() throws Exception {
        ChurchEntitlementChangedEvent sent = ChurchEntitlementChangedEvent.newBuilder()
                .setChurchId("c1")
                .setPeopleLimit(0)
                .setValidUntilEpochMs(0)
                .build();

        ChurchEntitlementChangedEvent received = ChurchEntitlementChangedEvent.parseFrom(sent.toByteArray());

        assertThat(received.hasPeopleLimit()).isTrue();
        assertThat(received.hasValidUntilEpochMs()).isTrue();
    }

    @Test
    @DisplayName("Declares no field named after a person")
    void namesNoPerson() {
        assertThat(ChurchEntitlementChangedEvent.getDescriptor().getFields())
                .extracting(FieldDescriptor::getName)
                .noneMatch(name -> PERSON_FIELD_NAME.matcher(name).matches());
    }
}
