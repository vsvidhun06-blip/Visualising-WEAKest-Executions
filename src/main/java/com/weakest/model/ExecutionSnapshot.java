package com.weakest.model;

import java.util.*;

public class ExecutionSnapshot {

    // ExecutionState fields
    public final int[]              threadPCs;
    public final Map<String, Integer> localVars;
    public final List<Integer>      lastEventIdPerThread; // null → -1

    // EventStructure fields
    public final List<EventRecord>       events;
    public final Map<Integer, List<Integer>> programOrder; // from → list of to
    public final Map<Integer, Integer>   readsFrom;        // readId → writeId
    public final Map<String, List<Integer>> coherenceOrder; // var → ordered write ids

    // Graph layout: node id → [cssClass, x, y]
    public final List<NodeRecord> nodeRecords;
    public final List<EdgeRecord> edgeRecords;

    // -------------------------------------------------------------------------

    public ExecutionSnapshot(int[] threadPCs,
                             Map<String, Integer> localVars,
                             List<Integer> lastEventIdPerThread,
                             List<EventRecord> events,
                             Map<Integer, List<Integer>> programOrder,
                             Map<Integer, Integer> readsFrom,
                             Map<String, List<Integer>> coherenceOrder,
                             List<NodeRecord> nodeRecords,
                             List<EdgeRecord> edgeRecords) {
        this.threadPCs             = Arrays.copyOf(threadPCs, threadPCs.length);
        this.localVars             = Collections.unmodifiableMap(new HashMap<>(localVars));
        this.lastEventIdPerThread  = Collections.unmodifiableList(new ArrayList<>(lastEventIdPerThread));
        this.events                = Collections.unmodifiableList(new ArrayList<>(events));
        this.programOrder          = deepCopyIntListMap(programOrder);
        this.readsFrom             = Collections.unmodifiableMap(new HashMap<>(readsFrom));
        this.coherenceOrder        = deepCopyStringListMap(coherenceOrder);
        this.nodeRecords           = Collections.unmodifiableList(new ArrayList<>(nodeRecords));
        this.edgeRecords           = Collections.unmodifiableList(new ArrayList<>(edgeRecords));
    }

    private static Map<Integer, List<Integer>> deepCopyIntListMap(Map<Integer, List<Integer>> src) {
        Map<Integer, List<Integer>> copy = new HashMap<>();
        src.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<Integer>> deepCopyStringListMap(Map<String, List<Integer>> src) {
        Map<String, List<Integer>> copy = new HashMap<>();
        src.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        return Collections.unmodifiableMap(copy);
    }


    // Inner records — lightweight data holders


    public record EventRecord(
            int id,
            int threadId,
            String type,      // "INIT" | "READ" | "WRITE"
            String variable,
            int value,
            String memOrder,
            String localVar   // only for READ
    ) {}

    public record NodeRecord(
            String nodeId,
            String cssClass,
            double x,
            double y
    ) {}

    public record EdgeRecord(
            String edgeId,
            String fromNode,
            String toNode,
            String cssClass,
            String label
    ) {}
}