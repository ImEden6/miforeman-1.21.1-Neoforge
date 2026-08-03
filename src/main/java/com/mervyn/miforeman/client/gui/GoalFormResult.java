package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.goal.ProductionGoal.TargetType;

/**
 * Bundles the Define Goal form's field values for the callback hand-off from
 * {@link com.mervyn.miforeman.client.gui.blockui.DefineGoalWindow} back to {@link ClipboardScreen}
 * (see .scratch/blockui-define-goal-trial/issues/04-state-handoff-contract.md).
 */
public record GoalFormResult(
        String goalName,
        TargetType targetType,
        String targetIdStr,
        double rate,
        boolean perHour,
        double threshold
) {
}
