package io.parity.pay.api.security;

import io.parity.pay.api.member.MemberAccount;
import java.time.Instant;

/**
 * 재설정 토큰을 사용자에게 전달하는 포트.
 *
 * <p>토큰은 이 포트 밖으로 나가면 안 됩니다. 응답 본문에 실어 보내면 이메일 주소만 아는 사람이
 * 계정을 가져갈 수 있고, 로그에 남기면 로그를 볼 수 있는 사람이 모두 가져갈 수 있습니다.
 *
 * <p>이메일·SMS 어댑터는 아직 없습니다. 운영 기본 구현은 요청 사실만 남기고 토큰을 전달하지
 * 않으므로, 전달 어댑터를 붙이기 전에는 재설정 흐름이 운영에서 완결되지 않습니다. 이 상태를
 * 문서에 명시합니다. 근거: docs/05-technical-design.md §11
 */
interface PasswordResetDelivery {

    void deliver(MemberAccount account, String rawToken, Instant expiresAt);
}
