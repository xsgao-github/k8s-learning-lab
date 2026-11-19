package xsgao.k8s.order.ordering;

import java.math.BigDecimal;

public record OrderLineResponse(
        Long productId,
        String name,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal
) {

    public static OrderLineResponse from(OrderLine line) {
        return new OrderLineResponse(
                line.getProductId(),
                line.getName(),
                line.getUnitPrice(),
                line.getQuantity(),
                line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())));
    }
}
