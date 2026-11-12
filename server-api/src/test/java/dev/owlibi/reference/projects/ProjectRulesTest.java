package dev.owlibi.reference.projects;

import dev.owlibi.reference.web.ApiFailure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRulesTest {

    @Test
    void acceptsValidVersionNumbers() {
        assertEquals(0L, ProjectRules.version("0"));
        assertEquals(1L, ProjectRules.version("1"));
        assertEquals(25L, ProjectRules.version("25"));
    }

    @Test
    void rejectsInvalidVersionNumbers() {
        String[] invalidValues = {
            "abc",
            "-1",
            "01",
            "9223372036854775808"
        };

        for (String value : invalidValues) {
            ApiFailure exception = assertThrows(
                ApiFailure.class,
                () -> ProjectRules.version(value)
            );

            assertEquals(422, exception.status());
        }
    }

    @Test
    void rejectsNullVersion() {
        ApiFailure exception = assertThrows(
            ApiFailure.class,
            () -> ProjectRules.version(null)
        );

        assertEquals(422, exception.status());
    }
}
