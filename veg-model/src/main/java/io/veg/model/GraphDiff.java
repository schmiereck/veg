package io.veg.model;

import java.util.List;
import java.util.Objects;

/**
 * Structural difference between two graph snapshots ({@code fromCommitId -> toCommitId}).
 */
public record GraphDiff(
        CommitId fromCommitId,
        CommitId toCommitId,
        List<EntityNode> addedEntities,
        List<EntityNode> removedEntities,
        List<Change.ValueUpdated> updatedValues,
        List<Edge> addedEdges,
        List<Edge> removedEdges
) {
    public GraphDiff {
        Objects.requireNonNull(fromCommitId, "fromCommitId must not be null");
        Objects.requireNonNull(toCommitId, "toCommitId must not be null");
        addedEntities = List.copyOf(addedEntities);
        removedEntities = List.copyOf(removedEntities);
        updatedValues = List.copyOf(updatedValues);
        addedEdges = List.copyOf(addedEdges);
        removedEdges = List.copyOf(removedEdges);
    }

    public boolean isEmpty() {
        return addedEntities.isEmpty()
                && removedEntities.isEmpty()
                && updatedValues.isEmpty()
                && addedEdges.isEmpty()
                && removedEdges.isEmpty();
    }
}
