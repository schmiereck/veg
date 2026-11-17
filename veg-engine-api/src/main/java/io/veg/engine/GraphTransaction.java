package io.veg.engine;

import io.veg.model.Actor;
import io.veg.model.CommitId;
import io.veg.model.Reason;
import io.veg.model.SavepointId;

import java.util.Optional;

/**
 * Atomic, isolated read/write workspace with savepoint support (V-03, V-11).
 *
 * <p>All mutations stay local to this transaction until {@link #commit()} succeeds.
 * Closing an open transaction without committing automatically triggers {@link #rollback()}.</p>
 */
public interface GraphTransaction extends GraphSession, AutoCloseable {

    Actor actor();

    Reason reason();

    Optional<String> correlationId();

    boolean isOpen();

    SavepointId createSavepoint(String name);

    void rollbackToSavepoint(SavepointId savepointId);

    CommitId commit();

    void rollback();

    @Override
    void close();
}
