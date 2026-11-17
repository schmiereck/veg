package io.veg.engine.memory;

import io.veg.engine.AccessDeniedException;
import io.veg.engine.AuthorizedGraphReader;
import io.veg.engine.EntityNotFoundException;
import io.veg.engine.GraphReader;
import io.veg.model.AccessDecision;
import io.veg.model.AccessReason;
import io.veg.model.DerivedAssertion;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.Permission;
import io.veg.model.PredicateConstraints;
import io.veg.model.ValidityWindow;
import io.veg.model.ValueMutability;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Actor-scoped graph reader enforcing branch restrictions, "Deny Wins" graph ACLs,
 * and historical time-travel head checks (preventing exfiltration after revocation).
 */
final class AuthorizedGraphReaderImpl implements AuthorizedGraphReader {

    private final GraphReader targetSnapshot;
    private final GraphReader currentHeadSnapshot;
    private final String principalId;
    private final String branchName;
    private final Set<String> allowedBranchPrincipals;

    AuthorizedGraphReaderImpl(
            GraphReader targetSnapshot,
            GraphReader currentHeadSnapshot,
            String principalId,
            String branchName,
            Set<String> allowedBranchPrincipals
    ) {
        this.targetSnapshot = Objects.requireNonNull(targetSnapshot, "targetSnapshot must not be null");
        this.currentHeadSnapshot = currentHeadSnapshot;
        this.principalId = Objects.requireNonNull(principalId, "principalId must not be null");
        if (principalId.isBlank()) {
            throw new IllegalArgumentException("principalId must not be blank");
        }
        this.branchName = branchName;
        this.allowedBranchPrincipals = allowedBranchPrincipals == null ? null : Set.copyOf(allowedBranchPrincipals);
    }

    @Override
    public String principalId() {
        return principalId;
    }

    @Override
    public AccessDecision explainAccess(Permission permission, EntityId targetId) {
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");

        if (isBranchAccessDenied()) {
            return AccessDecision.deny(
                    principalId,
                    permission,
                    targetId,
                    AccessReason.BRANCH_RESTRICTED,
                    List.of(),
                    "Branch '" + branchName + "' read access is restricted for principal '" + principalId + "'"
            );
        }

        AccessDecision targetDecision = PermissionEvaluator.evaluate(
                targetSnapshot, principalId, permission, targetId
        );
        if (!targetDecision.allowed()) {
            return targetDecision;
        }

        if (currentHeadSnapshot != null && currentHeadSnapshot != targetSnapshot) {
            if (currentHeadSnapshot.findEntity(targetId).isPresent() || currentHeadSnapshot.findEdge(targetId).isPresent()) {
                AccessDecision headDecision = PermissionEvaluator.evaluate(currentHeadSnapshot, principalId, permission, targetId);
                if (!headDecision.allowed()) {
                    return AccessDecision.deny(
                            principalId,
                            permission,
                            targetId,
                            AccessReason.HISTORICAL_HEAD_DENY,
                            headDecision.winningPath(),
                            "Revoked or denied at current branch HEAD: " + headDecision.explanation()
                    );
                }
            } else {
                AccessDecision removedMetaCheck = checkRemovedElementMetaRevocation(currentHeadSnapshot, permission, targetId);
                if (removedMetaCheck != null) {
                    return removedMetaCheck;
                }
            }
        }

        return targetDecision;
    }

    private AccessDecision checkRemovedElementMetaRevocation(
            GraphReader headReader,
            Permission permission,
            EntityId targetId
    ) {
        Optional<EntityNode> historicalNodeOpt = targetSnapshot.findEntity(targetId);
        if (historicalNodeOpt.isPresent()) {
            EntityId typeId = historicalNodeOpt.get().typeId();
            if (headReader.findEntity(typeId).isPresent()) {
                AccessDecision typeHeadDecision = PermissionEvaluator.evaluate(
                        headReader, principalId, permission, typeId
                );
                if (!typeHeadDecision.allowed()
                        && (typeHeadDecision.reason() == AccessReason.DIRECT_DENY
                        || typeHeadDecision.reason() == AccessReason.INHERITED_DENY)) {
                    return AccessDecision.deny(
                            principalId,
                            permission,
                            targetId,
                            AccessReason.HISTORICAL_HEAD_DENY,
                            typeHeadDecision.winningPath(),
                            "Type " + typeId + " is denied at current HEAD"
                    );
                }
            }
        }
        Optional<Edge> historicalEdgeOpt = targetSnapshot.findEdge(targetId);
        if (historicalEdgeOpt.isPresent()) {
            EntityId predicateId = historicalEdgeOpt.get().predicateId();
            if (headReader.findEntity(predicateId).isPresent()) {
                AccessDecision predHeadDecision = PermissionEvaluator.evaluate(
                        headReader, principalId, permission, predicateId
                );
                if (!predHeadDecision.allowed()
                        && (predHeadDecision.reason() == AccessReason.DIRECT_DENY
                        || predHeadDecision.reason() == AccessReason.INHERITED_DENY)) {
                    return AccessDecision.deny(
                            principalId,
                            permission,
                            targetId,
                            AccessReason.HISTORICAL_HEAD_DENY,
                            predHeadDecision.winningPath(),
                            "Predicate " + predicateId + " is denied at current HEAD"
                    );
                }
            }
        }
        return null;
    }

