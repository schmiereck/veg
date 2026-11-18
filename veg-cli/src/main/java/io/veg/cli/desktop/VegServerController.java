package io.veg.cli.desktop;

import io.veg.cli.kb.GraphTabularService;
import io.veg.cli.kb.ProjectKnowledgeImporter;
import io.veg.cli.kb.ProjectKnowledgeImporter.ImportReport;
import io.veg.cli.studio.VegStudioServer;
import io.veg.engine.GraphTransaction;
import io.veg.engine.RdfGraphExporter;
import io.veg.engine.VegPatchService;
import io.veg.engine.VegPatchService.PatchApplyMode;
import io.veg.engine.VegPatchService.PatchResult;
import io.veg.engine.persistent.PersistentDiskVersionedDatabase;
import io.veg.model.Actor;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.IdGenerationStrategy;
import io.veg.model.RdfExportOptions;
import io.veg.model.RdfExportResult;
import io.veg.model.RdfFormat;
import io.veg.model.RdfImportResult;
import io.veg.model.Reason;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Thread-safe lifecycle and database administration controller powering the Windows
 * VEG Server Manager UI ({@link VegDesktopApp}) and automated desktop tests.
 */
public final class VegServerController implements AutoCloseable {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record ServerStatus(
            boolean running,
            Path dbDir,
            int configuredPort,
            int activePort,
            String studioUrl,
            String currentBranch,
            String headCommitId,
            long entityCount,
            long edgeCount,
            int commitCount,
            int branchCount,
            IdGenerationStrategy idStrategy
    ) {
    }

    private Path dbDir;
    private int configuredPort;
    private IdGenerationStrategy idStrategy = IdGenerationStrategy.SEQUENTIAL;

    private PersistentDiskVersionedDatabase db;
    private VegStudioServer server;

    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();
    private final List<String> recentLogs = new CopyOnWriteArrayList<>();

    public VegServerController(Path initialDbDir, int initialPort) {
        this.dbDir = (initialDbDir != null ? initialDbDir : resolveDefaultDbDir()).toAbsolutePath().normalize();
        this.configuredPort = validatePort(initialPort);
    }

    public static Path resolveDefaultDbDir() {
        String jpackageAppPath = System.getProperty("jpackage.app-path");
        if (jpackageAppPath != null && !jpackageAppPath.isBlank()) {
            Path exeDir = Path.of(jpackageAppPath).toAbsolutePath().getParent();
            if (exeDir != null && Files.isDirectory(exeDir.resolve("data/project-kb"))) {
                return exeDir.resolve("data/project-kb").normalize();
            }
        }
        Path cwdKb = Path.of("data/project-kb");
        if (Files.isDirectory(cwdKb)) {
            return cwdKb.toAbsolutePath().normalize();
        }
        Path parentKb = Path.of("../data/project-kb");
        if (Files.isDirectory(parentKb)) {
            return parentKb.toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".veg", "project-kb").toAbsolutePath().normalize();
    }

    public void addLogListener(Consumer<String> listener) {
        logListeners.add(Objects.requireNonNull(listener));
    }

    public void addStatusListener(Runnable listener) {
        statusListeners.add(Objects.requireNonNull(listener));
    }

    public List<String> recentLogs() {
        return List.copyOf(recentLogs);
    }

    public synchronized ServerStatus status() {
        if (db == null || server == null) {
            return new ServerStatus(
                    false, dbDir, configuredPort, 0, "", "-", "-", 0L, 0L, 0, 0, idStrategy
            );
        }
        var snap = db.currentSnapshot();
        return new ServerStatus(
                true,
                dbDir,
                configuredPort,
                server.port(),
                server.baseUrl(),
                db.currentBranch(),
                db.headCommitId().toString(),
                snap.entityCount(),
                snap.edgeCount(),
                db.commitHistory().size(),
                db.listBranches().size(),
                db.idGenerationStrategy()
        );
    }

    public synchronized void startServer() throws IOException {
        if (server != null) {
            return;
        }
        Files.createDirectories(dbDir);
        PersistentDiskVersionedDatabase opened = PersistentDiskVersionedDatabase.open(dbDir);
        try {
            opened.setIdGenerationStrategy(idStrategy);
            VegStudioServer http = new VegStudioServer(opened, configuredPort, dbDir);
            http.setActivityListener(msg -> {
                log(msg);
                notifyStatus();
            });
            http.start();
            this.db = opened;
            this.server = http;
            log("Server gestartet auf " + http.baseUrl() + " (DB: " + dbDir + ", HEAD: " + opened.headCommitId() + ")");
        } catch (Throwable t) {
            opened.close();
            if (t instanceof IOException io) {
                throw io;
            }
            throw new IOException("Fehler beim Starten des VEG-Servers", t);
        }
        notifyStatus();
    }

