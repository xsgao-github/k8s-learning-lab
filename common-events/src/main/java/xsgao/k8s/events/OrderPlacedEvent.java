package xsgao.k8s.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by order-service when a new order is accepted for payment.
 */
public record OrderPlacedEvent(
        UUID eventId,
        UUID orderId,
        String userId,
        BigDecimal totalAmount,
        OrderStatus status,
        Instant timestamp
) {
}
