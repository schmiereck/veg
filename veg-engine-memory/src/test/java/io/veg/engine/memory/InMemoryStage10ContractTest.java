package io.veg.engine.memory;

import io.veg.engine.VersionedGraphDatabase;
import io.veg.fixtures.Stage10ContractTestSuite;

/**
 * Runs all Stage 10 contract tests against Engine A ({@link InMemoryVersionedDatabase}).
 */
class InMemoryStage10ContractTest extends Stage10ContractTestSuite {

    @Override
    protected VersionedGraphDatabase createDatabase() {
        return new InMemoryVersionedDatabase();
    }
}
