package io.veg.engine.memory;

import io.veg.model.BootstrapIds;
import io.veg.model.Change;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.Value;

import java.util.List;
import java.util.Optional;

/**
 * Populates the minimal hardcoded {@link BootstrapIds} kernel into a {@link MutableGraphState}
 * and optionally emits the exact {@link Change} sequence for initial commit {@code c0}.
 */
final class BootstrapKernelInitializer {

    private final MutableGraphState state;
    private final List<Change> recorder;

    private BootstrapKernelInitializer(MutableGraphState state, List<Change> recorder) {
        this.state = state;
        this.recorder = recorder;
    }

    static void initialize(MutableGraphState state, List<Change> recorder) {
        new BootstrapKernelInitializer(state, recorder).run();
    }

    private void run() {
        registerType(BootstrapIds.META_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.MetaType");
        registerType(BootstrapIds.META_OBJECT_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.ObjectType");
        registerType(BootstrapIds.META_VALUE_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.ValueType");
        registerType(BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.PredicateType");
        registerType(BootstrapIds.META_EDGE_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.EdgeType");
        registerType(BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, BootstrapIds.META_VALUE_TYPE, EntityKind.META_TYPE, "veg.meta.Identifier");
        registerType(BootstrapIds.SYSTEM_INT_VALUE_TYPE, BootstrapIds.META_VALUE_TYPE, EntityKind.META_TYPE, "veg.meta.Int");
        registerType(BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE, BootstrapIds.META_VALUE_TYPE, EntityKind.META_TYPE, "veg.meta.Boolean");

        registerType(BootstrapIds.SCALAR_STRING, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.String");
        registerType(BootstrapIds.SCALAR_INT, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.Int");
        registerType(BootstrapIds.SCALAR_DECIMAL, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.Decimal");
        registerType(BootstrapIds.SCALAR_BOOLEAN, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.Boolean");
        registerType(BootstrapIds.SCALAR_BINARY, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.Binary");
        registerType(BootstrapIds.SCALAR_TIMESTAMP, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.Timestamp");

        registerType(BootstrapIds.ANY_ENTITY, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.wildcard.AnyEntity");
        registerType(BootstrapIds.ANY_OBJECT, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.wildcard.AnyObject");
        registerType(BootstrapIds.ANY_VALUE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.wildcard.AnyValue");
        registerType(BootstrapIds.ANY_EDGE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.wildcard.AnyEdge");

        registerType(BootstrapIds.MUTABILITY_IMMUTABLE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.mutability.Immutable");
        registerType(BootstrapIds.MUTABILITY_SHARED_MUTABLE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.mutability.SharedMutable");

        registerType(BootstrapIds.META_PRINCIPAL_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.PrincipalType");
        registerType(BootstrapIds.META_ACL_ENTRY_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.AclEntryType");
        registerType(BootstrapIds.META_PROPAGATION_RULE_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.PropagationRuleType");

        registerPredicate(BootstrapIds.HAS_NAME, "veg.meta.hasName");
        registerPredicate(BootstrapIds.HAS_SCALAR_TYPE, "veg.meta.hasScalarType");
        registerPredicate(BootstrapIds.HAS_DOMAIN, "veg.meta.hasDomain");
        registerPredicate(BootstrapIds.HAS_RANGE, "veg.meta.hasRange");
        registerPredicate(BootstrapIds.HAS_MAX_OUTGOING, "veg.meta.hasMaxOutgoing");
        registerPredicate(BootstrapIds.HAS_MAX_INCOMING, "veg.meta.hasMaxIncoming");
        registerPredicate(BootstrapIds.HAS_UNIQUE_TRIPLE, "veg.meta.hasUniqueTriple");
        registerPredicate(BootstrapIds.HAS_UNIQUE_VALUES, "veg.meta.hasUniqueValues");
        registerPredicate(BootstrapIds.HAS_MUTABILITY, "veg.meta.hasMutability");

        registerPredicate(BootstrapIds.MEMBER_OF_GROUP, "veg.acl.memberOfGroup");
        registerPredicate(BootstrapIds.ACL_PRINCIPAL, "veg.acl.principal");
        registerPredicate(BootstrapIds.ACL_TARGET, "veg.acl.target");
        registerPredicate(BootstrapIds.ACL_EFFECT, "veg.acl.effect");
        registerPredicate(BootstrapIds.ACL_PERMISSION, "veg.acl.permission");
        registerPredicate(BootstrapIds.PROPAGATION_PREDICATE, "veg.acl.propagationPredicate");
        registerPredicate(BootstrapIds.PROPAGATION_DIRECTION, "veg.acl.propagationDirection");
        registerPredicate(BootstrapIds.PROPAGATION_PERMISSION, "veg.acl.propagationPermission");
        registerPredicate(BootstrapIds.HAS_INVERSE_PREDICATE, "veg.meta.hasInversePredicate");
        registerPredicate(BootstrapIds.HAS_SUPER_PREDICATE, "veg.meta.hasSuperPredicate");

        addEdge(state.allocateBootstrapAuxId(), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.SCALAR_STRING);
        addEdge(state.allocateBootstrapAuxId(), BootstrapIds.SYSTEM_INT_VALUE_TYPE, BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.SCALAR_INT);
        addEdge(state.allocateBootstrapAuxId(), BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE, BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.SCALAR_BOOLEAN);

        attachFlag(BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, BootstrapIds.HAS_UNIQUE_VALUES, false);
        attachFlag(BootstrapIds.SYSTEM_INT_VALUE_TYPE, BootstrapIds.HAS_UNIQUE_VALUES, false);
        attachFlag(BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE, BootstrapIds.HAS_UNIQUE_VALUES, false);

        attachName(BootstrapIds.META_TYPE, "veg.meta.MetaType");
        attachName(BootstrapIds.META_OBJECT_TYPE, "veg.meta.ObjectType");
        attachName(BootstrapIds.META_VALUE_TYPE, "veg.meta.ValueType");
        attachName(BootstrapIds.META_PREDICATE_TYPE, "veg.meta.PredicateType");
        attachName(BootstrapIds.META_EDGE_TYPE, "veg.meta.EdgeType");
        attachName(BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, "veg.meta.Identifier");
        attachName(BootstrapIds.SYSTEM_INT_VALUE_TYPE, "veg.meta.Int");
        attachName(BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE, "veg.meta.Boolean");

        attachName(BootstrapIds.SCALAR_STRING, "veg.scalar.String");
        attachName(BootstrapIds.SCALAR_INT, "veg.scalar.Int");
        attachName(BootstrapIds.SCALAR_DECIMAL, "veg.scalar.Decimal");
        attachName(BootstrapIds.SCALAR_BOOLEAN, "veg.scalar.Boolean");
        attachName(BootstrapIds.SCALAR_BINARY, "veg.scalar.Binary");
        attachName(BootstrapIds.SCALAR_TIMESTAMP, "veg.scalar.Timestamp");

        attachName(BootstrapIds.ANY_ENTITY, "veg.wildcard.AnyEntity");
        attachName(BootstrapIds.ANY_OBJECT, "veg.wildcard.AnyObject");
        attachName(BootstrapIds.ANY_VALUE, "veg.wildcard.AnyValue");
        attachName(BootstrapIds.ANY_EDGE, "veg.wildcard.AnyEdge");

        attachName(BootstrapIds.MUTABILITY_IMMUTABLE, "veg.mutability.Immutable");
        attachName(BootstrapIds.MUTABILITY_SHARED_MUTABLE, "veg.mutability.SharedMutable");

        attachName(BootstrapIds.META_PRINCIPAL_TYPE, "veg.meta.PrincipalType");
        attachName(BootstrapIds.META_ACL_ENTRY_TYPE, "veg.meta.AclEntryType");
        attachName(BootstrapIds.META_PROPAGATION_RULE_TYPE, "veg.meta.PropagationRuleType");

        attachName(BootstrapIds.HAS_NAME, "veg.meta.hasName");
        attachName(BootstrapIds.HAS_SCALAR_TYPE, "veg.meta.hasScalarType");
        attachName(BootstrapIds.HAS_DOMAIN, "veg.meta.hasDomain");
        attachName(BootstrapIds.HAS_RANGE, "veg.meta.hasRange");
        attachName(BootstrapIds.HAS_MAX_OUTGOING, "veg.meta.hasMaxOutgoing");
        attachName(BootstrapIds.HAS_MAX_INCOMING, "veg.meta.hasMaxIncoming");
        attachName(BootstrapIds.HAS_UNIQUE_TRIPLE, "veg.meta.hasUniqueTriple");
        attachName(BootstrapIds.HAS_UNIQUE_VALUES, "veg.meta.hasUniqueValues");
        attachName(BootstrapIds.HAS_MUTABILITY, "veg.meta.hasMutability");

        attachName(BootstrapIds.MEMBER_OF_GROUP, "veg.acl.memberOfGroup");
        attachName(BootstrapIds.ACL_PRINCIPAL, "veg.acl.principal");
        attachName(BootstrapIds.ACL_TARGET, "veg.acl.target");
        attachName(BootstrapIds.ACL_EFFECT, "veg.acl.effect");
        attachName(BootstrapIds.ACL_PERMISSION, "veg.acl.permission");
        attachName(BootstrapIds.PROPAGATION_PREDICATE, "veg.acl.propagationPredicate");
        attachName(BootstrapIds.PROPAGATION_DIRECTION, "veg.acl.propagationDirection");
        attachName(BootstrapIds.PROPAGATION_PERMISSION, "veg.acl.propagationPermission");
        attachName(BootstrapIds.HAS_INVERSE_PREDICATE, "veg.meta.hasInversePredicate");
        attachName(BootstrapIds.HAS_SUPER_PREDICATE, "veg.meta.hasSuperPredicate");

        attachPredicateMeta(BootstrapIds.HAS_NAME, BootstrapIds.ANY_ENTITY, BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        attachPredicateMeta(BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.META_VALUE_TYPE, BootstrapIds.META_TYPE);
        attachPredicateMeta(BootstrapIds.HAS_DOMAIN, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.ANY_ENTITY);
        attachPredicateMeta(BootstrapIds.HAS_RANGE, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.ANY_ENTITY);
        attachPredicateMeta(BootstrapIds.HAS_MAX_OUTGOING, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.SYSTEM_INT_VALUE_TYPE);
        attachPredicateMeta(BootstrapIds.HAS_MAX_INCOMING, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.SYSTEM_INT_VALUE_TYPE);
        attachPredicateMeta(BootstrapIds.HAS_UNIQUE_TRIPLE, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE);
        attachPredicateMeta(BootstrapIds.HAS_UNIQUE_VALUES, BootstrapIds.META_VALUE_TYPE, BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE);
        attachPredicateMeta(BootstrapIds.HAS_MUTABILITY, BootstrapIds.ANY_VALUE, BootstrapIds.META_TYPE);

        attachPredicateMeta(BootstrapIds.MEMBER_OF_GROUP, BootstrapIds.META_PRINCIPAL_TYPE, BootstrapIds.META_PRINCIPAL_TYPE);
        attachPredicateMeta(BootstrapIds.ACL_PRINCIPAL, BootstrapIds.META_ACL_ENTRY_TYPE, BootstrapIds.META_PRINCIPAL_TYPE);
        attachPredicateMeta(BootstrapIds.ACL_TARGET, BootstrapIds.META_ACL_ENTRY_TYPE, BootstrapIds.ANY_ENTITY);
        attachPredicateMeta(BootstrapIds.ACL_EFFECT, BootstrapIds.META_ACL_ENTRY_TYPE, BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        attachPredicateMeta(BootstrapIds.ACL_PERMISSION, BootstrapIds.META_ACL_ENTRY_TYPE, BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        attachPredicateMeta(BootstrapIds.PROPAGATION_PREDICATE, BootstrapIds.META_PROPAGATION_RULE_TYPE, BootstrapIds.META_PREDICATE_TYPE);
        attachPredicateMeta(BootstrapIds.PROPAGATION_DIRECTION, BootstrapIds.META_PROPAGATION_RULE_TYPE, BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        attachPredicateMeta(BootstrapIds.PROPAGATION_PERMISSION, BootstrapIds.META_PROPAGATION_RULE_TYPE, BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        attachPredicateMeta(BootstrapIds.HAS_INVERSE_PREDICATE, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.META_PREDICATE_TYPE);
        attachPredicateMeta(BootstrapIds.HAS_SUPER_PREDICATE, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.META_PREDICATE_TYPE);
    }

    private void registerType(EntityId id, EntityId metaTypeId, EntityKind kind, String name) {
        addEntity(new EntityNode(id, metaTypeId, kind, Optional.empty()));
        state.typeByName.put(name, id);
    }

    private void registerPredicate(EntityId id, String name) {
        addEntity(new EntityNode(id, BootstrapIds.META_PREDICATE_TYPE, EntityKind.META_TYPE, Optional.empty()));
        state.predicateByName.put(name, id);
    }

    private void attachName(EntityId targetSchemaId, String name) {
        EntityId nameValueId = addSystemValue(BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, Value.of(name));
        addEdge(state.allocateBootstrapAuxId(), targetSchemaId, BootstrapIds.HAS_NAME, nameValueId);
    }

    private void attachFlag(EntityId targetSchemaId, EntityId flagPredicateId, boolean value) {
        EntityId flagValueId = addSystemValue(BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE, Value.of(value));
        addEdge(state.allocateBootstrapAuxId(), targetSchemaId, flagPredicateId, flagValueId);
    }

    private void attachPredicateMeta(EntityId predicateId, EntityId domainTypeId, EntityId rangeTypeId) {
        addEdge(state.allocateBootstrapAuxId(), predicateId, BootstrapIds.HAS_DOMAIN, domainTypeId);
        addEdge(state.allocateBootstrapAuxId(), predicateId, BootstrapIds.HAS_RANGE, rangeTypeId);
        attachFlag(predicateId, BootstrapIds.HAS_UNIQUE_TRIPLE, false);
    }

    private EntityId addSystemValue(EntityId systemValueTypeId, Value value) {
        EntityId id = state.allocateBootstrapAuxId();
        addEntity(new EntityNode(id, systemValueTypeId, EntityKind.VALUE, Optional.of(value)));
        addEdge(state.allocateBootstrapAuxId(), id, BootstrapIds.HAS_MUTABILITY, BootstrapIds.MUTABILITY_IMMUTABLE);
        return id;
    }

    private void addEntity(EntityNode node) {
        state.putEntity(node);
        if (recorder != null) {
            recorder.add(new Change.EntityCreated(node));
        }
    }

    private void addEdge(EntityId id, EntityId sourceId, EntityId predicateId, EntityId targetId) {
        Edge edge = new Edge(id, sourceId, predicateId, targetId);
        state.putEdge(edge);
        if (recorder != null) {
            recorder.add(new Change.EdgeConnected(edge));
        }
    }
}
