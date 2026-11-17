package io.veg.engine.persistent;

import io.veg.engine.GraphSession;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.fixtures.Stage1ContractTestSuite;
import io.veg.fixtures.Stage2ContractTestSuite;
import io.veg.fixtures.Stage3ContractTestSuite;
import io.veg.fixtures.Stage4ContractTestSuite;
import io.veg.fixtures.Stage5ContractTestSuite;
import io.veg.fixtures.Stage6ContractTestSuite;
import io.veg.fixtures.Stage10ContractTestSuite;
import io.veg.model.Actor;
import io.veg.model.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full Stage 1–6 contract equivalence test suite for {@link PersistentDiskVersionedDatabase}.
 */
class PersistentDiskContractTest {

    @TempDir
    Path tempRoot;

    private final AtomicInteger counter = new AtomicInteger();
    private final List<PersistentDiskVersionedDatabase> opened = new ArrayList<>();

    @AfterEach
    void closeAllDatabases() {
        for (PersistentDiskVersionedDatabase db : opened) {
            db.close();
        }
        opened.clear();
    }

    private PersistentDiskVersionedDatabase openFreshDatabase() {
        Path dir = tempRoot.resolve("db-" + counter.incrementAndGet());
        PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(
                dir, DurabilityMode.FLUSH_OS_BUFFER, 10);
        opened.add(db);
        return db;
    }

    @Nested
    class DiskStage1 extends Stage1ContractTestSuite {
        @Override
        protected GraphSession createSession() {
            return openFreshDatabase().beginTransaction(Actor.system(), Reason.of("s1"));
        }
    }

    @Nested
    class DiskStage2 extends Stage2ContractTestSuite {
        @Override
        protected GraphSession createSession() {
            return openFreshDatabase().beginTransaction(Actor.system(), Reason.of("s2"));
        }
    }

    @Nested
    class DiskStage3 extends Stage3ContractTestSuite {
        @Override
        protected VersionedGraphDatabase createDatabase() {
            return openFreshDatabase();
        }
    }

    @Nested
    class DiskStage4 extends Stage4ContractTestSuite {
        @Override
        protected VersionedGraphDatabase createDatabase() {
            return openFreshDatabase();
        }
    }

    @Nested
    class DiskStage5 extends Stage5ContractTestSuite {
        @Override
        protected VersionedGraphDatabase createDatabase() {
            return openFreshDatabase();
        }
    }

    @Nested
    class DiskStage6 extends Stage6ContractTestSuite {
        @Override
        protected VersionedGraphDatabase createDatabase() {
            return openFreshDatabase();
        }
    }

    @Nested
    class DiskStage10 extends Stage10ContractTestSuite {
        @Override
        protected VersionedGraphDatabase createDatabase() {
            return openFreshDatabase();
        }
    }
}
