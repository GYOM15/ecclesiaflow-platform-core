package com.ecclesiaflow.platform.idempotency;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Opens a fresh transaction on every call and records its life, so a test sees which work ran in which
 * transaction and how each ended. Spring still marks the thread as inside a transaction while it runs.
 */
public class RecordingTransactionManager extends AbstractPlatformTransactionManager {

    private final List<String> log = new CopyOnWriteArrayList<>();

    /** {@code begin}, {@code begin read-only}, {@code commit} and {@code rollback}, in order. */
    public List<String> log() {
        return log;
    }

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        log.add(definition.isReadOnly() ? "begin read-only" : "begin");
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        log.add("commit");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        log.add("rollback");
    }
}
