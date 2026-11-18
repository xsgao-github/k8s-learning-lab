package xsgao.k8s.order.cart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xsgao.k8s.order.catalog.Product;
import xsgao.k8s.order.catalog.ProductRepository;
import xsgao.k8s.order.web.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartItemRepository cartItems;

    @Mock
    private ProductRepository products;

    @InjectMocks
    private CartService cartService;

    @Test
    void savesANewCartItem() {
        Product product = new Product("Espresso", new BigDecimal("2.50"));
        when(products.findById(1L)).thenReturn(Optional.of(product));
        when(cartItems.findByUserIdAndProductId(eq("user-1"), isNull()))
                .thenReturn(Optional.empty());
        when(cartItems.findByUserIdOrderByIdAsc("user-1")).thenReturn(List.of());

        CartResponse response = cartService.addItem("user-1", new AddCartItemRequest(1L, 2));

        verify(cartItems).save(any(CartItem.class));
        assertThat(response.userId()).isEqualTo("user-1");
        assertThat(response.items()).isEmpty();
    }

    @Test
    void rejectsAnUnknownProduct() {
        when(products.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.addItem("user-1", new AddCartItemRequest(99L, 1)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void rejectsRemovingAnItemThatIsNotInTheCart() {
        when(cartItems.findByUserIdAndProductId("user-1", 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.removeItem("user-1", 99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }
}
