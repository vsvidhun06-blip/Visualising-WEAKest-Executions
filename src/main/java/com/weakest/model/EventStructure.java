package com.weakest.model;

import java.util.*;

public class EventStructure {
    private List<Event> events;
    private Map<Integer, List<Integer>> programOrder;
    private Map<Integer, Integer> readsFrom;
    private Map<String, List<Integer>> coherenceOrder;

    public EventStructure() {
        events = new ArrayList<>();
        programOrder = new HashMap<>();
        readsFrom = new HashMap<>();
        coherenceOrder = new HashMap<>();
    }

    public void addEvent(Event event) {
        events.add(event);
        programOrder.put(event.getId(), new ArrayList<>());
    }

    public void addProgramOrder(Event from, Event to) {
        programOrder.computeIfAbsent(from.getId(), k -> new ArrayList<>()).add(to.getId());
    }

    public void addReadsFrom(ReadEvent read, Event write) {
        readsFrom.put(read.getId(), write.getId());
        read.setJustifiedBy(write);
    }

    public void addCoherenceOrder(String variable, WriteEvent write) {
        coherenceOrder.computeIfAbsent(variable, k -> new ArrayList<>()).add(write.getId());
    }

    public List<Event> getEvents() { return events; }
    public Map<Integer, List<Integer>> getProgramOrder() { return programOrder; }
    public Map<Integer, Integer> getReadsFrom() { return readsFrom; }
    public Map<String, List<Integer>> getCoherenceOrder() { return coherenceOrder; }

    public Event getEventById(int id) {
        return events.stream().filter(e -> e.getId() == id).findFirst().orElse(null);
    }

    public List<WriteEvent> getWritesTo(String variable) {
        List<WriteEvent> writes = new ArrayList<>();
        for (Event e : events) {
            if ((e instanceof WriteEvent || e.getType() == EventType.INIT) && e.getVariable().equals(variable))
                writes.add((WriteEvent) e);
        }
        return writes;
    }
}