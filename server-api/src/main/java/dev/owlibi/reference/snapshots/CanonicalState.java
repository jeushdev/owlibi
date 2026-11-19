package dev.owlibi.reference.snapshots;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed canonical JSON encoding shared by every capture and date-dependent calculation. */
public final class CanonicalState {
    private CanonicalState() {}
    public static String json(Object value) { return JsonMapper.shared().writeValueAsString(sorted(JsonMapper.shared().valueToTree(value))); }
    private static JsonNode sorted(JsonNode node) {
        if(node.isObject()) {
            var result=JsonMapper.shared().createObjectNode();
            node.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> result.set(e.getKey(),sorted(e.getValue())));
            return result;
        }
        if(node.isArray()) { var result=JsonMapper.shared().createArrayNode(); node.forEach(n -> result.add(sorted(n))); return result; }
        return node;
    }
    public static String hash(String json) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String token(ProjectSnapshot capture) {
        var project=new LinkedHashMap<String,Object>();
        project.put("id",capture.project().id());project.put("name",capture.project().name());project.put("description",capture.project().description());
        project.put("templateSnapshot",JsonMapper.shared().readTree(capture.templateJson()));
        // Public task DTOs contain caller convenience fields. Persisted rows retain raw acceptance and assignment time instead.
        return hash(json(List.of(project,capture.members(),capture.settings(),capture.tasks())));
    }
    public static String calculationKey(ProjectSnapshot capture) {
        return hash(json(List.of(capture.snapshot().stateToken(),capture.projectToday().toString(),
            capture.settings().nearDueDays(),capture.settings().overloadThreshold())));
    }
}
