package io.veg.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of a three-way merge operation from {@code sourceCommitId} into {@code targetCommitId}.
 */
public record MergeResult(
        boolean succeeded,
        Optional<CommitId> mergeCommitId,
        CommitId baseCommitId,
        CommitId sourceCommitId,
        CommitId targetCommitId,
        List<MergeConflict> conflicts
) {
    public MergeResult {
        Objects.requireNonNull(mergeCommitId, "mergeCommitId must not be null");
        Objects.requireNonNull(baseCommitId, "baseCommitId must not be null");
        Objects.requireNonNull(sourceCommitId, "sourceCommitId must not be null");
        Objects.requireNonNull(targetCommitId, "targetCommitId must not be null");
        Objects.requireNonNull(conflicts, "conflicts must not be null");
        conflicts = List.copyOf(conflicts);
        if (succeeded && (!conflicts.isEmpty() || mergeCommitId.isEmpty())) {
            throw new IllegalArgumentException("Successful merge must have a mergeCommitId and no conflicts");
        }
        if (!succeeded && (conflicts.isEmpty() || mergeCommitId.isPresent())) {
            throw new IllegalArgumentException("Failed merge must have conflicts and no mergeCommitId");
        }
    }
}
