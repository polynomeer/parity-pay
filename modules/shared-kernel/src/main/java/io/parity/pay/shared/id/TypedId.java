package io.parity.pay.shared.id;

import java.util.UUID;

/**
 * 식별자 타입의 공통 계약.
 *
 * <p>문자열·UUID를 그대로 넘기면 서로 다른 종류의 ID가 뒤섞여도 컴파일러가 잡아주지 못합니다.
 * 근거: docs/06-domain-state-design.md §3
 */
public interface TypedId {
    UUID value();
}
