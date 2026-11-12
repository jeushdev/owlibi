package dev.owlibi.reference.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Typed, bounded JSON boundary. A2's string-only entry point stays strict. */
@Component
public final class JsonBody {
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(8000).build()).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public JsonNode read(HttpServletRequest request, String... fields) {
        var schema = new HashMap<String,Kind>();
        for (String field : fields) schema.put(field,Kind.STRING);
        return readTyped(request,schema);
    }
    public enum Kind { STRING, INTEGER, STRING_ARRAY, OBJECT, OBJECT_ARRAY }
    public JsonNode readTyped(HttpServletRequest request, Map<String,Kind> schema) {
        byte[] bytes = bytes(request);
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType());
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype()) ||
                (type.getCharset()!=null && !StandardCharsets.UTF_8.equals(type.getCharset())))
                throw new ApiFailure(415,"UNSUPPORTED_MEDIA_TYPE","Use application/json UTF-8");
        } catch (IllegalArgumentException e) { throw new ApiFailure(415,"UNSUPPORTED_MEDIA_TYPE","Use application/json UTF-8"); }
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) throw ApiFailure.malformed();
            validate(node,schema);
            return node;
        } catch (ApiFailure failure) { throw failure; }
        catch (Exception failure) { throw ApiFailure.malformed(); }
    }
    public static void validate(JsonNode node,Map<String,Kind> schema) {
        if (node==null || !node.isObject()) throw ApiFailure.malformed();
        for (var entry:node.properties()) {
            Kind kind=schema.get(entry.getKey());
            if (kind==null) throw ApiFailure.malformed();
            JsonNode value=entry.getValue();
            if (value.isNull()) continue; // Service owns explicit-null/required semantics.
            switch (kind) {
                case STRING -> { if (!value.isString()) throw ApiFailure.malformed(); Fields.scalar(value.asText()); }
                case INTEGER -> { if (!value.isIntegralNumber() || !value.canConvertToLong()) throw ApiFailure.malformed(); }
                case OBJECT -> { if (!value.isObject()) throw ApiFailure.malformed(); }
                case STRING_ARRAY -> {
                    if (!value.isArray()) throw ApiFailure.malformed();
                    for (JsonNode item:value) { if (!item.isString()) throw ApiFailure.malformed(); Fields.scalar(item.asText()); }
                }
                case OBJECT_ARRAY -> {
                    if (!value.isArray()) throw ApiFailure.malformed();
                    for (JsonNode item:value) if (!item.isObject()) throw ApiFailure.malformed();
                }
            }
        }
    }
    public static String string(JsonNode body, String field) {
        JsonNode value = body.get(field); return value == null || value.isNull() ? null : value.asText();
    }
    public static Long integer(JsonNode body,String field) {
        JsonNode value=body.get(field); return value==null || value.isNull()?null:value.longValue();
    }
    public static List<String> strings(JsonNode body,String field) {
        JsonNode value=body.get(field); if (value==null || value.isNull()) return null;
        var result=new ArrayList<String>(); value.forEach(item -> result.add(item.asText())); return List.copyOf(result);
    }
    public void empty(HttpServletRequest request) { if (bytes(request).length != 0) throw ApiFailure.malformed(); }
    private byte[] bytes(HttpServletRequest request) {
        if (request.getHeader("Content-Encoding") != null) throw ApiFailure.malformed();
        try {
            byte[] bytes = request.getInputStream().readNBytes(65537);
            if (bytes.length > 65536) throw new ApiFailure(400,"REQUEST_TOO_LARGE","Request body is too large");
            return bytes;
        } catch (IOException failure) { throw ApiFailure.malformed(); }
    }
}
