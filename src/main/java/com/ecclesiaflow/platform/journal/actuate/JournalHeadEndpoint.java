package com.ecclesiaflow.platform.journal.actuate;

import com.ecclesiaflow.platform.journal.JournalHead;
import com.ecclesiaflow.platform.journal.JournalIntegrity;
import com.ecclesiaflow.platform.journal.JournalPosition;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

/**
 * {@code /actuator/journalhead} on the unpublished management port: the head the host's nightly task copies off the
 * server, and {@code /actuator/journalhead/{seq}}, the hash at the position yesterday's copy named. Positions and
 * hashes only, never a line.
 */
@Endpoint(id = "journalhead")
public class JournalHeadEndpoint {

    private final JournalIntegrity integrity;
    private final String module;

    public JournalHeadEndpoint(JournalIntegrity integrity, String module) {
        this.integrity = integrity;
        this.module = module;
    }

    @ReadOperation
    public HeadDescriptor head() {
        JournalHead head = integrity.head();
        return new HeadDescriptor(module, head.lastSeq(), head.lastHashHex(), head.startSeq(), head.startHashHex());
    }

    @ReadOperation
    public PositionDescriptor position(@Selector long seq) {
        JournalPosition position = integrity.position(seq);
        return new PositionDescriptor(module, position.seq(), position.state().name(), position.hashHex());
    }

    public record HeadDescriptor(String module, long lastSeq, String lastHash, long startSeq, String startHash) {
    }

    public record PositionDescriptor(String module, long seq, String state, String hash) {
    }
}