    @Override
    public EntityNode getEntityOrThrow(EntityId id) {
        requireAccess(Permission.READ, id);
        return targetSnapshot.findEntity(id)
                .orElseThrow(() -> new EntityNotFoundException("Entity not found: " + id));
    }

    @Override
    public Edge getEdgeOrThrow(EntityId edgeId) {
        requireAccess(Permission.READ, edgeId);
        return targetSnapshot.findEdge(edgeId)
                .orElseThrow(() -> new EntityNotFoundException("Edge not found: " + edgeId));
    }

    @Override
    public void requireAccess(Permission permission, EntityId targetId) {
        AccessDecision decision = explainAccess(permission, targetId);
        if (!decision.allowed()) {
            throw new AccessDeniedException(decision);
        }
    }

    @Override
    public Optional<EntityNode> findEntity(EntityId id) {
        Objects.requireNonNull(id, "id must not be null");
        if (!canRead(id)) {
            return Optional.empty();
        }
        return targetSnapshot.findEntity(id);
    }

    @Override
    public Optional<Edge> findEdge(EntityId edgeId) {
        Objects.requireNonNull(edgeId, "edgeId must not be null");
        if (!canRead(edgeId)) {
            return Optional.empty();
        }
        return targetSnapshot.findEdge(edgeId);
    }

    @Override
    public Optional<EntityId> findTypeByName(String name) {
        if (isBranchAccessDenied()) {
            return Optional.empty();
        }
        return targetSnapshot.findTypeByName(name);
    }

    @Override
    public Optional<EntityId> findPredicateByName(String name) {
        if (isBranchAccessDenied()) {
            return Optional.empty();
        }
        return targetSnapshot.findPredicateByName(name);
    }

    @Override
    public Optional<String> resolveName(EntityId schemaEntityId) {
        Objects.requireNonNull(schemaEntityId, "schemaEntityId must not be null");
        if (isBranchAccessDenied()) {
            return Optional.empty();
        }
        return targetSnapshot.resolveName(schemaEntityId);
    }

    @Override
    public Optional<ValueMutability> resolveValueMutability(EntityId valueEntityId) {
        Objects.requireNonNull(valueEntityId, "valueEntityId must not be null");
        if (!canRead(valueEntityId)) {
            return Optional.empty();
        }
        return targetSnapshot.resolveValueMutability(valueEntityId);
    }

    @Override
    public Optional<PredicateConstraints> resolvePredicateConstraints(EntityId predicateId) {
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        if (isBranchAccessDenied()) {
            return Optional.empty();
        }
        return targetSnapshot.resolvePredicateConstraints(predicateId);
    }

    @Override
    public boolean isUniqueValueType(EntityId valueTypeId) {
        Objects.requireNonNull(valueTypeId, "valueTypeId must not be null");
        return !isBranchAccessDenied() && targetSnapshot.isUniqueValueType(valueTypeId);
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (!canRead(sourceId)) {
            return List.of();
        }
        return targetSnapshot.findOutgoingEdges(sourceId).stream()
                .filter(edge -> canRead(edge.id()))
                .toList();
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        if (!canRead(sourceId)) {
            return List.of();
        }
        return targetSnapshot.findOutgoingEdges(sourceId, predicateId).stream()
                .filter(edge -> canRead(edge.id()))
                .toList();
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        if (!canRead(targetId)) {
            return List.of();
        }
        return targetSnapshot.findIncomingEdges(targetId).stream()
                .filter(edge -> canRead(edge.id()))
                .toList();
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId, EntityId predicateId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        if (!canRead(targetId)) {
            return List.of();
        }
        return targetSnapshot.findIncomingEdges(targetId, predicateId).stream()
                .filter(edge -> canRead(edge.id()))
                .toList();
    }

    @Override
    public List<EntityNode> findEntitiesByType(EntityId typeId) {
        Objects.requireNonNull(typeId, "typeId must not be null");
        if (isBranchAccessDenied()) {
            return List.of();
        }
        return targetSnapshot.findEntitiesByType(typeId).stream()
                .filter(node -> canRead(node.id()))
                .toList();
    }

