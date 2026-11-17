package io.veg.engine.memory;

import io.veg.engine.VersionedGraphDatabase;
import io.veg.fixtures.Stage6ContractTestSuite;

class InMemoryStage6ContractTest extends Stage6ContractTestSuite {

    @Override
    protected VersionedGraphDatabase createDatabase() {
        return new InMemoryVersionedDatabase();
    }
}
