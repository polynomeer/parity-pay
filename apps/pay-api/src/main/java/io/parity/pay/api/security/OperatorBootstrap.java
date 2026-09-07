package io.parity.pay.api.security;

import io.parity.pay.api.member.MemberAccountRepository;
import io.parity.pay.shared.id.MemberId;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 운영자 계정 초기 생성.
 *
 * <p>운영자를 만들 권한은 운영자에게 있으므로, 첫 운영자는 시스템 밖에서 넣어야 합니다. 설정에
 * 적힌 계정이 없으면 만들고, 이미 있으면 아무것도 하지 않습니다.
 *
 * <p>운영 환경에서는 이 목록을 비우고 별도 절차로 계정을 만듭니다. 설정 파일에 비밀번호를 두는
 * 것은 로컬·테스트에서만 허용되는 방식입니다.
 */
@Component
public class OperatorBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OperatorBootstrap.class);

    private final SecurityProperties properties;
    private final MemberAccountRepository memberAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    OperatorBootstrap(
            SecurityProperties properties,
            MemberAccountRepository memberAccountRepository,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.properties = properties;
        this.memberAccountRepository = memberAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        createConfiguredOperators();
    }

    /** 설정에 적힌 운영자 계정을 만듭니다. 이미 있으면 아무것도 하지 않습니다. */
    public void createConfiguredOperators() {
        properties.bootstrapOperators().forEach(this::createIfAbsent);
    }

    void createIfAbsent(SecurityProperties.BootstrapOperator operator) {
        if (memberAccountRepository.findByEmail(operator.email()).isPresent()) {
            return;
        }
        MemberId memberId = MemberId.generate();
        jdbcTemplate.update(
                """
                INSERT INTO member (member_id, email, password_hash, status, roles, created_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (email) DO NOTHING
                """,
                memberId.value(),
                operator.email(),
                passwordEncoder.encode(operator.password()),
                operator.roles().stream().map(Role::name).collect(Collectors.joining(",")),
                Timestamp.from(clock.instant()));
        log.info("bootstrapped operator account {} with roles {}", operator.email(), operator.roles());
    }
}
