package dev.owlibi.reference.tasks;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One way a task can move between statuses, such as starting work or approving it.
 *
 * <p>The abstract class holds what every transition shares: its action name, the statuses it may start
 * from, and the status it leads to. Each subclass answers the questions that differ: who may request it,
 * whether a reason is required, and whether every assignee must have accepted the current agreement.
 * {@link TaskService} works only with this type, so adding a new transition means adding a subclass, not
 * another branch in the service.
 *
 * <p>The class is {@code sealed}: only the six transitions below can extend it.
 */
abstract sealed class TaskTransition {
    private final String action;
    private final TaskStatus target;
    private final Set<TaskStatus> sources;

    protected TaskTransition(String action, TaskStatus target, Set<TaskStatus> sources) {
        this.action = action;
        this.target = target;
        this.sources = Set.copyOf(sources);
    }

    /** The action name the browser sends, for example {@code "START"}. It is also the activity event type. */
    final String action() {
        return action;
    }

    /** The status after this transition, or empty when the task is not in a status it starts from. */
    final Optional<TaskStatus> next(TaskStatus currentStatus) {
        return sources.contains(currentStatus) ? Optional.of(target) : Optional.empty();
    }

    /** Whether an assignee who is not the leader may request this transition. The leader may request any. */
    abstract boolean openToAssignees();

    /** Whether the person requesting it must give a reason. */
    abstract boolean requiresReason();

    /** Whether every current assignee must have accepted the current agreement first. */
    abstract boolean requiresAcceptance();

    /** The task's first-start time after this transition. Most transitions leave it unchanged. */
    Instant firstStart(Instant current, Instant now) {
        return current;
    }

    private static final Map<String, TaskTransition> BY_ACTION =
        List.of(new Start(), new SubmitReview(), new Approve(), new Return(), new Reopen(), new Cancel())
            .stream()
            .collect(Collectors.toUnmodifiableMap(TaskTransition::action, Function.identity()));

    /** The transition for an action name, or empty when the name is unknown. */
    static Optional<TaskTransition> named(String action) {
        return action == null ? Optional.empty() : Optional.ofNullable(BY_ACTION.get(action));
    }

    /** An assignee starts work on a task that is still to do. */
    static final class Start extends TaskTransition {
        Start() {
            super("START", TaskStatus.IN_PROGRESS, Set.of(TaskStatus.TODO));
        }

        @Override boolean openToAssignees() { return true; }
        @Override boolean requiresReason() { return false; }
        @Override boolean requiresAcceptance() { return true; }

        /** Starting work records the first start time, once. */
        @Override
        Instant firstStart(Instant current, Instant now) {
            return current == null ? now : current;
        }
    }

    /** An assignee hands finished work to the leader. */
    static final class SubmitReview extends TaskTransition {
        SubmitReview() {
            super("SUBMIT_REVIEW", TaskStatus.REVIEW, Set.of(TaskStatus.IN_PROGRESS));
        }

        @Override boolean openToAssignees() { return true; }
        @Override boolean requiresReason() { return false; }
        @Override boolean requiresAcceptance() { return true; }
    }

    /** The leader approves reviewed work, so its points count. */
    static final class Approve extends TaskTransition {
        Approve() {
            super("APPROVE", TaskStatus.DONE, Set.of(TaskStatus.REVIEW));
        }

        @Override boolean openToAssignees() { return false; }
        @Override boolean requiresReason() { return false; }
        @Override boolean requiresAcceptance() { return true; }
    }

    /** The leader sends reviewed work back for another pass. */
    static final class Return extends TaskTransition {
        Return() {
            super("RETURN", TaskStatus.IN_PROGRESS, Set.of(TaskStatus.REVIEW));
        }

        @Override boolean openToAssignees() { return false; }
        @Override boolean requiresReason() { return true; }
        @Override boolean requiresAcceptance() { return false; }
    }

    /** The leader reopens a finished task. */
    static final class Reopen extends TaskTransition {
        Reopen() {
            super("REOPEN", TaskStatus.IN_PROGRESS, Set.of(TaskStatus.DONE));
        }

        @Override boolean openToAssignees() { return false; }
        @Override boolean requiresReason() { return true; }
        @Override boolean requiresAcceptance() { return true; }
    }

    /** The leader cancels a task that is not finished. */
    static final class Cancel extends TaskTransition {
        Cancel() {
            super("CANCEL", TaskStatus.CANCELLED, Set.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.REVIEW));
        }

        @Override boolean openToAssignees() { return false; }
        @Override boolean requiresReason() { return true; }
        @Override boolean requiresAcceptance() { return false; }
    }
}
