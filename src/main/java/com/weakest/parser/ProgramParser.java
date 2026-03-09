package com.weakest.parser;

import com.weakest.model.*;

public class ProgramParser {

    public Program parse(String input) throws ParseException {
        Program program = new Program();
        String[] lines = input.split("\n");
        int currentThreadId = -1;
        int instrPos = 0;
        boolean inInit = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;

            if (line.equals("init:")) {
                inInit = true;
                currentThreadId = -1;
                continue;
            }

            if (line.matches("Thread \\d+:")) {
                inInit = false;
                currentThreadId = Integer.parseInt(line.replace("Thread ", "").replace(":", "").trim());
                program.addThread(new ProgramThread(currentThreadId));
                instrPos = 0;
                continue;
            }

            if (inInit) {
                // X = 0
                if (line.matches("[A-Za-z]\\w*\\s*=\\s*-?\\d+")) {
                    String[] parts = line.split("=");
                    program.setInitValue(parts[0].trim(), Integer.parseInt(parts[1].trim()));
                }
                continue;
            }

            if (currentThreadId >= 0) {
                ProgramThread thread = getThread(program, currentThreadId);
                if (thread == null) throw new ParseException("Thread not found: " + currentThreadId);
                Instruction instr = parseInstruction(line, currentThreadId, instrPos++);
                thread.addInstruction(instr);
            }
        }
        return program;
    }

    private Instruction parseInstruction(String line, int threadId, int pos) throws ParseException {
        // READ: @r1 = read(X, rlx)
        if (line.contains("= read(")) {
            String localVar = line.substring(0, line.indexOf("=")).trim();
            String inner = line.substring(line.indexOf("read(") + 5, line.lastIndexOf(")")).trim();
            String[] parts = inner.split(",");
            if (parts.length != 2) throw new ParseException("Invalid read syntax: " + line);
            String variable = parts[0].trim();
            MemoryOrder order = parseOrder(parts[1].trim());
            return Instruction.readInstruction(threadId, pos, localVar, variable, order);
        }

        // WRITE: write(X, @r1 + 1, rel)
        if (line.startsWith("write(")) {
            String inner = line.substring(6, line.lastIndexOf(")")).trim();
            int first = inner.indexOf(",");
            int last = inner.lastIndexOf(",");
            if (first == last) throw new ParseException("Invalid write syntax: " + line);
            String variable = inner.substring(0, first).trim();
            String valueExpr = inner.substring(first + 1, last).trim();
            MemoryOrder order = parseOrder(inner.substring(last + 1).trim());
            return Instruction.writeInstruction(threadId, pos, variable, valueExpr, order);
        }

        throw new ParseException("Cannot parse: " + line);
    }

    private MemoryOrder parseOrder(String s) throws ParseException {
        switch (s.toLowerCase()) {
            case "rlx": case "relaxed": return MemoryOrder.RELAXED;
            case "acq": case "acquire": return MemoryOrder.ACQUIRE;
            case "rel": case "release": return MemoryOrder.RELEASE;
            case "sc": return MemoryOrder.SC;
            default: throw new ParseException("Unknown memory order: " + s);
        }
    }

    private ProgramThread getThread(Program program, int id) {
        for (ProgramThread t : program.getThreads())
            if (t.getThreadId() == id) return t;
        return null;
    }

    public static class ParseException extends Exception {
        public ParseException(String msg) { super(msg); }
    }
}