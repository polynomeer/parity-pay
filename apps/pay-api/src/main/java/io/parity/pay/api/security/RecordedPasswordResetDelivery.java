package io.parity.pay.api.security;

import io.parity.pay.api.member.MemberAccount;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬·테스트 전용 전달 구현.
 *
 * <p>메일 서버 없이 재설정 흐름을 끝까지 돌려보기 위한 것입니다. 토큰을 메모리에만 담고 프로세스가
 * 죽으면 사라집니다. {@code local}·{@code test} 프로필에서만 빈으로 등록되므로 운영에는 존재하지
 * 않습니다.
 */
@Component
@Profile({"local", "test"})
public class RecordedPasswordResetDelivery implements PasswordResetDelivery {

    private final Map<String, String> tokensByEmail = new ConcurrentHashMap<>();

    @Override
    public void deliver(MemberAccount account, String rawToken, Instant expiresAt) {
        tokensByEmail.put(account.email(), rawToken);
    }

    /** 마지막으로 발급된 토큰입니다. 테스트와 로컬 데모가 사용자 대신 "메일을 확인"하는 자리입니다. */
    public Optional<String> lastTokenFor(String email) {
        return Optional.ofNullable(tokensByEmail.get(email));
    }
}
