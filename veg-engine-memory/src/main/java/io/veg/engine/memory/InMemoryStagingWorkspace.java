package io.veg.engine.memory;

import io.veg.engine.EntityNotFoundException;
import io.veg.engine.MissingDependencyException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.StagingWorkspace;
import io.veg.engine.TransactionStateException;
import io.veg.model.Actor;
import io.veg.model.Change;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.PredicateConstraints;
import io.veg.model.Reason;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Git-style selective staging workspace on a target branch (V-06).
 */
final class InMemoryStagingWorkspace extends AbstractGraphReader implements StagingWorkspace {

    private record GroupedChange(EntityId groupId, Change change) {
    }

    private final InMemoryVersionedDatabase database;
    private final String branchName;
    private CommitId baseCommitId;

    private final List<Change> rawRecorder = new ArrayList<>();
    private final List<GroupedChange> trackedChanges = new ArrayList<>();
    private final Set<EntityId> stagedGroupIds = new LinkedHashSet<>();
    private final InMemoryGraphEngine delegate;

    InMemoryStagingWorkspace(
            InMemoryVersionedDatabase database,
            String branchName,
            CommitId baseCommitId,
            MutableGraphState baseStateCopy
    ) {
        this.database = database;
        this.branchName = branchName;
        this.baseCommitId = baseCommitId;
        this.delegate = new InMemoryGraphEngine(baseStateCopy, rawRecorder);
    }

    @Override
    protected MutableGraphState state() {
        return delegate.state();
    }

    @Override
    public String branchName() {
        return branchName;
    }

    @Override
    public CommitId baseCommitId() {
        return baseCommitId;
    }

    @Override
    public void stageEntity(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        boolean existsInPending = trackedChanges.stream().anyMatch(gc -> gc.groupId().equals(entityId));
        if (!existsInPending) {
            throw new EntityNotFoundException("No pending workspace changes for entity: " + entityId);
        }
        stagedGroupIds.add(entityId);
    }

    @Override
    public void unstageEntity(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        stagedGroupIds.remove(entityId);
    }

    @Override
    public void stageAll() {
        for (GroupedChange gc : trackedChanges) {
            stagedGroupIds.add(gc.groupId());
        }
    }

    @Override
    public void unstageAll() {
        stagedGroupIds.clear();
    }

    @Override
    public Set<EntityId> stagedEntityIds() {
        return Set.copyOf(stagedGroupIds);
    }

    @Override
    public List<Change> stagedChanges() {
        return trackedChanges.stream()
                .filter(gc -> stagedGroupIds.contains(gc.groupId()))
                .map(GroupedChange::change)
                .toList();
    }

    @Override
    public List<Change> unstagedChanges() {
        return trackedChanges.stream()
                .filter(gc -> !stagedGroupIds.contains(gc.groupId()))
                .map(GroupedChange::change)
                .toList();
    }

    @Override
    public CommitId commitStaged(Actor actor, Reason reason, String correlationId) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        CommitId currentBranchHead = database.branchHead(branchName);
        if (!baseCommitId.equals(currentBranchHead)) {
            throw new TransactionStateException(
                    "Branch '" + branchName + "' moved from " + baseCommitId + " to " + currentBranchHead);
        }

        List<Change> toCommit = stagedChanges();
        MutableGraphState workingBase = database.stateCopyForBranch(branchName);
        applyAndValidateStaged(workingBase, toCommit);
        workingBase.rebuildDerivedIndexes();

        CommitId newCommitId = database.publishBranchCommit(
                branchName,
                baseCommitId,
                DataCategory.REFERENCE,
                actor,
                reason,
                Optional.ofNullable(correlationId),
                workingBase,
                toCommit
        );

