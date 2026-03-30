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

public class QuizWindow {

    // ── Quiz questions ────────────────────────────────────────────────
    record QuizQuestion(
            String litmusName,
            String program,
            String question,
            String hint,
            boolean correctAnswer,       // true = YES it's possible, false = NO it's forbidden
            String explanation,
            String outcomeToCheck        // e.g. "r1=0,r2=0" — used to validate execution
    ) {}

    private static final List<QuizQuestion> QUESTIONS = List.of(
            new QuizQuestion("SB",
                    "// Store Buffering\ninit:\n    X = 0\n    Y = 0\n\nThread 1:\n    write(X, 1, rlx)\n    @r1 = read(Y, rlx)\n\nThread 2:\n    write(Y, 1, rlx)\n    @r2 = read(X, rlx)",
                    "Under WEAKEST, is the outcome  r1=0 AND r2=0  possible?",
                    "💡 Try executing Thread 1 write, Thread 2 write, then both reads — what do they see?",
                    true,
                    "YES — r1=0, r2=0 is ALLOWED under WEAKEST (and x86 TSO). Each thread reads before seeing the other's write. This is the classic Store Buffering weak memory outcome. Under Sequential Consistency this would be FORBIDDEN.",
                    "r1=0,r2=0"
            ),
            new QuizQuestion("LB",
                    "// Load Buffering\ninit:\n    X = 0\n    Y = 0\n\nThread 1:\n    @r1 = read(X, rlx)\n    write(Y, @r1, rlx)\n\nThread 2:\n    @r2 = read(Y, rlx)\n    write(X, @r2, rlx)",
                    "Under WEAKEST, is the outcome  r1=1 AND r2=1  possible?",
                    "💡 Can Thread 1 read the value that Thread 2 hasn't written yet?",
                    true,
                    "YES — r1=1, r2=1 is ALLOWED under WEAKEST. This is the Load Buffering test. Relaxed reads allow speculative forwarding. Under SC this would be FORBIDDEN since it requires reading values from the future.",
                    "r1=1,r2=1"
            ),
            new QuizQuestion("MP",
                    "// Message Passing\ninit:\n    X = 0\n    Y = 0\n\nThread 1:\n    write(X, 1, rlx)\n    write(Y, 1, rel)\n\nThread 2:\n    @r1 = read(Y, acq)\n    @r2 = read(X, rlx)",
                    "If Thread 2 reads r1=1 (the flag), is r2=0 still possible?",
                    "💡 Think about what the release/acquire pair guarantees...",
                    false,
                    "NO — if r1=1, then r2=0 is FORBIDDEN. The release write to Y and acquire read of Y create a synchronizes-with (sw) edge. This means all writes before the release (including X=1) are visible to Thread 2 after the acquire. So r2 must be 1.",
                    "r1=1,r2=0"
            ),
            new QuizQuestion("CYC",
                    "// Cycle / SC fence test\ninit:\n    X = 0\n    Y = 0\n\nThread 1:\n    @r1 = read(X, sc)\n    write(Y, 1, sc)\n\nThread 2:\n    @r2 = read(Y, sc)\n    write(X, 1, sc)",
                    "Under WEAKEST with SC operations, is the outcome  r1=1 AND r2=1  possible?",
                    "💡 SC operations impose a total order — can both reads see the other's write simultaneously?",
                    false,
                    "NO — r1=1, r2=1 is FORBIDDEN even under WEAKEST when using SC (sequentially consistent) operations. SC operations impose a total order on all SC accesses. For both r1 and r2 to read 1, each write must be observed before the other's read — but this creates a cycle in the SC order, which violates psc-acyclicity. This outcome is forbidden in ALL five memory models.",
                    "r1=1,r2=1"
            ),
            new QuizQuestion("IRIW",
                    "// IRIW\ninit:\n    X = 0\n    Y = 0\n\nThread 1:\n    write(X, 1, rlx)\n\nThread 2:\n    write(Y, 1, rlx)\n\nThread 3:\n    @r1 = read(X, rlx)\n    @r2 = read(Y, rlx)\n\nThread 4:\n    @r3 = read(Y, rlx)\n    @r4 = read(X, rlx)",
                    "Can Thread 3 see X=1,Y=0 while Thread 4 sees Y=1,X=0 simultaneously?",
                    "💡 This tests multi-copy atomicity — do all threads see writes in the same order?",
                    true,
                    "YES — this is ALLOWED under WEAKEST. WEAKEST does not guarantee multi-copy atomicity (MCA). Thread 3 can see Thread 1's write before Thread 2's, while Thread 4 sees them in the opposite order. This is the IRIW (Independent Reads of Independent Writes) test.",
                    "r1=1,r2=0,r3=1,r4=0"
            )
    );

    // ── State ─────────────────────────────────────────────────────────
    private int currentQuestionIdx = 0;
    private int score = 0;
    private int totalAnswered = 0;
    private final List<Boolean> answerHistory = new ArrayList<>();

    // ── Model (per question) ──────────────────────────────────────────
    private Program        program;
    private EventStructure eventStructure;
    private ExecutionState executionState;
    private final ConsistencyChecker checker    = new ConsistencyChecker();
    private final HintEngine         hintEngine = new HintEngine();
    private Graph    graph;
    private FxViewer viewer;
    private final Map<String, String>   nodeCssClass = new LinkedHashMap<>();
    private final Map<String, double[]> nodeXY       = new LinkedHashMap<>();
    private final Map<Integer, Integer> threadEventCount = new HashMap<>();
    private static final double COLUMN_SPACING = 300.0;
    private static final double ROW_SPACING    = 150.0;
    private final int[] shadowPCs = new int[64];
    private final Deque<ExecutionSnapshot> undoStack = new ArrayDeque<>();

