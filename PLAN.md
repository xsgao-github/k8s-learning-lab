# Implementation Plan & Claude Code Prompts

Execute this plan module-by-module. Paste the prompt for each milestone into Claude Code, review the diff, run the verification step, and commit before moving to the next module.

---

<a id="module-0"></a>
## Module 0: Environment Bootstrap & Baseline Verification
**Focus:** Setting up local development tooling, build chains, and containerized runtime environments.
**Guide:** [docs/module-00-setup.md](docs/module-00-setup.md)

### Prompt to Use:
> "Initialize Module 0: Verify and establish baseline local environment tooling.
> 1. Verify Windows 11, Docker Desktop (Linux engine), Java 17+, Maven 3.9+, and `kind`/`kubectl` CLIs.
> 2. Create `infra/kind-config.yaml` with 1 control-plane node and 2 worker nodes; put the Ingress `extraPortMappings` for ports 80 and 443 on the first worker and label it `ingress-ready=true`.
> 3. Bootstrap the local `kind` cluster, install the NGINX Ingress Controller with the kind provider, and pin it to the `ingress-ready` worker.
> 4. Run a baseline smoke test ensuring cluster nodes are `Ready` and Ingress controller pods are running."

### Verification:
- `kind get clusters` shows `k8s-lab`.
- `kubectl get pods -n ingress-nginx` shows controller running.

---

<a id="module-1"></a>
## Module 1: Multi-Module Maven Skeleton & Shared Event Contracts
**Goal:** Establish the root parent POM, module structure, and immutable Java `record` event schemas.
**Guide:** [docs/module-01-common-events.md](docs/module-01-common-events.md)

### Prompt to Use:
> "Read `CLAUDE.md` and `README.md`. Initialize Module 1: create the root Maven `pom.xml` (Spring Boot 3.3.x, Java 17) with four backend modules (`common-events`, `order-service`, `payment-service`, `print-service`). In `common-events`, define Java `record` classes for `OrderPlacedEvent`, `PaymentProcessedEvent`, and `PrintCompletedEvent` (including `eventId`, `orderId`, `userId`, `totalAmount`, `status`, and `timestamp`). Verify the entire reactor builds cleanly with `mvn clean compile`."

### Verification:
- `mvn clean compile` succeeds across all modules.

---

<a id="module-2"></a>
## Module 2: PostgreSQL & the First Deployable Slice
**Goal:** Deliver a working catalog/cart API backed by PostgreSQL, containerized and deployed to `kind`. Order submission returns an explicit `501` until Module 3 adds Kafka and payment.
**Guide:** [docs/module-02-order-service.md](docs/module-02-order-service.md)

### Prompt to Use:
> "Implement Module 2:
> 1. In `order-service`, add Spring Web, Data JPA, PostgreSQL driver, Validation, and Actuator. Create `Product` (seed 5 samples via `CommandLineRunner`) and `CartItem` JPA entities/repositories. Implement `GET /api/products`, `GET /api/carts/{userId}`, `POST /api/carts/{userId}/items`, and `DELETE /api/carts/{userId}/items/{productId}`. Add `POST /api/orders` returning `501 Not Implemented` with a clear JSON error; do not add Kafka yet.
> 2. Enable Actuator liveness/readiness probes. Add a multi-stage non-root Dockerfile and build `order-service:0.1.0` with the repository root as the build context.
> 3. Create `infra/k8s/namespace.yaml`, `postgres.yaml` (Secret, StatefulSet with `volumeClaimTemplates`, and ClusterIP Service `postgres:5432`), `app-config.yaml`, `order-service.yaml` (Deployment with 2 replicas, `imagePullPolicy: IfNotPresent`, requests/limits, liveness/readiness probes, and ClusterIP Service), and `ingress.yaml` routing `/api` to `order-service:8081`.
> 4. Load the image into `kind`, apply the manifests, and verify the API through the Ingress."

### Verification:
- `mvn -q -pl order-service -am test` passes.
- `kubectl apply -f infra/k8s/namespace.yaml -f infra/k8s/app-config.yaml -f infra/k8s/postgres.yaml -f infra/k8s/order-service.yaml -f infra/k8s/ingress.yaml` brings PostgreSQL and `order-service` pods to Ready.
- `http://localhost/api/products` returns the seeded catalog; adding and removing cart items works and persists.
- `POST /api/orders` returns `501` with a clear message; no pod crashes.

---

