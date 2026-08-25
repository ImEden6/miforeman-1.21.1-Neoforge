package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.goal.ProductionGoal.TargetType;

/**
 * Container record holding validated goal form input values.
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
