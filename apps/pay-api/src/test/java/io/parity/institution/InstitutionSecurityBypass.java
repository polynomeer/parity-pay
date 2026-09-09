package io.parity.institution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 시험에서 외부기관을 열어 둡니다.
 *
 * <p>기관 앱에는 Spring Security 의존성이 없습니다. 그런데 통합 시험에서는 pay-api와 클래스패스를
 * 공유하므로 Spring Security가 자동 설정되고, 기관의 모든 엔드포인트가 401이 됩니다. 운영 배포에는
 * 이 문제 자체가 없습니다.
 *
 * <p>예전에는 자동설정 클래스 이름을 나열해 제외했습니다. 그 목록은 <b>Spring Boot 판올림마다
 * 깨집니다</b> — Boot 4에서 네 클래스가 모두 다른 패키지로 옮겨갔고, 이름이 틀리면 조용히 아무것도
 * 제외되지 않아 401만 남습니다. 이름을 맞히는 대신 열어 두는 규칙을 직접 넣습니다.
 *
 * <p>패키지가 {@code io.parity.pay} 밖인 것이 중요합니다. 안에 두면 pay-api의 컴포넌트 스캔에 잡혀
 * 우리 보안 설정과 충돌합니다 — 모든 요청을 허용하는 체인이 먼저 걸려 다른 체인이 도달 불가가 되고,
 * 컨텍스트가 아예 뜨지 않습니다. 기관 컨텍스트에만 {@code .sources(...)}로 등록합니다.
 */
@Configuration
@ConditionalOnClass(SecurityFilterChain.class)
public class InstitutionSecurityBypass {

    @Bean
    SecurityFilterChain institutionFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
