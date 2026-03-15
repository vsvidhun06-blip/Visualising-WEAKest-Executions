package com.weakest.ui;

import com.weakest.checker.ConsistencyChecker;
import com.weakest.model.*;
import com.weakest.parser.ProgramParser;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.graphstream.graph.*;
import org.graphstream.graph.implementations.MultiGraph;
import org.graphstream.ui.fx_viewer.FxViewer;
import org.graphstream.ui.fx_viewer.FxDefaultView;

import java.util.*;

public class MainController {

    // ── Core model ────────────────────────────────────────────────────
    private Program        program;
    private EventStructure eventStructure;
    private ExecutionState executionState;
    private final ConsistencyChecker checker    = new ConsistencyChecker();
    private final HintEngine         hintEngine = new HintEngine();

    // ── Undo ─────────────────────────────────────────────────────────
    private final Deque<ExecutionSnapshot> undoStack = new ArrayDeque<>();
    private static final int MAX_UNDO = 50;

    // ── Graph ─────────────────────────────────────────────────────────
    private Graph         graph;
    private FxViewer      viewer;
    private FxDefaultView graphView;
    private final Map<String, String>   nodeCssClass = new LinkedHashMap<>();
    private final Map<String, double[]> nodeXY       = new LinkedHashMap<>();

    // ── Layout ────────────────────────────────────────────────────────
    private static final double COLUMN_SPACING = 300.0;
    private static final double ROW_SPACING    = 150.0;
    private final Map<Integer, Integer> threadEventCount = new HashMap<>();

    // ── Screen dims (computed once) ───────────────────────────────────
    private double screenW, screenH, leftW;

    // ── UI ────────────────────────────────────────────────────────────
    private BorderPane root;
    private TextArea   programInput;
    private TextArea   logArea;
    private VBox       threadButtonsBox;
    private Button     undoButton, resetButton;
    private Label      undoCountLabel;

    // Context / inline-choice panel
    private VBox        contextPanel;
    private Label       contextTitle;
    private Label       contextBody;
    private VBox        inlineChoiceBox;
    private Timeline    contextPulse;

    // Hint buttons
    private Button hintBtn, revealBtn;
    private Label  hintStatusLabel;
    private String currentLitmus = null;

    // Forbidden zone panel
    private VBox   forbiddenPanel;
    private Label  forbiddenTitle;
    private VBox   forbiddenBody;

    // Graph tooltip panel (click-to-explain)
    private VBox   tooltipPanel;

    // Pending read state
    private int         pendingThreadIdx = -1;
    private Instruction pendingInstr;
    private ReadEvent   pendingRead;
    private List<Event> pendingValid;

    private ExplanationWindow explanationWindow;
    private ComparisonWindow  comparisonWindow;
    private BranchExplorerWindow branchExplorerWindow;
    private ModelComparisonWindow modelComparisonWindow;
    private final int[] shadowPCs = new int[64];

    public MainController() {
        System.setProperty("org.graphstream.ui", "javafx");
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        screenW = screen.getWidth();
        screenH = screen.getHeight();
        leftW   = Math.max(400, screenW * 0.27);
        buildUI();
        Platform.runLater(this::showWelcomeDialog);
    }

    // =========================================================================
    // Welcome dialog
    // =========================================================================

    private void showWelcomeDialog() {
        Stage w = new Stage();
        w.setTitle("👋  Welcome to WEAKEST Visualiser!");
        double ww = Math.min(680, screenW * 0.85);
        double wh = Math.min(620, screenH * 0.80);
        w.setWidth(ww); w.setHeight(wh);

        VBox box = new VBox(14);
        box.setPadding(new Insets(28));
        box.setStyle("-fx-background-color:#1e1e2e;");

        Label title = new Label("👋  Welcome to WEAKEST!");
        title.setFont(Font.font("Arial", FontWeight.BOLD, 22));
        title.setTextFill(Color.web("#89b4fa"));

        Label sub = new Label("An interactive tool for exploring weak memory concurrency — step by step.");
        sub.setWrapText(true);
        sub.setFont(Font.font("Arial", FontPosture.ITALIC, 13));
        sub.setTextFill(Color.web("#6c7086"));

        VBox steps = new VBox(8);
        String[] lines = {
                "1️⃣  Pick a Litmus Test  — SB, LB, MP, CYC or IRIW",
                "2️⃣  Click ▶ Load Program",
                "3️⃣  Click a Thread button  — 🟢 = write (automatic),  🟡 = read (you choose!)",
                "4️⃣  When a 🟡 button pulses, click it — choice cards appear below the buttons",
                "5️⃣  Click a card to pick which value the thread reads, then click Confirm",
                "6️⃣  Watch the graph update with po / rf / co / sw edges",
                "7️⃣  Use ↩ Undo to go back and try different choices",
                "8️⃣  Use 💡 Hint or 🗺 Reveal path if you get stuck"
        };
        for (String s : lines) {
            Label l = new Label(s);
            l.setWrapText(true);
            l.setFont(Font.font("Arial", 14));
            l.setTextFill(Color.web("#cdd6f4"));
            steps.getChildren().add(l);
        }

        Label tip = new Label("💡  The panel below the thread buttons always tells you exactly what to do next!");
        tip.setWrapText(true);
        tip.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        tip.setTextFill(Color.web("#f9e2af"));
        tip.setPadding(new Insets(8));
        tip.setStyle("-fx-background-color:#313244;-fx-background-radius:6;");

        Button go = new Button("🚀  Let's go!");
        go.setStyle("-fx-background-color:#89b4fa;-fx-text-fill:#1e1e2e;" +
                "-fx-font-weight:bold;-fx-font-size:15;-fx-padding:10 30;");
        go.setOnAction(e -> w.close());

        HBox br = new HBox(go); br.setAlignment(Pos.CENTER_RIGHT);
        box.getChildren().addAll(title, sub, steps, tip, br);

        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color:#1e1e2e;-fx-background:#1e1e2e;");
        w.setScene(new Scene(sp, ww, wh));
        Rectangle2D sc = Screen.getPrimary().getVisualBounds();
        w.setX(sc.getMinX() + (sc.getWidth()  - ww) / 2);
        w.setY(sc.getMinY() + (sc.getHeight() - wh) / 2);
        w.show();
    }

    // =========================================================================
    // UI construction
    // =========================================================================

