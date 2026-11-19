package xsgao.k8s.order.ordering;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.Topics;

@Component
public class PaymentProcessedListener {

    private final OrderService orders;

    public PaymentProcessedListener(OrderService orders) {
        this.orders = orders;
    }

    @KafkaListener(
            topics = Topics.PAYMENT_PROCESSED,
            groupId = "order-service-group",
            containerFactory = "paymentProcessedListenerContainerFactory")
    public void onPaymentProcessed(PaymentProcessedEvent event) {
        orders.applyPaymentResult(event);
    }
}
