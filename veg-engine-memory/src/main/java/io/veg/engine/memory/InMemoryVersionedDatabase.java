package io.veg.engine.memory;

import io.veg.engine.CommitNotFoundException;
import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.RevertConflictException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.TransactionStateException;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.BootstrapIds;
import io.veg.model.Change;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.GraphDiff;
import io.veg.model.Reason;
import io.veg.model.Value;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Linear append-only versioned in-memory database implementing atomic transactions,
 * historical snapshots ({@code asOf}), structural diffs, counter-commit reverts, and full replay (Stage 3).
 */
public final class InMemoryVersionedDatabase implements VersionedGraphDatabase {

    private final List<Commit> commits = new ArrayList<>();
    private final Map<CommitId, InMemoryGraphSnapshot> snapshots = new HashMap<>();

    private CommitId headCommitId;
    private MutableGraphState headState;

    public InMemoryVersionedDatabase() {
        MutableGraphState initialState = new MutableGraphState();
        List<Change> bootstrapChanges = new ArrayList<>();
        BootstrapKernelInitializer.initialize(initialState, bootstrapChanges);

        Commit bootstrapCommit = new Commit(
                CommitId.BOOTSTRAP,
                Optional.empty(),
                Actor.system(),
                Reason.of("Bootstrap kernel initialization"),
                Optional.empty(),
                Instant.now(),
                bootstrapChanges
        );
        commits.add(bootstrapCommit);
        snapshots.put(CommitId.BOOTSTRAP, new InMemoryGraphSnapshot(initialState.copy()));
        this.headCommitId = CommitId.BOOTSTRAP;
        this.headState = initialState;
    }

    @Override
    public CommitId headCommitId() {
        return headCommitId;
    }

    @Override
    public GraphReader currentSnapshot() {
        return snapshots.get(headCommitId);
    }

    @Override
    public GraphReader asOf(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        InMemoryGraphSnapshot snapshot = snapshots.get(commitId);
        if (snapshot == null) {
            throw new CommitNotFoundException("Commit not found: " + commitId);
        }
        return snapshot;
    }

    @Override
    public Commit getCommit(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        long idx = commitId.value();
        if (idx < 0 || idx >= commits.size()) {
            throw new CommitNotFoundException("Commit not found: " + commitId);
        }
        return commits.get((int) idx);
    }

    @Override
    public List<Commit> commitHistory() {
        return List.copyOf(commits);
    }

