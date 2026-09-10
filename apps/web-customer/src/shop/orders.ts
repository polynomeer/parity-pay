/**
 * 주문 저장소 (DOC-15 §2).
 *
 * **주문은 클라이언트가 가지고 있고, 결제는 서버가 가지고 있습니다.** 백엔드에 주문 엔티티가
 * 없기 때문입니다 — `orderId`는 우리가 만들어 결제에 실어 보내는 문자열입니다.
 *
 * 이 사실이 DOC-15가 강조한 것을 **말이 아니라 구조로** 만듭니다. 주문 상태와 결제 상태는
 * 서로 다른 시스템이 가지고 있어서, 둘을 같은 값으로 둘 수가 없습니다.
 *
 * | | 소유자 | 값 |
 * |---|---|---|
 * | 주문 상태 | 클라이언트 | 결제대기 · 배송중 · 부분반품 · 구매확정 · 주문취소 |
 * | 결제 상태 | 서버 | READY · PROCESSING · APPROVED · PARTIALLY_CANCELED · CANCELED · FAILED · UNKNOWN |
 *
 * 근거: docs/15-ui-screen-plan.md §2, docs/16-ui-implementation-plan.md §2.1
 */

export type OrderStatus = "PLACED" | "SHIPPING" | "PARTIALLY_RETURNED" | "CONFIRMED" | "CANCELED";

export interface Order {
  readonly orderId: string;
  readonly productId: string;
  readonly productName: string;
  readonly merchantId: string;
  readonly merchantName: string;
  readonly amount: number;
  readonly status: OrderStatus;
  readonly placedAt: string;
  /** 결제가 확정된 뒤에만 채워집니다. 확정 전에는 서버에 물어볼 ID가 없습니다. */
  readonly paymentId?: string;
}

const STORAGE_KEY = "paritypay.orders";

function read(storage: Storage): Order[] {
  const raw = storage.getItem(STORAGE_KEY);
  if (raw === null) {
    return [];
  }
  try {
    return JSON.parse(raw) as Order[];
  } catch {
    storage.removeItem(STORAGE_KEY);
    return [];
  }
}

export function listOrders(storage: Storage = localStorage): Order[] {
  return read(storage).sort((a, b) => b.placedAt.localeCompare(a.placedAt));
}

export function findOrder(orderId: string, storage: Storage = localStorage): Order | undefined {
  return read(storage).find((o) => o.orderId === orderId);
}

export function saveOrder(order: Order, storage: Storage = localStorage): void {
  const orders = read(storage).filter((o) => o.orderId !== order.orderId);
  orders.push(order);
  storage.setItem(STORAGE_KEY, JSON.stringify(orders));
}

/** 주문 하나를 만듭니다. `orderId`는 서버가 아니라 우리가 정합니다. */
export function placeOrder(
  input: Omit<Order, "orderId" | "status" | "placedAt">,
  storage: Storage = localStorage,
): Order {
  const order: Order = {
    ...input,
    orderId: `ord-${Date.now()}-${Math.floor(Math.random() * 1e4)}`,
    status: "PLACED",
    placedAt: new Date().toISOString(),
  };
  saveOrder(order, storage);
  return order;
}

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  PLACED: "결제 완료",
  SHIPPING: "배송 중",
  PARTIALLY_RETURNED: "부분 반품",
  CONFIRMED: "구매확정",
  CANCELED: "주문 취소",
};

/** 결제 상태를 사용자 문구로 옮깁니다. 서버 열거형을 그대로 보여 주지 않습니다. */
export function paymentStatusLabel(status: string | undefined): string {
  switch (status) {
    case "APPROVED":
      return "결제 승인";
    case "PARTIALLY_CANCELED":
      return "부분 취소";
    case "CANCELED":
      return "전액 취소";
    case "FAILED":
      return "결제 실패";
    case "PROCESSING":
    case "UNKNOWN":
      // 사용자에게 UNKNOWN이라고 쓰지 않습니다. 실패로도 쓰지 않습니다.
      return "결과 확인 중";
    default:
      return "결제 대기";
  }
}
