package io.parity.pay.api.outbox;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    private final Map<String, Schema> schemas;

    EventSchemaValidator(ObjectMapper objectMapper) {
        this.schemas = loadSchemas(objectMapper);
    }

    /** 이 봉투가 자기 이벤트 타입의 스키마를 만족하는지 봅니다. */
    void validate(JsonNode envelope) {
        String key = keyOf(
                envelope.path("eventType").asText(),
                envelope.path("eventVersion").asInt());
        Schema schema = schemas.get(key);
        if (schema == null) {
            throw new EventContractViolationException(
                    "no schema for event " + key + "; declared schemas: " + schemas.keySet());
        }
        List<Error> errors = schema.validate(envelope);
        if (!errors.isEmpty()) {
            // 어느 필드가 문제인지 함께 적습니다. json-schema-validator 3.x의 메시지에는 경로가
            // 들어 있지 않아, 그대로 쓰면 "형식이 틀렸다"까지만 알 수 있습니다.
            throw new EventContractViolationException(key + " violates its schema: "
                    + errors.stream()
                            .map(error -> error.getInstanceLocation() + " " + error.getMessage())
                            .collect(Collectors.joining("; ")));
        }
    }

    /** 스키마가 있는 이벤트 목록입니다. 카탈로그 검사에서 사용합니다. */
    public Set<String> knownEvents() {
        return new TreeSet<>(schemas.keySet());
    }

    static String keyOf(String eventType, int eventVersion) {
        return eventType + "-v" + eventVersion;
    }

    private static Map<String, Schema> loadSchemas(ObjectMapper objectMapper) {
        String envelope = readEnvelope();
        // 이벤트 스키마가 봉투 스키마를 $ref로 참조합니다. 네트워크로 가져오지 않도록 내용을
        // 직접 등록합니다.
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaLoader(loaders -> loaders.resourceLoaders(
                                resources -> resources.resources(Map.of(ENVELOPE_ID, envelope))))
                        .schemaRegistryConfig(SchemaRegistryConfig.builder()
                                .formatAssertionsEnabled(true)
                                .build()));

        Map<String, Schema> loaded = new LinkedHashMap<>();
        for (Resource resource : findSchemaResources()) {
            String filename = resource.getFilename();
            if (filename == null || filename.equals("envelope.schema.json")) {
                continue;
            }
            String key = filename.substring(0, filename.length() - ".schema.json".length());
            try (InputStream in = resource.getInputStream()) {
                loaded.put(key, registry.getSchema(objectMapper.readTree(in)));
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
