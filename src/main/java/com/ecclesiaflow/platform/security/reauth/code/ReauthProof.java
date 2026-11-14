package com.ecclesiaflow.platform.security.reauth.code;

import java.util.Objects;
import java.util.UUID;

/** What a replayed request presents: the challenge of its 202 and the code the mail gave. */
public record ReauthProof(UUID challengeId, String code) {

    public ReauthProof {
        Objects.requireNonNull(challengeId, "challengeId");
        Objects.requireNonNull(code, "code");
    }

    @Override
    public String toString() {
        return "ReauthProof[challengeId=" + challengeId + "]";
    }
}
