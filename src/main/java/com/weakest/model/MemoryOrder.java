package com.weakest.model;

public enum MemoryOrder {
    RELAXED,    // rlx - no synchronization
    ACQUIRE,    // acq - for reads only
    RELEASE,    // rel - for writes only
    SC          // sc  - sequentially consistent
}