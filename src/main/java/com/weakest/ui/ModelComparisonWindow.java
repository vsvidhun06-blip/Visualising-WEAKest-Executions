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

import java.util.*;
import java.util.concurrent.*;

/**
 * Full-screen window comparing 5 memory models side-by-side:
 *   SC | TSO | PSO | RA | WEAKEST
 *
 * For each model, shows which outcomes are ALLOWED.
 * Outcomes exclusive to weaker models are highlighted.
 * A summary table at the top shows the model hierarchy.
 */
public class ModelComparisonWindow {

    // -- Model definitions ---------------------------------------------
    private static final String[] MODEL_NAMES = {"SC", "TSO", "PSO", "RA", "WEAKEST"};

    private static String[] modelColours() {
        String a = "#89b4fa", b = "#94e2d5", c = "#a6e3a1", d = "#f9e2af", e = "#cba6f7";
        return new String[]{a, b, c, d, e};
    }
    private static final String[] MODEL_COLOURS = modelColours();
    private static final String[] MODEL_DESCS   = {
            "Sequential Consistency\nAll threads see writes in the same\nglobal order. Strongest model.",
            "Total Store Order (x86)\nWrites may be delayed in a per-thread\nstore buffer. r1=0,r2=0 possible in SB.",
            "Partial Store Order\nPer-variable store buffers per thread.\nMore relaxed than TSO.",
            "Release-Acquire\nrlx reads see any write. acq/rel pairs\ncreate synchronization edges.",
            "WEAKEST\nMaximally weak -- allows all outcomes\nnot forbidden by the justification\nsequence (prevents OOTA)."
    };

    // -- State ---------------------------------------------------------
    private final Program program;
    private final String  litmusName;
    private Stage         stage;

    // Results per model
    private final Map<String, Set<String>> modelOutcomes = new LinkedHashMap<>();
    private final Map<String, Integer>     modelExecCounts = new LinkedHashMap<>();

    // UI
    private Label     statusLabel;
    private ProgressIndicator spinner;
    private VBox      outcomeTableBox;
    private HBox      modelHeaderRow;
    private Label     summaryLabel;

    // -- Constructor ---------------------------------------------------

    public ModelComparisonWindow(Program program, String litmusName) {
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
        startEnumeration();
    }

    // -- Build Stage ---------------------------------------------------

    private void buildStage() {
        stage = new Stage();
        stage.setTitle("D83DDD2C  Multi-Model Comparison -- " + litmusName);

        // -- Header ----------------------------------------------------
        HBox header = new HBox(14);
        header.setPadding(new Insets(12, 16, 12, 16));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color:#313244;");

        Label title = new Label("🔬  Memory Model Comparison -- " + litmusName);
        title.setFont(Font.font("Arial", FontWeight.BOLD, 20));
        title.setTextFill(Color.web("#cdd6f4"));

        statusLabel = new Label("Enumerating all 5 models...");
        statusLabel.setFont(Font.font("Arial", FontPosture.ITALIC, 13));
        statusLabel.setTextFill(Color.web("#f9e2af"));

        spinner = new ProgressIndicator();
        spinner.setPrefSize(22, 22);

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        Button closeBtn = new Button("  Close");
        closeBtn.setStyle("-fx-background-color:#f38ba8;-fx-text-fill:#1e1e2e;" +
                "-fx-font-weight:bold;-fx-padding:6 14;");
        closeBtn.setOnAction(e -> stage.close());

        header.getChildren().addAll(title, sp, spinner, statusLabel, closeBtn);

        // -- Model info cards ------------------------------------------
        HBox modelCards = new HBox(6);
        modelCards.setPadding(new Insets(10, 14, 10, 14));
        modelCards.setStyle("-fx-background-color:#181825;");
        for (int i = 0; i < MODEL_NAMES.length; i++) {
            modelCards.getChildren().add(buildModelCard(i));
        }

        // -- Hierarchy label -------------------------------------------
        HBox hierarchyBar = new HBox();
        hierarchyBar.setPadding(new Insets(6, 16, 6, 16));
        hierarchyBar.setAlignment(Pos.CENTER_LEFT);
        hierarchyBar.setStyle("-fx-background-color:#1e1e2e;");
        Label hierLbl = new Label(
                "Model hierarchy (strictest  weakest):  " +
                        "SC    TSO    PSO    RA    WEAKEST" +
                        "   --   Each model ALLOWS everything the model to its left allows, plus more.");
        hierLbl.setFont(Font.font("Arial", 13));
        hierLbl.setTextFill(Color.web("#a6adc8"));
        hierarchyBar.getChildren().add(hierLbl);

        // -- Outcome comparison table ----------------------------------
        summaryLabel = new Label("Enumerating...");
        summaryLabel.setFont(Font.font("Arial", FontPosture.ITALIC, 13));
        summaryLabel.setTextFill(Color.web("#6c7086"));
        summaryLabel.setPadding(new Insets(6, 16, 2, 16));

        // Column headers
        modelHeaderRow = new HBox(0);
        modelHeaderRow.setStyle("-fx-background-color:#1e1e2e;-fx-border-color:#45475a;" +
                "-fx-border-width:0 0 2 0;");
        modelHeaderRow.setPadding(new Insets(0));

        Label outcomeHdr = colHeader("Outcome", "#cdd6f4", 220);
        modelHeaderRow.getChildren().add(outcomeHdr);
        for (int i = 0; i < MODEL_NAMES.length; i++) {
            modelHeaderRow.getChildren().add(colHeader(MODEL_NAMES[i], MODEL_COLOURS[i], 130));
        }
        Label notesHdr = colHeader("Notes", "#a6adc8", 300);
        modelHeaderRow.getChildren().add(notesHdr);

        outcomeTableBox = new VBox(0);

        ScrollPane tableScroll = new ScrollPane(outcomeTableBox);
        tableScroll.setFitToWidth(true);
        tableScroll.setStyle("-fx-background-color:#181825;-fx-background:#181825;");
        VBox.setVgrow(tableScroll, Priority.ALWAYS);

        VBox tableSection = new VBox(0, summaryLabel, modelHeaderRow, tableScroll);
        tableSection.setStyle("-fx-background-color:#181825;");
        VBox.setVgrow(tableSection, Priority.ALWAYS);

        // -- Root ------------------------------------------------------
        VBox root = new VBox(0, header, modelCards, hierarchyBar,
                divider(), tableSection);
        VBox.setVgrow(tableSection, Priority.ALWAYS);
        root.setStyle("-fx-background-color:#181825;");
        root.setMaxHeight(Double.MAX_VALUE);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setMaximized(true);
    }

