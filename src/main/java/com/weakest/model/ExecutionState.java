package com.weakest.model;

import java.util.*;

public class ExecutionState {
    private Program program;
    private EventStructure eventStructure;
    private int[] threadPCs;
    private Map<String, Integer> localVars;
    private List<Event> lastEventPerThread;

    public ExecutionState(Program program, EventStructure es) {
        this.program = program;
        this.eventStructure = es;
        this.threadPCs = new int[program.getThreadCount()];
        this.localVars = new HashMap<>();
        this.lastEventPerThread = new ArrayList<>(Collections.nCopies(program.getThreadCount(), null));
    }

    public boolean isThreadDone(int idx) {
        return threadPCs[idx] >= program.getThreads().get(idx).size();
    }

    public boolean allThreadsDone() {
        for (int i = 0; i < program.getThreadCount(); i++)
            if (!isThreadDone(i)) return false;
        return true;
    }

    public Instruction getNextInstruction(int idx) {
        if (isThreadDone(idx)) return null;
        return program.getThreads().get(idx).getInstruction(threadPCs[idx]);
    }

    public void advanceThread(int idx) { threadPCs[idx]++; }
    public void setLocalVar(String name, int value) { localVars.put(name, value); }
    public int getLocalVar(String name) { return localVars.getOrDefault(name, 0); }
    public Event getLastEventForThread(int idx) { return lastEventPerThread.get(idx); }
    public void setLastEventForThread(int idx, Event e) { lastEventPerThread.set(idx, e); }
    public EventStructure getEventStructure() { return eventStructure; }
    public Program getProgram() { return program; }
    public Map<String, Integer> getAllLocalVars() {
        return Collections.unmodifiableMap(localVars);
    }

    public int evaluateExpression(String expr) {
        expr = expr.trim();
        if (expr.matches("-?\\d+")) return Integer.parseInt(expr);
        if (expr.startsWith("@")) return getLocalVar(expr);
        for (String op : new String[]{"+", "-", "*", "/"}) {
            for (int i = expr.length() - 1; i > 0; i--) {
                if (String.valueOf(expr.charAt(i)).equals(op)) {
                    int left = evaluateExpression(expr.substring(0, i));
                    int right = evaluateExpression(expr.substring(i + 1));
                    switch (op) {
                        case "+": return left + right;
                        case "-": return left - right;
                        case "*": return left * right;
                        case "/": return right != 0 ? left / right : 0;
                    }
                }
            }
        }
        return 0;
    }
}