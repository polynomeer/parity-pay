package io.parity.pay.api.security;

/**
 * 권한 모델. 근거: docs/02-prd.md §6
 *
 * <p>Spring Security의 {@code hasRole}은 {@code ROLE_} 접두사를 기대하므로 권한 문자열을 함께
 * 정의합니다.
 */
public enum Role {
    /** 본인 지갑·충전·결제·취소·거래내역 */
    CUSTOMER,
    /** 본인 정산 내역 조회 */
    MERCHANT,
    /** 거래·원장·대사·이벤트 읽기 */
    OPS_VIEWER,
    /** 허용된 재조회·재처리·보류 작업 */
    OPS_OPERATOR,
    /** 금액 보정과 고위험 운영 작업 승인 */
    OPS_APPROVER,
    /** 배치·이벤트 소비·복구 작업 */
    SYSTEM;

    public String authority() {
        return "ROLE_" + name();
    }
}
