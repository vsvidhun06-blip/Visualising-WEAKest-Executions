package com.weakest.ui;

import com.weakest.model.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.animation.*;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.graphstream.graph.*;
import org.graphstream.graph.implementations.MultiGraph;
import org.graphstream.ui.fx_viewer.FxDefaultView;
import org.graphstream.ui.fx_viewer.FxViewer;

import java.util.*;
import java.util.concurrent.*;


public class ComparisonWindow {

    // -- Layout constants ----------------------------------------------
    private static final double COLUMN_SPACING = 260.0;
    private static final double ROW_SPACING    = 130.0;

    private static final String[] ALL_MODELS = {"SC", "TSO", "PSO", "RA", "WEAKEST"};

    private static String[] modelAccents() {
        String sc = "#89b4fa", tso = "#94e2d5", pso = "#a6e3a1", ra = "#f9e2af", wk = "#cba6f7";
        return new String[]{sc, tso, pso, ra, wk};
    }
    private static final String[] MODEL_ACCENTS = modelAccents();

    private static String accentFor(String model) {
        for (int i = 0; i < ALL_MODELS.length; i++)
            if (ALL_MODELS[i].equals(model)) return MODEL_ACCENTS[i];
        return "#cdd6f4";
    }

    // -- State ---------------------------------------------------------
    private final Program program;
    private final String  litmusName;
    private Stage         stage;

    private String leftModel  = "SC";
    private String rightModel = "WEAKEST";

    // Graph panels
    private Graph    leftGraph, rightGraph;
    private FxViewer leftViewer, rightViewer;

    // UI references
    private Label             statusLabel;
    private ProgressIndicator spinner;
    private VBox              leftOutcomeBox, rightOutcomeBox;
    private Label             leftCountLabel, rightCountLabel;
    private Label             leftPanelLabel, rightPanelLabel;
    private Label             leftSelectedLabel, rightSelectedLabel;
    private VBox              leftOutcomePanel, rightOutcomePanel;
    private ComboBox<String>  leftPicker, rightPicker;
    private Label             titleLabel;

    // Tooltip overlays for both graph panels
    private VBox   leftTooltip,  rightTooltip;
    private Map<String, double[]> leftNodeXY  = new HashMap<>();
    private Map<String, double[]> rightNodeXY = new HashMap<>();
    private FxDefaultView leftViewRef, rightViewRef;

    // -- Constructor ---------------------------------------------------

    public ComparisonWindow(Program program, String litmusName) {
        System.setProperty("org.graphstream.ui", "javafx");
        this.program    = program;
        this.litmusName = litmusName;
        buildStage();
    }

    // -- Show ----------------------------------------------------------

    public void show() {
        stage.show();
        stage.setMaximized(true);
        stage.toFront();
        stage.requestFocus();
        runComparison();
    }

    // -- Build Stage ---------------------------------------------------

    private void buildStage() {
        stage = new Stage();
        stage.setTitle("Compare Models -- " + litmusName);

        // -- Header ----------------------------------------------------
        HBox header = new HBox(10);
        header.setPadding(new Insets(10, 16, 10, 16));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color:#313244;");

        titleLabel = new Label("Compare Models -- " + litmusName);
        titleLabel.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        titleLabel.setTextFill(Color.web("#cdd6f4"));

        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);

        Label vsLbl = new Label("VS");
        vsLbl.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        vsLbl.setTextFill(Color.web("#6c7086"));
        vsLbl.setPadding(new Insets(0, 8, 0, 8));

        leftPicker  = modelPicker("SC");
        rightPicker = modelPicker("WEAKEST");

        Button compareBtn = new Button("Compare");
        compareBtn.setStyle("-fx-background-color:#ffffff;-fx-text-fill:#1e1e2e;"
                + "-fx-font-weight:bold;-fx-font-size:14;-fx-padding:7 20;"
                + "-fx-background-radius:6;-fx-cursor:hand;");
        compareBtn.setOnMouseEntered(e -> compareBtn.setStyle(
                "-fx-background-color:#1e1e2e;-fx-text-fill:#ffffff;"
                        + "-fx-font-weight:bold;-fx-font-size:14;-fx-padding:7 20;"
                        + "-fx-background-radius:6;-fx-border-color:#ffffff;-fx-border-width:2;"
                        + "-fx-border-radius:6;-fx-cursor:hand;"));
        compareBtn.setOnMouseExited(e -> compareBtn.setStyle(
                "-fx-background-color:#ffffff;-fx-text-fill:#1e1e2e;"
                        + "-fx-font-weight:bold;-fx-font-size:14;-fx-padding:7 20;"
                        + "-fx-background-radius:6;-fx-cursor:hand;"));
        compareBtn.setOnAction(e -> {
            String lm = leftPicker.getValue();
            String rm = rightPicker.getValue();
            if (lm.equals(rm)) {
                statusLabel.setText("Please select two different models.");
                statusLabel.setTextFill(Color.web("#f38ba8"));
                return;
            }
            leftModel  = lm;
            rightModel = rm;
            refreshPanelLabels();
            runComparison();
        });

