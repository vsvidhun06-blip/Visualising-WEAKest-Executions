package com.weakest.ui;

import com.weakest.model.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.Stage;
import org.graphstream.graph.*;
import org.graphstream.graph.implementations.MultiGraph;
import org.graphstream.ui.fx_viewer.FxDefaultView;
import org.graphstream.ui.fx_viewer.FxViewer;

import java.util.*;
import java.util.concurrent.*;


public class ComparisonWindow {

    // ── Layout constants ──────────────────────────────────────────────
    private static final double COLUMN_SPACING = 260.0;
    private static final double ROW_SPACING    = 130.0;

    // ── State ─────────────────────────────────────────────────────────
    private final Program program;
    private final String  litmusName;
    private Stage         stage;

    // Results (written by background thread, read on FX thread via Platform.runLater)
    private List<ExecutionEnumerator.EnumeratedExecution> scResults;
    private List<ExecutionEnumerator.EnumeratedExecution> weakestResults;

    // Graph panels
    private Graph    scGraph, wkGraph;
    private FxViewer scViewer, wkViewer;

    // UI references
    private Label     statusLabel;
    private ProgressIndicator spinner;
    private VBox      scOutcomeBox, wkOutcomeBox;
    private Label     scCountLabel, wkCountLabel;
    private StackPane scGraphStack, wkGraphStack;
    private Label     scSelectedLabel, wkSelectedLabel;

    // ── Constructor ───────────────────────────────────────────────────

    public ComparisonWindow(Program program, String litmusName) {
        System.setProperty("org.graphstream.ui", "javafx");
        this.program    = program;
        this.litmusName = litmusName;
        buildStage();
    }

    // ── Show ──────────────────────────────────────────────────────────

    public void show() {
        stage.show();
        stage.setMaximized(true);
        stage.toFront();
        stage.requestFocus();
        startEnumeration();
    }

    // ── Build Stage ───────────────────────────────────────────────────

