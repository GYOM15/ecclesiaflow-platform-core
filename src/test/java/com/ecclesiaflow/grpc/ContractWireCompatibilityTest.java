package com.ecclesiaflow.grpc;

import com.ecclesiaflow.grpc.auth.AuthServiceProto;
import com.ecclesiaflow.grpc.church.ChurchServiceProto;
import com.ecclesiaflow.grpc.email.EmailQueueMessage;
import com.ecclesiaflow.grpc.email.EmailServiceProto;
import com.ecclesiaflow.grpc.events.auth.AuthDomainEventsProto;
import com.ecclesiaflow.grpc.events.auth.ExistingAccountNoticeEvent;
import com.ecclesiaflow.grpc.events.auth.SetupTokenIssuedEvent;
import com.ecclesiaflow.grpc.events.church.ChurchDomainEventsProto;
import com.ecclesiaflow.grpc.events.church.ChurchInvitationCreatedEvent;
import com.ecclesiaflow.grpc.events.church.MemberAddedToGroupEvent;
import com.ecclesiaflow.grpc.events.church.MemberAdmittedToChurchEvent;
import com.ecclesiaflow.grpc.events.church.MemberRemovedFromChurchEvent;
import com.ecclesiaflow.grpc.events.church.MemberRemovedFromGroupEvent;
import com.ecclesiaflow.grpc.events.members.MemberAnonymizedEvent;
import com.ecclesiaflow.grpc.events.members.MemberContactsErasedEvent;
import com.ecclesiaflow.grpc.events.members.MemberDeactivatedEvent;
import com.ecclesiaflow.grpc.events.members.MemberProfileChangedEvent;
import com.ecclesiaflow.grpc.events.members.MemberReactivatedEvent;
import com.ecclesiaflow.grpc.events.members.MembersDomainEventsProto;
import com.ecclesiaflow.grpc.members.MembersServiceProto;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Services deployed at different versions talk to each other, so what the contracts put on the wire
 * may only grow. A failure here is a wire change: add the new field to the snapshot, never edit a
 * line that is already in it.
 */
class ContractWireCompatibilityTest {

    private static final List<FileDescriptor> CONTRACTS = List.of(
            AuthServiceProto.getDescriptor(),
            ChurchServiceProto.getDescriptor(),
            MembersServiceProto.getDescriptor(),
            EmailServiceProto.getDescriptor(),
            AuthDomainEventsProto.getDescriptor(),
            ChurchDomainEventsProto.getDescriptor(),
            MembersDomainEventsProto.getDescriptor());

    @Test
    @DisplayName("Packages, RPCs, field numbers and types match what the deployed services exchange")
    void contractsMatchTheWire() {
        assertThat(snapshot()).isEqualTo(WIRE);
    }

    @Test
    @DisplayName("Messages sent over RabbitMQ keep the class name their consumers resolve from __TypeId__")
    void queuedMessagesKeepTheirJavaNames() {
        assertThat(List.of(SetupTokenIssuedEvent.class, ExistingAccountNoticeEvent.class,
                ChurchInvitationCreatedEvent.class, MemberRemovedFromChurchEvent.class,
                MemberAdmittedToChurchEvent.class, MemberAddedToGroupEvent.class,
                MemberRemovedFromGroupEvent.class, MemberProfileChangedEvent.class,
                MemberAnonymizedEvent.class, MemberContactsErasedEvent.class,
                MemberDeactivatedEvent.class, MemberReactivatedEvent.class, EmailQueueMessage.class))
                .extracting(Class::getName)
                .containsExactly(
                        "com.ecclesiaflow.grpc.events.auth.SetupTokenIssuedEvent",
                        "com.ecclesiaflow.grpc.events.auth.ExistingAccountNoticeEvent",
                        "com.ecclesiaflow.grpc.events.church.ChurchInvitationCreatedEvent",
                        "com.ecclesiaflow.grpc.events.church.MemberRemovedFromChurchEvent",
                        "com.ecclesiaflow.grpc.events.church.MemberAdmittedToChurchEvent",
                        "com.ecclesiaflow.grpc.events.church.MemberAddedToGroupEvent",
                        "com.ecclesiaflow.grpc.events.church.MemberRemovedFromGroupEvent",
                        "com.ecclesiaflow.grpc.events.members.MemberProfileChangedEvent",
                        "com.ecclesiaflow.grpc.events.members.MemberAnonymizedEvent",
                        "com.ecclesiaflow.grpc.events.members.MemberContactsErasedEvent",
                        "com.ecclesiaflow.grpc.events.members.MemberDeactivatedEvent",
                        "com.ecclesiaflow.grpc.events.members.MemberReactivatedEvent",
                        "com.ecclesiaflow.grpc.email.EmailQueueMessage");
    }