        spinner = new ProgressIndicator();
        spinner.setPrefSize(22, 22);
        spinner.setVisible(false);

        statusLabel = new Label("");
        statusLabel.setFont(Font.font("Arial", FontPosture.ITALIC, 13));
        statusLabel.setTextFill(Color.web("#f9e2af"));
        HBox.setHgrow(statusLabel, Priority.NEVER);

        Button closeBtn = new Button("X  Close");
        closeBtn.setStyle("-fx-background-color:#f38ba8;-fx-text-fill:#1e1e2e;"
                + "-fx-font-weight:bold;-fx-padding:7 14;");
        closeBtn.setOnAction(e -> stage.close());

        header.getChildren().addAll(
                titleLabel, spacer,
                leftPicker, vsLbl, rightPicker, compareBtn,
                spinner, statusLabel, closeBtn);

        // -- Outcome table area (top half) ─────────────────────────────
        leftOutcomeBox  = new VBox(4);
        rightOutcomeBox = new VBox(4);
        leftCountLabel  = countLabel(accentFor(leftModel),  leftModel);
        rightCountLabel = countLabel(accentFor(rightModel), rightModel);

        leftOutcomePanel  = buildOutcomePanel(headingFor(leftModel),  accentFor(leftModel),
                leftCountLabel,  leftOutcomeBox);
        rightOutcomePanel = buildOutcomePanel(headingFor(rightModel), accentFor(rightModel),
                rightCountLabel, rightOutcomeBox);

        HBox tables = new HBox(1, leftOutcomePanel, rightOutcomePanel);
        HBox.setHgrow(leftOutcomePanel,  Priority.ALWAYS);
        HBox.setHgrow(rightOutcomePanel, Priority.ALWAYS);
        tables.setMinHeight(160);
        tables.setMaxHeight(280);
        tables.setPrefHeight(240);

        //  Graph panels (bottom half) ────────────────────────────────
        leftGraph  = new MultiGraph("CmpLeft");
        rightGraph = new MultiGraph("CmpRight");
        applyGraphStyle(leftGraph);
        applyGraphStyle(rightGraph);

        leftViewer  = new FxViewer(leftGraph,  FxViewer.ThreadingModel.GRAPH_IN_GUI_THREAD);
        rightViewer = new FxViewer(rightGraph, FxViewer.ThreadingModel.GRAPH_IN_GUI_THREAD);
        leftViewer.disableAutoLayout();
        rightViewer.disableAutoLayout();

        FxDefaultView leftView  = (FxDefaultView) leftViewer.addDefaultView(false);
        FxDefaultView rightView = (FxDefaultView) rightViewer.addDefaultView(false);
        leftViewRef  = leftView;
        rightViewRef = rightView;
        leftView.setMinSize(100, 100);
        rightView.setMinSize(100, 100);
        leftView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        rightView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(leftView,  Priority.ALWAYS);
        HBox.setHgrow(rightView, Priority.ALWAYS);
        VBox.setVgrow(leftView,  Priority.ALWAYS);
        VBox.setVgrow(rightView, Priority.ALWAYS);

        leftSelectedLabel  = graphCaption("Click an outcome above to view its execution graph",
                accentFor(leftModel));
        rightSelectedLabel = graphCaption("Click an outcome above to view its execution graph",
                accentFor(rightModel));

        leftPanelLabel  = panelBadge(leftModel,  accentFor(leftModel));
        rightPanelLabel = panelBadge(rightModel, accentFor(rightModel));

        leftTooltip  = buildTooltipPanel();
        rightTooltip = buildTooltipPanel();

        StackPane leftStack  = graphPanel(leftView,  buildLegend(),
                leftSelectedLabel,  leftPanelLabel, leftTooltip);
        StackPane rightStack = graphPanel(rightView, buildLegend(),
                rightSelectedLabel, rightPanelLabel, rightTooltip);

        // Wire click handlers
        leftView.setOnMouseClicked(e  -> handleGraphClick(e, leftGraph,  leftViewer,  leftViewRef,  leftNodeXY,  leftTooltip));
        rightView.setOnMouseClicked(e -> handleGraphClick(e, rightGraph, rightViewer, rightViewRef, rightNodeXY, rightTooltip));

