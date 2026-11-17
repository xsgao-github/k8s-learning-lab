package xsgao.k8s.events;

/**
 * Kafka topic names shared by every service. Keeping them here stops a producer
 * and its consumers from silently drifting to different topic names.
 */
public final class Topics {

    public static final String ORDER_PLACED = "order.placed";
    public static final String PAYMENT_PROCESSED = "payment.processed";
    public static final String PRINT_COMPLETED = "print.completed";

    private Topics() {
    }
}
