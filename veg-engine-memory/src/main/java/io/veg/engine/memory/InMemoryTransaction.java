package io.veg.engine.memory;

import io.veg.engine.GraphTransaction;
import io.veg.engine.TransactionStateException;
import io.veg.model.AccessDecision;
import io.veg.model.Actor;
import io.veg.model.Change;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.DerivedAssertion;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.Permission;
import io.veg.model.PredicateConstraints;
import io.veg.model.PropagationDirection;
import io.veg.model.Reason;
import io.veg.model.SavepointId;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Isolated branch-scoped read/write transaction workspace with multi-level savepoints (V-03, V-11).
 */
final class InMemoryTransaction implements GraphTransaction {

    private record SavepointEntry(SavepointId id, MutableGraphState stateSnapshot, int changeCount) {
    }

    private final InMemoryVersionedDatabase database;
    private final String branchName;
    private final CommitId baseCommitId;
    private final DataCategory dataCategory;
    private final Actor actor;
    private final Reason reason;
    private final Optional<String> correlationId;

    private final List<Change> pendingChanges = new ArrayList<>();
    private final InMemoryGraphEngine delegate;
    private final List<SavepointEntry> savepoints = new ArrayList<>();

    private long nextSavepointSeq = 0L;
    private boolean open = true;

    InMemoryTransaction(
            InMemoryVersionedDatabase database,
            String branchName,
            CommitId baseCommitId,
            MutableGraphState baseStateCopy,
            DataCategory dataCategory,
            Actor actor,
            Reason reason,
            String correlationId
    ) {
        this.database = database;
        this.branchName = branchName;
        this.baseCommitId = baseCommitId;
        this.dataCategory = dataCategory;
        this.actor = actor;
        this.reason = reason;
        this.correlationId = Optional.ofNullable(correlationId);
        this.delegate = new InMemoryGraphEngine(baseStateCopy, pendingChanges);
    }

    @Override
    public Actor actor() {
        return actor;
    }

    @Override
    public Reason reason() {
        return reason;
    }

