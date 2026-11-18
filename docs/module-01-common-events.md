# Module 01: Maven Skeleton & Shared Event Contracts

In this module we establish the Maven reactor, keep shared event contracts framework-free, and verify the Java 17 build before service logic exists.

---

## 1. Getting Started

```powershell
java -version
mvn -version
mvn -q clean compile
```

---

## 2. Structure

```text
pom.xml
common-events/       shared contracts (xsgao.k8s.events)
order-service/       depends on common-events
payment-service/
print-service/
```

The root POM is the reactor. It inherits `spring-boot-starter-parent` for dependency and plugin management while keeping our coordinates:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.13</version>
</parent>

<groupId>xsgao</groupId>
<artifactId>k8s-learning-lab</artifactId>
<packaging>pom</packaging>

<modules>
    <module>common-events</module>
    <module>order-service</module>
    <module>payment-service</module>
    <module>print-service</module>
</modules>
```

- The Spring parent is inherited build configuration, not project ownership.
- Service modules depend on `common-events`; Spring Web/JPA/Kafka arrive in Modules 2–3.
- Maven group ID is `xsgao`; all Java packages start with `xsgao.k8s`.

---

## 3. Event Contracts

All contracts live in `xsgao.k8s.events`:

```java
public record OrderPlacedEvent(UUID eventId, UUID orderId, String userId,
        BigDecimal totalAmount, OrderStatus status, Instant timestamp) {}

public record PaymentProcessedEvent(UUID eventId, UUID orderId, String userId,
        BigDecimal totalAmount, PaymentStatus status, Instant timestamp) {}

public record PrintCompletedEvent(UUID eventId, UUID orderId, String userId,
        BigDecimal totalAmount, OrderStatus status, Instant timestamp) {}

public enum OrderStatus { PENDING_PAYMENT, PAID, PAYMENT_FAILED, PRINTED }
public enum PaymentStatus { SUCCEEDED, FAILED }
```

| Event | Producer | Consumer | Status |
|---|---|---|---|
| `OrderPlacedEvent` | `order-service` | `payment-service` | `PENDING_PAYMENT` |
| `PaymentProcessedEvent` | `payment-service` | `order-service`, `print-service` | `SUCCEEDED` / `FAILED` |
| `PrintCompletedEvent` | `print-service` | `order-service` | `PRINTED` |

- `orderId` is the Kafka message key, so all events for one order share a partition.
- `BigDecimal` avoids rounding in money values; `Instant` is UTC.
- Records are immutable and carry no Spring/Jackson annotations. Services configure JSON serialization in Modules 2–3.

---

## 4. Module 1 Checklist

- [ ] `mvn -q clean compile` succeeds.
- [ ] The reactor contains all four modules.
- [ ] Records and enums live in `xsgao.k8s.events`.
- [ ] No `com.example` references remain.
- [ ] `mvn -q -DskipTests package` produces one JAR per module.

Next: [Module 2 — PostgreSQL & Order Service](module-02-order-service.md).
