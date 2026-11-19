# Module 03: Kafka, Order Placement & the Event Flow

Replace the `501` with real order placement, run a single-node KRaft Kafka in `kind`, and add the stateless payment and print consumers. A normal order now travels `PENDING_PAYMENT -> PAID -> PRINTED`; one over `1000` ends `PAYMENT_FAILED`.

---

## 1. Event Flow

```text
POST /api/orders
      │  one PostgreSQL transaction
      ▼
 order-service ──► orders + order_lines + outbox
      │                              │
      │                       OutboxRelay every 500 ms
      │                              │
      └──────── order.placed ────────┘  key = orderId
                     │
                     ▼
              payment-service ──► payment.processed   key = orderId
                     │                    │
                     │                    ├──► print-service ──► print.completed
                     │                    │                          │
                     └────────────────────┴──► order-service ◄───────┘
                                                 applies the state change
```

| Topic | Partitions | Produced by | Consumed by | Consumer group |
|---|---|---|---|---|
| `order.placed` | 3 | `order-service` | `payment-service` | `payment-service-group` |
| `payment.processed` | 3 | `payment-service` | `order-service`, `print-service` | `order-service-group`, `print-service-group` |
| `print.completed` | 3 | `print-service` | `order-service` | `order-service-group` |

Every event is keyed by `orderId`, so all events for one order land on the same partition and are processed in order. Topics are declared by the service that produces them (`NewTopic` beans, 3 partitions, replication factor 1); the broker has auto-creation disabled so a missing topic is a loud failure instead of a silent one-partition topic.

JSON is used everywhere with **type headers disabled**: `JsonSerializer#setAddTypeInfo(false)` and `JsonDeserializer<>(Event.class, objectMapper, false)`. The producer and every consumer share the application `ObjectMapper`, so `BigDecimal` and `Instant` round-trip as `5.00` and `2026-01-01T00:00:00Z`.

## 2. The Transactional Outbox

`order-service` writes PostgreSQL and Kafka, and there is no shared transaction between them. Publishing directly from the request thread leaves a window: the order commits, the process dies before the send, and the order is stuck in `PENDING_PAYMENT` forever.

The outbox closes that window:

1. `placeOrder` saves the order **and** an `outbox` row (`aggregate_id`, `topic`, `payload`, `created_at`, `sent_at`) in one transaction. An event exists if and only if the order committed.
2. `OutboxRelay` runs every 500 ms. It claims up to 100 unsent rows with `SELECT ... FOR UPDATE SKIP LOCKED` — which also keeps the two `order-service` replicas from publishing the same row — sends them in id order, and marks each `sent_at`.
3. A failed send stops the batch before that row is marked, so the next tick retries it. Nothing is skipped or reordered.
4. Rows whose `sent_at` is older than one hour are deleted.

Delivery is therefore **at least once**. Duplicates are made harmless by the consumers (next section), and duplicate receipt lines remain possible — this is documented, not hidden.

## 3. Idempotency

The `orders` row is the deduplication record. Every listener applies a transition only from a state it is allowed to come from:

| current state | `payment.processed` SUCCEEDED | `payment.processed` FAILED | `print.completed` |
|---|---|---|---|
| `PENDING_PAYMENT` | → `PAID` | → `PAYMENT_FAILED` | deferred to `pending_events` |
| `PAID` | no-op | no-op | → `PRINTED` |
| `PAYMENT_FAILED` | no-op | no-op | no-op |
| `PRINTED` | no-op | no-op | no-op |

Printing is only reachable from `PAID` (`OrderState.follows` owns the legal edges, so no caller can bypass payment). The two topics are consumed independently, though, so a receipt can arrive before the payment result has been applied. Rather than accepting an illegal `PENDING_PAYMENT -> PRINTED` jump, `order-service` writes the event to the `pending_events` side table — order id, event type, and the original JSON payload — and acknowledges the Kafka record. When the payment later succeeds, the same transaction applies the deferred receipt, transitions the order to `PRINTED`, and deletes the row. A declined payment discards anything deferred for that order, because a receipt can never follow.

