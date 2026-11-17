# CLAUDE.md — Engineering Rules & Context

## Role & Workflow
You are acting as a senior backend/platform pair-programmer. Follow a disciplined, incremental workflow:
- Never generate all services in one giant dump. Implement one vertical slice or phase from `PLAN.md` at a time.
- Explain architectural trade-offs briefly before writing code, and verify builds (`mvn test`) and manifests (`kubectl apply --dry-run=client`) after changes.
- All code is subject to strict human review; prioritize clarity, explicitness, and production-grade reliability over clever shortcuts.

## Token Discipline
The user pays for every token; default to the smallest sufficient action.
- **One module per request.** Never scaffold future modules, services, endpoints, or docs; build only what the current `PLAN.md` module needs.
- **Minimal context reads.** Read only the files or ranges required; never re-read a file after editing it; prefer `Grep`/`Glob` over broad directory reads.
- **Edit, don't re-emit.** Use targeted edits and show only the changed lines; never print whole files or unchanged code back to the user.
- **Scoped verification.** Java: `mvn -q -pl <module> -am test`. Images: `mvn -q -DskipTests package`. Manifests: `kubectl apply --dry-run=client -f <file>`. Do not run the full reactor, rebuild all images, or touch the cluster unless the change requires it.
- **Filter cluster output.** Use `kubectl get ... -o wide`, `kubectl logs --tail=100 --since=10m`, and bounded Kafka consumers (`--max-messages`). Never stream or dump full logs into the conversation.
- **Summarize failures.** Quote only the relevant error lines and state the fix; report success in a few lines.
- **Stay on task.** No unsolicited refactors, doc rewrites, architecture recaps, or "while I'm here" changes. Choose the smallest reasonable default; ask only when blocked.
- **One clear path.** Implement and document a single default workflow. Do not add optional flags, alternative setup paths, or "just in case" variants unless explicitly requested.
- **Reuse before writing.** Prefer existing utilities, manifests, and upstream images over new files or dependencies.

## Environment & Tooling
- **OS/Shell:** Windows 11 Pro with Docker Desktop (Linux engine); run `kind` and `kubectl` from PowerShell. WSL is not required.
- **Build System:** Multi-module Maven (`pom.xml` at root with `common-events`, `order-service`, `payment-service`, `print-service`).
- **Coordinates & packages:** Maven `groupId` is `xsgao`; all Java packages start with `xsgao.k8s`.
- **Java Standards:**
  - Use Java 17 features (`record` for DTOs and Kafka events, `sealed` interfaces where appropriate, `var` only when type is obvious).
  - Constructor injection only (no `@Autowired` field injection).
  - Strict separation of JPA `@Entity` models from REST DTOs and Kafka event payloads.

## Architecture & Messaging Rules
- **Single Database Rule:** Only `order-service` connects directly to PostgreSQL. `payment-service` and `print-service` are stateless event processors communicating strictly via Kafka.
- **Idempotency & Ordering:**
  - Partition all order-related Kafka topics by `orderId` (as the Kafka message key) so events for the same order are processed in strict sequence.
  - Consumers must handle duplicate deliveries safely (e.g., check current order state before transitioning `PENDING_PAYMENT` -> `PAID` -> `PRINTED`).
- **Serialization:** Use JSON serialization (`JsonSerializer` / `JsonDeserializer`) with explicit type headers disabled or mapped cleanly via `common-events` DTOs to avoid brittle class-name coupling.

## Docker & Kubernetes Standards
- **Dockerfiles:** Multi-stage builds using `eclipse-temurin:17-jdk-jammy` for build and `eclipse-temurin:17-jre-jammy` for runtime. Run containers as a non-root user.
- **JVM Memory Sizing:** Always configure explicit container memory limits in K8s manifests (`resources.requests` and `resources.limits`, e.g., `512Mi`) and set `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"` to prevent `OOMKilled` pods.
- **Probes:** Every Spring Boot service must expose `/actuator/health/liveness` and `/actuator/health/readiness` (`management.endpoint.health.probes.enabled=true`) and configure matching `livenessProbe` and `readinessProbe` in K8s `Deployment` YAML.
- **Configuration:** Zero hardcoded hosts, ports, or credentials. Inject via Kubernetes `ConfigMap` and `Secret` (`envFrom` or `valueFrom`).
- **Ingress:** ingress-nginx runs on the first worker (`ingress-ready=true`), where `kind` maps host ports 80/443. Do not move it to the control-plane node.
- **Image Pull Policy:** Set `imagePullPolicy: IfNotPresent` on local service deployments so `kind load docker-image` images are used without attempting external registry pulls.