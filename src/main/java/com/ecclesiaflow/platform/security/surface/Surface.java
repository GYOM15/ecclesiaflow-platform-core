package com.ecclesiaflow.platform.security.surface;

import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.contract.InvalidApiContractException;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Map;

/**
 * Who an operation is exposed to, read from its {@code x-ef-surface} extension, and what a token must carry
 * to reach it. Applies to requests that present a token; a {@code security: []} operation is never ruled.
 */
public enum Surface {

    /** {@code x-ef-surface: member}: any valid token; a caller outside the console has its admin powers capped. */
    MEMBER("member") {
        @Override
        public boolean permits(Collection<? extends GrantedAuthority> authorities) {
            return true;
        }
    },

    /** No {@code x-ef-surface}: the web console only, whose tokens alone carry {@code ef:surface:full}. */
    CONSOLE("console") {
        @Override
        public boolean permits(Collection<? extends GrantedAuthority> authorities) {
            return has(authorities, SURFACE_FULL);
        }
    },

    /**
     * {@code x-ef-surface: platform}, without {@code x-ef-platform-duty} or with {@code moderation}: platform
     * moderation. The scope and the role are both required, and {@code ef:surface:full} never stands in for either.
     */
    PLATFORM("platform") {
        @Override
        public boolean permits(Collection<? extends GrantedAuthority> authorities) {
            return has(authorities, MODERATION_ACT) && has(authorities, PLATFORM_MODERATOR);
        }
    },

    /**
     * {@code x-ef-surface: platform} with {@code x-ef-platform-duty: billing}: billing operations. A couple of
     * its own, so a moderator never reaches the money and a billing operator never moderates.
     */
    PLATFORM_BILLING("platform-billing") {
        @Override
        public boolean permits(Collection<? extends GrantedAuthority> authorities) {
            return has(authorities, BILLING_OPERATE) && has(authorities, BILLING_OPERATOR);
        }
    };

    public static final String EXTENSION = "x-ef-surface";
    public static final String DUTY_EXTENSION = "x-ef-platform-duty";

    public static final String SURFACE_FULL = "SCOPE_ef:surface:full";
    public static final String MODERATION_ACT = "SCOPE_ef:moderation:act";
    public static final String PLATFORM_MODERATOR = "ROLE_PLATFORM_MODERATOR";
    public static final String BILLING_OPERATE = "SCOPE_ef:billing:operate";
    public static final String BILLING_OPERATOR = "ROLE_PLATFORM_BILLING";

    private static final String MODERATION_DUTY = "moderation";
    private static final String BILLING_DUTY = "billing";

    private final String tag;

    Surface(String tag) {
        this.tag = tag;
    }

    /** Whether a token converted to these authorities reaches an operation of this surface. */
    public abstract boolean permits(Collection<? extends GrantedAuthority> authorities);

    /** The value a metric or a log line carries. */
    public String tag() {
        return tag;
    }

    /**
     * Reads the operation's declaration.
     *
     * @throws InvalidApiContractException when {@code x-ef-surface} holds anything but {@code member} or
     *                                     {@code platform}, or marks a {@code security: []} operation
     *                                     {@code platform}, which would open moderation to callers without a token;
     *                                     when {@code x-ef-platform-duty} holds anything but {@code moderation} or
     *                                     {@code billing}, or stands beside another surface, where it would rule
     *                                     nothing
     */
    public static Surface of(ContractOperation operation) {
        Map<String, Object> extensions = operation.extensions();
        if (!extensions.containsKey(EXTENSION)) {
            refuseStrayDuty(operation);
            return CONSOLE;
        }
        Object value = extensions.get(EXTENSION);
        if (MEMBER.tag.equals(value)) {
            refuseStrayDuty(operation);
            return MEMBER;
        }
        if (PLATFORM.tag.equals(value)) {
            if (operation.tokenless()) {
                throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                        "member or no value on a security: [] operation");
            }
            return dutyOf(operation);
        }
        throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value, "member or platform");
    }

    private static void refuseStrayDuty(ContractOperation operation) {
        if (operation.extensions().containsKey(DUTY_EXTENSION)) {
            throw InvalidApiContractException.invalidExtension(operation, DUTY_EXTENSION,
                    operation.extensions().get(DUTY_EXTENSION), "no value beside an x-ef-surface other than platform");
        }
    }

    // Absent is moderation, the only duty before billing's: the contracts written then keep their meaning.
    private static Surface dutyOf(ContractOperation operation) {
        if (!operation.extensions().containsKey(DUTY_EXTENSION)) {
            return PLATFORM;
        }
        Object duty = operation.extensions().get(DUTY_EXTENSION);
        if (MODERATION_DUTY.equals(duty)) {
            return PLATFORM;
        }
        if (BILLING_DUTY.equals(duty)) {
            return PLATFORM_BILLING;
        }
        throw InvalidApiContractException.invalidExtension(operation, DUTY_EXTENSION, duty, "moderation or billing");
    }

    private static boolean has(Collection<? extends GrantedAuthority> authorities, String expected) {
        return authorities != null
                && authorities.stream().anyMatch(authority -> expected.equals(authority.getAuthority()));
    }
}
