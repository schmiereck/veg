package io.veg.engine.memory;

import io.veg.engine.GraphTransaction;
import io.veg.engine.OptimisticCommitConflictException;
import io.veg.engine.TransactionStateException;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.CommitId;
import io.veg.model.Reason;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Shared MVCC idempotent transaction coordinator across all {@link VersionedGraphDatabase} backends ({@code V-15}).
 */
public final class IdempotentCommitSupport {

    private static final int MAX_IDEMPOTENT_RETRIES = 16;

    private IdempotentCommitSupport() {
    }

    public static String compositeKey(String branchName, String idempotencyKey) {
        return branchName + "\u0000" + idempotencyKey;
    }

    public static CommitId execute(
            VersionedGraphDatabase db,
            Object[] stripes,
            Function<String, Optional<CommitId>> lookup,
            BiConsumer<String, CommitId> register,
            String branchName,
            String idempotencyKey,
            Actor actor,
            Reason reason,
            String correlationId,
            Consumer<GraphTransaction> work
    ) {
        Objects.requireNonNull(db, "db must not be null");
        Objects.requireNonNull(branchName, "branchName must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(work, "work must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }

        String key = compositeKey(branchName, idempotencyKey);
        Object stripe = stripes[(key.hashCode() & Integer.MAX_VALUE) % stripes.length];

        synchronized (stripe) {
            Optional<CommitId> existing = lookup.apply(key);
            if (existing.isPresent()) {
                return existing.get();
            }

            OptimisticCommitConflictException lastConflict = null;
            for (int attempt = 1; attempt <= MAX_IDEMPOTENT_RETRIES; attempt++) {
                try (GraphTransaction tx = db.beginTransaction(branchName, actor, reason, correlationId)) {
                    work.accept(tx);
                    if (!tx.isOpen()) {
                        throw new TransactionStateException(
                                "Transaction callback must leave closing/committing to executeIdempotent");
                    }
                    CommitId commitId = tx.commit();
                    register.accept(key, commitId);
                    return commitId;
                } catch (OptimisticCommitConflictException conflict) {
                    lastConflict = conflict;
                }
            }
            throw lastConflict;
        }
    }

    public static Object[] createStripes() {
        Object[] stripes = new Object[64];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }
}
