package com.mervyn.miforeman.goal;

/** Ordered worst-to-best so {@link #ordinal()} can be used directly as a sort/priority key
 *  (see MonitoringScreen's status sort). */
public enum MachineStatus {
    RED,
    ORANGE,
    YELLOW,
    GREEN;

    /** ARGB colour for this status, shared by every screen/widget that renders it (monitoring
     *  list, review list, graph canvas) so the four status colours live in exactly one place. */
    public int colour() {
        return switch (this) {
            case RED -> 0xFFCC3333;
            case ORANGE -> 0xFFE67700;
            case YELLOW -> 0xFF9A6C00;
            case GREEN -> 0xFF2E7D32;
        };
    }
}
