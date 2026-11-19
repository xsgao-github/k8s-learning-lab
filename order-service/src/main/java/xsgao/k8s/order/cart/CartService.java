package xsgao.k8s.order.cart;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xsgao.k8s.order.catalog.Product;
import xsgao.k8s.order.catalog.ProductRepository;
import xsgao.k8s.order.web.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CartService {

    private final CartItemRepository cartItems;
    private final ProductRepository products;

    public CartService(CartItemRepository cartItems, ProductRepository products) {
        this.cartItems = cartItems;
        this.products = products;
    }

    public CartResponse getCart(String userId) {
        List<CartItemResponse> items = cartItems.findByUserIdOrderByIdAsc(userId).stream()
                .map(CartItemResponse::from)
                .toList();
        BigDecimal total = items.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(userId, items, total);
    }

    @Transactional
    public CartResponse addItem(String userId, AddCartItemRequest request) {
        Product product = products.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product " + request.productId() + " was not found."));

        CartItem item = cartItems.findByUserIdAndProductId(userId, product.getId())
                .map(existing -> {
                    existing.addQuantity(request.quantity());
                    return existing;
                })
                .orElseGet(() -> new CartItem(userId, product, request.quantity()));

        cartItems.save(item);
        return getCart(userId);
    }

    @Transactional
    public CartResponse removeItem(String userId, Long productId) {
        CartItem item = cartItems.findByUserIdAndProductId(userId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product " + productId + " is not in the cart for user " + userId + "."));

        cartItems.delete(item);
        return getCart(userId);
    }

    @Transactional
    public void clear(String userId) {
        cartItems.deleteByUserId(userId);
    }
}
