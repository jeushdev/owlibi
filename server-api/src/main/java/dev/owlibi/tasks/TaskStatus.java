package dev.owlibi.tasks;

/**
 * Where a task is in its life cycle. The names match the {@code tasks.status} column and the JSON API.
 *
 * <pre>
 *   TODO ──START──▶ IN_PROGRESS ──SUBMIT_REVIEW──▶ REVIEW ──APPROVE──▶ DONE
 *                        ▲                            │                 │
 *                        └───────────RETURN───────────┘                 │
 *                        └───────────REOPEN─────────────────────────────┘
 *   CANCEL moves any open task (TODO, IN_PROGRESS, REVIEW) to CANCELLED.
 * </pre>
 *
 * <p>The methods below name the groups of statuses the business rules talk about, so the rest of the
 * code asks {@code status.isClosed()} instead of comparing strings.
 */
public enum TaskStatus {
    TODO("To do"),
    IN_PROGRESS("In progress"),
    REVIEW("In review"),
    DONE("Done"),
    CANCELLED("Cancelled");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    /** The readable name shown to people, for example {@code "In progress"}. */
    public String label() {
        return label;
    }

    /** Whether the task is finished one way or another (done or cancelled), so it can no longer be edited or accepted. */
    public boolean isClosed() {
        return this == DONE || this == CANCELLED;
    }

    /** Whether the task is still active: to do, in progress or waiting for review. */
    public boolean isOpen() {
        return !isClosed();
    }

    /** Whether the task's effort points still count as remaining work. Work waiting for review does not. */
    public boolean countsAsRemaining() {
        return this == TODO || this == IN_PROGRESS;
    }

    /** Whether the task's effort points count toward its assignees' completed contribution. */
    public boolean countsAsCompleted() {
        return this == DONE;
    }

    /**
     * The status stored under {@code value}, such as {@code "IN_PROGRESS"}.
     *
     * @throws IllegalArgumentException when {@code value} is not a known status
     */
    public static TaskStatus from(String value) {
        for (TaskStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown task status: " + value);
    }
}
