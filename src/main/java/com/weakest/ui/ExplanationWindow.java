package com.weakest.ui;

import com.weakest.model.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.paint.CycleMethod;
import javafx.scene.shape.*;
import javafx.scene.text.*;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.*;

public class ExplanationWindow {

    private Stage stage;
    private EventStructure eventStructure;
    private ExecutionState executionState;
    private Program program;

    // Live-updating panels
    private VBox executionNarrativeBox;
    private Label lastActionLabel;

    public ExplanationWindow() {
        stage = new Stage();
        stage.setTitle("📖  WEAKEST — Interactive Learning Companion");

        // Default full screen
        javafx.geometry.Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        stage.setX(screen.getMinX());
        stage.setY(screen.getMinY());
        stage.setWidth(screen.getWidth());
        stage.setHeight(screen.getHeight());
        stage.setMaximized(true);
        stage.setMaximized(true);

        buildUI();
    }

    private void buildUI() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #11111b;");

        // ── Header ──────────────────────────────────────────────────────
        HBox header = new HBox(16);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setStyle("-fx-background-color: #181825;");

        Label icon = new Label("🧠");
        icon.setFont(Font.font(32));

        VBox headerText = new VBox(2);
        Label title = new Label("WEAKEST Learning Companion");
        title.setFont(Font.font("Arial", FontWeight.BOLD, 22));
        title.setTextFill(Color.web("#cdd6f4"));
        Label subtitle = new Label("Understanding weak memory concurrency — visually and interactively");
        subtitle.setFont(Font.font("Arial", FontPosture.ITALIC, 13));
        subtitle.setTextFill(Color.web("#6c7086"));
        headerText.getChildren().addAll(title, subtitle);
        header.getChildren().addAll(icon, headerText);
        root.setTop(header);

        // ── Tabs ────────────────────────────────────────────────────────
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setStyle(
                "-fx-background-color: #11111b;" +
                        "-fx-tab-min-width: 200px;" +
                        "-fx-tab-max-width: 200px;"
        );

        Tab tab1 = new Tab("  🔍  What's Happening Now  ");
        tab1.setContent(buildCurrentExecutionTab());

        Tab tab2 = new Tab("  📐  Key Concepts  ");
        tab2.setContent(buildConceptsTab());

        Tab tab3 = new Tab("  🌍  Why Does This Matter?  ");
        tab3.setContent(buildWhyItMattersTab());

        tabs.getTabs().addAll(tab1, tab2, tab3);
        root.setCenter(tabs);

