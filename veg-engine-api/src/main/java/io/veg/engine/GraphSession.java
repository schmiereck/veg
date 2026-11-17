package io.veg.engine;

import io.veg.model.EntityId;
import io.veg.model.Value;
import io.veg.model.ValueType;

/**
 * Mutable session against a VEG graph state.
 *
 * <p>Every schema definition and data mutation validates invariants atomically before updating visible state.
 * In Stage 3+, transactional workspaces and commits will wrap or implement this contract.</p>
 */
public interface GraphSession extends GraphReader {

    EntityId defineObjectType(String name);

    EntityId defineValueType(String name, ValueType scalarType);

    EntityId definePredicate(String name, EntityId domainTypeId, EntityId rangeTypeId);

    EntityId createObject(EntityId objectTypeId);

    EntityId createValue(EntityId valueTypeId, Value value);

    EntityId connect(EntityId sourceId, EntityId predicateId, EntityId targetId);

    void disconnect(EntityId edgeId);
}
