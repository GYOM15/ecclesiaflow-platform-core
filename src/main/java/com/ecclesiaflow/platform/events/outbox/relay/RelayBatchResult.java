package com.ecclesiaflow.platform.events.outbox.relay;

/**
 * @param deferred      rows handed back without an attempt counted, because the broker could
 *                      not be reached or the relay was interrupted
 * @param batchWasFull  whether the claim hit the batch size, so more rows are probably due
 */
public record RelayBatchResult(int claimed, int sent, int retried, int parked, int deferred, boolean batchWasFull) {

    public static final RelayBatchResult EMPTY = new RelayBatchResult(0, 0, 0, 0, 0, false);
}