    @SuppressWarnings("all")
    private void buildUI() {
        root = new BorderPane();
        root.setStyle("-fx-background-color:#1e1e2e;");

        Label title = new Label("  WEAKEST Execution Visualiser");
        title.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        title.setTextFill(Color.WHITE);
        title.setPadding(new Insets(10));
        HBox top = new HBox(title);
        top.setStyle("-fx-background-color:#313244;");
        root.setTop(top);

        // ── Left panel ────────────────────────────────────────────────
        VBox leftContent = new VBox(8);
        leftContent.setPadding(new Insets(10));
        leftContent.setStyle("-fx-background-color:#181825;");

        Label inputLbl = styled("Program Input:", "#6c7086", 12, true);
        programInput = new TextArea(defaultProgram());
        programInput.setPrefHeight(Math.max(140, screenH * 0.14));
        programInput.setFont(Font.font("Monospaced", 12));
        programInput.setStyle("-fx-control-inner-background:#313244;-fx-text-fill:#cdd6f4;");
        Tooltip.install(programInput, tip("Write or paste a program, then click Load Program."));

        Button loadBtn = btn("▶  Load Program", "#89b4fa");
        loadBtn.setOnAction(e -> loadProgram());
        Tooltip.install(loadBtn, tip("Parse and initialise the execution."));

        Button explainBtn = btn("📖  Open Learning Companion", "#f9e2af");
        explainBtn.setOnAction(e -> openExplanationWindow());
        Tooltip.install(explainBtn, tip("Full visual guide to po, rf, co, sw and OOTA."));

        Button quizBtn = btn("🎓  Quiz Mode", "#cba6f7");
        quizBtn.setOnAction(e -> openQuizWindow());
        Tooltip.install(quizBtn, tip("Test your understanding with 5 interactive litmus questions."));

        Button compareBtn = btn("⚖  Compare Models", "#94e2d5");
        compareBtn.setOnAction(e -> openComparisonWindow());
        Tooltip.install(compareBtn, tip("Enumerate all executions and compare SC vs WEAKEST outcomes."));

        Button branchBtn = btn("⑂  Branch Explorer", "#ff6600");
        branchBtn.setOnAction(e -> openBranchExplorer());
        Tooltip.install(branchBtn, tip("Visualise all possible execution branches as a tree."));

        Button modelCmpBtn = btn("🔬  Compare All Models", "#b4befe");
        modelCmpBtn.setOnAction(e -> openModelComparison());
        Tooltip.install(modelCmpBtn, tip("Compare SC, TSO, PSO, RA and WEAKEST side-by-side for this program."));

        Label litmusLbl = styled("📚  Litmus Tests  (click to load):", "#cdd6f4", 13, true);
        HBox row1 = new HBox(6), row2 = new HBox(6), row3 = new HBox(6),
                row4 = new HBox(6);
        //noinspection CssUnknownUnit,CssUnknownProperty,CssInvalidPropertyValue
        String[][] tests = {
                // Row 1 — classic 2-thread
                {"SB",       "#89dceb", litmusSB(),       "Store Buffering — r1=0,r2=0 ALLOWED on x86"},
                {"LB",       "#a6e3a1", litmusLB(),       "Load Buffering — r1=1,r2=1 ALLOWED"},
                {"MP",       "#fab387", litmusMP(),       "Message Passing — acq/rel guarantees ordering"},
                {"CYC",      "#f38ba8", litmusCYC(),      "Cycle / OOTA — r1=1,r2=1 FORBIDDEN"},
                // Row 2 — coherence & multi-thread
                {"IRIW",     "#cba6f7", litmusIRIW(),     "4-thread non-multi-copy atomicity test"},
                {"CoRR",     "#89dceb", litmusCoRR(),     "Coherence Read-Read — r1=1,r2=0 FORBIDDEN"},
                {"CoRW",     "#f9e2af", litmusCoRW(),     "Coherence Read-Write — rf must respect co"},
                {"2+2W",     "#a6e3a1", litmus2p2W(),     "Two-plus-two writes — co must be consistent"},
                // Row 3 — causality & atomic
                {"WRC",      "#fab387", litmusWRC(),      "Write-Read Causality — 3-thread causality chain"},
                {"ISA2",     "#cba6f7", litmusISA2(),     "Store Forwarding + Coherence — 3-thread"},
                {"RMW",      "#89dceb", litmusRMW(),      "Read-Modify-Write — atomic increment pattern"},
                {"3.SB",     "#f38ba8", litmus3SB(),      "3-thread Store Buffering ring — r1=0,r2=0,r3=0 ALLOWED"},
                // Row 4 — OOTA & fence variants
                {"OOTA",     "#f38ba8", litmusOOTA(),     "Out-of-Thin-Air — central dissertation concept, FORBIDDEN"},
                {"LB+fence", "#a6e3a1", litmusLBfence(),  "LB with SC ops — r1=1,r2=1 now FORBIDDEN"},
                {"SB+fence", "#89dceb", litmusSBfence(),  "SB with SC ops — r1=0,r2=0 now FORBIDDEN"},
                {"MP+rlx",   "#f9e2af", litmusMPrelaxed(),"MP without acq/rel — r1=1,r2=0 now ALLOWED"},
        };
        for (int i = 0; i < tests.length; i++) {
            final String n=tests[i][0], c=tests[i][1], p=tests[i][2], t=tests[i][3];
            Button b = new Button(n);
            b.setPrefWidth(Math.max(55, leftW * 0.13));
            b.setStyle("-fx-background-color:"+c+";-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:5;");
            b.setOnAction(e->{ programInput.setText(p); currentLitmus=n; log("📚 Loaded: "+n); });
            Tooltip.install(b, tip(t));
            if      (i < 4)  row1.getChildren().add(b);
            else if (i < 8)  row2.getChildren().add(b);
            else if (i < 12) row3.getChildren().add(b);
            else             row4.getChildren().add(b);
        }

        Label threadLbl = styled("🧵  Execute Thread:", "#cdd6f4", 13, true);
        Tooltip.install(threadLbl, tip("🟢 = deterministic write. 🟡 = choice needed — button pulses, then pick a card below!"));
        threadButtonsBox = new VBox(6);

        HBox actionRow = new HBox(8); actionRow.setAlignment(Pos.CENTER_LEFT);
        undoButton = new Button("↩ Undo");
        undoButton.setStyle("-fx-background-color:#f38ba8;-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:8 12;");
        undoButton.setDisable(true);
        undoButton.setOnAction(e -> performUndo());
        Tooltip.install(undoButton, tip("Go back one step."));
        undoCountLabel = new Label("");
        undoCountLabel.setFont(Font.font("Monospaced", 11));
        undoCountLabel.setTextFill(Color.web("#6c7086"));
        resetButton = new Button("⟳ Reset");
        resetButton.setStyle("-fx-background-color:#a6e3a1;-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:8 12;");
        resetButton.setDisable(true);
        resetButton.setOnAction(e -> resetExecution());
        Tooltip.install(resetButton, tip("Clear and start again."));
        Button exportBtn = new Button("📷 Export PNG");
        exportBtn.setStyle("-fx-background-color:#cba6f7;-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:8 12;");
        exportBtn.setOnAction(e -> exportPNG());
        Tooltip.install(exportBtn, tip("Save the current execution graph as a PNG image."));
        actionRow.getChildren().addAll(undoButton, undoCountLabel, resetButton, exportBtn);

        contextPanel = new VBox(8);
        contextPanel.setPadding(new Insets(12));
        contextPanel.setStyle(contextStyle("#89b4fa"));
        contextTitle = styled("💡  What to do next", "#89b4fa", 14, true);
        contextBody  = new Label("Load a program to get started.");
        contextBody.setWrapText(true);
        contextBody.setFont(Font.font("Arial", 13));
        contextBody.setTextFill(Color.web("#cdd6f4"));
        inlineChoiceBox = new VBox(8);
        contextPanel.getChildren().addAll(contextTitle, contextBody, inlineChoiceBox);

        VBox hintBox = buildHintRow();

        // ── Forbidden Zone panel ──────────────────────────────────────
        forbiddenTitle = new Label("🚫  Forbidden Zone");
        forbiddenTitle.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        forbiddenTitle.setTextFill(Color.web("#f38ba8"));
        forbiddenBody = new VBox(4);
        Label fbPlaceholder = new Label("Execute a step to see which outcomes become impossible.");
        fbPlaceholder.setWrapText(true);
        fbPlaceholder.setFont(Font.font("Arial", 12));
        fbPlaceholder.setTextFill(Color.web("#6c7086"));
        forbiddenBody.getChildren().add(fbPlaceholder);
        forbiddenPanel = new VBox(6, forbiddenTitle, forbiddenBody);
        forbiddenPanel.setPadding(new Insets(10));
        forbiddenPanel.setStyle("-fx-background-color:#1e1e2e;-fx-border-color:#f38ba8;" +
                "-fx-border-width:1px;-fx-border-radius:6;-fx-background-radius:6;");
        forbiddenPanel.setVisible(false);
        forbiddenPanel.setManaged(false);

        Label logLbl = styled("Execution Log:", "#6c7086", 12, true);
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(Math.max(100, screenH * 0.10));
        logArea.setFont(Font.font("Monospaced", 11));
        logArea.setStyle("-fx-control-inner-background:#313244;-fx-text-fill:#a6e3a1;");

        leftContent.getChildren().addAll(
                inputLbl, programInput, loadBtn, explainBtn, quizBtn, compareBtn, branchBtn, modelCmpBtn,
                litmusLbl, row1, row2, row3, row4,
                threadLbl, threadButtonsBox, actionRow,
                contextPanel, forbiddenPanel, hintBox,
                logLbl, logArea);

        ScrollPane leftScroll = new ScrollPane(leftContent);
        leftScroll.setFitToWidth(true);
        leftScroll.setStyle("-fx-background-color:#181825;-fx-background:#181825;-fx-border-color:transparent;");

        // ── Graph area ────────────────────────────────────────────────
        graph = new MultiGraph("ES");
        graph.setAttribute("ui.stylesheet", graphStyle());
        graph.setAttribute("ui.quality");
        graph.setAttribute("ui.antialias");

        viewer = new FxViewer(graph, FxViewer.ThreadingModel.GRAPH_IN_GUI_THREAD);
        viewer.disableAutoLayout();
        graphView = (FxDefaultView) viewer.addDefaultView(false);
        graphView.setPrefSize(screenW - leftW, screenH);
        FxDefaultView view = graphView;

        // ── Legend overlay ────────────────────────────────────────────
        VBox legendBox = new VBox(4);
        legendBox.setPadding(new Insets(8, 10, 8, 10));
        legendBox.setStyle("-fx-background-color:rgba(30,30,46,0.88);-fx-background-radius:8;" +
                "-fx-border-color:#45475a;-fx-border-width:1;-fx-border-radius:8;");
        legendBox.setMaxWidth(Region.USE_PREF_SIZE);
        legendBox.setMaxHeight(Region.USE_PREF_SIZE);
        for (String[] item : new String[][]{
                {"#fab387","INIT"},{"#a6e3a1","READ"},{"#f38ba8","WRITE"},
                {"#89b4fa","─  po"},{"#a6e3a1","─  rf"},
                {"#cba6f7","╌  co"},{"#94e2d5","━  sw"}}) {
            HBox lr = new HBox(6); lr.setAlignment(Pos.CENTER_LEFT);
            javafx.scene.shape.Rectangle dot = new javafx.scene.shape.Rectangle(10,10);
            dot.setArcWidth(3); dot.setArcHeight(3); dot.setFill(Color.web(item[0]));
            Label lbl = new Label(item[1]);
            lbl.setFont(Font.font("Monospaced", 11)); lbl.setTextFill(Color.web(item[0]));
            lr.getChildren().addAll(dot, lbl); legendBox.getChildren().add(lr);
        }
        StackPane graphStack = new StackPane(view, legendBox);
        StackPane.setAlignment(legendBox, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(legendBox, new Insets(0, 14, 14, 0));

        // ── Graph tooltip overlay (click-to-explain) ──────────────────
        // Panel is a bare VBox shell — content is rebuilt dynamically by showTooltipCard()
        tooltipPanel = new VBox(0);
        tooltipPanel.setMaxWidth(240);
        tooltipPanel.setMinWidth(180);
        tooltipPanel.setMaxHeight(Region.USE_PREF_SIZE);  // shrink-wrap height
        tooltipPanel.setMinHeight(Region.USE_PREF_SIZE);
        tooltipPanel.setStyle("-fx-background-color:#181825;" +
                "-fx-border-color:#cba6f7;-fx-border-width:1.5px;" +
                "-fx-border-radius:10;-fx-background-radius:10;" +
                "-fx-effect:dropshadow(gaussian,rgba(203,166,247,0.4),16,0.35,0,0);");
        tooltipPanel.setVisible(false);
        tooltipPanel.setManaged(false);
        tooltipPanel.setOpacity(0);
        tooltipPanel.setScaleX(0.7);
        tooltipPanel.setScaleY(0.7);
        tooltipPanel.setOnMouseClicked(javafx.event.Event::consume);
        graphStack.getChildren().add(tooltipPanel);
        StackPane.setAlignment(tooltipPanel, Pos.TOP_LEFT);
        StackPane.setMargin(tooltipPanel, new Insets(0, 0, 0, 0));

        // Wire graph click → explain node/edge, or hide tooltip if clicking empty space
        view.setOnMouseClicked(evt -> handleGraphClick(evt));

        // ── SplitPane: left controls | right graph ────────────────────
        SplitPane splitPane = new SplitPane(leftScroll, graphStack);
        splitPane.setStyle("-fx-background-color:#1e1e2e;");
        SplitPane.setResizableWithParent(leftScroll, false);

        root.setCenter(splitPane);

        // Maximize the main stage and set divider after layout
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.windowProperty().addListener((obs2, oldWin, win) -> {
                    if (win instanceof Stage s) {
                        s.setMaximized(true);
                        // Set divider after stage is shown and sized
                        Platform.runLater(() ->
                                splitPane.setDividerPositions(leftW / s.getWidth())
                        );
                    }
                });
            }
        });
    }

    private String contextStyle(String colour) {
        return "-fx-background-color:#1e1e2e;" +
                "-fx-border-color:" + colour + ";" +
                "-fx-border-width:0 0 0 5;" +
                "-fx-border-radius:4;" +
                "-fx-background-radius:4;";
    }

    // =========================================================================
    // Context panel helpers
    // =========================================================================

    private void setContext(String title, String body, String colour) {
        stopContextPulse();
        contextTitle.setText(title);
        contextTitle.setTextFill(Color.web(colour));
        contextBody.setText(body);
        contextBody.setTextFill(Color.web("#cdd6f4"));
        inlineChoiceBox.getChildren().clear();
        contextPanel.setStyle(contextStyle(colour));
    }

    private void startContextPulse(String colour) {
        stopContextPulse();
        String dimmed = colour + "88";
        contextPulse = new Timeline(
                new KeyFrame(Duration.ZERO,        e -> contextPanel.setStyle(contextStyle(colour))),
                new KeyFrame(Duration.millis(600),  e -> contextPanel.setStyle(contextStyle(dimmed))),
                new KeyFrame(Duration.millis(1200), e -> contextPanel.setStyle(contextStyle(colour)))
        );
        contextPulse.setCycleCount(Animation.INDEFINITE);
        contextPulse.play();
    }

    private void stopContextPulse() {
        if (contextPulse != null) { contextPulse.stop(); contextPulse = null; }
    }

    private void refreshContextPanel() {
        if (program == null) {
            setContext("💡  Getting Started",
                    "Choose a Litmus Test above (try SB or MP!), then click ▶ Load Program.", "#89b4fa");
            return;
        }
        if (pendingThreadIdx >= 0) return;

        if (executionState.allThreadsDone()) {
            setContext("🎉  Complete!",
                    "All threads finished!\n\n➜ Click ⟳ Reset to try a different ordering.\n" +
                            "➜ Use ↩ Undo to go back and make different rf choices.", "#a6e3a1");
            return;
        }

        List<HintEngine.ThreadInfo> statuses = hintEngine.getThreadStatuses(
                program, executionState, eventStructure, checker);

        boolean anyChoice = statuses.stream()
                .anyMatch(t -> t.status == HintEngine.ThreadStatus.NEEDS_CHOICE);
        if (!anyChoice) stopContextPulse();

        HintEngine.ThreadInfo choiceThread = statuses.stream()
                .filter(t -> t.status == HintEngine.ThreadStatus.NEEDS_CHOICE)
                .findFirst().orElse(null);
        HintEngine.ThreadInfo runThread = statuses.stream()
                .filter(t -> t.status == HintEngine.ThreadStatus.CAN_EXECUTE)
                .findFirst().orElse(null);

        if (choiceThread != null) {
            Instruction instr = executionState.getNextInstruction(choiceThread.threadIndex);
            ReadEvent probe = new ReadEvent(choiceThread.threadIndex + 1, instr.getVariable(),
                    instr.getMemoryOrder(), instr.getLocalVar());
            List<Event> valid = checker.getValidWritesFor(eventStructure, probe);
            boolean cross = valid.stream()
                    .anyMatch(w -> w.getThreadId() != 0 && w.getThreadId() != probe.getThreadId());

            StringBuilder sb = new StringBuilder();
            sb.append("👆  Click the pulsing 🟡 Thread ").append(choiceThread.threadIndex + 1)
                    .append(" button above!\n\n");
            sb.append("Choice cards will appear here so you can pick\n");
            sb.append("what value ").append(instr.getLocalVar()).append(" reads from ")
                    .append(instr.getVariable()).append(":\n\n");
            for (Event w : valid) {
                sb.append("  • value ").append(w.getValue());
                if (w.getThreadId() == 0) sb.append("  (initial)");
                else sb.append("  ← Thread ").append(w.getThreadId()).append(" ⚡");
                sb.append("\n");
            }
            if (cross) sb.append("\n💡 Pick the ⚡ value to see weak memory!");
            setContext("🟡  Decision Point — Click Thread " + (choiceThread.threadIndex + 1) + "!",
                    sb.toString(), "#f9e2af");
            startContextPulse("#f9e2af");

        } else if (runThread != null) {
            Instruction instr = executionState.getNextInstruction(runThread.threadIndex);
            StringBuilder sb = new StringBuilder();
            sb.append("👆  Click 🟢 Thread ").append(runThread.threadIndex + 1)
                    .append(" to execute:\n").append(instr).append("\n\n");
            sb.append("All threads:\n");
            for (HintEngine.ThreadInfo ti : statuses)
                sb.append("  ").append(ti.emoji).append(" Thread ")
                        .append(ti.threadIndex + 1).append(": ").append(ti.nextInstruction).append("\n");
            setContext("🟢  Ready — Click Thread " + (runThread.threadIndex + 1), sb.toString(), "#a6e3a1");
        }
    }

    // =========================================================================
    // Forbidden Zone panel
    // =========================================================================

    private void refreshForbiddenZone() {
        if (program == null || currentLitmus == null || executionState == null) {
            forbiddenPanel.setVisible(false);
            forbiddenPanel.setManaged(false);
            return;
        }

        // Compute which named outcomes are now forbidden given current rf choices
        List<String[]> outcomes = getLitmusOutcomes(currentLitmus);
        if (outcomes.isEmpty()) {
            forbiddenPanel.setVisible(false);
            forbiddenPanel.setManaged(false);
            return;
        }

        // Get current committed rf assignments
        Map<Integer, Integer> currentRf = eventStructure.getReadsFrom();

        List<String[]> forbidden  = new ArrayList<>();
        List<String[]> possible   = new ArrayList<>();
        List<String[]> achieved   = new ArrayList<>();

        for (String[] outcome : outcomes) {
            String label    = outcome[0]; // e.g. "r1=0, r2=0"
            String category = outcome[1]; // "ALLOWED" or "FORBIDDEN"
            String reason   = outcome[2]; // short explanation

            // Check if current rf already contradicts this outcome
            boolean contradicted = isOutcomeContradicted(label, currentRf);
            boolean achieved_now = isOutcomeAchieved(label);

            if (achieved_now)          achieved.add(outcome);
            else if (contradicted)     forbidden.add(outcome);
            else                       possible.add(outcome);
        }

        forbiddenBody.getChildren().clear();

        // Show achieved outcomes in green
        for (String[] o : achieved) {
            HBox row = outcomeRow("✅", o[0], o[1].equals("ALLOWED") ? "WEAKEST ALLOWS" : "WEAKEST FORBIDS",
                    o[1].equals("ALLOWED") ? "#a6e3a1" : "#f38ba8", o[2]);
            forbiddenBody.getChildren().add(row);
        }

        // Show still-possible outcomes in yellow
        for (String[] o : possible) {
            HBox row = outcomeRow("🟡", o[0], "still possible",  "#f9e2af", o[2]);
            forbiddenBody.getChildren().add(row);
        }

        // Show newly-ruled-out outcomes in red
        for (String[] o : forbidden) {
            HBox row = outcomeRow("❌", o[0], "ruled out by your choices", "#f38ba8", o[2]);
            forbiddenBody.getChildren().add(row);
        }

        if (forbiddenBody.getChildren().isEmpty()) {
            Label none = new Label("No outcomes tracked for this program.");
            none.setTextFill(Color.web("#6c7086"));
            none.setFont(Font.font("Arial", 12));
            forbiddenBody.getChildren().add(none);
        }

        int ruledOut = forbidden.size();
        forbiddenTitle.setText("🚫  Forbidden Zone  (" + ruledOut + " ruled out, "
                + possible.size() + " still possible)");

        forbiddenPanel.setVisible(true);
        forbiddenPanel.setManaged(true);
    }

    private HBox outcomeRow(String icon, String outcome, String status, String color, String reason) {
        VBox content = new VBox(1);
        Label top = new Label(icon + "  " + outcome + "  —  " + status);
        top.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        top.setTextFill(Color.web(color));
        top.setWrapText(true);
        Label sub = new Label("    " + reason);
        sub.setFont(Font.font("Arial", 11));
        sub.setTextFill(Color.web("#6c7086"));
        sub.setWrapText(true);
        content.getChildren().addAll(top, sub);
        HBox row = new HBox(content);
        row.setPadding(new Insets(4, 6, 4, 6));
        row.setStyle("-fx-background-color:#313244;-fx-background-radius:4;");
        return row;
    }

    /** Returns named outcomes for each litmus test: [label, ALLOWED/FORBIDDEN, reason] */
    private List<String[]> getLitmusOutcomes(String litmus) {
        return switch (litmus.toUpperCase()) {
            case "SB" -> List.of(
                    new String[]{"r1=0, r2=0", "ALLOWED",  "store buffering — both reads saw initial value"},
                    new String[]{"r1=1, r2=0", "ALLOWED",  "T1 saw T2's write, T2 saw initial X"},
                    new String[]{"r1=0, r2=1", "ALLOWED",  "T1 saw initial Y, T2 saw T1's write"},
                    new String[]{"r1=1, r2=1", "ALLOWED",  "both threads saw each other's writes (SC)"}
            );
            case "LB" -> List.of(
                    new String[]{"r1=1, r2=1", "ALLOWED",  "load buffering — reads reordered ahead of writes"},
                    new String[]{"r1=0, r2=0", "ALLOWED",  "both threads read initial values"},
                    new String[]{"r1=1, r2=0", "ALLOWED",  "T1 read T2's write, T2 read initial Y"},
                    new String[]{"r1=0, r2=1", "ALLOWED",  "T1 read initial X, T2 read T1's write"}
            );
            case "MP" -> List.of(
                    new String[]{"r1=1, r2=1", "ALLOWED",  "perfect sync — acq/rel pair worked"},
                    new String[]{"r1=0, r2=0", "ALLOWED",  "T2 ran before T1 wrote anything"},
                    new String[]{"r1=0, r2=1", "ALLOWED",  "T2 saw X=1 but not Y=1 yet"},
                    new String[]{"r1=1, r2=0", "FORBIDDEN", "sw edge forbids seeing Y=1 without X=1"}
            );
            case "CYC" -> List.of(
                    new String[]{"r1=0, r2=0", "ALLOWED",  "only buildable outcome — causality preserved"},
                    new String[]{"r1=1, r2=1", "FORBIDDEN", "causality cycle — OOTA value, blocked by WEAKEST"},
                    new String[]{"r1=1, r2=0", "FORBIDDEN", "would require T1 to read a value that doesn't exist yet"},
                    new String[]{"r1=0, r2=1", "FORBIDDEN", "would require T2 to read a value that doesn't exist yet"}
            );
            case "IRIW" -> List.of(
                    new String[]{"r1=1,r2=0,r3=1,r4=0", "ALLOWED",  "non-MCA — threads saw writes in opposite orders"},
                    new String[]{"r1=1,r2=1,r3=1,r4=1", "ALLOWED",  "SC-compatible — both threads saw both writes"},
                    new String[]{"r1=0,r2=0,r3=0,r4=0", "ALLOWED",  "both readers ran before any writes"},
                    new String[]{"r1=1,r2=1,r3=1,r4=0", "ALLOWED",  "T3 saw both writes, T4 only saw Y"}
            );
            case "CoRR" -> List.of(
                    new String[]{"r1=1, r2=1", "ALLOWED",  "both threads saw the new write — coherent"},
                    new String[]{"r1=0, r2=0", "ALLOWED",  "both threads saw the initial value"},
                    new String[]{"r1=0, r2=1", "ALLOWED",  "T3 ran before T2 saw the write"},
                    new String[]{"r1=1, r2=0", "FORBIDDEN", "coherence violation — T2 saw new X but T3 didn't"}
            );
            case "2+2W" -> List.of(
                    new String[]{"X=1, Y=2", "ALLOWED",  "T1's X write and T2's Y write won co"},
                    new String[]{"X=2, Y=1", "ALLOWED",  "T2's X write and T1's Y write won co"},
                    new String[]{"X=1, Y=1", "ALLOWED",  "T1 won both coherence orders"},
                    new String[]{"X=2, Y=2", "ALLOWED",  "T2 won both coherence orders"}
            );
            case "WRC" -> List.of(
                    new String[]{"r1=1, r2=1, r3=1", "ALLOWED",  "full causality chain propagated"},
                    new String[]{"r1=0, r2=0, r3=0", "ALLOWED",  "T3 ran before T1 wrote"},
                    new String[]{"r1=1, r2=1, r3=0", "FORBIDDEN", "causality violation — T3 saw Y=1 but not X=1"},
                    new String[]{"r1=1, r2=0, r3=0", "ALLOWED",  "T2 saw X=1 but wrote Y=0, T3 read initial"}
            );
            case "RMW" -> List.of(
                    new String[]{"r1=0, r2=1", "ALLOWED",  "T1 read X=0 then T2 read X=1 — correct ordering"},
                    new String[]{"r1=1, r2=0", "ALLOWED",  "T2 read X=0 then T1 read X=1 — correct ordering"},
                    new String[]{"r1=0, r2=0", "FORBIDDEN", "both read initial — lost update, one write invisible"},
                    new String[]{"r1=1, r2=1", "FORBIDDEN", "both read each other's write — impossible without atomics"}
            );
            case "ISA2" -> List.of(
                    new String[]{"r1=1, r2=1, r3=1", "ALLOWED",  "full chain propagated with sync"},
                    new String[]{"r1=0, r2=0, r3=0", "ALLOWED",  "T2 and T3 ran before T1"},
                    new String[]{"r1=1, r2=1, r3=0", "FORBIDDEN", "causality violation — T3 saw Z=1 but not X=1"},
                    new String[]{"r1=1, r2=0, r3=0", "ALLOWED",  "T2 forwarded Y=1 but Z=0 when T3 read"}
            );
            case "CoRW" -> List.of(
                    new String[]{"r1=0", "ALLOWED",  "T2 read initial X=0, then wrote X=2"},
                    new String[]{"r1=1", "ALLOWED",  "T2 read T1's X=1, then co(W1,W2) holds naturally"},
                    new String[]{"r1=1 then co(W2,W1)", "FORBIDDEN", "T2 read X=1 but its write came before T1's in co — violation"}
            );
            case "LB+fence" -> List.of(
                    new String[]{"r1=0, r2=0", "ALLOWED",  "both threads read initial values — SC"},
                    new String[]{"r1=1, r2=0", "ALLOWED",  "T1 saw T2's write, T2 read initial"},
                    new String[]{"r1=0, r2=1", "ALLOWED",  "T2 saw T1's write, T1 read initial"},
                    new String[]{"r1=1, r2=1", "FORBIDDEN", "SC ops forbid load buffering — no reordering!"}
            );
            case "SB+fence" -> List.of(
                    new String[]{"r1=1, r2=1", "ALLOWED",  "SC — both threads saw each other's writes"},
                    new String[]{"r1=1, r2=0", "ALLOWED",  "T1 saw T2's write, T2 read initial X"},
                    new String[]{"r1=0, r2=1", "ALLOWED",  "T2 saw T1's write, T1 read initial Y"},
                    new String[]{"r1=0, r2=0", "FORBIDDEN", "SC fences drain store buffer — weak outcome impossible!"}
            );
            case "MP+rlx" -> List.of(
                    new String[]{"r1=1, r2=1", "ALLOWED",  "both reads succeeded — lucky ordering"},
                    new String[]{"r1=0, r2=0", "ALLOWED",  "T2 ran before T1 wrote anything"},
                    new String[]{"r1=0, r2=1", "ALLOWED",  "T2 saw X=1 but not Y=1 yet"},
                    new String[]{"r1=1, r2=0", "ALLOWED",  "WITHOUT acq/rel this is now ALLOWED! No sw edge."}
            );
            case "OOTA" -> List.of(
                    new String[]{"r1=0, r2=0", "ALLOWED",  "only buildable outcome — no thin-air values"},
                    new String[]{"r1=1, r2=1", "FORBIDDEN", "OOTA — r1=1 requires Y=1 requires r2=1 requires X=1 requires r1=1 — circular!"},
                    new String[]{"r1=1, r2=0", "FORBIDDEN", "r1=1 requires X=1 which requires r2=1 — contradiction"},
                    new String[]{"r1=0, r2=1", "FORBIDDEN", "r2=1 requires Y=1 which requires r1=1 — contradiction"}
            );
            case "3.SB" -> List.of(
                    new String[]{"r1=0, r2=0, r3=0", "ALLOWED",  "3-way store buffering — all three reads saw initial values"},
                    new String[]{"r1=1, r2=0, r3=0", "ALLOWED",  "T1 saw T2's Y, others saw initial values"},
                    new String[]{"r1=0, r2=1, r3=0", "ALLOWED",  "T2 saw T3's Z, others saw initial values"},
                    new String[]{"r1=0, r2=0, r3=1", "ALLOWED",  "T3 saw T1's X, others saw initial values"},
                    new String[]{"r1=1, r2=1, r3=1", "ALLOWED",  "SC outcome — all threads saw each other's writes"},
                    new String[]{"r1=1, r2=1, r3=0", "ALLOWED",  "T1 and T2 saw cross-thread writes, T3 saw initial X"}
            );
            default -> List.of();
        };
    }

    /** Check if current rf choices already contradict a named outcome */
    private boolean isOutcomeContradicted(String outcomeLabel, Map<Integer, Integer> rf) {
        if (rf.isEmpty()) return false;
        Map<String, Integer> committed = getCommittedRegisterValues();
        if (committed.isEmpty()) return false;
        // Parse outcome label e.g. "r1=0, r2=1" or "r1=1,r2=0,r3=1,r4=0"
        for (String part : outcomeLabel.split(",")) {
            part = part.trim();
            if (!part.contains("=")) continue;
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            String reg = "@" + kv[0].trim();
            try {
                int expected = Integer.parseInt(kv[1].trim());
                Integer actual = committed.get(reg);
                if (actual != null && actual != expected) return true; // contradicted
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    /** Check if outcome is fully achieved (all registers match) */
    private boolean isOutcomeAchieved(String outcomeLabel) {
        if (!executionState.allThreadsDone()) return false;
        Map<String, Integer> all = executionState.getAllLocalVars();
        for (String part : outcomeLabel.split(",")) {
            part = part.trim();
            if (!part.contains("=")) continue;
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            String reg = "@" + kv[0].trim();
            try {
                int expected = Integer.parseInt(kv[1].trim());
                Integer actual = all.get(reg);
                if (actual == null || actual != expected) return false;
            } catch (NumberFormatException ignored) { return false; }
        }
        return true;
    }

    /** Get register values that are already committed (reads completed) */
    private Map<String, Integer> getCommittedRegisterValues() {
        return executionState.getAllLocalVars();
    }

    // =========================================================================
    // Inline read-from choice
    // =========================================================================

    private void showInlineChoice(int idx, Instruction instr, List<Event> valid) {
        pendingThreadIdx = idx;
        pendingInstr     = instr;
        pendingRead      = null;
        pendingValid     = valid;

        stopContextPulse();
        boolean cross = valid.stream()
                .anyMatch(w -> w.getThreadId() != 0 && w.getThreadId() != idx + 1);

        contextTitle.setText("🟡  Choose: What does " + instr.getLocalVar() + " read from "
                + instr.getVariable() + "?");
        contextTitle.setTextFill(Color.web("#f9e2af"));
        contextBody.setText(
                "Thread " + (idx+1) + " is reading " + instr.getVariable()
                        + " [" + instr.getMemoryOrder().name().toLowerCase() + "]\n"
                        + "Click a card below, then click ✅ Confirm.");
        contextBody.setTextFill(Color.web("#a6adc8"));
        contextPanel.setStyle(contextStyle("#f9e2af"));

        inlineChoiceBox.getChildren().clear();

        final Event[] chosen = {valid.get(0)};
        List<VBox> cards = new ArrayList<>();

        for (int i = 0; i < valid.size(); i++) {
            Event w     = valid.get(i);
            boolean isCross  = w.getThreadId() != 0 && w.getThreadId() != idx + 1;
            boolean isInit   = w.getThreadId() == 0;
            boolean syncing  = (w.getMemoryOrder() == MemoryOrder.RELEASE || w.getMemoryOrder() == MemoryOrder.SC)
                    && (instr.getMemoryOrder() == MemoryOrder.ACQUIRE || instr.getMemoryOrder() == MemoryOrder.SC);
            boolean first    = (i == 0);

            VBox card = new VBox(5);
            card.setPadding(new Insets(10, 12, 10, 12));
            card.setStyle(
                    "-fx-background-color:" + (first ? "#2e2e4e" : "#252535") + ";" +
                            "-fx-border-color:"     + (first ? "#89b4fa" : "#45475a") + ";" +
                            "-fx-border-width:2;-fx-border-radius:8;-fx-background-radius:8;" +
                            "-fx-cursor:hand;"
            );
            cards.add(card);

            HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT);
            Label badge = new Label(" " + w.getValue() + " ");
            badge.setFont(Font.font("Arial", FontWeight.BOLD, 20));
            badge.setTextFill(Color.web("#1e1e2e"));
            badge.setStyle("-fx-background-color:" + (isInit ? "#fab387" : isCross ? "#a6e3a1" : "#89b4fa")
                    + ";-fx-background-radius:6;-fx-padding:2 10;");

            String src = isInit ? "Initial value (set in init block)"
                    : "Written by Thread " + w.getThreadId() + " → " + w;
            Label srcLbl = new Label(src);
            srcLbl.setFont(Font.font("Arial", FontWeight.BOLD, 13));
            srcLbl.setTextFill(Color.web("#cdd6f4"));
            srcLbl.setWrapText(true);
            row.getChildren().addAll(badge, srcLbl);
            if (isCross) {
                Label star = new Label("  ⭐ Interesting!  ");
                star.setStyle("-fx-background-color:#f9e2af;-fx-text-fill:#1e1e2e;" +
                        "-fx-font-weight:bold;-fx-background-radius:4;-fx-font-size:11;");
                row.getChildren().add(star);
            }

            String meaning = isInit && !cross
                    ? "📖 Only option — gives the SC-compatible outcome."
                    : isInit
                    ? "📖 Safe choice. Sequential Consistency always gives this."
                    : isCross && syncing
                    ? "🔵 Cross-thread with release/acquire sync! sw edge will appear."
                    : isCross
                    ? "⚡ Weak memory! " + instr.getLocalVar() + " sees another thread's write.\n   IMPOSSIBLE under Sequential Consistency!"
                    : "📖 Same-thread earlier write.";
            Label mLbl = new Label(meaning);
            mLbl.setWrapText(true);
            mLbl.setFont(Font.font("Arial", 12));
            mLbl.setTextFill(Color.web(isCross ? "#f9e2af" : "#a6adc8"));

            String outcome = predictOutcome(instr, w, idx);
            Label oLbl = new Label("➜  " + outcome);
            oLbl.setWrapText(true);
            oLbl.setFont(Font.font("Arial", FontPosture.ITALIC, 11));
            oLbl.setTextFill(Color.web("#6c7086"));

            card.getChildren().addAll(row, mLbl, oLbl);

            final Event thisW  = w;
            final int thisIdx  = i;
            card.setOnMouseClicked(e -> {
                chosen[0] = thisW;
                for (int j = 0; j < cards.size(); j++) {
                    boolean sel = (j == thisIdx);
                    cards.get(j).setStyle(
                            "-fx-background-color:" + (sel ? "#2e2e4e" : "#252535") + ";" +
                                    "-fx-border-color:" + (sel ? "#a6e3a1" : "#45475a") + ";" +
                                    "-fx-border-width:2;-fx-border-radius:8;-fx-background-radius:8;-fx-cursor:hand;"
                    );
                }
            });
            inlineChoiceBox.getChildren().add(card);
        }

        Button confirm = new Button(valid.size() == 1 ? "👍  Got it, continue!" : "✅  Confirm Choice");
        confirm.setMaxWidth(Double.MAX_VALUE);
        confirm.setStyle("-fx-background-color:#a6e3a1;-fx-text-fill:#1e1e2e;" +
                "-fx-font-weight:bold;-fx-font-size:13;-fx-padding:10;");
        confirm.setOnAction(e -> {
            try {
                int saved        = pendingThreadIdx;
                Instruction si   = pendingInstr;
                Event pick       = chosen[0];

                pendingThreadIdx = -1;
                pendingInstr = null; pendingRead = null; pendingValid = null;
                inlineChoiceBox.getChildren().clear();
                stopContextPulse();

                ReadEvent realRead = new ReadEvent(saved + 1, si.getVariable(),
                        si.getMemoryOrder(), si.getLocalVar());

                completeRead(saved, si, realRead, pick);

                shadowPCs[saved] = executionState.isThreadDone(saved)
                        ? program.getThreads().get(saved).size()
                        : shadowPCs[saved] + 1;

                graph.setAttribute("ui.stylesheet", graphStyle());

                updateButtons();
                refreshContextPanel();
                refreshHintStatus();
                refreshForbiddenZone();
                if (executionState.allThreadsDone()) finishExecution();

            } catch (Exception ex) {
                log("❌ Error in confirm: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                ex.printStackTrace();
            }
        });
        inlineChoiceBox.getChildren().add(confirm);

        startContextPulse("#f9e2af");
    }

    // =========================================================================
    // Hint panel
    // =========================================================================

    private VBox buildHintRow() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(4, 0, 0, 0));
        hintStatusLabel = new Label("Hint System");
        hintStatusLabel.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        hintStatusLabel.setTextFill(Color.web("#6c7086"));
        HBox br = new HBox(8);
        hintBtn = new Button("💡 Give me a hint");
        hintBtn.setStyle("-fx-background-color:#313244;-fx-text-fill:#f9e2af;-fx-font-weight:bold;-fx-padding:6;");
        hintBtn.setDisable(true);
        hintBtn.setOnAction(e -> showHintDialog());
        Tooltip.install(hintBtn, tip("Detailed rf choices and outcome predictions."));
        revealBtn = new Button("🗺 Reveal path");
        revealBtn.setStyle("-fx-background-color:#313244;-fx-text-fill:#cba6f7;-fx-font-weight:bold;-fx-padding:6;");
        revealBtn.setDisable(true);
        revealBtn.setOnAction(e -> showRevealDialog());
        Tooltip.install(revealBtn, tip("Full step-by-step path to the most interesting outcome."));
        br.getChildren().addAll(hintBtn, revealBtn);
        box.getChildren().addAll(hintStatusLabel, br);
        return box;
    }

    private void refreshHintStatus() {
        if (program == null || executionState == null) return;
        List<HintEngine.ThreadInfo> st = hintEngine.getThreadStatuses(
                program, executionState, eventStructure, checker);
        StringBuilder sb = new StringBuilder();
        for (HintEngine.ThreadInfo ti : st)
            sb.append(ti.emoji).append(" T").append(ti.threadIndex+1).append("  ");
        hintStatusLabel.setText(sb.toString().trim());
        hintBtn.setDisable(st.stream().allMatch(t -> t.status == HintEngine.ThreadStatus.DONE));
        revealBtn.setDisable(false);
    }

    private void showHintDialog() {
        if (program == null) return;
        List<HintEngine.ThreadInfo> st = hintEngine.getThreadStatuses(
                program, executionState, eventStructure, checker);
        StringBuilder sb = new StringBuilder("📋  STATE\n─────────────────\n\n");
        for (HintEngine.ThreadInfo ti : st)
            sb.append(ti.emoji).append("  Thread ").append(ti.threadIndex+1)
                    .append(": ").append(ti.nextInstruction).append("\n");
        sb.append("\n");
        HintEngine.ThreadInfo focus = st.stream()
                .filter(t -> t.status == HintEngine.ThreadStatus.NEEDS_CHOICE).findFirst()
                .orElse(st.stream().filter(t -> t.status == HintEngine.ThreadStatus.CAN_EXECUTE)
                        .findFirst().orElse(null));
        if (focus != null && focus.status == HintEngine.ThreadStatus.NEEDS_CHOICE) {
            Instruction instr = executionState.getNextInstruction(focus.threadIndex);
            ReadEvent probe = new ReadEvent(focus.threadIndex+1, instr.getVariable(),
                    instr.getMemoryOrder(), instr.getLocalVar());
            List<Event> v = checker.getValidWritesFor(eventStructure, probe);
            HintEngine.RfHint h = hintEngine.buildRfHint(probe, v, eventStructure, checker);
            sb.append("🟡 Thread ").append(focus.threadIndex+1).append(" needs to choose:\n");
            sb.append(h.summary).append("\n\n");
            for (String c : h.choices) sb.append(c).append("\n");
            if (!h.outcomeWarning.isEmpty()) sb.append("\n").append(h.outcomeWarning);
        } else if (focus != null) {
            sb.append("🟢 Next: Thread ").append(focus.threadIndex+1).append(" → ").append(focus.nextInstruction);
        } else sb.append("🎉 All done!");
        showDialog("💡  Hint", sb.toString(), "#f9e2af");
    }

    private void showRevealDialog() {
        String name = currentLitmus != null ? currentLitmus : "custom";
        HintEngine.RevealPath path = hintEngine.getRevealPath(name, executionState, eventStructure, checker);
        StringBuilder sb = new StringBuilder();
        sb.append("🎯  Goal:\n").append(path.targetOutcome).append("\n\n");
        sb.append("👉  YOUR NEXT STEP:\n");
        sb.append("─────────────────────────────\n");
        sb.append(path.nextStep).append("\n\n");
        sb.append("📋  Full path (" + path.stepsCompleted + " steps done):\n");
        sb.append("─────────────────────────────\n");
        for (String step : path.steps) sb.append(step).append("\n");
        sb.append("\n📖  Why this matters:\n").append(path.explanation);
        showDialog("🗺  " + path.title, sb.toString(), "#cba6f7");
    }

    private void showDialog(String title, String body, String accent) {
        Stage d = new Stage();
        d.setTitle(title);
        double dw = Math.min(600, screenW * 0.85);
        double dh = Math.min(500, screenH * 0.70);
        VBox c = new VBox(12); c.setPadding(new Insets(20));
        c.setStyle("-fx-background-color:#1e1e2e;");
        Label tl = new Label(title);
        tl.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        tl.setTextFill(Color.web(accent));
        TextArea ba = new TextArea(body);
        ba.setEditable(false); ba.setWrapText(true); ba.setPrefHeight(dh - 120);
        ba.setFont(Font.font("Arial", 13));
        ba.setStyle("-fx-control-inner-background:#313244;-fx-text-fill:#cdd6f4;" +
                "-fx-border-color:"+accent+";-fx-border-radius:6;");
        Button cl = new Button("Got it!");
        cl.setStyle("-fx-background-color:"+accent+";-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:8 20;");
        cl.setOnAction(e -> d.close());
        HBox br = new HBox(cl); br.setAlignment(Pos.CENTER_RIGHT);
        c.getChildren().addAll(tl, ba, br);
        d.setScene(new Scene(c, dw, dh));
        Rectangle2D sc = Screen.getPrimary().getVisualBounds();
        d.setX(sc.getMinX() + (sc.getWidth()-dw)/2);
        d.setY(sc.getMinY() + (sc.getHeight()-dh)/2);
        d.show();
    }

    // =========================================================================
    // Explanation window
    // =========================================================================

    private void openExplanationWindow() {
        if (explanationWindow == null) explanationWindow = new ExplanationWindow();
        if (eventStructure != null)
            explanationWindow.update(eventStructure, executionState, program, "Opened manually.");
        explanationWindow.show();
    }

    private void openQuizWindow() {
        new QuizWindow().show();
    }

    private void openModelComparison() {
        if (program == null) {
            log("⚠️ Load a program first before opening Model Comparison!");
            return;
        }
        String name = currentLitmus != null ? currentLitmus : "custom";
        modelComparisonWindow = new ModelComparisonWindow(program, name);
        modelComparisonWindow.show();
    }

    private void openBranchExplorer() {
        if (program == null) {
            log("⚠️ Load a program first before opening Branch Explorer!");
            return;
        }
        String name = currentLitmus != null ? currentLitmus : "custom";
        branchExplorerWindow = new BranchExplorerWindow(program, name);
        branchExplorerWindow.show();
    }

    private void openComparisonWindow() {
        if (program == null) {
            log("⚠️ Load a program first before comparing!");
            return;
        }
        String name = currentLitmus != null ? currentLitmus : "custom";
        comparisonWindow = new ComparisonWindow(program, name);
        comparisonWindow.show();
    }

    private void exportPNG() {
        if (graphView == null) {
            log("⚠️ No graph to export yet.");
            return;
        }
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Save Graph as PNG");
        String defaultName = (currentLitmus != null ? currentLitmus : "execution") + "_graph.png";
        fc.setInitialFileName(defaultName);
        fc.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("PNG Image", "*.png"));
        java.io.File file = fc.showSaveDialog(root.getScene().getWindow());
        if (file == null) return;

        // Snapshot the graph view node
        javafx.scene.image.WritableImage img = graphView.snapshot(
                new javafx.scene.SnapshotParameters(), null);
        try {
            javax.imageio.ImageIO.write(
                    javafx.embed.swing.SwingFXUtils.fromFXImage(img, null),
                    "PNG", file);
            log("📷 Graph exported to: " + file.getName());
        } catch (java.io.IOException ex) {
            log("❌ Export failed: " + ex.getMessage());
        }
    }

    private void notifyExplanation(String msg) {
        if (explanationWindow != null && explanationWindow.isShowing())
            explanationWindow.update(eventStructure, executionState, program, msg);
    }

    // =========================================================================
    // Thread buttons
    // =========================================================================

    private void updateButtons() {
        threadButtonsBox.getChildren().clear();
        if (program == null) return;
        HBox row = new HBox(6);
        for (int i = 0; i < program.getThreadCount(); i++) {
            final int idx = i;
            boolean done = executionState.isThreadDone(i);
            Instruction next = executionState.getNextInstruction(i);
            HintEngine.ThreadStatus status = done ? HintEngine.ThreadStatus.DONE
                    : (next != null && next.getType() == Instruction.InstructionType.READ
                    ? HintEngine.ThreadStatus.NEEDS_CHOICE : HintEngine.ThreadStatus.CAN_EXECUTE);
            String col = switch(status){case DONE->"#45475a";case CAN_EXECUTE->"#a6e3a1";default->"#f9e2af";};
            String em  = switch(status){case DONE->"🔴";case CAN_EXECUTE->"🟢";default->"🟡";};
            String lbl = done ? em+" T"+(i+1)+"\n(done)"
                    : em+" T"+(i+1)+"\n"+(next.getType()==Instruction.InstructionType.READ
                    ? "READ "+next.getVariable()+" (choose!)"
                    : "WRITE "+next.getVariable()+"="+next.getValueExpr());
            Button b = new Button(lbl);
            b.setWrapText(true); b.setDisable(done);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setStyle("-fx-background-color:"+col+";-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:8;-fx-font-size:12;");
            b.setOnAction(e -> executeThread(idx));
            if (pendingThreadIdx >= 0) b.setDisable(true);
            Tooltip.install(b, tip(done ? "Done." : status==HintEngine.ThreadStatus.NEEDS_CHOICE
                    ? "Click to see the read-from choice cards in the panel below!"
                    : "Click to execute this write — a graph edge will be added."));
            HBox.setHgrow(b, Priority.ALWAYS);
            if (status == HintEngine.ThreadStatus.NEEDS_CHOICE) {
                Timeline pulse = new Timeline(
                        new KeyFrame(Duration.ZERO,        new KeyValue(b.opacityProperty(), 1.0)),
                        new KeyFrame(Duration.millis(600),  new KeyValue(b.opacityProperty(), 0.45)),
                        new KeyFrame(Duration.millis(1200), new KeyValue(b.opacityProperty(), 1.0))
                );
                pulse.setCycleCount(Animation.INDEFINITE);
                pulse.play();
                b.setUserData(pulse);
            }
            row.getChildren().add(b);
        }
        threadButtonsBox.getChildren().add(row);
    }

    // =========================================================================
    // Undo
    // =========================================================================

    private void pushSnapshot() {
        if (program == null) return;
        int[] pcs = Arrays.copyOf(shadowPCs, program.getThreadCount());
        Map<String,Integer> vars = new HashMap<>(executionState.getAllLocalVars());
        List<Integer> lastIds = new ArrayList<>();
        for (int i = 0; i < program.getThreadCount(); i++) {
            Event e = executionState.getLastEventForThread(i);
            lastIds.add(e != null ? e.getId() : -1);
        }
        List<ExecutionSnapshot.EventRecord> evRecs = new ArrayList<>();
        for (Event e : eventStructure.getEvents())
            evRecs.add(new ExecutionSnapshot.EventRecord(e.getId(), e.getThreadId(),
                    e.getType().name(), e.getVariable(), e.getValue(),
                    e.getMemoryOrder().name(), (e instanceof ReadEvent re) ? re.getLocalVar() : null));
        Map<Integer,List<Integer>> po = new HashMap<>();
        eventStructure.getProgramOrder().forEach((k,v) -> po.put(k, new ArrayList<>(v)));
        Map<Integer,Integer> rf = new HashMap<>(eventStructure.getReadsFrom());
        Map<String,List<Integer>> co = new HashMap<>();
        eventStructure.getCoherenceOrder().forEach((k,v) -> co.put(k, new ArrayList<>(v)));
        List<ExecutionSnapshot.NodeRecord> nodes = new ArrayList<>();
        nodeCssClass.forEach((id,css) -> {
            double[] xy = nodeXY.getOrDefault(id, new double[]{0,0});
            nodes.add(new ExecutionSnapshot.NodeRecord(id, css, xy[0], xy[1]));
        });
        List<ExecutionSnapshot.EdgeRecord> edges = new ArrayList<>();
        graph.edges().forEach(edge -> edges.add(new ExecutionSnapshot.EdgeRecord(
                edge.getId(), edge.getSourceNode().getId(), edge.getTargetNode().getId(),
                (String)edge.getAttribute("ui.class"), (String)edge.getAttribute("ui.label"))));
        for (Map.Entry<Integer,Integer> e : threadEventCount.entrySet())
            co.put("__tec__"+e.getKey(), new ArrayList<>(List.of(e.getValue())));
        undoStack.push(new ExecutionSnapshot(pcs, vars, lastIds, evRecs, po, rf, co, nodes, edges));
        if (undoStack.size() > MAX_UNDO) {
            Deque<ExecutionSnapshot> t = new ArrayDeque<>(undoStack);
            while (t.size() > MAX_UNDO) t.pollLast();
            undoStack.clear(); undoStack.addAll(t);
        }
        updateUndoButton();
    }

    private void performUndo() {
        if (undoStack.isEmpty()) return;
        pendingThreadIdx = -1; pendingInstr = null; pendingRead = null; pendingValid = null;
        restoreSnapshot(undoStack.pop());
        updateUndoButton(); updateButtons(); refreshContextPanel(); refreshHintStatus(); refreshForbiddenZone();
        log("↩ Undone.");
        notifyExplanation("↩ Undo.");
    }

    private void restoreSnapshot(ExecutionSnapshot snap) {
        eventStructure = new EventStructure();
        Map<Integer,Event> idToEvent = new LinkedHashMap<>();
        for (ExecutionSnapshot.EventRecord rec : snap.events) {
            Event e = rec.type().equals("READ")
                    ? new ReadEvent(rec.threadId(), rec.variable(), MemoryOrder.valueOf(rec.memOrder()), rec.localVar())
                    : new WriteEvent(rec.threadId(), rec.variable(), MemoryOrder.valueOf(rec.memOrder()), rec.value(), String.valueOf(rec.value()));
            Event.forceId(e, rec.id());
            eventStructure.addEvent(e);
            idToEvent.put(rec.id(), e);
        }
        snap.programOrder.forEach((fid,tids) -> tids.forEach(tid -> {
            Event f=idToEvent.get(fid), t=idToEvent.get(tid);
            if(f!=null&&t!=null) eventStructure.addProgramOrder(f,t);
        }));
        snap.readsFrom.forEach((rid,wid) -> {
            Event r=idToEvent.get(rid), w=idToEvent.get(wid);
            if(r instanceof ReadEvent re && w!=null) eventStructure.addReadsFrom(re,w);
        });
        snap.coherenceOrder.forEach((var,ids) -> {
            if(var.startsWith("__tec__")) return;
            for(int id:ids){ Event w=idToEvent.get(id); if(w instanceof WriteEvent we) eventStructure.addCoherenceOrder(var,we); }
        });
        executionState = new ExecutionState(program, eventStructure);
        int[] pcs = snap.threadPCs;
        for(int i=0;i<pcs.length;i++) for(int j=0;j<pcs[i];j++) executionState.advanceThread(i);
        snap.localVars.forEach(executionState::setLocalVar);
        for(int i=0;i<snap.lastEventIdPerThread.size();i++){
            int eid=snap.lastEventIdPerThread.get(i);
            if(eid>=0) executionState.setLastEventForThread(i, idToEvent.get(eid));
        }
        int maxId = snap.events.stream().mapToInt(ExecutionSnapshot.EventRecord::id).max().orElse(-1);
        Event.resetCounterTo(maxId+1);
        threadEventCount.clear();
        snap.coherenceOrder.forEach((k,v)->{
            if(k.startsWith("__tec__")) threadEventCount.put(Integer.parseInt(k.substring(7)), v.get(0));
        });
        graph.clear(); nodeCssClass.clear(); nodeXY.clear();
        graph.setAttribute("ui.stylesheet", graphStyle());
        graph.setAttribute("ui.quality"); graph.setAttribute("ui.antialias");
        snap.nodeRecords.forEach(nr -> {
            Node n = graph.addNode(nr.nodeId());
            Event e = idToEvent.get(Integer.parseInt(nr.nodeId().substring(1)));
            n.setAttribute("ui.label", e!=null?e.toString():nr.nodeId());
            n.setAttribute("ui.class", nr.cssClass());
            n.setAttribute("xyz", nr.x(), nr.y(), 0);
            nodeCssClass.put(nr.nodeId(), nr.cssClass());
            nodeXY.put(nr.nodeId(), new double[]{nr.x(), nr.y()});
        });
        snap.edgeRecords.forEach(er -> {
            if(graph.getEdge(er.edgeId())!=null) return;
            Edge edge=graph.addEdge(er.edgeId(), er.fromNode(), er.toNode(), true);
            edge.setAttribute("ui.class",er.cssClass()); edge.setAttribute("ui.label",er.label());
        });
        System.arraycopy(pcs, 0, shadowPCs, 0, pcs.length);
    }

    private void updateUndoButton() {
        boolean has = !undoStack.isEmpty();
        undoButton.setDisable(!has);
        undoCountLabel.setText(has?"("+undoStack.size()+")":"");
    }

    private int[] getField_threadPCs() { return Arrays.copyOf(shadowPCs, program.getThreadCount()); }

    // =========================================================================
    // Layout helpers
    // =========================================================================

    private double columnX(int tid) {
        int total=(program!=null?program.getThreadCount():0)+1;
        return (tid-(total-1)/2.0)*COLUMN_SPACING;
    }
    private double nextRowY(int tid) {
        int row=threadEventCount.getOrDefault(tid,0);
        threadEventCount.put(tid,row+1);
        return -row*ROW_SPACING;
    }
    private void resetLayoutCounters() { threadEventCount.clear(); }
    private boolean isSyncing(Event w, ReadEvent r) {
        return (w.getMemoryOrder()==MemoryOrder.RELEASE||w.getMemoryOrder()==MemoryOrder.SC)
                && (r.getMemoryOrder()==MemoryOrder.ACQUIRE||r.getMemoryOrder()==MemoryOrder.SC);
    }

    // =========================================================================
    // Program lifecycle
    // =========================================================================

    private void loadProgram() {
        Event.resetCounter(); undoStack.clear(); Arrays.fill(shadowPCs,0);
        pendingThreadIdx=-1; pendingInstr=null; pendingRead=null; pendingValid=null;
        if (forbiddenPanel != null) { forbiddenPanel.setVisible(false); forbiddenPanel.setManaged(false); }
        if (tooltipPanel   != null) hideTooltip();
        String text = programInput.getText().toUpperCase();
        if      (text.contains("LOAD BUFFERING"))                        currentLitmus = "LB";
        else if (text.contains("STORE BUFFERING"))                       currentLitmus = "SB";
        else if (text.contains("MESSAGE PASSING"))                       currentLitmus = "MP";
        else if (text.contains("CYCLE") && text.contains("CYC"))         currentLitmus = "CYC";
        else if (text.contains("IRIW"))                                  currentLitmus = "IRIW";
        else if (text.contains("CORR"))                                  currentLitmus = "CoRR";
        else if (text.contains("2+2W"))                                  currentLitmus = "2+2W";
        else if (text.contains("WRC"))                                   currentLitmus = "WRC";
        else if (text.contains("RMW"))                                   currentLitmus = "RMW";
        else if (text.contains("ISA2"))                                  currentLitmus = "ISA2";
        else if (text.contains("CORW"))                                  currentLitmus = "CoRW";
        else if (text.contains("LB+FENCE") || (text.contains("LOAD BUFFERING") && text.contains("SC FENCE"))) currentLitmus = "LB+fence";
        else if (text.contains("SB+FENCE") || (text.contains("STORE BUFFERING") && text.contains("SC FENCE"))) currentLitmus = "SB+fence";
        else if (text.contains("MP+RLX") || (text.contains("MESSAGE PASSING") && text.contains("RELAXED"))) currentLitmus = "MP+rlx";
        else if (text.contains("OOTA") || text.contains("OUT-OF-THIN-AIR"))  currentLitmus = "OOTA";
        else if (text.contains("3.SB") || (text.contains("3-THREAD") && text.contains("STORE BUFFERING"))) currentLitmus = "3.SB";
        // else: leave currentLitmus as whatever was set by the litmus button
        try {
            program = new ProgramParser().parse(programInput.getText());
            eventStructure = new EventStructure();
            executionState = new ExecutionState(program, eventStructure);
            graph.clear(); nodeCssClass.clear(); nodeXY.clear();
            graph.setAttribute("ui.stylesheet", graphStyle());
            graph.setAttribute("ui.quality"); graph.setAttribute("ui.antialias");
            resetLayoutCounters();
            for (Map.Entry<String,Integer> e : program.getInitValues().entrySet()) {
                WriteEvent init = new WriteEvent(0,e.getKey(),MemoryOrder.SC,e.getValue(),String.valueOf(e.getValue()));
                eventStructure.addEvent(init); eventStructure.addCoherenceOrder(e.getKey(),init);
                addNode(init,"init");
            }
            updateButtons(); refreshContextPanel(); refreshHintStatus(); refreshForbiddenZone();
            updateUndoButton(); undoButton.setDisable(true); resetButton.setDisable(false);
            log("✅ Loaded! "+program.getThreadCount()+" threads. Init: "+program.getInitValues());
            notifyExplanation("Program loaded.");
        } catch (ProgramParser.ParseException ex) { log("❌ Parse error: "+ex.getMessage()); }
    }

    private void executeThread(int idx) {
        if (executionState.isThreadDone(idx)) return;
        if (pendingThreadIdx >= 0) {
            log("⚠️ Please confirm the current read choice first!");
            startContextPulse("#f9e2af");
            return;
        }
        try {
            Instruction instr = executionState.getNextInstruction(idx);
            log("\n▶ T"+(idx+1)+": "+instr);
            if (instr.getType() == Instruction.InstructionType.READ) {
                int counterBefore = Event.peekCounter();
                ReadEvent probe = new ReadEvent(idx+1, instr.getVariable(),
                        instr.getMemoryOrder(), instr.getLocalVar());
                List<Event> valid = checker.getValidWritesFor(eventStructure, probe);
                Event.resetCounterTo(counterBefore);
                if (valid.isEmpty()) { log("❌ No valid writes for "+instr.getVariable()); return; }
                pushSnapshot();
                showInlineChoice(idx, instr, valid);
            } else {
                pushSnapshot();
                handleWrite(idx, instr);
                shadowPCs[idx] = executionState.isThreadDone(idx)
                        ? program.getThreads().get(idx).size() : shadowPCs[idx] + 1;
                graph.setAttribute("ui.stylesheet", graphStyle());
                updateButtons(); refreshContextPanel(); refreshHintStatus(); refreshForbiddenZone();
                if (executionState.allThreadsDone()) finishExecution();
            }
        } catch (Exception ex) {
            log("❌ Error T"+(idx+1)+": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void finishExecution() {
        String fb = hintEngine.buildCompletionFeedback(executionState, currentLitmus);
        log("\n"+fb);
        notifyExplanation("🎉 "+fb);
        setContext("🎉  Done!", fb, "#a6e3a1");
        // Celebration: pulse the context panel green 3 times
        String bright = "#a6e3a1", dim = "#a6e3a188";
        Timeline celebrate = new Timeline(
                new KeyFrame(Duration.ZERO,         e -> contextPanel.setStyle(contextStyle(bright))),
                new KeyFrame(Duration.millis(250),   e -> contextPanel.setStyle(contextStyle(dim))),
                new KeyFrame(Duration.millis(500),   e -> contextPanel.setStyle(contextStyle(bright))),
                new KeyFrame(Duration.millis(750),   e -> contextPanel.setStyle(contextStyle(dim))),
                new KeyFrame(Duration.millis(1000),  e -> contextPanel.setStyle(contextStyle(bright))),
                new KeyFrame(Duration.millis(1500),  e -> contextPanel.setStyle(contextStyle(bright)))
        );
        celebrate.play();
        // Flash all nodes with a final border glow only
        graph.nodes().forEach(n -> {
            String cls = nodeCssClass.get(n.getId());
            if (cls == null) return;
            String strokeColor = cls.equals("write") ? "#f9e2af" : cls.equals("read") ? "#a6e3a1" : "#fab387";
            n.setAttribute("ui.style", "fill-color:#313244;stroke-color:" + strokeColor + ";stroke-width:5px;");
            Timeline restore = new Timeline(
                    new KeyFrame(Duration.millis(1200), ev -> n.removeAttribute("ui.style"))
            );
            restore.play();
        });
    }

    /** Flash a node briefly with a given colour then restore */
    private void flashNode(String nodeId, String colour, int durationMs) {
        Node n = graph.getNode(nodeId);
        if (n == null) return;
        // Stroke-only flash — NEVER change fill-color (stays #313244)
        n.setAttribute("ui.style", "stroke-color:" + colour + ";stroke-width:4px;fill-color:#313244;");
        new Timeline(new KeyFrame(Duration.millis(durationMs),
                e -> n.removeAttribute("ui.style"))).play();
    }

    /** Flash an edge briefly with a given colour then restore */
    private void flashEdge(String edgeId, String colour, int durationMs) {
        Edge e = graph.getEdge(edgeId);
        if (e == null) return;
        e.setAttribute("ui.style", "fill-color:" + colour + ";size:4px;");
        new Timeline(new KeyFrame(Duration.millis(durationMs),
                ev -> e.removeAttribute("ui.style"))).play();
    }

    // =========================================================================
    // Graph click → tooltip explanation
    // =========================================================================

    private void handleGraphClick(javafx.scene.input.MouseEvent evt) {
        if (eventStructure == null || nodeXY.isEmpty()) return;

        // Store screen coords for tooltip positioning
        tooltipClickX = evt.getX();
        tooltipClickY = evt.getY();

        // Convert screen pixels to graph units using camera with fallback
        double clickGX, clickGY;
        try {
            org.graphstream.ui.geom.Point3 p =
                    graphView.getCamera().transformPxToGu(evt.getX(), evt.getY());
            clickGX = p.x; clickGY = p.y;
        } catch (Exception ex) {
            return; // camera not ready yet
        }

        // Find nearest node — tight threshold so only direct clicks on nodes register
        double NODE_THRESHOLD = 40;
        org.graphstream.graph.Node nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (org.graphstream.graph.Node n : graph) {
            double[] xy = nodeXY.get(n.getId());
            if (xy == null) continue;
            double dist = Math.sqrt(Math.pow(clickGX - xy[0], 2) + Math.pow(clickGY - xy[1], 2));
            if (dist < bestDist) { bestDist = dist; nearest = n; }
        }
        if (nearest != null && bestDist < NODE_THRESHOLD) {
            explainNode(nearest);
            return;
        }

        // Find nearest edge — use point-to-segment distance, not midpoint distance
        double EDGE_THRESHOLD = 12;
        org.graphstream.graph.Edge nearestEdge = null;
        double bestEdgeDist = Double.MAX_VALUE;
        for (org.graphstream.graph.Edge e : graph.edges().toList()) {
            double[] xy0 = nodeXY.get(e.getSourceNode().getId());
            double[] xy1 = nodeXY.get(e.getTargetNode().getId());
            if (xy0 == null || xy1 == null) continue;
            double dist = pointToSegmentDist(clickGX, clickGY, xy0[0], xy0[1], xy1[0], xy1[1]);
            if (dist < bestEdgeDist) { bestEdgeDist = dist; nearestEdge = e; }
        }
        if (nearestEdge != null && bestEdgeDist < EDGE_THRESHOLD) {
            explainEdge(nearestEdge);
        } else {
            // Clicked empty space — hide tooltip
            hideTooltip();
        }
    }

    // Point-to-line-segment distance in graph units
    private double pointToSegmentDist(double px, double py,
                                      double ax, double ay,
                                      double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        if (dx == 0 && dy == 0) return Math.sqrt((px-ax)*(px-ax) + (py-ay)*(py-ay));
        double t = Math.max(0, Math.min(1, ((px-ax)*dx + (py-ay)*dy) / (dx*dx + dy*dy)));
        double nx = ax + t*dx, ny = ay + t*dy;
        return Math.sqrt((px-nx)*(px-nx) + (py-ny)*(py-ny));
    }

    private void explainNode(org.graphstream.graph.Node n) {
        String id = n.getId();
        if (!id.startsWith("e")) return;
        int eventId;
        try { eventId = Integer.parseInt(id.substring(1)); }
        catch (NumberFormatException ex) { return; }
        Event ev = eventStructure.getEventById(eventId);
        if (ev == null) return;

        String cls    = nodeCssClass.getOrDefault(id, "");
        String colour = cls.equals("write") ? "#f38ba8" : cls.equals("read") ? "#a6e3a1" : "#fab387";

        String typeTag, title, badge = null;
        List<String[]> rows = new ArrayList<>();

        if (cls.equals("init")) {
            typeTag = "INIT WRITE";
            title   = ev.getVariable() + " = " + ev.getValue();
            rows.add(new String[]{"order",  "sc"});
            rows.add(new String[]{"formal", "W₀(" + ev.getVariable() + "=" + ev.getValue() + ")"});
            rows.add(new String[]{"note",   "initial value before any thread runs"});
        } else if (cls.equals("write")) {
            typeTag = "WRITE  ·  T" + ev.getThreadId();
            title   = ev.getVariable() + " = " + ev.getValue();
            rows.add(new String[]{"order",  ev.getMemoryOrder().name().toLowerCase()});
            rows.add(new String[]{"formal", "W_" + ev.getMemoryOrder().name().toLowerCase()
                    + "(" + ev.getVariable() + "=" + ev.getValue() + ")"});
            List<String> readers = new ArrayList<>();
            eventStructure.getReadsFrom().forEach((rId, wId) -> {
                if (wId == eventId) {
                    Event r = eventStructure.getEventById(rId);
                    if (r != null) readers.add("T" + r.getThreadId());
                }
            });
            rows.add(new String[]{"read by", readers.isEmpty() ? "—" : String.join("  ", readers)});
            badge = memOrderBadge(ev.getMemoryOrder());
        } else {
            typeTag = "READ  ·  T" + ev.getThreadId();
            title   = ev.getVariable();
            rows.add(new String[]{"order", ev.getMemoryOrder().name().toLowerCase()});
            Integer srcId = eventStructure.getReadsFrom().get(eventId);
            if (srcId != null) {
                Event src = eventStructure.getEventById(srcId);
                if (src != null) {
                    String from = src.getThreadId() == 0 ? "init" : "T" + src.getThreadId();
                    rows.add(new String[]{"rf from", from + "  (" + src.getVariable() + "=" + src.getValue() + ")"});
                    rows.add(new String[]{"formal",  "rf( e" + src.getId() + " → e" + eventId + " )"});
                    if (src.getThreadId() != 0 && src.getThreadId() != ev.getThreadId())
                        badge = "⚡ cross-thread";
                }
            } else {
                rows.add(new String[]{"rf from", "⏳ pending"});
            }
            if (ev instanceof ReadEvent re)
                rows.add(new String[]{"register", re.getLocalVar()});
            if (badge == null) badge = memOrderBadge(ev.getMemoryOrder());
        }

        showTooltipCard(typeTag, title, colour, rows, badge);
    }

    private void explainEdge(org.graphstream.graph.Edge e) {
        String cls   = (String) e.getAttribute("ui.class");
        String srcId = e.getSourceNode().getId();
        String tgtId = e.getTargetNode().getId();
        Event src = getEventForNodeId(srcId);
        Event tgt = getEventForNodeId(tgtId);
        if (src == null || tgt == null) return;

        String colour, typeTag, title, badge = null;
        List<String[]> rows = new ArrayList<>();

        switch (cls != null ? cls : "") {
            case "po" -> {
                colour  = "#89b4fa";  typeTag = "po";  title = "Program Order";
                rows.add(new String[]{"from",   "T" + src.getThreadId() + "  e" + src.getId()});
                rows.add(new String[]{"to",     "T" + tgt.getThreadId() + "  e" + tgt.getId()});
                rows.add(new String[]{"formal", "(e" + src.getId() + ", e" + tgt.getId() + ") ∈ po"});
                badge = "intra-thread";
            }
            case "rf" -> {
                colour  = "#a6e3a1";  typeTag = "rf";  title = "Reads-From";
                rows.add(new String[]{"write",  "T" + src.getThreadId() + "  " + src.getVariable() + "=" + src.getValue()});
                rows.add(new String[]{"read",   "T" + tgt.getThreadId() + "  " + tgt.getVariable()});
                rows.add(new String[]{"formal", "(e" + src.getId() + ", e" + tgt.getId() + ") ∈ rf"});
                badge = (src.getThreadId() != 0 && src.getThreadId() != tgt.getThreadId()) ? "⚡ cross-thread" : "same thread";
            }
            case "co" -> {
                colour  = "#cba6f7";  typeTag = "co";  title = "Coherence Order";
                rows.add(new String[]{"var",    src.getVariable()});
                rows.add(new String[]{"before", "e" + src.getId() + "  val=" + src.getValue()});
                rows.add(new String[]{"after",  "e" + tgt.getId() + "  val=" + tgt.getValue()});
                rows.add(new String[]{"formal", "(e" + src.getId() + ", e" + tgt.getId() + ") ∈ co"});
            }
            case "sw" -> {
                colour  = "#94e2d5";  typeTag = "sw";  title = "Synchronises-With";
                rows.add(new String[]{"release", "T" + src.getThreadId() + "  e" + src.getId()});
                rows.add(new String[]{"acquire", "T" + tgt.getThreadId() + "  e" + tgt.getId()});
                rows.add(new String[]{"formal",  "(e" + src.getId() + ", e" + tgt.getId() + ") ∈ sw"});
                badge = "rel / acq pair";
            }
            default -> {
                colour = "#cdd6f4";  typeTag = cls != null ? cls : "edge";  title = src + " → " + tgt;
            }
        }

        showTooltipCard(typeTag, title, colour, rows, badge);
    }

    private Event getEventForNodeId(String nodeId) {
        if (!nodeId.startsWith("e")) return null;
        try {
            int id = Integer.parseInt(nodeId.substring(1));
            return eventStructure.getEventById(id);
        } catch (NumberFormatException ex) { return null; }
    }

    private String memOrderBadge(MemoryOrder mo) {
        return switch (mo) {
            case SC      -> "🔒 sc";
            case RELEASE -> "📤 release";
            case ACQUIRE -> "📥 acquire";
            case RELAXED -> "🌀 relaxed";
            default      -> null;
        };
    }


    private Timeline tooltipDismissTimer;
    private double   tooltipClickX, tooltipClickY; // screen coords of last click

    private void showTooltipCard(String typeTag, String title, String colour,
                                 List<String[]> rows, String badge) {
        // ── Build card content ────────────────────────────────────────
        tooltipPanel.getChildren().clear();

        // Top colour bar
        javafx.scene.layout.Region topBar = new javafx.scene.layout.Region();
        topBar.setPrefHeight(4);
        topBar.setStyle("-fx-background-color:" + colour + ";" +
                "-fx-background-radius:8 8 0 0;");

        // Header: type tag + close button
        Label tagLbl = new Label(typeTag);
        tagLbl.setFont(Font.font("Monospaced", FontWeight.BOLD, 9));
        tagLbl.setTextFill(Color.web(colour));
        tagLbl.setStyle("-fx-background-color:rgba(255,255,255,0.06);" +
                "-fx-background-radius:3;-fx-padding:1 5 1 5;");

        Button closeBtn = new Button("✕");
        closeBtn.setStyle("-fx-background-color:transparent;-fx-text-fill:#45475a;" +
                "-fx-cursor:hand;-fx-padding:0 2;-fx-font-size:10;");
        closeBtn.setOnAction(e -> hideTooltip());

        HBox header = new HBox(tagLbl, closeBtn);
        HBox.setHgrow(tagLbl, Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(7, 8, 4, 10));

        // Big title value
        Label titleLbl = new Label(title);
        titleLbl.setFont(Font.font("Monospaced", FontWeight.BOLD, 18));
        titleLbl.setTextFill(Color.web(colour));
        titleLbl.setPadding(new Insets(0, 10, 6, 10));

        // Divider
        javafx.scene.shape.Rectangle div = new javafx.scene.shape.Rectangle();
        div.setHeight(1);
        div.setFill(Color.web("#313244"));
        div.widthProperty().bind(tooltipPanel.widthProperty());

        // Key-value rows
        VBox rowsBox = new VBox(0);
        rowsBox.setPadding(new Insets(6, 0, 8, 0));
        for (String[] row : rows) {
            Label keyLbl = new Label(row[0]);
            keyLbl.setFont(Font.font("Monospaced", 10));
            keyLbl.setTextFill(Color.web("#6c7086"));
            keyLbl.setMinWidth(52);
            keyLbl.setPadding(new Insets(3, 6, 3, 10));

            Label valLbl = new Label(row[1]);
            valLbl.setFont(Font.font("Monospaced", 11));
            valLbl.setTextFill(Color.web("#cdd6f4"));
            valLbl.setWrapText(true);
            valLbl.setMaxWidth(160);
            valLbl.setPadding(new Insets(3, 8, 3, 0));

            HBox rowBox = new HBox(keyLbl, valLbl);
            rowBox.setAlignment(Pos.TOP_LEFT);
            rowsBox.getChildren().add(rowBox);
        }

        // Badge pill at bottom
        tooltipPanel.getChildren().addAll(topBar, header, titleLbl, div, rowsBox);
        if (badge != null) {
            Label badgeLbl = new Label(badge);
            badgeLbl.setFont(Font.font("Arial", FontWeight.BOLD, 10));
            badgeLbl.setTextFill(Color.web(colour));
            badgeLbl.setStyle("-fx-background-color:rgba(255,255,255,0.07);" +
                    "-fx-background-radius:20;-fx-padding:2 8 2 8;");
            HBox badgeRow = new HBox(badgeLbl);
            badgeRow.setAlignment(Pos.CENTER_RIGHT);
            badgeRow.setPadding(new Insets(0, 10, 8, 0));
            tooltipPanel.getChildren().add(badgeRow);
        }

        // Update border glow colour
        tooltipPanel.setStyle("-fx-background-color:#181825;" +
                "-fx-border-color:" + colour + ";-fx-border-width:1.5px;" +
                "-fx-border-radius:10;-fx-background-radius:10;" +
                "-fx-effect:dropshadow(gaussian," + colour + ",16,0.35,0,0);");

        // ── Position near click ───────────────────────────────────────
        tooltipPanel.setVisible(true);
        tooltipPanel.setManaged(true);
        tooltipPanel.applyCss();
        tooltipPanel.layout();
        double panelW = tooltipPanel.prefWidth(-1);
        double panelH = tooltipPanel.prefHeight(panelW);
        double graphW = graphView.getWidth();
        double graphH = graphView.getHeight();
        double tx = tooltipClickX + 20;
        double ty = tooltipClickY - panelH / 2;
        if (tx + panelW > graphW - 8) tx = tooltipClickX - panelW - 20;
        if (tx < 4) tx = 4;
        if (ty < 4) ty = 4;
        if (ty + panelH > graphH - 4) ty = graphH - panelH - 4;
        StackPane.setMargin(tooltipPanel, new Insets(ty, 0, 0, tx));

        // ── Pop animation ─────────────────────────────────────────────
        if (tooltipDismissTimer != null) tooltipDismissTimer.stop();
        tooltipPanel.setScaleX(0.65);
        tooltipPanel.setScaleY(0.65);
        tooltipPanel.setOpacity(0);

        ScaleTransition scaleUp = new ScaleTransition(Duration.millis(200), tooltipPanel);
        scaleUp.setToX(1.06); scaleUp.setToY(1.06);
        scaleUp.setInterpolator(Interpolator.EASE_OUT);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(160), tooltipPanel);
        fadeIn.setToValue(1.0);
        ScaleTransition settle = new ScaleTransition(Duration.millis(90), tooltipPanel);
        settle.setToX(1.0); settle.setToY(1.0);
        scaleUp.setOnFinished(e -> settle.play());
        new ParallelTransition(scaleUp, fadeIn).play();

        tooltipDismissTimer = new Timeline(new KeyFrame(Duration.seconds(8), e -> hideTooltip()));
        tooltipDismissTimer.play();
    }

    private void hideTooltip() {
        if (!tooltipPanel.isVisible()) return;
        if (tooltipDismissTimer != null) { tooltipDismissTimer.stop(); tooltipDismissTimer = null; }
        // Fade + shrink out
        FadeTransition fadeOut = new FadeTransition(Duration.millis(150), tooltipPanel);
        fadeOut.setToValue(0);
        ScaleTransition shrink = new ScaleTransition(Duration.millis(150), tooltipPanel);
        shrink.setToX(0.75); shrink.setToY(0.75);
        shrink.setInterpolator(Interpolator.EASE_IN);
        ParallelTransition popOut = new ParallelTransition(fadeOut, shrink);
        popOut.setOnFinished(e -> {
            tooltipPanel.setVisible(false);
            tooltipPanel.setManaged(false);
        });
        popOut.play();
    }

    private int getReflectedPC(int idx) {
        return executionState.isThreadDone(idx)
                ? program.getThreads().get(idx).size() : shadowPCs[idx]+1;
    }

    private void completeRead(int idx, Instruction instr, ReadEvent read, Event write) {
        eventStructure.addEvent(read);
        eventStructure.addReadsFrom(read, write);
        addNode(read, "read"); // MUST be before any addEdge calls
        Event last = executionState.getLastEventForThread(idx);
        if(last!=null){ eventStructure.addProgramOrder(last,read); addEdge(last,read,"po"); }
        executionState.setLastEventForThread(idx,read);
        executionState.setLocalVar(instr.getLocalVar(), read.getValue());
        executionState.advanceThread(idx);
        addEdge(write,read,"rf");
        boolean sw=isSyncing(write,read);
        if(sw){ addSwEdge(write,read); log("🔵 sw sync!"); }
        log("✅ "+instr.getLocalVar()+"="+read.getValue()+" ← "+write+(sw?" 🔵sw":""));
        notifyExplanation("T"+(idx+1)+" read "+instr.getVariable()+"="+read.getValue()+" from "+write);
    }

    private void handleWrite(int idx, Instruction instr) {
        int val = executionState.evaluateExpression(instr.getValueExpr());
        WriteEvent write = new WriteEvent(idx+1, instr.getVariable(), instr.getMemoryOrder(), val, instr.getValueExpr());
        eventStructure.addEvent(write);
        List<Integer> coList = eventStructure.getCoherenceOrder().getOrDefault(instr.getVariable(), Collections.emptyList());
        if(!coList.isEmpty()){
            Event prev = eventStructure.getEventById(coList.get(coList.size()-1));
            addNode(write,"write");
            if(prev!=null){ addCoEdge(prev,write); log("🟣 co edge"); }
        } else addNode(write,"write");
        eventStructure.addCoherenceOrder(instr.getVariable(),write);
        Event last = executionState.getLastEventForThread(idx);
        if(last!=null){ eventStructure.addProgramOrder(last,write); addEdge(last,write,"po"); }
        executionState.setLastEventForThread(idx,write);
        executionState.advanceThread(idx);
        log("✅ write("+instr.getVariable()+"="+val+","+instr.getMemoryOrder().name().toLowerCase()+")");
        notifyExplanation("T"+(idx+1)+" wrote "+instr.getVariable()+"="+val);
    }

    // =========================================================================
    // Graph mutation
    // =========================================================================

    private void addNode(Event e, String css) {
        String id="e"+e.getId(); if(graph.getNode(id)!=null) return;
        double x=columnX(e.getThreadId()), y=nextRowY(e.getThreadId());
        Node n=graph.addNode(id); n.setAttribute("ui.label",e.toString());
        n.setAttribute("ui.class",css); n.setAttribute("xyz",x,y,0);
        nodeCssClass.put(id,css); nodeXY.put(id,new double[]{x,y});
        // Flash: bright border glow only, keep dark fill — then restore
        String strokeColor = css.equals("write") ? "#f9e2af" : css.equals("read") ? "#a6e3a1" : "#fab387";
        n.setAttribute("ui.style",
                "fill-color:#313244;stroke-color:" + strokeColor + ";stroke-width:5px;");
        Timeline flash = new Timeline(
                new KeyFrame(Duration.millis(700), ev ->
                        n.setAttribute("ui.style",
                                "fill-color:#313244;stroke-color:" + strokeColor + ";stroke-width:2px;")),
                new KeyFrame(Duration.millis(1000), ev ->
                        n.removeAttribute("ui.style"))
        );
        flash.play();
    }

    private void addEdge(Event f, Event t, String type) {
        String id=type+f.getId()+"_"+t.getId(); if(graph.getEdge(id)!=null) return;
        Edge e=graph.addEdge(id,"e"+f.getId(),"e"+t.getId(),true);
        e.setAttribute("ui.class",type); e.setAttribute("ui.label",type);
        // Brief thick flash then back to normal size
        e.setAttribute("ui.style", "size:5px;");
        Timeline flash = new Timeline(
                new KeyFrame(Duration.millis(600), ev ->
                        e.setAttribute("ui.style", "size:3px;")),
                new KeyFrame(Duration.millis(900), ev ->
                        e.removeAttribute("ui.style"))
        );
        flash.play();
    }
    private void addCoEdge(Event f, Event t) {
        String id="co"+f.getId()+"_"+t.getId(); if(graph.getEdge(id)!=null) return;
        Edge e=graph.addEdge(id,"e"+f.getId(),"e"+t.getId(),true);
        e.setAttribute("ui.class","co"); e.setAttribute("ui.label","co");
    }
    private void addSwEdge(Event w, Event r) {
        String id="sw"+w.getId()+"_"+r.getId(); if(graph.getEdge(id)!=null) return;
        Edge e=graph.addEdge(id,"e"+w.getId(),"e"+r.getId(),true);
        e.setAttribute("ui.class","sw"); e.setAttribute("ui.label","sw");
    }

    // =========================================================================
    // Reset
    // =========================================================================

    private void resetExecution() {
        if(program==null) return;
        Event.resetCounter(); undoStack.clear(); Arrays.fill(shadowPCs,0);
        pendingThreadIdx=-1; pendingInstr=null; pendingRead=null; pendingValid=null;
        if (forbiddenPanel != null) { forbiddenPanel.setVisible(false); forbiddenPanel.setManaged(false); }
        if (tooltipPanel   != null) hideTooltip();
        eventStructure=new EventStructure(); executionState=new ExecutionState(program,eventStructure);
        graph.clear(); nodeCssClass.clear(); nodeXY.clear();
        graph.setAttribute("ui.stylesheet",graphStyle());
        graph.setAttribute("ui.quality"); graph.setAttribute("ui.antialias");
        resetLayoutCounters();
        for(Map.Entry<String,Integer> e:program.getInitValues().entrySet()){
            WriteEvent init=new WriteEvent(0,e.getKey(),MemoryOrder.SC,e.getValue(),String.valueOf(e.getValue()));
            eventStructure.addEvent(init); eventStructure.addCoherenceOrder(e.getKey(),init); addNode(init,"init");
        }
        updateButtons(); refreshContextPanel(); refreshHintStatus(); refreshForbiddenZone(); updateUndoButton();
        log("⟳ Reset."); notifyExplanation("⟳ Reset.");
    }

    // =========================================================================
    // Predict outcome
    // =========================================================================

    private String predictOutcome(Instruction instr, Event write, int idx) {
        if(currentLitmus==null) return "Custom program — explore freely!";
        boolean isCross = write.getThreadId()!=0 && write.getThreadId()!=idx+1;
        return switch(currentLitmus) {
            case "SB"  -> isCross ? "→ leads to r1=0,r2=0 — the WEAK SB outcome! ⚡"
                    : "→ SC-compatible outcome.";
            case "LB"  -> isCross ? "→ cross-thread LB read." : "→ initial value.";
            case "MP"  -> {
                boolean sy = (write.getMemoryOrder()==MemoryOrder.RELEASE||write.getMemoryOrder()==MemoryOrder.SC)
                        && (instr.getMemoryOrder()==MemoryOrder.ACQUIRE||instr.getMemoryOrder()==MemoryOrder.SC);
                yield isCross&&sy ? "→ r1=1 with sync — X guaranteed to be 1! ✅"
                        : isCross     ? "→ r1=1 but check memory orders for full guarantee."
                        : "→ r1=0 — Thread 2 read before Thread 1 wrote.";
            }
            case "CYC"  -> isCross ? "⚠️ Would need OOTA cycle — WEAKEST blocks this."
                    : "→ r1=0 — only safe outcome.";
            case "IRIW" -> isCross ? "→ non-MCA behaviour possible! ⚡" : "→ initial value.";
            case "CoRR" -> isCross ? "→ coherence read — must be consistent with sibling reads! ⚡"
                    : "→ initial value.";
            case "2+2W" -> "→ last write wins in coherence order.";
            case "WRC"  -> isCross ? "→ causality chain — if r1=1 then r2 must follow! ⚡"
                    : "→ initial value.";
            case "RMW"  -> isCross ? "→ T2 sees T1's increment — correct RMW ordering. ✅"
                    : "→ T1 reads initial 0 first.";
            case "ISA2" -> isCross ? "→ store forwarding chain — check X is also visible! ⚡"
                    : "→ initial value.";
            case "CoRW"    -> isCross ? "→ T2 reads T1's write — co(W1,W2) must hold after this! ⚡"
                    : "→ initial value — T2's write will start the co chain.";
            case "LB+fence" -> isCross ? "→ ⚠️  SC ops forbid this! r1=1,r2=1 is FORBIDDEN."
                    : "→ initial value (SC-safe).";
            case "SB+fence" -> isCross ? "→ SC fence drained store buffer — seeing cross-thread value. ✅"
                    : "→ ⚠️  SC fences mean r1=0,r2=0 is FORBIDDEN here!";
            case "MP+rlx"  -> isCross ? "→ ⚡ r1=1,r2=0 is now ALLOWED — no acq/rel, no sw edge!"
                    : "→ initial value.";
            case "OOTA"    -> isCross ? "→ ❌ FORBIDDEN — would create out-of-thin-air value!"
                    : "→ initial value (only safe choice).";
            case "3.SB"    -> isCross ? "→ ⚡ cross-thread read — 3-way store buffer in action!"
                    : "→ initial value — all three buffers full.";
            default     -> "Explore!";
        };
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private Label styled(String text, String colour, int size, boolean bold) {
        Label l = new Label(text);
        l.setFont(Font.font("Arial", bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
        l.setTextFill(Color.web(colour));
        return l;
    }
    private Button btn(String text, String colour) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle("-fx-background-color:"+colour+";-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:9;");
        return b;
    }
    private Tooltip tip(String text) {
        Tooltip t = new Tooltip(text);
        t.setWrapText(true); t.setMaxWidth(280);
        t.setFont(Font.font("Arial", 12));
        t.setShowDelay(Duration.millis(300));
        return t;
    }
    private void log(String msg) { logArea.appendText(msg+"\n"); }

    // =========================================================================
    // Litmus programs
    // =========================================================================

    private String defaultProgram() {
        return "init:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    @r1 = read(X, rlx)\n    write(Y, @r1, rel)\n\n" +
                "Thread 2:\n    @r2 = read(Y, acq)\n    write(X, @r2, rlx)";
    }
    private String litmusSB() {
        return "// Store Buffering (SB)\n// r1=0,r2=0 ALLOWED\ninit:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    write(X, 1, rlx)\n    @r1 = read(Y, rlx)\n\n" +
                "Thread 2:\n    write(Y, 1, rlx)\n    @r2 = read(X, rlx)";
    }
    private String litmusLB() {
        return "// Load Buffering (LB)\n// r1=1,r2=1 ALLOWED\ninit:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    @r1 = read(X, rlx)\n    write(Y, @r1, rlx)\n\n" +
                "Thread 2:\n    @r2 = read(Y, rlx)\n    write(X, @r2, rlx)";
    }
    private String litmusMP() {
        return "// Message Passing (MP)\n// if r1=1 then r2=1\ninit:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    write(X, 1, rlx)\n    write(Y, 1, rel)\n\n" +
                "Thread 2:\n    @r1 = read(Y, acq)\n    @r2 = read(X, rlx)";
    }
    private String litmusCYC() {
        return "// Cycle (CYC)\n// r1=1,r2=1 FORBIDDEN\ninit:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    @r1 = read(X, rlx)\n    write(Y, @r1, rlx)\n\n" +
                "Thread 2:\n    @r2 = read(Y, rlx)\n    write(X, @r2, rlx)";
    }
    private String litmusIRIW() {
        return "// IRIW\ninit:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    write(X, 1, rlx)\n\nThread 2:\n    write(Y, 1, rlx)\n\n" +
                "Thread 3:\n    @r1 = read(X, rlx)\n    @r2 = read(Y, rlx)\n\n" +
                "Thread 4:\n    @r3 = read(Y, rlx)\n    @r4 = read(X, rlx)";
    }

    // ── New litmus tests ──────────────────────────────────────────────

    private String litmusCoRR() {
        return "// CoRR — Coherence Read-Read\n" +
                "// If T2 reads new X, T3 must also read new X\n" +
                "// r1=1, r2=0 FORBIDDEN\n" +
                "init:\n    X = 0\n\n" +
                "Thread 1:\n    write(X, 1, rlx)\n\n" +
                "Thread 2:\n    @r1 = read(X, rlx)\n\n" +
                "Thread 3:\n    @r2 = read(X, rlx)";
    }

    private String litmus2p2W() {
        return "// 2+2W — Two-Plus-Two Writes\n" +
                "// r1=2, r2=1 FORBIDDEN — co must be consistent\n" +
                "init:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    write(X, 1, rlx)\n    write(Y, 1, rlx)\n\n" +
                "Thread 2:\n    write(Y, 2, rlx)\n    write(X, 2, rlx)";
    }

    private String litmusWRC() {
        return "// WRC — Write-Read Causality\n" +
                "// r1=1, r2=0 FORBIDDEN — causality must propagate\n" +
                "init:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    write(X, 1, rlx)\n\n" +
                "Thread 2:\n    @r1 = read(X, rlx)\n    write(Y, @r1, rlx)\n\n" +
                "Thread 3:\n    @r2 = read(Y, rlx)\n    @r3 = read(X, rlx)";
    }

    private String litmusRMW() {
        return "// RMW — Read-Modify-Write (atomic increment)\n" +
                "// Both threads increment X: final X should be 2\n" +
                "// r1=0, r2=1 ALLOWED  |  r1=1, r2=0 ALLOWED\n" +
                "// r1=0, r2=0 FORBIDDEN — one increment lost\n" +
                "init:\n    X = 0\n\n" +
                "Thread 1:\n    @r1 = read(X, acq)\n    write(X, 1, rel)\n\n" +
                "Thread 2:\n    @r2 = read(X, acq)\n    write(X, 2, rel)";
    }

    private String litmusISA2() {
        return "// ISA2 — Store Forwarding + Coherence\n" +
                "// r1=1, r2=1, r3=0 FORBIDDEN under strong models\n" +
                "init:\n    X = 0\n    Y = 0\n    Z = 0\n\n" +
                "Thread 1:\n    write(X, 1, rlx)\n    write(Y, 1, rel)\n\n" +
                "Thread 2:\n    @r1 = read(Y, acq)\n    write(Z, @r1, rlx)\n\n" +
                "Thread 3:\n    @r2 = read(Z, rlx)\n    @r3 = read(X, rlx)";
    }

    private String litmus3SB() {
        return "// 3.SB — 3-Thread Store Buffering Ring\n" +
                "// r1=0, r2=0, r3=0 ALLOWED — 3-way store buffering\n" +
                "// Each thread writes its variable then reads the next thread's variable\n" +
                "init:\n    X = 0\n    Y = 0\n    Z = 0\n\n" +
                "Thread 1:\n    write(X, 1, rlx)\n    @r1 = read(Y, rlx)\n\n" +
                "Thread 2:\n    write(Y, 1, rlx)\n    @r2 = read(Z, rlx)\n\n" +
                "Thread 3:\n    write(Z, 1, rlx)\n    @r3 = read(X, rlx)";
    }

    private String litmusCoRW() {
        return "// CoRW — Coherence Read-Write\n" +
                "// r1=1 then co(W2,W1) FORBIDDEN — read-write coherence\n" +
                "// If T2 reads T1's write, T2's own write must come after T1's in co\n" +
                "init:\n    X = 0\n\n" +
                "Thread 1:\n    write(X, 1, rlx)\n\n" +
                "Thread 2:\n    @r1 = read(X, rlx)\n    write(X, 2, rlx)";
    }

    private String litmusLBfence() {
        return "// LB+fence — Load Buffering with SC fences\n" +
                "// r1=1, r2=1 FORBIDDEN — fences restore sequential consistency\n" +
                "init:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    @r1 = read(X, sc)\n    write(Y, 1, sc)\n\n" +
                "Thread 2:\n    @r2 = read(Y, sc)\n    write(X, 1, sc)";
    }

    private String litmusSBfence() {
        return "// SB+fence — Store Buffering with SC fences\n" +
                "// r1=0, r2=0 FORBIDDEN — SC fences drain the store buffer\n" +
                "init:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    write(X, 1, sc)\n    @r1 = read(Y, sc)\n\n" +
                "Thread 2:\n    write(Y, 1, sc)\n    @r2 = read(X, sc)";
    }

    private String litmusMPrelaxed() {
        return "// MP+rlx — Message Passing with relaxed accesses\n" +
                "// r1=1, r2=0 ALLOWED — without acq/rel, sync is lost\n" +
                "// Compare with MP where acq/rel makes r1=1,r2=0 FORBIDDEN\n" +
                "init:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    write(X, 1, rlx)\n    write(Y, 1, rlx)\n\n" +
                "Thread 2:\n    @r1 = read(Y, rlx)\n    @r2 = read(X, rlx)";
    }

    private String litmusOOTA() {
        return "// OOTA — Out-of-Thin-Air\n" +
                "// r1=42, r2=42 FORBIDDEN — values cannot appear from nowhere\n" +
                "// WEAKEST specifically designed to forbid OOTA via justification\n" +
                "init:\n    X = 0\n    Y = 0\n\n" +
                "Thread 1:\n    @r1 = read(X, rlx)\n    write(Y, @r1, rlx)\n\n" +
                "Thread 2:\n    @r2 = read(Y, rlx)\n    write(X, @r2, rlx)";
    }

    //noinspection CssUnknownUnit,CssUnknownProperty,CssInvalidPropertyValue,CssInvalidFunction
    private String graphStyle() {
        return "graph{padding:150px;}" +
                "node{shape:rounded-box;size:150px,50px;fill-color:#313244;" +
                "stroke-mode:plain;stroke-color:#89b4fa;stroke-width:2px;" +
                "text-color:#cdd6f4;text-style:bold;text-size:14;}" +
                "node.init{stroke-color:#fab387;text-color:#fab387;}" +
                "node.read{stroke-color:#a6e3a1;text-color:#a6e3a1;}" +
                "node.write{stroke-color:#f38ba8;text-color:#f38ba8;}" +
                "node.cycle_node{stroke-color:#ff0000;stroke-width:4px;text-color:#ff0000;}" +
                "edge{arrow-shape:arrow;arrow-size:14px,7px;" +
                "text-size:12;text-color:#cdd6f4;" +
                "text-background-mode:rounded-box;text-background-color:#1e1e2e;text-padding:3px;" +
                "fill-color:#89b4fa;size:2px;}" +
                "edge.rf{fill-color:#a6e3a1;size:2px;}" +
                "edge.po{fill-color:#89b4fa;size:2px;}" +
                "edge.co{fill-color:#cba6f7;size:2px;stroke-mode:dashes;}" +
                "edge.sw{fill-color:#94e2d5;size:4px;}" +
                "edge.cycle{fill-color:#ff0000;size:4px;text-color:#ff0000;}";
    }

    public Pane getRoot() { return root; }
}