package com.mervyn.miforeman.goal;

/** Why a machine isn't running at full rate, finer than MachineStatus. */
public enum FailureReason {
    NONE,
    /** RED: no candidate recipe matches current inputs and the machine isn't on a loop. Upstream supply is short. */
    STARVED,
    /** RED or a downstream YELLOW: the machine is on a loop with no outside source and loses material every lap. */
    DEAD_LOOP,
    /** ORANGE: a candidate recipe matches, but the machine's output is full. */
    CLOG_LOCK,
    /** YELLOW: crafting below the expected rate because its output is nearly full. An early warning of CLOG_LOCK. */
    DISPOSAL_THROTTLED
}