<a id="module-3"></a>
## Module 3: Kafka, Order Placement & the Event Flow
**Goal:** Add Kafka to the cluster, complete order creation, and deploy the stateless payment and print consumers. The full event flow works in `kind`.
**Guide:** [docs/module-03-payment-print-service.md](docs/module-03-payment-print-service.md)

### Prompt to Use:
> "Implement Module 3:
> 1. Add `infra/k8s/kafka.yaml`: a single-node KRaft Kafka `StatefulSet` with a PVC and a headless/ClusterIP Service on `kafka:9092`.
> 2. In `order-service`, add Spring Kafka and the `Order` entity/repository. Implement `POST /api/orders`: convert the cart into an order in `PENDING_PAYMENT`, clear the cart, publish `OrderPlacedEvent` to `order.placed` keyed by `orderId`, and expose `GET /api/orders/{id}` for status polling. Add idempotent `@KafkaListener`s for `payment.processed` and `print.completed`. Configure JSON serializers/deserializers with type headers disabled.
> 3. In `payment-service`, listen to `order.placed` (group `payment-service-group`). Decline when `totalAmount > 1000`, otherwise succeed, and publish `PaymentProcessedEvent` to `payment.processed` keyed by `orderId`. Add a non-root Dockerfile and a Deployment with probes and resources.
> 4. In `print-service`, listen to `payment.processed` only when status is `SUCCEEDED` (group `print-service-group`). Log a structured receipt and publish `PrintCompletedEvent` to `print.completed`. Add a non-root Dockerfile and a Deployment with probes and resources.
> 5. Build and load all three images, apply the manifests, and verify the end-to-end flow."

### Verification:
- `mvn -q -pl order-service,payment-service,print-service -am test` passes.
- All pods are Ready; Kafka and order-service logs show successful publishing and consuming.
- A normal order reaches `PRINTED` through the Ingress; an order over `1000` ends `PAYMENT_FAILED`.
- `print-service` logs a receipt for the successful order.

---

<a id="module-4"></a>
## Module 4: React Frontend & Ingress
**Goal:** Build and deploy the browser client, then route `/` to it and `/api` to the backend through the existing Ingress.
**Guide:** [docs/module-04-frontend.md](docs/module-04-frontend.md)

### Prompt to Use:
> "Implement Module 4:
> 1. Create a Vite + React + TypeScript app in `frontend-web/`: product grid, cart drawer with add/remove/quantity controls, checkout, and polling of `GET /api/orders/{id}`.
> 2. Add a multi-stage Dockerfile (Node build, then an unprivileged nginx runtime on port 8080) and a Deployment/Service with probes and resources.
> 3. Extend `infra/k8s/ingress.yaml`: `/` routes to `frontend-web:8080` and `/api` routes to `order-service:8081`.
> 4. Build and load the frontend image, apply the manifests, and verify the browser flow."

### Verification:
- `http://localhost/` serves the UI; browse products, add to cart, and check out.
- The order status reaches `PRINTED` without manual API calls.
- All pods are Ready, and the Ingress routes both `/` and `/api`.

---

<a id="module-5"></a>
## Module 5: Resilience & Autoscaling
**Goal:** Practice day-two Kubernetes operations against the running platform.
**Guide:** [docs/module-05-resilience-autoscaling.md](docs/module-05-resilience-autoscaling.md)

### Prompt to Use:
> "Implement Module 5:
> 1. Install metrics-server in `kind` with `--kubelet-insecure-tls` if it is not present.
> 2. Add `infra/k8s/hpa.yaml` for `order-service` (min 2, max 5 replicas, target CPU 70%) and `infra/k8s/pdb.yaml` for the same Deployment.
> 3. Add a `preStop` hook and an explicit termination grace period to `order-service` so pod removal does not drop requests.
> 4. Write `scripts/module-05-smoke-test.ps1` that browses products, adds items to the cart, places a valid order and a `> 1000` declined order, polls `GET /api/orders/{id}` until the terminal status, and deletes an order-service pod under load.
> 5. Write `scripts/module-05-drills.ps1`, an interactive walkthrough that waits for Enter between the HPA scale-up and node-drain steps.
> 6. Verify scaling and disruption behavior under load."

### Verification:
- `kubectl get hpa` shows the order-service scaling toward the CPU target.
- Deleting an `order-service` pod during load produces no failed requests and the pod is recreated.
- A node drain respects the PDB and leaves at least one Ready replica.

