/** 상품 목록 (DOC-15 §2). 상품은 프론트 고정 데이터입니다. */
import { useNavigate } from "react-router-dom";
import { PRODUCTS } from "../shop/catalog";
import { placeOrder } from "../shop/orders";
import { formatWon } from "../format";

export function Products() {
  const navigate = useNavigate();

  return (
    <section>
      <h1>상품</h1>
      <ul>
        {PRODUCTS.map((product) => (
          <li key={product.id}>
            <strong>{product.name}</strong>
            <span> {formatWon(product.price)}</span>
            <small> {product.merchantName}</small>
            <button
              type="button"
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
