package com.ecclesiaflow.grpc.billing;

import com.ecclesiaflow.grpc.events.billing.ChurchEntitlementChangedEvent;
import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Church and communication rebuild from GetEntitlement the projection the event otherwise fills, and apply both
 * by version: a field the two spell, type or number apart would make a startup read disagree with the events.
 */
@DisplayName("GetEntitlementResponse - the event's snapshot")
class GetEntitlementResponseTest {

    private static final int ENVELOPE_FIELDS = 2;

    @Test
    @DisplayName("Carries the event's fields after its envelope, same names and types, numbered two lower")
    void mirrorsTheEvent() {
        List<String> snapshot = ChurchEntitlementChangedEvent.getDescriptor().getFields().stream()
                .filter(field -> field.getNumber() > ENVELOPE_FIELDS)
                .map(field -> line(field.getNumber() - ENVELOPE_FIELDS, field))
                .toList();

        assertThat(GetEntitlementResponse.getDescriptor().getFields().stream()
                .map(field -> line(field.getNumber(), field)))
                .isNotEmpty()
                .containsExactlyElementsOf(snapshot);
    }

    private static String line(int number, FieldDescriptor field) {
        return number + " " + (field.toProto().getProto3Optional() ? "optional " : "") + field.getType()
                + " " + field.getName();
    }
}
