package com.weakest.ui;

import com.weakest.checker.ConsistencyChecker;
import com.weakest.model.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.*;
import javafx.util.Duration;

import java.util.*;
import java.util.stream.Collectors;

public class BranchExplorerWindow extends Stage {

    private static final int    MAX_DEPTH   = 200;  // guard against infinite loops
    private static final int    MAX_LEAVES  = 100;  // cap to avoid UI overload

    private final Program            program;
    private final String             litmusName;
    private final ConsistencyChecker checker = new ConsistencyChecker();

    // UI
    private ScrollPane treeScroll;
    private VBox       detailPanel;
    private Label      detailTitle;
    private TextArea   detailBody;
    private Label      statsLabel;

    // Tree root (computed on open)
    private BranchNode treeRoot;
    private int        totalLeaves;

    public BranchExplorerWindow(Program program, String litmusName) {
        this.program   = program;
        this.litmusName = litmusName;
        setTitle("⑂  Branch Explorer — " + litmusName.toUpperCase());
        setMaximized(true);
        buildUI();
    }

    // UI Construction

    private void buildUI() {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();

        // ── Top bar ───────────────────────────────────────────────────
        Label titleLbl = new Label("⑂  Branch Explorer");
        titleLbl.setFont(Font.font("Arial", FontWeight.BOLD, 20));
        titleLbl.setTextFill(Color.web("#ff6600"));

        Label subLbl = new Label("All possible execution branches under WEAKEST — click a leaf to see its outcome.");
        subLbl.setFont(Font.font("Arial", 13));
        subLbl.setTextFill(Color.web("#6c7086"));

        statsLabel = new Label("Computing...");
        statsLabel.setFont(Font.font("Monospaced", 12));
        statsLabel.setTextFill(Color.web("#cba6f7"));

        Button closeBtn = new Button("✕  Close");
        closeBtn.setStyle("-fx-background-color:#45475a;-fx-text-fill:#cdd6f4;-fx-font-weight:bold;-fx-padding:6 14;");
        closeBtn.setOnAction(e -> close());

        HBox topRight = new HBox(statsLabel, closeBtn);
        topRight.setAlignment(Pos.CENTER_RIGHT);
        topRight.setSpacing(16);
        HBox.setHgrow(topRight, Priority.ALWAYS);

        HBox topBar = new HBox(16, titleLbl, subLbl, topRight);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(14, 18, 14, 18));
        topBar.setStyle("-fx-background-color:#313244;");

        // ── Legend ────────────────────────────────────────────────────
        HBox legend = buildLegend();
        legend.setPadding(new Insets(8, 18, 8, 18));
        legend.setStyle("-fx-background-color:#181825;-fx-border-color:#313244;-fx-border-width:0 0 1 0;");

        // ── Tree panel ────────────────────────────────────────────────
        VBox treeContent = new VBox();
        treeContent.setStyle("-fx-background-color:#1e1e2e;");
        treeContent.setPadding(new Insets(20, 20, 20, 20));

        treeScroll = new ScrollPane(treeContent);
        treeScroll.setFitToWidth(false);
        treeScroll.setStyle("-fx-background-color:#1e1e2e;-fx-background:#1e1e2e;" +
                "-fx-border-color:transparent;");
        treeScroll.setPrefWidth(screen.getWidth() * 0.65);

        // ── Detail panel ──────────────────────────────────────────────
        detailTitle = new Label("Click a branch leaf to see details");
        detailTitle.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        detailTitle.setTextFill(Color.web("#ff6600"));
        detailTitle.setWrapText(true);

        detailBody = new TextArea("Select any leaf node (final outcome box) from the tree on the left.\n\n"
                + "Leaf nodes show the complete rf assignment for that branch.");
        detailBody.setEditable(false);
        detailBody.setWrapText(true);
        detailBody.setFont(Font.font("Monospaced", 13));
        detailBody.setStyle("-fx-control-inner-background:#181825;-fx-text-fill:#cdd6f4;" +
                "-fx-border-color:#45475a;-fx-border-radius:6;");
        VBox.setVgrow(detailBody, Priority.ALWAYS);

        detailPanel = new VBox(12, detailTitle, detailBody);
        detailPanel.setPadding(new Insets(18));
        detailPanel.setStyle("-fx-background-color:#181825;-fx-border-color:#313244;" +
                "-fx-border-width:0 0 0 1;");
        detailPanel.setPrefWidth(screen.getWidth() * 0.30);

