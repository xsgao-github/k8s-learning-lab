# Module 05: Resilience & Autoscaling

Add metrics-server, an HPA and a PodDisruptionBudget to the running platform, and make pod removal graceful. Then prove the behaviour with an automated smoke test and an interactive drill runner.

---

## 1. What this module adds

| Object | Purpose |
|---|---|
| `metrics-server` (kube-system) | Supplies CPU/memory metrics to the HPA (`v1beta1.metrics.k8s.io`) |
| `HorizontalPodAutoscaler order-service` | 2 → 5 replicas at 70% average CPU |
| `PodDisruptionBudget order-service` | `minAvailable: 1`, so a voluntary disruption cannot take the service down |
| `order-service` `preStop` + 30 s grace | Lets in-flight requests finish before the process exits |

`scripts/bootstrap-kind.ps1` now installs metrics-server (pinned to v0.9.1) with `--kubelet-insecure-tls`, waits for the API, and prints `kubectl top nodes`. The install is idempotent, so re-running the bootstrap on an existing cluster is safe.

## 2. Autoscaling

`infra/k8s/hpa.yaml`:

```yaml
minReplicas: 2
maxReplicas: 5
metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
behavior:
  scaleDown:
    stabilizationWindowSeconds: 60
```

The Deployment keeps `replicas: 2`, which equals the HPA minimum. Once the HPA exists it owns the replica count; applying `order-service.yaml` again resets the count to 2, which is harmless.

CPU is measured against the container **request** (`250m`), so 70% means roughly `175m` average per pod. The request is what makes the HPA able to compute a utilization at all.

## 3. Disruption

`infra/k8s/pdb.yaml` uses `minAvailable: 1`. With two or more replicas, the API allows one eviction at a time and blocks the next until a replacement is Ready.

`order-service.yaml` adds:

```yaml
terminationGracePeriodSeconds: 30
# container:
lifecycle:
  preStop:
    exec:
      command: ["sh", "-c", "sleep 5"]
```

The `preStop` sleep gives kube-proxy time to remove the endpoint before Spring stops accepting connections; Spring's graceful shutdown then has its 20 s window inside the 30 s grace period.

## 4. Smoke test

```powershell
pwsh scripts/module-05-smoke-test.ps1
```

Unattended, exit `0`/`1`. It applies `hpa.yaml`, `pdb.yaml` and `order-service.yaml`, then checks:

- metrics API `Available`, and `kubectl top pods` returns rows
- HPA spec: min 2, max 5, CPU 70, and a live CPU reading
- PDB `minAvailable=1` with at least one allowed disruption
- a valid order reaches `PRINTED`; a `1002.50` order reaches `PAYMENT_FAILED`
- **deleting an order-service pod under a 20 s request loop produces zero non-2xx responses**, and the Deployment returns to two Ready replicas

Typical output:

```text
== Autoscaling
   PASS  HPA minReplicas = 2
   PASS  HPA maxReplicas = 5
   PASS  HPA CPU target = 70
   PASS  HPA is reading CPU metrics (14% of target)
== Disruption: delete a pod under load
   PASS  no failed requests during deletion (of 10) = 0
   PASS  order-service returned to 2 Ready replicas
All 16 checks passed.
```

## 5. Interactive drills

```powershell
pwsh scripts/module-05-drills.ps1
```

Each step prints what it will do and **waits for Enter**, then runs it, so nothing is copy-pasted. It walks through:

1. start a 4-replica `busybox` load Deployment;
2. poll the HPA every 15 s (up to 5 minutes) until it scales above 2;
3. remove the load and watch it settle back toward 2;
4. drain `k8s-lab-worker2` for `order-service` pods only;
5. uncordon the node.

Cleanup always runs: the load Deployment is deleted and the node uncordoned even after a failure.

Observed during the lab:

```text
21:31:21  cpu: 66%/70%   2 replicas
21:31:41  cpu: 152%/70%  2 replicas
21:32:02  cpu: 128%/70%  5 replicas
...
after the load stops: 5 -> 4 -> 3 -> 2
```

### Why the drain uses a pod selector

PostgreSQL and Kafka use `local-path` PersistentVolumes, which are bound to the node that hosts them. A full `kubectl drain` would evict `postgres-0` or `kafka-0` and leave the volume unreachable from another node. The drill therefore uses:

```powershell
kubectl drain k8s-lab-worker2 --ignore-daemonsets --delete-emptydir-data `
  --pod-selector='app.kubernetes.io/name=order-service'
```

That still exercises the PDB and proves at least one Ready replica survives, without touching stateful workloads. `k8s-lab-worker` hosts the Ingress controller (label `ingress-ready=true`), so `k8s-lab-worker2` is the safe target.

## 6. Manual checks

```powershell
kubectl get hpa order-service -n k8s-lab
kubectl get pdb order-service -n k8s-lab
kubectl top pods -n k8s-lab
kubectl get apiservice v1beta1.metrics.k8s.io
```

Expected:

```text
NAME            REFERENCE                  TARGETS        MINPODS   MAXPODS   REPLICAS
order-service   Deployment/order-service   cpu: 19%/70%   2         5         2

NAME            MIN AVAILABLE   ALLOWED DISRUPTIONS
order-service   1               1
```

## 7. Troubleshooting

- **`TARGETS` shows `<unknown>`** — metrics-server needs one or two 15 s scrapes; wait a minute. If it never resolves, `kubectl logs -n kube-system deploy/metrics-server` (the usual cause is a missing `--kubelet-insecure-tls`).
- **The HPA does not scale** — check `kubectl top pods`. Light traffic may keep order-service well below `175m`. Scale the load Deployment up before concluding it is broken.
- **`kubectl top` fails right after bootstrap** — the API server is available before the first scrape; the smoke test polls rather than assuming.
- **Rolling updates take longer** — the 5 s `preStop` is intentional; total shutdown is bounded by 30 s.
- **Do not run a full node drain** in this lab, for the `local-path` reason above.