    // -- Background enumeration ----------------------------------------

    private void startEnumeration() {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ModelComparisonEnumerator");
            t.setDaemon(true);
            return t;
        });
        exec.submit(() -> {
            try {
                ExecutionEnumerator en = new ExecutionEnumerator();
                int saved = Event.peekCounter();

                Map<String, List<ExecutionEnumerator.EnumeratedExecution>> results = new LinkedHashMap<>();
                results.put("SC",      en.enumerateSC(program));      Event.resetCounterTo(saved);
                results.put("TSO",     en.enumerateTSO(program));     Event.resetCounterTo(saved);
                results.put("PSO",     en.enumeratePSO(program));     Event.resetCounterTo(saved);
                results.put("RA",      en.enumerateRA(program));      Event.resetCounterTo(saved);
                results.put("WEAKEST", en.enumerateWeakest(program)); Event.resetCounterTo(saved);

                Platform.runLater(() -> onEnumerationDone(results));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText(" Error: " + ex.getMessage());
                    spinner.setVisible(false);
                    ex.printStackTrace();
                });
            }
        });
        exec.shutdown();
    }

    private void onEnumerationDone(
            Map<String, List<ExecutionEnumerator.EnumeratedExecution>> results) {

        spinner.setVisible(false);

        // Collect all outcomes per model
        for (String model : MODEL_NAMES) {
            List<ExecutionEnumerator.EnumeratedExecution> execs = results.get(model);
            Set<String> outcomes = new LinkedHashSet<>();
            if (execs != null) {
                execs.stream().map(ExecutionEnumerator.EnumeratedExecution::outcomeLabel)
                        .forEach(outcomes::add);
                modelExecCounts.put(model, execs.size());
            }
            modelOutcomes.put(model, outcomes);
        }

        // All outcomes across all models
        Set<String> allOutcomes = new TreeSet<>();
        modelOutcomes.values().forEach(allOutcomes::addAll);

        // Build table rows
        outcomeTableBox.getChildren().clear();
        boolean alternate = false;
        for (String outcome : allOutcomes) {
            outcomeTableBox.getChildren().add(buildOutcomeRow(outcome, alternate));
            alternate = !alternate;
        }

        // Summary
        Set<String> scOnly    = new TreeSet<>(modelOutcomes.get("SC"));
        Set<String> weakExtra = new TreeSet<>(modelOutcomes.get("WEAKEST"));
        weakExtra.removeAll(scOnly);

        summaryLabel.setText(
                "SC: " + modelOutcomes.get("SC").size() + " outcomes  |  " +
                        "TSO: " + modelOutcomes.get("TSO").size() + "  |  " +
                        "PSO: " + modelOutcomes.get("PSO").size() + "  |  " +
                        "RA: " + modelOutcomes.get("RA").size() + "  |  " +
                        "WEAKEST: " + modelOutcomes.get("WEAKEST").size() + " outcomes" +
                        (weakExtra.isEmpty() ? "" : "    " + weakExtra.size() + " weak-only outcome(s)")
        );
        summaryLabel.setTextFill(weakExtra.isEmpty()
                ? Color.web("#a6e3a1") : Color.web("#f9e2af"));

        statusLabel.setText("Done -- " + allOutcomes.size() + " distinct outcomes across all models");
        statusLabel.setTextFill(Color.web("#a6e3a1"));
    }

    // -- Row builder ---------------------------------------------------

    private HBox buildOutcomeRow(String outcome, boolean alternate) {
        HBox row = new HBox(0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-background-color:" + (alternate ? "#1e1e2e" : "#181825") + ";");
        row.setPrefHeight(38);

        // Outcome label
        Label outLbl = new Label(outcome);
        outLbl.setFont(Font.font("Monospaced", FontWeight.BOLD, 13));
        outLbl.setMinWidth(220); outLbl.setPrefWidth(220);
        outLbl.setPadding(new Insets(0, 8, 0, 14));

        // Determine which models allow this outcome
        boolean[] allowed = new boolean[MODEL_NAMES.length];
        int firstAllowed = -1;
        for (int i = 0; i < MODEL_NAMES.length; i++) {
            allowed[i] = modelOutcomes.getOrDefault(MODEL_NAMES[i], Set.of()).contains(outcome);
            if (allowed[i] && firstAllowed == -1) firstAllowed = i;
        }

        // Colour the outcome label based on whether SC allows it
        boolean scAllows = allowed[0];
        boolean weakestOnly = !scAllows && allowed[MODEL_NAMES.length - 1];
        outLbl.setTextFill(Color.web(scAllows ? "#a6e3a1" : weakestOnly ? "#cba6f7" : "#f9e2af"));
        row.getChildren().add(outLbl);

        // Model checkboxes
        for (int i = 0; i < MODEL_NAMES.length; i++) {
            row.getChildren().add(buildCheckCell(allowed[i], MODEL_COLOURS[i]));
        }

        // Notes column
        String note = buildNote(allowed, outcome);
        Label noteLbl = new Label(note);
        noteLbl.setFont(Font.font("Arial", 12));
        noteLbl.setTextFill(Color.web("#6c7086"));
        noteLbl.setMinWidth(300); noteLbl.setPrefWidth(300);
        noteLbl.setWrapText(true);
        noteLbl.setPadding(new Insets(0, 8, 0, 14));
        row.getChildren().add(noteLbl);

        return row;
    }

    private StackPane buildCheckCell(boolean allowed, String accent) {
        StackPane cell = new StackPane();
        cell.setMinWidth(130); cell.setPrefWidth(130);
        cell.setMinHeight(38); cell.setPrefHeight(38);
        cell.setStyle(allowed
                ? "-fx-background-color:rgba(" + hexToRgb(accent) + ",0.12);"
                : "-fx-background-color:rgba(30,30,46,0.3);");

        Label lbl = new Label(allowed ? "✅  ALLOWED" : "❌");
        lbl.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        lbl.setTextFill(Color.web(allowed ? accent : "#45475a"));
        cell.getChildren().add(lbl);
        return cell;
    }

    private String buildNote(boolean[] allowed, String outcome) {
        // Find the first (strictest) model that allows this outcome
        for (int i = 0; i < MODEL_NAMES.length; i++) {
            if (allowed[i]) {
                if (i == 0) return "SC-compatible -- all models allow this";
                if (i == 1) return "Weak outcome first seen in TSO (x86 store buffering)";
                if (i == 2) return "Weak outcome first seen in PSO (per-variable buffers)";
                if (i == 3) return "Weak outcome first seen in RA (relaxed acq/rel)";
                if (i == 4) return " WEAKEST-only -- forbidden by SC/TSO/PSO/RA, allowed by WEAKEST's justification sequence";
            }
        }
        return "Not observed in any model";
    }

    // -- UI helpers ----------------------------------------------------

    private VBox buildModelCard(int i) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(10, 14, 10, 14));
        card.setStyle("-fx-background-color:#1e1e2e;-fx-border-color:" + MODEL_COLOURS[i] +
                ";-fx-border-width:0 0 3 0;-fx-background-radius:6;");
        HBox.setHgrow(card, Priority.ALWAYS);

        Label nameLbl = new Label(MODEL_NAMES[i]);
        nameLbl.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        nameLbl.setTextFill(Color.web(MODEL_COLOURS[i]));

        Label descLbl = new Label(MODEL_DESCS[i]);
        descLbl.setFont(Font.font("Arial", 11));
        descLbl.setTextFill(Color.web("#a6adc8"));
        descLbl.setWrapText(true);

        card.getChildren().addAll(nameLbl, descLbl);
        return card;
    }

    private Label colHeader(String text, String colour, double width) {
        Label l = new Label(text);
        l.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        l.setTextFill(Color.web(colour));
        l.setMinWidth(width); l.setPrefWidth(width);
        l.setPadding(new Insets(8, 8, 8, 14));
        return l;
    }

    private Region divider() {
        Region r = new Region();
        r.setPrefHeight(2);
        r.setStyle("-fx-background-color:#313244;");
        return r;
    }

    /** Convert hex colour to "r,g,b" for rgba() use */
    private String hexToRgb(String hex) {
        hex = hex.replace("#", "");
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        return r + "," + g + "," + b;
    }
}