        // ── Main split ────────────────────────────────────────────────
        HBox mainArea = new HBox(treeScroll, detailPanel);
        HBox.setHgrow(treeScroll, Priority.ALWAYS);
        VBox.setVgrow(mainArea, Priority.ALWAYS);

        VBox root = new VBox(topBar, legend, mainArea);
        root.setStyle("-fx-background-color:#1e1e2e;");

        Scene scene = new Scene(root, screen.getWidth(), screen.getHeight());
        setScene(scene);

        // Build tree async so UI appears fast
        Platform.runLater(() -> {
            buildTree();
            renderTree(treeContent);
        });
    }

    private HBox buildLegend() {
        HBox box = new HBox(20);
        box.setAlignment(Pos.CENTER_LEFT);
        String[][] items = {
                {"#ff6600", "⑂  branch point (decision)"},
                {"#a6e3a1", "✅  leaf (final outcome)"},
                {"#f38ba8", "🚫  leaf (blocked by WEAKEST)"},
                {"#89b4fa", "─  single option (no conflict)"},
        };
        for (String[] it : items) {
            Label l = new Label(it[1]);
            l.setFont(Font.font("Arial", 12));
            l.setTextFill(Color.web(it[0]));
            box.getChildren().add(l);
        }
        return box;
    }

    // Tree building (DFS)

    private void buildTree() {
        // Build initial ES with init events
        EventStructure initEs = new EventStructure();
        int[] nextId = {0};
        for (Map.Entry<String, Integer> e : program.getInitValues().entrySet()) {
            WriteEvent w = new WriteEvent(0, e.getKey(), MemoryOrder.SC,
                    e.getValue(), String.valueOf(e.getValue()));
            Event.forceId(w, nextId[0]++);
            initEs.addEvent(w);
            initEs.addCoherenceOrder(e.getKey(), w);
        }

        int counterSave = Event.peekCounter();

        DfsState initState = new DfsState(
                initEs,
                new int[program.getThreadCount()],
                new HashMap<>(),
                new ArrayList<>(Collections.nCopies(program.getThreadCount(), null)),
                nextId[0],
                new ArrayList<>()
        );

        treeRoot = new BranchNode("root", "Program Start", null, 0, initState);
        totalLeaves = 0;
        buildSubtree(treeRoot, initState, 0);

        // Restore counter so main UI is unaffected
        Event.resetCounterTo(counterSave);

        int leaves = countLeaves(treeRoot);
        totalLeaves = leaves;
        Platform.runLater(() ->
                statsLabel.setText("⑂  " + leaves + " possible execution(s) found")
        );
    }

    private void buildSubtree(BranchNode node, DfsState state, int depth) {
        if (depth > MAX_DEPTH || totalLeaves >= MAX_LEAVES) return;

        if (allDone(state)) {
            node.isLeaf = true;
            node.finalVars = new HashMap<>(state.localVars);
            node.rfSummary = buildRfSummary(state);
            totalLeaves++;
            return;
        }

        List<Integer> runnable = getRunnableThreads(state);
        if (runnable.isEmpty()) {
            node.isLeaf = true;
            node.finalVars = new HashMap<>(state.localVars);
            node.rfSummary = buildRfSummary(state);
            node.blocked = true;
            totalLeaves++;
            return;
        }

        // Pick first thread with a decision (read) first, else first write thread
        int tidx = runnable.stream()
                .filter(t -> getNextInstr(state, t) != null
                        && getNextInstr(state, t).getType() == Instruction.InstructionType.READ)
                .findFirst()
                .orElse(runnable.get(0));

        Instruction instr = getNextInstr(state, tidx);
        if (instr == null) return;

        if (instr.getType() == Instruction.InstructionType.READ) {
            // Decision point — probe valid writes
            ReadEvent probe = new ReadEvent(tidx + 1, instr.getVariable(),
                    instr.getMemoryOrder(), instr.getLocalVar());
            Event.forceId(probe, state.nextId);
            List<Event> valid = checker.getValidWritesFor(state.es, probe);

            node.isDecision = true;
            node.decisionVar = instr.getVariable();
            node.decisionThread = tidx + 1;
            node.decisionLocalVar = instr.getLocalVar();

            String[] branchLetters = {"A","B","C","D","E","F","G","H"};
            for (int i = 0; i < valid.size(); i++) {
                Event w = valid.get(i);
                String letter = i < branchLetters.length ? branchLetters[i] : "?";
                String src = w.getThreadId() == 0 ? "init(0)" : "T" + w.getThreadId() + "=" + w.getValue();
                String label = "⑂ " + letter + ":  " + instr.getLocalVar() + " ← " + src;

                DfsState next = applyRead(state, tidx, instr, w);
                if (next == null) continue;

                List<String> newPath = new ArrayList<>(state.path);
                newPath.add("T" + (tidx+1) + " reads " + instr.getVariable()
                        + " from " + (w.getThreadId()==0?"init":"T"+w.getThreadId())
                        + " (value " + w.getValue() + ")  [branch " + letter + "]");

                DfsState nextWithPath = new DfsState(next.es, next.threadPCs,
                        next.localVars, next.lastEventPerThread, next.nextId, newPath);

                BranchNode child = new BranchNode(letter, label, letter, depth + 1, nextWithPath);
                child.isCrossBranch = w.getThreadId() != 0 && w.getThreadId() != tidx + 1;
                child.branchLetter = letter;
                node.children.add(child);
                buildSubtree(child, nextWithPath, depth + 1);
            }

        } else {
            // Deterministic write — no branching
            DfsState next = applyWrite(state, tidx, instr);
            if (next == null) return;

            int val = next.localVars.getOrDefault(
                    instr.getValueExpr().startsWith("@") ? instr.getValueExpr() : null, 0);
            // Compute written value from state
            int writtenVal = evalExpr(instr.getValueExpr(), state.localVars);
            String label = "T" + (tidx+1) + ": write(" + instr.getVariable() + "=" + writtenVal + ")";

            List<String> newPath = new ArrayList<>(state.path);
            newPath.add(label);
            DfsState nextWithPath = new DfsState(next.es, next.threadPCs,
                    next.localVars, next.lastEventPerThread, next.nextId, newPath);

            BranchNode child = new BranchNode("w", label, null, depth + 1, nextWithPath);
            child.isDeterministic = true;
            node.children.add(child);
            buildSubtree(child, nextWithPath, depth + 1);
        }
    }

    // Tree rendering

    private void renderTree(VBox container) {
        container.getChildren().clear();
        if (treeRoot == null) {
            container.getChildren().add(styledLabel("No tree computed.", "#6c7086", 13, false));
            return;
        }
        renderNode(container, treeRoot, 0, true);
    }

    private void renderNode(VBox container, BranchNode node, int depth, boolean isLast) {
        if (node == null) return;

        String indent = "    ".repeat(depth);

        if (node.label.equals("Program Start")) {
            // Root node
            HBox box = rootBox();
            container.getChildren().add(box);
        } else if (node.isLeaf) {
            // Leaf — outcome box
            HBox box = leafBox(node, indent);
            container.getChildren().add(box);
        } else if (node.isDecision) {
            // Decision point header
            HBox box = decisionBox(node, indent);
            container.getChildren().add(box);
        } else if (node.isDeterministic) {
            // Single deterministic step
            HBox box = deterministicBox(node, indent);
            container.getChildren().add(box);
        }

        // Recurse into children
        for (int i = 0; i < node.children.size(); i++) {
            renderNode(container, node.children.get(i), depth + 1, i == node.children.size() - 1);
        }
    }

    private HBox rootBox() {
        Label lbl = new Label("🌳  Execution tree for: " + litmusName.toUpperCase());
        lbl.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        lbl.setTextFill(Color.web("#cdd6f4"));
        lbl.setPadding(new Insets(0, 0, 6, 0));
        HBox box = new HBox(lbl);
        box.setPadding(new Insets(4, 0, 8, 4));
        return box;
    }

    private HBox decisionBox(BranchNode node, String indent) {
        Label connector = new Label(indent + "┣━");
        connector.setFont(Font.font("Monospaced", 13));
        connector.setTextFill(Color.web("#45475a"));

        Label lbl = new Label("⑂  Decision: T" + node.decisionThread
                + " reads " + node.decisionVar + " → " + node.decisionLocalVar
                + "  (" + node.children.size() + " branch" + (node.children.size()==1?"":"es") + ")");
        lbl.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        lbl.setTextFill(Color.web("#ff6600"));

        // Conflict note if multiple branches
        HBox box = new HBox(6, connector, lbl);
        if (node.children.size() > 1) {
            Label conflict = new Label(" # conflict ");
            conflict.setFont(Font.font("Monospaced", 10));
            conflict.setTextFill(Color.web("#ff6600"));
            conflict.setStyle("-fx-background-color:rgba(255,102,0,0.12);" +
                    "-fx-background-radius:3;-fx-padding:1 5;");
            box.getChildren().add(conflict);
        }
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(3, 0, 1, 0));
        return box;
    }

    private HBox deterministicBox(BranchNode node, String indent) {
        Label connector = new Label(indent + "│   ");
        connector.setFont(Font.font("Monospaced", 12));
        connector.setTextFill(Color.web("#45475a"));

        Label lbl = new Label(node.label);
        lbl.setFont(Font.font("Arial", 12));
        lbl.setTextFill(Color.web("#6c7086"));

        HBox box = new HBox(4, connector, lbl);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(1, 0, 1, 0));
        return box;
    }

    private HBox leafBox(BranchNode node, String indent) {
        Label connector = new Label(indent + "└──");
        connector.setFont(Font.font("Monospaced", 12));
        connector.setTextFill(Color.web("#45475a"));

        String outcomeStr = formatOutcome(node.finalVars);
        boolean isCross = node.isCrossBranch;
        String colour = node.blocked ? "#f38ba8" : isCross ? "#f9e2af" : "#a6e3a1";
        String icon   = node.blocked ? "🚫" : isCross ? "⚡" : "✅";

        Label lbl = new Label(node.label + "  →  " + icon + " " + outcomeStr);
        lbl.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        lbl.setTextFill(Color.web(colour));
        lbl.setStyle("-fx-background-color:" + colour + "22;" +
                "-fx-background-radius:6;-fx-padding:4 10;-fx-cursor:hand;");

        // Click to show detail
        final BranchNode finalNode = node;
        lbl.setOnMouseClicked(e -> showDetail(finalNode));

        // Hover effect
        lbl.setOnMouseEntered(e ->
                lbl.setStyle("-fx-background-color:" + colour + "44;" +
                        "-fx-background-radius:6;-fx-padding:4 10;-fx-cursor:hand;")
        );
        lbl.setOnMouseExited(e ->
                lbl.setStyle("-fx-background-color:" + colour + "22;" +
                        "-fx-background-radius:6;-fx-padding:4 10;-fx-cursor:hand;")
        );

        HBox box = new HBox(4, connector, lbl);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(2, 0, 2, 0));
        return box;
    }

    private void showDetail(BranchNode node) {
        String colour = node.blocked ? "#f38ba8" : node.isCrossBranch ? "#f9e2af" : "#a6e3a1";
        String outcome = formatOutcome(node.finalVars);

        detailTitle.setText((node.blocked ? "🚫  Blocked branch" : "✅  Branch outcome: ") + outcome);
        detailTitle.setTextFill(Color.web(colour));

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════\n");
        sb.append("  FINAL OUTCOME\n");
        sb.append("═══════════════════════════════\n");
        if (node.finalVars == null || node.finalVars.isEmpty()) {
            sb.append("  (no registers)\n");
        } else {
            node.finalVars.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        String k = e.getKey().startsWith("@") ? e.getKey().substring(1) : e.getKey();
                        sb.append("  ").append(k).append(" = ").append(e.getValue()).append("\n");
                    });
        }
        sb.append("\n");

        if (!node.rfSummary.isEmpty()) {
            sb.append("═══════════════════════════════\n");
            sb.append("  rf ASSIGNMENTS (reads-from)\n");
            sb.append("═══════════════════════════════\n");
            for (String line : node.rfSummary)
                sb.append("  ").append(line).append("\n");
            sb.append("\n");
        }

        sb.append("═══════════════════════════════\n");
        sb.append("  EXECUTION PATH\n");
        sb.append("═══════════════════════════════\n");
        if (node.state != null && !node.state.path.isEmpty()) {
            for (int i = 0; i < node.state.path.size(); i++)
                sb.append("  ").append(i+1).append(". ").append(node.state.path.get(i)).append("\n");
        } else {
            sb.append("  (path not recorded)\n");
        }

        if (node.blocked) {
            sb.append("\n⚠️  This branch was blocked — no valid writes remained\n");
            sb.append("   for some read, making the execution incomplete under WEAKEST.\n");
        } else if (node.isCrossBranch) {
            sb.append("\n⚡  This is a WEAK outcome — the read crossed thread boundaries.\n");
            sb.append("   This outcome is impossible under Sequential Consistency.\n");
        }

        // Conflict information
        if (node.branchLetter != null) {
            sb.append("\n⑂  Conflict info:\n");
            sb.append("   This execution took branch '" + node.branchLetter + "'.\n");
            sb.append("   Events on this branch are in conflict (#) with\n");
            sb.append("   events on all sibling branches.\n");
            sb.append("   In event structure theory: two events e # e' cannot\n");
            sb.append("   both appear in the same valid execution.\n");
        }

        detailBody.setText(sb.toString());

        // Pulse the detail panel
        String bright = colour;
        String dim    = colour + "44";
        detailTitle.setStyle("-fx-background-color:" + dim + ";-fx-background-radius:4;-fx-padding:4;");
        new Timeline(
                new KeyFrame(Duration.millis(300), e -> detailTitle.setStyle("")),
                new KeyFrame(Duration.millis(600), e -> detailTitle.setStyle(
                        "-fx-background-color:" + dim + ";-fx-background-radius:4;-fx-padding:4;")),
                new KeyFrame(Duration.millis(900), e -> detailTitle.setStyle(""))
        ).play();
    }

    // DFS helpers (mirrored from ExecutionEnumerator but tree-aware)

    private boolean allDone(DfsState state) {
        for (int i = 0; i < program.getThreadCount(); i++) {
            ProgramThread t = program.getThreads().get(i);
            if (t != null && state.threadPCs[i] < t.size()) return false;
        }
        return true;
    }

    private List<Integer> getRunnableThreads(DfsState state) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < program.getThreadCount(); i++) {
            ProgramThread t = program.getThreads().get(i);
            if (t != null && state.threadPCs[i] < t.size()) out.add(i);
        }
        return out;
    }

    private Instruction getNextInstr(DfsState state, int tidx) {
        ProgramThread t = program.getThreads().get(tidx);
        if (t == null || state.threadPCs[tidx] >= t.size()) return null;
        return t.getInstruction(state.threadPCs[tidx]);
    }

    private DfsState applyRead(DfsState state, int tidx, Instruction instr, Event srcWrite) {
        EventStructure newEs  = cloneEs(state.es);
        int[]   newPCs        = Arrays.copyOf(state.threadPCs, state.threadPCs.length);
        Map<String,Integer> newVars = new HashMap<>(state.localVars);
        List<Event> newLast   = new ArrayList<>(state.lastEventPerThread);
        int newNextId         = state.nextId;

        Event clonedWrite = newEs.getEventById(srcWrite.getId());
        if (clonedWrite == null) return null;

        ReadEvent read = new ReadEvent(tidx + 1, instr.getVariable(),
                instr.getMemoryOrder(), instr.getLocalVar());
        Event.forceId(read, newNextId++);
        newEs.addEvent(read);
        newEs.addReadsFrom(read, clonedWrite);

        Event prev = newLast.get(tidx);
        if (prev != null) {
            Event cp = newEs.getEventById(prev.getId());
            if (cp != null) newEs.addProgramOrder(cp, read);
        }
        newLast.set(tidx, read);
        newVars.put(instr.getLocalVar(), clonedWrite.getValue());
        newPCs[tidx]++;

        return new DfsState(newEs, newPCs, newVars, newLast, newNextId, state.path);
    }

    private DfsState applyWrite(DfsState state, int tidx, Instruction instr) {
        EventStructure newEs  = cloneEs(state.es);
        int[]   newPCs        = Arrays.copyOf(state.threadPCs, state.threadPCs.length);
        Map<String,Integer> newVars = new HashMap<>(state.localVars);
        List<Event> newLast   = new ArrayList<>(state.lastEventPerThread);
        int newNextId         = state.nextId;

        int val = evalExpr(instr.getValueExpr(), newVars);
        WriteEvent write = new WriteEvent(tidx + 1, instr.getVariable(),
                instr.getMemoryOrder(), val, instr.getValueExpr());
        Event.forceId(write, newNextId++);
        newEs.addEvent(write);
        newEs.addCoherenceOrder(instr.getVariable(), write);

        Event prev = newLast.get(tidx);
        if (prev != null) {
            Event cp = newEs.getEventById(prev.getId());
            if (cp != null) newEs.addProgramOrder(cp, write);
        }
        newLast.set(tidx, write);
        newPCs[tidx]++;

        return new DfsState(newEs, newPCs, newVars, newLast, newNextId, state.path);
    }

    private int evalExpr(String expr, Map<String, Integer> vars) {
        expr = expr.trim();
        if (expr.matches("-?\\d+")) return Integer.parseInt(expr);
        if (expr.startsWith("@")) return vars.getOrDefault(expr, 0);
        for (int i = expr.length()-1; i > 0; i--) {
            char c = expr.charAt(i);
            if (c=='+'||c=='-'||c=='*'||c=='/') {
                int l = evalExpr(expr.substring(0,i), vars);
                int r = evalExpr(expr.substring(i+1), vars);
                return switch(c){case '+' -> l+r; case '-' -> l-r;
                    case '*' -> l*r; case '/' -> r!=0?l/r:0; default -> 0;};
            }
        }
        return 0;
    }

    private EventStructure cloneEs(EventStructure src) {
        EventStructure dst = new EventStructure();
        for (Event e : src.getEvents()) {
            Event clone = (e instanceof ReadEvent re)
                    ? new ReadEvent(re.getThreadId(), re.getVariable(), re.getMemoryOrder(), re.getLocalVar())
                    : new WriteEvent(e.getThreadId(), e.getVariable(), e.getMemoryOrder(),
                    e.getValue(), String.valueOf(e.getValue()));
            Event.forceId(clone, e.getId());
            dst.addEvent(clone);
        }
        src.getProgramOrder().forEach((from, tos) -> tos.forEach(to -> {
            Event f = dst.getEventById(from), t = dst.getEventById(to);
            if (f != null && t != null) dst.addProgramOrder(f, t);
        }));
        src.getReadsFrom().forEach((rid, wid) -> {
            Event r = dst.getEventById(rid), w = dst.getEventById(wid);
            if (r instanceof ReadEvent re && w != null) dst.addReadsFrom(re, w);
        });
        src.getCoherenceOrder().forEach((var, ids) -> {
            for (int id : ids) {
                Event w = dst.getEventById(id);
                if (w instanceof WriteEvent we) dst.addCoherenceOrder(var, we);
            }
        });
        return dst;
    }

    private List<String> buildRfSummary(DfsState state) {
        List<String> lines = new ArrayList<>();
        state.es.getReadsFrom().forEach((readId, writeId) -> {
            Event r = state.es.getEventById(readId);
            Event w = state.es.getEventById(writeId);
            if (r instanceof ReadEvent re && w != null) {
                String from = w.getThreadId() == 0 ? "init" : "T" + w.getThreadId();
                lines.add("rf( " + from + "(" + w.getVariable() + "=" + w.getValue() + ")"
                        + " → T" + r.getThreadId() + "(" + re.getLocalVar() + ") )");
            }
        });
        return lines;
    }

    private String formatOutcome(Map<String, Integer> vars) {
        if (vars == null || vars.isEmpty()) return "(no registers)";
        return vars.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    String k = e.getKey().startsWith("@") ? e.getKey().substring(1) : e.getKey();
                    return k + "=" + e.getValue();
                })
                .collect(Collectors.joining(", "));
    }

    private int countLeaves(BranchNode node) {
        if (node == null) return 0;
        if (node.isLeaf) return 1;
        int sum = 0;
        for (BranchNode c : node.children) sum += countLeaves(c);
        return sum;
    }

    // Utility

    private Label styledLabel(String text, String colour, int size, boolean bold) {
        Label l = new Label(text);
        l.setFont(Font.font("Arial", bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
        l.setTextFill(Color.web(colour));
        return l;
    }

    // Data structures

    private static class BranchNode {
        final String   id;
        final String   label;
        String         branchLetter;   // "A", "B", etc., or null
        final int      depth;
        final DfsState state;

        boolean isLeaf          = false;
        boolean isDecision      = false;
        boolean isDeterministic = false;
        boolean blocked         = false;
        boolean isCrossBranch   = false;

        // Decision info (set if isDecision)
        String decisionVar, decisionLocalVar;
        int    decisionThread;

        // Leaf info (set if isLeaf)
        Map<String, Integer> finalVars;
        List<String>         rfSummary = new ArrayList<>();

        List<BranchNode> children = new ArrayList<>();

        BranchNode(String id, String label, String branchLetter, int depth, DfsState state) {
            this.id           = id;
            this.label        = label;
            this.branchLetter = branchLetter;
            this.depth        = depth;
            this.state        = state;
        }
    }

    private record DfsState(
            EventStructure       es,
            int[]                threadPCs,
            Map<String,Integer>  localVars,
            List<Event>          lastEventPerThread,
            int                  nextId,
            List<String>         path
    ) {}
}