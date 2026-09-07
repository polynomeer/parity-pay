package io.parity.pay.api.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.parity.pay.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 인증·인가 설정.
 *
 * <p>규칙은 셋입니다.
 *
 * <ul>
 *   <li>가입과 로그인만 공개합니다. 나머지 모든 API는 인증이 필요합니다.
 *   <li>운영 API는 역할로 나눕니다. 읽기는 {@code OPS_VIEWER} 이상, 상태를 바꾸는 작업은
 *       {@code OPS_OPERATOR} 이상, 금액 보정은 승인자가 따로 있어야 합니다.
 *   <li>세션을 만들지 않습니다. 토큰만으로 판단합니다.
 * </ul>
 *
 * <p>근거: docs/02-prd.md §6, docs/05-technical-design.md §11
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 토큰 기반이며 브라우저 폼을 쓰지 않으므로 CSRF 토큰이 필요 없습니다.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/v1/members").permitAll()
                        .requestMatchers("/api/v1/auth/tokens", "/api/v1/auth/tokens/refresh")
                        .permitAll()
                        // 헬스와 지표는 내부 수집 대상입니다. 운영에서는 네트워크로 제한합니다.
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole(Role.OPS_VIEWER.name())
                        // 장애 주입은 운영 환경에 존재하면 안 되는 기능입니다. 최소한 운영자로 제한합니다.
                        .requestMatchers("/api/v1/admin/mock-bank/**")
                        .hasRole(Role.OPS_OPERATOR.name())
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/**")
                        .hasAnyRole(
                                Role.OPS_VIEWER.name(),
                                Role.OPS_OPERATOR.name(),
                                Role.OPS_APPROVER.name())
                        .requestMatchers("/api/v1/admin/**")
                        .hasAnyRole(Role.OPS_OPERATOR.name(), Role.OPS_APPROVER.name())
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, 401, ErrorCode.RESOURCE_NOT_FOUND,
                                        "authentication is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, 403, ErrorCode.RISK_BLOCKED,
                                        "this operation requires a different role")))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    /** 오류 응답 형식을 API 나머지와 맞춥니다. 근거: docs/08-db-api-event-spec.md §5 */
    private static void writeError(
            jakarta.servlet.http.HttpServletResponse response,
            int status,
            ErrorCode code,
            String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write("{\"code\":\"%s\",\"message\":\"%s\",\"traceId\":\"\",\"details\":{}}"
                        .formatted(code.name(), message));
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    JwtEncoder jwtEncoder(SecurityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        return NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private static SecretKeySpec secretKey(SecurityProperties properties) {
        return new SecretKeySpec(
                properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** 비밀번호는 bcrypt로 해시합니다. 근거: docs/05-technical-design.md §11 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
