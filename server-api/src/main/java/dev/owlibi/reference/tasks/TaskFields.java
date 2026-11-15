package dev.owlibi.reference.tasks;

import dev.owlibi.reference.web.ApiFailure;
import java.time.LocalDate;
import java.util.*;

final class TaskFields {
    private TaskFields() {}
    static LocalDate date(String value) {
        if (value==null) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
            return LocalDate.parse(value);
        } catch (RuntimeException failure) { throw ApiFailure.validation("dueDate"); }
    }
    static List<UUID> assignees(List<String> values) {
        if (values==null || values.isEmpty() || values.size()>6) throw ApiFailure.validation("assigneeIds");
        var ids=new LinkedHashSet<UUID>();
        for (String value:values) {
            try {
                UUID id=UUID.fromString(value);
                if (!id.toString().equalsIgnoreCase(value) || !ids.add(id)) throw new IllegalArgumentException();
            } catch (RuntimeException failure) { throw ApiFailure.validation("assigneeIds"); }
        }
        return List.copyOf(ids);
    }
    static long version(Long value,String field,long minimum) {
        if (value==null || value<minimum) throw ApiFailure.validation(field); return value;
    }
}
