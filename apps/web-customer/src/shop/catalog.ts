/**
 * 테스트 상품 목록 (DOC-15 §2).
 *
 * **백엔드에는 상품이라는 개념이 없습니다.** 결제 API는 `orderId`·`merchantId`·금액만 받으며,
 * 그 `orderId`는 클라이언트가 만듭니다. 그러니 상품 카탈로그는 화면이 결제의 맥락을 만들기 위한
 * 고정 데이터이고, 서버에 저장되지 않습니다. 있는 척하지 않기 위해 여기에 적어 둡니다.
 *
 * 근거: docs/16-ui-implementation-plan.md §2.1
 */
export interface Product {
  readonly id: string;
  readonly name: string;
  readonly price: number;
  readonly merchantId: string;
  readonly merchantName: string;
}

/** 판매자 두 곳을 둡니다. 정산이 판매자별로 갈린다는 것을 화면에서 보여 주기 위해서입니다. */
const MERCHANT_A = "3f1b7c64-9a2e-4c3d-8f11-5a7e2b9d4c60";
const MERCHANT_B = "6d2c8e51-4b7a-4e9f-9c22-1b8d3f6a5e71";

export const PRODUCTS: readonly Product[] = [
  { id: "p-1", name: "핸드드립 원두 200g", price: 18_000, merchantId: MERCHANT_A, merchantName: "테스트 로스터리" },
  { id: "p-2", name: "드립 서버 600ml", price: 24_000, merchantId: MERCHANT_A, merchantName: "테스트 로스터리" },
  { id: "p-3", name: "무선 마우스", price: 32_000, merchantId: MERCHANT_B, merchantName: "테스트 전자" },
  { id: "p-4", name: "기계식 키보드", price: 96_000, merchantId: MERCHANT_B, merchantName: "테스트 전자" },
  { id: "p-5", name: "USB-C 허브", price: 45_000, merchantId: MERCHANT_B, merchantName: "테스트 전자" },
];

export function findProduct(id: string): Product | undefined {
  return PRODUCTS.find((p) => p.id === id);
}
