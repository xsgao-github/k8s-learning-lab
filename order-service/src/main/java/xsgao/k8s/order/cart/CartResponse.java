package xsgao.k8s.order.cart;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(String userId, List<CartItemResponse> items, BigDecimal total) {
}
