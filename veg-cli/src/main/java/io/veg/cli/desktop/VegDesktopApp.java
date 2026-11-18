package io.veg.cli.desktop;

import io.veg.cli.desktop.VegServerController.ServerStatus;
import io.veg.engine.VegPatchService.PatchApplyMode;
import io.veg.engine.VegPatchService.PatchResult;
import io.veg.model.IdGenerationStrategy;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Native Windows desktop control center for managing VEG databases, starting/stopping the
 * embedded HTTP server, running imports/backups/.vegpatch merges, and launching VEG Access Studio.
 */
public final class VegDesktopApp {

    private final VegServerController controller;
    private final boolean autoOpenBrowser;

    private JFrame frame;
    private TrayIcon trayIcon;
    private JLabel statusBadgeLabel;
    private JLabel dbPathValueLabel;
    private JLabel urlValueLabel;
    private JLabel branchCommitValueLabel;
    private JLabel countsValueLabel;
    private JButton toggleServerButton;
    private JButton openStudioButton;
    private JComboBox<IdGenerationStrategy> strategyCombo;
    private JTextArea logArea;
    private JMenuItem startMenuItem;
    private JMenuItem stopMenuItem;
    private boolean updatingCombo;

    public VegDesktopApp(VegServerController controller, boolean autoOpenBrowser) {
        this.controller = controller;
        this.autoOpenBrowser = autoOpenBrowser;
    }

    public static void main(String[] args) {
        executeDesktop(args, System.err);
    }

    public static int runCli(String[] args, PrintStream out, PrintStream err) {
        String[] subArgs = new String[Math.max(0, args.length - 1)];
        if (args.length > 1) {
            System.arraycopy(args, 1, subArgs, 0, subArgs.length);
        }
        return executeDesktop(subArgs, err);
    }

    private static int executeDesktop(String[] args, PrintStream err) {
        Path dbDir = null;
        int port = 8080;
        boolean autoBrowser = true;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if ("--no-browser".equals(flag)) {
                autoBrowser = false;
                continue;
            }
            if (i + 1 >= args.length) {
                err.println("Missing value for option: " + flag);
                return 1;
            }
            String val = args[++i];
            switch (flag) {
                case "--db" -> dbDir = Path.of(val);
                case "--port" -> port = Integer.parseInt(val);
                default -> {
                    err.println("Unknown option: " + flag);
                    return 1;
                }
            }
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        VegServerController ctrl = new VegServerController(dbDir, port);
        Runtime.getRuntime().addShutdownHook(new Thread(ctrl::close, "veg-desktop-shutdown"));

        boolean openOnBoot = autoBrowser;
        SwingUtilities.invokeLater(() -> new VegDesktopApp(ctrl, openOnBoot).showWindow());
        return 0;
    }

    private void showWindow() {
        frame = new JFrame("VEG Server Manager — Versioned Entity Graph");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(820, 580));
        frame.setSize(900, 640);
        frame.setLocationRelativeTo(null);

        BufferedImage logoImage = loadLogoImage();
        if (logoImage != null) {
            frame.setIconImages(List.of(
                    logoImage.getScaledInstance(64, 64, Image.SCALE_SMOOTH),
                    logoImage.getScaledInstance(32, 32, Image.SCALE_SMOOTH),
                    logoImage.getScaledInstance(16, 16, Image.SCALE_SMOOTH)
            ));
        }

