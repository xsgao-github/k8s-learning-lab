package xsgao.k8s.order.ordering;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PendingEventRepository extends JpaRepository<PendingEvent, Long> {

    boolean existsByOrderIdAndEventType(UUID orderId, PendingEventType eventType);

    List<PendingEvent> findByOrderIdAndEventType(UUID orderId, PendingEventType eventType);

    long deleteByOrderId(UUID orderId);
}