        Scene scene = new Scene(root);
        stage.setScene(scene);
    }

    // TAB 1 — What's Happening Now

    private ScrollPane buildCurrentExecutionTab() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(28, 40, 28, 40));
        content.setStyle("-fx-background-color: #11111b;");

        // Last action highlight box
        lastActionLabel = new Label("Load a program and start executing to see explanations here.");
        lastActionLabel.setWrapText(true);
        lastActionLabel.setFont(Font.font("Arial", 15));
        lastActionLabel.setTextFill(Color.web("#cdd6f4"));

        VBox lastActionBox = new VBox(8);
        lastActionBox.setPadding(new Insets(16));
        lastActionBox.setStyle(
                "-fx-background-color: #1e1e2e;" +
                        "-fx-border-color: #89b4fa;" +
                        "-fx-border-width: 0 0 0 4;" +
                        "-fx-border-radius: 4;"
        );
        Label lastActionTitle = new Label("⚡  Last Action");
        lastActionTitle.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        lastActionTitle.setTextFill(Color.web("#89b4fa"));
        lastActionBox.getChildren().addAll(lastActionTitle, lastActionLabel);

        // Narrative scroll area
        Label narrativeTitle = sectionTitle("📜  Execution Story So Far");

        executionNarrativeBox = new VBox(12);
        executionNarrativeBox.setPadding(new Insets(8, 0, 8, 0));

        Label emptyMsg = new Label("Your execution story will appear here as you add events.");
        emptyMsg.setTextFill(Color.web("#6c7086"));
        emptyMsg.setFont(Font.font("Arial", FontPosture.ITALIC, 13));
        executionNarrativeBox.getChildren().add(emptyMsg);

        // Legend
        Label legendTitle = sectionTitle("🎨  Colour Legend");
        HBox legend = new HBox(24);
        legend.setAlignment(Pos.CENTER_LEFT);
        legend.getChildren().addAll(
                legendChip("#fab387", "INIT write"),
                legendChip("#a6e3a1", "READ event"),
                legendChip("#f38ba8", "WRITE event"),
                legendChip("#89b4fa", "po  (program order)"),
                legendChip("#a6e3a1", "rf  (reads-from)"),
                legendChip("#cba6f7", "co  (coherence order)")
        );

        content.getChildren().addAll(lastActionBox, narrativeTitle, executionNarrativeBox, legendTitle, legend);

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background: #11111b; -fx-background-color: #11111b;");
        return sp;
    }

    // TAB 2 — Key Concepts (visual cards)

    private ScrollPane buildConceptsTab() {
        VBox content = new VBox(32);
        content.setPadding(new Insets(28, 40, 40, 40));
        content.setStyle("-fx-background-color: #11111b;");

        content.getChildren().addAll(
                sectionTitle("📐  Core Concepts of WEAKEST"),
                buildConceptCard(
                        "🧵  Program Order (po)",
                        "#89b4fa",
                        "Program order (po) is the order in which instructions appear in a thread's source code. " +
                                "It's the sequence a programmer writes — top to bottom. " +
                                "In the event graph, po edges (blue arrows) connect events within the same thread in order.",
                        buildPoCanvas()
                ),
                buildConceptCard(
                        "📬  Reads-From (rf)",
                        "#a6e3a1",
                        "The reads-from relation (rf) connects a WRITE event to the READ event that read its value. " +
                                "When you click a thread button and choose which write to read from, you're defining an rf edge. " +
                                "This is the core of execution semantics — rf edges show HOW data flows between threads.",
                        buildRfCanvas()
                ),
                buildConceptCard(
                        "⚖️  Coherence Order (co)",
                        "#cba6f7",
                        "Coherence order (co) is the total order of all writes to the same variable. " +
                                "Every write to X must be ordered relative to every other write to X. " +
                                "This prevents the absurdity of different threads seeing writes to X in different orders.",
                        buildCoCanvas()
                ),
                buildConceptCard(
                        "🌀  Out-of-Thin-Air (OOTA)",
                        "#f38ba8",
                        "OOTA is when a value appears in a register that was NEVER written to memory — it came from nowhere. " +
                                "Example: both threads read a=42 even though 42 was never initialized. " +
                                "WEAKEST prevents OOTA by requiring every read to be justified by a causally prior write, " +
                                "and forbidding circular justification chains. " +
                                "If accepting a read would create a cycle in po+rf, WEAKEST blocks it.",
                        buildOotaCanvas()
                ),
                buildConceptCard(
                        "🔒  Memory Orders (rlx, acq, rel, sc)",
                        "#fab387",
                        "Memory orders control how strongly a read or write synchronizes with other threads:\n\n" +
                                "  • relaxed (rlx) — no synchronization guarantees. Just the value.\n" +
                                "  • acquire (acq) — a read that 'acquires' all writes visible before the matching release.\n" +
                                "  • release (rel) — a write that 'releases', making all prior writes visible to any matching acquire.\n" +
                                "  • sc — sequentially consistent. The strongest: all sc operations appear in a total global order.",
                        buildMemOrdCanvas()
                )
        );

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background: #11111b; -fx-background-color: #11111b;");
        return sp;
    }

    // TAB 3 — Why Does This Matter?

    private ScrollPane buildWhyItMattersTab() {
        VBox content = new VBox(28);
        content.setPadding(new Insets(28, 40, 40, 40));
        content.setStyle("-fx-background-color: #11111b;");

        content.getChildren().addAll(
                sectionTitle("🌍  From Theory to Real Hardware"),
                infoCard(
                        "🖥️  Why does x86 allow Store Buffering (SB)?",
                        "#89b4fa",
                        "Intel and AMD CPUs have a per-core write buffer (store buffer). " +
                                "When Thread 1 writes X=1, the value sits in Thread 1's local buffer — " +
                                "Thread 2 reads X from main memory and sees 0 before the buffer drains. " +
                                "\n\nThis is why r1=0, r2=0 is ALLOWED on x86 even though it looks impossible under sequential consistency. " +
                                "It's not a bug — it's a deliberate performance trade-off."
                ),
                infoCard(
                        "📱  Why is ARM even weaker than x86?",
                        "#cba6f7",
                        "ARM allows almost any reordering of independent loads and stores. " +
                                "Without explicit memory barriers (DMB/DSB), a write may become visible to different cores " +
                                "in different orders. This is called non-multi-copy atomicity. " +
                                "\n\nARM's Message Passing (MP) test REQUIRES acquire/release to work correctly. " +
                                "Without it, r1=1 but r2=0 is possible — the flag was set but the data wasn't visible yet."
                ),
                infoCard(
                        "🎓  Why does WEAKEST exist?",
                        "#a6e3a1",
                        "C11's memory model had a flaw: it accidentally allowed thin-air reads due to relaxed semantics. " +
                                "Multiple proposals tried to fix this (Promising Semantics, JES). " +
                                "\n\nWEAKEST (Chakraborty & Vafeiadis, 2019) solved it using event structures: " +
                                "instead of one execution at a time, it represents ALL possible executions together, " +
                                "checking causality constraints incrementally. " +
                                "Every read must be justified by a prior write — no circular justification allowed."
                ),
                infoCard(
                        "🧪  What the litmus tests prove",
                        "#fab387",
                        "Litmus tests are minimal programs that distinguish memory models:\n\n" +
                                "  • SB (Store Buffering): r1=0,r2=0 — ALLOWED under WEAKEST (store buffers)\n" +
                                "  • LB (Load Buffering): r1=1,r2=1 — ALLOWED under WEAKEST (no cycle forms)\n" +
                                "  • MP (Message Passing): if r1=1 then r2=1 — guaranteed by acq/rel\n" +
                                "  • CYC: r1=1,r2=1 — FORBIDDEN (creates OOTA cycle)\n\n" +
                                "The key insight: LB and CYC look almost identical, but one is allowed and one isn't. " +
                                "That's the subtlety WEAKEST captures precisely."
                ),
                infoCard(
                        "💡  How to build intuition",
                        "#f38ba8",
                        "The best way to develop weak memory intuition:\n\n" +
                                "  1. Load a litmus test (SB, LB, or MP)\n" +
                                "  2. Try every possible ordering of thread steps\n" +
                                "  3. Notice which rf choices are allowed and which are blocked\n" +
                                "  4. Ask: could this execution happen on your laptop right now?\n\n" +
                                "The answer is almost always YES for the allowed executions. " +
                                "Real hardware really does this — every time you run a multi-threaded program."
                )
        );

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background: #11111b; -fx-background-color: #11111b;");
        return sp;
    }

    // Canvas mini-diagrams

    private Canvas buildPoCanvas() {
        Canvas c = new Canvas(380, 160);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#181825"));
        g.fillRoundRect(0, 0, 380, 160, 12, 12);

        // Thread column label
        g.setFill(Color.web("#6c7086"));
        g.setFont(Font.font("Monospaced", FontWeight.BOLD, 11));
        g.fillText("Thread 1", 60, 20);

        // Nodes
        drawNode(g, 90, 50, "W(X=1)", "#f38ba8");
        drawNode(g, 90, 110, "R(Y,rlx)", "#a6e3a1");

        // po arrow
        drawArrow(g, 90, 72, 90, 98, "#89b4fa");
        g.setFill(Color.web("#89b4fa"));
        g.setFont(Font.font("Monospaced", 11));
        g.fillText("po", 100, 88);

        // Label
        g.setFill(Color.web("#6c7086"));
        g.setFont(Font.font("Arial", FontPosture.ITALIC, 12));
        g.fillText("Events in the same thread are connected top-to-bottom by po", 10, 150);

        return c;
    }

    private Canvas buildRfCanvas() {
        Canvas c = new Canvas(380, 180);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#181825"));
        g.fillRoundRect(0, 0, 380, 180, 12, 12);

        g.setFill(Color.web("#6c7086"));
        g.setFont(Font.font("Monospaced", FontWeight.BOLD, 11));
        g.fillText("Thread 1", 45, 20);
        g.fillText("Thread 2", 245, 20);

        drawNode(g, 70, 60, "W(X=1,rel)", "#f38ba8");
        drawNode(g, 270, 60, "R(X,acq)", "#a6e3a1");

        // rf arrow crossing threads
        drawArrow(g, 130, 65, 210, 65, "#a6e3a1");
        g.setFill(Color.web("#a6e3a1"));
        g.setFont(Font.font("Monospaced", FontWeight.BOLD, 12));
        g.fillText("rf", 165, 58);

        g.setFill(Color.web("#6c7086"));
        g.setFont(Font.font("Arial", FontPosture.ITALIC, 12));
        g.fillText("rf edge: Thread 2's read gets its value from Thread 1's write", 10, 140);
        g.fillText("This is how data flows between threads!", 10, 158);

        return c;
    }

    private Canvas buildCoCanvas() {
        Canvas c = new Canvas(380, 180);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#181825"));
        g.fillRoundRect(0, 0, 380, 180, 12, 12);

        g.setFill(Color.web("#6c7086"));
        g.setFont(Font.font("Monospaced", FontWeight.BOLD, 11));
        g.fillText("Writes to X — coherence order", 90, 20);

        drawNode(g, 60, 70, "W[T0](X=0)", "#fab387");
        drawNode(g, 190, 70, "W(X=1,rlx)", "#f38ba8");
        drawNode(g, 310, 70, "W(X=2,rlx)", "#f38ba8");

        drawArrow(g, 120, 75, 130, 75, "#cba6f7");
        drawArrow(g, 250, 75, 260, 75, "#cba6f7");

        g.setFill(Color.web("#cba6f7"));
        g.setFont(Font.font("Monospaced", FontWeight.BOLD, 11));
        g.fillText("co", 124, 68);
        g.fillText("co", 254, 68);

        g.setFill(Color.web("#6c7086"));
        g.setFont(Font.font("Arial", FontPosture.ITALIC, 12));
        g.fillText("All writes to the same variable are totally ordered via co", 10, 140);
        g.fillText("Every thread must agree on this order", 10, 158);

        return c;
    }

    private Canvas buildOotaCanvas() {
        Canvas c = new Canvas(380, 200);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#181825"));
        g.fillRoundRect(0, 0, 380, 200, 12, 12);

        g.setFill(Color.web("#f38ba8"));
        g.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        g.fillText("❌  Forbidden OOTA cycle", 110, 20);

        // Four nodes in a diamond representing the cycle
        drawNode(g, 190, 40, "R(X=1)", "#a6e3a1");   // top
        drawNode(g, 60,  100, "W(Y=1)", "#f38ba8");  // left
        drawNode(g, 190, 155, "R(Y=1)", "#a6e3a1");  // bottom
        drawNode(g, 310, 100, "W(X=1)", "#f38ba8");  // right

        // Cycle arrows in red
        drawArrow(g, 190, 62,  100, 95,  "#ff0000");  // R(X)->W(Y)  po
        drawArrow(g, 110, 110, 175, 150, "#ff0000");  // W(Y)->R(Y)  rf
        drawArrow(g, 245, 155, 315, 118, "#ff0000");  // R(Y)->W(X)  po
        drawArrow(g, 315, 88,  245, 50,  "#ff0000");  // W(X)->R(X)  rf

        g.setFill(Color.web("#ff0000"));
        g.setFont(Font.font("Monospaced", 10));
        g.fillText("po", 128, 83);
        g.fillText("rf", 155, 143);
        g.fillText("po", 293, 143);
        g.fillText("rf", 287, 73);

        g.setFill(Color.web("#6c7086"));
        g.setFont(Font.font("Arial", FontPosture.ITALIC, 11));
        g.fillText("WEAKEST blocks any rf edge that would complete this cycle", 20, 190);

        return c;
    }

    private Canvas buildMemOrdCanvas() {
        Canvas c = new Canvas(380, 160);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.web("#181825"));
        g.fillRoundRect(0, 0, 380, 160, 12, 12);

        // Strength bar
        g.setFill(Color.web("#313244"));
        g.fillRoundRect(20, 40, 340, 36, 8, 8);

        // Gradient fill (weak → strong)
        LinearGradient grad = new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#a6e3a1")),
                new Stop(0.5, Color.web("#fab387")),
                new Stop(1.0, Color.web("#f38ba8")));
        g.setFill(grad);
        g.fillRoundRect(20, 40, 340, 36, 8, 8);

        String[] labels = {"rlx", "acq / rel", "sc"};
        double[] xPos   = {40, 160, 310};
        g.setFont(Font.font("Monospaced", FontWeight.BOLD, 13));
        g.setFill(Color.web("#1e1e2e"));
        for (int i = 0; i < labels.length; i++) {
            g.fillText(labels[i], xPos[i], 64);
        }

        g.setFill(Color.web("#6c7086"));
        g.setFont(Font.font("Arial", 12));
        g.fillText("← weaker (faster)              stronger (safer) →", 30, 105);
        g.fillText("rlx: no sync    acq/rel: paired sync    sc: total order", 15, 125);

        return c;
    }

    // Drawing helpers

    private void drawNode(GraphicsContext g, double cx, double cy, String label, String colour) {
        double w = 110, h = 30;
        g.setFill(Color.web("#313244"));
        g.fillRoundRect(cx - w/2, cy - h/2, w, h, 8, 8);
        g.setStroke(Color.web(colour));
        g.setLineWidth(2);
        g.strokeRoundRect(cx - w/2, cy - h/2, w, h, 8, 8);
        g.setFill(Color.web(colour));
        g.setFont(Font.font("Monospaced", FontWeight.BOLD, 11));
        g.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        g.fillText(label, cx, cy + 4);
        g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
    }

    private void drawArrow(GraphicsContext g, double x1, double y1, double x2, double y2, String colour) {
        g.setStroke(Color.web(colour));
        g.setLineWidth(2);
        g.strokeLine(x1, y1, x2, y2);

        // Arrowhead
        double angle = Math.atan2(y2 - y1, x2 - x1);
        double arrowLen = 10;
        double arrowAngle = Math.toRadians(25);
        double x3 = x2 - arrowLen * Math.cos(angle - arrowAngle);
        double y3 = y2 - arrowLen * Math.sin(angle - arrowAngle);
        double x4 = x2 - arrowLen * Math.cos(angle + arrowAngle);
        double y4 = y2 - arrowLen * Math.sin(angle + arrowAngle);
        g.setFill(Color.web(colour));
        g.fillPolygon(new double[]{x2, x3, x4}, new double[]{y2, y3, y4}, 3);
    }

    // UI component builders

    private VBox buildConceptCard(String title, String accentColour, String explanation, Canvas diagram) {
        VBox card = new VBox(14);
        card.setPadding(new Insets(20));
        card.setStyle(
                "-fx-background-color: #1e1e2e;" +
                        "-fx-background-radius: 10;" +
                        "-fx-border-color: " + accentColour + ";" +
                        "-fx-border-width: 0 0 0 5;" +
                        "-fx-border-radius: 4;"
        );

        Label titleLabel = new Label(title);
        titleLabel.setFont(Font.font("Arial", FontWeight.BOLD, 17));
        titleLabel.setTextFill(Color.web(accentColour));

        Label body = new Label(explanation);
        body.setWrapText(true);
        body.setFont(Font.font("Arial", 14));
        body.setTextFill(Color.web("#cdd6f4"));
        body.setLineSpacing(3);

        card.getChildren().addAll(titleLabel, body);
        if (diagram != null) {
            Label diagramLabel = new Label("Visual:");
            diagramLabel.setFont(Font.font("Arial", FontWeight.BOLD, 12));
            diagramLabel.setTextFill(Color.web("#6c7086"));
            card.getChildren().addAll(diagramLabel, diagram);
        }
        return card;
    }

    private VBox infoCard(String title, String accentColour, String body) {
        return buildConceptCard(title, accentColour, body, null);
    }

    private Label sectionTitle(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        l.setTextFill(Color.web("#cdd6f4"));
        l.setPadding(new Insets(8, 0, 4, 0));
        return l;
    }

    private HBox legendChip(String colour, String label) {
        HBox chip = new HBox(6);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setPadding(new Insets(4, 10, 4, 10));
        chip.setStyle("-fx-background-color: #1e1e2e; -fx-background-radius: 20;");

        Circle dot = new Circle(6);
        dot.setFill(Color.web(colour));

        Label lbl = new Label(label);
        lbl.setFont(Font.font("Monospaced", 12));
        lbl.setTextFill(Color.web("#cdd6f4"));
        chip.getChildren().addAll(dot, lbl);
        return chip;
    }

    // Public API — called from MainController to update live state

    /** Call this after every execution step to refresh the live explanation. */
    public void update(EventStructure es, ExecutionState state, Program prog, String lastAction) {
        this.eventStructure = es;
        this.executionState = state;
        this.program = prog;

        if (lastAction != null) {
            lastActionLabel.setText(lastAction);
        }

        rebuildNarrative();
    }

    private void rebuildNarrative() {
        executionNarrativeBox.getChildren().clear();

        if (eventStructure == null || eventStructure.getEvents().isEmpty()) {
            Label empty = new Label("No events yet — load a program and start stepping.");
            empty.setTextFill(Color.web("#6c7086"));
            empty.setFont(Font.font("Arial", FontPosture.ITALIC, 13));
            executionNarrativeBox.getChildren().add(empty);
            return;
        }

        for (Event e : eventStructure.getEvents()) {
            executionNarrativeBox.getChildren().add(buildEventCard(e));
        }

        // Summary
        int readCount  = (int) eventStructure.getEvents().stream().filter(e -> e.getType() == EventType.READ).count();
        int writeCount = (int) eventStructure.getEvents().stream().filter(e -> e.getType() == EventType.WRITE || e.getType() == EventType.INIT).count();
        int rfCount    = eventStructure.getReadsFrom().size();

        Label summary = new Label(
                "📊  Summary: " + eventStructure.getEvents().size() + " events total  |  " +
                        readCount + " reads  |  " + writeCount + " writes  |  " +
                        rfCount + " rf edges"
        );
        summary.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        summary.setTextFill(Color.web("#6c7086"));
        summary.setPadding(new Insets(8, 0, 0, 0));
        executionNarrativeBox.getChildren().add(summary);
    }

    private HBox buildEventCard(Event e) {
        HBox card = new HBox(14);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(10, 14, 10, 14));

        boolean isInit  = e.getType() == EventType.INIT;
        boolean isRead  = e.getType() == EventType.READ;
        boolean isWrite = e.getType() == EventType.WRITE;

        String colour = isInit ? "#fab387" : isRead ? "#a6e3a1" : "#f38ba8";
        String typeEmoji = isInit ? "🟠" : isRead ? "🟢" : "🔴";

        card.setStyle(
                "-fx-background-color: #1e1e2e;" +
                        "-fx-background-radius: 8;" +
                        "-fx-border-color: " + colour + ";" +
                        "-fx-border-width: 0 0 0 3;" +
                        "-fx-border-radius: 3;"
        );

        Label idLabel = new Label("e" + e.getId());
        idLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 13));
        idLabel.setTextFill(Color.web(colour));
        idLabel.setMinWidth(32);

        VBox descBox = new VBox(3);
        Label typeLabel = new Label(typeEmoji + "  " + plainEnglishEvent(e));
        typeLabel.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        typeLabel.setTextFill(Color.web("#cdd6f4"));
        typeLabel.setWrapText(true);

        String detail = detailForEvent(e);
        Label detailLabel = new Label(detail);
        detailLabel.setFont(Font.font("Arial", 12));
        detailLabel.setTextFill(Color.web("#6c7086"));
        detailLabel.setWrapText(true);

        descBox.getChildren().addAll(typeLabel, detailLabel);
        card.getChildren().addAll(idLabel, descBox);
        HBox.setHgrow(descBox, Priority.ALWAYS);
        return card;
    }

    private String plainEnglishEvent(Event e) {
        switch (e.getType()) {
            case INIT:
                return "Initialise " + e.getVariable() + " = " + e.getValue();
            case READ:
                ReadEvent r = (ReadEvent) e;
                return "Thread " + e.getThreadId() + " reads " + e.getVariable()
                        + " and gets " + e.getValue()
                        + "  [" + e.getMemoryOrder().name().toLowerCase() + "]";
            case WRITE:
                return "Thread " + e.getThreadId() + " writes " + e.getVariable()
                        + " = " + e.getValue()
                        + "  [" + e.getMemoryOrder().name().toLowerCase() + "]";
            default:
                return e.toString();
        }
    }

    private String detailForEvent(Event e) {
        StringBuilder sb = new StringBuilder();

        // po predecessors
        List<Integer> poPreds = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : eventStructure.getProgramOrder().entrySet()) {
            if (entry.getValue().contains(e.getId())) poPreds.add(entry.getKey());
        }
        if (!poPreds.isEmpty()) {
            sb.append("po from: e").append(poPreds.get(0));
            sb.append("  (this event comes after e").append(poPreds.get(0)).append(" in program order)");
        }

        // rf source
        Integer rfFrom = eventStructure.getReadsFrom().get(e.getId());
        if (rfFrom != null) {
            if (sb.length() > 0) sb.append("   •   ");
            sb.append("reads-from: e").append(rfFrom);
            Event src = eventStructure.getEventById(rfFrom);
            if (src != null) sb.append("  (value ").append(src.getValue()).append(" came from ").append(src).append(")");
        }

        if (sb.length() == 0) {
            if (e.getType() == EventType.INIT) return "Initial value — all threads can read this before any writes.";
            return "No incoming edges yet.";
        }
        return sb.toString();
    }

    public void show() {
        stage.show();
        stage.setMaximized(true);
        stage.toFront();
        stage.requestFocus();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }
}