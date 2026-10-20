package com.mervyn.miforeman.goal;

/** Ordered worst to best, so the ordinal works as a sort key. */
public enum MachineStatus {
    RED,
    ORANGE,
    YELLOW,
    GREEN;

    public int colour() {
        return switch (this) {
            case RED -> 0xFFCC3333;
            case ORANGE -> 0xFFE67700;
            case YELLOW -> 0xFF9A6C00;
            case GREEN -> 0xFF2E7D32;
        };
    }
}
