package io.veg.engine.memory;

import io.veg.engine.GraphReader;
import io.veg.model.AccessDecision;
import io.veg.model.AccessEffect;
import io.veg.model.AccessReason;
import io.veg.model.BootstrapIds;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.Permission;
import io.veg.model.PropagationDirection;
import io.veg.model.Value;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Evaluates graph-based access control decisions against any {@link GraphReader} using Default Deny
 * and global "Deny Wins" ("Verbieten gewinnt") across direct ACLs, group hierarchies, type scopes,
 * and cyclic-safe declarative permission propagation.
 */
final class PermissionEvaluator {

    private record AclEntry(
            EntityId aclId,
            EntityId principalId,
            AccessEffect effect,
            Permission permission
    ) {
        boolean matchesPermission(Permission requested) {
            if (effect == AccessEffect.DENY) {
                return permission == requested;
            }
            return permission == requested || permission == Permission.ADMIN;
        }
    }

    private record PropagationRule(EntityId predicateId, PropagationDirection direction) {
    }

    private PermissionEvaluator() {
    }

    static AccessDecision evaluate(
            GraphReader reader,
            String principalId,
            Permission permission,
            EntityId targetId
    ) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(targetId, "targetId");
        if (principalId.isBlank()) {
            throw new IllegalArgumentException("principalId must not be blank");
        }

        Optional<Edge> edgeTargetOpt = reader.findEdge(targetId);
        Optional<EntityNode> entityTargetOpt = edgeTargetOpt.isPresent() ? Optional.empty() : reader.findEntity(targetId);
        if (edgeTargetOpt.isEmpty() && entityTargetOpt.isEmpty()) {
            return AccessDecision.deny(
                    principalId,
                    permission,
                    targetId,
                    AccessReason.DEFAULT_DENY,
                    List.of(),
                    "Target element does not exist in graph state"
            );
        }

        Optional<EntityId> principalEntityOpt = reader.findPrincipalByName(principalId);
        if (principalEntityOpt.isEmpty()) {
            return AccessDecision.deny(
                    principalId,
                    permission,
                    targetId,
                    AccessReason.DEFAULT_DENY,
                    List.of(),
                    "Principal '" + principalId + "' is not registered (default deny)"
            );
        }

