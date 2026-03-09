package com.weakest.checker;

import com.weakest.model.*;
import java.util.*;

public class ConsistencyChecker {

    // Public API

    public boolean wouldCreateCycle(EventStructure es, ReadEvent read, Event write) {
        Map<Integer, Set<Integer>> graph = buildGraph(es);
        graph.computeIfAbsent(write.getId(), k -> new HashSet<>()).add(read.getId());
        return hasCycle(graph);
    }

    public boolean hasCycle(Map<Integer, Set<Integer>> graph) {
        Set<Integer> visited = new HashSet<>();
        Set<Integer> stack   = new HashSet<>();
        for (int node : graph.keySet())
            if (dfs(node, graph, visited, stack)) return true;
        return false;
    }

    public boolean isWeakestValid(EventStructure es) {
        if (hasCycle(buildGraph(es))) return false;
        return hasJustificationSequence(es);
    }

    public boolean isJustifiable(EventStructure es, ReadEvent read, Event write) {
        if (wouldCreateCycle(es, read, write)) return false;
        // Build a hypothetical ES with this rf added
        EventStructure hyp = hypothetical(es, read, write);
        return hasJustificationSequence(hyp);
    }

    public List<Event> getValidWritesFor(EventStructure es, ReadEvent read) {
        List<Event> valid = new ArrayList<>();
        for (Event e : es.getEvents()) {
            if (!e.getVariable().equals(read.getVariable())) continue;
            if (!(e instanceof WriteEvent || e.getType() == EventType.INIT)) continue;
            if (isValidReadFrom(es, read, e)) valid.add(e);
        }
        return valid;
    }

    public boolean isValidReadFrom(EventStructure es, ReadEvent read, Event write) {
        if (!read.getVariable().equals(write.getVariable())) return false;
        if (wouldCreateCycle(es, read, write)) return false;
        // Also check justifiability
        EventStructure hyp = hypothetical(es, read, write);
        return hasJustificationSequence(hyp);
    }

    public boolean hasJustificationSequence(EventStructure es) {
        // Collect all reads that have an rf assignment
        Map<Integer, Integer> rf = es.getReadsFrom(); // readId → writeId
        if (rf.isEmpty()) return true; // no reads to justify

        Set<Integer> allReadIds = new HashSet<>(rf.keySet());
        Set<Integer> justified  = new HashSet<>();

        boolean progress = true;
        while (progress) {
            progress = false;
            for (int readId : allReadIds) {
                if (justified.contains(readId)) continue;
                Event readEvt = es.getEventById(readId);
                if (!(readEvt instanceof ReadEvent read)) continue;
                int writeId = rf.get(readId);
                Event write = es.getEventById(writeId);
                if (write == null) continue;

                if (isJustifiableGiven(es, read, write, justified)) {
                    justified.add(readId);
                    progress = true;
                }
            }
        }

        return justified.containsAll(allReadIds);
    }

    private boolean isJustifiableGiven(EventStructure es, ReadEvent read,
                                       Event write, Set<Integer> justified) {
        // Build partial graph: writes + justified reads only
        Map<Integer, Set<Integer>> partial = buildPartialGraph(es, justified);

        // All writes (and init) to the same variable
        String var = read.getVariable();
        List<Event> writesToVar = new ArrayList<>();
        for (Event e : es.getEvents()) {
            if (e.getVariable().equals(var)
                    && (e instanceof WriteEvent || e.getType() == EventType.INIT)
                    && e.getId() != write.getId()) {
                writesToVar.add(e);
            }
        }

        // Reachability from write in partial graph
        Set<Integer> reachableFromWrite = reachable(write.getId(), partial);

        // po-predecessors of read (events that reach read via po)
        // i.e. events from which read is reachable via po edges only
        Map<Integer, Set<Integer>> poOnly = buildPoGraph(es);
        Set<Integer> poReachToRead = reverseReachable(read.getId(), poOnly);

        // Check if any interposing write exists
        for (Event wp : writesToVar) {
            if (reachableFromWrite.contains(wp.getId())
                    && poReachToRead.contains(wp.getId())) {
                // w' interposes between w and r — not justifiable now
                return false;
            }
        }
        return true;
    }

    // Graph construction helpers

    public Map<Integer, Set<Integer>> buildGraph(EventStructure es) {
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        // po edges
        for (Map.Entry<Integer, List<Integer>> e : es.getProgramOrder().entrySet())
            graph.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
        // rf edges
        for (Map.Entry<Integer, Integer> e : es.getReadsFrom().entrySet())
            graph.computeIfAbsent(e.getValue(), k -> new HashSet<>()).add(e.getKey());
        return graph;
    }

