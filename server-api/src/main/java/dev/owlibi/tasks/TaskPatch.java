package dev.owlibi.tasks;

import java.util.List;

/**
 * The fields of a task edit request.
 *
 * <p>Each {@link Field} records whether it was supplied at all, which tells "leave unchanged" apart
 * from an explicit {@code null}.
 */
public record TaskPatch(Long version, String reason, Field<String> title, Field<String> description,
    Field<String> category, Field<String> dueDate, Field<String> effortPoints, Field<List<String>> assigneeIds) {

    /** One optional request field and whether the client sent it. */
    public record Field<T>(boolean supplied, T value) {}
}
