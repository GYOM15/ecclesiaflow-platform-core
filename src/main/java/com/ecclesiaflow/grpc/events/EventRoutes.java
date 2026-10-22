package com.ecclesiaflow.grpc.events;

/**
 * The exact routing keys the event messages declare in {@code (ecclesiaflow.events.routing_keys)}.
 * Publishers and bindings name these constants, never a literal or a wildcard: a copied key drifts
 * from its producer, and ContractWireCompatibilityTest holds this class equal to the declarations.
 */
public final class EventRoutes {

    public static final String AUTH_SETUP_TOKEN_ISSUED_V1 = "auth.setup-token.issued.v1";
    public static final String AUTH_EXISTING_ACCOUNT_NOTICE_V1 = "auth.existing-account.notice.v1";
    public static final String AUTH_TWO_FACTOR_REMOVED_V1 = "auth.two-factor.removed.v1";
    public static final String AUTH_ACCOUNT_SESSIONS_REVOKED_V1 = "auth.account.sessions-revoked.v1";

    public static final String CHURCH_INVITATION_CREATED_V1 = "church.invitation.created.v1";
    public static final String CHURCH_MEMBER_REMOVED_FROM_CHURCH_V1 = "church.member.removed-from-church.v1";
    public static final String CHURCH_MEMBER_ADMITTED_TO_CHURCH_V1 = "church.member.admitted-to-church.v1";
    public static final String CHURCH_MEMBER_ADDED_TO_GROUP_V1 = "church.member.added-to-group.v1";
    public static final String CHURCH_MEMBER_REMOVED_FROM_GROUP_V1 = "church.member.removed-from-group.v1";
    public static final String CHURCH_NOTIFICATION_CREATED_V1 = "church.notification.created.v1";

    // Published before the .v1 suffix rule; a key never changes, so these stay as they are.
    public static final String MEMBERS_PROFILE_CHANGED = "members.profile.changed";
    public static final String MEMBERS_PROFILE_ANONYMIZED = "members.profile.anonymized";
    public static final String MEMBERS_CONTACTS_ERASED = "members.contacts.erased";
    public static final String MEMBERS_ACCOUNT_DEACTIVATED = "members.account.deactivated";
    public static final String MEMBERS_ACCOUNT_REACTIVATED = "members.account.reactivated";

    public static final String MEMBERS_ACCOUNT_SUSPENDED_V1 = "members.account.suspended.v1";
    public static final String MEMBERS_ACCOUNT_SUSPENSION_LIFTED_V1 = "members.account.suspension-lifted.v1";

    public static final String CHAT_MESSAGE_POSTED_V1 = "chat.message.posted.v1";

    // One key per publishing module: a consumer of the message binds each of them by name.
    public static final String CHAT_CONTENT_REPORTED_V1 = "chat.content.reported.v1";
    public static final String CHURCH_CONTENT_REPORTED_V1 = "church.content.reported.v1";
    public static final String CHAT_MODERATION_ACTION_TAKEN_V1 = "chat.moderation.action-taken.v1";
    public static final String CHURCH_MODERATION_ACTION_TAKEN_V1 = "church.moderation.action-taken.v1";
    public static final String MEMBERS_MODERATION_ACTION_TAKEN_V1 = "members.moderation.action-taken.v1";

    private EventRoutes() {
    }
}
