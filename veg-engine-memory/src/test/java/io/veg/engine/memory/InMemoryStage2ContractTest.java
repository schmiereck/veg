package io.veg.engine.memory;

import io.veg.engine.GraphSession;
import io.veg.fixtures.Stage2ContractTestSuite;

class InMemoryStage2ContractTest extends Stage2ContractTestSuite {

    @Override
    protected GraphSession createSession() {
        return new InMemoryGraphEngine();
    }
}
