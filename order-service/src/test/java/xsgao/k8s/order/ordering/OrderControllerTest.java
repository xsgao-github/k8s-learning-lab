package xsgao.k8s.order.ordering;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import xsgao.k8s.order.web.EmptyCartException;
import xsgao.k8s.order.web.ResourceNotFoundException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orders;

    @Test
    void placesAnOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orders.placeOrder("user-1")).thenReturn(order(orderId));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/orders/" + orderId)))
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(5.00));
    }

    @Test
    void returnsAnOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orders.getOrder(orderId)).thenReturn(order(orderId));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.lines[0].name").value("Espresso"));
    }

    @Test
    void returnsNotFoundForAnUnknownOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orders.getOrder(orderId))
                .thenThrow(new ResourceNotFoundException("Order " + orderId + " was not found."));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void rejectsAnEmptyCart() throws Exception {
        when(orders.placeOrder("user-1"))
                .thenThrow(new EmptyCartException("Cart for user user-1 is empty."));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order rejected"))
                .andExpect(jsonPath("$.detail").value("Cart for user user-1 is empty."));
    }

    @Test
    void rejectsBlankUserId() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    private static OrderResponse order(UUID orderId) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new OrderResponse(orderId, "user-1", new BigDecimal("5.00"), OrderState.PENDING_PAYMENT,
                now, now,
                List.of(new OrderLineResponse(1L, "Espresso", new BigDecimal("2.50"), 2,
                        new BigDecimal("5.00"))));
    }
}
