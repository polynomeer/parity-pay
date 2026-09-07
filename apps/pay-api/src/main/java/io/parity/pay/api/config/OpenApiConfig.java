package io.parity.pay.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 명세 설정.
 *
 * <p>명세는 손으로 쓰지 않고 구현에서 생성합니다. 손으로 쓴 명세는 구현과 어긋나도 아무도 모르는
 * 채로 남습니다. 생성된 결과는 저장소에 스냅샷으로 두고, 구현이 바뀌면 테스트가 차이를 알려줍니다.
 *
 * <p>근거: docs/03-mvp-scope.md §7(OpenAPI가 구현과 일치), docs/10-test-strategy.md §8
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI parityPayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ParityPay API")
                        .version("v1")
                        .description(
                                """
                                플랫폼 내장형 페이머니 결제·원장 백엔드입니다.

                                금융 쓰기 요청은 `Idempotency-Key` 헤더를 요구합니다. 같은 키와 같은
                                본문은 저장된 결과를 돌려주고, 같은 키에 다른 본문은
                                `IDEMPOTENCY_KEY_REUSED`로 거절합니다.

                                외부 결과를 알 수 없는 요청은 실패가 아니라 `202 Accepted`로 응답하고
                                조회 위치를 알려줍니다. 타임아웃을 실패로 단정하지 않습니다.
                                """)
                        .license(new License().name("Portfolio project")))
                // 호스트를 명세에 박지 않습니다. 배포 위치마다 달라지는 값이고, 실행할 때마다
                // 포트가 바뀌면 명세 스냅샷이 의미 없는 차이를 냅니다.
                .servers(List.of(new Server().url("/").description("이 명세를 제공한 서버")))
                // 가입과 로그인을 제외한 모든 엔드포인트가 액세스 토큰을 요구합니다.
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "POST /api/v1/auth/tokens 로 발급받은 액세스 토큰")));
    }

    /** 고객이 쓰는 API입니다. */
    @Bean
    GroupedOpenApi customerApi() {
        return GroupedOpenApi.builder()
                .group("customer")
                .pathsToMatch("/api/v1/**")
                .pathsToExclude("/api/v1/admin/**")
                .build();
    }

    /** 운영자 전용 API입니다. 역할이 없으면 403입니다. */
    @Bean
    GroupedOpenApi operationsApi() {
        return GroupedOpenApi.builder().group("operations").pathsToMatch("/api/v1/admin/**").build();
    }
}
