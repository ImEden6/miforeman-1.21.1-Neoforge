package com.mervyn.miforeman.goal;

/** Ordered worst-to-best so {@link #ordinal()} can be used directly as a sort/priority key
 *  (see MonitoringScreen's status sort). */
public enum MachineStatus {
    RED,
    ORANGE,
    YELLOW,
    GREEN
}
