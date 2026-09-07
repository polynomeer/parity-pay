package io.parity.pay.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.support.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 마이그레이션 검증.
 *
 * <p>통합 테스트는 매번 빈 DB에 마이그레이션을 적용하므로 "적용된다"는 사실 자체는 이미 확인됩니다.
 * 여기서는 그것만으로 잡히지 않는 실수를 잡습니다.
 *
 * <ul>
 *   <li>이미 적용된 마이그레이션 파일을 나중에 고치는 것 — 체크섬이 어긋나 다른 환경에서 실패합니다.
 *   <li>버전 번호를 건너뛰거나 중복해 순서가 흐트러지는 것.
 *   <li>애플리케이션 엔티티와 스키마가 어긋나는 것 — {@code ddl-auto: validate}가 잡습니다.
 * </ul>
 *
 * <p>근거: docs/03-mvp-scope.md §7(품질 게이트), docs/10-test-strategy.md §11
 */
class MigrationTest extends AbstractIntegrationTest {

    private static final Path MIGRATION_DIR = Path.of("src", "main", "resources", "db", "migration");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("빈 DB에서 모든 마이그레이션이 적용되고 체크섬이 일치한다")
    void migrationsApplyCleanlyToAnEmptyDatabase() {
        // 체크섬이 어긋나면 예외로 실패합니다. 이미 적용된 파일을 수정하면 여기서 걸립니다.
        flyway.validate();

        List<MigrationInfo> applied = Arrays.stream(flyway.info().applied()).toList();
        assertThat(applied).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("마이그레이션 파일과 적용된 버전이 일치한다")
    void everyMigrationFileIsApplied() throws IOException {
        List<String> fileVersions;
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            fileVersions = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .map(name -> name.substring(1, name.indexOf("__")))
                    .sorted(java.util.Comparator.comparingInt(Integer::parseInt))
                    .toList();
        }

        List<String> appliedVersions = Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().getVersion())
                .toList();

        assertThat(appliedVersions).containsExactlyElementsOf(fileVersions);
        // 버전이 1부터 빠짐없이 이어져야 합니다.
        assertThat(fileVersions)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, fileVersions.size())
                        .mapToObj(String::valueOf)
                        .toList());
    }

    @Test
    @DisplayName("원장 불변조건 트리거가 실제로 설치되어 있다")
    void invariantTriggersExist() {
        List<String> triggers = jdbcTemplate.queryForList(
                """
                SELECT tgname FROM pg_trigger
                 WHERE NOT tgisinternal
                 ORDER BY tgname
                """,
                String.class);

        // 스키마에는 있지만 트리거를 빠뜨린 채 배포되는 상황을 막습니다.
        assertThat(triggers)
                .contains(
                        "tg_ledger_entry_balance",
                        "tg_ledger_transaction_balance",
                        "tg_ledger_entry_immutable",
                        "tg_ledger_transaction_immutable",
                        "tg_audit_log_append_only");
    }
}
