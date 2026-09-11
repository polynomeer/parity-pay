package io.parity.pay.shared.security;

/**
 * 호출자가 <b>방금</b> 비밀번호를 다시 확인했다는 증거.
 *
 * <p>로그인 세션은 15분짜리 액세스 토큰과 회전하는 리프레시 토큰으로 오래 이어집니다. 원장을
 * 움직이는 운영 작업은 그 세션이 살아 있다는 것만으로는 부족합니다 — 자리를 비운 단말, 넘겨받은
 * 탭이 그대로 승인할 수 있기 때문입니다. 그래서 그 작업 앞에서는 비밀번호를 다시 묻고, 짧게 사는
 * 증거를 요구합니다.
 *
 * <p>업무 모듈은 증거가 무엇인지(토큰인지, 어떻게 서명됐는지) 알지 못합니다. 문자열을 받아 조립
 * 지점에 넘길 뿐입니다. {@link ApprovalAuthority}와 같은 자리입니다.
 *
 * <p>근거: docs/14-frontend-design.md §13 열린 질문 4, docs/05-technical-design.md §11
 */
public interface RecentAuthentication {

    /**
     * 증거를 검증합니다.
     *
     * @throws io.parity.pay.shared.error.BusinessException 증거가 없거나, 만료됐거나, 위조됐거나,
     *     지금 호출자의 것이 아닐 때
     */
    void require(String proof);
}
