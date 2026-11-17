package io.veg.engine;

import io.veg.model.EntityId;
import io.veg.model.Permission;
import io.veg.model.PredicateConstraints;
import io.veg.model.PropagationDirection;
import io.veg.model.RdfFormat;
import io.veg.model.RdfImportResult;
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

    void renameSchemaElement(EntityId schemaEntityId, String newName);

    void updatePredicateConstraints(EntityId predicateId, PredicateConstraints newConstraints);

    void enableUniqueValues(EntityId valueTypeId);

    // --- Stage 5: Graph access control & semantic predicate rules (S-01..S-03, M-12) ---

    EntityId definePrincipal(String principalId);

    EntityId addGroupMember(EntityId memberPrincipalId, EntityId groupPrincipalId);

    EntityId grantPermission(EntityId principalEntityId, Permission permission, EntityId targetId);

    EntityId denyPermission(EntityId principalEntityId, Permission permission, EntityId targetId);

    EntityId definePermissionPropagation(EntityId predicateId, PropagationDirection direction, Permission permission);

    void defineInversePredicate(EntityId predicateId, EntityId inversePredicateId);

    void defineSubPredicate(EntityId subPredicateId, EntityId superPredicateId);

    // --- Stage 6: RDF/Turtle dataset ingestion ---

    default RdfImportResult importRdf(String content, RdfFormat format) {
        return RdfGraphImporter.importRdf(this, content, format);
    }

    default RdfImportResult importTurtle(String turtleContent) {
        return RdfGraphImporter.importRdf(this, turtleContent, RdfFormat.TURTLE);
    }
}
