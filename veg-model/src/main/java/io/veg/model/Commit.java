package io.veg.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable version transition recorded in the commit DAG (V-01, V-02, V-09).
 */
public record Commit(
        CommitId id,
        List<CommitId> parentIds,
        String branchName,
        DataCategory dataCategory,
        Actor actor,
        Reason reason,
        Optional<String> correlationId,
        Instant timestamp,
        List<Change> changes
) {
    public Commit {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(parentIds, "parentIds must not be null");
        Objects.requireNonNull(branchName, "branchName must not be null");
        Objects.requireNonNull(dataCategory, "dataCategory must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(changes, "changes must not be null");
        if (branchName.isBlank()) {
            throw new IllegalArgumentException("branchName must not be blank");
        }
        parentIds = List.copyOf(parentIds);
        changes = List.copyOf(changes);
    }

    /**
     * Compatibility constructor for single-parent linear commits on {@code "main"}.
     */
    public Commit(
            CommitId id,
            Optional<CommitId> parentId,
            Actor actor,
            Reason reason,
            Optional<String> correlationId,
            Instant timestamp,
            List<Change> changes
    ) {
        this(
                id,
                Objects.requireNonNull(parentId, "parentId must not be null").map(List::of).orElseGet(List::of),
                "main",
                DataCategory.REFERENCE,
                actor,
                reason,
                correlationId,
                timestamp,
                changes
        );
    }

    public Optional<CommitId> parentId() {
        return parentIds.isEmpty() ? Optional.empty() : Optional.of(parentIds.getFirst());
    }

    public boolean isMergeCommit() {
        return parentIds.size() > 1;
    }

    /**
     * Returns {@code true} if any change in this commit directly creates, removes, updates,
     * connects, or disconnects {@code entityId}.
     */
    public boolean touchesEntity(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        for (Change change : changes) {
            switch (change) {
                case Change.EntityCreated(EntityNode node) -> {
                    if (node.id().equals(entityId)) {
                        return true;
                    }
                }
                case Change.EntityRemoved(EntityNode node) -> {
                    if (node.id().equals(entityId)) {
                        return true;
                    }
                }
                case Change.ValueUpdated(EntityId id, _, _) -> {
                    if (id.equals(entityId)) {
                        return true;
                    }
                }
                case Change.EdgeConnected(Edge edge) -> {
                    if (edge.id().equals(entityId)
                            || edge.sourceId().equals(entityId)
                            || edge.targetId().equals(entityId)) {
                        return true;
                    }
                }
                case Change.EdgeDisconnected(Edge edge) -> {
                    if (edge.id().equals(entityId)
                            || edge.sourceId().equals(entityId)
                            || edge.targetId().equals(entityId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
