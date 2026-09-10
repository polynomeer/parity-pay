/** 여러 결제의 현재 상태를 한 번에 봅니다. 주문 목록이 결제 상태를 함께 보여 주기 위해서입니다. */
import { getPayment, type PaymentResponse } from "@paritypay/api-client";
import { useQueries } from "@tanstack/react-query";
import { api } from "./api";

export function usePayments(paymentIds: readonly (string | undefined)[]): Record<string, PaymentResponse> {
  const ids = paymentIds.filter((id): id is string => id !== undefined);
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: ["payment", id],
      queryFn: () => getPayment(api, id),
    })),
  });
  const byId: Record<string, PaymentResponse> = {};
  results.forEach((result, index) => {
    const id = ids[index];
    if (id !== undefined && result.data !== undefined) {
      byId[id] = result.data;
    }
  });
  return byId;
}