    private static String snapshot() {
        StringBuilder out = new StringBuilder();
        for (FileDescriptor file : CONTRACTS) {
            out.append("file ").append(file.getName())
                    .append(" package ").append(file.getPackage())
                    .append(" java_package ").append(file.getOptions().getJavaPackage()).append('\n');
            file.getServices().forEach(service -> render(service, out));
            file.getMessageTypes().forEach(message -> render(message, out));
            file.getEnumTypes().forEach(enumType -> render(enumType, out));
        }
        return out.toString();
    }

    private static void render(ServiceDescriptor service, StringBuilder out) {
        out.append("service ").append(service.getFullName()).append('\n');
        for (MethodDescriptor method : service.getMethods()) {
            out.append("  ").append(method.getName())
                    .append(' ').append(method.getInputType().getFullName())
                    .append(" -> ").append(method.getOutputType().getFullName()).append('\n');
        }
    }

    private static void render(Descriptor message, StringBuilder out) {
        if (message.getOptions().getMapEntry()) {
            return;
        }
        out.append("message ").append(message.getFullName()).append('\n');
        for (FieldDescriptor field : message.getFields()) {
            out.append("  ").append(field.getNumber())
                    .append(' ').append(typeOf(field))
                    .append(' ').append(field.getName()).append('\n');
        }
        for (DescriptorProto.ReservedRange range : message.toProto().getReservedRangeList()) {
            reserved(range.getStart(), range.getEnd() - 1, out);
        }
        message.getNestedTypes().forEach(nested -> render(nested, out));
        message.getEnumTypes().forEach(enumType -> render(enumType, out));
    }

    private static void render(EnumDescriptor enumType, StringBuilder out) {
        out.append("enum ").append(enumType.getFullName()).append('\n');
        for (EnumValueDescriptor value : enumType.getValues()) {
            out.append("  ").append(value.getNumber()).append(' ').append(value.getName()).append('\n');
        }
        for (EnumDescriptorProto.EnumReservedRange range : enumType.toProto().getReservedRangeList()) {
            reserved(range.getStart(), range.getEnd(), out);
        }
    }

    // Message ranges end exclusive, enum ranges inclusive: both are rendered inclusive.
    private static void reserved(int first, int last, StringBuilder out) {
        out.append("  reserved ").append(first);
        if (last != first) {
            out.append('-').append(last);
        }
        out.append('\n');
    }

    private static String typeOf(FieldDescriptor field) {
        if (field.isMapField()) {
            Descriptor entry = field.getMessageType();
            return "map<" + typeOf(entry.findFieldByNumber(1)) + "," + typeOf(entry.findFieldByNumber(2)) + ">";
        }
        String type = switch (field.getJavaType()) {
            case MESSAGE -> field.getMessageType().getFullName();
            case ENUM -> field.getEnumType().getFullName();
            default -> field.getType().name().toLowerCase(Locale.ROOT);
        };
        if (field.isRepeated()) {
            return "repeated " + type;
        }
        return field.toProto().getProto3Optional() ? "optional " + type : type;
    }