        Set<EntityId> effectivePrincipals = expandPrincipalHierarchy(reader, principalEntityOpt.get());
        if (edgeTargetOpt.isPresent()) {
            return evaluateEdge(reader, principalId, permission, edgeTargetOpt.get(), effectivePrincipals);
        }
        return evaluateEntity(reader, principalId, permission, targetId, entityTargetOpt.get(), effectivePrincipals);
    }

    private static AccessDecision evaluateEntity(
            GraphReader reader,
            String principalId,
            Permission permission,
            EntityId targetId,
            EntityNode targetNode,
            Set<EntityId> effectivePrincipals
    ) {
        List<AclEntry> directAcls = readMatchingAcls(reader, targetId, effectivePrincipals, permission);
        Optional<AclEntry> directDeny = directAcls.stream()
                .filter(e -> e.effect() == AccessEffect.DENY)
                .findFirst();
        if (directDeny.isPresent()) {
            return AccessDecision.deny(
                    principalId,
                    permission,
                    targetId,
                    AccessReason.DIRECT_DENY,
                    List.of(targetId, directDeny.get().aclId()),
                    "Direct DENY ACL " + directDeny.get().aclId() + " on " + targetId
            );
        }

        AclEntry directAllow = directAcls.stream()
                .filter(e -> e.effect() == AccessEffect.ALLOW)
                .findFirst()
                .orElse(null);

        AccessDecision inheritedDeny = null;
        AccessDecision inheritedAllow = null;

        if (targetNode != null && !targetId.equals(targetNode.typeId())) {
            List<AclEntry> typeAcls = readMatchingAcls(reader, targetNode.typeId(), effectivePrincipals, permission);
            for (AclEntry acl : typeAcls) {
                if (acl.effect() == AccessEffect.DENY && inheritedDeny == null) {
                    inheritedDeny = AccessDecision.deny(
                            principalId,
                            permission,
                            targetId,
                            AccessReason.INHERITED_DENY,
                            List.of(targetId, targetNode.typeId(), acl.aclId()),
                            "Type-level DENY ACL " + acl.aclId() + " on type " + targetNode.typeId()
                    );
                } else if (acl.effect() == AccessEffect.ALLOW && inheritedAllow == null) {
                    inheritedAllow = AccessDecision.allow(
                            principalId,
                            permission,
                            targetId,
                            AccessReason.INHERITED_ALLOW,
                            List.of(targetId, targetNode.typeId(), acl.aclId()),
                            "Type-level ALLOW ACL " + acl.aclId() + " on type " + targetNode.typeId()
                    );
                }
            }
        }

        if (inheritedDeny == null && targetNode != null && targetNode.kind() == EntityKind.VALUE) {
            AccessDecision predDeny = checkValueIncomingPredicateDeny(reader, principalId, permission, targetId, effectivePrincipals);
            if (predDeny != null) {
                inheritedDeny = predDeny;
            }
        }

        List<PropagationRule> rules = readPropagationRules(reader, permission);
        if (!rules.isEmpty()) {
            AccessDecision[] propagationOutcome = bfsPropagation(
                    reader, principalId, permission, targetId, effectivePrincipals, rules
            );
            if (inheritedDeny == null && propagationOutcome[0] != null) {
                inheritedDeny = propagationOutcome[0];
            }
            if (inheritedAllow == null && propagationOutcome[1] != null) {
                inheritedAllow = propagationOutcome[1];
            }
        }

        if (inheritedDeny != null) {
            return inheritedDeny;
        }
        if (directAllow != null) {
            return AccessDecision.allow(
                    principalId,
                    permission,
                    targetId,
                    AccessReason.DIRECT_ALLOW,
                    List.of(targetId, directAllow.aclId()),
                    "Direct ALLOW ACL " + directAllow.aclId() + " on " + targetId
            );
        }
        if (inheritedAllow != null) {
            return inheritedAllow;
        }
        return AccessDecision.deny(
                principalId,
                permission,
                targetId,
                AccessReason.DEFAULT_DENY,
                List.of(),
                "No direct or propagated ALLOW entry found for " + principalId + " on " + targetId
        );
    }

    private static AccessDecision evaluateEntityById(
            GraphReader reader,
            String principalId,
            Permission permission,
            EntityId targetId,
            Set<EntityId> effectivePrincipals
    ) {
        return evaluateEntity(reader, principalId, permission, targetId, reader.findEntity(targetId).orElse(null), effectivePrincipals);
    }

    private static AccessDecision evaluateEdge(
            GraphReader reader,
            String principalId,
            Permission permission,
            Edge edge,
            Set<EntityId> effectivePrincipals
    ) {
        EntityId edgeId = edge.id();
        List<AclEntry> directAcls = readMatchingAcls(reader, edgeId, effectivePrincipals, permission);
        Optional<AclEntry> directDeny = directAcls.stream()
                .filter(e -> e.effect() == AccessEffect.DENY)
                .findFirst();
        if (directDeny.isPresent()) {
            return AccessDecision.deny(
                    principalId,
                    permission,
                    edgeId,
                    AccessReason.DIRECT_DENY,
                    List.of(edgeId, directDeny.get().aclId()),
                    "Direct DENY ACL " + directDeny.get().aclId() + " on edge " + edgeId
            );
        }

        AccessDecision predDecision = evaluateEntityById(reader, principalId, permission, edge.predicateId(), effectivePrincipals);
        if (!predDecision.allowed() && isExplicitOrInheritedDeny(predDecision.reason())) {
            return AccessDecision.deny(
                    principalId,
                    permission,
                    edgeId,
                    AccessReason.INHERITED_DENY,
                    prependStep(edgeId, predDecision.winningPath()),
                    "Denied via edge predicate " + edge.predicateId() + ": " + predDecision.explanation()
            );
        }

        AccessDecision sourceDecision = evaluateEntityById(reader, principalId, permission, edge.sourceId(), effectivePrincipals);
        if (!sourceDecision.allowed() && isExplicitOrInheritedDeny(sourceDecision.reason())) {
            return AccessDecision.deny(
                    principalId,
                    permission,
                    edgeId,
                    AccessReason.INHERITED_DENY,
                    prependStep(edgeId, sourceDecision.winningPath()),
                    "Denied via edge source " + edge.sourceId() + ": " + sourceDecision.explanation()
            );
        }

        AccessDecision targetDecision = evaluateEntityById(reader, principalId, permission, edge.targetId(), effectivePrincipals);
        if (!targetDecision.allowed() && isExplicitOrInheritedDeny(targetDecision.reason())) {
            return AccessDecision.deny(
                    principalId,
                    permission,
                    edgeId,
                    AccessReason.INHERITED_DENY,
                    prependStep(edgeId, targetDecision.winningPath()),
                    "Denied via edge target " + edge.targetId() + ": " + targetDecision.explanation()
            );
        }

        Optional<AclEntry> directAllow = directAcls.stream()
                .filter(e -> e.effect() == AccessEffect.ALLOW)
                .findFirst();
        if (directAllow.isPresent()) {
            return AccessDecision.allow(
                    principalId,
                    permission,
                    edgeId,
                    AccessReason.DIRECT_ALLOW,
                    List.of(edgeId, directAllow.get().aclId()),
                    "Direct ALLOW ACL " + directAllow.get().aclId() + " on edge " + edgeId
            );
        }

        if (predDecision.allowed()) {
            return AccessDecision.allow(
                    principalId,
                    permission,
                    edgeId,
                    AccessReason.INHERITED_ALLOW,
                    prependStep(edgeId, predDecision.winningPath()),
                    "Allowed via edge predicate " + edge.predicateId() + ": " + predDecision.explanation()
            );
        }

        if (permission == Permission.READ && sourceDecision.allowed() && targetDecision.allowed()) {
            return AccessDecision.allow(
                    principalId,
                    permission,
                    edgeId,
                    AccessReason.INHERITED_ALLOW,
                    List.of(edgeId, edge.sourceId(), edge.targetId()),
                    "Both endpoints (" + edge.sourceId() + ", " + edge.targetId() + ") are readable and predicate is not denied"
            );
        }

        return AccessDecision.deny(
                principalId,
                permission,
                edgeId,
                AccessReason.DEFAULT_DENY,
                List.of(),
                "Edge " + edgeId + " lacks permission " + permission + " for " + principalId
        );
    }

    private static AccessDecision checkValueIncomingPredicateDeny(
            GraphReader reader,
            String principalId,
            Permission permission,
            EntityId valueId,
            Set<EntityId> effectivePrincipals
    ) {
        List<Edge> incoming = reader.findIncomingEdges(valueId);
        if (incoming.isEmpty()) {
            return null;
        }
        AccessDecision firstDeny = null;
        boolean hasNonDeniedUserEdge = false;
        for (Edge edge : incoming) {
            if (BootstrapIds.HAS_MUTABILITY.equals(edge.predicateId()) || BootstrapIds.HAS_NAME.equals(edge.predicateId())) {
                continue;
            }
            List<AclEntry> predAcls = readMatchingAcls(reader, edge.predicateId(), effectivePrincipals, permission);
            Optional<AclEntry> deny = predAcls.stream().filter(a -> a.effect() == AccessEffect.DENY).findFirst();
            if (deny.isPresent()) {
                if (firstDeny == null) {
                    firstDeny = AccessDecision.deny(
                            principalId,
                            permission,
                            valueId,
                            AccessReason.INHERITED_DENY,
                            List.of(valueId, edge.predicateId(), deny.get().aclId()),
                            "Denied via incoming predicate " + edge.predicateId() + " ACL " + deny.get().aclId()
                    );
                }
            } else {
                hasNonDeniedUserEdge = true;
            }
        }
        return !hasNonDeniedUserEdge ? firstDeny : null;
    }

    private static AccessDecision[] bfsPropagation(
            GraphReader reader,
            String principalId,
            Permission permission,
            EntityId targetId,
            Set<EntityId> effectivePrincipals,
            List<PropagationRule> rules
    ) {
        Set<EntityId> visited = new HashSet<>();
        visited.add(targetId);
        Map<EntityId, EntityId> childBackpointer = new HashMap<>();
        Deque<EntityId> queue = new ArrayDeque<>();
        queue.add(targetId);

        AccessDecision firstDeny = null;
        AccessDecision firstAllow = null;

        while (!queue.isEmpty()) {
            EntityId current = queue.poll();
            List<EntityId> neighbors = new ArrayList<>();
            for (PropagationRule rule : rules) {
                if (rule.direction() == PropagationDirection.OUTGOING || rule.direction() == PropagationDirection.BOTH) {
                    for (Edge edge : reader.findIncomingEdges(current, rule.predicateId())) {
                        neighbors.add(edge.sourceId());
                    }
                }
                if (rule.direction() == PropagationDirection.INCOMING || rule.direction() == PropagationDirection.BOTH) {
                    for (Edge edge : reader.findOutgoingEdges(current, rule.predicateId())) {
                        neighbors.add(edge.targetId());
                    }
                }
            }
            neighbors.sort(Comparator.naturalOrder());

            for (EntityId ancestor : neighbors) {
                if (!visited.add(ancestor)) {
                    continue;
                }
                childBackpointer.put(ancestor, current);
                queue.add(ancestor);

                List<EntityId> scopesToCheck = new ArrayList<>();
                scopesToCheck.add(ancestor);
                EntityNode ancestorNode = reader.findEntity(ancestor).orElse(null);
                if (ancestorNode != null && !ancestor.equals(ancestorNode.typeId())) {
                    scopesToCheck.add(ancestorNode.typeId());
                }

                for (EntityId scopeId : scopesToCheck) {
                    List<AclEntry> acls = readMatchingAcls(reader, scopeId, effectivePrincipals, permission);
                    for (AclEntry acl : acls) {
                        List<EntityId> path = reconstructPath(targetId, ancestor, childBackpointer, scopeId, acl.aclId());
                        if (acl.effect() == AccessEffect.DENY && firstDeny == null) {
                            firstDeny = AccessDecision.deny(
                                    principalId,
                                    permission,
                                    targetId,
                                    AccessReason.INHERITED_DENY,
                                    path,
                                    "Inherited DENY ACL " + acl.aclId() + " via scope " + scopeId
                            );
                        } else if (acl.effect() == AccessEffect.ALLOW && firstAllow == null) {
                            firstAllow = AccessDecision.allow(
                                    principalId,
                                    permission,
                                    targetId,
                                    AccessReason.INHERITED_ALLOW,
                                    path,
                                    "Inherited ALLOW ACL " + acl.aclId() + " via scope " + scopeId
                            );
                        }
                    }
                }
            }
        }

        return new AccessDecision[]{firstDeny, firstAllow};
    }

    private static List<EntityId> reconstructPath(
            EntityId targetId,
            EntityId ancestor,
            Map<EntityId, EntityId> childBackpointer,
            EntityId scopeId,
            EntityId aclId
    ) {
        List<EntityId> reversed = new ArrayList<>();
        EntityId cursor = ancestor;
        while (cursor != null) {
            reversed.add(cursor);
            if (cursor.equals(targetId)) {
                break;
            }
            cursor = childBackpointer.get(cursor);
        }
        List<EntityId> path = new ArrayList<>(reversed.reversed());
        if (!scopeId.equals(ancestor)) {
            path.add(scopeId);
        }
        path.add(aclId);
        return List.copyOf(path);
    }

    private static Set<EntityId> expandPrincipalHierarchy(GraphReader reader, EntityId principalEntityId) {
        Set<EntityId> principals = new LinkedHashSet<>();
        Deque<EntityId> queue = new ArrayDeque<>();
        principals.add(principalEntityId);
        queue.add(principalEntityId);
        while (!queue.isEmpty()) {
            EntityId current = queue.poll();
            List<EntityId> groups = reader.findOutgoingEdges(current, BootstrapIds.MEMBER_OF_GROUP).stream()
                    .map(Edge::targetId)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            for (EntityId group : groups) {
                if (principals.add(group)) {
                    queue.add(group);
                }
            }
        }
        return principals;
    }

    private static List<AclEntry> readMatchingAcls(
            GraphReader reader,
            EntityId targetId,
            Set<EntityId> effectivePrincipals,
            Permission requestedPermission
    ) {
        List<Edge> targetEdges = reader.findIncomingEdges(targetId, BootstrapIds.ACL_TARGET);
        if (targetEdges.isEmpty()) {
            return List.of();
        }
        List<AclEntry> matched = new ArrayList<>();
        List<EntityId> aclIds = targetEdges.stream()
                .map(Edge::sourceId)
                .sorted(Comparator.naturalOrder())
                .toList();
        for (EntityId aclId : aclIds) {
            Optional<EntityId> aclPrincipal = singleEdgeTarget(reader, aclId, BootstrapIds.ACL_PRINCIPAL);
            if (aclPrincipal.isEmpty() || !effectivePrincipals.contains(aclPrincipal.get())) {
                continue;
            }
            Optional<String> effectStr = singleStringTarget(reader, aclId, BootstrapIds.ACL_EFFECT);
            Optional<String> permStr = singleStringTarget(reader, aclId, BootstrapIds.ACL_PERMISSION);
            if (effectStr.isEmpty() || permStr.isEmpty()) {
                continue;
            }
            AclEntry entry = new AclEntry(
                    aclId,
                    aclPrincipal.get(),
                    AccessEffect.valueOf(effectStr.get()),
                    Permission.valueOf(permStr.get())
            );
            if (entry.matchesPermission(requestedPermission)) {
                matched.add(entry);
            }
        }
        return matched;
    }

    private static List<PropagationRule> readPropagationRules(GraphReader reader, Permission requestedPermission) {
        List<EntityId> ruleIds;
        if (reader instanceof PropagationRuleSource fastSource) {
            ruleIds = fastSource.propagationRuleIds();
        } else {
            ruleIds = reader.allEntities().stream()
                    .filter(n -> BootstrapIds.META_PROPAGATION_RULE_TYPE.equals(n.typeId()))
                    .map(EntityNode::id)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
        if (ruleIds.isEmpty()) {
            return List.of();
        }
        List<PropagationRule> rules = new ArrayList<>();
        for (EntityId ruleId : ruleIds) {
            Optional<EntityId> predId = singleEdgeTarget(reader, ruleId, BootstrapIds.PROPAGATION_PREDICATE);
            Optional<String> dirStr = singleStringTarget(reader, ruleId, BootstrapIds.PROPAGATION_DIRECTION);
            Optional<String> permStr = singleStringTarget(reader, ruleId, BootstrapIds.PROPAGATION_PERMISSION);
            if (predId.isPresent() && dirStr.isPresent() && permStr.isPresent()) {
                Permission rulePerm = Permission.valueOf(permStr.get());
                if (rulePerm == requestedPermission || rulePerm == Permission.ADMIN) {
                    rules.add(new PropagationRule(predId.get(), PropagationDirection.valueOf(dirStr.get())));
                }
            }
        }
        return rules;
    }

    private static Optional<EntityId> singleEdgeTarget(GraphReader reader, EntityId sourceId, EntityId predicateId) {
        List<Edge> edges = reader.findOutgoingEdges(sourceId, predicateId);
        return edges.isEmpty() ? Optional.empty() : Optional.of(edges.getFirst().targetId());
    }

    private static Optional<String> singleStringTarget(GraphReader reader, EntityId sourceId, EntityId predicateId) {
        Optional<EntityId> valueId = singleEdgeTarget(reader, sourceId, predicateId);
        if (valueId.isEmpty()) {
            return Optional.empty();
        }
        EntityNode node = reader.findEntity(valueId.get()).orElse(null);
        if (node != null && node.payload().isPresent() && node.payload().get() instanceof Value.StringValue(String val)) {
            return Optional.of(val);
        }
        return Optional.empty();
    }

    private static boolean isExplicitOrInheritedDeny(AccessReason reason) {
        return reason == AccessReason.DIRECT_DENY || reason == AccessReason.INHERITED_DENY;
    }

    private static List<EntityId> prependStep(EntityId first, List<EntityId> rest) {
        List<EntityId> path = new ArrayList<>(1 + rest.size());
        path.add(first);
        path.addAll(rest);
        return List.copyOf(path);
    }
}
