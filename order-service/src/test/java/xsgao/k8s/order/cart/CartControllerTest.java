package xsgao.k8s.order.cart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CartController.class)
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CartService cartService;

    @Test
    void addsItemToCart() throws Exception {
        when(cartService.addItem(eq("user-1"), any(AddCartItemRequest.class)))
                .thenReturn(new CartResponse("user-1", List.of(), BigDecimal.ZERO));

        mockMvc.perform(post("/api/carts/user-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":2}"))
                .andExpect(status().isOk());
    }

    @Test
    void removesItemFromCart() throws Exception {
        when(cartService.removeItem("user-1", 1L))
                .thenReturn(new CartResponse("user-1", List.of(), BigDecimal.ZERO));

        mockMvc.perform(delete("/api/carts/user-1/items/1"))
                .andExpect(status().isOk());
    }
}
