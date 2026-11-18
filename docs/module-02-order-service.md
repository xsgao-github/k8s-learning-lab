# Module 02: PostgreSQL & the First Deployable Slice

Deliver the product catalog and cart API on PostgreSQL, package it as a container, and deploy it to `kind`. Order placement deliberately returns `501` until Module 3 adds Kafka and payment.

---

## 1. Getting Started

### Build and Test

From the repository root:

```powershell
mvn -q -pl order-service -am test
```

### Build and Load the Image

```powershell
docker build -f order-service/Dockerfile -t order-service:0.1.0 .
kind load docker-image order-service:0.1.0 --name k8s-lab
```

The build context is the repository root so Docker can see the root POM and `common-events`.

### Deploy

```powershell
kubectl apply -f infra/k8s/namespace.yaml
kubectl apply -f infra/k8s/

kubectl rollout status statefulset/postgres -n k8s-lab --timeout=240s
kubectl rollout status deployment/order-service -n k8s-lab --timeout=300s
kubectl get pods -n k8s-lab
kubectl get ingress -n k8s-lab
```

Expected:

```text
postgres-0                      1/1 Running
order-service-... (2 replicas)  1/1 Running
coffee-platform                 nginx, localhost:80
```

### Verify

Run the Module 2 smoke test with `pwsh scripts/module-02-smoke-test.ps1` — it builds the image, deploys, and asserts the catalog, cart and PostgreSQL checks below. The current order-service build wires Kafka at startup, so the script starts Kafka as a dependency; it makes no Module 3 assertions. It accepts `501` from `POST /api/orders` (the Module 2 stub), or `201`/`409` when a later module has replaced the stub.

```powershell
# Catalog
curl.exe -s http://localhost/api/products

# Add two Espresso to user-1's cart
curl.exe -s -X POST http://localhost/api/carts/user-1/items `
  -H "Content-Type: application/json" `
  -d '{"productId":1,"quantity":2}'

# Read and remove
curl.exe -s http://localhost/api/carts/user-1
curl.exe -s -X DELETE http://localhost/api/carts/user-1/items/1

# Ordering is intentionally unavailable
curl.exe -s -i -X POST http://localhost/api/orders `
  -H "Content-Type: application/json" `
  -d '{"userId":"user-1"}'
```

`POST /api/orders` returns `501` with:

```json
{
  "title": "Order placement not available",
  "detail": "Order placement is enabled in Module 3."
}
```

---

## 2. Application

A request follows one path:

`HTTP → Controller → Service → Repository → Hibernate → PostgreSQL`

- **Controllers** map `/api/...` routes, validate request bodies, and return DTO records.
- **Services** own business rules and transactions. `ProductService` is read-only; `CartService` writes.
- **Repositories** are Spring Data interfaces; Hibernate turns their calls into SQL.
- **Entities** map to tables; DTOs keep persistence details out of HTTP responses.

### Code Layout

- `catalog/` — `Product`, repository, service, controller, and the five-product seed.
- `cart/` — `CartItem`, repository, service, controller, and request/response DTOs.
- `ordering/` — `OrderController`, currently returning `501`.
- `web/` — exception handling for 404 and 400 responses.

### Endpoints

| Method | Path | Behaviour |
|---|---|---|
| `GET` | `/api/products` | Seeded catalog |
| `GET` | `/api/carts/{userId}` | Cart contents and total |
| `POST` | `/api/carts/{userId}/items` | Add or increment a cart line |
| `DELETE` | `/api/carts/{userId}/items/{productId}` | Remove a cart line |
| `POST` | `/api/orders` | Deliberate `501` until Module 3 |

### Storage

- `products`: id, name, price.
- `cart_items`: id, user_id, product_id, quantity.
- One cart row per user/product, with a foreign key to `products`.
- Cart writes run in a transaction and return the recalculated cart.
- Products are seeded only when `products` is empty.
- Cart data lives in PostgreSQL, not in the pod.

### Tests

Eight tests: controller slices check routes, JSON, and validation; Mockito tests check cart business failures. No database is started.

---

## 3. Kubernetes Resources

| File | Objects |
|---|---|
| `namespace.yaml` | `Namespace k8s-lab` |
| `app-config.yaml` | `ConfigMap app-config` with `DB_URL` |
| `postgres.yaml` | `Secret`, `Service`, `StatefulSet`, PVC |
| `order-service.yaml` | `Deployment` (2 replicas), `Service` |
| `ingress.yaml` | `Ingress coffee-platform` |

- **Secret** `postgres-credentials`: `coffee` database/user and local password.
- **Service** `postgres`: stable name `postgres:5432`.
- **StatefulSet** `postgres`: stable pod `postgres-0` and a 1 Gi PVC; its init container fixes volume ownership for the non-root server.
- **Deployment** `order-service`: init container waits for PostgreSQL; probes use `/actuator/health/*`; runs as UID 10001 with a read-only root filesystem.
- **Ingress** `coffee-platform`: routes `/api` Prefix to `order-service:8081`.

---

## 4. Pod Operations

```powershell
# Status
kubectl get pods -n k8s-lab -o wide
kubectl get pods -n k8s-lab -w
kubectl get events -n k8s-lab --sort-by=.lastTimestamp

# Details and logs
kubectl describe pod <pod> -n k8s-lab
kubectl logs <pod> -n k8s-lab --tail=100
kubectl logs <pod> -n k8s-lab --tail=100 -f
kubectl logs -n k8s-lab -l app.kubernetes.io/name=order-service --tail=100

# Shell inside the running application
kubectl exec -it <order-service-pod> -n k8s-lab -- sh

# PostgreSQL shell
kubectl exec -it postgres-0 -n k8s-lab -- psql -U coffee -d coffee
# then: \dt   or   SELECT * FROM products;

# Direct access without Ingress
kubectl port-forward svc/order-service 8081:8081 -n k8s-lab

# Rollout state
kubectl rollout status deployment/order-service -n k8s-lab
kubectl rollout restart deployment/order-service -n k8s-lab
```

---

## 5. Common Pitfalls

- **Changing `POSTGRES_DB` has no effect on existing data.** PostgreSQL initializes only an empty data directory; delete the StatefulSet and PVC, then reapply. This deletes database data.
- **Editing a ConfigMap or Secret does not restart pods.** Environment variables are read at container start: run `kubectl rollout restart deployment/order-service -n k8s-lab`.
- **`POST /api/orders` returns `501`.** Expected; order placement is Module 3.
- **Cart data disappears after `-Recreate`.** That deletes the cluster and PVC. A normal pod restart preserves it.

---

## 6. Module 2 Checklist

- [ ] `mvn -q -pl order-service -am test` passes.
- [ ] The image is loaded into `kind`.
- [ ] PostgreSQL and both order-service replicas are Ready.
- [ ] Products and cart add/remove work through `http://localhost/api/...`.
- [ ] Cart contents survive an order-service pod restart.
- [ ] `POST /api/orders` returns `501` with the Module 3 message.

Next: [Module 3 — Kafka, Order Placement & the Event Flow](../PLAN.md#module-3).
