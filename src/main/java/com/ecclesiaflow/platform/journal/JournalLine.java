package com.ecclesiaflow.platform.journal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A line as the journal holds it, hashes included. Kept as read, unchecked: the verifier must see what is stored, not
 * what an entry would have allowed.
 *
 * @param seq         the line's position in the module's chain, from 1
 * @param outcome     {@code DONE} or {@code REFUSED}, as stored
 * @param prevHashHex the previous line's hash, lowercase hex; {@code null} for the first line
 * @param rowHashHex  the line's hash, lowercase hex
 */
public record JournalLine(long seq, Instant occurredAt, String actorSubject, List<String> actorRoles,
                          String actorSession, String requestId, String gesture, String outcome, String refusalCode,
                          String targetKind, String targetRef, String caseRef, String reasonCode,
                          Map<String, String> details, String prevHashHex, String rowHashHex) {

    public JournalLine {
        actorRoles = actorRoles == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(actorRoles));
        details = details == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(details));
    }
}