    @Override
    public List<Commit> historyOf(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        List<Commit> result = new ArrayList<>();
        for (Commit commit : commits) {
            if (commit.touchesEntity(entityId)) {
                result.add(commit);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public GraphDiff diff(CommitId fromCommit, CommitId toCommit) {
        Objects.requireNonNull(fromCommit, "fromCommit must not be null");
        Objects.requireNonNull(toCommit, "toCommit must not be null");
        return GraphDiffCalculator.compute(fromCommit, asOf(fromCommit), toCommit, asOf(toCommit));
    }

    @Override
    public GraphTransaction beginTransaction(Actor actor, Reason reason, String correlationId) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        return new InMemoryTransaction(this, headCommitId, headState.copy(), actor, reason, correlationId);
    }

    @Override
    public CommitId revert(CommitId targetCommitId, Actor actor, Reason reason, String correlationId) {
        Objects.requireNonNull(targetCommitId, "targetCommitId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        if (targetCommitId.isBootstrap()) {
            throw new RevertConflictException("Cannot revert bootstrap commit " + targetCommitId);
        }
        Commit target = getCommit(targetCommitId);

        MutableGraphState working = headState.copy();
        List<Change> counterChanges = new ArrayList<>(target.changes().size());

        List<Change> targetChanges = target.changes();
        for (int i = targetChanges.size() - 1; i >= 0; i--) {
            Change change = targetChanges.get(i);
            applyInverseChange(working, change, counterChanges);
        }
        working.rebuildDerivedIndexes();

        return appendCommit(actor, reason, Optional.ofNullable(correlationId), working, counterChanges);
    }

    @Override
    public GraphReader replayAsOf(CommitId commitId) {
        Objects.requireNonNull(commitId, "commitId must not be null");
        getCommit(commitId); // validate existence

        MutableGraphState replayed = new MutableGraphState();
        for (int i = 0; i <= commitId.value(); i++) {
            Commit commit = commits.get(i);
            for (Change change : commit.changes()) {
                replayed.applyReplayChange(change);
            }
        }
        replayed.rebuildDerivedIndexes();
        return new InMemoryGraphSnapshot(replayed);
    }

    CommitId publishTransactionCommit(
            CommitId expectedBaseCommitId,
            Actor actor,
            Reason reason,
            Optional<String> correlationId,
            MutableGraphState committedState,
            List<Change> changes
    ) {
        if (!expectedBaseCommitId.equals(headCommitId)) {
            throw new TransactionStateException(
                    "Concurrent commit conflict: transaction based on " + expectedBaseCommitId
                            + " but head is now " + headCommitId);
        }
        return appendCommit(actor, reason, correlationId, committedState, changes);
    }

    private CommitId appendCommit(
            Actor actor,
            Reason reason,
            Optional<String> correlationId,
            MutableGraphState nextState,
            List<Change> changes
    ) {
        CommitId nextId = headCommitId.next();
        Commit commit = new Commit(
                nextId,
                Optional.of(headCommitId),
                actor,
                reason,
                correlationId,
                Instant.now(),
                changes
        );
        commits.add(commit);
        snapshots.put(nextId, new InMemoryGraphSnapshot(nextState.copy()));
        this.headCommitId = nextId;
        this.headState = nextState;
        return nextId;
    }

    private void applyInverseChange(MutableGraphState working, Change change, List<Change> counterChanges) {
        switch (change) {
            case Change.EdgeConnected(Edge edge) -> {
                Edge current = working.edges.get(edge.id());
                if (current == null) {
                    throw new RevertConflictException("Cannot revert EdgeConnected " + edge.id() + ": edge no longer exists");
                }
                if (!working.findOutgoingEdges(edge.id()).isEmpty() || !working.findIncomingEdges(edge.id()).isEmpty()) {
                    throw new RevertConflictException(
                            "Cannot revert EdgeConnected " + edge.id() + ": dependent edges still reference it");
                }
                working.removeEdge(edge.id());
                counterChanges.add(new Change.EdgeDisconnected(current));
            }
            case Change.EdgeDisconnected(Edge edge) -> {
                if (working.edges.containsKey(edge.id())) {
                    throw new RevertConflictException("Cannot revert EdgeDisconnected " + edge.id() + ": edge already exists");
                }
                EntityNode src = working.entities.get(edge.sourceId());
                EntityNode pred = working.entities.get(edge.predicateId());
                EntityNode tgt = working.entities.get(edge.targetId());
                if (src == null || pred == null || tgt == null) {
                    throw new RevertConflictException(
                            "Cannot revert EdgeDisconnected " + edge.id() + ": endpoint or predicate no longer exists");
                }
                try {
                    GraphSchemaValidator.validateConnection(working, src, pred, tgt);
                } catch (SchemaViolationException ex) {
                    throw new RevertConflictException(
                            "Cannot revert EdgeDisconnected " + edge.id() + ": " + ex.getMessage());
                }
                working.putEdge(edge);
                counterChanges.add(new Change.EdgeConnected(edge));
            }
            case Change.ValueUpdated(EntityId entityId, Value previousValue, Value newValue) -> {
                EntityNode current = working.entities.get(entityId);
                if (current == null || current.kind() != EntityKind.VALUE) {
                    throw new RevertConflictException("Cannot revert ValueUpdated on " + entityId + ": entity missing or not VALUE");
                }
                Value liveValue = current.payload().orElseThrow();
                if (!liveValue.equals(newValue)) {
                    throw new RevertConflictException(
                            "Cannot revert ValueUpdated on " + entityId + ": current value " + liveValue
                                    + " differs from expected " + newValue);
                }
                if (working.isUniqueValueType(current.typeId()) && !previousValue.equals(newValue)) {
                    Map<Value, EntityId> idx = working.uniqueValuesByType.get(current.typeId());
                    if (idx != null) {
                        EntityId owner = idx.get(previousValue);
                        if (owner != null && !owner.equals(entityId)) {
                            throw new RevertConflictException(
                                    "Cannot revert ValueUpdated on " + entityId + ": previous unique value "
                                            + previousValue + " is now owned by " + owner);
                        }
                        idx.remove(newValue);
                        idx.put(previousValue, entityId);
                    }
                }
                if (BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE.equals(current.typeId())
                        && previousValue instanceof Value.StringValue(String oldName)) {
                    List<Edge> nameHolders = working.findIncomingEdges(entityId, BootstrapIds.HAS_NAME);
                    if (!nameHolders.isEmpty()) {
                        EntityNode holder = working.entities.get(nameHolders.getFirst().sourceId());
                        boolean isPredicate = holder != null && BootstrapIds.META_PREDICATE_TYPE.equals(holder.typeId());
                        Map<String, EntityId> nameMap = isPredicate ? working.predicateByName : working.typeByName;
                        EntityId existingOwner = nameMap.get(oldName);
                        if (existingOwner != null && !existingOwner.equals(nameHolders.getFirst().sourceId())) {
                            throw new RevertConflictException(
                                    "Cannot revert schema rename to '" + oldName + "': name is already taken by " + existingOwner);
                        }
                    }
                }
                working.putEntity(new EntityNode(entityId, current.typeId(), EntityKind.VALUE, Optional.of(previousValue)));
                counterChanges.add(new Change.ValueUpdated(entityId, newValue, previousValue));
            }
            case Change.EntityCreated(EntityNode node) -> {
                EntityNode current = working.entities.get(node.id());
                if (current == null) {
                    throw new RevertConflictException("Cannot revert EntityCreated " + node.id() + ": entity no longer exists");
                }
                for (Edge edge : working.edges.values()) {
                    if (edge.sourceId().equals(node.id())
                            || edge.predicateId().equals(node.id())
                            || edge.targetId().equals(node.id())) {
                        throw new RevertConflictException(
                                "Cannot revert EntityCreated " + node.id() + ": edge " + edge.id() + " still references it");
                    }
                }
                for (EntityNode other : working.entities.values()) {
                    if (!other.id().equals(node.id()) && other.typeId().equals(node.id())) {
                        throw new RevertConflictException(
                                "Cannot revert EntityCreated " + node.id() + ": entity " + other.id() + " still uses it as type");
                    }
                }
                working.entities.remove(node.id());
                counterChanges.add(new Change.EntityRemoved(current));
            }
            case Change.EntityRemoved(EntityNode node) -> {
                if (working.entities.containsKey(node.id())) {
                    throw new RevertConflictException("Cannot revert EntityRemoved " + node.id() + ": entity already exists");
                }
                if (!node.typeId().isBootstrap() && !working.entities.containsKey(node.typeId())) {
                    throw new RevertConflictException(
                            "Cannot revert EntityRemoved " + node.id() + ": type " + node.typeId() + " no longer exists");
                }
                if (node.kind() == EntityKind.VALUE && working.isUniqueValueType(node.typeId())) {
                    Map<Value, EntityId> idx = working.uniqueValuesByType.get(node.typeId());
                    if (idx != null && idx.containsKey(node.payload().orElseThrow())) {
                        throw new RevertConflictException(
                                "Cannot revert EntityRemoved " + node.id() + ": unique value already taken");
                    }
                }
                working.putEntity(node);
                counterChanges.add(new Change.EntityCreated(node));
            }
        }
    }
}
