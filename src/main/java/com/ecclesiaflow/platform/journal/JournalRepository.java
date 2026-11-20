package com.ecclesiaflow.platform.journal;

import java.util.List;
import java.util.Optional;

/** The chain as stored, for its verification, its anchors and its purge. */
public interface JournalRepository {

    JournalHead head();

    /** Up to {@code limit} lines after position {@code afterSeq}, up to {@code throughSeq}, in position order. */
    List<JournalLine> lines(long afterSeq, long throughSeq, int limit);

    /** The hash of the line at this position, when a line holds it. */
    Optional<String> rowHashAt(long seq);

    /** Removes the lines past the retention and moves the start point onto them; returns how many. */
    long purgeExpired();
}
