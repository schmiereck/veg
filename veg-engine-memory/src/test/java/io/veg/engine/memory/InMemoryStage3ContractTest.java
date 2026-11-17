package io.veg.engine.memory;

import io.veg.engine.VersionedGraphDatabase;
import io.veg.fixtures.Stage3ContractTestSuite;

class InMemoryStage3ContractTest extends Stage3ContractTestSuite {

    @Override
    protected VersionedGraphDatabase createDatabase() {
        return new InMemoryVersionedDatabase();
    }
}
