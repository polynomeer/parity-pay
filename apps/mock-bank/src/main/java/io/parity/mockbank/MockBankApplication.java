package io.parity.mockbank;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Mock Bank — 외부 금융기관 대역.
 *
 * <p>같은 프로세스 안의 빈이 아니라 **별도 프로세스**입니다. 그래야 실제로 재현되는 것들이 있습니다.
 *
 * <ul>
 *   <li>연결 거부 — 프로세스를 죽이면 됩니다
 *   <li>응답 지연과 읽기 타임아웃 — 지연을 주입하면 클라이언트가 진짜로 기다립니다
 *   <li>승인 후 응답 유실 — 기관은 처리하고 응답만 끊습니다(F-006·F-010)
 * </ul>
 *
 * <p>같은 프로세스 대역에서는 이 모든 것이 예외를 던지는 흉내였습니다. 흉내는 우리가 생각한
 * 지점에서만 실패합니다.
 *
 * <p>근거: docs/05-technical-design.md §10, docs/13-implementation-checklist.md
 */
@SpringBootApplication
public class MockBankApplication {

    /**
     * 설정 파일 이름을 {@code mock-bank}으로 고정합니다.
     *
     * <p>기본값 {@code application}을 쓰면 통합 테스트에서 문제가 됩니다. 그때는 pay-api와
     * 클래스패스를 공유하므로 기관이 **pay-api의 application.yml을 읽고** 우리 DB 설정과 Flyway를
     * 함께 가져갑니다. 기관은 자기 설정만 압니다.
     */
    public static void main(String[] args) {
        new SpringApplicationBuilder(MockBankApplication.class)
                .properties("spring.config.name=mock-bank")
                .run(args);
    }
}
