package io.parity.pay.api.security;

import io.parity.pay.api.member.MemberAccount;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 재설정 토큰을 메일로 보냅니다.
 *
 * <p>{@code spring.mail.host}가 설정되면 켜지고, 그때 다른 전달 구현보다 우선합니다. 토큰은 메일
 * 본문의 링크에만 들어가며 로그에는 남기지 않습니다 — 로그를 볼 수 있는 사람이 계정을 가져갈 수
 * 있게 되기 때문입니다.
 *
 * <p>{@link PasswordService}가 커밋 뒤에 부릅니다. 여기서 실패하면 예외가 커밋된 트랜잭션 밖으로
 * 나가는데, 토큰은 이미 저장돼 있고 사용자가 다시 요청하면 새 토큰이 나가므로 삼키지 않고 기록만
 * 합니다. 요청 응답은 이미 202로 나간 뒤입니다.
 */
@Component
@Primary
@ConditionalOnProperty("spring.mail.host")
class SmtpPasswordResetDelivery implements PasswordResetDelivery {

    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetDelivery.class);

    private final JavaMailSender mailSender;
    private final PasswordResetLinkProperties links;

    SmtpPasswordResetDelivery(JavaMailSender mailSender, PasswordResetLinkProperties links) {
        this.mailSender = mailSender;
        this.links = links;
    }

    @Override
    public void deliver(MemberAccount account, String rawToken, Instant expiresAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(links.from());
        message.setTo(account.email());
        message.setSubject("[ParityPay] 비밀번호 재설정");
        message.setText(
                """
                비밀번호 재설정을 요청하셨습니다. 아래 링크에서 새 비밀번호를 정하세요.

                %s

                이 링크는 %s까지 유효하며 한 번만 쓸 수 있습니다.
                요청한 적이 없다면 이 메일을 무시하세요. 비밀번호는 바뀌지 않습니다.
                """
                        .formatted(links.linkFor(rawToken), expiresAt));
        try {
            mailSender.send(message);
            log.info("password reset mail sent to member {}", account.memberId());
        } catch (RuntimeException e) {
            // 토큰은 로그에 넣지 않습니다. 회원 ID와 원인만 남깁니다.
            log.error("password reset mail failed for member {}", account.memberId(), e);
            throw e;
        }
    }
}
