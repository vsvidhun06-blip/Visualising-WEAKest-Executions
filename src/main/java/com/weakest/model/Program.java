package com.weakest.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Program {
    private Map<String, Integer> initValues;
    private List<ProgramThread> threads;

    public Program() {
        initValues = new HashMap<>();
        threads = new ArrayList<>();
    }

    public void setInitValue(String variable, int value) { initValues.put(variable, value); }
    public int getInitValue(String variable) { return initValues.getOrDefault(variable, 0); }
    public void addThread(ProgramThread thread) { threads.add(thread); }
    public Map<String, Integer> getInitValues() { return initValues; }
    public List<ProgramThread> getThreads() { return threads; }
    public int getThreadCount() { return threads.size(); }
}