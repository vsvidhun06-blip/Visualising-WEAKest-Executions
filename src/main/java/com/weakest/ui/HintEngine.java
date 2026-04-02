package com.weakest.ui;

import com.weakest.checker.ConsistencyChecker;
import com.weakest.model.*;
import java.util.*;

public class HintEngine {

    // Enums / inner classes ─────────────────────────────────────────────────

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

    // Thread statuses ───────────────────────────────────────────────────────

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

    // RfHint ────────────────────────────────────────────────────────────────

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

    // Reveal Path ───────────────────────────────────────────────────────────

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
            case "CORR" -> revealCoRR(done, es);
            case "2+2W" -> reveal2p2W(done, es);
            case "WRC"  -> revealWRC(done, es);
            case "RMW"  -> revealRMW(done, es);
            case "ISA2" -> revealISA2(done, es);
            case "CORW"     -> revealCoRW(done, es);
            case "LB+FENCE" -> revealLBfence(done, es);
            case "SB+FENCE" -> revealSBfence(done, es);
            case "MP+RLX"   -> revealMPrelaxed(done, es);
            case "OOTA"     -> revealOOTA(done, es);
            case "3.SB"     -> reveal3SB(done, es);
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

    private RevealPath revealCoRR(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, rlx)   [co edge from init]",
                "2️⃣  Thread 2 → read(X)  ⬅ pick value 1  [sees the new write!]",
                "3️⃣  Thread 3 → read(X)  ⬅ pick value 0  ← WEAKEST will BLOCK this!",
                "🎯  r1=1, r2=0 — FORBIDDEN! Coherence violation blocked by co."
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 to write X=1 first.";
            case 1 -> "👉 Click 🟡 Thread 2 to read X.\n   Pick value 1 — see the new write!";
            case 2 -> "👉 Click 🟡 Thread 3 to read X.\n   Try picking value 0 — WEAKEST should block it!\n   Coherence requires T3 to agree with T2.";
            default -> "🎉 Done! CoRR shows coherence — reads of the same variable must be consistent.";
        };
        return new RevealPath("CoRR — Coherence Read-Read",
                "r1=1, r2=0 — FORBIDDEN — coherence violation", steps, next,
                "Coherence (co) means all threads agree on the order of writes to a variable.\n"
                        + "If T2 reads X=1, it means X=1 is 'visible'. T3 cannot then read X=0\n"
                        + "because that would imply T3 sees an earlier state after T2 saw a later one.\n"
                        + "WEAKEST enforces this via the co relation and the justification sequence.", done);
    }

    private RevealPath reveal2p2W(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, rlx)   [co: init→X1]",
                "2️⃣  Thread 1 → write(Y=1, rlx)   [co: init→Y1]",
                "3️⃣  Thread 2 → write(Y=2, rlx)   [co: Y1→Y2]",
                "4️⃣  Thread 2 → write(X=2, rlx)   [co: X1→X2]",
                "🎯  Watch the co edges form — they cannot cycle!"
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 to write X=1.";
            case 1 -> "👉 Click 🟢 Thread 1 to write Y=1.";
            case 2 -> "👉 Click 🟢 Thread 2 to write Y=2.";
            case 3 -> "👉 Click 🟢 Thread 2 to write X=2.";
            default -> "🎉 Done! Look at the co (purple dashed) edges — they form a consistent order.";
        };
        return new RevealPath("2+2W — Two-Plus-Two Writes",
                "Consistent co edges — no cycle", steps, next,
                "2+2W tests coherence (co) order directly.\n"
                        + "Thread 1 writes X=1 then Y=1. Thread 2 writes Y=2 then X=2.\n"
                        + "The co relation must be a total order per variable and must not cycle.\n"
                        + "This test has no read outcomes — focus on the purple co edges in the graph!\n"
                        + "A cycle in co would mean X1 < X2 < X1, which is impossible.", done);
    }

    private RevealPath revealWRC(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, rlx)",
                "2️⃣  Thread 2 → read(X)  ⬅ pick value 1  [sees T1's write]",
                "3️⃣  Thread 2 → write(Y=1, rlx)   [Y carries X's value forward]",
                "4️⃣  Thread 3 → read(Y)  ⬅ pick value 1  [sees T2's write]",
                "5️⃣  Thread 3 → read(X)  ⬅ pick value 0  ← WEAKEST BLOCKS!",
                "🎯  r2=1, r3=0 — FORBIDDEN — causality chain violated."
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 to write X=1.";
            case 1 -> "👉 Click 🟡 Thread 2 to read X.\n   Pick value 1 — T2 sees T1's write!";
            case 2 -> "👉 Click 🟢 Thread 2 to write Y=1.";
            case 3 -> "👉 Click 🟡 Thread 3 to read Y.\n   Pick value 1 — T3 sees T2's write!";
            case 4 -> "👉 Click 🟡 Thread 3 to read X.\n   Try picking value 0 — WEAKEST should block this!\n   T3 seeing Y=1 means it must see X=1 (causality chain).";
            default -> "🎉 Done! WRC shows causality — if T3 reads T2's write, it must see T1's write too.";
        };
        return new RevealPath("WRC — Write-Read Causality",
                "r1=1, r2=1, r3=0 — FORBIDDEN — causality violation", steps, next,
                "WRC tests whether causality propagates through rf chains.\n"
                        + "T1 writes X=1. T2 reads X=1 and writes Y=1 (forwarding the value).\n"
                        + "T3 reads Y=1 — it MUST also read X=1 because the value Y=1 was caused by X=1.\n"
                        + "If T3 reads Y=1 but X=0, that breaks the causal chain.\n"
                        + "WEAKEST detects this via the justification sequence — T3 cannot justify\n"
                        + "reading Y=1 without also being able to see X=1.", done);
    }

    private RevealPath revealRMW(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → read(X, acq)  ⬅ pick value 0  [reads initial]",
                "2️⃣  Thread 1 → write(X=1, rel)  [T1 increments X to 1]",
                "3️⃣  Thread 2 → read(X, acq)  ⬅ pick value 1  [sees T1's write!]",
                "4️⃣  Thread 2 → write(X=2, rel)  [T2 increments X to 2]",
                "🎯  r1=0, r2=1 — valid RMW: T1 got 0, T2 got 1. Final X=2."
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟡 Thread 1 to read X.\n   Pick value 0 (initial) — T1 starts the increment.";
            case 1 -> "👉 Click 🟢 Thread 1 to write X=1.";
            case 2 -> "👉 Click 🟡 Thread 2 to read X.\n   Pick value 1 — T2 sees T1's increment!";
            case 3 -> "👉 Click 🟢 Thread 2 to write X=2.";
            default -> "🎉 Done! r1=0, r2=1 — correct RMW ordering. Both increments visible!";
        };
        return new RevealPath("RMW — Read-Modify-Write",
                "r1=0, r2=1 — valid: T1 saw 0, T2 saw T1's write", steps, next,
                "RMW (Read-Modify-Write) models atomic increment operations like CAS.\n"
                        + "Without true hardware atomics, we use acq/rel to approximate the ordering.\n"
                        + "The key property: each thread should read the value written by the previous one.\n"
                        + "r1=0,r2=0 would be FORBIDDEN — it means T2 lost T1's update.\n"
                        + "This is the classic ABA problem in lock-free programming!", done);
    }

    private RevealPath revealISA2(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, rlx)",
                "2️⃣  Thread 1 → write(Y=1, rel)   [Y is the sync flag]",
                "3️⃣  Thread 2 → read(Y, acq)  ⬅ pick value 1  [acq/rel sync!]",
                "4️⃣  Thread 2 → write(Z=1, rlx)   [forwards Y's value to Z]",
                "5️⃣  Thread 3 → read(Z, rlx)  ⬅ pick value 1",
                "6️⃣  Thread 3 → read(X, rlx)  ⬅ pick value 0  ← WEAKEST BLOCKS!",
                "🎯  r2=1, r3=0 — FORBIDDEN — ISA2 causality violation."
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 to write X=1.";
            case 1 -> "👉 Click 🟢 Thread 1 to write Y=1 (rel).";
            case 2 -> "👉 Click 🟡 Thread 2 to read Y.\n   Pick value 1 — acq/rel sync edge will appear!";
            case 3 -> "👉 Click 🟢 Thread 2 to write Z=1.";
            case 4 -> "👉 Click 🟡 Thread 3 to read Z.\n   Pick value 1.";
            case 5 -> "👉 Click 🟡 Thread 3 to read X.\n   Try picking value 0 — WEAKEST should block!\n   T3 must see X=1 because of the Y→Z sync chain.";
            default -> "🎉 Done! ISA2 shows store forwarding across 3 threads. The sync chain must propagate X!";
        };
        return new RevealPath("ISA2 — Store Forwarding + Coherence",
                "r1=1, r2=1, r3=0 — FORBIDDEN — 3-thread causality violation", steps, next,
                "ISA2 is a 3-thread extension of WRC with a release/acquire sync in the middle.\n"
                        + "T1 writes X=1 and Y=1(rel). T2 reads Y=1(acq) — this creates a sw edge.\n"
                        + "T2 then writes Z=1. T3 reads Z=1 and should therefore see X=1.\n"
                        + "The sw edge from T1→T2 + the rf edge T2→T3 creates a happens-before chain.\n"
                        + "T3 seeing Z=1 but X=0 would violate this chain — WEAKEST blocks it.", done);
    }

    private RevealPath revealCoRW(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, rlx)   [co: init→X1]",
                "2️⃣  Thread 2 → read(X)  ⬅ pick value 1  [rf: T2←T1]",
                "3️⃣  Thread 2 → write(X=2, rlx)   [co must put W_T2 AFTER W_T1!]",
                "🎯  r1=1 — ALLOWED, and co(W_T1, W_T2) automatically holds."
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 to write X=1.";
            case 1 -> "👉 Click 🟡 Thread 2 to read X.\n   Pick value 1 — T2 sees T1's write!\n   Watch how the rf edge constrains the co order.";
            case 2 -> "👉 Click 🟢 Thread 2 to write X=2.\n   The co order must place W_T2 after W_T1.";
            default -> "🎉 Done! CoRW: reading a write constrains where your own write sits in co.";
        };
        return new RevealPath("CoRW — Coherence Read-Write",
                "r1=1 with co(W_T1, W_T2) — rf constrains co", steps, next,
                "Coherence Read-Write (CoRW): if T2 reads from T1's write on variable X,\n"
                        + "then T2's own write to X must come AFTER T1's write in the coherence order (co).\n"
                        + "This prevents: T2 reads X=1 from T1, but T2's write is before T1's in co.\n"
                        + "That would mean X goes: T2's write → T1's write → T2 reads T1's write.\n"
                        + "A cycle in the combined rf+co relation — WEAKEST forbids this.", done);
    }

    private RevealPath revealLBfence(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → read(X, sc)  ⬅ pick value 0  [initial — no cross-thread read!]",
                "2️⃣  Thread 2 → read(Y, sc)  ⬅ pick value 0  [initial]",
                "3️⃣  Thread 1 → write(Y=1, sc)",
                "4️⃣  Thread 2 → write(X=1, sc)",
                "🎯  r1=0, r2=0 — SC outcome. r1=1,r2=1 is now FORBIDDEN!"
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟡 Thread 1 to read X.\n   Pick value 0 — SC ops mean you CANNOT get r1=1,r2=1!";
            case 1 -> "👉 Click 🟡 Thread 2 to read Y.\n   Pick value 0 again.";
            case 2 -> "👉 Click 🟢 Thread 1 to write Y=1.";
            case 3 -> "👉 Click 🟢 Thread 2 to write X=1.";
            default -> "🎉 Done! Compare with LB — same structure, but SC ops forbid the r1=1,r2=1 outcome.";
        };
        return new RevealPath("LB+fence — Load Buffering with SC ops",
                "r1=0, r2=0 (SC). r1=1, r2=1 FORBIDDEN!", steps, next,
                "LB+fence uses sc (SC) memory orders instead of rlx.\n"
                        + "In LB (relaxed), r1=1,r2=1 was ALLOWED because reads could be reordered.\n"
                        + "With SC ops, reads cannot be moved ahead of writes in program order.\n"
                        + "The sc-sc pair creates synchronization that prevents the load buffering behaviour.\n"
                        + "This is the key contrast: memory ordering PREVENTS weak behaviours!", done);
    }

    private RevealPath revealSBfence(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, sc)",
                "2️⃣  Thread 2 → write(Y=1, sc)",
                "3️⃣  Thread 1 → read(Y, sc)  ⬅ MUST see Y=1 now!",
                "4️⃣  Thread 2 → read(X, sc)  ⬅ MUST see X=1 now!",
                "🎯  r1=1, r2=1 (SC). r1=0, r2=0 is now FORBIDDEN!"
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 to write X=1 (sc).";
            case 1 -> "👉 Click 🟢 Thread 2 to write Y=1 (sc).";
            case 2 -> "👉 Click 🟡 Thread 1 to read Y.\n   SC write already happened — T1 must see Y=1!";
            case 3 -> "👉 Click 🟡 Thread 2 to read X.\n   T2 must see X=1. r1=0,r2=0 impossible with SC ops!";
            default -> "🎉 Done! SB+fence vs SB: SC ops drain the store buffer, forbidding r1=0,r2=0.";
        };
        return new RevealPath("SB+fence — Store Buffering with SC ops",
                "r1=1, r2=1 (SC). r1=0, r2=0 FORBIDDEN!", steps, next,
                "SB+fence uses sc (SC) memory orders instead of rlx.\n"
                        + "In SB (relaxed), r1=0,r2=0 was ALLOWED because writes were buffered.\n"
                        + "With SC ops, every write is immediately visible before the next sc read.\n"
                        + "The store buffer is effectively drained by the SC fence.\n"
                        + "This directly demonstrates what x86 TSO fences (MFENCE) achieve!", done);
    }

    private RevealPath revealMPrelaxed(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, rlx)",
                "2️⃣  Thread 1 → write(Y=1, rlx)   [no rel — no sync!]",
                "3️⃣  Thread 2 → read(Y, rlx)  ⬅ pick value 1  [sees Y=1]",
                "4️⃣  Thread 2 → read(X, rlx)  ⬅ pick value 0  ← NOW ALLOWED!",
                "🎯  r1=1, r2=0 — WEAK outcome, ALLOWED without acq/rel."
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 to write X=1.";
            case 1 -> "👉 Click 🟢 Thread 1 to write Y=1.";
            case 2 -> "👉 Click 🟡 Thread 2 to read Y.\n   Pick value 1.";
            case 3 -> "👉 Click 🟡 Thread 2 to read X.\n   Pick value 0! No sw edge → no obligation to see X=1.\n   In MP (with acq/rel) this would be FORBIDDEN!";
            default -> "🎉 Done! r1=1,r2=0 — ALLOWED here but FORBIDDEN in MP with acq/rel.\n   This is WHY acq/rel memory orders exist!";
        };
        return new RevealPath("MP+rlx — Message Passing without sync",
                "r1=1, r2=0 — ALLOWED (no acq/rel = no sw edge)", steps, next,
                "MP+rlx removes the acq/rel from the original Message Passing test.\n"
                        + "Without release on write(Y) and acquire on read(Y), there is no sw edge.\n"
                        + "Without a sw edge, Thread 2 has no obligation to see X=1 when it sees Y=1.\n"
                        + "Result: r1=1, r2=0 is NOW ALLOWED — the weak outcome we wanted to prevent!\n"
                        + "This is the exact motivation for acquire/release memory orders in C11/C++11.", done);
    }

    private RevealPath revealOOTA(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → read(X, rlx)  ⬅ pick value 0  [ONLY valid choice]",
                "2️⃣  Thread 1 → write(Y=0, rlx)   [writes what it read]",
                "3️⃣  Thread 2 → read(Y, rlx)  ⬅ pick value 0  [ONLY valid choice]",
                "4️⃣  Thread 2 → write(X=0, rlx)   [writes what it read]",
                "🎯  r1=0, r2=0 — the ONLY outcome. r1=1,r2=1 would be OOTA!"
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟡 Thread 1 to read X.\n   Only value 0 is valid — no thin-air values exist!\n   WEAKEST's justification sequence blocks circular reasoning.";
            case 1 -> "👉 Click 🟢 Thread 1 to write Y=0 (it forwards what it read).";
            case 2 -> "👉 Click 🟡 Thread 2 to read Y.\n   Only value 0 is valid here too.";
            case 3 -> "👉 Click 🟢 Thread 2 to write X=0.";
            default -> "🎉 Done! r1=0, r2=0 — OOTA values were correctly forbidden.\n   This is the defining property of WEAKEST vs. simpler weak memory models!";
        };
        return new RevealPath("OOTA — Out-of-Thin-Air",
                "r1=0, r2=0 — ONLY outcome. r1=1,r2=1 FORBIDDEN!", steps, next,
                "OOTA (Out-of-Thin-Air) is the central problem your dissertation addresses!\n"
                        + "The program is identical to CYC/LB — reads forwarded into writes.\n"
                        + "The question: can r1=42, r2=42 appear 'out of thin air'?\n"
                        + "If r1=42: T1 must have read X=42. But who wrote X=42? T2.\n"
                        + "If r2=42: T2 must have read Y=42. But who wrote Y=42? T1.\n"
                        + "But T1 only writes @r1 — so T1 writes 42 because it READ 42... circular!\n"
                        + "WEAKEST forbids this via the justification sequence:\n"
                        + "every event must be justified by events that already exist.", done);
    }

    private RevealPath reveal3SB(int done, EventStructure es) {
        List<String> steps = List.of(
                "1️⃣  Thread 1 → write(X=1, rlx)   [T1 buffers its write]",
                "2️⃣  Thread 2 → write(Y=1, rlx)   [T2 buffers its write]",
                "3️⃣  Thread 3 → write(Z=1, rlx)   [T3 buffers its write]",
                "4️⃣  Thread 1 → read(Y)  ⬅ pick value 0  [T1 doesn't see T2's write yet!]",
                "5️⃣  Thread 2 → read(Z)  ⬅ pick value 0  [T2 doesn't see T3's write yet!]",
                "6️⃣  Thread 3 → read(X)  ⬅ pick value 0  [T3 doesn't see T1's write yet!]",
                "🎯  r1=0, r2=0, r3=0 — 3-way store buffering! All buffers full simultaneously."
        );
        String next = switch (done) {
            case 0 -> "👉 Click 🟢 Thread 1 to write X=1. Do ALL writes before any reads!";
            case 1 -> "👉 Click 🟢 Thread 2 to write Y=1.";
            case 2 -> "👉 Click 🟢 Thread 3 to write Z=1. All three writes buffered!";
            case 3 -> "👉 Click 🟡 Thread 1 to read Y.\n   Pick value 0 — T1's store buffer is full, it can't see Y=1 yet!";
            case 4 -> "👉 Click 🟡 Thread 2 to read Z.\n   Pick value 0 — same story for T2.";
            case 5 -> "👉 Click 🟡 Thread 3 to read X.\n   Pick value 0 — 3-way ring complete!";
            default -> "🎉 Done! r1=0,r2=0,r3=0 — 3-way store buffering.\n   Under SC this would need a 3-cycle in hb — impossible. Under WEAKEST, it's fine!";
        };
        return new RevealPath("3.SB — 3-Thread Store Buffering Ring",
                "r1=0, r2=0, r3=0 — ALL writes buffered simultaneously", steps, next,
                "3.SB generalises the classic 2-thread Store Buffering to a ring of 3 threads.\n"
                        + "Thread 1 writes X and reads Y. Thread 2 writes Y and reads Z. Thread 3 writes Z and reads X.\n"
                        + "Under SC, r1=0,r2=0,r3=0 is impossible:\n"
                        + "  • r1=0 means T1 read Y before T2's write: po(T1.read, T2.write)\n"
                        + "  • r2=0 means T2 read Z before T3's write: po(T2.read, T3.write)\n"
                        + "  • r3=0 means T3 read X before T1's write: po(T3.read, T1.write)\n"
                        + "  • Combined with po: T1.write → T1.read → T2.write → T2.read → T3.write → T3.read → T1.write\n"
                        + "  • A cycle in happens-before! Impossible under SC.\n"
                        + "Under WEAKEST (and TSO/x86), writes can be buffered — so all 3 reads see stale values.", done);
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

            case "CoRR" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2");
                if (r1 == null || r2 == null) yield "⚠️  Could not read final register values.";
                yield (r1 == 1 && r2 == 0)
                        ? "❌  FORBIDDEN: r1=1, r2=0 — coherence violation!\n"
                        + "   T2 saw the new X=1, but T3 did not — reads of the same variable\n"
                        + "   must observe writes in a consistent order (coherence).\n"
                        + "   WEAKEST enforces this via the co relation."
                        : (r1.equals(r2))
                        ? "✅  r1=" + r1 + ", r2=" + r2 + " — coherent outcome.\n"
                        + "   Both threads agree on what value X has — coherence preserved!\n"
                        + "   Reset and try r1=1,r2=0 to see WEAKEST block the violation."
                        : "📖  r1=" + r1 + ", r2=" + r2 + " — valid ordering.\n"
                        + "   ✅ ALLOWED — T3 simply ran before T2 saw the write.";
            }

            case "2+2W" -> {
                yield "✅  2+2W complete!\n"
                        + "   This test has no registers — it's about write ordering (co).\n"
                        + "   Two threads each write to both X and Y in opposite orders.\n"
                        + "   WEAKEST requires coherence (co) to be consistent — no cycle allowed.\n"
                        + "   Look at the co edges in the graph — they must form a total order per variable.";
            }

            case "WRC" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2"), r3 = locals.get("@r3");
                if (r1 == null || r2 == null || r3 == null) yield "⚠️  Could not read all register values.";
                yield (r1 == 1 && r2 == 1 && r3 == 0)
                        ? "❌  FORBIDDEN: r1=1, r2=1, r3=0 — causality violation!\n"
                        + "   T2 saw X=1 and propagated it (wrote Y=1). T3 saw Y=1 but not X=1.\n"
                        + "   The causal chain X→Y→T3 means T3 MUST see X=1 if it sees Y=1.\n"
                        + "   WEAKEST blocks this via the justification sequence."
                        : (r1 == 1 && r2 == 1 && r3 == 1)
                        ? "✅  r1=1, r2=1, r3=1 — full causality chain!\n"
                        + "   T3 correctly saw X=1 after reading Y=1. Causality preserved."
                        : "📖  r1=" + r1 + ", r2=" + r2 + ", r3=" + r3 + "\n"
                        + "   ✅ ALLOWED — an SC-compatible ordering.\n"
                        + "   Try: run T1 write, T2 read X then write Y, T3 read Y then X\n"
                        + "   to find the causality violation (r2=1, r3=0).";
            }

            case "RMW" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2");
                if (r1 == null || r2 == null) yield "⚠️  Could not read final register values.";
                if (r1 == 0 && r2 == 0)
                    yield "❌  FORBIDDEN: r1=0, r2=0 — lost update!\n"
                            + "   Both threads read the initial value 0 — one increment was lost.\n"
                            + "   Real RMW (CAS) atomics prevent this. Here the acq/rel helps\n"
                            + "   but without true atomics, ordering still allows this under WEAKEST.";
                else if (r1 == 1 && r2 == 1)
                    yield "❌  FORBIDDEN: r1=1, r2=1 — both read each other's write!\n"
                            + "   This is impossible — T1's write is 1 and T2's write is 2.\n"
                            + "   They cannot both read a value they haven't written yet.";
                else
                    yield "✅  r1=" + r1 + ", r2=" + r2 + " — valid RMW ordering.\n"
                            + "   One thread saw the initial value, the other saw the first write.\n"
                            + "   This mimics correct atomic increment behaviour!";
            }

            case "ISA2" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2"), r3 = locals.get("@r3");
                if (r1 == null || r2 == null || r3 == null) yield "⚠️  Could not read all register values.";
                yield (r1 == 1 && r2 == 1 && r3 == 0)
                        ? "❌  FORBIDDEN: r1=1, r2=1, r3=0 — store forwarding violation!\n"
                        + "   T2 saw Y=1 (acq) and forwarded Z=1. T3 saw Z=1 but X=0.\n"
                        + "   The acq/rel chain Y→Z means T3 must see X=1 if it sees Z=1.\n"
                        + "   This is the ISA2 pattern — a 3-thread causality violation."
                        : (r1 == 1 && r2 == 1 && r3 == 1)
                        ? "✅  r1=1, r2=1, r3=1 — store forwarding chain complete!\n"
                        + "   T3 correctly saw X=1 after the full Y→Z sync chain propagated."
                        : "📖  r1=" + r1 + ", r2=" + r2 + ", r3=" + r3 + "\n"
                        + "   ✅ ALLOWED — valid ordering.\n"
                        + "   To find the forbidden outcome: run T1 full, T2 full (read Y=1, write Z=1),\n"
                        + "   then T3 reads Z first (picks 1), then reads X (picks 0).";
            }

            case "CoRW" -> {
                Integer r1 = locals.get("@r1");
                if (r1 == null) yield "✅  CoRW complete!\n"
                        + "   This test demonstrates read-write coherence.\n"
                        + "   If T2 reads X=1 (T1's write), T2's own write must come AFTER T1's in co.\n"
                        + "   WEAKEST enforces: rf(T2←T1) implies co(W_T1, W_T2).";
                yield r1 == 1
                        ? "✅  r1=1 — T2 read T1's write.\n"
                        + "   Now T2's write to X must come AFTER T1's write in co (coherence order).\n"
                        + "   This is read-write coherence (CoRW): rf and co must be consistent."
                        : "📖  r1=0 — T2 read the initial value.\n"
                        + "   ✅ ALLOWED — T2's write will be first in co.\n"
                        + "   Reset and try: run T1 first, then T2 reads X=1 to see CoRW in action!";
            }

            case "LB+fence" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2");
                if (r1 == null || r2 == null) yield "⚠️  Could not read final register values.";
                yield (r1 == 1 && r2 == 1)
                        ? "❌  FORBIDDEN: r1=1, r2=1 — SC fences blocked load buffering!\n"
                        + "   Unlike LB (relaxed), SC memory orders prevent reads from being\n"
                        + "   reordered ahead of writes. The sc-sc pair creates synchronization.\n"
                        + "   Compare with LB: identical structure but r1=1,r2=1 was ALLOWED there!"
                        : "✅  r1=" + r1 + ", r2=" + r2 + " — SC-compatible outcome.\n"
                        + "   ✅ ALLOWED under both SC and WEAKEST.\n"
                        + "   Key insight: SC ops forbid the r1=1,r2=1 outcome that relaxed LB allows.\n"
                        + "   This shows how memory ordering PREVENTS weak behaviours!";
            }

            case "SB+fence" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2");
                if (r1 == null || r2 == null) yield "⚠️  Could not read final register values.";
                yield (r1 == 0 && r2 == 0)
                        ? "❌  FORBIDDEN: r1=0, r2=0 — SC fences drain the store buffer!\n"
                        + "   Unlike SB (relaxed), SC memory orders prevent write buffering.\n"
                        + "   Each thread's sc write is visible to all before the sc read proceeds.\n"
                        + "   Compare with SB: identical structure but r1=0,r2=0 was ALLOWED there!"
                        : "✅  r1=" + r1 + ", r2=" + r2 + " — SC-compatible outcome.\n"
                        + "   ✅ ALLOWED under both SC and WEAKEST.\n"
                        + "   Key insight: SC ops forbid the r1=0,r2=0 store-buffering outcome!";
            }

            case "MP+rlx" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2");
                if (r1 == null || r2 == null) yield "⚠️  Could not read final register values.";
                yield (r1 == 1 && r2 == 0)
                        ? "⚡  WEAK outcome! r1=1, r2=0 — relaxed MP allows this!\n"
                        + "   ✅ ALLOWED by WEAKEST — without acq/rel there is NO sw edge.\n"
                        + "   T2 saw Y=1 but was not forced to see X=1.\n"
                        + "   ❌ FORBIDDEN in the original MP with acq/rel.\n"
                        + "   This shows why acq/rel exists — to prevent exactly this outcome!"
                        : "📖  r1=" + r1 + ", r2=" + r2 + "\n"
                        + "   ✅ ALLOWED — valid ordering.\n"
                        + "   To find the weak outcome: run T1 full, then T2 reads Y=1, then reads X=0!\n"
                        + "   Compare with MP (acq/rel) where r1=1,r2=0 is FORBIDDEN.";
            }

            case "OOTA" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2");
                if (r1 == null || r2 == null) yield "⚠️  Could not read final register values.";
                yield (r1 == 0 && r2 == 0)
                        ? "✅  r1=0, r2=0 — the ONLY possible outcome under WEAKEST.\n"
                        + "   OOTA values (like r1=42, r2=42) are FORBIDDEN by the justification sequence.\n"
                        + "   WEAKEST was specifically designed to forbid out-of-thin-air values!\n"
                        + "   This is identical to CYC — the structure is the same, the OOTA problem is the same."
                        : "❌  UNEXPECTED: r1=" + r1 + ", r2=" + r2 + "\n"
                        + "   Any non-zero outcome requires values that don't exist yet.\n"
                        + "   WEAKEST should have blocked this — check the consistency checker!";
            }

            case "3.SB" -> {
                Integer r1 = locals.get("@r1"), r2 = locals.get("@r2"), r3 = locals.get("@r3");
                if (r1 == null || r2 == null || r3 == null) yield "⚠️  Could not read all 3 register values.";
                yield (r1 == 0 && r2 == 0 && r3 == 0)
                        ? "⚡  WEAK outcome! r1=0, r2=0, r3=0\n"
                        + "   ✅ ALLOWED by WEAKEST — 3-way store buffering!\n"
                        + "   All three threads had their writes buffered simultaneously.\n"
                        + "   Compare with 2-thread SB: same principle, extended to a 3-thread ring.\n"
                        + "   This outcome requires a cycle in hb — forbidden under SC but fine here!"
                        : (r1 == 1 && r2 == 1 && r3 == 1)
                        ? "✅  r1=1, r2=1, r3=1 — full SC outcome.\n"
                        + "   Each thread saw the previous thread's write.\n"
                        + "   Reset and do ALL writes first, then ALL reads, to find r1=0,r2=0,r3=0!"
                        : "📖  r1=" + r1 + ", r2=" + r2 + ", r3=" + r3 + "\n"
                        + "   ✅ ALLOWED — partial store buffering.\n"
                        + "   To find the weak outcome: execute all three writes first,\n"
                        + "   then all three reads picking the initial value each time!";
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