    private static final String WIRE = """
            file ecclesiaflow/auth/auth_service.proto package ecclesiaflow.auth java_package com.ecclesiaflow.grpc.auth
            service ecclesiaflow.auth.AuthService
              GenerateTemporaryToken ecclesiaflow.auth.TemporaryTokenRequest -> ecclesiaflow.auth.TemporaryTokenResponse
              ProvisionUserAndIssueSetupToken ecclesiaflow.auth.ProvisionUserAndIssueSetupTokenRequest -> ecclesiaflow.auth.ProvisionUserAndIssueSetupTokenResponse
              DeleteKeycloakUser ecclesiaflow.auth.DeleteKeycloakUserRequest -> ecclesiaflow.auth.DeleteKeycloakUserResponse
              DisableKeycloakUser ecclesiaflow.auth.DisableKeycloakUserRequest -> ecclesiaflow.auth.DisableKeycloakUserResponse
              UpdateKeycloakUserEmail ecclesiaflow.auth.UpdateKeycloakUserEmailRequest -> ecclesiaflow.auth.UpdateKeycloakUserEmailResponse
              UpdateKeycloakUserName ecclesiaflow.auth.UpdateKeycloakUserNameRequest -> ecclesiaflow.auth.UpdateKeycloakUserNameResponse
              UpdateKeycloakUserLocale ecclesiaflow.auth.UpdateKeycloakUserLocaleRequest -> ecclesiaflow.auth.UpdateKeycloakUserLocaleResponse
              AssignRealmRole ecclesiaflow.auth.AssignRealmRoleRequest -> ecclesiaflow.auth.AssignRealmRoleResponse
              RevokeRealmRole ecclesiaflow.auth.RevokeRealmRoleRequest -> ecclesiaflow.auth.RevokeRealmRoleResponse
              SetChurchClaims ecclesiaflow.auth.SetChurchClaimsRequest -> ecclesiaflow.auth.SetChurchClaimsResponse
              SendExistingAccountNotice ecclesiaflow.auth.SendExistingAccountNoticeRequest -> ecclesiaflow.auth.SendExistingAccountNoticeResponse
              NoticeSignupOnAddress ecclesiaflow.auth.NoticeSignupOnAddressRequest -> ecclesiaflow.auth.NoticeSignupOnAddressResponse
            message ecclesiaflow.auth.TemporaryTokenRequest
              1 string email
              2 string member_id
              3 string metadata
              4 string locale
            message ecclesiaflow.auth.TemporaryTokenResponse
              1 string temporary_token
              2 int32 expires_in_seconds
              3 string password_endpoint
            message ecclesiaflow.auth.ProvisionUserAndIssueSetupTokenRequest
              1 string email
              2 string first_name
              3 string last_name
              4 string metadata
              5 string locale
            message ecclesiaflow.auth.ProvisionUserAndIssueSetupTokenResponse
              1 string keycloak_user_id
              2 string temporary_token
              3 int32 expires_in_seconds
              4 string password_endpoint
              5 string actual_metadata
              6 bool is_replay
            message ecclesiaflow.auth.DeleteKeycloakUserRequest
              1 string keycloak_user_id
            message ecclesiaflow.auth.DeleteKeycloakUserResponse
              1 bool success
              2 string message
            message ecclesiaflow.auth.DisableKeycloakUserRequest
              1 string keycloak_user_id
            message ecclesiaflow.auth.DisableKeycloakUserResponse
              1 bool success
              2 string message
            message ecclesiaflow.auth.UpdateKeycloakUserEmailRequest
              1 string keycloak_user_id
              2 string new_email
            message ecclesiaflow.auth.UpdateKeycloakUserEmailResponse
              1 bool success
              2 string message
            message ecclesiaflow.auth.UpdateKeycloakUserNameRequest
              1 string keycloak_user_id
              2 string first_name
              3 string last_name
            message ecclesiaflow.auth.UpdateKeycloakUserNameResponse
              1 bool success
              2 string message
            message ecclesiaflow.auth.UpdateKeycloakUserLocaleRequest
              1 string keycloak_user_id
              2 string locale
            message ecclesiaflow.auth.UpdateKeycloakUserLocaleResponse
              1 bool success
              2 string message
            message ecclesiaflow.auth.AssignRealmRoleRequest
              1 string keycloak_user_id
              2 string role_name
            message ecclesiaflow.auth.AssignRealmRoleResponse
              1 bool success
              2 string message
            message ecclesiaflow.auth.RevokeRealmRoleRequest
              1 string keycloak_user_id
              2 string role_name
            message ecclesiaflow.auth.RevokeRealmRoleResponse
              1 bool success
              2 string message
            message ecclesiaflow.auth.SetChurchClaimsRequest
              1 string keycloak_user_id
              2 string church_id
              3 bool church_admin
              4 repeated string capabilities
              5 int64 capabilities_expire_at_epoch_ms
            message ecclesiaflow.auth.SetChurchClaimsResponse
            message ecclesiaflow.auth.SendExistingAccountNoticeRequest
              1 string keycloak_user_id
            message ecclesiaflow.auth.SendExistingAccountNoticeResponse
            message ecclesiaflow.auth.NoticeSignupOnAddressRequest
              1 string email
            message ecclesiaflow.auth.NoticeSignupOnAddressResponse
              1 bool account_exists
            file ecclesiaflow/church/church_service.proto package ecclesiaflow.church java_package com.ecclesiaflow.grpc.church
            service ecclesiaflow.church.ChurchService
              OnSignupCompleted ecclesiaflow.church.OnSignupCompletedRequest -> ecclesiaflow.church.OnSignupCompletedResponse
              RepublishActiveMemberships ecclesiaflow.church.RepublishActiveMembershipsRequest -> ecclesiaflow.church.RepublishActiveMembershipsResponse
              CheckAccountClosure ecclesiaflow.church.CheckAccountClosureRequest -> ecclesiaflow.church.CheckAccountClosureResponse
            message ecclesiaflow.church.OnSignupCompletedRequest
              1 string user_id
              2 string church_id
              3 string occurred_at
              4 int32 schema_version
            message ecclesiaflow.church.OnSignupCompletedResponse
              1 ecclesiaflow.church.ActivationStatus status
            message ecclesiaflow.church.RepublishActiveMembershipsRequest
              1 int32 schema_version
            message ecclesiaflow.church.RepublishActiveMembershipsResponse
              1 int64 republished_count
            message ecclesiaflow.church.CheckAccountClosureRequest
              1 string keycloak_user_id
            message ecclesiaflow.church.CheckAccountClosureResponse
              1 repeated string owned_church_ids
              2 repeated string last_admin_church_ids
            enum ecclesiaflow.church.ActivationStatus
              0 ACTIVATION_STATUS_UNSPECIFIED
              1 ACTIVATED
              2 ALREADY_ACTIVE
              3 NOT_FOUND
            file ecclesiaflow/members/members_service.proto package ecclesiaflow.members java_package com.ecclesiaflow.grpc.members
            service ecclesiaflow.members.MembersService
              GetMemberConfirmationStatus ecclesiaflow.members.ConfirmationStatusRequest -> ecclesiaflow.members.ConfirmationStatusResponse
              NotifyAccountActivated ecclesiaflow.members.AccountActivatedRequest -> ecclesiaflow.members.AccountActivatedResponse
              NotifyLocalCredentialsAdded ecclesiaflow.members.LocalCredentialsAddedRequest -> ecclesiaflow.members.LocalCredentialsAddedResponse
              GetMemberProfile ecclesiaflow.members.GetMemberProfileRequest -> ecclesiaflow.members.GetMemberProfileResponse
              GetMemberContacts ecclesiaflow.members.GetMemberContactsRequest -> ecclesiaflow.members.GetMemberContactsResponse
              ProvisionMember ecclesiaflow.members.ProvisionMemberRequest -> ecclesiaflow.members.ProvisionMemberResponse
            message ecclesiaflow.members.ConfirmationStatusRequest
              1 string email
            message ecclesiaflow.members.ConfirmationStatusResponse
              1 bool member_exists
              2 bool is_confirmed
            message ecclesiaflow.members.AccountActivatedRequest
              1 string member_id
              2 string keycloak_user_id
            message ecclesiaflow.members.AccountActivatedResponse
              1 bool success
              2 string message
            message ecclesiaflow.members.LocalCredentialsAddedRequest
              1 string keycloak_user_id
            message ecclesiaflow.members.LocalCredentialsAddedResponse
              1 bool success
              2 string message
            message ecclesiaflow.members.GetMemberProfileRequest
              1 string keycloak_user_id
              2 string caller_church_id
            message ecclesiaflow.members.GetMemberProfileResponse
              1 bool found
              2 string first_name
              3 string last_name
              4 string email
              5 string baptism_date
              6 string baptism_type
              7 string baptism_type_label
              8 bool phone_shared_with_church
              9 ecclesiaflow.members.BaptismDeclaration baptism_declared
            message ecclesiaflow.members.GetMemberContactsRequest
              1 repeated string keycloak_user_ids
              2 string caller_church_id
            message ecclesiaflow.members.GetMemberContactsResponse
              1 repeated ecclesiaflow.members.MemberContact contacts
            message ecclesiaflow.members.MemberContact
              1 string keycloak_user_id
              2 string phone_number
            message ecclesiaflow.members.ProvisionMemberRequest
              1 string keycloak_user_id
              2 string email
              3 string first_name
              4 string last_name
              5 string church_id
              6 string address
              7 string phone_number
            message ecclesiaflow.members.ProvisionMemberResponse
              1 string member_id
              2 bool created
            enum ecclesiaflow.members.BaptismDeclaration
              0 BAPTISM_DECLARATION_UNSPECIFIED
              1 BAPTISM_DECLARATION_NOT_BAPTISED
              2 BAPTISM_DECLARATION_BAPTISED
            file ecclesiaflow/email/email_service.proto package ecclesiaflow.email java_package com.ecclesiaflow.grpc.email
            service ecclesiaflow.email.EmailService
              SendEmail ecclesiaflow.email.SendEmailRequest -> ecclesiaflow.email.SendEmailResponse
              GetEmailStatus ecclesiaflow.email.EmailStatusRequest -> ecclesiaflow.email.EmailStatusResponse
              SendBulkEmails ecclesiaflow.email.SendBulkEmailsRequest -> ecclesiaflow.email.SendBulkEmailsResponse
            message ecclesiaflow.email.SendEmailRequest
              1 repeated string to
              2 optional string from
              3 string subject
              4 ecclesiaflow.email.EmailTemplateType template_type
              5 map<string,string> variables
              6 ecclesiaflow.email.Priority priority
              7 optional string locale
            message ecclesiaflow.email.SendEmailResponse
              1 string email_id
              2 ecclesiaflow.email.Status status
              3 int64 queued_at
              4 optional string message
            message ecclesiaflow.email.EmailStatusRequest
              1 string email_id
            message ecclesiaflow.email.EmailStatusResponse
              1 string email_id
              2 repeated string to
              3 string subject
              4 ecclesiaflow.email.Status status
              5 optional string provider
              6 int64 queued_at
              7 optional int64 sent_at
              8 optional int64 delivered_at
              9 optional int64 failed_at
              10 optional int64 opened_at
              11 int32 clicks
              12 optional string error_message
            message ecclesiaflow.email.SendBulkEmailsRequest
              1 repeated ecclesiaflow.email.SendEmailRequest emails
            message ecclesiaflow.email.SendBulkEmailsResponse
              1 repeated ecclesiaflow.email.SendEmailResponse results
              2 int32 total
              3 int32 queued
              4 int32 failed
            message ecclesiaflow.email.EmailQueueMessage
              1 string email_id
              2 optional string from
              3 repeated string to
              4 string subject
              5 string template_name
              6 map<string,string> variables
              7 ecclesiaflow.email.Priority priority
              8 int32 retry_count
            enum ecclesiaflow.email.EmailTemplateType
              0 EMAIL_TEMPLATE_UNSPECIFIED
              3 EMAIL_TEMPLATE_WELCOME
              4 EMAIL_TEMPLATE_EMAIL_CONFIRMATION
              5 EMAIL_TEMPLATE_PROFILE_UPDATED
              6 EMAIL_TEMPLATE_EMAIL_CHANGE_VERIFICATION
              7 EMAIL_TEMPLATE_CHURCH_SIGNUP_SETUP
              8 EMAIL_TEMPLATE_CHURCH_INVITATION
              reserved 1
              reserved 2
            enum ecclesiaflow.email.Priority
              0 PRIORITY_UNSPECIFIED
              1 PRIORITY_HIGH
              2 PRIORITY_NORMAL
              3 PRIORITY_LOW
            enum ecclesiaflow.email.Status
              0 STATUS_UNSPECIFIED
              1 STATUS_QUEUED
              2 STATUS_SENT
              3 STATUS_DELIVERED
              4 STATUS_FAILED
              5 STATUS_BOUNCED
            file ecclesiaflow/events/auth/v1/domain_events.proto package ecclesiaflow.events.auth.v1 java_package com.ecclesiaflow.grpc.events.auth
            message ecclesiaflow.events.auth.v1.SetupTokenIssuedEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string email
              4 string raw_token
              5 string flow_id
              6 string display_context_json
              7 int32 expires_in_seconds
              8 bytes sealed_raw_token
              9 string sealed_key_id
              10 string locale
            message ecclesiaflow.events.auth.v1.ExistingAccountNoticeEvent
              1 string email
              2 string locale
              3 string event_id
            file ecclesiaflow/events/church/v1/church_events.proto package ecclesiaflow.events.church.v1 java_package com.ecclesiaflow.grpc.events.church
            message ecclesiaflow.events.church.v1.ChurchInvitationCreatedEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string recipient_email
              4 string invitation_token
              5 string church_id
              6 string church_name
              7 int32 expires_in_seconds
              8 string locale
            message ecclesiaflow.events.church.v1.MemberRemovedFromChurchEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string church_id
              4 string member_user_id
              5 string member_display_name
            message ecclesiaflow.events.church.v1.MemberAdmittedToChurchEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string church_id
              4 string member_user_id
              5 string member_display_name
            message ecclesiaflow.events.church.v1.MemberAddedToGroupEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string church_id
              4 string group_id
              5 string member_user_id
              6 string member_display_name
            message ecclesiaflow.events.church.v1.MemberRemovedFromGroupEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string church_id
              4 string group_id
              5 string member_user_id
              6 string member_display_name
            file ecclesiaflow/events/members/v1/members_events.proto package ecclesiaflow.events.members.v1 java_package com.ecclesiaflow.grpc.events.members
            message ecclesiaflow.events.members.v1.MemberProfileChangedEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string keycloak_user_id
              4 string first_name
              5 string last_name
              6 string email
              7 string baptism_date
              8 string baptism_type
              9 string baptism_type_label
              10 bool phone_shared_with_church
              11 ecclesiaflow.events.members.v1.BaptismDeclaration baptism_declared
            message ecclesiaflow.events.members.v1.MemberAnonymizedEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string keycloak_user_id
            message ecclesiaflow.events.members.v1.MemberContactsErasedEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string keycloak_user_id
              4 repeated string recipient_digests
            message ecclesiaflow.events.members.v1.MemberDeactivatedEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string keycloak_user_id
              4 repeated string recipient_digests
            message ecclesiaflow.events.members.v1.MemberReactivatedEvent
              1 string event_id
              2 int64 occurred_at_epoch_ms
              3 string keycloak_user_id
              4 repeated string recipient_digests
            enum ecclesiaflow.events.members.v1.BaptismDeclaration
              0 BAPTISM_DECLARATION_UNSPECIFIED
              1 BAPTISM_DECLARATION_NOT_BAPTISED
              2 BAPTISM_DECLARATION_BAPTISED
            """;
}
