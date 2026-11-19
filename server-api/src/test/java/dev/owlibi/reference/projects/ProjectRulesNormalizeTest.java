package dev.owlibi.reference.projects;

import dev.owlibi.reference.web.ApiFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRulesNormalizeTest {

    private final JsonMapper mapper = JsonMapper.shared();

    @Test
    void acceptsValidProjectSettings() throws Exception {
        var input = mapper.readTree("""
            {
              "effortScaleName": "Fibonacci",
              "effortScaleExamples": [
                {"points": "1", "label": "Small"},
                {"points": "2", "label": "Medium"}
              ],
              "overloadThreshold": "10",
              "nearDueDays": 7
            }
            """);

        var result = ProjectRules.normalize(input, null);

        assertEquals("Fibonacci", result.effortScaleName());
        assertEquals("10.00", result.overloadThreshold());
        assertEquals(7, result.nearDueDays());
        assertEquals(2, result.effortScaleExamples().size());
    }

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        var input = mapper.readTree("""
            {
              "effortScaleName": "Fibonacci"
            }
            """);

        assertThrows(
            ApiFailure.class,
            () -> ProjectRules.normalize(input, null)
        );
    }

    @Test
    void rejectsNearDueDaysAboveThirty() throws Exception {
        var input = mapper.readTree("""
            {
              "effortScaleName": "Fibonacci",
              "effortScaleExamples": [
                {"points": "1", "label": "Small"}
              ],
              "overloadThreshold": "10",
              "nearDueDays": 31
            }
            """);

        assertThrows(
            ApiFailure.class,
            () -> ProjectRules.normalize(input, null)
        );
    }

    @Test
    void rejectsDuplicateEffortPoints() throws Exception {
        var input = mapper.readTree("""
            {
              "effortScaleName": "Fibonacci",
              "effortScaleExamples": [
                {"points": "1", "label": "Small"},
                {"points": "1.00", "label": "Medium"}
              ],
              "overloadThreshold": "10",
              "nearDueDays": 7
            }
            """);

        assertThrows(
            ApiFailure.class,
            () -> ProjectRules.normalize(input, null)
        );
    }
}