        HBox graphs = new HBox(1, leftStack, rightStack);
        HBox.setHgrow(leftStack,  Priority.ALWAYS);
        HBox.setHgrow(rightStack, Priority.ALWAYS);
        graphs.setMaxHeight(Double.MAX_VALUE);

        // -- Root ------------------------------------------------------
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#1e1e2e;");
        root.setTop(header);
        VBox center = new VBox(0, tables, divider(), graphs);
        VBox.setVgrow(graphs, Priority.ALWAYS);
        center.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(center, Priority.ALWAYS);
        root.setCenter(center);
        root.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        Scene scene = new Scene(root, 1400, 900);
        stage.setScene(scene);
        stage.setMaximized(true);
    }

    // -- Refresh panel headings after model change ---------------------

    private void refreshPanelLabels() {
        updateOutcomePanelHeader(leftOutcomePanel,
                headingFor(leftModel), accentFor(leftModel));
        updateOutcomePanelHeader(rightOutcomePanel,
                headingFor(rightModel), accentFor(rightModel));

        leftCountLabel.setText("Enumerating " + leftModel + "...");
        leftCountLabel.setTextFill(Color.web(accentFor(leftModel)));
        rightCountLabel.setText("Enumerating " + rightModel + "...");
        rightCountLabel.setTextFill(Color.web(accentFor(rightModel)));

        leftSelectedLabel.setText("Click an outcome above to view its execution graph");
        leftSelectedLabel.setTextFill(Color.web(accentFor(leftModel)));
        rightSelectedLabel.setText("Click an outcome above to view its execution graph");
        rightSelectedLabel.setTextFill(Color.web(accentFor(rightModel)));

        leftPanelLabel.setText(leftModel);
        leftPanelLabel.setTextFill(Color.web(accentFor(leftModel)));
        rightPanelLabel.setText(rightModel);
        rightPanelLabel.setTextFill(Color.web(accentFor(rightModel)));

        stage.setTitle("Compare: " + leftModel + " vs " + rightModel + " -- " + litmusName);

        leftOutcomeBox.getChildren().clear();
        rightOutcomeBox.getChildren().clear();
        leftGraph.clear();
        rightGraph.clear();
        applyGraphStyle(leftGraph);
        applyGraphStyle(rightGraph);
    }

    private void updateOutcomePanelHeader(VBox panel, String heading, String accent) {
        if (panel.getChildren().isEmpty()) return;
        javafx.scene.Node hdrNode = panel.getChildren().get(0);
        if (hdrNode instanceof HBox hdr) {
            hdr.setStyle("-fx-background-color:#1e1e2e;-fx-border-color:" + accent
                    + ";-fx-border-width:0 0 2 0;");
            if (!hdr.getChildren().isEmpty()
                    && hdr.getChildren().get(0) instanceof Label hl) {
                hl.setText(heading);
                hl.setTextFill(Color.web(accent));
            }
        }
    }

    // -- Background enumeration ----------------------------------------

    private void runComparison() {
        spinner.setVisible(true);
        statusLabel.setText("Enumerating " + leftModel + " and " + rightModel + "...");
        statusLabel.setTextFill(Color.web("#f9e2af"));

        String lm = leftModel, rm = rightModel;

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ComparisonEnumerator");
            t.setDaemon(true);
            return t;
        });
        exec.submit(() -> {
            try {
                ExecutionEnumerator en = new ExecutionEnumerator();
                int saved = Event.peekCounter();
                List<ExecutionEnumerator.EnumeratedExecution> left  = enumerate(en, lm);
                Event.resetCounterTo(saved);
                List<ExecutionEnumerator.EnumeratedExecution> right = enumerate(en, rm);
                Event.resetCounterTo(saved);
                Platform.runLater(() -> onEnumerationDone(left, right, lm, rm));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error: " + ex.getMessage());
                    statusLabel.setTextFill(Color.web("#f38ba8"));
                    spinner.setVisible(false);
                    ex.printStackTrace();
                });
            }
        });
        exec.shutdown();
    }

    private List<ExecutionEnumerator.EnumeratedExecution> enumerate(
            ExecutionEnumerator en, String model) {
        return switch (model) {
            case "SC"  -> en.enumerateSC(program);
            case "TSO" -> en.enumerateTSO(program);
            case "PSO" -> en.enumeratePSO(program);
            case "RA"  -> en.enumerateRA(program);
            default    -> en.enumerateWeakest(program);
        };
    }

    private void onEnumerationDone(
            List<ExecutionEnumerator.EnumeratedExecution> left,
            List<ExecutionEnumerator.EnumeratedExecution> right,
            String lm, String rm) {

        spinner.setVisible(false);

        Map<String, List<ExecutionEnumerator.EnumeratedExecution>> lByOutcome = groupByOutcome(left);
        Map<String, List<ExecutionEnumerator.EnumeratedExecution>> rByOutcome = groupByOutcome(right);

        Set<String> rightOnly = new HashSet<>(rByOutcome.keySet());
        rightOnly.removeAll(lByOutcome.keySet());

        buildOutcomeRows(leftOutcomeBox,  lByOutcome, rightOnly, false, lm, rm);
        buildOutcomeRows(rightOutcomeBox, rByOutcome, rightOnly, true,  lm, rm);

        int lU = lByOutcome.size(), lE = left.size();
        int rU = rByOutcome.size(), rE = right.size();
        int extra = rightOnly.size();

        leftCountLabel.setText(lU + " outcome" + plural(lU) + "  (" + lE + " executions)");
        leftCountLabel.setTextFill(Color.web(accentFor(lm)));
        rightCountLabel.setText(rU + " outcome" + plural(rU) + "  (" + rE + " executions)"
                + (extra > 0 ? "  +" + extra + " extra" : ""));
        rightCountLabel.setTextFill(Color.web(accentFor(rm)));

        boolean capped = lE >= ExecutionEnumerator.MAX_EXECUTIONS
                || rE >= ExecutionEnumerator.MAX_EXECUTIONS;

        statusLabel.setText("Done."
                + (capped ? "  (capped at " + ExecutionEnumerator.MAX_EXECUTIONS + ")" : "")
                + "  " + lm + ": " + lU + " outcomes"
                + "  |  " + rm + ": " + rU + " outcomes"
                + (extra > 0 ? "  (+" + extra + " in " + rm + " only)" : ""));
        statusLabel.setTextFill(extra > 0 ? Color.web("#f9e2af") : Color.web("#a6e3a1"));
    }

    // -- Outcome rows --------------------------------------------------

    private void buildOutcomeRows(
            VBox box,
            Map<String, List<ExecutionEnumerator.EnumeratedExecution>> byOutcome,
            Set<String> rightOnlyOutcomes,
            boolean isRight,
            String lm, String rm) {

        box.getChildren().clear();
        List<String> sorted = new ArrayList<>(byOutcome.keySet());
        sorted.sort(Comparator.naturalOrder());

        for (String outcome : sorted) {
            List<ExecutionEnumerator.EnumeratedExecution> execs = byOutcome.get(outcome);
            boolean isExtra = rightOnlyOutcomes.contains(outcome);

            String bg     = isExtra ? "#2a1e1e" : "#1e2a1e";
            String border = isExtra ? "#f38ba8"  : "#a6e3a1";

            HBox row = new HBox(10);
            row.setPadding(new Insets(7, 12, 7, 12));
            row.setAlignment(Pos.CENTER_LEFT);
            row.setStyle("-fx-background-color:" + bg
                    + ";-fx-border-color:" + border
                    + ";-fx-border-width:0 0 0 4;-fx-cursor:hand;");

            Label outLbl = new Label(outcome);
            outLbl.setFont(Font.font("Monospaced", FontWeight.BOLD, 14));
            outLbl.setTextFill(Color.web(isExtra ? "#f38ba8" : "#a6e3a1"));
            outLbl.setMinWidth(160);

            Label cntLbl = new Label("x " + execs.size());
            cntLbl.setFont(Font.font("Arial", 12));
            cntLbl.setTextFill(Color.web("#6c7086"));

            String tagText  = isExtra ? rm + " ONLY" : "in both";
            String tagBg    = isExtra ? "#3a1e1e"   : "#1e3a1e";
            String tagColor = isExtra ? "#f38ba8"   : "#a6e3a1";
            Label tagLbl = new Label(tagText);
            tagLbl.setFont(Font.font("Arial", FontWeight.BOLD, 11));
            tagLbl.setTextFill(Color.web(tagColor));
            tagLbl.setStyle("-fx-background-color:" + tagBg
                    + ";-fx-background-radius:4;-fx-padding:2 6;");

            Label clickLbl = new Label("click to view graph");
            clickLbl.setFont(Font.font("Arial", FontPosture.ITALIC, 11));
            clickLbl.setTextFill(Color.web("#45475a"));

            Region sp2 = new Region(); HBox.setHgrow(sp2, Priority.ALWAYS);
            row.getChildren().addAll(outLbl, cntLbl, tagLbl, sp2, clickLbl);

            // Hover
            row.setOnMouseEntered(e -> row.setStyle(
                    "-fx-background-color:" + (isExtra ? "#3a2020" : "#203a20")
                            + ";-fx-border-color:" + border
                            + ";-fx-border-width:0 0 0 4;-fx-cursor:hand;"));
            row.setOnMouseExited(e -> row.setStyle(
                    "-fx-background-color:" + bg
                            + ";-fx-border-color:" + border
                            + ";-fx-border-width:0 0 0 4;-fx-cursor:hand;"));

            ExecutionEnumerator.EnumeratedExecution first = execs.get(0);
            if (isRight) {
                row.setOnMouseClicked(e -> {
                    loadGraph(rightGraph, first.snap());
                    rightSelectedLabel.setText("Showing: " + outcome
                            + "  (" + execs.size() + " execution" + plural(execs.size()) + ")");
                });
            } else {
                row.setOnMouseClicked(e -> {
                    loadGraph(leftGraph, first.snap());
                    leftSelectedLabel.setText("Showing: " + outcome
                            + "  (" + execs.size() + " execution" + plural(execs.size()) + ")");
                });
            }

            box.getChildren().add(row);
        }

        if (sorted.isEmpty()) {
            Label empty = new Label("No executions found.");
            empty.setTextFill(Color.web("#6c7086"));
            empty.setPadding(new Insets(12));
            box.getChildren().add(empty);
        }
    }

    // -- Graph rendering -----------------------------------------------

    private void loadGraph(Graph g, ExecutionEnumerator.SnapData snap) {
        boolean isLeft = (g == leftGraph);
        Map<String, double[]> nodeXY = isLeft ? leftNodeXY : rightNodeXY;
        nodeXY.clear();
        g.clear();
        applyGraphStyle(g);

        Map<Integer, Integer> rowCounter = new HashMap<>();
        int maxTid = snap.events().stream()
                .mapToInt(ExecutionEnumerator.EventRec::threadId).max().orElse(0);

        for (var rec : snap.events()) {
            String nodeId = "e" + rec.id();
            Node n = g.addNode(nodeId);
            String css = switch (rec.type()) {
                case "INIT"  -> "init";
                case "READ"  -> "read";
                default      -> "write";
            };
            n.setAttribute("ui.class", css);
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
            double nx = (rec.threadId() - maxTid / 2.0) * COLUMN_SPACING;
            double ny = -row * ROW_SPACING;
            n.setAttribute("xyz", nx, ny, 0);
            nodeXY.put(nodeId, new double[]{nx, ny});
        }

        snap.po().forEach((fromId, toIds) -> toIds.forEach(toId -> {
            String eid = "po" + fromId + "_" + toId;
            if (g.getEdge(eid) == null
                    && g.getNode("e"+fromId) != null && g.getNode("e"+toId) != null) {
                Edge e = g.addEdge(eid, "e"+fromId, "e"+toId, true);
                e.setAttribute("ui.class", "po"); e.setAttribute("ui.label", "po");
            }
        }));

        snap.rf().forEach((readId, writeId) -> {
            String eid = "rf" + writeId + "_" + readId;
            if (g.getEdge(eid) == null
                    && g.getNode("e"+writeId) != null && g.getNode("e"+readId) != null) {
                Edge e = g.addEdge(eid, "e"+writeId, "e"+readId, true);
                e.setAttribute("ui.class", "rf"); e.setAttribute("ui.label", "rf");
            }
        });

        snap.co().forEach((var, ids) -> {
            for (int i = 0; i < ids.size() - 1; i++) {
                int a = ids.get(i), b = ids.get(i+1);
                String eid = "co" + a + "_" + b;
                if (g.getEdge(eid) == null
                        && g.getNode("e"+a) != null && g.getNode("e"+b) != null) {
                    Edge e = g.addEdge(eid, "e"+a, "e"+b, true);
                    e.setAttribute("ui.class", "co"); e.setAttribute("ui.label", "co");
                }
            }
        });

        snap.sw().forEach(pair -> {
            String[] parts = pair.split("_");
            if (parts.length == 2) {
                String eid = "sw" + pair;
                if (g.getEdge(eid) == null
                        && g.getNode("e"+parts[0]) != null && g.getNode("e"+parts[1]) != null) {
                    Edge e = g.addEdge(eid, "e"+parts[0], "e"+parts[1], true);
                    e.setAttribute("ui.class", "sw"); e.setAttribute("ui.label", "sw");
                }
            }
        });
    }

    // -- UI helpers ----------------------------------------------------

    private ComboBox<String> modelPicker(String selected) {
        ComboBox<String> cb = new ComboBox<>();
        cb.getItems().addAll(ALL_MODELS);
        cb.setValue(selected);
        cb.setPrefWidth(140);

        cb.setStyle(
                "-fx-background-color:#ffffff;" +
                        "-fx-text-fill:#1e1e2e;" +
                        "-fx-font-size:14;" +
                        "-fx-font-weight:bold;" +
                        "-fx-border-color:#ffffff;" +
                        "-fx-border-width:2;" +
                        "-fx-border-radius:6;" +
                        "-fx-background-radius:6;" +
                        "-fx-padding:5 10;-fx-cursor:hand;"
        );

        // Subtle white -> dim pulse so it catches the eye without being loud
        Timeline pulse = new Timeline(
                new KeyFrame(Duration.ZERO,        e -> cb.setOpacity(1.0)),
                new KeyFrame(Duration.millis(800),  e -> cb.setOpacity(0.55)),
                new KeyFrame(Duration.millis(1600), e -> cb.setOpacity(1.0))
        );
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();

        cb.setOnMouseEntered(e -> {
            pulse.pause();
            cb.setOpacity(1.0);
            cb.setStyle(
                    "-fx-background-color:#1e1e2e;" +
                            "-fx-text-fill:#ffffff;" +
                            "-fx-font-size:14;" +
                            "-fx-font-weight:bold;" +
                            "-fx-border-color:#ffffff;" +
                            "-fx-border-width:2;" +
                            "-fx-border-radius:6;" +
                            "-fx-background-radius:6;" +
                            "-fx-padding:5 10;-fx-cursor:hand;"
            );
        });
        cb.setOnMouseExited(e -> {
            cb.setStyle(
                    "-fx-background-color:#ffffff;" +
                            "-fx-text-fill:#1e1e2e;" +
                            "-fx-font-size:14;" +
                            "-fx-font-weight:bold;" +
                            "-fx-border-color:#ffffff;" +
                            "-fx-border-width:2;" +
                            "-fx-border-radius:6;" +
                            "-fx-background-radius:6;" +
                            "-fx-padding:5 10;-fx-cursor:hand;"
            );
            pulse.play();
        });

        cb.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setStyle("-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-font-size:14;"
                        + "-fx-background-color:transparent;");
            }
        });
        cb.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item);
                setStyle("-fx-text-fill:#cdd6f4;-fx-font-weight:bold;"
                        + "-fx-font-size:13;-fx-background-color:#1e1e2e;-fx-padding:4 8;");
            }
        });
        return cb;
    }

    private String headingFor(String model) {
        return switch (model) {
            case "SC"  -> "Sequential Consistency (SC)";
            case "TSO" -> "Total Store Order (TSO)";
            case "PSO" -> "Partial Store Order (PSO)";
            case "RA"  -> "Release-Acquire (RA)";
            default    -> "WEAKEST";
        };
    }

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
        panel.setStyle("-fx-background-color:#181825;"
                + "-fx-border-color:#313244;-fx-border-width:0 1 0 0;");

        HBox hdr = new HBox(8);
        hdr.setPadding(new Insets(10, 14, 10, 14));
        hdr.setAlignment(Pos.CENTER_LEFT);
        hdr.setStyle("-fx-background-color:#1e1e2e;-fx-border-color:" + accent
                + ";-fx-border-width:0 0 2 0;");
        Label hl = new Label(heading);
        hl.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        hl.setTextFill(Color.web(accent));
        hdr.getChildren().add(hl);

        countLabel.setFont(Font.font("Arial", FontPosture.ITALIC, 12));
        countLabel.setTextFill(Color.web(accent));
        countLabel.setPadding(new Insets(4, 14, 4, 14));

        ScrollPane scroll = new ScrollPane(outcomesBox);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:#181825;-fx-background:#181825;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        panel.getChildren().addAll(hdr, countLabel, scroll);
        return panel;
    }

    private Label panelBadge(String text, String accent) {
        Label l = new Label(text);
        l.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        l.setTextFill(Color.web(accent));
        l.setStyle("-fx-background-color:rgba(30,30,46,0.82);-fx-padding:4 10;"
                + "-fx-background-radius:0 0 6 0;");
        return l;
    }

    private StackPane graphPanel(FxDefaultView view, VBox legend,
                                 Label caption, Label badge, VBox tooltip) {
        StackPane stack = new StackPane(view, legend);
        StackPane.setAlignment(legend, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(legend, new Insets(0, 14, 14, 0));

        StackPane.setAlignment(badge, Pos.TOP_LEFT);

        caption.setFont(Font.font("Arial", FontPosture.ITALIC, 12));
        caption.setStyle("-fx-background-color:rgba(30,30,46,0.82);-fx-padding:4 10;");
        caption.setWrapText(true);
        StackPane.setAlignment(caption, Pos.BOTTOM_LEFT);
        StackPane.setMargin(caption, new Insets(0, 130, 0, 0));

        StackPane.setAlignment(tooltip, Pos.TOP_LEFT);
        stack.getChildren().addAll(badge, caption, tooltip);
        stack.setStyle("-fx-background-color:#1e1e2e;"
                + "-fx-border-color:#313244;-fx-border-width:0 1 0 0;");
        stack.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(stack, Priority.ALWAYS);
        return stack;
    }

    private Label countLabel(String accent, String model) {
        Label l = new Label("Enumerating " + model + "...");
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

    private void applyGraphStyle(Graph g) {
        //noinspection CssUnknownProperty,CssInvalidPropertyValue,CssUnknownUnit,CssInvalidFunction
        g.setAttribute("ui.stylesheet", graphStyle());
        g.setAttribute("ui.quality");
        g.setAttribute("ui.antialias");
    }

    //noinspection CssUnknownProperty,CssInvalidPropertyValue,CssUnknownUnit,CssInvalidFunction
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

    private VBox buildLegend() {
        VBox box = new VBox(4);
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setStyle("-fx-background-color:rgba(30,30,46,0.88);-fx-background-radius:8;"
                + "-fx-border-color:#45475a;-fx-border-width:1;-fx-border-radius:8;");
        box.setMaxWidth(Region.USE_PREF_SIZE);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        String[][] items = {
                {"#fab387","INIT"}, {"#a6e3a1","READ"}, {"#f38ba8","WRITE"},
                {"#89b4fa","- po"}, {"#a6e3a1","- rf"},
                {"#cba6f7","- co"}, {"#94e2d5","- sw"}
        };
        for (String[] item : items) {
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

    private String plural(int n) { return n == 1 ? "" : "s"; }

    // Graph click tooltip system ────────────────────────────────────

    private VBox buildTooltipPanel() {
        VBox tp = new VBox(0);
        tp.setMaxWidth(230);
        tp.setMinWidth(170);
        tp.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        tp.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        tp.setStyle("-fx-background-color:#181825;-fx-border-color:#45475a;"
                + "-fx-border-width:1;-fx-background-radius:8;-fx-border-radius:8;"
                + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.5),10,0,0,4);");
        tp.setVisible(false);
        tp.setManaged(false);
        tp.setOnMouseClicked(javafx.event.Event::consume);
        return tp;
    }

    private void handleGraphClick(javafx.scene.input.MouseEvent evt,
                                  Graph graph, FxViewer viewer,
                                  FxDefaultView view,
                                  Map<String, double[]> nodeXY,
                                  VBox tooltip) {
        if (nodeXY.isEmpty()) return;

        double clickGX, clickGY;
        try {
            org.graphstream.ui.geom.Point3 p =
                    view.getCamera().transformPxToGu(evt.getX(), evt.getY());
            clickGX = p.x; clickGY = p.y;
        } catch (Exception ex) { return; }

        // Find nearest node
        double NODE_THRESHOLD = 40;
        Node nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (Node n : graph) {
            double[] xy = nodeXY.get(n.getId());
            if (xy == null) continue;
            double d = Math.hypot(clickGX - xy[0], clickGY - xy[1]);
            if (d < bestDist) { bestDist = d; nearest = n; }
        }
        if (nearest != null && bestDist < NODE_THRESHOLD) {
            showNodeTooltip(nearest, evt.getX(), evt.getY(), tooltip);
            return;
        }

        // Find nearest edge (point-to-segment distance)
        double EDGE_THRESHOLD = 12;
        Edge nearestEdge = null;
        double bestEdgeDist = Double.MAX_VALUE;
        for (Edge e : graph.edges().toList()) {
            double[] xy0 = nodeXY.get(e.getSourceNode().getId());
            double[] xy1 = nodeXY.get(e.getTargetNode().getId());
            if (xy0 == null || xy1 == null) continue;
            double d = pointToSegDist(clickGX, clickGY, xy0[0], xy0[1], xy1[0], xy1[1]);
            if (d < bestEdgeDist) { bestEdgeDist = d; nearestEdge = e; }
        }
        if (nearestEdge != null && bestEdgeDist < EDGE_THRESHOLD) {
            showEdgeTooltip(nearestEdge, evt.getX(), evt.getY(), tooltip);
        } else {
            hideTooltip(tooltip);
        }
    }

    private void showNodeTooltip(Node n, double sx, double sy, VBox tooltip) {
        String id = n.getId();
        if (!id.startsWith("e")) return;
        String cls = n.getAttribute("ui.class") != null
                ? n.getAttribute("ui.class").toString() : "";
        String colour = cls.equals("write") ? "#f38ba8"
                : cls.equals("read")  ? "#a6e3a1" : "#fab387";
        String label  = n.getAttribute("ui.label") != null
                ? n.getAttribute("ui.label").toString() : id;

        String typeTag = cls.equals("write") ? "WRITE"
                : cls.equals("read")  ? "READ" : "INIT";
        String desc = switch (cls) {
            case "write" -> "A write event commits a value to a shared variable.";
            case "read"  -> "A read event loads a value from a shared variable via rf.";
            default      -> "The initial write setting all variables to their starting value.";
        };

        buildTooltipContent(tooltip, typeTag, colour, label, desc, null);
        positionTooltip(tooltip, sx, sy);
    }

    private void showEdgeTooltip(Edge e, double sx, double sy, VBox tooltip) {
        String cls = e.getAttribute("ui.class") != null
                ? e.getAttribute("ui.class").toString() : "";
        String colour = switch (cls) {
            case "rf" -> "#a6e3a1";
            case "po" -> "#89b4fa";
            case "co" -> "#cba6f7";
            case "sw" -> "#94e2d5";
            default   -> "#cdd6f4";
        };
        String[] info = switch (cls) {
            case "rf" -> new String[]{"reads-from (rf)",
                    "This read gets its value from this write."};
            case "po" -> new String[]{"program-order (po)",
                    "Sequential order within one thread."};
            case "co" -> new String[]{"coherence-order (co)",
                    "Global order of writes to the same variable."};
            case "sw" -> new String[]{"synchronises-with (sw)",
                    "A release write paired with an acquire read."};
            default   -> new String[]{cls, ""};
        };

        buildTooltipContent(tooltip, info[0], colour,
                e.getSourceNode().getId() + " → " + e.getTargetNode().getId(),
                info[1], null);
        positionTooltip(tooltip, sx, sy);
    }

    private void buildTooltipContent(VBox tooltip, String tag, String colour,
                                     String label, String desc, String extra) {
        tooltip.getChildren().clear();

        // Title bar
        HBox titleBar = new HBox(6);
        titleBar.setPadding(new Insets(7, 10, 7, 10));
        titleBar.setStyle("-fx-background-color:" + colour + "22;"
                + "-fx-border-color:" + colour + ";-fx-border-width:0 0 1 0;");
        Label tagLbl = new Label(tag);
        tagLbl.setFont(Font.font("Monospaced", FontWeight.BOLD, 12));
        tagLbl.setTextFill(Color.web(colour));
        titleBar.getChildren().add(tagLbl);

        // Label
        Label labelLbl = new Label(label);
        labelLbl.setFont(Font.font("Monospaced", 11));
        labelLbl.setTextFill(Color.web("#cdd6f4"));
        labelLbl.setPadding(new Insets(6, 10, 2, 10));
        labelLbl.setWrapText(true);
        labelLbl.setMaxWidth(210);

        // Description
        Label descLbl = new Label(desc);
        descLbl.setFont(Font.font("Arial", 11));
        descLbl.setTextFill(Color.web("#6c7086"));
        descLbl.setPadding(new Insets(2, 10, 8, 10));
        descLbl.setWrapText(true);
        descLbl.setMaxWidth(210);

        tooltip.getChildren().addAll(titleBar, labelLbl, descLbl);
        if (extra != null) {
            Label ex = new Label(extra);
            ex.setFont(Font.font("Arial", FontPosture.ITALIC, 10));
            ex.setTextFill(Color.web("#45475a"));
            ex.setPadding(new Insets(0, 10, 6, 10));
            ex.setWrapText(true); ex.setMaxWidth(210);
            tooltip.getChildren().add(ex);
        }

        tooltip.setVisible(true);
        tooltip.setManaged(false);
        tooltip.setOpacity(1.0);
    }

    private void positionTooltip(VBox tooltip, double sx, double sy) {
        double tx = sx + 14, ty = sy - 10;
        StackPane.setMargin(tooltip, new Insets(ty, 0, 0, tx));
        tooltip.setVisible(true);
    }

    private void hideTooltip(VBox tooltip) {
        tooltip.setVisible(false);
    }

    private double pointToSegDist(double px, double py,
                                  double ax, double ay,
                                  double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        if (dx == 0 && dy == 0) return Math.hypot(px - ax, py - ay);
        double t = Math.max(0, Math.min(1,
                ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }
}