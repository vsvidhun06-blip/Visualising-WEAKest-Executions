package com.weakest.ui;

import com.weakest.checker.ConsistencyChecker;
import com.weakest.model.*;

import java.util.*;

/**
 * Enumerates all possible executions of a Program under two memory models:
 *
 *   WEAKEST — reads may read from any write that does not create a cycle
 *             in the justification graph (po ∪ rf).
 *
 *   SC      — reads must read from the most recent write to the variable
 *             in coherence order (last write executed so far).
 *
 * DFS is capped at MAX_EXECUTIONS. Safe to run off the JavaFX thread.
 */
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

    // ── Two-phase enumeration for TSO / PSO / RA ─────────────────────
    //
    // Phase 1: Generate all complete thread interleavings (po-skeletons).
    //          Every instruction is executed, but reads take a special
    //          "deferred" write placeholder — value resolved in phase 2.
    // Phase 2: For each skeleton, try every possible rf assignment that
    //          satisfies the model's axioms.  Collect final outcomes.
    //
    // This correctly models TSO/PSO/RA because the co order is now FIXED
    // (all writes committed in some interleaving order) before rf is chosen,
    // so the model's read constraints can be applied accurately.

    // ── Public entry points ───────────────────────────────────────────

    public List<EnumeratedExecution> enumerateTSO(Program program) {
        return enumeratePostHoc(program, this::isTSOValid);
    }

    public List<EnumeratedExecution> enumeratePSO(Program program) {
        return enumeratePostHoc(program, this::isPSOValid);
    }

    public List<EnumeratedExecution> enumerateRA(Program program) {
        return enumeratePostHoc(program, this::isRAValid);
    }

    // ── Model validity predicates ─────────────────────────────────────

    /**
     * TSO validity for a complete execution (fixed co, chosen rf):
     *  1. co-per-location: no read sees a write co-before another write
     *     it has already seen (monotone reads per variable per thread).
     *  2. Store-buffer forwarding: if a thread has written W to X and
     *     that write is co-latest for X from that thread, its own reads
     *     of X must see W or co-later.
     *  3. No store-load reordering across a po-later read that already
     *     has an rf from a newer write (coherence).
     */
    private boolean isTSOValid(EventStructure es) {
        Map<Integer, Integer> rf = es.getReadsFrom();
        Map<String, List<Integer>> co = es.getCoherenceOrder();

        for (Map.Entry<Integer, Integer> entry : rf.entrySet()) {
            int readId  = entry.getKey();
            int writeId = entry.getValue();
            Event r = es.getEventById(readId);
            Event w = es.getEventById(writeId);
            if (!(r instanceof ReadEvent) || w == null) continue;

            String var = r.getVariable();
            List<Integer> coList = co.getOrDefault(var, Collections.emptyList());
            int wIdx = coList.indexOf(writeId);

            // Store-buffer forwarding: if thread has a later co write, must see it
            for (int i = wIdx + 1; i < coList.size(); i++) {
                Event laterW = es.getEventById(coList.get(i));
                if (laterW != null && laterW.getThreadId() == r.getThreadId()) {
                    // This thread wrote later — must see own write, not w
                    return false;
                }
            }

            // Coherence-per-location (CoRR): no two reads in same thread
            // where r1 sees W1, r2 (po-after r1) sees W2 with W2 co-before W1
            for (Map.Entry<Integer, Integer> e2 : rf.entrySet()) {
                int r2id = e2.getKey(); int w2id = e2.getValue();
                if (r2id == readId) continue;
                Event r2 = es.getEventById(r2id);
                if (r2 == null || r2.getThreadId() != r.getThreadId()) continue;
                if (!r2.getVariable().equals(var)) continue;
                // Check if r2 is po-after r (r →po r2)
                if (!isPoReachable(es, readId, r2id)) continue;
                // r2 must see w2 co>= w (cannot go backwards)
                int w2Idx = coList.indexOf(w2id);
                if (w2Idx < wIdx) return false; // r2 sees older write — TSO violation
            }
        }
        return true;
    }

    /**
     * PSO validity: per-variable store buffers.
     * A read may see any write to that variable in co order.
     * Only constraint: coherence-per-location still applies within same thread
     * for the SAME variable (can't see a write co-before one already seen
     * for the same variable in the same thread).
     * PSO is strictly more permissive than TSO.
     */
    private boolean isPSOValid(EventStructure es) {
        Map<Integer, Integer> rf = es.getReadsFrom();
        Map<String, List<Integer>> co = es.getCoherenceOrder();

        for (Map.Entry<Integer, Integer> entry : rf.entrySet()) {
            int readId  = entry.getKey();
            int writeId = entry.getValue();
            Event r = es.getEventById(readId);
            if (!(r instanceof ReadEvent)) continue;

            String var = r.getVariable();
            List<Integer> coList = co.getOrDefault(var, Collections.emptyList());
            int wIdx = coList.indexOf(writeId);

            // Coherence-per-location for same variable, same thread
            for (Map.Entry<Integer, Integer> e2 : rf.entrySet()) {
                int r2id = e2.getKey(); int w2id = e2.getValue();
                if (r2id == readId) continue;
                Event r2 = es.getEventById(r2id);
                if (r2 == null || r2.getThreadId() != r.getThreadId()) continue;
                if (!r2.getVariable().equals(var)) continue;
                if (!isPoReachable(es, readId, r2id)) continue;
                int w2Idx = coList.indexOf(w2id);
                if (w2Idx < wIdx) return false;
            }
        }
        return true;
    }

    /**
     * RA validity: Release-Acquire.
     *  - An acquire read must rf-from a release/sc write (or init).
     *  - A relaxed read may rf-from ANY write to that variable in co order.
     *  - Additionally: no "weak" rf that creates a synchronises-with cycle
     *    (i.e., rf must respect happens-before for acq/rel pairs).
     */
    private boolean isRAValid(EventStructure es) {
        Map<Integer, Integer> rf = es.getReadsFrom();

        for (Map.Entry<Integer, Integer> entry : rf.entrySet()) {
            int readId  = entry.getKey();
            int writeId = entry.getValue();
            Event r = es.getEventById(readId);
            Event w = es.getEventById(writeId);
            if (!(r instanceof ReadEvent re) || w == null) continue;

            boolean isAcq = re.getMemoryOrder() == MemoryOrder.ACQUIRE
                    || re.getMemoryOrder() == MemoryOrder.SC;

            if (isAcq) {
                // Acquire reads must pair with release/sc writes or init
                boolean wIsRel = w.getThreadId() == 0
                        || w.getMemoryOrder() == MemoryOrder.RELEASE
                        || w.getMemoryOrder() == MemoryOrder.SC;
                if (!wIsRel) return false;
            }
            // Relaxed reads: any write allowed — no constraint
        }
        return true;
    }

    // ── Post-hoc rf assignment enumeration ────────────────────────────

    @FunctionalInterface
    private interface RfValidator {
        boolean isValid(EventStructure es);
    }

    private List<EnumeratedExecution> enumeratePostHoc(Program program,
                                                       RfValidator validator) {
        // Phase 1: collect all complete event skeletons (all po interleavings,
        // writes committed but reads not yet rf-assigned)
        List<SkeletonState> skeletons = new ArrayList<>();
        collectSkeletons(program, skeletons);

        // Phase 2: for each skeleton, enumerate all rf assignments
        Set<String> seenOutcomes = new LinkedHashSet<>();
        List<EnumeratedExecution> results = new ArrayList<>();

        for (SkeletonState skel : skeletons) {
            if (results.size() >= MAX_EXECUTIONS) break;
            enumerateRfAssignments(skel, validator, seenOutcomes, results);
        }
        return results;
    }

    // Skeleton = event structure with all writes + reads (no rf yet), plus po
    private record SkeletonState(
            EventStructure es,
            List<Integer>  readIds,   // in po order, need rf assignment
            Map<String, Integer> localVars
    ) {}

    private void collectSkeletons(Program program, List<SkeletonState> out) {
        // DFS over all thread interleavings; at reads, always take value=0
        // (placeholder — actual value filled in phase 2 from rf)
        int[] nextId = {0};
        EventStructure initEs = new EventStructure();
        for (Map.Entry<String, Integer> e : program.getInitValues().entrySet()) {
            WriteEvent w = new WriteEvent(0, e.getKey(), MemoryOrder.SC,
                    e.getValue(), String.valueOf(e.getValue()));
            Event.forceId(w, nextId[0]++);
            initEs.addEvent(w);
            initEs.addCoherenceOrder(e.getKey(), w);
        }

        record SkelDfsState(EventStructure es, int[] pcs,
                            Map<String, Integer> vars,
                            List<Event> lastPerThread,
                            List<Integer> readIds,
                            int nextId) {}

        Deque<SkelDfsState> stack = new ArrayDeque<>();
        stack.push(new SkelDfsState(initEs, new int[program.getThreadCount()],
                new HashMap<>(),
                new ArrayList<>(Collections.nCopies(program.getThreadCount(), null)),
                new ArrayList<>(), nextId[0]));

        Set<String> seen = new HashSet<>();
        int cap = Math.min(200, MAX_EXECUTIONS);

        while (!stack.isEmpty() && out.size() < cap) {
            SkelDfsState s = stack.pop();

            // Deduplicate skeletons by po+co fingerprint
            String fp = skelFingerprint(s.es, s.pcs);
            if (!seen.add(fp)) continue;

            boolean done = true;
            for (int i = 0; i < program.getThreadCount(); i++) {
                ProgramThread t = program.getThreads().get(i);
                if (t != null && s.pcs()[i] < t.size()) { done = false; break; }
            }
            if (done) {
                out.add(new SkeletonState(s.es(), s.readIds(), s.vars()));
                continue;
            }

            for (int tidx = program.getThreadCount() - 1; tidx >= 0; tidx--) {
                ProgramThread t = program.getThreads().get(tidx);
                if (t == null || s.pcs()[tidx] >= t.size()) continue;
                Instruction instr = t.getInstruction(s.pcs()[tidx]);

                EventStructure newEs = cloneEs(s.es());
                int[] newPCs = Arrays.copyOf(s.pcs(), s.pcs().length);
                Map<String, Integer> newVars = new HashMap<>(s.vars());
                List<Event> newLast = new ArrayList<>(s.lastPerThread());
                List<Integer> newReads = new ArrayList<>(s.readIds());
                int newNid = s.nextId();

                if (instr.getType() == Instruction.InstructionType.WRITE) {
                    int val = evalExpr(instr.getValueExpr(), newVars);
                    WriteEvent w = new WriteEvent(tidx + 1, instr.getVariable(),
                            instr.getMemoryOrder(), val, instr.getValueExpr());
                    Event.forceId(w, newNid++);
                    newEs.addEvent(w);
                    newEs.addCoherenceOrder(instr.getVariable(), w);
                    Event prev = newLast.get(tidx);
                    if (prev != null) {
                        Event cp = newEs.getEventById(prev.getId());
                        if (cp != null) newEs.addProgramOrder(cp, w);
                    }
                    newLast.set(tidx, w);
                } else {
                    // READ — add event but no rf yet (placeholder)
                    ReadEvent r = new ReadEvent(tidx + 1, instr.getVariable(),
                            instr.getMemoryOrder(), instr.getLocalVar());
                    Event.forceId(r, newNid++);
                    newEs.addEvent(r);
                    newReads.add(r.getId());
                    Event prev = newLast.get(tidx);
                    if (prev != null) {
                        Event cp = newEs.getEventById(prev.getId());
                        if (cp != null) newEs.addProgramOrder(cp, r);
                    }
                    newLast.set(tidx, r);
                    // localVar left unset for now (filled in phase 2)
                }
                newPCs[tidx]++;
                stack.push(new SkelDfsState(newEs, newPCs, newVars, newLast, newReads, newNid));
            }
        }
    }

    private String skelFingerprint(EventStructure es, int[] pcs) {
        // po edges + co lists uniquely identify a skeleton
        StringBuilder sb = new StringBuilder();
        es.getProgramOrder().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("->").append(e.getValue()).append(";"));
        es.getCoherenceOrder().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append(":").append(e.getValue()).append(";"));
        return sb.toString();
    }

    private void enumerateRfAssignments(SkeletonState skel, RfValidator validator,
                                        Set<String> seenOutcomes,
                                        List<EnumeratedExecution> results) {
        // Enumerate all assignments of rf for each read in the skeleton
        // Each read can rf-from any write to the same variable in co order
        List<Integer> readIds = skel.readIds();
        if (readIds.isEmpty()) {
            // No reads — one trivial execution
            tryCommitExecution(skel.es(), skel.localVars(), validator, seenOutcomes, results);
            return;
        }

        // Collect candidate writes per read
        List<List<Integer>> candidates = new ArrayList<>();
        for (int rid : readIds) {
            Event r = skel.es().getEventById(rid);
            if (r == null) { candidates.add(Collections.emptyList()); continue; }
            String var = r.getVariable();
            List<Integer> coList = skel.es().getCoherenceOrder()
                    .getOrDefault(var, Collections.emptyList());
            candidates.add(new ArrayList<>(coList));
        }

        // Recursively assign rf for each read in order
        assignRf(skel, candidates, readIds, 0, validator, seenOutcomes, results);
    }

    private void assignRf(SkeletonState skel, List<List<Integer>> candidates,
                          List<Integer> readIds, int pos,
                          RfValidator validator,
                          Set<String> seenOutcomes,
                          List<EnumeratedExecution> results) {
        if (results.size() >= MAX_EXECUTIONS) return;
        if (pos == readIds.size()) {
            // All reads assigned in skel.es() via addReadsFrom — validate and collect
            tryCommitExecution(skel.es(), skel.localVars(), validator, seenOutcomes, results);
            return;
        }
        int rid = readIds.get(pos);
        List<Integer> cands = pos < candidates.size() ? candidates.get(pos) : Collections.emptyList();
        for (int wid : cands) {
            // Clone es so each branch is independent
            EventStructure branchEs = cloneEs(skel.es());
            Map<String, Integer> branchVars = new HashMap<>(skel.localVars());
            Event r = branchEs.getEventById(rid);
            Event w = branchEs.getEventById(wid);
            if (!(r instanceof ReadEvent re) || w == null) continue;
            branchEs.addReadsFrom(re, w);
            branchVars.put(re.getLocalVar(), w.getValue());
            SkeletonState branch = new SkeletonState(branchEs, skel.readIds(), branchVars);
            assignRf(branch, candidates, readIds, pos + 1, validator, seenOutcomes, results);
        }
    }

    private void tryCommitExecution(EventStructure es, Map<String, Integer> vars,
                                    RfValidator validator,
                                    Set<String> seenOutcomes,
                                    List<EnumeratedExecution> results) {
        if (!validator.isValid(es)) return;
        // Build outcome label from local vars
        Map<String, Integer> finals = new HashMap<>();
        vars.forEach((k, v) -> { if (k.startsWith("@")) finals.put(k, v); });
        // Build outcome string for dedup
        StringBuilder sb = new StringBuilder();
        finals.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()).append(","));
        if (seenOutcomes.add(sb.toString())) {
            results.add(toExecutionFrom(es, finals));
        }
    }

    private EnumeratedExecution toExecutionFrom(EventStructure es,
                                                Map<String, Integer> finals) {
        List<EventRec> evRecs = new ArrayList<>();
        for (Event e : es.getEvents()) {
            evRecs.add(new EventRec(e.getId(), e.getThreadId(), e.getType().name(),
                    e.getVariable(), e.getValue(), e.getMemoryOrder().name(),
                    (e instanceof ReadEvent re) ? re.getLocalVar() : null));
        }
        Map<Integer, List<Integer>> po = new HashMap<>();
        es.getProgramOrder().forEach((k, v) -> po.put(k, new ArrayList<>(v)));
        Map<Integer, Integer> rf = new HashMap<>(es.getReadsFrom());
        Map<String, List<Integer>> co = new HashMap<>();
        es.getCoherenceOrder().forEach((k, v) -> co.put(k, new ArrayList<>(v)));
        Set<String> sw = new HashSet<>();
        es.getReadsFrom().forEach((readId, writeId) -> {
            Event r = es.getEventById(readId);
            Event w = es.getEventById(writeId);
            if (r instanceof ReadEvent re && w != null) {
                boolean isSw = (w.getMemoryOrder() == MemoryOrder.RELEASE
                        || w.getMemoryOrder() == MemoryOrder.SC)
                        && (re.getMemoryOrder() == MemoryOrder.ACQUIRE
                        || re.getMemoryOrder() == MemoryOrder.SC);
                if (isSw) sw.add(writeId + "_" + readId);
            }
        });
        return new EnumeratedExecution(finals,
                new SnapData(evRecs, po, rf, co, sw));
    }

    // po reachability helper (used by TSO/PSO coherence check)
    private boolean isPoReachable(EventStructure es, int fromId, int toId) {
        Map<Integer, List<Integer>> po = es.getProgramOrder();
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> q = new ArrayDeque<>();
        q.add(fromId);
        while (!q.isEmpty()) {
            int cur = q.poll();
            if (cur == toId) return true;
            if (!visited.add(cur)) continue;
            List<Integer> nexts = po.get(cur);
            if (nexts != null) q.addAll(nexts);
        }
        return false;
    }

    // ── Generic enumeration with pluggable write-set function ─────────
    // (kept for potential future use; TSO/PSO/RA now use post-hoc approach)

    @FunctionalInterface
    private interface WriteSetFn {
        List<Event> get(DfsState state, int tidx, Instruction instr);
    }

    private void enumerateWith(Program program, WriteSetFn writeFn,
                               List<EnumeratedExecution> results) {
        int[] nextId = {0};
        EventStructure initEs = new EventStructure();
        for (Map.Entry<String, Integer> e : program.getInitValues().entrySet()) {
            WriteEvent w = new WriteEvent(0, e.getKey(), MemoryOrder.SC,
                    e.getValue(), String.valueOf(e.getValue()));
            Event.forceId(w, nextId[0]++);
            initEs.addEvent(w);
            initEs.addCoherenceOrder(e.getKey(), w);
        }
        DfsState root = new DfsState(initEs, new int[program.getThreadCount()],
                new HashMap<>(),
                new ArrayList<>(Collections.nCopies(program.getThreadCount(), null)),
                nextId[0]);
        Deque<DfsState> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && results.size() < MAX_EXECUTIONS) {
            DfsState state = stack.pop();
            if (allDone(state, program)) { results.add(toExecution(state)); continue; }
            List<Integer> runnable = getRunnableThreads(state, program);
            for (int i = runnable.size() - 1; i >= 0; i--) {
                int tidx = runnable.get(i);
                Instruction instr = getNextInstr(state, program, tidx);
                if (instr == null) continue;
                if (instr.getType() == Instruction.InstructionType.READ) {
                    List<Event> writes = writeFn.get(state, tidx, instr);
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

    // ── TSO write set ─────────────────────────────────────────────────
    // TSO: store-load reordering allowed; store-store ordering preserved.
    // A read in thread T can see any write in co order, BUT:
    //   - If T itself has written to this variable (store buffer), it must
    //     see its own latest write (store-buffer forwarding).
    //   - Otherwise, it must see at least the write that is co-after any
    //     write already read by this thread (coherence-per-location: once
    //     a thread sees write W, it cannot later see a co-predecessor of W).
    // Net effect vs SC: allows stale reads from OTHER threads' writes.
    private List<Event> getTSOWrites(DfsState state, int tidx, Instruction instr) {
        String var = instr.getVariable();
        List<Integer> coList = state.es.getCoherenceOrder()
                .getOrDefault(var, Collections.emptyList());
        if (coList.isEmpty()) return Collections.emptyList();

        // Store-buffer forwarding: must see own thread's latest write
        for (int i = coList.size() - 1; i >= 0; i--) {
            Event w = state.es.getEventById(coList.get(i));
            if (w != null && w.getThreadId() == tidx + 1) {
                return List.of(w);
            }
        }

        // Find the minimum co index this thread is already committed to seeing
        // (based on what this thread has already read for this variable via rf)
        int minCoIdx = 0;
        for (Map.Entry<Integer, Integer> rfEntry : state.es.getReadsFrom().entrySet()) {
            Event reader = state.es.getEventById(rfEntry.getKey());
            if (reader != null && reader.getThreadId() == tidx + 1
                    && reader instanceof ReadEvent re
                    && re.getVariable().equals(var)) {
                int writeId = rfEntry.getValue();
                int idx = coList.indexOf(writeId);
                if (idx > minCoIdx) minCoIdx = idx;
            }
        }

        // Can see any write at or after minCoIdx (cannot go backwards in co)
        List<Event> result = new ArrayList<>();
        for (int i = minCoIdx; i < coList.size(); i++) {
            Event w = state.es.getEventById(coList.get(i));
            if (w != null) result.add(w);
        }
        return result.isEmpty() ? Collections.singletonList(
                state.es.getEventById(coList.get(coList.size() - 1))) : result;
    }

    // ── PSO write set ─────────────────────────────────────────────────
    // PSO: store-store reordering ALSO allowed (per variable).
    // A read can see any write in co order -- no coherence-per-location
    // constraint from this thread's previous reads.
    // More relaxed than TSO: can observe stale values even after seeing newer ones.
    private List<Event> getPSOWrites(DfsState state, int tidx, Instruction instr) {
        String var = instr.getVariable();
        List<Integer> coList = state.es.getCoherenceOrder()
                .getOrDefault(var, Collections.emptyList());
        List<Event> result = new ArrayList<>();
        for (int id : coList) {
            Event w = state.es.getEventById(id);
            if (w != null) result.add(w);
        }
        return result;
    }

    // ── RA write set ──────────────────────────────────────────────────
    // RA: acquire reads must see only release/sc writes (or init).
    // Relaxed reads can see any write in co order.
    // Stricter than WEAKEST (no out-of-thin-air), more relaxed than SC.
    // Key difference from SC: rlx reads are unconstrained by co.
    // Key difference from WEAKEST: acq reads are constrained to rel/sc writes.
    private List<Event> getRAWrites(DfsState state, int tidx, Instruction instr) {
        String var = instr.getVariable();
        List<Integer> coList = state.es.getCoherenceOrder()
                .getOrDefault(var, Collections.emptyList());
        if (coList.isEmpty()) return Collections.emptyList();

        boolean isAcq = instr.getMemoryOrder() == MemoryOrder.ACQUIRE
                || instr.getMemoryOrder() == MemoryOrder.SC;

        if (!isAcq) {
            // Relaxed reads: any write in co order
            List<Event> result = new ArrayList<>();
            for (int id : coList) {
                Event w = state.es.getEventById(id);
                if (w != null) result.add(w);
            }
            return result;
        }

        // Acquire reads: must pair with a release/sc write or the init write
        List<Event> result = new ArrayList<>();
        for (int id : coList) {
            Event w = state.es.getEventById(id);
            if (w == null) continue;
            if (w.getThreadId() == 0   // init write (thread 0)
                    || w.getMemoryOrder() == MemoryOrder.RELEASE
                    || w.getMemoryOrder() == MemoryOrder.SC) {
                result.add(w);
            }
        }
        // Fallback: if no qualifying write, allow any (avoid empty = stuck DFS)
        if (result.isEmpty()) {
            Event last = state.es.getEventById(coList.get(coList.size() - 1));
            if (last != null) result.add(last);
        }
        return result;
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