        frame.setJMenuBar(buildMenuBar());
        frame.setContentPane(buildMainPanel(logoImage));
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdownAndExit();
            }
        });

        controller.addLogListener(line -> SwingUtilities.invokeLater(() -> appendLogLine(line)));
        controller.addStatusListener(() -> SwingUtilities.invokeLater(this::refreshUi));

        installSystemTray(logoImage);

        Timer pollTimer = new Timer(3000, ignored -> refreshUi());
        pollTimer.start();

        frame.setVisible(true);
        refreshUi();

        runAsync("Starte VEG-Datenbank und HTTP-Server...", () -> {
            controller.startServer();
            return controller.status();
        }, status -> {
            refreshUi();
            if (autoOpenBrowser && status.running()) {
                openStudioBrowser(true);
            }
        });
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu dbMenu = new JMenu("Datenbank");
        JMenuItem openDbItem = new JMenuItem("DB-Verzeichnis öffnen / wechseln...");
        openDbItem.addActionListener(ignored -> chooseExistingOrNewDb(false));
        JMenuItem newDbItem = new JMenuItem("Neue leere Datenbank anlegen...");
        newDbItem.addActionListener(ignored -> chooseExistingOrNewDb(true));
        JMenuItem ckptItem = new JMenuItem("Checkpoint jetzt schreiben");
        ckptItem.addActionListener(ignored -> runAsync("Schreibe Checkpoint...", () -> {
            controller.createCheckpoint();
            return null;
        }, null));
        JMenuItem archiveItem = new JMenuItem("Kaltarchivierung bis Commit...");
        archiveItem.addActionListener(ignored -> promptColdArchive());
        JMenuItem backupItem = new JMenuItem("Backup erstellen...");
        backupItem.addActionListener(ignored -> promptBackup());
        JMenuItem restoreItem = new JMenuItem("Backup wiederherstellen...");
        restoreItem.addActionListener(ignored -> promptRestore());
        JMenuItem exitItem = new JMenuItem("Beenden");
        exitItem.addActionListener(ignored -> shutdownAndExit());

        dbMenu.add(openDbItem);
        dbMenu.add(newDbItem);
        dbMenu.addSeparator();
        dbMenu.add(ckptItem);
        dbMenu.add(archiveItem);
        dbMenu.add(backupItem);
        dbMenu.add(restoreItem);
        dbMenu.addSeparator();
        dbMenu.add(exitItem);

        JMenu serverMenu = new JMenu("Server");
        startMenuItem = new JMenuItem("Server starten");
        startMenuItem.addActionListener(ignored -> runAsync("Starte Server...", () -> {
            controller.startServer();
            return null;
        }, null));
        stopMenuItem = new JMenuItem("Server stoppen");
        stopMenuItem.addActionListener(ignored -> runAsync("Stoppe Server...", () -> {
            controller.stopServer();
            return null;
        }, null));
        JMenuItem restartItem = new JMenuItem("Server neu starten");
        restartItem.addActionListener(ignored -> runAsync("Starte Server neu...", () -> {
            controller.restartServer();
            return null;
        }, null));
        JMenuItem portItem = new JMenuItem("Port ändern...");
        portItem.addActionListener(ignored -> promptPortChange());

        JMenu strategyMenu = new JMenu("ID-Vergabestrategie");
        for (IdGenerationStrategy s : IdGenerationStrategy.values()) {
            JMenuItem item = new JMenuItem(s.name());
            item.addActionListener(ignored -> controller.setIdStrategy(s));
            strategyMenu.add(item);
        }

        serverMenu.add(startMenuItem);
        serverMenu.add(stopMenuItem);
        serverMenu.add(restartItem);
        serverMenu.addSeparator();
        serverMenu.add(portItem);
        serverMenu.add(strategyMenu);

        JMenu dataMenu = new JMenu("Import / Export & Git-Patch");
        JMenuItem importDocsItem = new JMenuItem("Projekt-Dokumentation (.md) in DB importieren...");
        importDocsItem.addActionListener(ignored -> promptImportProjectDocs());
        JMenuItem importRdfItem = new JMenuItem("RDF-Datei (.ttl / .nt) importieren...");
        importRdfItem.addActionListener(ignored -> promptImportRdf());
        JMenuItem exportRdfItem = new JMenuItem("Aktuellen Graph als RDF (.ttl / .nt) exportieren...");
        exportRdfItem.addActionListener(ignored -> promptExportRdf());
        JMenuItem mergePatchItem = new JMenuItem("Git-Patch (.vegpatch) 3-Wege-Merge...");
        mergePatchItem.addActionListener(ignored -> promptApplyPatch(PatchApplyMode.MERGE));
        JMenuItem applyPatchItem = new JMenuItem("Git-Patch (.vegpatch) direkt auf HEAD anwenden...");
        applyPatchItem.addActionListener(ignored -> promptApplyPatch(PatchApplyMode.APPLY));
        JMenuItem exportPatchItem = new JMenuItem("Commit-Diff als .vegpatch exportieren...");
        exportPatchItem.addActionListener(ignored -> promptExportPatch());

        dataMenu.add(importDocsItem);
        dataMenu.add(importRdfItem);
        dataMenu.add(exportRdfItem);
        dataMenu.addSeparator();
        dataMenu.add(mergePatchItem);
        dataMenu.add(applyPatchItem);
        dataMenu.add(exportPatchItem);

        JMenu browserMenu = new JMenu("Browser & Ansicht");
        JMenuItem appWinItem = new JMenuItem("VEG Access Studio im App-Fenster öffnen");
        appWinItem.addActionListener(ignored -> openStudioBrowser(true));
        JMenuItem defBrowserItem = new JMenuItem("VEG Access Studio im Standard-Browser öffnen");
        defBrowserItem.addActionListener(ignored -> openStudioBrowser(false));
        JMenuItem copyUrlItem = new JMenuItem("Studio-URL kopieren");
        copyUrlItem.addActionListener(ignored -> copyStudioUrl());
        JMenuItem refreshItem = new JMenuItem("Status aktualisieren");
        refreshItem.addActionListener(ignored -> refreshUi());

        browserMenu.add(appWinItem);
        browserMenu.add(defBrowserItem);
        browserMenu.add(copyUrlItem);
        browserMenu.addSeparator();
        browserMenu.add(refreshItem);

        bar.add(dbMenu);
        bar.add(serverMenu);
        bar.add(dataMenu);
        bar.add(browserMenu);
        return bar;
    }

    private JPanel buildMainPanel(BufferedImage logoImage) {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Header Panel
        JPanel header = new JPanel(new BorderLayout(12, 6));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD0D7DE)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        header.setBackground(Color.WHITE);

        JPanel brandBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brandBox.setOpaque(false);
        if (logoImage != null) {
            brandBox.add(new JLabel(new ImageIcon(logoImage.getScaledInstance(44, 44, Image.SCALE_SMOOTH))));
        }
        JPanel titleStack = new JPanel();
        titleStack.setOpaque(false);
        titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("VEG Server Manager & Studio Launcher");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        statusBadgeLabel = new JLabel("Initialisiere...");
        statusBadgeLabel.setFont(statusBadgeLabel.getFont().deriveFont(Font.BOLD, 12f));
        titleStack.add(title);
        titleStack.add(Box.createVerticalStrut(3));
        titleStack.add(statusBadgeLabel);
        brandBox.add(titleStack);

        JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        topButtons.setOpaque(false);
        openStudioButton = new JButton("Access Studio öffnen");
        openStudioButton.addActionListener(ignored -> openStudioBrowser(true));
        toggleServerButton = new JButton("Server stoppen");
        toggleServerButton.addActionListener(ignored -> {
            if (controller.status().running()) {
                runAsync("Stoppe Server...", () -> {
                    controller.stopServer();
                    return null;
                }, null);
            } else {
                runAsync("Starte Server...", () -> {
                    controller.startServer();
                    return null;
                }, null);
            }
        });
        JButton switchDbButton = new JButton("DB wechseln...");
        switchDbButton.addActionListener(ignored -> chooseExistingOrNewDb(false));

        topButtons.add(openStudioButton);
        topButtons.add(toggleServerButton);
        topButtons.add(switchDbButton);

        header.add(brandBox, BorderLayout.WEST);
        header.add(topButtons, BorderLayout.EAST);

        // Metrics + Quick Actions
        JPanel center = new JPanel(new BorderLayout(0, 10));
        JPanel infoCard = new JPanel(new GridBagLayout());
        infoCard.setBackground(Color.WHITE);
        infoCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Aktive Datenbank & Engine-Status"),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));

        dbPathValueLabel = new JLabel("-");
        urlValueLabel = new JLabel("-");
        branchCommitValueLabel = new JLabel("-");
        countsValueLabel = new JLabel("-");
        strategyCombo = new JComboBox<>(IdGenerationStrategy.values());
        strategyCombo.addActionListener(ignored -> {
            if (!updatingCombo) {
                IdGenerationStrategy selected = (IdGenerationStrategy) strategyCombo.getSelectedItem();
                if (selected != null) {
                    controller.setIdStrategy(selected);
                }
            }
        });

        addInfoRow(infoCard, 0, "Datenbank-Ordner:", dbPathValueLabel);
        addInfoRow(infoCard, 1, "Studio-URL:", urlValueLabel);
        addInfoRow(infoCard, 2, "Branch / HEAD:", branchCommitValueLabel);
        addInfoRow(infoCard, 3, "Bestand:", countsValueLabel);
        addInfoRow(infoCard, 4, "ID-Strategie:", strategyCombo);

        JPanel quickBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        JButton ckptBtn = new JButton("Checkpoint schreiben");
        ckptBtn.addActionListener(ignored -> runAsync("Schreibe Checkpoint...", () -> {
            controller.createCheckpoint();
            return null;
        }, null));
        JButton backupBtn = new JButton("Backup...");
        backupBtn.addActionListener(ignored -> promptBackup());
        JButton patchMergeBtn = new JButton(".vegpatch Merge...");
        patchMergeBtn.addActionListener(ignored -> promptApplyPatch(PatchApplyMode.MERGE));
        JButton importDocsBtn = new JButton("Projekt-Docs importieren...");
        importDocsBtn.addActionListener(ignored -> promptImportProjectDocs());
        JButton clearLogBtn = new JButton("Log leeren");
        clearLogBtn.addActionListener(ignored -> logArea.setText(""));

        quickBar.add(ckptBtn);
        quickBar.add(backupBtn);
        quickBar.add(patchMergeBtn);
        quickBar.add(importDocsBtn);
        quickBar.add(clearLogBtn);

        center.add(infoCard, BorderLayout.NORTH);
        center.add(quickBar, BorderLayout.CENTER);

        // Console Log
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        for (String line : controller.recentLogs()) {
            logArea.append(line + "\n");
        }
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Server- & Transaktionsprotokoll"));
        logScroll.setPreferredSize(new Dimension(860, 260));

        JPanel topStack = new JPanel(new BorderLayout(0, 10));
        topStack.add(header, BorderLayout.NORTH);
        topStack.add(center, BorderLayout.CENTER);

        root.add(topStack, BorderLayout.NORTH);
        root.add(logScroll, BorderLayout.CENTER);
        return root;
    }

    private static void addInfoRow(JPanel panel, int row, String labelText, java.awt.Component valueComp) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.WEST;
        left.insets = new Insets(4, 2, 4, 14);
        JLabel lbl = new JLabel(labelText);
        lbl.setForeground(new Color(0x475569));
        panel.add(lbl, left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1.0;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.anchor = GridBagConstraints.WEST;
        right.insets = new Insets(4, 2, 4, 2);
        panel.add(valueComp, right);
    }

    private void refreshUi() {
        if (frame == null) {
            return;
        }
        ServerStatus st = controller.status();
        if (st.running()) {
            statusBadgeLabel.setText("● LÄUFT auf Port " + st.activePort());
            statusBadgeLabel.setForeground(new Color(0x0E7A4D));
            toggleServerButton.setText("Server stoppen");
            openStudioButton.setEnabled(true);
            urlValueLabel.setText(st.studioUrl());
            branchCommitValueLabel.setText(st.currentBranch() + "  (" + st.headCommitId() + ")");
            countsValueLabel.setText(String.format(
                    "%d Entities · %d Edges · %d Commits · %d Branches",
                    st.entityCount(), st.edgeCount(), st.commitCount(), st.branchCount()
            ));
        } else {
            statusBadgeLabel.setText("○ GESTOPPT (Port " + st.configuredPort() + ")");
            statusBadgeLabel.setForeground(new Color(0xB42318));
            toggleServerButton.setText("Server starten");
            openStudioButton.setEnabled(false);
            urlValueLabel.setText("Server gestoppt");
            branchCommitValueLabel.setText("-");
            countsValueLabel.setText("-");
        }
        dbPathValueLabel.setText(st.dbDir().toString());
        startMenuItem.setEnabled(!st.running());
        stopMenuItem.setEnabled(st.running());

        updatingCombo = true;
        try {
            strategyCombo.setSelectedItem(st.idStrategy());
        } finally {
            updatingCombo = false;
        }
    }

    private void appendLogLine(String line) {
        if (logArea != null) {
            logArea.append(line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
    }

    private void chooseExistingOrNewDb(boolean createNew) {
        JFileChooser chooser = new JFileChooser(controller.status().dbDir().getParent() != null
                ? controller.status().dbDir().getParent().toFile()
                : new java.io.File("."));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(createNew ? "Verzeichnis für neue leere VEG-DB wählen" : "VEG-Datenbankverzeichnis öffnen");
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path chosen = chooser.getSelectedFile().toPath();
        if (createNew && Files.exists(chosen)) {
            int confirm = JOptionPane.showConfirmDialog(
                    frame,
                    "Verzeichnis existiert bereits:\n" + chosen + "\nInhalt überschreiben und neu initialisieren?",
                    "Neue Datenbank",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }
        runAsync(createNew ? "Erstelle neue Datenbank..." : "Wechsle Datenbank...", () -> {
            if (createNew) {
                controller.createFreshDatabase(chosen);
            } else {
                controller.switchDatabase(chosen);
            }
            return null;
        }, null);
    }

    private void promptPortChange() {
        String current = Integer.toString(controller.status().configuredPort());
        String input = JOptionPane.showInputDialog(frame, "HTTP-Port für VEG Access Studio (0 = automatisch):", current);
        if (input == null || input.isBlank()) {
            return;
        }
        int newPort = Integer.parseInt(input.trim());
        runAsync("Ändere Port auf " + newPort + "...", () -> {
            controller.setPort(newPort);
            return null;
        }, null);
    }

    private void promptColdArchive() {
        String input = JOptionPane.showInputDialog(frame, "Kaltarchivierung bis einschließlich Commit-ID (z. B. c4):", "c4");
        if (input == null || input.isBlank()) {
            return;
        }
        runAsync("Archiviere bis " + input.trim() + "...", () -> {
            controller.archiveHistoryBefore(input.trim());
            return null;
        }, null);
    }

    private void promptBackup() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Zielverzeichnis für Backup wählen");
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path backupDir = chooser.getSelectedFile().toPath();
        runAsync("Erstelle Backup...", () -> {
            controller.createBackup(backupDir);
            return null;
        }, null);
    }

    private void promptRestore() {
        JFileChooser srcChooser = new JFileChooser();
        srcChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        srcChooser.setDialogTitle("Quell-Backup-Verzeichnis wählen");
        if (srcChooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path backupDir = srcChooser.getSelectedFile().toPath();
        Path targetDir = controller.status().dbDir();
        int confirm = JOptionPane.showConfirmDialog(
                frame,
                "Backup aus " + backupDir + " in aktuelles Verzeichnis wiederherstellen?\n" + targetDir,
                "Backup wiederherstellen",
                JOptionPane.YES_NO_OPTION
        );
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        runAsync("Stelle Backup wieder her...", () -> {
            controller.restoreBackup(backupDir, targetDir);
            return null;
        }, null);
    }

    private void promptImportProjectDocs() {
        JFileChooser chooser = new JFileChooser(new java.io.File("."));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Projekt-Wurzelverzeichnis mit README.md & docs/ wählen");
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path docsRoot = chooser.getSelectedFile().toPath();
        int cleanChoice = JOptionPane.showConfirmDialog(
                frame,
                "Aktuelle Datenbank vor dem Import neu aufbauen (--clean)?",
                "Projekt-Dokumentation importieren",
                JOptionPane.YES_NO_CANCEL_OPTION
        );
        if (cleanChoice == JOptionPane.CANCEL_OPTION) {
            return;
        }
        boolean clean = (cleanChoice == JOptionPane.YES_OPTION);
        runAsync("Importiere Projekt-Dokumentation...", () -> controller.importProjectDocs(docsRoot, clean), null);
    }

    private void promptImportRdf() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("RDF Turtle / N-Triples (*.ttl, *.nt)", "ttl", "nt"));
        chooser.setDialogTitle("RDF-Datei importieren");
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path rdfFile = chooser.getSelectedFile().toPath();
        runAsync("Importiere RDF...", () -> controller.importRdfFile(rdfFile), null);
    }

    private void promptExportRdf() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("RDF Turtle / N-Triples (*.ttl, *.nt)", "ttl", "nt"));
        chooser.setSelectedFile(new java.io.File("veg-export.ttl"));
        chooser.setDialogTitle("Graph als RDF exportieren");
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path outFile = chooser.getSelectedFile().toPath();
        runAsync("Exportiere RDF...", () -> controller.exportRdfFile(outFile), null);
    }

    private void promptExportPatch() {
        String from = JOptionPane.showInputDialog(frame, "Basis-Commit (von):", "c1");
        if (from == null || from.isBlank()) {
            return;
        }
        String to = JOptionPane.showInputDialog(frame, "Ziel-Commit (nach):", controller.status().headCommitId());
        if (to == null || to.isBlank()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("VEG Patch (*.vegpatch)", "vegpatch"));
        chooser.setSelectedFile(new java.io.File("diff-" + from.trim() + "-" + to.trim() + ".vegpatch"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path out = chooser.getSelectedFile().toPath();
        runAsync("Exportiere .vegpatch...", () -> controller.exportVegPatch(from.trim(), to.trim(), out), null);
    }

    private void promptApplyPatch(PatchApplyMode mode) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("VEG Patch (*.vegpatch)", "vegpatch"));
        chooser.setDialogTitle(mode == PatchApplyMode.MERGE ? ".vegpatch für 3-Wege-Merge wählen" : ".vegpatch zum Anwenden wählen");
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path patchFile = chooser.getSelectedFile().toPath();
        runAsync("Verarbeite .vegpatch (" + mode + ")...", () -> controller.applyVegPatch(patchFile, mode), result -> {
            if (result instanceof PatchResult pr && !pr.merged()) {
                JOptionPane.showMessageDialog(
                        frame,
                        "Merge-Konflikt erkannt (" + pr.conflicts().size() + " Konflikte). Ziel-Branch unverändert.",
                        "Patch Merge-Konflikt",
                        JOptionPane.WARNING_MESSAGE
                );
            }
        });
    }

    private void openStudioBrowser(boolean preferAppWindow) {
        ServerStatus st = controller.status();
        if (!st.running()) {
            JOptionPane.showMessageDialog(frame, "Der Server läuft aktuell nicht.", "Hinweis", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String url = st.studioUrl();
        try {
            if (preferAppWindow && tryLaunchChromiumAppWindow(url)) {
                return;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            new ProcessBuilder("cmd", "/c", "start", "", url).start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    frame,
                    "Browser konnte nicht automatisch geöffnet werden:\n" + url + "\n(" + ex.getMessage() + ")",
                    "Browser-Hinweis",
                    JOptionPane.WARNING_MESSAGE
            );
        }
    }

    private static boolean tryLaunchChromiumAppWindow(String url) {
        List<Path> candidates = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String localAppData = System.getenv("LOCALAPPDATA");
        if (programFilesX86 != null) {
            candidates.add(Path.of(programFilesX86, "Microsoft/Edge/Application/msedge.exe"));
        }
        if (programFiles != null) {
            candidates.add(Path.of(programFiles, "Microsoft/Edge/Application/msedge.exe"));
            candidates.add(Path.of(programFiles, "Google/Chrome/Application/chrome.exe"));
        }
        if (localAppData != null) {
            candidates.add(Path.of(localAppData, "Google/Chrome/Application/chrome.exe"));
        }
        for (Path exe : candidates) {
            if (Files.isRegularFile(exe)) {
                try {
                    new ProcessBuilder(exe.toString(), "--app=" + url).start();
                    return true;
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    private void copyStudioUrl() {
        ServerStatus st = controller.status();
        String url = st.running() ? st.studioUrl() : "http://127.0.0.1:" + st.configuredPort() + "/studio/index.html";
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
    }

    private void installSystemTray(BufferedImage logoImage) {
        if (!SystemTray.isSupported() || logoImage == null) {
            return;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu menu = new PopupMenu();
            MenuItem openStudio = new MenuItem("VEG Access Studio öffnen");
            openStudio.addActionListener(ignored -> openStudioBrowser(true));
            MenuItem showMgr = new MenuItem("Server Manager anzeigen");
            showMgr.addActionListener(ignored -> SwingUtilities.invokeLater(() -> {
                frame.setVisible(true);
                frame.toFront();
            }));
            MenuItem exit = new MenuItem("Beenden");
            exit.addActionListener(ignored -> shutdownAndExit());
            menu.add(openStudio);
            menu.add(showMgr);
            menu.addSeparator();
            menu.add(exit);

            trayIcon = new TrayIcon(logoImage, "VEG Access Studio", menu);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);
        } catch (Exception ignored) {
        }
    }

    private void shutdownAndExit() {
        if (trayIcon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
        controller.close();
        if (frame != null) {
            frame.dispose();
        }
        System.exit(0);
    }

    private <T> void runAsync(String description, Callable<T> work, java.util.function.Consumer<T> onSuccess) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                try {
                    T result = get();
                    refreshUi();
                    if (onSuccess != null) {
                        onSuccess.accept(result);
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    appendLogLine("[FEHLER] " + description + " -> " + cause.getMessage());
                    JOptionPane.showMessageDialog(
                            frame,
                            cause.getMessage(),
                            description,
                            JOptionPane.ERROR_MESSAGE
                    );
                    refreshUi();
                }
            }
        }.execute();
    }

    private static BufferedImage loadLogoImage() {
        try (InputStream in = VegDesktopApp.class.getResourceAsStream("/studio/logo.png")) {
            return in != null ? ImageIO.read(in) : null;
        } catch (IOException ignored) {
            return null;
        }
    }
}