    @Override
    public Optional<String> correlationId() {
        return correlationId;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public SavepointId createSavepoint(String name) {
        ensureOpen();
        SavepointId id = new SavepointId(name, nextSavepointSeq++);
        savepoints.add(new SavepointEntry(id, delegate.state().copy(), pendingChanges.size()));
        return id;
    }

    @Override
    public void rollbackToSavepoint(SavepointId savepointId) {
        ensureOpen();
        Objects.requireNonNull(savepointId, "savepointId must not be null");

        int targetIndex = -1;
        for (int i = savepoints.size() - 1; i >= 0; i--) {
            if (savepoints.get(i).id().equals(savepointId)) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) {
            throw new TransactionStateException("Invalid or superseded savepoint: " + savepointId);
        }

        while (savepoints.size() - 1 > targetIndex) {
            savepoints.removeLast();
        }
        SavepointEntry target = savepoints.get(targetIndex);
        delegate.restoreState(target.stateSnapshot().copy());
        while (pendingChanges.size() > target.changeCount()) {
            pendingChanges.removeLast();
        }
    }

    @Override
    public CommitId commit() {
        ensureOpen();
        open = false;
        savepoints.clear();
        return database.publishBranchCommit(
                branchName, baseCommitId, dataCategory, actor, reason, correlationId, delegate.state(), pendingChanges);
    }

    @Override
    public void rollback() {
        ensureOpen();
        open = false;
        savepoints.clear();
        pendingChanges.clear();
    }

    @Override
    public void close() {
        if (open) {
            rollback();
        }
    }

    private void ensureOpen() {
        if (!open) {
            throw new TransactionStateException("Transaction is already closed");
        }
    }

    @Override public EntityId defineObjectType(String name) { ensureOpen(); return delegate.defineObjectType(name); }
    @Override public EntityId defineValueType(String name, ValueType scalarType, boolean uniqueValues) {
        ensureOpen();
        return delegate.defineValueType(name, scalarType, uniqueValues);
    }
    @Override public EntityId definePredicate(String name, EntityId domainTypeId, EntityId rangeTypeId, PredicateConstraints constraints) {
        ensureOpen();
        return delegate.definePredicate(name, domainTypeId, rangeTypeId, constraints);
    }
    @Override public EntityId createObject(EntityId objectTypeId) { ensureOpen(); return delegate.createObject(objectTypeId); }
    @Override public EntityId createValue(EntityId valueTypeId, Value value, ValueMutability mutability) {
        ensureOpen();
        return delegate.createValue(valueTypeId, value, mutability);
    }
    @Override public void updateValue(EntityId valueEntityId, Value newValue) { ensureOpen(); delegate.updateValue(valueEntityId, newValue); }
    @Override public EntityId connect(EntityId sourceId, EntityId predicateId, EntityId targetId) {
        ensureOpen();
        return delegate.connect(sourceId, predicateId, targetId);
    }
    @Override public void disconnect(EntityId edgeId) { ensureOpen(); delegate.disconnect(edgeId); }
    @Override public void renameSchemaElement(EntityId schemaEntityId, String newName) {
        ensureOpen();
        delegate.renameSchemaElement(schemaEntityId, newName);
    }
    @Override public void updatePredicateConstraints(EntityId predicateId, PredicateConstraints newConstraints) {
        ensureOpen();
        delegate.updatePredicateConstraints(predicateId, newConstraints);
    }
    @Override public void enableUniqueValues(EntityId valueTypeId) { ensureOpen(); delegate.enableUniqueValues(valueTypeId); }

    @Override public EntityId definePrincipal(String principalId) { ensureOpen(); return delegate.definePrincipal(principalId); }
    @Override public EntityId addGroupMember(EntityId memberPrincipalId, EntityId groupPrincipalId) {
        ensureOpen();
        return delegate.addGroupMember(memberPrincipalId, groupPrincipalId);
    }
    @Override public EntityId grantPermission(EntityId principalEntityId, Permission permission, EntityId targetId) {
        ensureOpen();
        return delegate.grantPermission(principalEntityId, permission, targetId);
    }
    @Override public EntityId denyPermission(EntityId principalEntityId, Permission permission, EntityId targetId) {
        ensureOpen();
        return delegate.denyPermission(principalEntityId, permission, targetId);
    }
    @Override public EntityId definePermissionPropagation(EntityId predicateId, PropagationDirection direction, Permission permission) {
        ensureOpen();
        return delegate.definePermissionPropagation(predicateId, direction, permission);
    }
    @Override public void defineInversePredicate(EntityId predicateId, EntityId inversePredicateId) {
        ensureOpen();
        delegate.defineInversePredicate(predicateId, inversePredicateId);
    }
    @Override public void defineSubPredicate(EntityId subPredicateId, EntityId superPredicateId) {
        ensureOpen();
        delegate.defineSubPredicate(subPredicateId, superPredicateId);
    }

    @Override public Optional<EntityNode> findEntity(EntityId id) { ensureOpen(); return delegate.findEntity(id); }
    @Override public Optional<Edge> findEdge(EntityId edgeId) { ensureOpen(); return delegate.findEdge(edgeId); }
    @Override public Optional<EntityId> findTypeByName(String name) { ensureOpen(); return delegate.findTypeByName(name); }
    @Override public Optional<EntityId> findPredicateByName(String name) { ensureOpen(); return delegate.findPredicateByName(name); }
    @Override public Optional<String> resolveName(EntityId schemaEntityId) { ensureOpen(); return delegate.resolveName(schemaEntityId); }
    @Override public Optional<ValueMutability> resolveValueMutability(EntityId valueEntityId) {
        ensureOpen();
        return delegate.resolveValueMutability(valueEntityId);
    }
    @Override public Optional<PredicateConstraints> resolvePredicateConstraints(EntityId predicateId) {
        ensureOpen();
        return delegate.resolvePredicateConstraints(predicateId);
    }
    @Override public boolean isUniqueValueType(EntityId valueTypeId) { ensureOpen(); return delegate.isUniqueValueType(valueTypeId); }
    @Override public List<Edge> findOutgoingEdges(EntityId sourceId) { ensureOpen(); return delegate.findOutgoingEdges(sourceId); }
    @Override public List<Edge> findOutgoingEdges(EntityId sourceId, EntityId predicateId) {
        ensureOpen();
        return delegate.findOutgoingEdges(sourceId, predicateId);
    }
    @Override public List<Edge> findIncomingEdges(EntityId targetId) { ensureOpen(); return delegate.findIncomingEdges(targetId); }
    @Override public List<Edge> findIncomingEdges(EntityId targetId, EntityId predicateId) {
        ensureOpen();
        return delegate.findIncomingEdges(targetId, predicateId);
    }
    @Override public long entityCount() { ensureOpen(); return delegate.entityCount(); }
    @Override public long edgeCount() { ensureOpen(); return delegate.edgeCount(); }
    @Override public List<EntityNode> allEntities() { ensureOpen(); return delegate.allEntities(); }
    @Override public List<Edge> allEdges() { ensureOpen(); return delegate.allEdges(); }
    @Override public Optional<EntityId> findPrincipalByName(String principalId) { ensureOpen(); return delegate.findPrincipalByName(principalId); }
    @Override public AccessDecision evaluateAccess(String principalId, Permission permission, EntityId targetId) {
        ensureOpen();
        return delegate.evaluateAccess(principalId, permission, targetId);
    }
    @Override public List<DerivedAssertion> findDerivedOutgoingAssertions(EntityId sourceId) {
        ensureOpen();
        return delegate.findDerivedOutgoingAssertions(sourceId);
    }
    @Override public List<DerivedAssertion> findDerivedOutgoingAssertions(EntityId sourceId, EntityId predicateId) {
        ensureOpen();
        return delegate.findDerivedOutgoingAssertions(sourceId, predicateId);
    }
    @Override public List<DerivedAssertion> findAllOutgoingAssertions(EntityId sourceId) {
        ensureOpen();
        return delegate.findAllOutgoingAssertions(sourceId);
    }
    @Override public List<DerivedAssertion> findAllOutgoingAssertions(EntityId sourceId, EntityId predicateId) {
        ensureOpen();
        return delegate.findAllOutgoingAssertions(sourceId, predicateId);
    }
}
