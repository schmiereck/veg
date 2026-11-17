package io.veg.engine.memory;

import io.veg.engine.DuplicateSchemaElementException;
import io.veg.engine.EntityNotFoundException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.TypeMismatchException;
import io.veg.model.AccessEffect;
import io.veg.model.BootstrapIds;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.Permission;
import io.veg.model.PropagationDirection;
import io.veg.model.Value;

import java.util.Objects;
import java.util.Optional;

/**
 * Handles creation and validation of graph-stored principals, group memberships,
 * ACL entries, permission propagation rules, and semantic predicate links.
 */
final class AccessControlOperationsHandler {

    private final RecordedMutationSink mutations;

    AccessControlOperationsHandler(RecordedMutationSink mutations) {
        this.mutations = mutations;
    }

    EntityId definePrincipal(MutableGraphState state, String principalId) {
        String validId = GraphSchemaValidator.requireValidSchemaName(principalId);
        if (state.principalByName.containsKey(validId)) {
            throw new DuplicateSchemaElementException("Principal already exists: " + validId);
        }

        EntityId id = state.allocateUserId();
        mutations.createEntity(state, new EntityNode(id, BootstrapIds.META_PRINCIPAL_TYPE, EntityKind.OBJECT, Optional.empty()));
        mutations.attachMetadataFlag(state, id, BootstrapIds.HAS_NAME, Value.of(validId), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        state.principalByName.put(validId, id);
        return id;
    }

    EntityId addGroupMember(MutableGraphState state, EntityId memberPrincipalId, EntityId groupPrincipalId) {
        Objects.requireNonNull(memberPrincipalId, "memberPrincipalId must not be null");
        Objects.requireNonNull(groupPrincipalId, "groupPrincipalId must not be null");

        EntityNode memberNode = requirePrincipal(state, memberPrincipalId, "memberPrincipalId");
        EntityNode groupNode = requirePrincipal(state, groupPrincipalId, "groupPrincipalId");
        EntityNode predNode = GraphSchemaValidator.requireEntityExists(state, BootstrapIds.MEMBER_OF_GROUP, "MEMBER_OF_GROUP");
        GraphSchemaValidator.validateConnection(state, memberNode, predNode, groupNode);

        EntityId edgeId = state.allocateUserId();
        mutations.connectEdge(state, edgeId, memberPrincipalId, BootstrapIds.MEMBER_OF_GROUP, groupPrincipalId);
        return edgeId;
    }

    EntityId createAclEntry(
            MutableGraphState state,
            EntityId principalEntityId,
            AccessEffect effect,
            Permission permission,
            EntityId targetId
    ) {
        Objects.requireNonNull(principalEntityId, "principalEntityId must not be null");
        Objects.requireNonNull(effect, "effect must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");

        requirePrincipal(state, principalEntityId, "principalEntityId");
        if (!state.entities.containsKey(targetId) && !state.edges.containsKey(targetId)) {
            throw new EntityNotFoundException("Missing ACL target entity or edge: " + targetId);
        }

        EntityId aclId = state.allocateUserId();
        mutations.createEntity(state, new EntityNode(aclId, BootstrapIds.META_ACL_ENTRY_TYPE, EntityKind.OBJECT, Optional.empty()));
        mutations.connectEdge(state, state.allocateUserId(), aclId, BootstrapIds.ACL_PRINCIPAL, principalEntityId);
        mutations.connectEdge(state, state.allocateUserId(), aclId, BootstrapIds.ACL_TARGET, targetId);
        mutations.attachMetadataFlag(state, aclId, BootstrapIds.ACL_EFFECT, Value.of(effect.name()), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        mutations.attachMetadataFlag(state, aclId, BootstrapIds.ACL_PERMISSION, Value.of(permission.name()), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        return aclId;
    }

    EntityId definePermissionPropagation(
            MutableGraphState state,
            EntityId predicateId,
            PropagationDirection direction,
            Permission permission
    ) {
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(permission, "permission must not be null");

        requirePredicate(state, predicateId, "predicateId");

        EntityId ruleId = state.allocateUserId();
        mutations.createEntity(state, new EntityNode(ruleId, BootstrapIds.META_PROPAGATION_RULE_TYPE, EntityKind.OBJECT, Optional.empty()));
        mutations.connectEdge(state, state.allocateUserId(), ruleId, BootstrapIds.PROPAGATION_PREDICATE, predicateId);
        mutations.attachMetadataFlag(state, ruleId, BootstrapIds.PROPAGATION_DIRECTION, Value.of(direction.name()), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        mutations.attachMetadataFlag(state, ruleId, BootstrapIds.PROPAGATION_PERMISSION, Value.of(permission.name()), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        return ruleId;
    }

    void defineInversePredicate(MutableGraphState state, EntityId predicateId, EntityId inversePredicateId) {
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        Objects.requireNonNull(inversePredicateId, "inversePredicateId must not be null");

        EntityNode sourcePred = requirePredicate(state, predicateId, "predicateId");
        EntityNode targetPred = requirePredicate(state, inversePredicateId, "inversePredicateId");
        EntityNode metaPred = GraphSchemaValidator.requireEntityExists(state, BootstrapIds.HAS_INVERSE_PREDICATE, "HAS_INVERSE_PREDICATE");
        GraphSchemaValidator.validateConnection(state, sourcePred, metaPred, targetPred);

        boolean exists = state.findOutgoingEdges(predicateId, BootstrapIds.HAS_INVERSE_PREDICATE).stream()
                .anyMatch(e -> e.targetId().equals(inversePredicateId));
        if (!exists) {
            mutations.connectEdge(state, state.allocateUserId(), predicateId, BootstrapIds.HAS_INVERSE_PREDICATE, inversePredicateId);
        }
    }

    void defineSubPredicate(MutableGraphState state, EntityId subPredicateId, EntityId superPredicateId) {
        Objects.requireNonNull(subPredicateId, "subPredicateId must not be null");
        Objects.requireNonNull(superPredicateId, "superPredicateId must not be null");
        if (subPredicateId.equals(superPredicateId)) {
            throw new SchemaViolationException("Predicate cannot be a sub-predicate of itself: " + subPredicateId);
        }

        EntityNode subPred = requirePredicate(state, subPredicateId, "subPredicateId");
        EntityNode superPred = requirePredicate(state, superPredicateId, "superPredicateId");
        EntityNode metaPred = GraphSchemaValidator.requireEntityExists(state, BootstrapIds.HAS_SUPER_PREDICATE, "HAS_SUPER_PREDICATE");
        GraphSchemaValidator.validateConnection(state, subPred, metaPred, superPred);

        boolean exists = state.findOutgoingEdges(subPredicateId, BootstrapIds.HAS_SUPER_PREDICATE).stream()
                .anyMatch(e -> e.targetId().equals(superPredicateId));
        if (!exists) {
            mutations.connectEdge(state, state.allocateUserId(), subPredicateId, BootstrapIds.HAS_SUPER_PREDICATE, superPredicateId);
        }
    }

    private static EntityNode requirePrincipal(MutableGraphState state, EntityId id, String role) {
        EntityNode node = GraphSchemaValidator.requireEntityExists(state, id, role);
        if (!BootstrapIds.META_PRINCIPAL_TYPE.equals(node.typeId())) {
            throw new TypeMismatchException(
                    "Entity " + id + " for " + role + " is not a principal (META_PRINCIPAL_TYPE), was: " + node.typeId());
        }
        return node;
    }

    private static EntityNode requirePredicate(MutableGraphState state, EntityId id, String role) {
        EntityNode node = GraphSchemaValidator.requireEntityExists(state, id, role);
        if (!BootstrapIds.META_PREDICATE_TYPE.equals(node.typeId())) {
            throw new TypeMismatchException(
                    "Entity " + id + " for " + role + " is not a predicate (META_PREDICATE_TYPE), was: " + node.typeId());
        }
        return node;
    }
}