    private void buildStage() {
        stage = new Stage();
        stage.setTitle("⚖  SC vs WEAKEST — " + litmusName);

        // ── Header ────────────────────────────────────────────────────
        HBox header = new HBox(14);
        header.setPadding(new Insets(12, 16, 12, 16));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color:#313244;");

        Label title = new Label("⚖  SC vs WEAKEST Comparison — " + litmusName);
        title.setFont(Font.font("Arial", FontWeight.BOLD, 20));
        title.setTextFill(Color.web("#cdd6f4"));

        statusLabel = new Label("Enumerating…");
        statusLabel.setFont(Font.font("Arial", FontPosture.ITALIC, 13));
        statusLabel.setTextFill(Color.web("#f9e2af"));

        spinner = new ProgressIndicator();
        spinner.setPrefSize(22, 22);

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        Button closeBtn = new Button("✕ Close");
        closeBtn.setStyle("-fx-background-color:#f38ba8;-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:6 14;");
        closeBtn.setOnAction(e -> stage.close());

        header.getChildren().addAll(title, sp, spinner, statusLabel, closeBtn);

        // ── Outcome tables (top half) ─────────────────────────────────
        scOutcomeBox  = new VBox(4);
        wkOutcomeBox  = new VBox(4);
        scCountLabel  = outcomeCountLabel("#89b4fa", "SC");
        wkCountLabel  = outcomeCountLabel("#cba6f7", "WEAKEST");

        VBox scPanel = buildOutcomePanel("🔵  Sequential Consistency (SC)", "#89b4fa",
                scCountLabel, scOutcomeBox);
        VBox wkPanel = buildOutcomePanel("⚡  WEAKEST", "#cba6f7",
                wkCountLabel, wkOutcomeBox);

        HBox tables = new HBox(1, scPanel, wkPanel);
        HBox.setHgrow(scPanel, Priority.ALWAYS);
        HBox.setHgrow(wkPanel, Priority.ALWAYS);
        tables.setMinHeight(160);
        tables.setMaxHeight(280);
        tables.setPrefHeight(240);

        // ── Graph panels (bottom half) ────────────────────────────────
        scGraph  = new MultiGraph("CmpSC");
        wkGraph  = new MultiGraph("CmpWK");
        scGraph.setAttribute("ui.stylesheet", graphStyle());
        wkGraph.setAttribute("ui.stylesheet", graphStyle());
        scGraph.setAttribute("ui.quality"); scGraph.setAttribute("ui.antialias");
        wkGraph.setAttribute("ui.quality"); wkGraph.setAttribute("ui.antialias");

        scViewer = new FxViewer(scGraph, FxViewer.ThreadingModel.GRAPH_IN_GUI_THREAD);
        wkViewer = new FxViewer(wkGraph, FxViewer.ThreadingModel.GRAPH_IN_GUI_THREAD);
        scViewer.disableAutoLayout();
        wkViewer.disableAutoLayout();

        FxDefaultView scView = (FxDefaultView) scViewer.addDefaultView(false);
        FxDefaultView wkView = (FxDefaultView) wkViewer.addDefaultView(false);
        scView.setMinSize(100, 100);
        wkView.setMinSize(100, 100);
        scView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        wkView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(scView, Priority.ALWAYS);
        HBox.setHgrow(wkView, Priority.ALWAYS);
        VBox.setVgrow(scView, Priority.ALWAYS);
        VBox.setVgrow(wkView, Priority.ALWAYS);

        scSelectedLabel = graphCaption("Click an SC outcome above to view its execution graph", "#89b4fa");
        wkSelectedLabel = graphCaption("Click a WEAKEST outcome above to view its execution graph", "#cba6f7");

        scGraphStack = graphPanel(scView, buildLegend(), scSelectedLabel, "#89b4fa", "SC");
        wkGraphStack = graphPanel(wkView, buildLegend(), wkSelectedLabel, "#cba6f7", "WEAKEST");

        HBox graphs = new HBox(1, scGraphStack, wkGraphStack);
        HBox.setHgrow(scGraphStack, Priority.ALWAYS);
        HBox.setHgrow(wkGraphStack, Priority.ALWAYS);
        graphs.setMaxHeight(Double.MAX_VALUE);

        // ── Root ──────────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#1e1e2e;");
        root.setTop(header);
        VBox center = new VBox(0, tables, divider(), graphs);
        VBox.setVgrow(graphs, Priority.ALWAYS);
        center.setMaxHeight(Double.MAX_VALUE);
        root.setCenter(center);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setMaximized(true);
    }

    // ── Background enumeration ────────────────────────────────────────