    private Map<Integer, Set<Integer>> buildPartialGraph(EventStructure es,
                                                         Set<Integer> justified) {
        Map<Integer, Set<Integer>> graph = new HashMap<>();

        // po edges between writes (and init), and from/to justified reads
        for (Map.Entry<Integer, List<Integer>> entry : es.getProgramOrder().entrySet()) {
            int fromId = entry.getKey();
            Event from = es.getEventById(fromId);
            if (from == null) continue;
            boolean fromOk = isWriteOrInit(from) || justified.contains(fromId);
            if (!fromOk) continue;
            for (int toId : entry.getValue()) {
                Event to = es.getEventById(toId);
                if (to == null) continue;
                boolean toOk = isWriteOrInit(to) || justified.contains(toId);
                if (!toOk) continue;
                graph.computeIfAbsent(fromId, k -> new HashSet<>()).add(toId);
            }
        }

        // rf edges only for justified reads
        for (Map.Entry<Integer, Integer> entry : es.getReadsFrom().entrySet()) {
            int readId  = entry.getKey();
            int writeId = entry.getValue();
            if (justified.contains(readId)) {
                graph.computeIfAbsent(writeId, k -> new HashSet<>()).add(readId);
            }
        }

        return graph;
    }

    private Map<Integer, Set<Integer>> buildPoGraph(EventStructure es) {
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : es.getProgramOrder().entrySet())
            graph.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
        return graph;
    }

    // ══════════════════════════════════════════════════════════════════
    // Reachability helpers
    // ══════════════════════════════════════════════════════════════════

    /** Forward reachability from a node (BFS). */

    private Set<Integer> reachable(int start, Map<Integer, Set<Integer>> graph) {
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (!visited.add(cur)) continue;
            queue.addAll(graph.getOrDefault(cur, Collections.emptySet()));
        }
        visited.remove(start); // exclude the start node itself
        return visited;
    }

    private Set<Integer> reverseReachable(int target, Map<Integer, Set<Integer>> graph) {
        // Build reverse graph
        Map<Integer, Set<Integer>> rev = new HashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : graph.entrySet()) {
            for (int to : entry.getValue()) {
                rev.computeIfAbsent(to, k -> new HashSet<>()).add(entry.getKey());
            }
        }
        return reachable(target, rev);
    }

    // Cycle path extraction (for UI highlighting)

    public List<Integer> findCyclePath(EventStructure es) {
        return findCyclePath(buildGraph(es));
    }

    public List<Integer> findCyclePath(EventStructure es, ReadEvent read, Event write) {
        Map<Integer, Set<Integer>> graph = buildGraph(es);
        graph.computeIfAbsent(write.getId(), k -> new HashSet<>()).add(read.getId());
        return findCyclePath(graph);
    }

    private List<Integer> findCyclePath(Map<Integer, Set<Integer>> graph) {
        Set<Integer> visited = new HashSet<>();
        LinkedList<Integer> stack = new LinkedList<>();
        for (int node : graph.keySet()) {
            List<Integer> cycle = dfsCycle(node, graph, visited, stack);
            if (!cycle.isEmpty()) return cycle;
        }
        return Collections.emptyList();
    }

    private List<Integer> dfsCycle(int node, Map<Integer, Set<Integer>> graph,
                                   Set<Integer> visited, LinkedList<Integer> stack) {
        if (stack.contains(node)) {
            List<Integer> cycle = new ArrayList<>();
            boolean inCycle = false;
            for (int n : stack) {
                if (n == node) inCycle = true;
                if (inCycle) cycle.add(n);
            }
            cycle.add(node);
            return cycle;
        }
        if (visited.contains(node)) return Collections.emptyList();
        visited.add(node);
        stack.addLast(node);
        for (int neighbor : graph.getOrDefault(node, new HashSet<>())) {
            List<Integer> cycle = dfsCycle(neighbor, graph, visited, stack);
            if (!cycle.isEmpty()) return cycle;
        }
        stack.removeLast();
        return Collections.emptyList();
    }

    // Utility

    private boolean isWriteOrInit(Event e) {
        return e instanceof WriteEvent || e.getType() == EventType.INIT;
    }

    private EventStructure hypothetical(EventStructure es,
                                        ReadEvent read, Event write) {
        EventStructure hyp = new EventStructure();
        // Copy all events
        for (Event e : es.getEvents()) hyp.addEvent(e);
        // Also add the probe read if it's not already in es
        if (es.getEventById(read.getId()) == null) hyp.addEvent(read);
        // Copy po
        es.getProgramOrder().forEach((fromId, toIds) -> {
            Event from = hyp.getEventById(fromId);
            toIds.forEach(toId -> {
                Event to = hyp.getEventById(toId);
                if (from != null && to != null) hyp.addProgramOrder(from, to);
            });
        });
        // Copy rf + add new edge
        es.getReadsFrom().forEach((readId, writeId) -> {
            Event r = hyp.getEventById(readId);
            Event w = hyp.getEventById(writeId);
            if (r instanceof ReadEvent re && w != null) hyp.addReadsFrom(re, w);
        });
        hyp.addReadsFrom(read, write);
        // Copy co
        es.getCoherenceOrder().forEach((var, ids) -> {
            for (int id : ids) {
                Event w = hyp.getEventById(id);
                if (w instanceof WriteEvent we) hyp.addCoherenceOrder(var, we);
            }
        });
        return hyp;
    }

    // ── DFS helpers

    private boolean dfs(int node, Map<Integer, Set<Integer>> graph,
                        Set<Integer> visited, Set<Integer> stack) {
        if (stack.contains(node))   return true;
        if (visited.contains(node)) return false;
        visited.add(node);
        stack.add(node);
        for (int neighbor : graph.getOrDefault(node, new HashSet<>()))
            if (dfs(neighbor, graph, visited, stack)) return true;
        stack.remove(node);
        return false;
    }
}