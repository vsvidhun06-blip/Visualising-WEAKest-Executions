package com.weakest.model;

public abstract class Event {

    // ── Static ID counter ─────────────────────────────────────────────
    private static int counter = 0;

    public static void resetCounter() {
        counter = 0;
    }

    public static void resetCounterTo(int value) {
        counter = value;
    }

    public static int peekCounter() {
        return counter;
    }

    public static void forceId(Event e, int id) {
        e.id = id;
    }

    // ── Instance fields

    private int id;                  // NOT final — undo needs to override it
    private final int threadId;
    private final String variable;
    private final MemoryOrder memoryOrder;
    private final EventType type;
    private int value;

    // ── Constructor
    protected Event(int threadId, EventType type, String variable,
                    MemoryOrder memoryOrder, int value) {
        this.id          = counter++;
        this.threadId    = threadId;
        this.variable    = variable;
        this.memoryOrder = memoryOrder;
        this.type        = type;
        this.value       = value;
    }

    // ── Getters
    public int         getId()          { return id; }
    public int         getThreadId()    { return threadId; }
    public String      getVariable()    { return variable; }
    public MemoryOrder getMemoryOrder() { return memoryOrder; }
    public EventType   getType()        { return type; }
    public int         getValue()       { return value; }

    public void setValue(int value)     { this.value = value; }

    // ── toString
    @Override
    public String toString() {
        String typePrefix = switch (type) {
            case INIT  -> "W[T0]";
            case READ  -> "R[T"  + threadId + "]";
            case WRITE -> "W[T"  + threadId + "]";
        };
        return typePrefix + "(" + variable + "=" + value + ","
                + memoryOrder.name().toLowerCase() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Event other)) return false;
        return id == other.id;
    }

    @Override
    public int hashCode() { return id; }
}