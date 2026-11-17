package io.veg.engine.memory;

import io.veg.engine.RevertConflictException;
import io.veg.engine.SchemaViolationException;
import io.veg.model.BootstrapIds;
import io.veg.model.Change;
import io.veg.model.Commit;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes and validates counter-commit changes when reverting a historical commit (V-10, T09).
 */
final class CommitReverter {

    private CommitReverter() {
    }

    static List<Change> revertOnto(MutableGraphState working, Commit targetCommit) {
        List<Change> targetChanges = targetCommit.changes();
        List<Change> counterChanges = new ArrayList<>(targetChanges.size());

        for (int i = targetChanges.size() - 1; i >= 0; i--) {
            applyInverseChange(working, targetChanges.get(i), counterChanges);
        }
        working.rebuildDerivedIndexes();
        return counterChanges;
    }

    private static void applyInverseChange(MutableGraphState working, Change change, List<Change> counterChanges) {
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