        trackedChanges.removeIf(gc -> stagedGroupIds.contains(gc.groupId()));
        stagedGroupIds.clear();
        this.baseCommitId = newCommitId;
        return newCommitId;
    }

    private static void applyAndValidateStaged(MutableGraphState workingBase, List<Change> staged) {
        for (Change change : staged) {
            switch (change) {
                case Change.EntityCreated(EntityNode node) -> {
                    if (!node.typeId().isBootstrap() && !workingBase.entities.containsKey(node.typeId())) {
                        throw new MissingDependencyException(
                                "Staged entity " + node.id() + " depends on unstaged type " + node.typeId());
                    }
                    workingBase.putEntity(node);
                }
                case Change.EdgeConnected(Edge edge) -> {
                    EntityNode src = workingBase.entities.get(edge.sourceId());
                    EntityNode pred = workingBase.entities.get(edge.predicateId());
                    EntityNode tgt = workingBase.entities.get(edge.targetId());
                    if (src == null || pred == null || tgt == null) {
                        throw new MissingDependencyException(
                                "Staged edge " + edge.id() + " depends on unstaged endpoint or predicate");
                    }
                    try {
                        GraphSchemaValidator.validateConnection(workingBase, src, pred, tgt);
                    } catch (SchemaViolationException ex) {
                        throw new MissingDependencyException("Staged edge " + edge.id() + " invalid: " + ex.getMessage());
                    }
                    workingBase.putEdge(edge);
                }
                case Change.ValueUpdated(EntityId entityId, _, Value newValue) -> {
                    if (!workingBase.entities.containsKey(entityId)) {
                        throw new MissingDependencyException(
                                "Staged value update on " + entityId + " depends on unstaged entity");
                    }
                    workingBase.applyReplayChange(change);
                }
                case Change.EdgeDisconnected(Edge edge) -> {
                    if (!workingBase.edges.containsKey(edge.id())) {
                        throw new MissingDependencyException(
                                "Staged disconnect of " + edge.id() + " references missing edge");
                    }
                    if (!workingBase.findOutgoingEdges(edge.id()).isEmpty()
                            || !workingBase.findIncomingEdges(edge.id()).isEmpty()) {
                        throw new MissingDependencyException(
                                "Cannot stage disconnect of " + edge.id() + " while dependent edge remains unstaged");
                    }
                    workingBase.removeEdge(edge.id());
                }
                case Change.EntityRemoved(EntityNode node) -> {
                    if (!workingBase.entities.containsKey(node.id())) {
                        throw new MissingDependencyException(
                                "Staged removal of " + node.id() + " references missing entity");
                    }
                    workingBase.applyReplayChange(change);
                }
            }
        }
    }

    private void recordGroup(EntityId groupId, int fromRawIndex) {
        for (int i = fromRawIndex; i < rawRecorder.size(); i++) {
            trackedChanges.add(new GroupedChange(groupId, rawRecorder.get(i)));
        }
    }

    // --- GraphSession mutation overrides tagging each Change with its logical groupId ---

    @Override
    public EntityId defineObjectType(String name) {
        int start = rawRecorder.size();
        EntityId id = delegate.defineObjectType(name);
        recordGroup(id, start);
        return id;
    }

    @Override
    public EntityId defineValueType(String name, ValueType scalarType, boolean uniqueValues) {
        int start = rawRecorder.size();
        EntityId id = delegate.defineValueType(name, scalarType, uniqueValues);
        recordGroup(id, start);
        return id;
    }

    @Override
    public EntityId definePredicate(String name, EntityId domainTypeId, EntityId rangeTypeId, PredicateConstraints constraints) {
        int start = rawRecorder.size();
        EntityId id = delegate.definePredicate(name, domainTypeId, rangeTypeId, constraints);
        recordGroup(id, start);
        return id;
    }

    @Override
    public EntityId createObject(EntityId objectTypeId) {
        int start = rawRecorder.size();
        EntityId id = delegate.createObject(objectTypeId);
        recordGroup(id, start);
        return id;
    }

    @Override
    public EntityId createValue(EntityId valueTypeId, Value value, ValueMutability mutability) {
        int start = rawRecorder.size();
        EntityId id = delegate.createValue(valueTypeId, value, mutability);
        recordGroup(id, start);
        return id;
    }

    @Override
    public void updateValue(EntityId valueEntityId, Value newValue) {
        int start = rawRecorder.size();
        delegate.updateValue(valueEntityId, newValue);
        recordGroup(valueEntityId, start);
    }

    @Override
    public EntityId connect(EntityId sourceId, EntityId predicateId, EntityId targetId) {
        int start = rawRecorder.size();
        EntityId edgeId = delegate.connect(sourceId, predicateId, targetId);
        recordGroup(edgeId, start);
        return edgeId;
    }

    @Override
    public void disconnect(EntityId edgeId) {
        int start = rawRecorder.size();
        delegate.disconnect(edgeId);
        recordGroup(edgeId, start);
    }

    @Override
    public void renameSchemaElement(EntityId schemaEntityId, String newName) {
        int start = rawRecorder.size();
        delegate.renameSchemaElement(schemaEntityId, newName);
        recordGroup(schemaEntityId, start);
    }

    @Override
    public void updatePredicateConstraints(EntityId predicateId, PredicateConstraints newConstraints) {
        int start = rawRecorder.size();
        delegate.updatePredicateConstraints(predicateId, newConstraints);
        recordGroup(predicateId, start);
    }

    @Override
    public void enableUniqueValues(EntityId valueTypeId) {
        int start = rawRecorder.size();
        delegate.enableUniqueValues(valueTypeId);
        recordGroup(valueTypeId, start);
    }
}
