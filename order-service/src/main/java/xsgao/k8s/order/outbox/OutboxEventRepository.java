package xsgao.k8s.order.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims the oldest unsent rows for publishing. {@code SKIP LOCKED} lets the
     * two order-service replicas run the relay concurrently without publishing the
     * same row twice; the lock is held until the relay's transaction commits.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE sent_at IS NULL
            ORDER BY id
            LIMIT :batch
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnsent(@Param("batch") int batch);

    long deleteBySentAtBefore(Instant cutoff);
}
