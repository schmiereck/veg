package io.veg.engine;

import io.veg.model.EntityId;
import io.veg.model.PredicateConstraints;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;

/**
 * Mutable session against a VEG graph state.
 *
 * <p>Every schema definition and data mutation validates invariants atomically before updating visible state.
 * In Stage 3+, transactional workspaces and commits will wrap or implement this contract.</p>
 */
public interface GraphSession extends GraphReader {

    EntityId defineObjectType(String name);

    default EntityId defineValueType(String name, ValueType scalarType) {
        return defineValueType(name, scalarType, false);
    }

    EntityId defineValueType(String name, ValueType scalarType, boolean uniqueValues);

    default EntityId definePredicate(String name, EntityId domainTypeId, EntityId rangeTypeId) {
        return definePredicate(name, domainTypeId, rangeTypeId, PredicateConstraints.unconstrained());
    }

    EntityId definePredicate(String name, EntityId domainTypeId, EntityId rangeTypeId, PredicateConstraints constraints);

    EntityId createObject(EntityId objectTypeId);

    default EntityId createValue(EntityId valueTypeId, Value value) {
        return createValue(valueTypeId, value, ValueMutability.IMMUTABLE);
    }

    EntityId createValue(EntityId valueTypeId, Value value, ValueMutability mutability);

    void updateValue(EntityId valueEntityId, Value newValue);

    EntityId connect(EntityId sourceId, EntityId predicateId, EntityId targetId);

    void disconnect(EntityId edgeId);
}
