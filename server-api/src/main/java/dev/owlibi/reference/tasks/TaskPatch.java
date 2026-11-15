package dev.owlibi.reference.tasks;

import java.util.List;

/** Sparse request values; supplied distinguishes omission from explicit null. */
public record TaskPatch(Long version, String reason, Field<String> title, Field<String> description,
    Field<String> category, Field<String> dueDate, Field<String> effortPoints, Field<List<String>> assigneeIds) {
    public record Field<T>(boolean supplied, T value) {}
}
