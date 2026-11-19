package xsgao.k8s.order.ordering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String userId,
        BigDecimal totalAmount,
        OrderState status,
        Instant createdAt,
        Instant updatedAt,
        List<OrderLineResponse> lines
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getLines().stream().map(OrderLineResponse::from).toList());
    }
}
