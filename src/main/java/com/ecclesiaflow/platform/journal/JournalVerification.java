package com.ecclesiaflow.platform.journal;

import java.time.Duration;
import java.time.Instant;

/** A completed walk of the chain: when it ended, how long it took, what it found. */
public record JournalVerification(Instant verifiedAt, Duration took, JournalVerdict verdict) {
}
