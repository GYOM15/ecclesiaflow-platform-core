package com.ecclesiaflow.platform.webhooks.processing;

/** @param batchWasFull whether the claim hit the batch size, so more notifications are probably due */
public record ProcessingBatchResult(int claimed, int handled, int retried, int parked, boolean batchWasFull) {

    public static final ProcessingBatchResult EMPTY = new ProcessingBatchResult(0, 0, 0, 0, false);
}
