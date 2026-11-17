package io.veg.model;

import java.util.Objects;

/**
 * Atomic, replayable state mutation recorded inside a transaction and persisted as part of a {@link Commit}.
 */
public sealed interface Change permits
        Change.EntityCreated,
        Change.EntityRemoved,
        Change.ValueUpdated,
        Change.EdgeConnected,
        Change.EdgeDisconnected {

    EntityId primaryEntityId();

    record EntityCreated(EntityNode node) implements Change {
        public EntityCreated {
            Objects.requireNonNull(node, "node must not be null");
        }

        @Override
        public EntityId primaryEntityId() {
            return node.id();
        }
    }

    record EntityRemoved(EntityNode node) implements Change {
        public EntityRemoved {
            Objects.requireNonNull(node, "node must not be null");
        }

        @Override
        public EntityId primaryEntityId() {
            return node.id();
        }
    }

    record ValueUpdated(EntityId entityId, Value previousValue, Value newValue) implements Change {
        public ValueUpdated {
            Objects.requireNonNull(entityId, "entityId must not be null");
            Objects.requireNonNull(previousValue, "previousValue must not be null");
            Objects.requireNonNull(newValue, "newValue must not be null");
        }

        @Override
        public EntityId primaryEntityId() {
            return entityId;
        }
    }

    record EdgeConnected(Edge edge) implements Change {
        public EdgeConnected {
            Objects.requireNonNull(edge, "edge must not be null");
        }

        @Override
        public EntityId primaryEntityId() {
            return edge.id();
        }
    }

    record EdgeDisconnected(Edge edge) implements Change {
        public EdgeDisconnected {
            Objects.requireNonNull(edge, "edge must not be null");
        }

        @Override
        public EntityId primaryEntityId() {
            return edge.id();
        }
    }
}
