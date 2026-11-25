package dev.owlibi.reference.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a request's JSON body strictly: size-limited, UTF-8 only, no duplicate or unknown fields,
 * and every field checked against the type the endpoint expects.
 */
@Component
public final class JsonBody {
    private static final int MAXIMUM_BYTES = 65536;

    /** The JSON type a field must have. */
    public enum Kind { STRING, INTEGER, STRING_ARRAY, OBJECT, OBJECT_ARRAY }

    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(8000).build())
            .build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    /** Reads a body whose fields are all strings. */
    public JsonNode read(HttpServletRequest request, String... fields) {
        var schema = new HashMap<String, Kind>();
        for (String field : fields) {
            schema.put(field, Kind.STRING);
        }
        return readTyped(request, schema);
    }

    /** Reads a JSON object body and checks each field against {@code schema}. */
    public JsonNode readTyped(HttpServletRequest request, Map<String, Kind> schema) {
        byte[] bytes = bytes(request);
        requireJsonContentType(request);
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw ApiFailure.malformed();
            }
            validate(node, schema);
            return node;
        } catch (ApiFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw ApiFailure.malformed();
        }
    }

    private static void requireJsonContentType(HttpServletRequest request) {
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType());
            boolean json = "application".equalsIgnoreCase(type.getType()) && "json".equalsIgnoreCase(type.getSubtype());
            boolean utf8 = type.getCharset() == null || StandardCharsets.UTF_8.equals(type.getCharset());
            if (!json || !utf8) {
                throw unsupportedMediaType();
            }
        } catch (IllegalArgumentException invalid) {
            throw unsupportedMediaType();
        }
    }

    private static ApiFailure unsupportedMediaType() {
        return new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json UTF-8");
    }

    /** Checks that {@code node} is an object whose fields all appear in {@code schema} with the right type. */
    public static void validate(JsonNode node, Map<String, Kind> schema) {
        if (node == null || !node.isObject()) {
            throw ApiFailure.malformed();
        }
        for (var entry : node.properties()) {
            Kind kind = schema.get(entry.getKey());
            if (kind == null) {
                throw ApiFailure.malformed();
            }
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                continue; // Service owns explicit-null/required semantics.
            }
            switch (kind) {
                case STRING -> requireString(value);
                case INTEGER -> {
                    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                        throw ApiFailure.malformed();
                    }
                }
                case OBJECT -> {
                    if (!value.isObject()) {
                        throw ApiFailure.malformed();
                    }
                }
                case STRING_ARRAY -> {
                    if (!value.isArray()) {
                        throw ApiFailure.malformed();
                    }
                    for (JsonNode item : value) {
                        requireString(item);
                    }
                }
                case OBJECT_ARRAY -> {
                    if (!value.isArray()) {
                        throw ApiFailure.malformed();
                    }
                    for (JsonNode item : value) {
                        if (!item.isObject()) {
                            throw ApiFailure.malformed();
                        }
                    }
                }
            }
        }
    }

    private static void requireString(JsonNode value) {
        if (!value.isString()) {
            throw ApiFailure.malformed();
        }
        Fields.scalar(value.asText());
    }

    /** A string field, or {@code null} when it is missing or JSON {@code null}. */
    public static String string(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** An integer field, or {@code null} when it is missing or JSON {@code null}. */
    public static Long integer(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.longValue();
    }

    /** A string-array field, or {@code null} when it is missing or JSON {@code null}. */
    public static List<String> strings(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        var result = new ArrayList<String>();
        value.forEach(item -> result.add(item.asText()));
        return List.copyOf(result);
    }

    /** Rejects a request that should have no body but sent one. */
    public void empty(HttpServletRequest request) {
        if (bytes(request).length != 0) {
            throw ApiFailure.malformed();
        }
    }

    private byte[] bytes(HttpServletRequest request) {
        if (request.getHeader("Content-Encoding") != null) {
            throw ApiFailure.malformed();
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAXIMUM_BYTES + 1);
            if (bytes.length > MAXIMUM_BYTES) {
                throw new ApiFailure(400, "REQUEST_TOO_LARGE", "Request body is too large");
            }
            return bytes;
        } catch (IOException failure) {
            throw ApiFailure.malformed();
        }
    }
}
