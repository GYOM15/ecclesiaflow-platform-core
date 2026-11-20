package com.ecclesiaflow.grpc.events.payments;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The payments service publishes to buses whose consumers re-read every payment before they write money, so the
 * four payment events share one shape a consumer reads alike, and every event id fits the 64 characters a
 * consumer's dedup column holds.
 */
@DisplayName("Payment events - wire contract")
class PaymentEventsTest {

    private static final int SHARED_FIELDS = 12;

    // The longest dedup column of a consumer (finance.inbound_event_receipt.event_id).
    private static final int EVENT_ID_MAX_LENGTH = 64;

    private static final Map<Descriptor, String> ROUTING_KEYS = Map.of(
            MerchantAccountChangedEvent.getDescriptor(), EventRoutes.PAYMENTS_MERCHANT_ACCOUNT_CHANGED_V1,
            PaymentSucceededEvent.getDescriptor(), EventRoutes.PAYMENTS_PAYMENT_SUCCEEDED_V1,
            PaymentFailedEvent.getDescriptor(), EventRoutes.PAYMENTS_PAYMENT_FAILED_V1,
            PaymentRefundedEvent.getDescriptor(), EventRoutes.PAYMENTS_PAYMENT_REFUNDED_V1,
            PaymentDisputedEvent.getDescriptor(), EventRoutes.PAYMENTS_PAYMENT_DISPUTED_V1,
            PayoutChangedEvent.getDescriptor(), EventRoutes.PAYMENTS_PAYOUT_CHANGED_V1,
            ProviderStatusChangedEvent.getDescriptor(), EventRoutes.PAYMENTS_PROVIDER_STATUS_CHANGED_V1);

    private static final List<Descriptor> PAYMENT_EVENTS = List.of(
            PaymentSucceededEvent.getDescriptor(),
            PaymentFailedEvent.getDescriptor(),
            PaymentRefundedEvent.getDescriptor(),
            PaymentDisputedEvent.getDescriptor());

