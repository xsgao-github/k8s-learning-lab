package xsgao.k8s.order.cart;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByUserIdOrderByIdAsc(String userId);

    Optional<CartItem> findByUserIdAndProductId(String userId, Long productId);
}
