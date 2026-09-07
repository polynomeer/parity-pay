package io.parity.pay.api.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * 이벤트 계약 검사기.
 *
 * <p>스키마는 `events/schema/`에 있고 이벤트 타입·버전마다 하나입니다. 검사는 봉투 전체를 대상으로
 * 하며, payload에 선언하지 않은 필드가 들어오면 실패합니다. 필드가 조용히 늘어나는 것을 막기
 * 위해서입니다. 필드를 추가하려면 스키마를 함께 고쳐야 하고, 그 변경이 소비자 계약 변경이라는 것이
 * 리뷰에서 보입니다. 근거: docs/08-db-api-event-spec.md §7·§8
 */
@Component
public class EventSchemaValidator {

    private static final String SCHEMA_PATTERN = "classpath*:events/schema/*.schema.json";
    private static final String ENVELOPE_ID = "https://paritypay.io/events/envelope.schema.json";
    private static final String ENVELOPE_RESOURCE = "events/schema/envelope.schema.json";

    private final Map<String, JsonSchema> schemas;

    EventSchemaValidator(ObjectMapper objectMapper) {
        this.schemas = loadSchemas(objectMapper);
    }

    /** 이 봉투가 자기 이벤트 타입의 스키마를 만족하는지 봅니다. */
    void validate(JsonNode envelope) {
        String key = keyOf(
                envelope.path("eventType").asText(),
                envelope.path("eventVersion").asInt());
        JsonSchema schema = schemas.get(key);
        if (schema == null) {
            throw new EventContractViolationException(
                    "no schema for event " + key + "; declared schemas: " + schemas.keySet());
        }
        Set<ValidationMessage> errors = schema.validate(envelope);
        if (!errors.isEmpty()) {
            throw new EventContractViolationException(key + " violates its schema: "
                    + errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; ")));
        }
    }

    /** 스키마가 있는 이벤트 목록입니다. 카탈로그 검사에서 사용합니다. */
    public Set<String> knownEvents() {
        return new TreeSet<>(schemas.keySet());
    }

    static String keyOf(String eventType, int eventVersion) {
        return eventType + "-v" + eventVersion;
    }

    private static Map<String, JsonSchema> loadSchemas(ObjectMapper objectMapper) {
        String envelope = readEnvelope();
        // 이벤트 스키마가 봉투 스키마를 $ref로 참조합니다. 네트워크로 가져오지 않도록 내용을
        // 직접 등록합니다.
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaLoaders(loaders -> loaders.schemas(Map.of(ENVELOPE_ID, envelope))));
        SchemaValidatorsConfig config =
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();

        Map<String, JsonSchema> loaded = new LinkedHashMap<>();
        for (Resource resource : findSchemaResources()) {
            String filename = resource.getFilename();
            if (filename == null || filename.equals("envelope.schema.json")) {
                continue;
            }
            String key = filename.substring(0, filename.length() - ".schema.json".length());
            try (InputStream in = resource.getInputStream()) {
                loaded.put(key, factory.getSchema(objectMapper.readTree(in), config));
            } catch (IOException e) {
                throw new IllegalStateException("failed to read event schema " + filename, e);
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("no event schemas found at " + SCHEMA_PATTERN);
        }
        return Map.copyOf(loaded);
    }

    private static Resource[] findSchemaResources() {
        try {
            return new PathMatchingResourcePatternResolver().getResources(SCHEMA_PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("failed to scan event schemas", e);
        }
    }

    private static String readEnvelope() {
        try (InputStream in = new PathMatchingResourcePatternResolver()
                .getResource("classpath:" + ENVELOPE_RESOURCE)
                .getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + ENVELOPE_RESOURCE, e);
        }
    }
}
