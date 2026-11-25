package dev.owlibi.summaries;

import java.time.LocalDate;

/**
 * How close an open task is to its due date, as shown in the workload summary ({@code dateState}).
 */
public enum DueState {
    /** The task has no due date. */
    NONE,
    /** The due date is before today. */
    OVERDUE,
    /** The due date is today or within the project's near-due window. */
    NEAR_DUE,
    /** The due date is after the near-due window. */
    LATER;

    /**
     * Classifies a due date.
     *
     * @param due the task's due date, or {@code null}
     * @param today the project's current date
     * @param nearDueCutoff the last date that still counts as near due ({@code today + nearDueDays})
     */
    public static DueState classify(LocalDate due, LocalDate today, LocalDate nearDueCutoff) {
        if (due == null) {
            return NONE;
        }
        if (due.isBefore(today)) {
            return OVERDUE;
        }
        if (!due.isAfter(nearDueCutoff)) {
            return NEAR_DUE;
        }
        return LATER;
    }
}
