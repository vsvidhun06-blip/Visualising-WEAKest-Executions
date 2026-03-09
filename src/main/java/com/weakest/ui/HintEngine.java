package com.weakest.ui;

import com.weakest.checker.ConsistencyChecker;
import com.weakest.model.*;
import java.util.*;

public class HintEngine {

    // ── Enums / inner classes ─────────────────

    public enum ThreadStatus { CAN_EXECUTE, NEEDS_CHOICE, DONE }

    public static class ThreadInfo {
        public final int threadIndex;
        public final ThreadStatus status;
        public final String nextInstruction;
        public final String emoji;
        public ThreadInfo(int threadIndex, ThreadStatus status, String nextInstruction) {
            this.threadIndex     = threadIndex;
            this.status          = status;
            this.nextInstruction = nextInstruction;
            this.emoji = switch (status) {
                case CAN_EXECUTE  -> "🟢";
                case NEEDS_CHOICE -> "🟡";
                case DONE         -> "🔴";
            };
        }
    }

    public static class RfHint {
        public final String summary;
        public final List<String> choices;
        public final String outcomeWarning;
        public RfHint(String summary, List<String> choices, String outcomeWarning) {
            this.summary        = summary;
            this.choices        = choices;
            this.outcomeWarning = outcomeWarning;
        }
    }

    public static class RevealPath {
        public final String title;
        public final String targetOutcome;
        public final List<String> steps;
        public final String nextStep;
        public final String explanation;
        public final int stepsCompleted;
        public RevealPath(String title, String targetOutcome, List<String> steps,
                          String nextStep, String explanation, int stepsCompleted) {
            this.title          = title;
            this.targetOutcome  = targetOutcome;
            this.steps          = steps;
            this.nextStep       = nextStep;
            this.explanation    = explanation;
            this.stepsCompleted = stepsCompleted;
        }
    }

    // ── Thread statuses ───────────────────────────────────────────────────────

    public List<ThreadInfo> getThreadStatuses(Program program, ExecutionState state,
                                              EventStructure es, ConsistencyChecker checker) {
        List<ThreadInfo> result = new ArrayList<>();
        for (int i = 0; i < program.getThreadCount(); i++) {
            if (state.isThreadDone(i)) {
                result.add(new ThreadInfo(i, ThreadStatus.DONE, "(done)"));
                continue;
            }
            Instruction instr = state.getNextInstruction(i);
            if (instr == null) {
                result.add(new ThreadInfo(i, ThreadStatus.DONE, "(done)"));
                continue;
            }
            if (instr.getType() == Instruction.InstructionType.READ) {
                ReadEvent probe = new ReadEvent(i + 1, instr.getVariable(),
                        instr.getMemoryOrder(), instr.getLocalVar());
                int n = checker.getValidWritesFor(es, probe).size();
                result.add(new ThreadInfo(i,
                        n > 1 ? ThreadStatus.NEEDS_CHOICE : ThreadStatus.CAN_EXECUTE,
                        instr.toString()));
            } else {
                result.add(new ThreadInfo(i, ThreadStatus.CAN_EXECUTE, instr.toString()));
            }
        }
        return result;
    }

    // ── RfHint ────────────────────────────────────────────────────────────────

    public RfHint buildRfHint(ReadEvent read, List<Event> validWrites,
                              EventStructure es, ConsistencyChecker checker) {
        String var = read.getVariable();
        String summary = "Thread " + read.getThreadId() + " reads " + var
                + " — " + validWrites.size() + " valid write"
                + (validWrites.size() == 1 ? "" : "s") + " available:";
        List<String> choices = new ArrayList<>();
        for (Event w : validWrites) {
            String c = "  • value " + w.getValue();
            if (w.getThreadId() == 0) {
                c += "  ← initial value (SC-compatible)";
            } else {
                c += "  ← Thread " + w.getThreadId();
                if (w.getThreadId() != read.getThreadId()) c += "  ⚡ cross-thread!";
            }
            if ((w.getMemoryOrder() == MemoryOrder.RELEASE || w.getMemoryOrder() == MemoryOrder.SC)
                    && (read.getMemoryOrder() == MemoryOrder.ACQUIRE || read.getMemoryOrder() == MemoryOrder.SC))
                c += "  🔵 sw edge will appear";
            choices.add(c);
        }
        boolean hasCross = validWrites.stream()
                .anyMatch(w -> w.getThreadId() != 0 && w.getThreadId() != read.getThreadId());
        String warn = hasCross
                ? "⚡ Cross-thread write available! Choosing it shows weak memory impossible under SC."
                : validWrites.stream().allMatch(w -> w.getThreadId() == 0)
                ? "💡 Only initial value available — execute more threads first!"
                : "";
        return new RfHint(summary, choices, warn);
    }

