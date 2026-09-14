/** 상품 목록 (DOC-15 §2). 상품은 프론트 고정 데이터입니다. */
import { useNavigate } from "react-router-dom";
import { PRODUCTS } from "../shop/catalog";
import { placeOrder } from "../shop/orders";
import { formatWon } from "../format";

/** 상품 사진 대신 쓰는 그림입니다. 카탈로그가 고정 데이터라 여기도 고정입니다. */
const ART: Record<string, string> = { "p-1": "☕", "p-2": "🫖", "p-3": "🖱️", "p-4": "⌨️", "p-5": "🔌" };

export function Products() {
  const navigate = useNavigate();

  return (
    <section className="page">
      <div className="page-head">
        <h1>상품</h1>
        <p>결제 데모입니다. 판매자 두 곳의 상품이 있고, 정산이 판매자별로 갈립니다.</p>
      </div>
      <ul className="products">
        {PRODUCTS.map((product) => (
          <li key={product.id} className="product">
            <div className="product__art" aria-hidden="true">
              {ART[product.id] ?? "📦"}
            </div>
            <small>{product.merchantName}</small>
            <strong className="product__name">{product.name}</strong>
            <span className="product__price">{formatWon(product.price)}</span>
            <button
              type="button"
              className="btn--primary"
              onClick={() => {
                const order = placeOrder({
                  productId: product.id,
                  productName: product.name,
                  merchantId: product.merchantId,
                  merchantName: product.merchantName,
                  amount: product.price,
                });
                navigate(`/orders/${order.orderId}`);
              }}
            >
              바로 구매
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