Both listeners load the order with `SELECT ... FOR UPDATE` (`OrderRepository.findByIdForUpdate`). Without that row lock the two consumers can interleave: payment reads `pending_events` while print is still inserting into it, sees nothing, commits `PAID`, and the deferred receipt is never applied.

Unknown order ids and stale transitions are logged and acknowledged, never thrown — retrying cannot help, and an exception would block the partition.

`payment-service` and `print-service` hold no state: the payment decision is deterministic, so a redelivered `order.placed` produces the same result and the order-state guards absorb it.

### Scope: this is a lab-sized workaround

`pending_events` is deliberately narrow. It handles one hazard (a receipt that beats its payment result) for one event type, with no timeouts and no compensation logic. It is a local deferral queue inside the order aggregate, not a distributed-transaction coordinator: a row whose payment result never arrives simply stays there — visible in the table, but with no automatic recovery. The outbox and the side table together also cannot express "undo the payment because printing failed".

**In production this flow would be modelled as a saga driven by an orchestrator** (a process manager), which is the orchestration counterpart of the choreography used here:

- it persists the process state explicitly — `PAYMENT_REQUESTED -> PAYMENT_CONFIRMED -> PRINT_REQUESTED -> COMPLETED`, plus `COMPENSATING`/`CANCELLED` branches — instead of inferring progress from `orders.status`;
- it emits commands (for example `process-payment`, `print-receipt`) and consumes replies, so step timeouts, retries and duplicate replies are handled once, in the process definition;
- failures trigger compensations, such as refunding and cancelling when printing fails after a successful payment — something neither the outbox nor this side table can represent;
- the ordering hazard moves out of the consumers: the orchestrator decides when a step may run, so a reply that arrives early is matched to its saga instead of being parked per event.

Typical choices are Temporal, Camunda/Zeebe, Axon, or a hand-rolled process-manager service backed by its own state table. A saga does **not** replace the other two mechanisms: the transactional outbox is still needed so a command is published atomically with the state change, and every consumer must still be idempotent because at-least-once delivery is unchanged.

## 4. Build and Test

```powershell
mvn -q -pl order-service,payment-service,print-service -am test
```

## 5. Build and Load Images

The build context is the repository root for all three services.

```powershell
docker build -f order-service/Dockerfile   -t order-service:0.1.0 .
docker build -f payment-service/Dockerfile -t payment-service:0.1.0 .
docker build -f print-service/Dockerfile   -t print-service:0.1.0 .

kind load docker-image order-service:0.1.0 payment-service:0.1.0 print-service:0.1.0 --name k8s-lab
```

## 6. Deploy

Kafka first, then the services that wait for it.

```powershell
kubectl apply -f infra/k8s/namespace.yaml
kubectl apply -f infra/k8s/app-config.yaml -f infra/k8s/postgres.yaml -f infra/k8s/kafka.yaml
kubectl rollout status statefulset/kafka -n k8s-lab --timeout=300s

kubectl apply -f infra/k8s/order-service.yaml -f infra/k8s/payment-service.yaml -f infra/k8s/print-service.yaml -f infra/k8s/ingress.yaml
kubectl rollout status deployment/order-service   -n k8s-lab --timeout=300s
kubectl rollout status deployment/payment-service -n k8s-lab --timeout=300s
kubectl rollout status deployment/print-service   -n k8s-lab --timeout=300s

kubectl get pods -n k8s-lab -o wide
```

## 7. Smoke Test

Run the Module 3 smoke test with `pwsh scripts/module-03-smoke-test.ps1` — it builds the images, deploys, and asserts every check below. The commands are listed here so each step is also runnable by hand. The smoke test also covers the out-of-order case: it stops `payment-service`, injects a `print.completed` for a still-pending order, asserts it was deferred rather than applied, then restarts payment and asserts the deferred receipt is applied and its row removed. It always restores the `payment-service` replica count in a `finally` block.

Kafka stores data on the PVC and exposes the expected topics:

```powershell
kubectl exec -n k8s-lab kafka-0 -- ls -l /var/lib/kafka/data
kubectl exec -n k8s-lab kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9094 --list
kubectl exec -n k8s-lab kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9094 --describe --topic order.placed
```

