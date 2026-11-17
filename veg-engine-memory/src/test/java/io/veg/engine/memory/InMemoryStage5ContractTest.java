package io.veg.engine.memory;

import io.veg.engine.VersionedGraphDatabase;
import io.veg.fixtures.Stage5ContractTestSuite;

class InMemoryStage5ContractTest extends Stage5ContractTestSuite {

    @Override
    protected VersionedGraphDatabase createDatabase() {
        return new InMemoryVersionedDatabase();
    }
}