    private void startEnumeration() {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ComparisonEnumerator");
            t.setDaemon(true);
            return t;
        });

        exec.submit(() -> {
            try {
                ExecutionEnumerator enumerator = new ExecutionEnumerator();
                // Save global ID counter state — enumeration uses forceId so it doesn't
                // affect the main counter, but we restore to be safe
                int counterBefore = Event.peekCounter();

                List<ExecutionEnumerator.EnumeratedExecution> sc =
                        enumerator.enumerateSC(program);
                List<ExecutionEnumerator.EnumeratedExecution> wk =
                        enumerator.enumerateWeakest(program);

                Event.resetCounterTo(counterBefore);

                Platform.runLater(() -> onEnumerationDone(sc, wk));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("❌ Error: " + ex.getMessage());
                    spinner.setVisible(false);
                    ex.printStackTrace();
                });
            }
        });
        exec.shutdown();
    }

    private void onEnumerationDone(
            List<ExecutionEnumerator.EnumeratedExecution> sc,
            List<ExecutionEnumerator.EnumeratedExecution> wk) {
        this.scResults      = sc;
        this.weakestResults = wk;

        spinner.setVisible(false);

        // Group by outcome label
        Map<String, List<ExecutionEnumerator.EnumeratedExecution>> scByOutcome =
                groupByOutcome(sc);
        Map<String, List<ExecutionEnumerator.EnumeratedExecution>> wkByOutcome =
                groupByOutcome(wk);

        // Identify outcomes only in WEAKEST (weak-memory extras)
        Set<String> scKeys = scByOutcome.keySet();
        Set<String> weakOnly = new HashSet<>(wkByOutcome.keySet());
        weakOnly.removeAll(scKeys);

        // Build outcome rows
        buildOutcomeRows(scOutcomeBox, scByOutcome, weakOnly, false);
        buildOutcomeRows(wkOutcomeBox, wkByOutcome, weakOnly, true);

        int scUnique  = scByOutcome.size();
        int wkUnique  = wkByOutcome.size();
        int extraWeak = weakOnly.size();

        scCountLabel.setText(scUnique + " distinct outcome" + plural(scUnique)
                + "  (" + sc.size() + " executions)");
        wkCountLabel.setText(wkUnique + " distinct outcome" + plural(wkUnique)
                + "  (" + wk.size() + " executions)"
                + (extraWeak > 0 ? "  ⚡ +" + extraWeak + " weak-only" : ""));

        String capped = sc.size() >= ExecutionEnumerator.MAX_EXECUTIONS
                || wk.size() >= ExecutionEnumerator.MAX_EXECUTIONS
                ? "  ⚠ capped at " + ExecutionEnumerator.MAX_EXECUTIONS : "";

        statusLabel.setText("Done." + capped
                + "  SC: " + scUnique + " outcomes"
                + "  |  WEAKEST: " + wkUnique + " outcomes"
                + (extraWeak > 0 ? "  ⚡ " + extraWeak + " weak-only!" : ""));
        statusLabel.setTextFill(extraWeak > 0
                ? Color.web("#f9e2af") : Color.web("#a6e3a1"));
    }

    // ── Outcome rows ──────────────────────────────────────────────────

    private void buildOutcomeRows(
            VBox box,
            Map<String, List<ExecutionEnumerator.EnumeratedExecution>> byOutcome,
            Set<String> weakOnly,
            boolean isWeakest) {

        box.getChildren().clear();
        List<String> sorted = new ArrayList<>(byOutcome.keySet());
        sorted.sort(Comparator.naturalOrder());

        for (String outcome : sorted) {
            List<ExecutionEnumerator.EnumeratedExecution> execs = byOutcome.get(outcome);
            boolean isWeak = weakOnly.contains(outcome);

            HBox row = new HBox(10);
            row.setPadding(new Insets(7, 12, 7, 12));
            row.setAlignment(Pos.CENTER_LEFT);
            row.setStyle("-fx-background-color:" + (isWeak ? "#2a1e1e" : "#1e2a1e")
                    + ";-fx-border-color:" + (isWeak ? "#f38ba8" : "#a6e3a1")
                    + ";-fx-border-width:0 0 0 4;-fx-cursor:hand;");

            Label outLbl = new Label(outcome);
            outLbl.setFont(Font.font("Monospaced", FontWeight.BOLD, 14));
            outLbl.setTextFill(Color.web(isWeak ? "#f38ba8" : "#a6e3a1"));
            outLbl.setMinWidth(160);

            Label cntLbl = new Label("× " + execs.size());
            cntLbl.setFont(Font.font("Arial", 12));
            cntLbl.setTextFill(Color.web("#6c7086"));

            Label tagLbl = new Label(isWeak ? "⚡ WEAK ONLY" : "✅ SC");
            tagLbl.setFont(Font.font("Arial", FontWeight.BOLD, 11));
            tagLbl.setTextFill(Color.web(isWeak ? "#f38ba8" : "#a6e3a1"));
            tagLbl.setStyle("-fx-background-color:" + (isWeak ? "#3a1e1e" : "#1e3a1e")
                    + ";-fx-background-radius:4;-fx-padding:2 6;");

            Label clickLbl = new Label("click to view graph");
            clickLbl.setFont(Font.font("Arial", FontPosture.ITALIC, 11));
            clickLbl.setTextFill(Color.web("#45475a"));

            Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
            row.getChildren().addAll(outLbl, cntLbl, tagLbl, sp, clickLbl);

            // Hover effect
            row.setOnMouseEntered(e -> row.setStyle(
                    "-fx-background-color:" + (isWeak ? "#3a2a2a" : "#2a3a2a")
                            + ";-fx-border-color:" + (isWeak ? "#f38ba8" : "#a6e3a1")
                            + ";-fx-border-width:0 0 0 4;-fx-cursor:hand;"));
            row.setOnMouseExited(e -> row.setStyle(
                    "-fx-background-color:" + (isWeak ? "#2a1e1e" : "#1e2a1e")
                            + ";-fx-border-color:" + (isWeak ? "#f38ba8" : "#a6e3a1")
                            + ";-fx-border-width:0 0 0 4;-fx-cursor:hand;"));

            ExecutionEnumerator.EnumeratedExecution first = execs.get(0);
            row.setOnMouseClicked(e -> {
                if (isWeakest) {
                    loadGraph(wkGraph, first.snap(), program);
                    wkSelectedLabel.setText("Showing: " + outcome
                            + "  (" + execs.size() + " execution"
                            + plural(execs.size()) + ")");
                } else {
                    loadGraph(scGraph, first.snap(), program);
                    scSelectedLabel.setText("Showing: " + outcome
                            + "  (" + execs.size() + " execution"
                            + plural(execs.size()) + ")");
                }
            });

            box.getChildren().add(row);
        }

        if (sorted.isEmpty()) {
            Label empty = new Label("No executions found.");
            empty.setTextFill(Color.web("#6c7086"));
            empty.setPadding(new Insets(12));
            box.getChildren().add(empty);
        }
    }

    // ── Graph rendering ────────────

    private void loadGraph(Graph g, ExecutionEnumerator.SnapData snap, Program program) {
        g.clear();
        g.setAttribute("ui.stylesheet", graphStyle());
        g.setAttribute("ui.quality");
        g.setAttribute("ui.antialias");

        // Build id→rec map
        Map<Integer, ExecutionEnumerator.EventRec> idToRec = new HashMap<>();
        for (var rec : snap.events()) idToRec.put(rec.id(), rec);

        // Count events per thread for Y layout
        Map<Integer, Integer> rowCounter = new HashMap<>();
        int maxTid = snap.events().stream().mapToInt(ExecutionEnumerator.EventRec::threadId).max().orElse(0);

        for (var rec : snap.events()) {
            String nodeId = "e" + rec.id();
            Node n = g.addNode(nodeId);
            String css = switch (rec.type()) {
                case "INIT"  -> "init";
                case "READ"  -> "read";
                default      -> "write";
            };
            n.setAttribute("ui.class", css);

            // Label
            String label = switch (rec.type()) {
                case "INIT"  -> "init(" + rec.variable() + "=" + rec.value() + ")";
                case "READ"  -> rec.localVar() + "=read(" + rec.variable() + ","
                        + rec.memOrder().toLowerCase() + ")=" + rec.value();
                default      -> "write(" + rec.variable() + "=" + rec.value() + ","
                        + rec.memOrder().toLowerCase() + ")";
            };
            n.setAttribute("ui.label", label);

            int row = rowCounter.getOrDefault(rec.threadId(), 0);
            rowCounter.put(rec.threadId(), row + 1);

            double x = (rec.threadId() - maxTid / 2.0) * COLUMN_SPACING;
            double y = -row * ROW_SPACING;
            n.setAttribute("xyz", x, y, 0);
        }

        // po edges
        snap.po().forEach((fromId, toIds) -> toIds.forEach(toId -> {
            String eid = "po" + fromId + "_" + toId;
            if (g.getEdge(eid) == null && g.getNode("e"+fromId) != null && g.getNode("e"+toId) != null) {
                Edge e = g.addEdge(eid, "e"+fromId, "e"+toId, true);
                e.setAttribute("ui.class", "po"); e.setAttribute("ui.label", "po");
            }
        }));

        // rf edges
        snap.rf().forEach((readId, writeId) -> {
            String eid = "rf" + writeId + "_" + readId;
            if (g.getEdge(eid) == null && g.getNode("e"+writeId) != null && g.getNode("e"+readId) != null) {
                Edge e = g.addEdge(eid, "e"+writeId, "e"+readId, true);
                e.setAttribute("ui.class", "rf"); e.setAttribute("ui.label", "rf");
            }
        });

        // co edges
        snap.co().forEach((var, ids) -> {
            for (int i = 0; i < ids.size() - 1; i++) {
                int a = ids.get(i), b = ids.get(i+1);
                String eid = "co" + a + "_" + b;
                if (g.getEdge(eid) == null && g.getNode("e"+a) != null && g.getNode("e"+b) != null) {
                    Edge e = g.addEdge(eid, "e"+a, "e"+b, true);
                    e.setAttribute("ui.class", "co"); e.setAttribute("ui.label", "co");
                }
            }
        });

        // sw edges
        snap.sw().forEach(pair -> {
            String[] parts = pair.split("_");
            if (parts.length == 2) {
                String eid = "sw" + pair;
                if (g.getEdge(eid) == null && g.getNode("e"+parts[0]) != null && g.getNode("e"+parts[1]) != null) {
                    Edge e = g.addEdge(eid, "e"+parts[0], "e"+parts[1], true);
                    e.setAttribute("ui.class", "sw"); e.setAttribute("ui.label", "sw");
                }
            }
        });
    }

    // ── UI helpers ────────

    private Map<String, List<ExecutionEnumerator.EnumeratedExecution>> groupByOutcome(
            List<ExecutionEnumerator.EnumeratedExecution> execs) {
        Map<String, List<ExecutionEnumerator.EnumeratedExecution>> map = new LinkedHashMap<>();
        for (var ex : execs)
            map.computeIfAbsent(ex.outcomeLabel(), k -> new ArrayList<>()).add(ex);
        return map;
    }

    private VBox buildOutcomePanel(String heading, String accent,
                                   Label countLabel, VBox outcomesBox) {
        VBox panel = new VBox(0);
        panel.setStyle("-fx-background-color:#181825;-fx-border-color:#313244;-fx-border-width:0 1 0 0;");

        // Header bar
        HBox hdr = new HBox(8);
        hdr.setPadding(new Insets(10, 14, 10, 14));
        hdr.setAlignment(Pos.CENTER_LEFT);
        hdr.setStyle("-fx-background-color:#1e1e2e;-fx-border-color:" + accent
                + ";-fx-border-width:0 0 2 0;");
        Label hl = new Label(heading);
        hl.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        hl.setTextFill(Color.web(accent));
        hdr.getChildren().addAll(hl);

        // Count label
        countLabel.setFont(Font.font("Arial", FontPosture.ITALIC, 12));
        countLabel.setTextFill(Color.web("#6c7086"));
        countLabel.setPadding(new Insets(4, 14, 4, 14));

        ScrollPane scroll = new ScrollPane(outcomesBox);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:#181825;-fx-background:#181825;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        panel.getChildren().addAll(hdr, countLabel, scroll);
        return panel;
    }

    private StackPane graphPanel(FxDefaultView view, VBox legend,
                                 Label caption, String accent, String label) {
        VBox legendBox = legend;
        StackPane stack = new StackPane(view, legendBox);
        StackPane.setAlignment(legendBox, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(legendBox, new Insets(0, 14, 14, 0));

        // Panel label top-left
        Label panelLbl = new Label(label);
        panelLbl.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        panelLbl.setTextFill(Color.web(accent));
        panelLbl.setStyle("-fx-background-color:rgba(30,30,46,0.82);-fx-padding:4 10;-fx-background-radius:0 0 6 0;");
        StackPane.setAlignment(panelLbl, Pos.TOP_LEFT);
        StackPane.setMargin(panelLbl, new Insets(0, 0, 0, 0));

        // Caption bottom-left
        caption.setFont(Font.font("Arial", FontPosture.ITALIC, 12));
        caption.setTextFill(Color.web("#6c7086"));
        caption.setStyle("-fx-background-color:rgba(30,30,46,0.82);-fx-padding:4 10;");
        caption.setWrapText(true);
        StackPane.setAlignment(caption, Pos.BOTTOM_LEFT);
        StackPane.setMargin(caption, new Insets(0, 130, 0, 0));

        stack.getChildren().addAll(panelLbl, caption);
        stack.setStyle("-fx-background-color:#1e1e2e;-fx-border-color:#313244;-fx-border-width:0 1 0 0;");
        stack.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(stack, Priority.ALWAYS);
        return stack;
    }

    private Label outcomeCountLabel(String accent, String model) {
        Label l = new Label("Enumerating " + model + "…");
        l.setTextFill(Color.web(accent));
        return l;
    }

    private Label graphCaption(String text, String accent) {
        Label l = new Label(text);
        l.setTextFill(Color.web(accent));
        return l;
    }

    private Region divider() {
        Region r = new Region();
        r.setPrefHeight(2);
        r.setStyle("-fx-background-color:#313244;");
        return r;
    }

    private VBox buildLegend() {
        VBox box = new VBox(4);
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setStyle("-fx-background-color:rgba(30,30,46,0.88);-fx-background-radius:8;"
                + "-fx-border-color:#45475a;-fx-border-width:1;-fx-border-radius:8;");
        box.setMaxWidth(Region.USE_PREF_SIZE);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        for (String[] item : new String[][]{
                {"#fab387","INIT"},{"#a6e3a1","READ"},{"#f38ba8","WRITE"},
                {"#89b4fa","─  po"},{"#a6e3a1","─  rf"},
                {"#cba6f7","╌  co"},{"#94e2d5","━  sw"}}) {
            HBox lr = new HBox(6); lr.setAlignment(Pos.CENTER_LEFT);
            javafx.scene.shape.Rectangle dot = new javafx.scene.shape.Rectangle(10, 10);
            dot.setArcWidth(3); dot.setArcHeight(3);
            dot.setFill(Color.web(item[0]));
            Label lbl = new Label(item[1]);
            lbl.setFont(Font.font("Monospaced", 11));
            lbl.setTextFill(Color.web(item[0]));
            lr.getChildren().addAll(dot, lbl);
            box.getChildren().add(lr);
        }
        return box;
    }

    private String graphStyle() {
        return "graph{padding:120px;}"
                + "node{shape:rounded-box;size:165px,50px;fill-color:#313244;"
                + "stroke-mode:plain;stroke-color:#89b4fa;stroke-width:2px;"
                + "text-color:#cdd6f4;text-style:bold;text-size:12;}"
                + "node.init{stroke-color:#fab387;text-color:#fab387;}"
                + "node.read{stroke-color:#a6e3a1;text-color:#a6e3a1;}"
                + "node.write{stroke-color:#f38ba8;text-color:#f38ba8;}"
                + "edge{arrow-shape:arrow;arrow-size:12px,6px;"
                + "text-size:11;text-color:#cdd6f4;"
                + "text-background-mode:rounded-box;text-background-color:#1e1e2e;text-padding:2px;"
                + "fill-color:#89b4fa;size:2px;}"
                + "edge.rf{fill-color:#a6e3a1;size:2px;}"
                + "edge.po{fill-color:#89b4fa;size:2px;}"
                + "edge.co{fill-color:#cba6f7;size:2px;stroke-mode:dashes;}"
                + "edge.sw{fill-color:#94e2d5;size:3px;}";
    }

    private String plural(int n) { return n == 1 ? "" : "s"; }
}