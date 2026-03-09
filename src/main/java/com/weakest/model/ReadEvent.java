package com.weakest.model;

public class ReadEvent extends Event {
    private String localVar;
    private Event justifiedBy;

    public ReadEvent(int threadId, String variable, MemoryOrder memoryOrder, String localVar) {
        super(threadId, EventType.READ, variable, memoryOrder, 0);
        this.localVar = localVar;
    }

    public String getLocalVar() { return localVar; }
    public Event getJustifiedBy() { return justifiedBy; }

    public void setJustifiedBy(Event write) {
        this.justifiedBy = write;
        this.setValue(write.getValue());
    }
}