A normal order should reach `PRINTED`:

```powershell
curl.exe -s -X POST http://localhost/api/carts/user-1/items -H "Content-Type: application/json" -d '{"productId":1,"quantity":2}'
$order = curl.exe -s -X POST http://localhost/api/orders -H "Content-Type: application/json" -d '{"userId":"user-1"}' | ConvertFrom-Json
$order | ConvertTo-Json -Depth 4

for ($i = 0; $i -lt 30; $i++) {
    $current = curl.exe -s "http://localhost/api/orders/$($order.id)" | ConvertFrom-Json
    Write-Host "status=$($current.status)"
    if ($current.status -in @('PRINTED', 'PAYMENT_FAILED')) { break }
    Start-Sleep -Seconds 2
}
```

An order over `1000` should end `PAYMENT_FAILED` (`2.50 x 401 = 1002.50`):

```powershell
curl.exe -s -X POST http://localhost/api/carts/user-big/items -H "Content-Type: application/json" -d '{"productId":1,"quantity":401}'
$big = curl.exe -s -X POST http://localhost/api/orders -H "Content-Type: application/json" -d '{"userId":"user-big"}' | ConvertFrom-Json

for ($i = 0; $i -lt 30; $i++) {
    $current = curl.exe -s "http://localhost/api/orders/$($big.id)" | ConvertFrom-Json
    Write-Host "status=$($current.status)"
    if ($current.status -in @('PRINTED', 'PAYMENT_FAILED')) { break }
    Start-Sleep -Seconds 2
}
```

An empty cart is rejected before anything is published:

```powershell
curl.exe -s -i -X POST http://localhost/api/orders -H "Content-Type: application/json" -d '{"userId":"nobody"}'
# HTTP/1.1 409 ... title "Order rejected"
```

The receipt, the relay, and the outbox draining:

```powershell
kubectl logs -n k8s-lab -l app.kubernetes.io/name=print-service --tail=50 | Select-String "RECEIPT"
kubectl logs -n k8s-lab -l app.kubernetes.io/name=order-service --tail=50 | Select-String "outbox"
kubectl logs -n k8s-lab -l app.kubernetes.io/name=payment-service --tail=50 | Select-String "Payment decision"

kubectl exec -n k8s-lab postgres-0 -- psql -U coffee -d coffee -tAc "select count(*) from outbox where sent_at is null"
# expect 0
```

Events on the wire, bounded so nothing streams forever:

```powershell
kubectl exec -n k8s-lab kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server localhost:9094 --topic payment.processed --from-beginning `
  --max-messages 2 --timeout-ms 15000 --property print.key=true --property key.separator=:
```

The key printed before each payload is the `orderId`.

## 8. Notes and Troubleshooting

- **The `LOCAL` listener exists for in-pod clients.** Kafka advertises `PLAINTEXT://kafka:9092`, which resolves to the Service — and a Service has no endpoints until its pod is Ready. A probe or CLI that bootstraps against `localhost:9092` is redirected to `kafka:9092` and deadlocks. Probes and the `kubectl exec` commands above therefore use `localhost:9094`.
- **`CLUSTER_ID` is permanent for a volume.** Changing it makes the broker refuse to start. To reset Kafka, delete the PVC (`kubectl delete pvc data-kafka-0 -n k8s-lab`).
- **Kafka heap** is pinned to `-Xms256m -Xmx512m`, and the services use `-XX:MaxRAMPercentage=75.0` against their limits. Docker Desktop needs roughly 8 GB for Kafka, PostgreSQL, and three JVMs (two of them `order-service`).
- **Cold start** can cost one restart of a service: the init container only proves TCP reachability, and `spring.kafka.admin.fail-fast=true` turns a lost metadata race into a restart rather than a silently created topic.
- **A rebuilt image with the same tag** needs both `kind load docker-image ...` and `kubectl rollout restart deployment/<name> -n k8s-lab`; `imagePullPolicy: IfNotPresent` keeps the old pods otherwise.
- **Schema** is created by Hibernate (`ddl-auto: update`) on first startup: `orders`, `order_lines`, and `outbox`. This is convenient for the lab and is not a substitute for migrations.
