package com.ecclesiaflow.grpc;

import com.ecclesiaflow.grpc.auth.AuthServiceProto;
import com.ecclesiaflow.grpc.church.ChurchServiceProto;
import com.ecclesiaflow.grpc.email.EmailServiceProto;
import com.ecclesiaflow.grpc.events.auth.AuthDomainEventsProto;
import com.ecclesiaflow.grpc.events.church.ChurchDomainEventsProto;
import com.ecclesiaflow.grpc.events.members.MembersDomainEventsProto;
import com.ecclesiaflow.grpc.members.MembersServiceProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deprecated contract element stays on the wire for the clients still built against it, so the
 * option is what tells their authors to move off it; ContractWireCompatibilityTest keeps it present.
 */
class ContractDeprecationTest {

    private static final List<FileDescriptor> CONTRACTS = List.of(
            AuthServiceProto.getDescriptor(),
            ChurchServiceProto.getDescriptor(),
            MembersServiceProto.getDescriptor(),
            EmailServiceProto.getDescriptor(),
            AuthDomainEventsProto.getDescriptor(),
            ChurchDomainEventsProto.getDescriptor(),
            MembersDomainEventsProto.getDescriptor());

    @Test
    @DisplayName("Exactly the retired RPCs, messages, fields and enum values carry the deprecated option")
    void deprecatedElementsAreTheRetiredOnes() {
        assertThat(deprecatedElements())
                .containsExactly(
                        "ecclesiaflow.church.ChurchService.RepublishActiveMemberships",
                        "ecclesiaflow.events.auth.v1.SetupTokenIssuedEvent.raw_token");
    }

    private static List<String> deprecatedElements() {
        List<String> deprecated = new ArrayList<>();
        for (FileDescriptor file : CONTRACTS) {
            file.getServices().forEach(service -> service.getMethods().stream()
                    .filter(method -> method.getOptions().getDeprecated())
                    .forEach(method -> deprecated.add(method.getFullName())));
            file.getMessageTypes().forEach(message -> collect(message, deprecated));
            file.getEnumTypes().forEach(enumType -> collect(enumType, deprecated));
        }
        return deprecated;
    }

    private static void collect(Descriptor message, List<String> deprecated) {
        if (message.getOptions().getDeprecated()) {
            deprecated.add(message.getFullName());
        }
        message.getFields().stream()
                .filter(field -> field.getOptions().getDeprecated())
                .forEach(field -> deprecated.add(field.getFullName()));
        message.getNestedTypes().forEach(nested -> collect(nested, deprecated));
        message.getEnumTypes().forEach(enumType -> collect(enumType, deprecated));
    }

    private static void collect(EnumDescriptor enumType, List<String> deprecated) {
        if (enumType.getOptions().getDeprecated()) {
            deprecated.add(enumType.getFullName());
        }
        enumType.getValues().stream()
                .filter(value -> value.getOptions().getDeprecated())
                .forEach(value -> deprecated.add(value.getFullName()));
    }
}