    // ── Reveal Path ───────────────────────────────────────────────────────────

    /** Backward-compatible overload for callers that don't have state/es */
    public RevealPath getRevealPath(String litmusName) {
        return getRevealPath(litmusName, null, null, null);
    }

    public RevealPath getRevealPath(String litmusName, ExecutionState state,
                                    EventStructure es, ConsistencyChecker checker) {
        if (litmusName == null) litmusName = "custom";
        int done = (es != null) ? countEvents(es) : 0;
        return switch (litmusName.toUpperCase()) {
            case "SB"   -> revealSB(done, es);
            case "LB"   -> revealLB(done, es);
            case "MP"   -> revealMP(done, es);
            case "CYC"  -> revealCYC(done, es);
            case "IRIW" -> revealIRIW(done, es);
            default     -> revealCustom(done);
        };
    }

    private RevealPath revealSB(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, rlx)   [po+co edges added]",
                "2️⃣  Thread 2 → write(Y=1, rlx)   [po+co edges added]",
                "3️⃣  Thread 1 → read(Y)  ⬅ pick value 0  [reads INITIAL — store buffer!]",
                "4️⃣  Thread 2 → read(X)  ⬅ pick value 0  [reads INITIAL — store buffer!]",
                "🎯  r1=0, r2=0 — WEAK outcome! Both writes were buffered."
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 to write X=1 first.";
            case 1 -> "👉 Click 🟢 Thread 2 to write Y=1. Now both writes exist!";
            case 2 -> "👉 Click 🟡 Thread 1 to read Y.\n   When cards appear → pick value 0 (initial)!\n   Thread 1 doesn't see Thread 2's write yet — that's the store buffer.";
            case 3 -> "👉 Click 🟡 Thread 2 to read X.\n   Pick value 0 (initial)! Thread 2 also misses Thread 1's write.\n   Both store buffers are full — this is the weak outcome!";
            default -> "🎉 Done! You found r1=0,r2=0 — the SB weak outcome.\n   Reset and try reading BEFORE writing to get the SC outcome instead.";
        };
        return new RevealPath("Store Buffering — Weak Outcome",
                "r1=0, r2=0  (ALLOWED — demonstrates x86 store buffers)", steps, next,
                "Both threads read 0 even though both wrote 1.\n"
                        + "Each write sat in its store buffer, invisible to the other thread.\n"
                        + "This happens on REAL x86 hardware — it's not a bug, it's the architecture!\n"
                        + "Sequential Consistency (SC) would NEVER allow this.", done);
    }

    private RevealPath revealLB(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → read(X)  ⬅ pick value 0",
                "2️⃣  Thread 2 → read(Y)  ⬅ pick value 0",
                "3️⃣  Thread 1 → write(Y=@r1)   [writes whatever @r1 read]",
                "4️⃣  Thread 2 → write(X=@r2)   [writes whatever @r2 read]",
                "💡  r1=1,r2=1 is ALLOWED by WEAKEST but can't be built step-by-step.",
                "   Try CYC next — it looks identical but r1=1,r2=1 is FORBIDDEN there!"
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟡 Thread 1 to read X. Pick value 0.";
            case 1 -> "👉 Click 🟡 Thread 2 to read Y. Pick value 0.";
            case 2 -> "👉 Click 🟢 Thread 1 to write Y=0 (based on @r1=0).";
            case 3 -> "👉 Click 🟢 Thread 2 to write X=0. Execution complete!";
            default -> "🎉 LB done!\n   Now try CYC — identical structure, but r1=1,r2=1 is FORBIDDEN.\n   Can you see why LB is OK but CYC isn't?";
        };
        return new RevealPath("Load Buffering — Allowed vs Forbidden",
                "r1=0, r2=0  (buildable; r1=1,r2=1 ALLOWED but not constructible here)", steps, next,
                "LB and CYC look almost identical. The key difference:\n"
                        + "LB's rf edges form NO cycle → allowed.\n"
                        + "CYC's rf edges DO form a cycle → OOTA → forbidden.\n"
                        + "WEAKEST uses cycle detection to distinguish them automatically.", done);
    }

    private RevealPath revealMP(int done, EventStructure es) {
        boolean hasSw = hasSyncEdge(es);
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, rlx)   [data write]",
                "2️⃣  Thread 1 → write(Y=1, rel)    [flag write — RELEASE]",
                "3️⃣  Thread 2 → read(Y, acq)  ⬅ pick value 1!   [ACQUIRE — watch for 🔵 sw edge!]",
                "4️⃣  Thread 2 → read(X, rlx)  ⬅ only X=1 valid now   [sync guaranteed it!]",
                "🎯  r1=1, r2=1 — the sw edge did its job!"
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 → write(X=1). This is the DATA being sent.";
            case 1 -> "👉 Click 🟢 Thread 1 again → write(Y=1, rel).\n   The 'rel' means: everything before me is now visible to whoever acquires me.";
            case 2 -> "👉 Click 🟡 Thread 2 → read(Y, acq).\n   IMPORTANT: pick value 1!\n   Watch the graph — a teal 🔵 sw edge should appear. That's synchronization!";
            case 3 -> hasSw
                    ? "👉 Click 🟡 Thread 2 → read(X).\n   The sw edge forced X=1 to be visible — pick it!\n   This is why acquire/release guarantees safe communication."
                    : "👉 Click 🟡 Thread 2 → read(X).\n   Try picking X=1 to see the full sync effect.";
            default -> "🎉 MP done!\n   The teal sw edge in the graph means Thread 2 is guaranteed to see X=1.\n   This is exactly how mutexes and atomic flags work in C++!";
        };
        return new RevealPath("Message Passing — Release/Acquire Sync",
                "r1=1, r2=1  (guaranteed by acquire/release ordering)", steps, next,
                "The teal sw (synchronizes-with) edge is the core of acq/rel semantics.\n"
                        + "When Thread 2 sees Y=1 via acquire, it's guaranteed to see everything\n"
                        + "Thread 1 wrote BEFORE its release write.\n"
                        + "This is how C++ std::atomic, Java volatile, and mutexes work!", done);
    }

    private RevealPath revealCYC(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → read(X)  ⬅ only value 0 available  [no X=1 exists yet!]",
                "2️⃣  Thread 1 → write(Y=@r1=0, rlx)",
                "3️⃣  Thread 2 → read(Y)  ⬅ only value 0 valid  [WEAKEST blocks Y=1!]",
                "4️⃣  Thread 2 → write(X=@r2=0, rlx)",
                "🔒  r1=1,r2=1 is IMPOSSIBLE — WEAKEST detected the causality cycle!"
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟡 Thread 1 → read(X).\n   Notice: only value 0 is offered! There is no X=1 yet.\n   If you could read X=1, it would need to come 'out of thin air' — OOTA!";
            case 1 -> "👉 Click 🟢 Thread 1 → write(Y=0). Since @r1=0, Y gets 0.";
            case 2 -> "👉 Click 🟡 Thread 2 → read(Y).\n   Again only 0 is valid — WEAKEST checks: would Y=1 create a po+rf cycle? YES → BLOCKED!";
            case 3 -> "👉 Click 🟢 Thread 2 → write(X=0). Finish the only buildable execution.";
            default -> "🎉 CYC done! Only r1=0,r2=0 was possible.\n   Compare with LB — identical structure, but CYC creates a cycle.\n   WEAKEST caught it automatically using cycle detection!";
        };
        return new RevealPath("CYC — Out-of-Thin-Air Prevention",
                "r1=0, r2=0  (ONLY outcome — r1=1,r2=1 FORBIDDEN by WEAKEST)", steps, next,
                "The Out-of-Thin-Air (OOTA) problem:\n"
                        + "To get r1=1, Thread 1 needs to read X=1.\n"
                        + "But X=1 only exists after Thread 2 writes it.\n"
                        + "Thread 2 writes X=@r2, so @r2 must be 1.\n"
                        + "But @r2=1 needs Thread 2 to read Y=1 — which needs Thread 1 to write Y=1...\n"
                        + "→ Infinite circular dependency! WEAKEST blocks the first rf that would close the cycle.", done);
    }

    private RevealPath revealIRIW(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, rlx)",
                "2️⃣  Thread 2 → write(Y=1, rlx)",
                "3️⃣  Thread 3 → read(X)  ⬅ pick X=1  [T3 sees X first]",
                "4️⃣  Thread 3 → read(Y)  ⬅ pick Y=0  [T3 hasn't seen Y=1 yet!]",
                "5️⃣  Thread 4 → read(Y)  ⬅ pick Y=1  [T4 sees Y first]",
                "6️⃣  Thread 4 → read(X)  ⬅ pick X=0  [T4 hasn't seen X=1 yet!]",
                "🎯  T3 and T4 observed the writes in OPPOSITE orders — non-MCA!"
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 → write(X=1).";
            case 1 -> "👉 Click 🟢 Thread 2 → write(Y=1). Both values now exist!";
            case 2 -> "👉 Click 🟡 Thread 3 → read(X). Pick X=1 — Thread 3 sees X first.";
            case 3 -> "👉 Click 🟡 Thread 3 → read(Y). Pick Y=0 (initial)!\n   Thread 3 saw X=1 but not Y=1 yet. Interesting!";
            case 4 -> "👉 Click 🟡 Thread 4 → read(Y). Pick Y=1 — Thread 4 sees Y first.";
            case 5 -> "👉 Click 🟡 Thread 4 → read(X). Pick X=0 (initial)!\n   T4 saw Y=1 but not X=1. OPPOSITE order to T3 — that's non-MCA!";
            default -> "🎉 IRIW done!\n   T3 saw X then Y. T4 saw Y then X. They DISAGREED on write order!\n   Impossible on x86. Allowed on ARM/Power and in WEAKEST.";
        };
        return new RevealPath("IRIW — Non-Multi-Copy Atomicity",
                "T3: r1=1,r2=0  AND  T4: r3=1,r4=0  (opposite observation orders)", steps, next,
                "Multi-Copy Atomicity (MCA): when one core sees a write, ALL cores see it instantly.\n"
                        + "x86 is MCA — all cores agree on write order.\n"
                        + "ARM/Power is NOT MCA — different threads can see writes in different orders.\n"
                        + "IRIW demonstrates this: Thread 3 and Thread 4 observed the same two writes\n"
                        + "in completely opposite orders. This is real hardware behaviour!", done);
    }

    private RevealPath revealCustom(int done) {
        String next = done == 0
                ? "👉 Click any 🟢 thread to start. Writes are automatic; reads show choice cards."
                : "👉 Keep going! When a 🟡 button pulses, click it and pick the cross-thread value ⚡";
        return new RevealPath("Custom Program",
                "Find an execution where a read sees an unexpected cross-thread value",
                List.of(
                        "Try executing threads in different orders.",
                        "When 🟡 pulses → click it → pick the ⚡ cross-thread value!",
                        "Use ↩ Undo to go back and try the other rf choice.",
                        "Look for 🔵 teal sw edges — they show synchronization happened."
                ), next,
                "The most interesting executions are where a thread sees a value\n"
                        + "written by another thread without synchronization.\n"
                        + "That's weak memory in action!", done);
    }

    // ── Completion feedback ───────────────────────────────────────────────────

    public String buildCompletionFeedback(ExecutionState state, String litmusName) {
        Map<String, Integer> locals = state.getAllLocalVars();
        StringBuilder sb = new StringBuilder("🎯  Execution complete!\n\n");
        if (!locals.isEmpty()) {
            sb.append("📊  Final register values:\n");
            locals.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));
            sb.append("\n");
        }
        if (litmusName != null) sb.append(interpretOutcome(litmusName, locals));
        sb.append("\n\n💡  What to do next:\n");
        sb.append("  • ⟳ Reset and try a DIFFERENT thread ordering\n");
        sb.append("  • ↩ Undo to go back and pick the other rf choice\n");
        sb.append("  • Try another Litmus Test to see a new concept\n");
        sb.append("  • 📖 Open Learning Companion for full explanations");
        return sb.toString();
    }

    private String interpretOutcome(String name, Map<String, Integer> locals) {
        // Local vars are stored with @ prefix e.g. "@r1", "@r2"
        return switch (name.toUpperCase()) {

            case "SB" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2");
                if (r1 == null || r2 == null) yield "⚠️  Could not read final register values.";
                yield (r1 == 0 && r2 == 0)
                        ? "⚡  WEAK outcome! r1=0, r2=0\n"
                        + "   ✅ ALLOWED by WEAKEST — demonstrates store buffering.\n"
                        + "   ❌ FORBIDDEN under SC — both threads buffered their writes!\n"
                        + "   This is the classic x86 store buffer behaviour."
                        : "📖  SC-compatible: r1=" + r1 + ", r2=" + r2 + "\n"
                        + "   ✅ ALLOWED under both SC and WEAKEST.\n"
                        + "   Reset and do BOTH writes first, THEN read to find the weak r1=0,r2=0!";
            }

            case "LB" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2");
                if (r1 == null || r2 == null) yield "⚠️  Could not read final register values.";
                yield (r1 == 1 && r2 == 1)
                        ? "⚡  WEAK outcome! r1=1, r2=1\n"
                        + "   ✅ ALLOWED by WEAKEST — reads reordered ahead of writes.\n"
                        + "   ❌ FORBIDDEN under SC — no SC execution produces this!\n"
                        + "   Compare with CYC — same structure, but r1=1,r2=1 is FORBIDDEN there."
                        : "📖  SC-compatible: r1=" + r1 + ", r2=" + r2 + "\n"
                        + "   ✅ ALLOWED under both SC and WEAKEST.\n"
                        + "   Reset: do reads FIRST (before writes) to find the r1=1,r2=1 weak outcome!";
            }

            case "MP" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2");
                if (r1 == null || r2 == null) yield "⚠️  Could not read final register values.";
                if (r1 == 1 && r2 == 1)
                    yield "✅  r1=1, r2=1 — Message Passing worked!\n"
                            + "   The teal sw edge guaranteed r2=1 when r1=1.\n"
                            + "   The rel/acq pair published Thread 1's writes atomically.";
                else if (r1 == 1 && r2 == 0)
                    yield "❓  r1=1 but r2=0 — UNEXPECTED under WEAKEST!\n"
                            + "   ❌ FORBIDDEN: the sw edge means if T2 sees Y=1 (acq), it must see X=1.\n"
                            + "   Check that Thread 1 uses rel on write(Y) and Thread 2 uses acq on read(Y).";
                else
                    yield "📖  r1=0, r2=" + r2 + " — Thread 2 read the initial flag.\n"
                            + "   ✅ ALLOWED — Thread 2 just ran before Thread 1 wrote Y.\n"
                            + "   Reset: run ALL of Thread 1 first, then Thread 2 to see the synchronisation!";
            }

            case "CYC" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2");
                if (r1 == null || r2 == null)
                    yield "✅  CYC done! r1=0, r2=0 is the ONLY buildable outcome.\n"
                            + "   r1=1,r2=1 would require a causality cycle — WEAKEST blocked it!\n"
                            + "   Compare this with LB to understand the difference.";
                yield (r1 == 0 && r2 == 0)
                        ? "✅  r1=0, r2=0 — ONLY possible outcome under WEAKEST.\n"
                        + "   ❌ r1=1,r2=1 is FORBIDDEN — it creates a causality cycle (OOTA).\n"
                        + "   WEAKEST detected and blocked the out-of-thin-air value!\n"
                        + "   Compare with LB: identical structure, but LB ALLOWS r1=1,r2=1."
                        : "❌  UNEXPECTED: r1=" + r1 + ", r2=" + r2 + "\n"
                        + "   This should be FORBIDDEN by WEAKEST — check the consistency checker!";
            }

            case "IRIW" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2"),
                        r3 = locals.get("@r3"), r4 = locals.get("@r4");
                if (r1 == null || r2 == null || r3 == null || r4 == null)
                    yield "⚠️  Could not read all 4 register values.";
                boolean nonMCA = (r1 == 1 && r2 == 0 && r3 == 1 && r4 == 0);
                yield nonMCA
                        ? "⚡  Non-MCA outcome! r1=1,r2=0 and r3=1,r4=0\n"
                        + "   ✅ ALLOWED by WEAKEST — threads observed writes in OPPOSITE orders.\n"
                        + "   ❌ FORBIDDEN under SC and x86 — stores must be seen in the same order!\n"
                        + "   This demonstrates non-multi-copy-atomicity under weak memory."
                        : "📖  SC-compatible: r1=" + r1 + ",r2=" + r2 + ",r3=" + r3 + ",r4=" + r4 + "\n"
                        + "   ✅ ALLOWED under both SC and WEAKEST.\n"
                        + "   Follow Reveal Path to find the non-MCA outcome (r1=1,r2=0,r3=1,r4=0)!";
            }

            default -> "✅  Done! Reset and try different thread orderings to find all outcomes.";
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int countEvents(EventStructure es) {
        if (es == null) return 0;
        try {
            return (int) es.getEvents().stream()
                    .filter(e -> e.getThreadId() != 0)
                    .count();
        } catch (Exception ex) { return 0; }
    }

    private boolean hasSyncEdge(EventStructure es) {
        try {
            return es.getReadsFrom().entrySet().stream().anyMatch(entry -> {
                Event read  = es.getEventById(entry.getKey());
                Event write = es.getEventById(entry.getValue());
                if (read == null || write == null) return false;
                return (write.getMemoryOrder() == MemoryOrder.RELEASE
                        || write.getMemoryOrder() == MemoryOrder.SC)
                        && (read.getMemoryOrder()  == MemoryOrder.ACQUIRE
                        || read.getMemoryOrder()  == MemoryOrder.SC);
            });
        } catch (Exception ex) { return false; }
    }
}