    private static List<Descriptor> events() {
        return PaymentsDomainEventsProto.getDescriptor().getMessageTypes();
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
        @DisplayName("The four payment events read the same payment in fields 1 to 12")
        void sharesThePaymentShape() {
            assertThat(sharedFields(PaymentSucceededEvent.getDescriptor()))
                    .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getJavaType)
                    .containsExactly(
                            tuple(1, "event_id", JavaType.STRING),
                            tuple(2, "occurred_at_epoch_ms", JavaType.LONG),
                            tuple(3, "merchant_account_id", JavaType.STRING),
                            tuple(4, "merchant_ref", JavaType.STRING),
                            tuple(5, "payment_id", JavaType.STRING),
                            tuple(6, "caller_reference", JavaType.STRING),
                            tuple(7, "amount_minor", JavaType.LONG),
                            tuple(8, "currency", JavaType.STRING),
                            tuple(9, "provider", JavaType.STRING),
                            tuple(10, "provider_payment_ref", JavaType.STRING),
                            tuple(11, "status", JavaType.STRING),
                            tuple(12, "provider_status", JavaType.STRING));
            assertThat(PAYMENT_EVENTS).allSatisfy(event -> assertThat(sharedFields(event))
                    .as(event.getFullName())
                    .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getType)
                    .containsExactlyElementsOf(sharedFields(PaymentSucceededEvent.getDescriptor()).stream()
                            .map(field -> tuple(field.getNumber(), field.getName(), field.getType())).toList()));
        }

        @Test
        @DisplayName("A failure says why, a refund and a dispute carry their own object, from field 13")
        void addsTheirOwnFieldsFromThirteen() {
            assertThat(ownFields(PaymentSucceededEvent.getDescriptor())).isEmpty();
            assertThat(ownFields(PaymentFailedEvent.getDescriptor()))
                    .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getJavaType)
                    .containsExactly(
                            tuple(13, "reason_code", JavaType.STRING),
                            tuple(14, "provider_failure_code", JavaType.STRING));
            assertThat(ownFields(PaymentRefundedEvent.getDescriptor()))
                    .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getJavaType)
                    .containsExactly(
                            tuple(13, "refund_id", JavaType.STRING),
                            tuple(14, "refund_caller_reference", JavaType.STRING),
                            tuple(15, "refund_origin", JavaType.STRING),
                            tuple(16, "refund_amount_minor", JavaType.LONG),
                            tuple(17, "provider_refund_ref", JavaType.STRING),
                            tuple(18, "refund_status", JavaType.STRING),
                            tuple(19, "refund_provider_status", JavaType.STRING));
            assertThat(ownFields(PaymentDisputedEvent.getDescriptor()))
                    .extracting(FieldDescriptor::getNumber, FieldDescriptor::getName, FieldDescriptor::getJavaType)
                    .containsExactly(
                            tuple(13, "dispute_id", JavaType.STRING),
                            tuple(14, "phase", JavaType.STRING),
                            tuple(15, "dispute_amount_minor", JavaType.LONG),
                            tuple(16, "respond_by_epoch_ms", JavaType.LONG),
                            tuple(17, "provider_dispute_ref", JavaType.STRING),
                            tuple(18, "dispute_provider_status", JavaType.STRING));
        }

        @Test
        @DisplayName("Holds scalars only: a vocabulary is an open string, never an enum or a nested structure")
        void holdsOnlyScalars() {
            assertThat(events()).hasSize(ROUTING_KEYS.size()).allSatisfy(event -> {
                assertThat(event.getFields()).as(event.getFullName()).allSatisfy(field -> {
                    assertThat(field.isRepeated()).as(field.getName()).isFalse();
                    assertThat(field.getJavaType()).as(field.getName())
                            .isIn(JavaType.STRING, JavaType.LONG, JavaType.BOOLEAN);
                });
                assertThat(event.getNestedTypes()).isEmpty();
                assertThat(event.getEnumTypes()).isEmpty();
                assertThat(event.getOneofs()).isEmpty();
            });
            assertThat(PaymentsDomainEventsProto.getDescriptor().getEnumTypes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Money")
    class Money {

        @Test
        @DisplayName("Every amount is an int64 in minor units beside the currency of its event")
        void everyAmountHasItsCurrency() {
            List<Descriptor> withAmounts = events().stream()
                    .filter(event -> event.getFields().stream().anyMatch(field -> field.getName().endsWith("_minor")))
                    .toList();

            assertThat(withAmounts).hasSize(5).allSatisfy(event -> {
                assertThat(event.findFieldByName("currency")).as(event.getFullName()).isNotNull();
                assertThat(event.getFields().stream().filter(field -> field.getName().endsWith("_minor")))
                        .as(event.getFullName())
                        .allSatisfy(field -> assertThat(field.getType()).isEqualTo(FieldDescriptor.Type.INT64));
            });
        }

        @Test
        @DisplayName("Names no amount without its unit: a bare amount or total would invite a float or a sum")
        void namesEveryAmountInMinorUnits() {
            assertThat(events()).allSatisfy(event -> assertThat(event.getFields())
                    .extracting(FieldDescriptor::getName)
                    .noneMatch(name -> name.matches(".*(amount|total|fee|net|gross).*") && !name.endsWith("_minor")));
        }
    }

    @Nested
    @DisplayName("Neutrality")
    class Neutrality {

        @Test
        @DisplayName("Carries nothing of a person: no name, address, e-mail, phone or card detail")
        void carriesNoPersonalData() {
            assertThat(events()).allSatisfy(event -> assertThat(event.getFields())
                    .as(event.getFullName())
                    .extracting(FieldDescriptor::getName)
                    .noneMatch(name -> name.matches(
                            ".*(name|mail|address|phone|card|last4|brand|iban|bank_account|payer|holder|locale).*")));
        }

        @Test
        @DisplayName("Speaks the neutral vocabulary only: no project, provider or payment method is named")
        void namesNoProjectNorProvider() {
            // A refund is neutral; a fund is a project's.
            String forbidden = "(?i).*(church|donor|donation|gift|(?<!re)fund|stripe|checkout|wallet|momo).*";

            assertThat(events()).allSatisfy(event -> {
                assertThat(event.getName()).doesNotMatch(forbidden);
                assertThat(event.getFields()).extracting(FieldDescriptor::getName)
                        .noneMatch(name -> name.matches(forbidden));
            });
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
        @DisplayName("The seven facts have seven distinct keys, all published by payments")
        void keysAreDistinct() {
            assertThat(ROUTING_KEYS.values()).containsExactlyInAnyOrder(
                    "payments.merchant-account.changed.v1",
                    "payments.payment.succeeded.v1",
                    "payments.payment.failed.v1",
                    "payments.payment.refunded.v1",
                    "payments.payment.disputed.v1",
                    "payments.payout.changed.v1",
                    "payments.provider.status-changed.v1");
        }
    }

    @Nested
    @DisplayName("Event ids")
    class EventIds {

        private final String id = UUID.randomUUID().toString();

        @Test
        @DisplayName("The longest documented id of each event fits a consumer's 64-character dedup column")
        void fitTheDedupColumn() {
            assertThat(List.of(
                    "mer:" + id + ":" + Long.MAX_VALUE,
                    "pay:" + id + ":paid",
                    "pay:" + id + ":failed",
                    "ref:" + id + ":succeeded",
                    "dsp:" + id + ":FUNDS_REINSTATED",
                    "dsp:" + id + ":FUNDS_WITHDRAWN",
                    "pyo:" + id + ":FAILED",
                    "prv:" + "stripe" + ":" + Long.MAX_VALUE))
                    .allSatisfy(eventId -> assertThat(eventId).hasSizeLessThanOrEqualTo(EVENT_ID_MAX_LENGTH));
        }
    }

    @Nested
    @DisplayName("Round trip")
    class RoundTrip {

        @Test
        @DisplayName("A partial refund survives the wire with its own amount beside the payment's")
        void refundedSurvives() throws Exception {
            PaymentRefundedEvent sent = PaymentRefundedEvent.newBuilder()
                    .setEventId("ref:r1:succeeded")
                    .setOccurredAtEpochMs(1_790_000_000_000L)
                    .setMerchantAccountId("m1")
                    .setMerchantRef("tenant-1")
                    .setPaymentId("p1")
                    .setCallerReference("intent-1")
                    .setAmountMinor(2_500)
                    .setCurrency("CAD")
                    .setProvider("stripe")
                    .setProviderPaymentRef("pi_1")
                    .setStatus("PAID")
                    .setProviderStatus("succeeded")
                    .setRefundId("r1")
                    .setRefundCallerReference("refund-1")
                    .setRefundOrigin("API")
                    .setRefundAmountMinor(1_000)
                    .setProviderRefundRef("re_1")
                    .setRefundStatus("SUCCEEDED")
                    .setRefundProviderStatus("succeeded")
                    .build();

            PaymentRefundedEvent received = PaymentRefundedEvent.parseFrom(sent.toByteArray());

            assertThat(received).isEqualTo(sent);
            assertThat(received.getRefundAmountMinor()).isLessThan(received.getAmountMinor());
        }

        @Test
        @DisplayName("A refund made at the provider reads with an empty caller reference, not as an error")
        void providerRefundSurvives() throws Exception {
            PaymentRefundedEvent sent = PaymentRefundedEvent.newBuilder()
                    .setEventId("ref:r2:succeeded")
                    .setPaymentId("p1")
                    .setRefundId("r2")
                    .setRefundOrigin("PROVIDER")
                    .setRefundAmountMinor(2_500)
                    .setCurrency("CAD")
                    .build();

            PaymentRefundedEvent received = PaymentRefundedEvent.parseFrom(sent.toByteArray());

            assertThat(received.getRefundCallerReference()).isEmpty();
            assertThat(received.getRefundOrigin()).isEqualTo("PROVIDER");
        }

        @Test
        @DisplayName("A currency without decimals keeps its amount whole")
        void zeroDecimalCurrencySurvives() throws Exception {
            PaymentSucceededEvent sent = PaymentSucceededEvent.newBuilder()
                    .setEventId("pay:p2:paid")
                    .setPaymentId("p2")
                    .setAmountMinor(150_000)
                    .setCurrency("GNF")
                    .setStatus("PAID")
                    .build();

            assertThat(PaymentSucceededEvent.parseFrom(sent.toByteArray())).isEqualTo(sent);
        }

        @Test
        @DisplayName("A merchant snapshot survives with its version and flags")
        void merchantSnapshotSurvives() throws Exception {
            MerchantAccountChangedEvent sent = MerchantAccountChangedEvent.newBuilder()
                    .setEventId("mer:m1:3")
                    .setMerchantAccountId("m1")
                    .setStatus("RESTRICTED")
                    .setChargesEnabled(true)
                    .setPayoutsEnabled(false)
                    .setDisabledReason("requirements.past_due")
                    .setVersion(3)
                    .build();

            MerchantAccountChangedEvent received = MerchantAccountChangedEvent.parseFrom(sent.toByteArray());

            assertThat(received).isEqualTo(sent);
            assertThat(received.getPayoutsEnabled()).isFalse();
        }

        @Test
        @DisplayName("A failure, a dispute phase, a payout and a provider change survive the wire")
        void otherFactsSurvive() throws Exception {
            PaymentFailedEvent failed = PaymentFailedEvent.newBuilder()
                    .setEventId("pay:p3:failed").setStatus("FAILED").setReasonCode("EXPIRED").build();
            PaymentDisputedEvent disputed = PaymentDisputedEvent.newBuilder()
                    .setEventId("dsp:d1:OPENED").setPhase("OPENED").setDisputeAmountMinor(2_500)
                    .setRespondByEpochMs(1_791_000_000_000L).build();
            PayoutChangedEvent payout = PayoutChangedEvent.newBuilder()
                    .setEventId("pyo:o1:PAID").setStatus("PAID").setAmountMinor(9_700).setCurrency("CAD").build();
            ProviderStatusChangedEvent provider = ProviderStatusChangedEvent.newBuilder()
                    .setEventId("prv:stripe:1790000000000").setProvider("stripe")
                    .setStatus("AVAILABLE").setPreviousStatus("UNAVAILABLE").build();

            assertThat(PaymentFailedEvent.parseFrom(failed.toByteArray())).isEqualTo(failed);
            assertThat(PaymentDisputedEvent.parseFrom(disputed.toByteArray())).isEqualTo(disputed);
            assertThat(PayoutChangedEvent.parseFrom(payout.toByteArray())).isEqualTo(payout);
            assertThat(ProviderStatusChangedEvent.parseFrom(provider.toByteArray())).isEqualTo(provider);
        }
    }
}
