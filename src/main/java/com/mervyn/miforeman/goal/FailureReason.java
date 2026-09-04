package com.mervyn.miforeman.goal;

/** Why a machine isn't running at full rate, distinct from the coarse {@link MachineStatus}
 *  color. Lets the UI show a different fix for a different root cause instead of one generic
 *  "not running" message. */
public enum FailureReason {
    /** No further detail beyond the {@link MachineStatus} itself. */
    NONE,
    /** RED: no candidate recipe matches current inputs, and the machine isn't on a recycling
     *  loop. Upstream production just isn't keeping up. Fix: increase upstream supply. */
    STARVED,
    /** RED (or a downstream YELLOW): the machine sits on a recycling loop that has no external
     *  primer and is losing material every lap. Fix: wire in a source. */
    DEAD_LOOP,
    /** ORANGE: a candidate recipe matches, but the machine has no active recipe because its own
     *  output is full. Fix: add a drawer/trash. */
    CLOG_LOCK
}
