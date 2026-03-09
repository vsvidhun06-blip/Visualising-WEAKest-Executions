package com.weakest.ui;

import com.weakest.checker.ConsistencyChecker;
import com.weakest.model.*;

import java.util.*;


public class ExecutionEnumerator {

    public static final int MAX_EXECUTIONS = 500;

    // ── Result types ──────────────────────────────────────────────────

    public record EnumeratedExecution(
            Map<String, Integer> finalValues,
            SnapData             snap
    ) {
        /** e.g. "r1=0, r2=1" */
        public String outcomeLabel() {
            if (finalValues.isEmpty()) return "(no registers)";
            StringBuilder sb = new StringBuilder();
            finalValues.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        if (!sb.isEmpty()) sb.append(", ");
                        String k = e.getKey().startsWith("@")
                                ? e.getKey().substring(1) : e.getKey();
                        sb.append(k).append("=").append(e.getValue());
                    });
            return sb.toString();
        }
    }

    /** Lightweight snapshot sufficient to render a graph. */
    public record SnapData(
            List<EventRec>              events,
            Map<Integer, List<Integer>> po,
            Map<Integer, Integer>       rf,
            Map<String,  List<Integer>> co,
            Set<String>                 sw   // "writeId_readId"
    ) {}

    public record EventRec(
            int    id,
            int    threadId,
            String type,
            String variable,
            int    value,
            String memOrder,
            String localVar
    ) {}

    // ── Public API ────────────────────────────────────────────────────

    public List<EnumeratedExecution> enumerateWeakest(Program program) {
        List<EnumeratedExecution> out = new ArrayList<>();
        enumerate(program, false, out);
        return out;
    }

    public List<EnumeratedExecution> enumerateSC(Program program) {
        List<EnumeratedExecution> out = new ArrayList<>();
        enumerate(program, true, out);
        return out;
    }

    // ── Core DFS ─────────────────────────────────────────────────────

    private void enumerate(Program program, boolean scMode,
                           List<EnumeratedExecution> results) {
        ConsistencyChecker checker = new ConsistencyChecker();

        // Build init EventStructure
        int[] nextId = {0};
        EventStructure initEs = new EventStructure();
        for (Map.Entry<String, Integer> e : program.getInitValues().entrySet()) {
            WriteEvent w = new WriteEvent(0, e.getKey(), MemoryOrder.SC,
                    e.getValue(), String.valueOf(e.getValue()));
            Event.forceId(w, nextId[0]++);
            initEs.addEvent(w);
            initEs.addCoherenceOrder(e.getKey(), w);
        }

        DfsState root = new DfsState(
                initEs,
                new int[program.getThreadCount()],
                new HashMap<>(),
                new ArrayList<>(Collections.nCopies(program.getThreadCount(), null)),
                nextId[0]
        );

        Deque<DfsState> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty() && results.size() < MAX_EXECUTIONS) {
            DfsState state = stack.pop();

            if (allDone(state, program)) {
                results.add(toExecution(state));
                continue;
            }

            List<Integer> runnable = getRunnableThreads(state, program);
            // Reverse so that natural-order thread is explored first (stack pops last-pushed first)
            for (int i = runnable.size() - 1; i >= 0; i--) {
                int tidx = runnable.get(i);
                Instruction instr = getNextInstr(state, program, tidx);
                if (instr == null) continue;

                if (instr.getType() == Instruction.InstructionType.READ) {
                    List<Event> writes = scMode
                            ? getSCWrites(state, instr.getVariable())
                            : getWeakestWrites(state, checker, tidx, instr);
                    for (int j = writes.size() - 1; j >= 0; j--) {
                        DfsState next = applyRead(state, tidx, instr, writes.get(j));
                        if (next != null) stack.push(next);
                    }
                } else {
                    DfsState next = applyWrite(state, tidx, instr);
                    if (next != null) stack.push(next);
                }
            }
        }
    }

    // ── Valid write sets ──────────────────────────────────────────────

    private List<Event> getWeakestWrites(DfsState state, ConsistencyChecker checker,
                                         int tidx, Instruction instr) {
        // Use a temporary probe. forceId with nextId so the checker sees a new id.
        ReadEvent probe = new ReadEvent(tidx + 1, instr.getVariable(),
                instr.getMemoryOrder(), instr.getLocalVar());
        Event.forceId(probe, state.nextId);
        // getValidWritesFor does NOT add probe to es, so no cleanup needed
        return checker.getValidWritesFor(state.es, probe);
    }

    private List<Event> getSCWrites(DfsState state, String variable) {
        List<Integer> coList = state.es.getCoherenceOrder()
                .getOrDefault(variable, Collections.emptyList());
        if (coList.isEmpty()) return Collections.emptyList();
        Event last = state.es.getEventById(coList.get(coList.size() - 1));
        return last != null ? List.of(last) : Collections.emptyList();
    }

    // ── State transitions ─────────────────────────────────────────────

    private DfsState applyRead(DfsState state, int tidx,
                               Instruction instr, Event srcWrite) {
        EventStructure newEs        = cloneEs(state.es);
        int[]          newPCs       = Arrays.copyOf(state.threadPCs, state.threadPCs.length);
        Map<String, Integer> newVars = new HashMap<>(state.localVars);
        List<Event>    newLast      = new ArrayList<>(state.lastEventPerThread);
        int            newNextId    = state.nextId;

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

        return new DfsState(newEs, newPCs, newVars, newLast, newNextId);
    }

    private DfsState applyWrite(DfsState state, int tidx, Instruction instr) {
        EventStructure newEs        = cloneEs(state.es);
        int[]          newPCs       = Arrays.copyOf(state.threadPCs, state.threadPCs.length);
        Map<String, Integer> newVars = new HashMap<>(state.localVars);
        List<Event>    newLast      = new ArrayList<>(state.lastEventPerThread);
        int            newNextId    = state.nextId;

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

        return new DfsState(newEs, newPCs, newVars, newLast, newNextId);
    }

    // ── Misc helpers ─────────────────────────────────────────────────

    private boolean allDone(DfsState state, Program program) {
        for (int i = 0; i < program.getThreadCount(); i++) {
            ProgramThread t = program.getThreads().get(i);
            if (t != null && state.threadPCs[i] < t.size()) return false;
        }
        return true;
    }

    private List<Integer> getRunnableThreads(DfsState state, Program program) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < program.getThreadCount(); i++) {
            ProgramThread t = program.getThreads().get(i);
            if (t != null && state.threadPCs[i] < t.size()) out.add(i);
        }
        return out;
    }

    private Instruction getNextInstr(DfsState state, Program program, int tidx) {
        ProgramThread t = program.getThreads().get(tidx);
        if (t == null || state.threadPCs[tidx] >= t.size()) return null;
        return t.getInstruction(state.threadPCs[tidx]);
    }

    private int evalExpr(String expr, Map<String, Integer> vars) {
        expr = expr.trim();
        if (expr.matches("-?\\d+")) return Integer.parseInt(expr);
        if (expr.startsWith("@")) return vars.getOrDefault(expr, 0);
        for (int i = expr.length() - 1; i > 0; i--) {
            char c = expr.charAt(i);
            if (c == '+' || c == '-' || c == '*' || c == '/') {
                int l = evalExpr(expr.substring(0, i), vars);
                int r = evalExpr(expr.substring(i + 1), vars);
                return switch (c) {
                    case '+' -> l + r;
                    case '-' -> l - r;
                    case '*' -> l * r;
                    case '/' -> r != 0 ? l / r : 0;
                    default  -> 0;
                };
            }
        }
        return 0;
    }

    private EventStructure cloneEs(EventStructure src) {
        EventStructure dst = new EventStructure();
        for (Event e : src.getEvents()) {
            Event clone = (e instanceof ReadEvent re)
                    ? new ReadEvent(re.getThreadId(), re.getVariable(),
                    re.getMemoryOrder(), re.getLocalVar())
                    : new WriteEvent(e.getThreadId(), e.getVariable(),
                    e.getMemoryOrder(), e.getValue(),
                    String.valueOf(e.getValue()));
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

    private EnumeratedExecution toExecution(DfsState state) {
        List<EventRec> evRecs = new ArrayList<>();
        for (Event e : state.es.getEvents()) {
            evRecs.add(new EventRec(
                    e.getId(), e.getThreadId(), e.getType().name(),
                    e.getVariable(), e.getValue(), e.getMemoryOrder().name(),
                    (e instanceof ReadEvent re) ? re.getLocalVar() : null));
        }

        Map<Integer, List<Integer>> po = new HashMap<>();
        state.es.getProgramOrder().forEach((k, v) -> po.put(k, new ArrayList<>(v)));
        Map<Integer, Integer> rf = new HashMap<>(state.es.getReadsFrom());
        Map<String, List<Integer>> co = new HashMap<>();
        state.es.getCoherenceOrder().forEach((k, v) -> co.put(k, new ArrayList<>(v)));

        Set<String> sw = new HashSet<>();
        state.es.getReadsFrom().forEach((readId, writeId) -> {
            Event r = state.es.getEventById(readId);
            Event w = state.es.getEventById(writeId);
            if (r instanceof ReadEvent re && w != null) {
                boolean isSw = (w.getMemoryOrder() == MemoryOrder.RELEASE
                        || w.getMemoryOrder() == MemoryOrder.SC)
                        && (re.getMemoryOrder() == MemoryOrder.ACQUIRE
                        || re.getMemoryOrder() == MemoryOrder.SC);
                if (isSw) sw.add(writeId + "_" + readId);
            }
        });

        Map<String, Integer> finals = new HashMap<>();
        state.localVars.forEach((k, v) -> {
            if (k.startsWith("@")) finals.put(k, v);
        });

        return new EnumeratedExecution(finals, new SnapData(evRecs, po, rf, co, sw));
    }

    // ── DFS state ─────────────────────────────────────────────────────

    private record DfsState(
            EventStructure       es,
            int[]                threadPCs,
            Map<String, Integer> localVars,
            List<Event>          lastEventPerThread,
            int                  nextId
    ) {}
}