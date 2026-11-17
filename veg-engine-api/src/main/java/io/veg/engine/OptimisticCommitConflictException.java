package io.veg.engine;

import io.veg.model.CommitId;

import java.util.Objects;

/**
 * Thrown under optimistic MVCC concurrency control when another transaction commits on the
 * target branch before this transaction publishes its changes.
 */
public final class OptimisticCommitConflictException extends TransactionStateException {

    private final String branchName;
    private final CommitId expectedBaseCommitId;
    private final CommitId actualHeadCommitId;

    public OptimisticCommitConflictException(
            String branchName,
            CommitId expectedBaseCommitId,
            CommitId actualHeadCommitId
    ) {
        super("Concurrent commit conflict on branch '" + branchName + "': transaction based on "
                + expectedBaseCommitId + " but head is now " + actualHeadCommitId);
        this.branchName = Objects.requireNonNull(branchName, "branchName must not be null");
        this.expectedBaseCommitId = Objects.requireNonNull(expectedBaseCommitId, "expectedBaseCommitId must not be null");
        this.actualHeadCommitId = Objects.requireNonNull(actualHeadCommitId, "actualHeadCommitId must not be null");
    }

    public String branchName() {
        return branchName;
    }

    public CommitId expectedBaseCommitId() {
        return expectedBaseCommitId;
    }

    public CommitId actualHeadCommitId() {
        return actualHeadCommitId;
    }
}
