package io.parity.pay.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.parity.pay.api.member.MemberAccount;
import io.parity.pay.shared.id.MemberId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 재설정 메일 어댑터.
 *
 * <p>실제 SMTP 왕복은 E2E가 Mailpit으로 확인합니다. 여기서는 어댑터가 무엇을 조립하는지만 봅니다 —
 * 링크에 토큰이 들어가는가, 받는 사람이 맞는가. 같은 패키지에 두는 이유는 어댑터가 패키지 비공개이기
 * 때문입니다. 공개하면 조립 지점 밖에서 부를 수 있게 됩니다.
 */
class SmtpPasswordResetDeliveryTest {

    @Test
    @DisplayName("링크 템플릿의 {token} 자리에 토큰이 들어가고 회원의 주소로 간다")
    void composesTheResetLink() {
        JavaMailSender sender = mock(JavaMailSender.class);
        PasswordResetLinkProperties links =
                new PasswordResetLinkProperties("https://app.example.com/reset?token={token}", "noreply@example.com");
        SmtpPasswordResetDelivery delivery = new SmtpPasswordResetDelivery(sender, links);
        MemberAccount account = new MemberAccount(
                MemberId.of(UUID.randomUUID()), "who@example.com", "hash", "ACTIVE", Set.of(Role.CUSTOMER));

        delivery.deliver(account, "raw-token-123", Instant.parse("2026-09-13T00:30:00Z"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getTo()).containsExactly("who@example.com");
        assertThat(message.getFrom()).isEqualTo("noreply@example.com");
        assertThat(message.getText()).contains("https://app.example.com/reset?token=raw-token-123");
        assertThat(message.getText()).contains("한 번만");
    }

    @Test
    @DisplayName("템플릿에 {token} 자리가 없으면 뜨지 않는다 — 토큰 없는 링크는 아무도 못 쓴다")
    void templateWithoutTokenPlaceholderIsRejected() {
        assertThatThrownBy(() -> new PasswordResetLinkProperties("https://app.example.com/reset", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
