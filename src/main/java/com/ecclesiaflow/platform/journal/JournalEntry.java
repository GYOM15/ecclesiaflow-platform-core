package com.ecclesiaflow.platform.journal;

import java.util.Map;
import java.util.TreeMap;

/**
 * One gesture to journal, checked when built: every field is an id or a code, never a name, an address, a phone number,
 * a content or free text. {@code details} holds a few non-personal pairs (a verification channel, a state before and
 * after), sorted by key.
 *
 * @param requestId   the trace id of the request, {@code null} outside one
 * @param gesture     an open vocabulary: {@code ACCOUNT_SEARCHED}, {@code ACCOUNT_SUSPENDED}, {@code JOURNAL_READ}...
 * @param refusalCode set exactly when the gesture was refused
 * @param targetKind  what the gesture was about: {@code ACCOUNT}, {@code CHURCH}, {@code REPORT}...
 * @param targetRef   the target's internal id; for a search that found nothing, the address's digest
 * @param caseRef     the request or the report the gesture belongs to
 * @param reasonCode  the motive's code
 */
public record JournalEntry(JournalActor actor, String requestId, String gesture, JournalOutcome outcome,
                           String refusalCode, String targetKind, String targetRef, String caseRef, String reasonCode,
                           Map<String, String> details) {

    private static final int MAX_ID = 64;
    private static final int MAX_TARGET_KIND = 32;
    private static final int MAX_REF = 128;

    public JournalEntry {
        if (actor == null) {
            throw InvalidJournalEntryException.missing("actor");
        }
        if (outcome == null) {
            throw InvalidJournalEntryException.missing("outcome");
        }
        requestId = JournalValues.ref("requestId", requestId, MAX_ID, false);
        gesture = JournalValues.code("gesture", gesture, true);
        refusalCode = JournalValues.code("refusalCode", refusalCode, outcome == JournalOutcome.REFUSED);
        if (outcome == JournalOutcome.DONE && refusalCode != null) {
            throw InvalidJournalEntryException.refusalWithoutRefusal();
        }
        targetKind = JournalValues.code("targetKind", targetKind, MAX_TARGET_KIND, true);
        targetRef = JournalValues.ref("targetRef", targetRef, MAX_REF, false);
        caseRef = JournalValues.ref("caseRef", caseRef, MAX_REF, false);
        reasonCode = JournalValues.code("reasonCode", reasonCode, false);
        details = JournalValues.details(details);
    }

    /** A gesture carried out. */
    public static JournalEntry done(JournalActor actor, String requestId, String gesture, String targetKind,
                                    String targetRef) {
        return new JournalEntry(actor, requestId, gesture, JournalOutcome.DONE, null, targetKind, targetRef, null,
                null, Map.of());
    }

    /** The same gesture, refused by the module with this code. */
    public JournalEntry refused(String code) {
        return new JournalEntry(actor, requestId, gesture, JournalOutcome.REFUSED, code, targetKind, targetRef, caseRef,
                reasonCode, details);
    }

    public JournalEntry withCase(String ref) {
        return new JournalEntry(actor, requestId, gesture, outcome, refusalCode, targetKind, targetRef, ref,
                reasonCode, details);
    }

    public JournalEntry withReason(String code) {
        return new JournalEntry(actor, requestId, gesture, outcome, refusalCode, targetKind, targetRef, caseRef, code,
                details);
    }

    public JournalEntry withDetail(String key, String value) {
        Map<String, String> more = new TreeMap<>(details);
        more.put(key, value);
        return new JournalEntry(actor, requestId, gesture, outcome, refusalCode, targetKind, targetRef, caseRef,
                reasonCode, more);
    }
}