    @Override
    public Optional<ValidityWindow> resolveValidityWindow(EntityId entityOrEdgeId) {
        Objects.requireNonNull(entityOrEdgeId, "entityOrEdgeId must not be null");
        if (!canRead(entityOrEdgeId)) {
            return Optional.empty();
        }
        return targetSnapshot.resolveValidityWindow(entityOrEdgeId);
    }

    @Override
    public long entityCount() {
        return allEntities().size();
    }

    @Override
    public long edgeCount() {
        return allEdges().size();
    }

    @Override
    public List<EntityNode> allEntities() {
        if (isBranchAccessDenied()) {
            return List.of();
        }
        return targetSnapshot.allEntities().stream()
                .filter(node -> canRead(node.id()))
                .toList();
    }

    @Override
    public List<Edge> allEdges() {
        if (isBranchAccessDenied()) {
            return List.of();
        }
        return targetSnapshot.allEdges().stream()
                .filter(edge -> canRead(edge.id()))
                .toList();
    }

    @Override
    public Optional<EntityId> findPrincipalByName(String lookupPrincipalId) {
        if (isBranchAccessDenied()) {
            return Optional.empty();
        }
        return targetSnapshot.findPrincipalByName(lookupPrincipalId);
    }

    @Override
    public AccessDecision evaluateAccess(String evalPrincipalId, Permission permission, EntityId targetId) {
        Objects.requireNonNull(evalPrincipalId, "principalId must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        if (evalPrincipalId.equals(this.principalId)) {
            return explainAccess(permission, targetId);
        }
        if (isBranchAccessDenied()) {
            return AccessDecision.deny(
                    evalPrincipalId,
                    permission,
                    targetId,
                    AccessReason.BRANCH_RESTRICTED,
                    List.of(),
                    "Branch '" + branchName + "' read access is restricted"
            );
        }
        return targetSnapshot.evaluateAccess(evalPrincipalId, permission, targetId);
    }

    @Override
    public List<DerivedAssertion> findDerivedOutgoingAssertions(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (!canRead(sourceId)) {
            return List.of();
        }
        return filterAssertions(targetSnapshot.findDerivedOutgoingAssertions(sourceId));
    }

    @Override
    public List<DerivedAssertion> findDerivedOutgoingAssertions(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        if (!canRead(sourceId) || isPredicateExplicitlyDenied(predicateId)) {
            return List.of();
        }
        return filterAssertions(targetSnapshot.findDerivedOutgoingAssertions(sourceId, predicateId));
    }

    @Override
    public List<DerivedAssertion> findAllOutgoingAssertions(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (!canRead(sourceId)) {
            return List.of();
        }
        return filterAssertions(targetSnapshot.findAllOutgoingAssertions(sourceId));
    }

    @Override
    public List<DerivedAssertion> findAllOutgoingAssertions(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        if (!canRead(sourceId) || isPredicateExplicitlyDenied(predicateId)) {
            return List.of();
        }
        return filterAssertions(targetSnapshot.findAllOutgoingAssertions(sourceId, predicateId));
    }

    private List<DerivedAssertion> filterAssertions(List<DerivedAssertion> assertions) {
        return assertions.stream()
                .filter(a -> canRead(a.targetId()))
                .filter(a -> a.backingEdgeId().map(this::canRead).orElse(true))
                .filter(a -> !isPredicateExplicitlyDenied(a.predicateId()))
                .toList();
    }

    private boolean isPredicateExplicitlyDenied(EntityId predicateId) {
        AccessDecision targetDecision = PermissionEvaluator.evaluate(
                targetSnapshot, principalId, Permission.READ, predicateId
        );
        if (!targetDecision.allowed()
                && (targetDecision.reason() == AccessReason.DIRECT_DENY
                || targetDecision.reason() == AccessReason.INHERITED_DENY)) {
            return true;
        }
        if (currentHeadSnapshot != null && currentHeadSnapshot != targetSnapshot) {
            AccessDecision headDecision = PermissionEvaluator.evaluate(
                    currentHeadSnapshot, principalId, Permission.READ, predicateId
            );
            return !headDecision.allowed()
                    && (headDecision.reason() == AccessReason.DIRECT_DENY
                    || headDecision.reason() == AccessReason.INHERITED_DENY);
        }
        return false;
    }

    private boolean canRead(EntityId id) {
        return explainAccess(Permission.READ, id).allowed();
    }

    private boolean isBranchAccessDenied() {
        return allowedBranchPrincipals != null && !allowedBranchPrincipals.contains(principalId);
    }
}
