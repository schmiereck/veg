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
 * Applies node/edge mutations onto a {@link MutableGraphState} while optionally appending
 * replayable {@link Change} records to a transaction change log.
 */
final class RecordedMutationSink {

    private final List<Change> recorder;

    RecordedMutationSink(List<Change> recorder) {
        this.recorder = recorder;
    }

    void createEntity(MutableGraphState state, EntityNode node) {
        state.putEntity(node);
        if (recorder != null) {
            recorder.add(new Change.EntityCreated(node));
        }
    }

    void removeEntity(MutableGraphState state, EntityNode node) {
        state.entities.remove(node.id());
        if (recorder != null) {
            recorder.add(new Change.EntityRemoved(node));
        }
    }

    void updateValue(MutableGraphState state, EntityNode existingNode, Value newValue) {
        Value oldValue = existingNode.payload().orElseThrow();
        state.putEntity(new EntityNode(existingNode.id(), existingNode.typeId(), EntityKind.VALUE, Optional.of(newValue)));
        if (recorder != null) {
            recorder.add(new Change.ValueUpdated(existingNode.id(), oldValue, newValue));
        }
    }

    void connectEdge(MutableGraphState state, EntityId edgeId, EntityId sourceId, EntityId predicateId, EntityId targetId) {
        Edge edge = new Edge(edgeId, sourceId, predicateId, targetId);
        state.putEdge(edge);
        if (recorder != null) {
            recorder.add(new Change.EdgeConnected(edge));
        }
    }

    void disconnectEdge(MutableGraphState state, EntityId edgeId) {
        Edge removed = state.removeEdge(edgeId);
        if (removed != null && recorder != null) {
            recorder.add(new Change.EdgeDisconnected(removed));
        }
    }

    void attachMetadataFlag(
            MutableGraphState state,
            EntityId targetSchemaId,
            EntityId predicateId,
            Value value,
            EntityId systemValueTypeId
    ) {
        EntityId valueNodeId = state.allocateUserId();
        createEntity(state, new EntityNode(valueNodeId, systemValueTypeId, EntityKind.VALUE, Optional.of(value)));
        connectEdge(state, state.allocateUserId(), valueNodeId, BootstrapIds.HAS_MUTABILITY, BootstrapIds.MUTABILITY_IMMUTABLE);
        connectEdge(state, state.allocateUserId(), targetSchemaId, predicateId, valueNodeId);
    }
}
