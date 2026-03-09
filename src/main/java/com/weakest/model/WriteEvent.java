package com.weakest.model;

public class WriteEvent extends Event {
    private String valueExpression;

    public WriteEvent(int threadId, String variable, MemoryOrder memoryOrder, int value, String valueExpression) {
        super(threadId, EventType.WRITE, variable, memoryOrder, value);
        this.valueExpression = valueExpression;
    }

    public String getValueExpression() { return valueExpression; }
}