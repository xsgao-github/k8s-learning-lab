package xsgao.k8s.order.ordering;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import xsgao.k8s.events.PrintCompletedEvent;
import xsgao.k8s.events.Topics;

@Component
public class PrintCompletedListener {

    private final OrderService orders;

    public PrintCompletedListener(OrderService orders) {
        this.orders = orders;
    }

    @KafkaListener(
            topics = Topics.PRINT_COMPLETED,
            groupId = "order-service-group",
            containerFactory = "printCompletedListenerContainerFactory")
    public void onPrintCompleted(PrintCompletedEvent event) {
        orders.applyPrintCompleted(event);
    }
}
