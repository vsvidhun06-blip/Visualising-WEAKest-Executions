package com.weakest.model;

import java.util.ArrayList;
import java.util.List;

public class ProgramThread {
    private int threadId;
    private List<Instruction> instructions;

    public ProgramThread(int threadId) {
        this.threadId = threadId;
        this.instructions = new ArrayList<>();
    }

    public void addInstruction(Instruction instruction) { instructions.add(instruction); }
    public int getThreadId() { return threadId; }
    public List<Instruction> getInstructions() { return instructions; }
    public Instruction getInstruction(int index) { return instructions.get(index); }
    public int size() { return instructions.size(); }
}