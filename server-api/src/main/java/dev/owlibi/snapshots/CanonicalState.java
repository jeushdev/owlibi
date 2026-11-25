package dev.owlibi.snapshots;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A fixed JSON encoding (object keys sorted, no spaces) and SHA-256 hashing, shared by every capture
 * and date-dependent calculation. The same data always gives the same text, so equal hashes mean
 * equal data.
 */
public final class CanonicalState {
    private CanonicalState() {}

    /** {@code value} as compact JSON with every object's keys in sorted order. */
    public static String json(Object value) {
        return JsonMapper.shared().writeValueAsString(sorted(JsonMapper.shared().valueToTree(value)));
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var result = JsonMapper.shared().createObjectNode();
            node.properties().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.set(entry.getKey(), sorted(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = JsonMapper.shared().createArrayNode();
            node.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }

    /** The lowercase hex SHA-256 of {@code json} in UTF-8. */
    public static String hash(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The state token of a capture: a hash of its project, members, rules and tasks. */
    public static String token(ProjectSnapshot capture) {
        var project = new LinkedHashMap<String, Object>();
        project.put("id", capture.project().id());
        project.put("name", capture.project().name());
        project.put("description", capture.project().description());
        project.put("templateSnapshot", JsonMapper.shared().readTree(capture.templateJson()));
        // Public task DTOs contain caller convenience fields. Persisted rows retain raw acceptance and assignment time instead.
        return hash(json(List.of(project, capture.members(), capture.settings(), capture.tasks())));
    }

    /** A hash of everything a workload result depends on: the state token, the date and the two date/overload rules. */
    public static String calculationKey(ProjectSnapshot capture) {
        return hash(json(List.of(
            capture.snapshot().stateToken(),
            capture.projectToday().toString(),
            capture.settings().nearDueDays(),
            capture.settings().overloadThreshold())));
    }
}
