package io.veg.engine.memory;

import io.veg.engine.VersionedGraphDatabase;
import io.veg.fixtures.Stage4ContractTestSuite;

class InMemoryStage4ContractTest extends Stage4ContractTestSuite {

    @Override
    protected VersionedGraphDatabase createDatabase() {
        return new InMemoryVersionedDatabase();
    }
}
