package com.ecclesiaflow.grpc.members;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Members hands church only what a receipt prints of a person, since church keeps no contact copy; and a
 * missing structured address must not read as an empty one, or church would print a blank address
 * instead of falling back on the confirmed legacy text or refusing the receipt.
 */
@DisplayName("GetDonorIdentities - what members discloses")
class DonorIdentityTest {

    private static final Pattern CONTACT = Pattern.compile("mail|phone");

    @Test
    @DisplayName("No field of the request or the response, nested ones included, is an email or a phone")
    void carriesNoContact() {
        List<String> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectFieldNames(GetDonorIdentitiesRequest.getDescriptor(), seen, fields);
        collectFieldNames(GetDonorIdentitiesResponse.getDescriptor(), seen, fields);

        assertThat(fields)
                .contains("first_name", "middle_initial", "last_name", "postal_code")
                .noneMatch(name -> CONTACT.matcher(name).find());
    }

    @Test
    @DisplayName("An identity sent without a structured address reads with the address absent, not empty")
    void absentAddressStaysAbsent() throws InvalidProtocolBufferException {
        DonorIdentity sent = DonorIdentity.newBuilder()
                .setKeycloakUserId("5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11")
                .setStatus("FOUND")
                .setFirstName("Marie")
                .setLastName("Tremblay")
                .setLegacyAddress("12 rue Principale, Québec")
                .build();

        DonorIdentity received = DonorIdentity.parseFrom(sent.toByteArray());

        assertThat(received.hasAddress()).isFalse();
        assertThat(received.getMiddleInitial()).isEmpty();
        assertThat(received.getLegacyAddress()).isEqualTo("12 rue Principale, Québec");
    }

    @Test
    @DisplayName("A structured address crosses the wire field for field")
    void structuredAddressRoundTrips() throws InvalidProtocolBufferException {
        PostalAddress address = PostalAddress.newBuilder()
                .setLine1("1234 boulevard Saint-Laurent")
                .setLine2("App. 5")
                .setCity("Montréal")
                .setRegion("QC")
                .setPostalCode("H2X 2T1")
                .setCountryCode("CA")
                .build();
        GetDonorIdentitiesResponse sent = GetDonorIdentitiesResponse.newBuilder()
                .addIdentities(DonorIdentity.newBuilder()
                        .setKeycloakUserId("5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11")
                        .setStatus("FOUND")
                        .setFirstName("Jean")
                        .setMiddleInitial("P")
                        .setLastName("Gagnon")
                        .setAddress(address))
                .addIdentities(DonorIdentity.newBuilder()
                        .setKeycloakUserId("9c41a7d2-3e5b-4f80-b1a6-7d2e0c8f4a19")
                        .setStatus("ERASED"))
                .build();

        GetDonorIdentitiesResponse received = GetDonorIdentitiesResponse.parseFrom(sent.toByteArray());

        assertThat(received.getIdentities(0).getAddress()).isEqualTo(address);
        assertThat(received.getIdentities(0).getMiddleInitial()).isEqualTo("P");
        assertThat(received.getIdentities(1).getStatus()).isEqualTo("ERASED");
        assertThat(received.getIdentities(1).hasAddress()).isFalse();
        assertThat(received.getIdentities(1).getLastName()).isEmpty();
    }

    private static void collectFieldNames(Descriptor message, Set<String> seen, List<String> names) {
        if (!seen.add(message.getFullName())) {
            return;
        }
        for (FieldDescriptor field : message.getFields()) {
            names.add(field.getName());
            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                collectFieldNames(field.getMessageType(), seen, names);
            }
        }
    }
}