    public synchronized void stopServer() {
        boolean wasRunning = server != null || db != null;
        if (server != null) {
            try {
                server.close();
            } finally {
                server = null;
            }
        }
        if (db != null) {
            try {
                db.close();
            } finally {
                db = null;
            }
        }
        if (wasRunning) {
            log("Server und Persistenz-Engine sauber gestoppt.");
        }
        notifyStatus();
    }

    public synchronized void restartServer() throws IOException {
        stopServer();
        startServer();
    }

    public synchronized void switchDatabase(Path newDbDir) throws IOException {
        Objects.requireNonNull(newDbDir, "newDbDir must not be null");
        Path normalized = newDbDir.toAbsolutePath().normalize();
        boolean wasRunning = (server != null);
        stopServer();
        this.dbDir = normalized;
        log("Aktives DB-Verzeichnis gewechselt auf: " + normalized);
        if (wasRunning) {
            startServer();
        } else {
            notifyStatus();
        }
    }

    public synchronized void createFreshDatabase(Path newDbDir) throws IOException {
        Objects.requireNonNull(newDbDir, "newDbDir must not be null");
        Path normalized = newDbDir.toAbsolutePath().normalize();
        boolean wasRunning = (server != null);
        stopServer();
        if (Files.exists(normalized)) {
            deleteRecursively(normalized);
        }
        this.dbDir = normalized;
        log("Neue leere VEG-Datenbank vorbereitet: " + normalized);
        if (wasRunning) {
            startServer();
        } else {
            notifyStatus();
        }
    }

    public synchronized void setPort(int newPort) throws IOException {
        int validated = validatePort(newPort);
        if (validated == this.configuredPort) {
            return;
        }
        this.configuredPort = validated;
        log("Server-Port gesetzt auf " + validated);
        if (server != null) {
            restartServer();
        } else {
            notifyStatus();
        }
    }

    public synchronized void setIdStrategy(IdGenerationStrategy strategy) {
        this.idStrategy = Objects.requireNonNull(strategy, "strategy must not be null");
        if (db != null) {
            db.setIdGenerationStrategy(strategy);
        }
        log("ID-Vergabestrategie geändert auf: " + strategy);
        notifyStatus();
    }

    public synchronized void createCheckpoint() {
        requireRunningDb();
        db.createCheckpoint();
        log("Checkpoint erfolgreich geschrieben bei Commit " + db.headCommitId());
        notifyStatus();
    }

    public synchronized void archiveHistoryBefore(String commitIdText) {
        requireRunningDb();
        CommitId cutoff = GraphTabularService.parseCommitId(commitIdText);
        db.archiveHistoryBefore(cutoff);
        log("Kaltarchivierung erfolgreich bis inklusive Commit " + cutoff);
        notifyStatus();
    }

    public synchronized void createBackup(Path backupDir) {
        requireRunningDb();
        db.backupTo(backupDir);
        log("Vollständiges Backup erstellt in: " + backupDir.toAbsolutePath());
    }

    public synchronized void restoreBackup(Path backupDir, Path targetDbDir) throws IOException {
        boolean wasRunning = (server != null);
        stopServer();
        PersistentDiskVersionedDatabase.restoreFrom(backupDir, targetDbDir);
        this.dbDir = targetDbDir.toAbsolutePath().normalize();
        log("Backup aus " + backupDir + " wiederhergestellt nach " + this.dbDir);
        if (wasRunning) {
            startServer();
        } else {
            notifyStatus();
        }
    }

    public synchronized ImportReport importProjectDocs(Path docsRoot, boolean clean) throws IOException {
        boolean wasRunning = (server != null);
        if (clean) {
            stopServer();
            if (Files.exists(dbDir)) {
                deleteRecursively(dbDir);
            }
            Files.createDirectories(dbDir);
            db = PersistentDiskVersionedDatabase.open(dbDir);
            db.setIdGenerationStrategy(idStrategy);
        } else if (db == null) {
            Files.createDirectories(dbDir);
            db = PersistentDiskVersionedDatabase.open(dbDir);
            db.setIdGenerationStrategy(idStrategy);
        }
        ImportReport report = ProjectKnowledgeImporter.importFromDocsRoot(db, docsRoot);
        log(String.format("Projekt-Dokumentation importiert (%s): docs=%d, invariants=%d, adrs=%d, head=%s",
                docsRoot.toAbsolutePath(), report.documents(), report.invariants(), report.adrs(), report.headCommitId()));
        if (wasRunning && server == null) {
            startServer();
        } else {
            notifyStatus();
        }
        return report;
    }

