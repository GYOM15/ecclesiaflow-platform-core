package com.ecclesiaflow.grpc.payments;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payments service serves callers of other projects on the same contract, so it may speak of nothing but
 * merchants, payments, refunds, disputes and payouts, and every amount it exchanges must carry its currency.
 */
@DisplayName("Payments service - contract rules")
class PaymentsServiceContractTest {

    private static List<Descriptor> messages() {
        List<Descriptor> all = new ArrayList<>();
        PaymentsServiceProto.getDescriptor().getMessageTypes().forEach(message -> collect(message, all));
        return all;
    }

    private static void collect(Descriptor message, List<Descriptor> into) {
        into.add(message);
        message.getNestedTypes().forEach(nested -> collect(nested, into));
    }

    @Nested
    @DisplayName("Money")
    class Money {

        @Test
        @DisplayName("Every amount is an int64 in minor units beside the ISO 4217 currency of its message")
        void everyAmountHasItsCurrency() {
            List<Descriptor> withAmounts = messages().stream()
                    .filter(message -> message.getFields().stream().anyMatch(PaymentsServiceContractTest::isAmount))
                    .toList();

            assertThat(withAmounts).extracting(Descriptor::getName).containsExactlyInAnyOrder(
                    "Refund", "Dispute", "Payment", "Payout", "PayoutLine",
                    "StartPaymentRequest", "ChargeSavedMethodRequest", "RefundPaymentRequest");
            assertThat(withAmounts).allSatisfy(message -> {
                FieldDescriptor currency = message.findFieldByName("currency");
                assertThat(currency).as(message.getFullName()).isNotNull();
                assertThat(currency.getType()).isEqualTo(FieldDescriptor.Type.STRING);
                assertThat(message.getFields().stream().filter(PaymentsServiceContractTest::isAmount))
                        .as(message.getFullName())
                        .allSatisfy(field -> assertThat(field.getType()).isEqualTo(FieldDescriptor.Type.INT64));
            });
        }

        @Test
        @DisplayName("Names no amount without its unit: a bare amount would invite a float or a guess at the exponent")
        void namesEveryAmountInMinorUnits() {
            assertThat(messages()).allSatisfy(message -> assertThat(message.getFields())
                    .as(message.getFullName())
                    .extracting(FieldDescriptor::getName)
                    .noneMatch(name -> name.matches(".*(amount|total|fee|net|gross|refunded).*")
                            && !name.endsWith("_minor")));
        }
    }

    @Nested
    @DisplayName("Vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("Declares no enum: a value a later build adds breaks no caller")
        void declaresNoEnum() {
            assertThat(PaymentsServiceProto.getDescriptor().getEnumTypes()).isEmpty();
            assertThat(messages()).allSatisfy(message -> {
                assertThat(message.getEnumTypes()).as(message.getFullName()).isEmpty();
                assertThat(message.getFields()).as(message.getFullName())
                        .noneMatch(field -> field.getJavaType() == FieldDescriptor.JavaType.ENUM);
            });
        }

        @Test
        @DisplayName("Names no project, provider or payment method in an RPC, a message or a field")
        void namesNoProjectNorProvider() {
            // A refund is neutral; a fund is a project's.
            String forbidden =
                    "(?i).*(church|donor|donation|gift|(?<!re)fund|stripe|checkout|wallet|momo|mobile_?money).*";

            assertThat(PaymentsServiceProto.getDescriptor().getServices().getFirst().getMethods())
                    .extracting(MethodDescriptor::getName)
                    .noneMatch(name -> name.matches(forbidden));
            assertThat(messages()).allSatisfy(message -> {
                assertThat(message.getName()).doesNotMatch(forbidden);
                assertThat(message.getFields()).extracting(FieldDescriptor::getName)
                        .noneMatch(name -> name.matches(forbidden));
            });
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("Every RPC that creates money or a page names the caller reference it replays on")
        void everyCreationCarriesItsReference() {
            assertThat(List.of(StartPaymentRequest.getDescriptor(), ChargeSavedMethodRequest.getDescriptor(),
                    StartMethodSetupRequest.getDescriptor(), RefundPaymentRequest.getDescriptor()))
                    .allSatisfy(request -> assertThat(request.findFieldByName("caller_reference"))
                            .as(request.getFullName()).isNotNull());
            assertThat(OpenMerchantAccountRequest.getDescriptor().findFieldByName("merchant_ref")).isNotNull();
        }
    }

    private static boolean isAmount(FieldDescriptor field) {
        return field.getName().endsWith("_minor");
    }
}
