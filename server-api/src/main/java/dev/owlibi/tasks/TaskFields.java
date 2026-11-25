package dev.owlibi.tasks;

import dev.owlibi.web.ApiFailure;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Validation for the task-specific request fields: due dates, assignee lists and version numbers. */
final class TaskFields {
    private static final int MAXIMUM_ASSIGNEES = 6;

    private TaskFields() {}

    /** Parses a {@code yyyy-MM-dd} due date, or returns {@code null} when there is none. */
    static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw new IllegalArgumentException();
            }
            return LocalDate.parse(value);
        } catch (RuntimeException failure) {
            throw ApiFailure.validation("dueDate");
        }
    }

    /** Parses one to six distinct, lowercase-canonical UUIDs, keeping their order. */
    static List<UUID> assignees(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAXIMUM_ASSIGNEES) {
            throw ApiFailure.validation("assigneeIds");
        }
        var ids = new LinkedHashSet<UUID>();
        for (String value : values) {
            try {
                UUID id = UUID.fromString(value);
                boolean canonical = id.toString().equalsIgnoreCase(value);
                if (!canonical || !ids.add(id)) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException failure) {
                throw ApiFailure.validation("assigneeIds");
            }
        }
        return List.copyOf(ids);
    }

    /** Requires a version number of at least {@code minimum}. */
    static long version(Long value, String field, long minimum) {
        if (value == null || value < minimum) {
            throw ApiFailure.validation(field);
        }
        return value;
    }
}
