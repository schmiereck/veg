package io.veg.engine.memory;

import io.veg.engine.GraphSession;
import io.veg.fixtures.Stage1ContractTestSuite;

class InMemoryStage1ContractTest extends Stage1ContractTestSuite {

    @Override
    protected GraphSession createSession() {
        return new InMemoryGraphEngine();
    }
}
