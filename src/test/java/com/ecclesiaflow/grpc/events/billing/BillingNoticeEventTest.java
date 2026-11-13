package com.ecclesiaflow.grpc.events.billing;

import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Church picks the recipients, the holders of MANAGE_BILLING, so the notice names no person: billing keeps no
 * list of people, and the dead-letter copy of a notice discloses nobody.
 */
@DisplayName("BillingNoticeEvent - wire contract")
class BillingNoticeEventTest {

    private static final Pattern PERSON_FIELD_NAME = Pattern.compile(
            ".*(user|member|name|email|phone|address|recipient).*", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("Declares no field named after a person")
    void namesNoPerson() {
        assertThat(BillingNoticeEvent.getDescriptor().getFields())
                .extracting(FieldDescriptor::getName)
                .noneMatch(name -> PERSON_FIELD_NAME.matcher(name).matches());
    }

    @Test
    @DisplayName("Survives the wire with the parameters its text interpolates")
    void roundTripsTheParameters() throws Exception {
        BillingNoticeEvent sent = BillingNoticeEvent.newBuilder()
                .setEventId("notice:c1:credit-low:3")
                .setOccurredAtEpochMs(1_790_000_000_000L)
                .setChurchId("c1")
                .setKind("CREDIT_LOW")
                .putAllParams(Map.of("channel", "SMS_SEGMENT", "available_units", "40"))
                .build();

        BillingNoticeEvent received = BillingNoticeEvent.parseFrom(sent.toByteArray());

        assertThat(received).isEqualTo(sent);
        assertThat(received.getParamsMap()).containsEntry("available_units", "40");
    }
}
