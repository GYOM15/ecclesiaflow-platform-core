package com.ecclesiaflow.platform.security.reauth.code;

import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The store's contract kept in memory, with transactions that roll back on an exception as the database does:
 * what a refused call wrote before refusing must not survive it.
 */
public final class InMemoryEmailCodeStore implements EmailCodeStore {

    public record Occurrence(String subject, EmailCodeOccurrence kind, UUID challengeId, Instant at) {
    }

    public final Map<UUID, EmailCodeChallenge> challenges = new LinkedHashMap<>();
    public final List<Occurrence> occurrences = new ArrayList<>();
    public final List<String> locks = new ArrayList<>();
    public int transactionsRun;

    public final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            transactionsRun++;
            Map<UUID, EmailCodeChallenge> savedChallenges = new LinkedHashMap<>(challenges);
            List<Occurrence> savedOccurrences = new ArrayList<>(occurrences);
            try {
                return action.doInTransaction(new SimpleTransactionStatus());
            } catch (RuntimeException rollback) {
                challenges.clear();
                challenges.putAll(savedChallenges);
                occurrences.clear();
                occurrences.addAll(savedOccurrences);
                throw rollback;
            }
        }
    };

    @Override
    public void lockAccount(String subject) {
        locks.add(subject);
    }

    @Override
    public Optional<EmailCodeChallenge> findOpen(String subject) {
        return challenges.values().stream()
                .filter(challenge -> challenge.subject().equals(subject))
                .filter(challenge -> challenge.status() == EmailCodeStatus.PENDING)
                .findFirst();
    }

    @Override
    public Optional<EmailCodeChallenge> findForUpdate(UUID challengeId) {
        return Optional.ofNullable(challenges.get(challengeId));
    }

    @Override
    public void insert(EmailCodeChallenge challenge) {
        if (challenges.containsKey(challenge.id()) || findOpen(challenge.subject()).isPresent()) {
            throw new IllegalStateException("duplicate or second pending challenge");
        }
        challenges.put(challenge.id(), challenge);
    }

    @Override
    public void update(EmailCodeChallenge challenge) {
        if (!challenges.containsKey(challenge.id())) {
            throw new IllegalStateException("no such challenge");
        }
        challenges.put(challenge.id(), challenge);
    }

    @Override
    public boolean consume(UUID challengeId, Instant consumedAt) {
        EmailCodeChallenge challenge = challenges.get(challengeId);
        if (challenge == null || challenge.status() != EmailCodeStatus.PENDING) {
            return false;
        }
        challenges.put(challengeId, challenge.consumed(consumedAt));
        return true;
    }

    @Override
    public void record(String subject, EmailCodeOccurrence occurrence, UUID challengeId, Instant occurredAt) {
        occurrences.add(new Occurrence(subject, occurrence, challengeId, occurredAt));
    }

    @Override
    public void forget(String subject, EmailCodeOccurrence occurrence, UUID challengeId, Instant occurredAt) {
        occurrences.remove(new Occurrence(subject, occurrence, challengeId, occurredAt));
    }

    @Override
    public CounterWindow window(String subject, EmailCodeOccurrence occurrence, Instant since) {
        List<Instant> within = occurrences.stream()
                .filter(o -> o.subject().equals(subject) && o.kind() == occurrence && o.at().isAfter(since))
                .map(Occurrence::at)
                .sorted(Comparator.naturalOrder())
                .toList();
        return new CounterWindow(within.size(), within.isEmpty() ? null : within.get(0));
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        int before = challenges.size() + occurrences.size();
        challenges.values().removeIf(challenge -> challenge.createdAt().isBefore(cutoff));
        occurrences.removeIf(occurrence -> occurrence.at().isBefore(cutoff));
        return before - challenges.size() - occurrences.size();
    }

    public long count(String subject, EmailCodeOccurrence kind) {
        return occurrences.stream().filter(o -> o.subject().equals(subject) && o.kind() == kind).count();
    }

    public EmailCodeChallenge only() {
        return challenges.values().stream().reduce((first, second) -> {
            throw new IllegalStateException("more than one challenge");
        }).orElseThrow();
    }

    public EmailCodeChallenge get(UUID id) {
        return Objects.requireNonNull(challenges.get(id));
    }
}
