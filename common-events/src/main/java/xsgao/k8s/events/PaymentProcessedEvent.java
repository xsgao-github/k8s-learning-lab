package xsgao.k8s.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by payment-service after attempting to authorize an order.
 */
public record PaymentProcessedEvent(
        UUID eventId,
        UUID orderId,
        String userId,
        BigDecimal totalAmount,
        PaymentStatus status,
        Instant timestamp
) {
}