    public synchronized RdfImportResult importRdfFile(Path rdfFile) throws IOException {
        requireRunningDb();
        String content = Files.readString(rdfFile, StandardCharsets.UTF_8);
        RdfFormat format = detectRdfFormat(rdfFile);
        RdfImportResult res;
        CommitId cid;
        try (GraphTransaction tx = db.beginTransaction(
                db.currentBranch(),
                Actor.user("desktop-manager"),
                Reason.of("Import RDF: " + rdfFile.getFileName()),
                null,
                DataCategory.REFERENCE)) {
            res = tx.importRdf(content, format);
            cid = tx.commit();
        }
        log(String.format("RDF-Import (%s) abgeschlossen in %s: objects=%d, edges=%d",
                format, cid, res.importedObjectCount(), res.importedEdgeCount()));
        notifyStatus();
        return res;
    }

    public synchronized RdfExportResult exportRdfFile(Path outFile) throws IOException {
        requireRunningDb();
        RdfFormat format = detectRdfFormat(outFile);
        RdfExportOptions options = format == RdfFormat.N_TRIPLES
                ? RdfExportOptions.nTriplesDefault()
                : RdfExportOptions.turtleDefault();
        RdfExportResult res = RdfGraphExporter.export(db.currentSnapshot(), options);
        if (outFile.toAbsolutePath().getParent() != null) {
            Files.createDirectories(outFile.toAbsolutePath().getParent());
        }
        Files.writeString(outFile, res.content(), StandardCharsets.UTF_8);
        log("RDF-Export (" + format + ", " + res.exportedTripleCount() + " Triples) gespeichert unter " + outFile);
        return res;
    }

    public synchronized String exportVegPatch(String fromCommitStr, String toCommitStr, Path outFile) throws IOException {
        requireRunningDb();
        CommitId from = GraphTabularService.parseCommitId(fromCommitStr);
        CommitId to = GraphTabularService.parseCommitId(toCommitStr);
        String patchText = VegPatchService.exportPatch(db, from, to);
        if (outFile != null) {
            if (outFile.toAbsolutePath().getParent() != null) {
                Files.createDirectories(outFile.toAbsolutePath().getParent());
            }
            Files.writeString(outFile, patchText, StandardCharsets.UTF_8);
            log(".vegpatch (" + from + " -> " + to + ") exportiert nach " + outFile);
        }
        return patchText;
    }

    public synchronized PatchResult applyVegPatch(Path patchFile, PatchApplyMode mode) throws IOException {
        requireRunningDb();
        String patchText = Files.readString(patchFile, StandardCharsets.UTF_8);
        PatchResult result = VegPatchService.applyOrMergePatch(
                db, patchText, db.currentBranch(), mode, Actor.user("desktop-manager"), null);
        if (result.alreadyApplied()) {
            log("Patch " + result.patchId() + " war bereits in Branch '" + result.targetBranch() + "' enthalten.");
        } else if (result.merged()) {
            log("Patch (" + mode + ") erfolgreich übernommen -> Commit " + result.resultCommitId());
        } else {
            log("Merge-Konflikt bei Patch " + result.patchId() + " (" + result.conflicts().size() + " Konflikte).");
        }
        notifyStatus();
        return result;
    }

    @Override
    public void close() {
        stopServer();
    }

    private void requireRunningDb() {
        if (db == null) {
            throw new IllegalStateException("Datenbank ist nicht geöffnet. Bitte zuerst den Server starten.");
        }
    }

    private void log(String message) {
        String entry = "[" + LocalTime.now().format(LOG_TIME) + "] " + message;
        recentLogs.add(entry);
        while (recentLogs.size() > 500) {
            recentLogs.removeFirst();
        }
        for (Consumer<String> l : logListeners) {
            l.accept(entry);
        }
    }

    private void notifyStatus() {
        for (Runnable r : statusListeners) {
            r.run();
        }
    }

    private static int validatePort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Ungültiger Port (0..65535): " + port);
        }
        return port;
    }

    private static RdfFormat detectRdfFormat(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".nt") ? RdfFormat.N_TRIPLES : RdfFormat.TURTLE;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