    // ── Pending read ──────────────────────────────────────────────────
    private int         pendingThreadIdx = -1;
    private Instruction pendingInstr;
    private List<Event> pendingValid;

    // ── UI ────────────────────────────────────────────────────────────
    private Stage stage;
    private double screenW, screenH;
    private VBox   threadButtonsBox;
    private VBox   contextPanel;
    private Label  contextTitle;
    private Label  contextBody;
    private VBox   inlineChoiceBox;
    private Timeline contextPulse;
    private TextArea logArea;
    private Label  scoreLabel;
    private Label  questionLabel;
    private Label  questionText;
    private VBox   answerPanel;
    private VBox   feedbackPanel;
    private Button undoButton;
    private Label  undoCountLabel;

    // ── Build ─────────────────────────────────────────────────────────
    public QuizWindow() {
        System.setProperty("org.graphstream.ui", "javafx");
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        screenW = screen.getWidth();
        screenH = screen.getHeight();
        buildStage();
    }

    private void buildStage() {
        stage = new Stage();
        stage.setTitle("🎓  WEAKEST Quiz Mode");

        // ── Header ────────────────────────────────────────────────────
        HBox header = new HBox(16);
        header.setPadding(new Insets(12, 16, 12, 16));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color:#313244;");

        Label title = new Label("🎓  WEAKEST Quiz Mode");
        title.setFont(Font.font("Arial", FontWeight.BOLD, 20));
        title.setTextFill(Color.web("#cba6f7"));

        scoreLabel = new Label("Score: 0 / 0");
        scoreLabel.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        scoreLabel.setTextFill(Color.web("#a6e3a1"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕ Close");
        closeBtn.setStyle("-fx-background-color:#f38ba8;-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:6 14;");
        closeBtn.setOnAction(e -> stage.close());

        header.getChildren().addAll(title, spacer, scoreLabel, closeBtn);

        // ── Question panel ────────────────────────────────────────────
        VBox questionPanel = new VBox(10);
        questionPanel.setPadding(new Insets(14, 16, 10, 16));
        questionPanel.setStyle("-fx-background-color:#1e1e2e;-fx-border-color:#45475a;-fx-border-width:0 0 1 0;");

        questionLabel = new Label("Question 1 of " + QUESTIONS.size());
        questionLabel.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        questionLabel.setTextFill(Color.web("#6c7086"));

        questionText = new Label();
        questionText.setWrapText(true);
        questionText.setFont(Font.font("Arial", FontWeight.BOLD, 17));
        questionText.setTextFill(Color.web("#cdd6f4"));

        // Progress bar
        HBox progressBox = new HBox(4);
        for (int i = 0; i < QUESTIONS.size(); i++) {
            Region dot = new Region();
            dot.setPrefSize(18, 6);
            dot.setStyle("-fx-background-color:#45475a;-fx-background-radius:3;");
            dot.setUserData(i);
            progressBox.getChildren().add(dot);
        }

        questionPanel.getChildren().addAll(questionLabel, questionText, progressBox);

        // ── Left panel (controls) ─────────────────────────────────────
        VBox leftContent = new VBox(8);
        leftContent.setPadding(new Insets(10));
        leftContent.setStyle("-fx-background-color:#181825;");
        leftContent.setPrefWidth(380);

        // Hint box
        Label hintLbl = new Label();
        hintLbl.setWrapText(true);
        hintLbl.setFont(Font.font("Arial", FontPosture.ITALIC, 12));
        hintLbl.setTextFill(Color.web("#f9e2af"));
        hintLbl.setPadding(new Insets(8));
        hintLbl.setStyle("-fx-background-color:#2a2a3e;-fx-background-radius:6;");

        // Thread buttons
        Label threadLbl = new Label("🧵  Execute Thread:");
        threadLbl.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        threadLbl.setTextFill(Color.web("#cdd6f4"));
        threadButtonsBox = new VBox(6);

        // Undo row
        HBox actionRow = new HBox(8); actionRow.setAlignment(Pos.CENTER_LEFT);
        undoButton = new Button("↩ Undo");
        undoButton.setStyle("-fx-background-color:#f38ba8;-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:8 12;");
        undoButton.setDisable(true);
        undoButton.setOnAction(e -> performUndo());
        undoCountLabel = new Label("");
        undoCountLabel.setFont(Font.font("Monospaced", 11));
        undoCountLabel.setTextFill(Color.web("#6c7086"));
        Button resetBtn = new Button("⟳ Reset");
        resetBtn.setStyle("-fx-background-color:#a6e3a1;-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:8 12;");
        resetBtn.setOnAction(e -> loadCurrentQuestion());
        actionRow.getChildren().addAll(undoButton, undoCountLabel, resetBtn);

        // Context panel
        contextPanel = new VBox(8);
        contextPanel.setPadding(new Insets(12));
        contextPanel.setStyle(contextStyle("#89b4fa"));
        contextTitle = new Label("💡  What to do next");
        contextTitle.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        contextTitle.setTextFill(Color.web("#89b4fa"));
        contextBody = new Label("Execute threads to build the execution graph.");
        contextBody.setWrapText(true);
        contextBody.setFont(Font.font("Arial", 12));
        contextBody.setTextFill(Color.web("#cdd6f4"));
        inlineChoiceBox = new VBox(8);
        contextPanel.getChildren().addAll(contextTitle, contextBody, inlineChoiceBox);

        // Answer panel
        answerPanel = new VBox(10);
        answerPanel.setPadding(new Insets(10, 0, 0, 0));

        // Feedback panel (shown after answer)
        feedbackPanel = new VBox(10);
        feedbackPanel.setVisible(false);
        feedbackPanel.setManaged(false);

        // Log
        Label logLbl = new Label("Execution Log:");
        logLbl.setFont(Font.font("Arial", FontWeight.BOLD, 11));
        logLbl.setTextFill(Color.web("#6c7086"));
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(90);
        logArea.setFont(Font.font("Monospaced", 10));
        logArea.setStyle("-fx-control-inner-background:#313244;-fx-text-fill:#a6e3a1;");

        leftContent.getChildren().addAll(
                hintLbl, threadLbl, threadButtonsBox, actionRow,
                contextPanel, answerPanel, feedbackPanel,
                logLbl, logArea);

        // Store hintLbl reference for per-question update
        stage.setUserData(hintLbl);

        ScrollPane leftScroll = new ScrollPane(leftContent);
        leftScroll.setFitToWidth(true);
        leftScroll.setPrefWidth(390);
        leftScroll.setMinWidth(390);
        leftScroll.setMaxWidth(390);
        leftScroll.setStyle("-fx-background-color:#181825;-fx-background:#181825;-fx-border-color:transparent;");

        // ── Graph ─────────────────────────────────────────────────────
        graph = new MultiGraph("QuizES");
        graph.setAttribute("ui.stylesheet", graphStyle());
        graph.setAttribute("ui.quality");
        graph.setAttribute("ui.antialias");
        viewer = new FxViewer(graph, FxViewer.ThreadingModel.GRAPH_IN_GUI_THREAD);
        viewer.disableAutoLayout();
        FxDefaultView view = (FxDefaultView) viewer.addDefaultView(false);
        view.setPrefSize(screenW - 390, screenH - 200);

        VBox legendBox = buildLegend();
        StackPane graphStack = new StackPane(view, legendBox);
        StackPane.setAlignment(legendBox, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(legendBox, new Insets(0, 14, 14, 0));

        SplitPane splitPane = new SplitPane(leftScroll, graphStack);
        splitPane.setStyle("-fx-background-color:#1e1e2e;");
        SplitPane.setResizableWithParent(leftScroll, false);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#1e1e2e;");
        root.setTop(new VBox(header, questionPanel));
        root.setCenter(splitPane);

        Scene scene = new Scene(root, screenW, screenH);
        stage.setScene(scene);
        stage.setMaximized(true);

        // Load first question after stage is shown
        stage.setOnShown(e -> {
            splitPane.setDividerPositions(390.0 / stage.getWidth());
            loadCurrentQuestion();
        });
    }

    public void show() {
        stage.show();
        stage.setMaximized(true);
        stage.toFront();
        stage.requestFocus();
    }

    // =========================================================================
    // Question loading
    // =========================================================================

    private void loadCurrentQuestion() {
        if (currentQuestionIdx >= QUESTIONS.size()) {
            showFinalScore();
            return;
        }
        QuizQuestion q = QUESTIONS.get(currentQuestionIdx);

        // Update header
        questionLabel.setText("Question " + (currentQuestionIdx + 1) + " of " + QUESTIONS.size());
        questionText.setText(q.question());

        // Update hint
        if (stage.getUserData() instanceof Label hintLbl)
            hintLbl.setText(q.hint());

        // Reset model
        Event.resetCounter();
        undoStack.clear();
        Arrays.fill(shadowPCs, 0);
        pendingThreadIdx = -1; pendingInstr = null; pendingValid = null;
        nodeCssClass.clear(); nodeXY.clear(); threadEventCount.clear();
        graph.clear();
        graph.setAttribute("ui.stylesheet", graphStyle());
        graph.setAttribute("ui.quality");
        graph.setAttribute("ui.antialias");

        try {
            program = new ProgramParser().parse(q.program());
            eventStructure = new EventStructure();
            executionState = new ExecutionState(program, eventStructure);
            for (Map.Entry<String, Integer> e : program.getInitValues().entrySet()) {
                WriteEvent init = new WriteEvent(0, e.getKey(), MemoryOrder.SC, e.getValue(), String.valueOf(e.getValue()));
                eventStructure.addEvent(init);
                eventStructure.addCoherenceOrder(e.getKey(), init);
                addNode(init, "init");
            }
        } catch (ProgramParser.ParseException ex) {
            log("❌ Parse error: " + ex.getMessage());
            return;
        }

        // Reset UI
        answerPanel.getChildren().clear();
        feedbackPanel.getChildren().clear();
        feedbackPanel.setVisible(false);
        inlineChoiceBox.getChildren().clear();
        undoButton.setDisable(true);
        undoCountLabel.setText("");

        updateButtons();
        buildAnswerPanel();
        setContext("💡  Build the execution", "Execute threads to build the execution graph, then submit your answer below.", "#89b4fa");
        log("📋 Question " + (currentQuestionIdx + 1) + ": " + q.litmusName());
    }

    // =========================================================================
    // Answer panel
    // =========================================================================

    private void buildAnswerPanel() {
        answerPanel.getChildren().clear();

        boolean done = executionState != null && executionState.allThreadsDone();

        Label answerLbl = new Label("📝  Your Answer:");
        answerLbl.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        answerLbl.setTextFill(Color.web("#cdd6f4"));

        Label subLbl = new Label("Is this outcome possible under WEAKEST?");
        subLbl.setFont(Font.font("Arial", 11));
        subLbl.setTextFill(Color.web("#6c7086"));
        subLbl.setWrapText(true);

        Label reminderLbl = new Label("\u26A0\uFE0F  Complete the execution first, then answer.");
        reminderLbl.setFont(Font.font("Arial", FontPosture.ITALIC, 11));
        reminderLbl.setTextFill(Color.web("#f9e2af"));
        reminderLbl.setWrapText(true);
        reminderLbl.setVisible(!done);
        reminderLbl.setManaged(!done);

        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        Button yesBtn = new Button("\u2705  YES \u2014 It's Possible");
        yesBtn.setStyle("-fx-background-color:" + (done ? "#a6e3a1" : "#585b70") +
                ";-fx-text-fill:" + (done ? "#1e1e2e" : "#9399b2") +
                ";-fx-font-weight:bold;-fx-padding:10 16;");
        yesBtn.setDisable(!done);
        yesBtn.setOnAction(e -> submitAnswer(true));

        Button noBtn = new Button("\u274C  NO \u2014 It's Forbidden");
        noBtn.setStyle("-fx-background-color:" + (done ? "#f38ba8" : "#585b70") +
                ";-fx-text-fill:" + (done ? "#1e1e2e" : "#9399b2") +
                ";-fx-font-weight:bold;-fx-padding:10 16;");
        noBtn.setDisable(!done);
        noBtn.setOnAction(e -> submitAnswer(false));

        btnRow.getChildren().addAll(yesBtn, noBtn);
        answerPanel.getChildren().addAll(answerLbl, subLbl, reminderLbl, btnRow);
    }

    // =========================================================================
    // Answer submission & validation
    // =========================================================================

    private void submitAnswer(boolean userSaidYes) {
        QuizQuestion q = QUESTIONS.get(currentQuestionIdx);
        boolean correct = (userSaidYes == q.correctAnswer());

        if (correct) score++;
        totalAnswered++;
        answerHistory.add(correct);
        scoreLabel.setText("Score: " + score + " / " + totalAnswered);

        showFeedback(correct, q.explanation());
    }

    private boolean validateExecution(QuizQuestion q, boolean userSaidYes) {
        if (executionState == null) return true;
        Map<String, Integer> locals = executionState.getAllLocalVars();

        // Parse the expected outcome e.g. "r1=0,r2=0"
        String[] parts = q.outcomeToCheck().split(",");
        boolean outcomeAchieved = true;
        for (String part : parts) {
            String[] kv = part.trim().split("=");
            if (kv.length != 2) continue;
            String var = kv[0].trim();
            int expectedVal;
            try { expectedVal = Integer.parseInt(kv[1].trim()); }
            catch (NumberFormatException e) { continue; }
            Integer actualVal = locals.get("@" + var);
            if (actualVal == null) actualVal = locals.get(var);
            if (actualVal == null || actualVal != expectedVal) {
                outcomeAchieved = false;
                break;
            }
        }
        // If user said YES, their execution should show the outcome
        // If user said NO, their execution doesn't need to show it
        return !userSaidYes || outcomeAchieved;
    }

    private void showFeedback(boolean correct, String explanation) {
        answerPanel.setVisible(false);
        answerPanel.setManaged(false);
        feedbackPanel.getChildren().clear();
        feedbackPanel.setVisible(true);
        feedbackPanel.setManaged(true);

        String emoji = correct ? "🎉" : "❌";
        String verdict = correct ? "Correct!" : "Incorrect";
        String color = correct ? "#a6e3a1" : "#f38ba8";

        Label verdictLbl = new Label(emoji + "  " + verdict);
        verdictLbl.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        verdictLbl.setTextFill(Color.web(color));
        verdictLbl.setPadding(new Insets(8));
        verdictLbl.setStyle("-fx-background-color:#1e1e2e;-fx-border-color:" + color +
                ";-fx-border-width:0 0 0 4;-fx-background-radius:4;");

        TextArea explArea = new TextArea(explanation);
        explArea.setEditable(false);
        explArea.setWrapText(true);
        explArea.setPrefHeight(110);
        explArea.setFont(Font.font("Arial", 12));
        explArea.setStyle("-fx-control-inner-background:#313244;-fx-text-fill:#cdd6f4;");

        HBox navRow = new HBox(10);
        navRow.setAlignment(Pos.CENTER_LEFT);

        boolean hasNext = currentQuestionIdx + 1 < QUESTIONS.size();
        Button nextBtn = new Button(hasNext ? "Next Question ➜" : "🏆  See Final Score");
        nextBtn.setStyle("-fx-background-color:#89b4fa;-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:10 16;");
        nextBtn.setOnAction(e -> {
            currentQuestionIdx++;
            answerPanel.setVisible(true);
            answerPanel.setManaged(true);
            loadCurrentQuestion();
        });

        navRow.getChildren().add(nextBtn);
        feedbackPanel.getChildren().addAll(verdictLbl, explArea, navRow);
        setContext(correct ? "🎉  Well done!" : "📖  Keep learning!", explanation.substring(0, Math.min(80, explanation.length())) + "...", color);
    }

    // =========================================================================
    // Final score screen
    // =========================================================================

    private void showFinalScore() {
        // Replace center with score screen
        String grade = score == QUESTIONS.size() ? "🏆 Perfect!" :
                score >= (QUESTIONS.size() * 0.8) ? "⭐ Excellent!" :
                        score >= (QUESTIONS.size() * 0.6) ? "👍 Good!" : "📖 Keep Practising!";

        VBox scoreScreen = new VBox(20);
        scoreScreen.setAlignment(Pos.CENTER);
        scoreScreen.setPadding(new Insets(40));
        scoreScreen.setStyle("-fx-background-color:#1e1e2e;");

        Label gradeLbl = new Label(grade);
        gradeLbl.setFont(Font.font("Arial", FontWeight.BOLD, 36));
        gradeLbl.setTextFill(Color.web("#cba6f7"));

        Label scoreLbl = new Label("You scored  " + score + " / " + QUESTIONS.size());
        scoreLbl.setFont(Font.font("Arial", FontWeight.BOLD, 24));
        scoreLbl.setTextFill(Color.web("#cdd6f4"));

        VBox breakdown = new VBox(8);
        breakdown.setAlignment(Pos.CENTER);
        for (int i = 0; i < answerHistory.size(); i++) {
            boolean ok = answerHistory.get(i);
            HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT);
            Label dot = new Label(ok ? "✅" : "❌");
            dot.setFont(Font.font(16));
            Label ql = new Label("Q" + (i+1) + ": " + QUESTIONS.get(i).litmusName() + " — " + QUESTIONS.get(i).question());
            ql.setFont(Font.font("Arial", 13));
            ql.setTextFill(Color.web(ok ? "#a6e3a1" : "#f38ba8"));
            ql.setWrapText(true);
            ql.setMaxWidth(600);
            row.getChildren().addAll(dot, ql);
            breakdown.getChildren().add(row);
        }

        Button retryBtn = new Button("🔄  Try Again");
        retryBtn.setStyle("-fx-background-color:#cba6f7;-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-font-size:15;-fx-padding:12 30;");
        retryBtn.setOnAction(e -> {
            currentQuestionIdx = 0; score = 0; totalAnswered = 0;
            answerHistory.clear();
            scoreLabel.setText("Score: 0 / 0");
            buildStage();
            show();
            stage.close(); // close old stage
        });

        Button closeBtn2 = new Button("Close");
        closeBtn2.setStyle("-fx-background-color:#45475a;-fx-text-fill:#cdd6f4;-fx-font-weight:bold;-fx-padding:12 24;");
        closeBtn2.setOnAction(e -> stage.close());

        HBox btnRow = new HBox(14, retryBtn, closeBtn2);
        btnRow.setAlignment(Pos.CENTER);

        scoreScreen.getChildren().addAll(gradeLbl, scoreLbl, breakdown, btnRow);

        ScrollPane sp = new ScrollPane(scoreScreen);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color:#1e1e2e;-fx-background:#1e1e2e;");

        // Replace scene
        stage.getScene().setRoot(sp);
    }

    // =========================================================================
    // Thread execution (mirrors MainController logic)
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

    private void executeThread(int idx) {
        if (executionState.isThreadDone(idx)) return;
        if (pendingThreadIdx >= 0) { startContextPulse("#f9e2af"); return; }
        try {
            Instruction instr = executionState.getNextInstruction(idx);
            if (instr.getType() == Instruction.InstructionType.READ) {
                int cb = Event.peekCounter();
                ReadEvent probe = new ReadEvent(idx+1, instr.getVariable(), instr.getMemoryOrder(), instr.getLocalVar());
                List<Event> valid = checker.getValidWritesFor(eventStructure, probe);
                Event.resetCounterTo(cb);
                if (valid.isEmpty()) { log("❌ No valid writes"); return; }
                pushSnapshot();
                showInlineChoice(idx, instr, valid);
            } else {
                pushSnapshot();
                handleWrite(idx, instr);
                shadowPCs[idx] = executionState.isThreadDone(idx)
                        ? program.getThreads().get(idx).size() : shadowPCs[idx] + 1;
                graph.setAttribute("ui.stylesheet", graphStyle());
                updateButtons();
                buildAnswerPanel();
                if (executionState.allThreadsDone()) {
                    setContext("✅  Execution complete!", "Now submit your answer below.", "#a6e3a1");
                }
            }
        } catch (Exception ex) { log("❌ " + ex.getMessage()); }
    }

    private void showInlineChoice(int idx, Instruction instr, List<Event> valid) {
        pendingThreadIdx = idx; pendingInstr = instr; pendingValid = valid;
        stopContextPulse();
        contextTitle.setText("🟡  Choose: What does " + instr.getLocalVar() + " read?");
        contextTitle.setTextFill(Color.web("#f9e2af"));
        contextBody.setText("Thread "+(idx+1)+" reads "+instr.getVariable()+" ["+instr.getMemoryOrder().name().toLowerCase()+"]");
        contextBody.setTextFill(Color.web("#a6adc8"));
        contextPanel.setStyle(contextStyle("#f9e2af"));
        inlineChoiceBox.getChildren().clear();
        final Event[] chosen = {valid.get(0)};
        List<VBox> cards = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            Event w = valid.get(i);
            boolean isInit = w.getThreadId() == 0;
            boolean isCross = !isInit && w.getThreadId() != idx+1;
            VBox card = new VBox(4);
            card.setPadding(new Insets(8, 10, 8, 10));
            card.setStyle("-fx-background-color:" + (i==0?"#2e2e4e":"#252535") +
                    ";-fx-border-color:" + (i==0?"#89b4fa":"#45475a") +
                    ";-fx-border-width:2;-fx-border-radius:8;-fx-background-radius:8;-fx-cursor:hand;");
            cards.add(card);
            Label badge = new Label(" " + w.getValue() + " ");
            badge.setFont(Font.font("Arial", FontWeight.BOLD, 16));
            badge.setStyle("-fx-background-color:" + (isInit?"#fab387":isCross?"#a6e3a1":"#89b4fa") +
                    ";-fx-text-fill:#1e1e2e;-fx-background-radius:4;-fx-padding:2 8;");
            String src = isInit ? "Initial value" : (isCross?"⚡ Thread "+w.getThreadId():" Thread "+w.getThreadId());
            Label srcLbl = new Label(src);
            srcLbl.setFont(Font.font("Arial", 12)); srcLbl.setTextFill(Color.web("#cdd6f4"));
            card.getChildren().addAll(badge, srcLbl);
            final Event thisW = w; final int thisIdx = i;
            card.setOnMouseClicked(e -> {
                chosen[0] = thisW;
                for (int j=0;j<cards.size();j++) {
                    boolean sel=(j==thisIdx);
                    cards.get(j).setStyle("-fx-background-color:"+(sel?"#2e2e4e":"#252535")+
                            ";-fx-border-color:"+(sel?"#a6e3a1":"#45475a")+
                            ";-fx-border-width:2;-fx-border-radius:8;-fx-background-radius:8;-fx-cursor:hand;");
                }
            });
            inlineChoiceBox.getChildren().add(card);
        }
        Button confirm = new Button(valid.size()==1?"👍  Continue":"✅  Confirm");
        confirm.setMaxWidth(Double.MAX_VALUE);
        confirm.setStyle("-fx-background-color:#a6e3a1;-fx-text-fill:#1e1e2e;-fx-font-weight:bold;-fx-padding:8;");
        confirm.setOnAction(e -> {
            int saved = pendingThreadIdx; Instruction si = pendingInstr; Event pick = chosen[0];
            pendingThreadIdx=-1; pendingInstr=null; pendingValid=null;
            inlineChoiceBox.getChildren().clear(); stopContextPulse();
            ReadEvent realRead = new ReadEvent(saved+1, si.getVariable(), si.getMemoryOrder(), si.getLocalVar());
            completeRead(saved, si, realRead, pick);
            shadowPCs[saved] = executionState.isThreadDone(saved)
                    ? program.getThreads().get(saved).size() : shadowPCs[saved]+1;
            graph.setAttribute("ui.stylesheet", graphStyle());
            updateButtons();
            buildAnswerPanel();
            if (executionState.allThreadsDone())
                setContext("✅  Execution complete!", "Now submit your answer below.", "#a6e3a1");
        });
        inlineChoiceBox.getChildren().add(confirm);
        startContextPulse("#f9e2af");
    }

    private void completeRead(int idx, Instruction instr, ReadEvent read, Event write) {
        eventStructure.addEvent(read);
        eventStructure.addReadsFrom(read, write);
        Event last = executionState.getLastEventForThread(idx);
        if(last!=null){ eventStructure.addProgramOrder(last,read); }
        executionState.setLastEventForThread(idx,read);
        executionState.setLocalVar(instr.getLocalVar(), read.getValue());
        executionState.advanceThread(idx);
        // CRITICAL: addNode BEFORE any addEdge calls
        addNode(read, "read");
        if (last != null) addEdge(last, read, "po");
        addEdge(write, read, "rf");
        boolean sw = (write.getMemoryOrder()==MemoryOrder.RELEASE||write.getMemoryOrder()==MemoryOrder.SC)
                && (read.getMemoryOrder()==MemoryOrder.ACQUIRE||read.getMemoryOrder()==MemoryOrder.SC);
        if(sw) addSwEdge(write,read);
        graph.setAttribute("ui.stylesheet", graphStyle());
        log("✅ "+instr.getLocalVar()+"="+read.getValue());
    }

    private void handleWrite(int idx, Instruction instr) {
        int val = executionState.evaluateExpression(instr.getValueExpr());
        WriteEvent write = new WriteEvent(idx+1, instr.getVariable(), instr.getMemoryOrder(), val, instr.getValueExpr());
        eventStructure.addEvent(write);
        List<Integer> coList = eventStructure.getCoherenceOrder().getOrDefault(instr.getVariable(), Collections.emptyList());
        if(!coList.isEmpty()){
            Event prev = eventStructure.getEventById(coList.get(coList.size()-1));
            addNode(write,"write");
            if(prev!=null) addCoEdge(prev,write);
        } else addNode(write,"write");
        eventStructure.addCoherenceOrder(instr.getVariable(),write);
        Event last = executionState.getLastEventForThread(idx);
        if(last!=null){ eventStructure.addProgramOrder(last,write); addEdge(last,write,"po"); }
        executionState.setLastEventForThread(idx,write);
        executionState.advanceThread(idx);
        log("✅ write("+instr.getVariable()+"="+val+")");
    }

    // =========================================================================
    // Undo
    // =========================================================================

    private void pushSnapshot() {
        if(program==null) return;
        int[] pcs = Arrays.copyOf(shadowPCs, program.getThreadCount());
        Map<String,Integer> vars = new HashMap<>(executionState.getAllLocalVars());
        List<Integer> lastIds = new ArrayList<>();
        for(int i=0;i<program.getThreadCount();i++){
            Event e=executionState.getLastEventForThread(i); lastIds.add(e!=null?e.getId():-1);
        }
        List<ExecutionSnapshot.EventRecord> evRecs = new ArrayList<>();
        for(Event e:eventStructure.getEvents())
            evRecs.add(new ExecutionSnapshot.EventRecord(e.getId(),e.getThreadId(),e.getType().name(),
                    e.getVariable(),e.getValue(),e.getMemoryOrder().name(),(e instanceof ReadEvent re)?re.getLocalVar():null));
        Map<Integer,List<Integer>> po=new HashMap<>();
        eventStructure.getProgramOrder().forEach((k,v)->po.put(k,new ArrayList<>(v)));
        Map<Integer,Integer> rf=new HashMap<>(eventStructure.getReadsFrom());
        Map<String,List<Integer>> co=new HashMap<>();
        eventStructure.getCoherenceOrder().forEach((k,v)->co.put(k,new ArrayList<>(v)));
        List<ExecutionSnapshot.NodeRecord> nodes=new ArrayList<>();
        nodeCssClass.forEach((id,css)->{
            double[] xy=nodeXY.getOrDefault(id,new double[]{0,0});
            nodes.add(new ExecutionSnapshot.NodeRecord(id,css,xy[0],xy[1]));
        });
        List<ExecutionSnapshot.EdgeRecord> edges=new ArrayList<>();
        graph.edges().forEach(edge->edges.add(new ExecutionSnapshot.EdgeRecord(
                edge.getId(),edge.getSourceNode().getId(),edge.getTargetNode().getId(),
                (String)edge.getAttribute("ui.class"),(String)edge.getAttribute("ui.label"))));
        for(Map.Entry<Integer,Integer> e:threadEventCount.entrySet())
            co.put("__tec__"+e.getKey(),new ArrayList<>(List.of(e.getValue())));
        undoStack.push(new ExecutionSnapshot(pcs,vars,lastIds,evRecs,po,rf,co,nodes,edges));
        undoButton.setDisable(false);
        undoCountLabel.setText("("+undoStack.size()+")");
    }

    private void performUndo() {
        if(undoStack.isEmpty()) return;
        pendingThreadIdx=-1; pendingInstr=null; pendingValid=null;
        ExecutionSnapshot snap = undoStack.pop();
        restoreSnapshot(snap);
        undoButton.setDisable(undoStack.isEmpty());
        undoCountLabel.setText(undoStack.isEmpty()?"":"("+undoStack.size()+")");
        updateButtons();
        buildAnswerPanel();
        setContext("↩ Undone", "Choose a different execution path.", "#89b4fa");
    }

    private void restoreSnapshot(ExecutionSnapshot snap) {
        eventStructure=new EventStructure();
        Map<Integer,Event> idToEvent=new LinkedHashMap<>();
        for(ExecutionSnapshot.EventRecord rec:snap.events){
            Event e=rec.type().equals("READ")
                    ?new ReadEvent(rec.threadId(),rec.variable(),MemoryOrder.valueOf(rec.memOrder()),rec.localVar())
                    :new WriteEvent(rec.threadId(),rec.variable(),MemoryOrder.valueOf(rec.memOrder()),rec.value(),String.valueOf(rec.value()));
            Event.forceId(e,rec.id()); eventStructure.addEvent(e); idToEvent.put(rec.id(),e);
        }
        snap.programOrder.forEach((fid,tids)->tids.forEach(tid->{
            Event f=idToEvent.get(fid),t=idToEvent.get(tid);
            if(f!=null&&t!=null) eventStructure.addProgramOrder(f,t);
        }));
        snap.readsFrom.forEach((rid,wid)->{
            Event r=idToEvent.get(rid),w=idToEvent.get(wid);
            if(r instanceof ReadEvent re&&w!=null) eventStructure.addReadsFrom(re,w);
        });
        snap.coherenceOrder.forEach((var,ids)->{
            if(var.startsWith("__tec__")) return;
            for(int id:ids){Event w=idToEvent.get(id);if(w instanceof WriteEvent we) eventStructure.addCoherenceOrder(var,we);}
        });
        executionState=new ExecutionState(program,eventStructure);
        int[] pcs=snap.threadPCs;
        for(int i=0;i<pcs.length;i++) for(int j=0;j<pcs[i];j++) executionState.advanceThread(i);
        snap.localVars.forEach(executionState::setLocalVar);
        for(int i=0;i<snap.lastEventIdPerThread.size();i++){
            int eid=snap.lastEventIdPerThread.get(i);
            if(eid>=0) executionState.setLastEventForThread(i,idToEvent.get(eid));
        }
        int maxId=snap.events.stream().mapToInt(ExecutionSnapshot.EventRecord::id).max().orElse(-1);
        Event.resetCounterTo(maxId+1);
        threadEventCount.clear();
        snap.coherenceOrder.forEach((k,v)->{
            if(k.startsWith("__tec__")) threadEventCount.put(Integer.parseInt(k.substring(7)),v.get(0));
        });
        graph.clear(); nodeCssClass.clear(); nodeXY.clear();
        graph.setAttribute("ui.stylesheet",graphStyle());
        graph.setAttribute("ui.quality"); graph.setAttribute("ui.antialias");
        snap.nodeRecords.forEach(nr->{
            Node n=graph.addNode(nr.nodeId());
            Event e=idToEvent.get(Integer.parseInt(nr.nodeId().substring(1)));
            n.setAttribute("ui.label",e!=null?e.toString():nr.nodeId());
            n.setAttribute("ui.class",nr.cssClass()); n.setAttribute("xyz",nr.x(),nr.y(),0);
            nodeCssClass.put(nr.nodeId(),nr.cssClass()); nodeXY.put(nr.nodeId(),new double[]{nr.x(),nr.y()});
        });
        snap.edgeRecords.forEach(er->{
            if(graph.getEdge(er.edgeId())!=null) return;
            Edge edge=graph.addEdge(er.edgeId(),er.fromNode(),er.toNode(),true);
            edge.setAttribute("ui.class",er.cssClass()); edge.setAttribute("ui.label",er.label());
        });
        System.arraycopy(pcs,0,shadowPCs,0,pcs.length);
    }

    // =========================================================================
    // Graph helpers
    // =========================================================================

    private double columnX(int tid) {
        int total=(program!=null?program.getThreadCount():0)+1;
        return (tid-(total-1)/2.0)*COLUMN_SPACING;
    }
    private double nextRowY(int tid) {
        int row=threadEventCount.getOrDefault(tid,0); threadEventCount.put(tid,row+1);
        return -row*ROW_SPACING;
    }
    private void addNode(Event e, String css) {
        String id="e"+e.getId(); if(graph.getNode(id)!=null) return;
        double x=columnX(e.getThreadId()), y=nextRowY(e.getThreadId());
        Node n=graph.addNode(id); n.setAttribute("ui.label",e.toString());
        n.setAttribute("ui.class",css); n.setAttribute("xyz",x,y,0);
        nodeCssClass.put(id,css); nodeXY.put(id,new double[]{x,y});
    }
    private void addEdge(Event f, Event t, String type) {
        String id=type+f.getId()+"_"+t.getId(); if(graph.getEdge(id)!=null) return;
        Edge e=graph.addEdge(id,"e"+f.getId(),"e"+t.getId(),true);
        e.setAttribute("ui.class",type); e.setAttribute("ui.label",type);
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
    // Context / pulse helpers
    // =========================================================================

    private void setContext(String title, String body, String colour) {
        stopContextPulse();
        contextTitle.setText(title); contextTitle.setTextFill(Color.web(colour));
        contextBody.setText(body); contextBody.setTextFill(Color.web("#cdd6f4"));
        inlineChoiceBox.getChildren().clear();
        contextPanel.setStyle(contextStyle(colour));
    }
    private void startContextPulse(String colour) {
        stopContextPulse();
        String dimmed = colour+"88";
        contextPulse = new Timeline(
                new KeyFrame(Duration.ZERO,        e->contextPanel.setStyle(contextStyle(colour))),
                new KeyFrame(Duration.millis(600),  e->contextPanel.setStyle(contextStyle(dimmed))),
                new KeyFrame(Duration.millis(1200), e->contextPanel.setStyle(contextStyle(colour)))
        );
        contextPulse.setCycleCount(Animation.INDEFINITE); contextPulse.play();
    }
    private void stopContextPulse() {
        if(contextPulse!=null){contextPulse.stop();contextPulse=null;}
    }
    private String contextStyle(String colour) {
        return "-fx-background-color:#1e1e2e;-fx-border-color:"+colour+
                ";-fx-border-width:0 0 0 5;-fx-border-radius:4;-fx-background-radius:4;";
    }
    private void log(String msg) { logArea.appendText(msg+"\n"); }

    // =========================================================================
    // Legend + Graph style
    // =========================================================================

    private VBox buildLegend() {
        VBox box = new VBox(4);
        box.setPadding(new Insets(8,10,8,10));
        box.setStyle("-fx-background-color:rgba(30,30,46,0.88);-fx-background-radius:8;" +
                "-fx-border-color:#45475a;-fx-border-width:1;-fx-border-radius:8;");
        box.setMaxWidth(Region.USE_PREF_SIZE); box.setMaxHeight(Region.USE_PREF_SIZE);
        for(String[] item:new String[][]{
                {"#fab387","INIT"},{"#a6e3a1","READ"},{"#f38ba8","WRITE"},
                {"#89b4fa","─  po"},{"#a6e3a1","─  rf"},{"#cba6f7","╌  co"},{"#94e2d5","━  sw"}}){
            HBox lr=new HBox(6); lr.setAlignment(Pos.CENTER_LEFT);
            javafx.scene.shape.Rectangle dot=new javafx.scene.shape.Rectangle(10,10);
            dot.setArcWidth(3); dot.setArcHeight(3); dot.setFill(Color.web(item[0]));
            Label lbl=new Label(item[1]);
            lbl.setFont(Font.font("Monospaced",11)); lbl.setTextFill(Color.web(item[0]));
            lr.getChildren().addAll(dot,lbl); box.getChildren().add(lr);
        }
        return box;
    }

    private String graphStyle() {
        return "graph{padding:150px;}" +
                "node{shape:rounded-box;size:150px,50px;fill-color:#313244;" +
                "stroke-mode:plain;stroke-color:#89b4fa;stroke-width:2px;" +
                "text-color:#cdd6f4;text-style:bold;text-size:14;}" +
                "node.init{stroke-color:#fab387;text-color:#fab387;}" +
                "node.read{stroke-color:#a6e3a1;text-color:#a6e3a1;}" +
                "node.write{stroke-color:#f38ba8;text-color:#f38ba8;}" +
                "edge{arrow-shape:arrow;arrow-size:14px,7px;text-size:12;text-color:#cdd6f4;" +
                "text-background-mode:rounded-box;text-background-color:#1e1e2e;text-padding:3px;" +
                "fill-color:#89b4fa;size:2px;}" +
                "edge.rf{fill-color:#a6e3a1;size:2px;}" +
                "edge.po{fill-color:#89b4fa;size:2px;}" +
                "edge.co{fill-color:#cba6f7;size:2px;stroke-mode:dashes;}" +
                "edge.sw{fill-color:#94e2d5;size:4px;}";
    }
}