---

<a id="module-6"></a>
## Module 6: Helm Packaging & Release Lifecycle
**Goal:** Package the complete platform as a Helm chart and learn release management. The raw manifests remain the reference implementation.

### Prompt to Use:
> "Implement Module 6:
> 1. Create an `infra/helm/coffee-platform/` chart (`Chart.yaml`, `values.yaml`, `templates/`) covering PostgreSQL, Kafka, the three services, the frontend, and the Ingress. Keep `infra/k8s/` unchanged as the reference.
> 2. Parameterize image tags, replicas, CPU/memory, service ports, and the Ingress host/path. Do not template Kubernetes Secrets with plaintext credentials in `values.yaml`; reference an existing Secret or document the required `--set` values.
> 3. Verify `helm lint` and `helm template` render valid objects.
> 4. Install into a clean namespace with `helm upgrade --install ... -n k8s-lab --create-namespace`, run the end-to-end order flow, then demonstrate `helm history`, an upgrade with a changed image tag or replica count, `helm rollback`, and `helm uninstall`."

### Verification:
- `helm lint` and `helm template` succeed.
- `helm install` brings all pods to Ready and an order reaches `PRINTED` through the Ingress.
- `helm history` shows the upgrade; `helm rollback` restores the previous revision.
- `helm uninstall` removes the release; reinstalling reproduces the same state.

---

<a id="module-7"></a>
## Module 7: CI Pipeline (GitHub Actions)
**Goal:** Automate the full path proved in Modules 1–6: build, test, images, ephemeral kind cluster, Helm deploy, smoke test. Validate the raw manifests statically, but deploy through Helm only — CI must not maintain two deployment paths. Use a GitHub-hosted Ubuntu runner with no cloud credentials.

### Prompt to Use:
> "Implement Module 7:
> 1. Create `.github/workflows/ci.yml` triggered on `push` and `pull_request`.
> 2. Steps: checkout; set up Java 17 with Maven cache; `mvn -q verify`; validate `infra/k8s/` with `kubectl apply --dry-run=client -f`; `helm lint` and `helm template`; install `kubectl`, `helm`, and `kind`; build all four images (three services and the frontend) tagged with the commit SHA; create the cluster from `infra/kind-config.yaml`; install ingress-nginx and pin the controller to the `ingress-ready=true` node; load the images; `helm upgrade --install` into a fresh namespace; wait for rollouts; run `scripts/smoke-test.sh`.
> 3. Add `timeout-minutes`, cache `~/.m2`, and on failure print `kubectl get pods`, `kubectl describe`, events, and pod logs.
> 4. Tag images immutably with `${{ github.sha }}`; never use `latest`."

### Verification:
- The workflow passes on a push to `main`.
- Raw `infra/k8s/` manifests pass client-side dry-run, and the chart passes lint/template; only the Helm release is deployed.
- A deliberately failing unit test fails the workflow at the test step; a broken readiness probe fails it at the rollout step.
- The deployed image tag matches the commit SHA.
- The smoke test confirms `PENDING_PAYMENT` -> `PAID` -> `PRINTED` inside the CI cluster.

---

<a id="module-8"></a>
## Module 8: GitOps with Argo CD
**Goal:** Deploy the Helm chart from Git and let Argo CD continuously reconcile the cluster toward the repository.

### Prompt to Use:
> "Implement Module 8:
> 1. Install Argo CD into the `argocd` namespace using the official stable manifest. Expose the UI with `kubectl port-forward` for the lab.
> 2. Create `infra/gitops/application.yaml`, an Argo CD `Application` pointing at `https://github.com/xsgao-github/k8s-learning-lab.git`, path `infra/helm/coffee-platform`, destination namespace `k8s-lab`, with automated sync, prune, and self-heal.
> 3. Apply the Application and wait for `Synced` and `Healthy`.
> 4. Demonstrate drift correction: delete a Deployment or scale it manually, then observe Argo CD restore it. Commit a values change, sync it, and revert through Git.
> 5. Keep CI as the validation gate and Argo CD as the deployment path. If the repository is private, register repository credentials as a Secret."

### Verification:
- The Application reports `Synced` and `Healthy`; the UI lists the chart's resources.
- Manually deleting or scaling a managed resource is corrected automatically.
- A committed chart or values change is deployed; reverting the commit restores the previous state.
- Removing a resource from Git prunes it from the cluster.
