package io.parity.pay.api.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 이벤트 카탈로그가 세 곳에서 같은지 봅니다.
 *
 * <p>코드가 발행하는 이벤트, 계약 스키마 파일, 명세서의 카탈로그 표 — 셋 중 하나만 바뀌면 계약이
 * 조용히 어긋납니다. 소비자는 명세서를 보고 만드는데 생산자는 코드를 따르므로, 어긋난 것을 배포
 * 후에 알게 됩니다.
 *
 * <p>근거: docs/08-db-api-event-spec.md §7·§8
 */
class EventCatalogTest {

    private static final Path EVENT_SPEC = Path.of("..", "..", "docs", "08-db-api-event-spec.md");

    @Test
    @DisplayName("코드가 발행하는 이벤트마다 스키마 파일이 있다")
    void everyDeclaredEventHasASchema() {
        assertThat(declaredEventTypes()).isNotEmpty().isEqualTo(schemaEventTypes());
    }

    @Test
    @DisplayName("명세서 카탈로그의 구현 이벤트와 코드·스키마가 일치한다")
    void catalogMatchesCodeAndSchemas() {
        CatalogRows rows = catalogRows();

        assertThat(rows.implemented()).isEqualTo(declaredEventTypes());
        // 미구현으로 적힌 이벤트에 스키마나 생산자가 생겼다면 표의 상태가 낡은 것입니다.
        assertThat(rows.notImplemented()).doesNotContainAnyElementsOf(schemaEventTypes());
    }

    /** `*Events` 클래스의 문자열 상수가 이 시스템이 발행하는 이벤트 타입의 전부입니다. */
    private static Set<String> declaredEventTypes() {
        JavaClasses classes = new ClassFileImporter().importPackages("io.parity.pay");
        Set<String> types = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            if (!javaClass.getSimpleName().endsWith("Events")
                    || !javaClass.getPackageName().contains(".application.event")) {
                continue;
            }
            Stream.of(javaClass.reflect().getDeclaredFields())
                    .filter(EventCatalogTest::isPublicStringConstant)
                    .forEach(field -> types.add(readConstant(field) + "-v1"));
        }
        return types;
    }

    private static boolean isPublicStringConstant(Field field) {
        return Modifier.isPublic(field.getModifiers())
                && Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && field.getType() == String.class;
    }

    private static String readConstant(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("failed to read event type constant " + field, e);
        }
    }

    private static Set<String> schemaEventTypes() {
        try {
            Set<String> types = new TreeSet<>();
            for (Resource resource :
                    new PathMatchingResourcePatternResolver().getResources("classpath*:events/schema/*.schema.json")) {
                String filename = resource.getFilename();
                if (filename == null || filename.equals("envelope.schema.json")) {
                    continue;
                }
                types.add(filename.substring(0, filename.length() - ".schema.json".length()));
            }
            return types;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 명세서의 카탈로그 표를 읽습니다. 표가 사실인지 검사하는 것이 이 테스트의 목적입니다. */
    private static CatalogRows catalogRows() {
        List<String> lines;
        try {
            lines = Files.readAllLines(EVENT_SPEC, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + EVENT_SPEC.toAbsolutePath(), e);
        }

        Set<String> implemented = new LinkedHashSet<>();
        Set<String> notImplemented = new LinkedHashSet<>();
        for (String line : lines) {
            String[] cells = line.split("\\|");
            // | 이벤트 | 파티션 키 | 주요 payload | 소비자 | 상태 |
            if (cells.length != 6) {
                continue;
            }
            String event = cells[1].trim().replace(' ', '-');
            String status = cells[5].trim();
            if (!event.matches("[A-Za-z]+-v\\d+")) {
                continue;
            }
            if ("구현".equals(status)) {
                implemented.add(event);
            } else if ("미구현".equals(status)) {
                notImplemented.add(event);
            }
        }
        return new CatalogRows(new TreeSet<>(implemented), new TreeSet<>(notImplemented));
    }

    private record CatalogRows(Set<String> implemented, Set<String> notImplemented) {}
}
