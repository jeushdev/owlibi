package dev.owlibi.projects;

import dev.owlibi.web.ApiFailure;
import dev.owlibi.web.Fields;
import dev.owlibi.web.JsonBody;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A project's rules and how to validate them.
 *
 * <p>The rules are: the name of the effort scale, a few example estimates that explain it, the
 * remaining-points threshold above which a member counts as overloaded, and how many days ahead a
 * due date counts as near. Examples explain estimates; they never restrict the points a task may use.
 */
public final class ProjectRules {
    private static final int MAXIMUM_NEAR_DUE_DAYS = 30;
    private static final int MAXIMUM_EXAMPLES = 10;

    private ProjectRules() {}

    /** One example estimate on the effort scale, such as {@code 1.00} = "Small scoped change". */
    public record Example(String points, String label) {}

    /** The editable rule values, without version or timestamps. */
    public record Values(String effortScaleName, List<Example> effortScaleExamples, String overloadThreshold, int nearDueDays) {
        public Values {
            effortScaleExamples = List.copyOf(effortScaleExamples);
        }
    }

    /** The stored rules of one project, including whether the leader has configured them yet. */
    public record Settings(UUID projectId, boolean configured, String settingsVersion, String projectZone,
        String effortScaleName, List<Example> effortScaleExamples, String overloadThreshold, Integer nearDueDays,
        Instant updatedAtUtc) {

        /** The rule values, or {@code null} while the rules are not configured. */
        public Values values() {
            return configured ? new Values(effortScaleName, effortScaleExamples, overloadThreshold, nearDueDays) : null;
        }
    }

    /** The JSON fields of a full set of rule values. */
    public static final Map<String, JsonBody.Kind> VALUES = Map.of(
        "effortScaleName", JsonBody.Kind.STRING,
        "effortScaleExamples", JsonBody.Kind.OBJECT_ARRAY,
        "overloadThreshold", JsonBody.Kind.STRING,
        "nearDueDays", JsonBody.Kind.INTEGER);

    /** The JSON fields of an edit: the rule values plus the version being edited. */
    public static final Map<String, JsonBody.Kind> PATCH;

    /** The rule fields in the order they are checked and reported. */
    public static final List<String> ORDER = List.of("effortScaleName", "effortScaleExamples", "overloadThreshold", "nearDueDays");

    private static final Map<String, JsonBody.Kind> EXAMPLE_FIELDS = Map.of(
        "points", JsonBody.Kind.STRING,
        "label", JsonBody.Kind.STRING);

    static {
        var fields = new HashMap<>(VALUES);
        fields.put("settingsVersion", JsonBody.Kind.STRING);
        PATCH = Map.copyOf(fields);
    }

    /**
     * Validates submitted rule fields and merges them over the previous values.
     *
     * @param input the submitted JSON object
     * @param previous the current values, or {@code null} when every field must be supplied
     */
    public static Values normalize(JsonNode input, Values previous) {
        JsonBody.validate(input, PATCH);
        for (String field : ORDER) {
            boolean missingButRequired = previous == null && !input.has(field);
            boolean explicitlyNull = input.has(field) && input.get(field).isNull();
            if (missingButRequired || explicitlyNull) {
                throw ApiFailure.validation(field);
            }
        }

        String name = previous == null ? null : previous.effortScaleName();
        if (input.has("effortScaleName")) {
            name = Fields.text(JsonBody.string(input, "effortScaleName"), "effortScaleName", 100, true, true);
        }

        String threshold = previous == null ? null : previous.overloadThreshold();
        if (input.has("overloadThreshold")) {
            threshold = Fields.points(JsonBody.string(input, "overloadThreshold"), "overloadThreshold").toPlainString();
        }

        int days = previous == null ? 0 : previous.nearDueDays();
        if (input.has("nearDueDays")) {
            long value = JsonBody.integer(input, "nearDueDays");
            if (value < 0 || value > MAXIMUM_NEAR_DUE_DAYS) {
                throw ApiFailure.validation("nearDueDays");
            }
            days = (int) value;
        }

        List<Example> examples = previous == null ? null : previous.effortScaleExamples();
        if (input.has("effortScaleExamples")) {
            examples = examples(input.get("effortScaleExamples"));
        }
        return new Values(name, examples, threshold, days);
    }

    /** Validates 1 to 10 examples, each with valid points and a label, and no two with the same points. */
    private static List<Example> examples(JsonNode array) {
        if (array.isEmpty() || array.size() > MAXIMUM_EXAMPLES) {
            throw ApiFailure.validation("effortScaleExamples");
        }
        var examples = new ArrayList<Example>();
        var seenPoints = new HashSet<String>();
        for (var item : array) {
            JsonBody.validate(item, EXAMPLE_FIELDS);
            String points = Fields.points(JsonBody.string(item, "points"), "effortScaleExamples").toPlainString();
            String label = Fields.text(JsonBody.string(item, "label"), "effortScaleExamples", 100, true, true);
            if (!seenPoints.add(points)) {
                throw ApiFailure.validation("effortScaleExamples");
            }
            examples.add(new Example(points, label));
        }
        return List.copyOf(examples);
    }

    /** Re-checks a complete set of values that came from code (such as a template) rather than a request. */
    public static Values validated(Values value) {
        if (value == null) {
            throw ApiFailure.validation("settings");
        }
        return normalize(JsonMapper.shared().valueToTree(value), null);
    }

    /** Parses the {@code settingsVersion} a client sends back: a non-negative whole number without leading zeros. */
    public static long version(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) {
            throw ApiFailure.validation("settingsVersion");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw ApiFailure.validation("settingsVersion");
        }
    }

    /** The rules as an ordered map, recorded in the activity log before and after each change. */
    public static Map<String, Object> summary(Settings value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("configured", value.configured());
        result.put("settingsVersion", value.settingsVersion());
        result.put("effortScaleName", value.effortScaleName());
        result.put("effortScaleExamples", value.effortScaleExamples());
        result.put("overloadThreshold", value.overloadThreshold());
        result.put("nearDueDays", value.nearDueDays());
        return result;
    }
}
