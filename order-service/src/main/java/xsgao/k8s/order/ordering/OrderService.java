package xsgao.k8s.order.ordering;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xsgao.k8s.events.OrderPlacedEvent;
import xsgao.k8s.events.OrderStatus;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.PrintCompletedEvent;
import xsgao.k8s.events.Topics;
import xsgao.k8s.order.cart.CartItemResponse;
import xsgao.k8s.order.cart.CartResponse;
import xsgao.k8s.order.cart.CartService;
import xsgao.k8s.order.outbox.OutboxWriter;
import xsgao.k8s.order.web.EmptyCartException;
import xsgao.k8s.order.web.ResourceNotFoundException;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final CartService carts;
    private final OutboxWriter outbox;
    private final PendingEventStore pendingEvents;

    public OrderService(OrderRepository orders, CartService carts, OutboxWriter outbox,
                        PendingEventStore pendingEvents) {
        this.orders = orders;
        this.carts = carts;
        this.outbox = outbox;
        this.pendingEvents = pendingEvents;
    }

    /**
     * Converts the cart into an order and appends the event to the outbox in the
     * same transaction, so an event is published if and only if the order committed.
     */
    @Transactional
    public OrderResponse placeOrder(String userId) {
        CartResponse cart = carts.getCart(userId);
        if (cart.items().isEmpty()) {
            throw new EmptyCartException("Cart for user " + userId + " is empty.");
        }

        Order order = new Order(UUID.randomUUID(), userId);
        for (CartItemResponse item : cart.items()) {
            order.addLine(item.productId(), item.name(), item.unitPrice(), item.quantity());
        }
        orders.save(order);
        carts.clear(userId);

        outbox.append(Topics.ORDER_PLACED, order.getId().toString(), new OrderPlacedEvent(
                UUID.randomUUID(),
                order.getId(),
                userId,
                order.getTotalAmount(),
                OrderStatus.PENDING_PAYMENT,
                Instant.now()));

        return OrderResponse.from(order);
    }

    public OrderResponse getOrder(UUID orderId) {
        return orders.findById(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " was not found."));
    }

    /**
     * Applies a payment result exactly once. A duplicate, stale or contradictory
     * event leaves the order untouched and is acknowledged.
     */
    @Transactional
    public void applyPaymentResult(PaymentProcessedEvent event) {
        Order order = orders.findByIdForUpdate(event.orderId()).orElse(null);
        if (order == null) {
            log.warn("Ignoring payment.processed for unknown orderId={} eventId={}",
                    event.orderId(), event.eventId());
            return;
        }

        OrderState target = switch (event.status()) {
            case SUCCEEDED -> OrderState.PAID;
            case FAILED -> OrderState.PAYMENT_FAILED;
        };

        if (!order.transitionTo(target)) {
            log.info("Ignoring payment.processed for orderId={} currentState={} paymentStatus={}",
                    order.getId(), order.getStatus(), event.status());
            return;
        }

        if (target == OrderState.PAID) {
            settleDeferredPrint(order);
        } else {
            pendingEvents.discard(order.getId());
        }
    }

    /**
     * Printing is only legal from PAID. A receipt that arrives while the order is
     * still awaiting payment is deferred, with its original payload, and replayed
     * once the payment result has been applied.
     *
     * <p>This is a narrow, local workaround for a single ordering hazard. A production
     * flow would be a saga with an orchestrator that owns step timeouts and
     * compensations; see {@code docs/module-03-payment-print-service.md}.
     */
    @Transactional
    public void applyPrintCompleted(PrintCompletedEvent event) {
        Order order = orders.findByIdForUpdate(event.orderId()).orElse(null);
        if (order == null) {
            log.warn("Ignoring print.completed for unknown orderId={} eventId={}",
                    event.orderId(), event.eventId());
            return;
        }

        if (order.transitionTo(OrderState.PRINTED)) {
            return;
        }

        if (order.getStatus() == OrderState.PENDING_PAYMENT) {
            pendingEvents.deferPrintCompleted(order.getId(), event);
            log.info("Deferred print.completed until payment settles orderId={}", order.getId());
            return;
        }

        log.info("Ignoring print.completed for orderId={} currentState={}",
                order.getId(), order.getStatus());
    }

    private void settleDeferredPrint(Order order) {
        if (pendingEvents.takePrintCompleted(order.getId()).isPresent()
                && order.transitionTo(OrderState.PRINTED)) {
            log.info("Applied deferred print.completed orderId={}", order.getId());
        }
    }
}
