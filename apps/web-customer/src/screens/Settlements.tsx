/**
 * 판매자 정산 (DOC-15 §3).
 *
 * **일반적인 매출 대시보드가 아니라 금액의 근거를 추적할 수 있게 만드는 것**이 핵심입니다.
 * 그래서 합계 옆에 항목을 함께 놓고, 항목 합계가 순액과 같은지 화면에서 보이게 합니다(INV-008).
 *
 * 금액은 부호가 있습니다. 원장이 아니므로 방향을 부호로 표현합니다 — 취소와 수수료는 음수입니다.
 */
import {
  listMerchantSettlements,
  listSettlementItems,
  type SettlementItemResponse,
  type SettlementResponse,
} from "@paritypay/api-client";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api";
import { formatWon } from "../format";

const ITEM_LABEL: Record<string, string> = {
  SALE: "매출",
  CANCELLATION: "취소",
  FEE: "수수료",
  ADJUSTMENT: "조정",
};

export function Settlements() {
  const [openId, setOpenId] = useState<string | null>(null);
  const settlements = useQuery<SettlementResponse[]>({
    queryKey: ["merchant", "settlements"],
    queryFn: () => listMerchantSettlements(api),
  });

  if (settlements.isPending) {
    return <p>정산 내역을 불러오는 중입니다.</p>;
  }
  if (settlements.isError) {
    return <p role="alert">정산 내역을 불러오지 못했습니다.</p>;
  }

  return (
    <section>
      <h1>정산</h1>
      <table>
        <thead>
          <tr>
            <th>기간</th>
            <th>결제</th>
            <th>취소</th>
            <th>수수료</th>
            <th>정산액</th>
            <th>상태</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {settlements.data.map((settlement) => (
            <tr key={settlement.settlementId} data-testid="settlement-row">
              <td>
                {settlement.periodStart} ~ {settlement.periodEnd}
              </td>
              <td>{formatWon(settlement.grossAmount ?? 0)}</td>
              <td>{formatWon(settlement.cancellationAmount ?? 0)}</td>
              <td>{formatWon(settlement.feeAmount ?? 0)}</td>
              <td data-testid="net">{formatWon(settlement.netAmount ?? 0)}</td>
              <td>{settlement.status}</td>
              <td>
                <button
                  type="button"
                  data-testid="open-items"
                  onClick={() => setOpenId(settlement.settlementId ?? null)}
                >
                  근거 보기
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {settlements.data.length === 0 && <p>정산 내역이 없습니다.</p>}
      {openId !== null && <SettlementItems settlementId={openId} />}
    </section>
  );
}

function SettlementItems({ settlementId }: { settlementId: string }) {
  const items = useQuery<SettlementItemResponse[]>({
    queryKey: ["settlement-items", settlementId],
    queryFn: () => listSettlementItems(api, settlementId),
  });

  if (items.data === undefined) {
    return <p>근거를 불러오는 중입니다.</p>;
  }

  const sum = items.data.reduce((total, item) => total + (item.amount ?? 0), 0);

  return (
    <div data-testid="items">
      <h2>정산 근거</h2>
      <table>
        <thead>
          <tr>
            <th>유형</th>
            <th>결제</th>
            <th>금액</th>
          </tr>
        </thead>
        <tbody>
          {items.data.map((item) => (
            <tr key={item.itemId} data-testid="item-row">
              <td>{ITEM_LABEL[item.itemType ?? ""] ?? item.itemType}</td>
              <td>{item.paymentId}</td>
              {/* 부호를 지우지 않습니다. 취소와 수수료가 빼간 것이 보여야 합니다. */}
              <td data-testid="item-amount">{formatWon(item.amount ?? 0)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th colSpan={2}>항목 합계</th>
            <td data-testid="item-sum">{formatWon(sum)}</td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
