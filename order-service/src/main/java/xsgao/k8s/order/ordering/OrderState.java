package xsgao.k8s.order.ordering;

/**
 * Persisted order lifecycle. Deliberately separate from
 * {@code xsgao.k8s.events.OrderStatus} so that renaming an event contract value
 * can never silently rewrite values already stored in PostgreSQL.
 */
public enum OrderState {
    PENDING_PAYMENT,
    PAID,
    PAYMENT_FAILED,
    PRINTED;

    /**
     * The strict lifecycle: an order can only become PAID or PAYMENT_FAILED from
     * PENDING_PAYMENT, and only become PRINTED from PAID. Printing is therefore
     * unreachable before payment, structurally rather than by convention.
     */
    public boolean follows(OrderState previous) {
        return switch (previous) {
            case PENDING_PAYMENT -> this == PAID || this == PAYMENT_FAILED;
            case PAID -> this == PRINTED;
            case PAYMENT_FAILED, PRINTED -> false;
        };
    }
}
