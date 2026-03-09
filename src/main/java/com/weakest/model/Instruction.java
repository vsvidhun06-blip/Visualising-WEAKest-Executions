package com.weakest.model;

public class Instruction {
    public enum InstructionType { READ, WRITE }

    private InstructionType type;
    private String localVar;
    private String variable;
    private MemoryOrder memoryOrder;
    private String valueExpr;
    private int threadId;
    private int position;

    public static Instruction readInstruction(int threadId, int pos, String localVar, String variable, MemoryOrder order) {
        Instruction i = new Instruction();
        i.type = InstructionType.READ;
        i.threadId = threadId;
        i.position = pos;
        i.localVar = localVar;
        i.variable = variable;
        i.memoryOrder = order;
        return i;
    }

    public static Instruction writeInstruction(int threadId, int pos, String variable, String valueExpr, MemoryOrder order) {
        Instruction i = new Instruction();
        i.type = InstructionType.WRITE;
        i.threadId = threadId;
        i.position = pos;
        i.variable = variable;
        i.valueExpr = valueExpr;
        i.memoryOrder = order;
        return i;
    }

    public InstructionType getType() { return type; }
    public String getLocalVar() { return localVar; }
    public String getVariable() { return variable; }
    public MemoryOrder getMemoryOrder() { return memoryOrder; }
    public String getValueExpr() { return valueExpr; }
    public int getThreadId() { return threadId; }
    public int getPosition() { return position; }

    @Override
    public String toString() {
        if (type == InstructionType.READ)
            return localVar + " = read(" + variable + ", " + memoryOrder.name().toLowerCase() + ")";
        else
            return "write(" + variable + ", " + valueExpr + ", " + memoryOrder.name().toLowerCase() + ")";
    }
}