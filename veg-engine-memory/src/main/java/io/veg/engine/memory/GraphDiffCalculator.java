package io.veg.engine.memory;

import io.veg.engine.GraphReader;
import io.veg.model.Change;
import io.veg.model.CommitId;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.GraphDiff;
import io.veg.model.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes deterministic structural diffs between two immutable graph snapshots (V-04).
 */
final class GraphDiffCalculator {

    private GraphDiffCalculator() {
    }

    static GraphDiff compute(CommitId fromCommitId, GraphReader from, CommitId toCommitId, GraphReader to) {
        Map<EntityId, EntityNode> fromEntities = indexEntities(from.allEntities());
        Map<EntityId, EntityNode> toEntities = indexEntities(to.allEntities());
        Map<EntityId, Edge> fromEdges = indexEdges(from.allEdges());
        Map<EntityId, Edge> toEdges = indexEdges(to.allEdges());

        List<EntityNode> addedEntities = new ArrayList<>();
        List<EntityNode> removedEntities = new ArrayList<>();
        List<Change.ValueUpdated> updatedValues = new ArrayList<>();
        List<Edge> addedEdges = new ArrayList<>();
        List<Edge> removedEdges = new ArrayList<>();

        for (EntityNode toNode : to.allEntities()) {
            EntityNode fromNode = fromEntities.get(toNode.id());
            if (fromNode == null) {
                addedEntities.add(toNode);
            } else if (fromNode.kind() == EntityKind.VALUE && toNode.kind() == EntityKind.VALUE) {
                Value oldVal = fromNode.payload().orElseThrow();
                Value newVal = toNode.payload().orElseThrow();
                if (!oldVal.equals(newVal)) {
                    updatedValues.add(new Change.ValueUpdated(toNode.id(), oldVal, newVal));
                }
            }
        }

        for (EntityNode fromNode : from.allEntities()) {
            if (!toEntities.containsKey(fromNode.id())) {
                removedEntities.add(fromNode);
            }
        }

        for (Edge toEdge : to.allEdges()) {
            if (!fromEdges.containsKey(toEdge.id())) {
                addedEdges.add(toEdge);
            }
        }

        for (Edge fromEdge : from.allEdges()) {
            if (!toEdges.containsKey(fromEdge.id())) {
                removedEdges.add(fromEdge);
            }
        }

        return new GraphDiff(fromCommitId, toCommitId, addedEntities, removedEntities, updatedValues, addedEdges, removedEdges);
    }

    private static Map<EntityId, EntityNode> indexEntities(List<EntityNode> nodes) {
        Map<EntityId, EntityNode> map = new HashMap<>(nodes.size());
        for (EntityNode node : nodes) {
            map.put(node.id(), node);
        }
        return map;
    }

    private static Map<EntityId, Edge> indexEdges(List<Edge> edges) {
        Map<EntityId, Edge> map = new HashMap<>(edges.size());
        for (Edge edge : edges) {
            map.put(edge.id(), edge);
        }
        return map;
    }
}
