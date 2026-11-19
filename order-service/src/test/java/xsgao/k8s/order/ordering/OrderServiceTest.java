package xsgao.k8s.order.ordering;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xsgao.k8s.events.OrderPlacedEvent;
import xsgao.k8s.events.OrderStatus;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.PaymentStatus;
import xsgao.k8s.events.PrintCompletedEvent;
import xsgao.k8s.events.Topics;
import xsgao.k8s.order.cart.CartItemResponse;
import xsgao.k8s.order.cart.CartResponse;
import xsgao.k8s.order.cart.CartService;
import xsgao.k8s.order.outbox.OutboxWriter;
import xsgao.k8s.order.web.EmptyCartException;
import xsgao.k8s.order.web.ResourceNotFoundException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final BigDecimal TOTAL = new BigDecimal("5.00");

    @Mock
    private OrderRepository orders;

    @Mock
    private CartService carts;

    @Mock
    private OutboxWriter outbox;

    @Mock
    private PendingEventStore pendingEvents;

    @InjectMocks
    private OrderService service;

    @Test
    void placesAnOrderAndAppendsTheEventToTheOutbox() {
        when(carts.getCart("user-1")).thenReturn(new CartResponse("user-1", List.of(
                new CartItemResponse(1L, "Espresso", new BigDecimal("2.50"), 2, TOTAL)), TOTAL));

        OrderResponse response = service.placeOrder("user-1");

        assertThat(response.status()).isEqualTo(OrderState.PENDING_PAYMENT);
        assertThat(response.totalAmount()).isEqualByComparingTo(TOTAL);
        assertThat(response.lines()).hasSize(1);
        verify(orders).save(any(Order.class));
        verify(carts).clear("user-1");

        ArgumentCaptor<Object> appended = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq(Topics.ORDER_PLACED), eq(response.id().toString()), appended.capture());

        OrderPlacedEvent event = (OrderPlacedEvent) appended.getValue();
        assertThat(event.orderId()).isEqualTo(response.id());
        assertThat(event.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(event.totalAmount()).isEqualByComparingTo(TOTAL);
    }

    @Test
    void rejectsAnEmptyCartWithoutSavingOrPublishing() {
        when(carts.getCart("user-1")).thenReturn(new CartResponse("user-1", List.of(), BigDecimal.ZERO));

        assertThatThrownBy(() -> service.placeOrder("user-1"))
                .isInstanceOf(EmptyCartException.class);

        verify(orders, never()).save(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void returnsAStoredOrder() {
        UUID orderId = UUID.randomUUID();
        when(orders.findById(orderId)).thenReturn(Optional.of(order(orderId, OrderState.PENDING_PAYMENT)));

        OrderResponse response = service.getOrder(orderId);

        assertThat(response.id()).isEqualTo(orderId);
        assertThat(response.lines()).hasSize(1);
    }

    @Test
    void rejectsAnUnknownOrder() {
        UUID orderId = UUID.randomUUID();
        when(orders.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder(orderId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void marksAPendingOrderPaidOnASuccessfulPayment() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderState.PENDING_PAYMENT);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        service.applyPaymentResult(payment(orderId, PaymentStatus.SUCCEEDED));

        assertThat(order.getStatus()).isEqualTo(OrderState.PAID);
        verify(pendingEvents).takePrintCompleted(orderId);
    }

    @Test
    void marksAPendingOrderFailedOnADeclinedPayment() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderState.PENDING_PAYMENT);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        service.applyPaymentResult(payment(orderId, PaymentStatus.FAILED));

        assertThat(order.getStatus()).isEqualTo(OrderState.PAYMENT_FAILED);
        verify(pendingEvents).discard(orderId);
    }

    @Test
    void ignoresADuplicatePaymentResult() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderState.PAID);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        service.applyPaymentResult(payment(orderId, PaymentStatus.SUCCEEDED));
        service.applyPaymentResult(payment(orderId, PaymentStatus.FAILED));

        assertThat(order.getStatus()).isEqualTo(OrderState.PAID);
    }

    @Test
    void ignoresAPaymentResultForAnUnknownOrder() {
        UUID orderId = UUID.randomUUID();
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

        service.applyPaymentResult(payment(orderId, PaymentStatus.SUCCEEDED));
    }

    @Test
    void marksAPaidOrderPrinted() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderState.PAID);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        service.applyPrintCompleted(printed(orderId));

        assertThat(order.getStatus()).isEqualTo(OrderState.PRINTED);
        verify(pendingEvents, never()).deferPrintCompleted(any(), any());
    }

    @Test
    void defersAPrintReceiptThatArrivesBeforePayment() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderState.PENDING_PAYMENT);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        PrintCompletedEvent receipt = printed(orderId);
        service.applyPrintCompleted(receipt);

        assertThat(order.getStatus())
                .as("printing must not be reachable before payment")
                .isEqualTo(OrderState.PENDING_PAYMENT);
        verify(pendingEvents).deferPrintCompleted(orderId, receipt);
    }

    @Test
    void settlesADeferredReceiptWhenPaymentSucceeds() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderState.PENDING_PAYMENT);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        PrintCompletedEvent receipt = printed(orderId);
        service.applyPrintCompleted(receipt);
        assertThat(order.getStatus()).isEqualTo(OrderState.PENDING_PAYMENT);

        when(pendingEvents.takePrintCompleted(orderId)).thenReturn(Optional.of(receipt));
        service.applyPaymentResult(payment(orderId, PaymentStatus.SUCCEEDED));

        assertThat(order.getStatus()).isEqualTo(OrderState.PRINTED);
    }

    @Test
    void discardsADeferredReceiptWhenPaymentFails() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderState.PENDING_PAYMENT);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        service.applyPrintCompleted(printed(orderId));
        service.applyPaymentResult(payment(orderId, PaymentStatus.FAILED));

        assertThat(order.getStatus()).isEqualTo(OrderState.PAYMENT_FAILED);
        verify(pendingEvents).discard(orderId);
    }

    @Test
    void ignoresAPrintReceiptForAnOrderThatWasNeverPaid() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderState.PAYMENT_FAILED);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        service.applyPrintCompleted(printed(orderId));

        assertThat(order.getStatus()).isEqualTo(OrderState.PAYMENT_FAILED);
        verify(pendingEvents, never()).deferPrintCompleted(any(), any());
    }

    @Test
    void ignoresADuplicatePrintReceipt() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderState.PRINTED);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        service.applyPrintCompleted(printed(orderId));

        assertThat(order.getStatus()).isEqualTo(OrderState.PRINTED);
        verify(pendingEvents, never()).deferPrintCompleted(any(), any());
    }

    @Test
    void ignoresAPrintReceiptForAnUnknownOrder() {
        UUID orderId = UUID.randomUUID();
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

        service.applyPrintCompleted(printed(orderId));
    }

    private static Order order(UUID orderId, OrderState state) {
        Order order = new Order(orderId, "user-1");
        order.addLine(1L, "Espresso", new BigDecimal("2.50"), 2);
        switch (state) {
            case PENDING_PAYMENT -> {
            }
            case PAID -> order.transitionTo(OrderState.PAID);
            case PAYMENT_FAILED -> order.transitionTo(OrderState.PAYMENT_FAILED);
            case PRINTED -> {
                order.transitionTo(OrderState.PAID);
                order.transitionTo(OrderState.PRINTED);
            }
        }
        return order;
    }

    private static PaymentProcessedEvent payment(UUID orderId, PaymentStatus status) {
        return new PaymentProcessedEvent(UUID.randomUUID(), orderId, "user-1", TOTAL, status, Instant.now());
    }

    private static PrintCompletedEvent printed(UUID orderId) {
        return new PrintCompletedEvent(UUID.randomUUID(), orderId, "user-1", TOTAL, OrderStatus.PRINTED, Instant.now());
    }
}
