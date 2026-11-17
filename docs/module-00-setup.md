# Module 00: Environment & Cluster Bootstrap

Create the local `kind` cluster that every later module runs on, install ingress-nginx, and verify the three-node baseline.

---

## 1. Getting Started

### Prerequisites

- Windows 11 with Docker Desktop running Linux containers
- PowerShell 5.1+, `winget`, and `kubectl` (bundled with Docker Desktop)
- Java 17+ and Maven 3.9+ (needed from Module 1)

```powershell
docker info --format '{{.ServerVersion}}'
kubectl version --client
java -version
mvn -version
```

### Bootstrap

From the repository root:

```powershell
.\scripts\bootstrap-kind.ps1
```

The script verifies Docker, installs `kind` with winget if missing, creates or reuses `k8s-lab` from `infra/kind-config.yaml`, installs and pins ingress-nginx, then prints node and pod status. To rebuild from scratch:

```powershell
.\scripts\bootstrap-kind.ps1 -Recreate
```

### Verify

```powershell
kind get clusters
kubectl get nodes
kubectl get pods -n ingress-nginx
docker ps --filter "name=k8s-lab" --format "table {{.Names}}\t{{.Image}}\t{{.Ports}}"
```

Expected: cluster `k8s-lab`; three `Ready` nodes; the controller `1/1 Running` on `k8s-lab-worker`; three `kindest/node` containers (one control plane, two workers).

---

## 2. What the Bootstrap Creates

### Cluster Definition

`infra/kind-config.yaml`:

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
  - role: worker
    kubeadmConfigPatches:
      - |
        kind: JoinConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - containerPort: 80
        hostPort: 80
        protocol: TCP
      - containerPort: 443
        hostPort: 443
        protocol: TCP
  - role: worker
```

- One control plane and two schedulable workers.
- The first worker is labeled `ingress-ready=true` and owns the host port mappings for 80/443.
- The control-plane taint keeps workloads on the workers.

### Ingress Controller

The bootstrap applies the kind provider manifest, then pins the controller to the labeled worker:

```powershell
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl patch deployment ingress-nginx-controller -n ingress-nginx --type=merge `
  -p '{"spec":{"template":{"spec":{"nodeSelector":{"ingress-ready":"true"}}}}}'
kubectl wait --namespace ingress-nginx --for=condition=ready pod `
  --selector=app.kubernetes.io/component=controller --timeout=180s
```

The controller uses `hostPort` 80/443, which the worker's `extraPortMappings` publish on Windows. The manifest also creates the `ingress-nginx` namespace, RBAC, the admission webhook, and the `nginx` IngressClass. No routing rules exist until Module 2.

### Nodes vs. Pods

`docker ps` shows three node containers, not services. Each runs kubelet and containerd; pods run inside:

```powershell
kubectl get pods -A -o wide
docker exec k8s-lab-worker crictl ps
```

Each node supports up to 110 pods.

### Lifecycle

```powershell
docker stop k8s-lab-worker k8s-lab-worker2 k8s-lab-control-plane
```

```powershell
docker start k8s-lab-control-plane k8s-lab-worker k8s-lab-worker2
kubectl wait --for=condition=Ready node --all --timeout=180s
```

The containers use restart policy `on-failure`, so restarting Docker Desktop does not bring them back automatically. Stop/start preserves cluster data; `-Recreate` or `kind delete cluster --name k8s-lab` removes it.

---

## 3. Common Pitfalls

- **Ports 80 or 443 are in use.** The cluster cannot publish Ingress ports. Find and stop the listener, then rebuild:

  ```powershell
  Get-NetTCPConnection -State Listen -LocalPort 80,443 |
    Select-Object LocalAddress, LocalPort, OwningProcess
  .\scripts\bootstrap-kind.ps1 -Recreate
  ```

- **Ingress controller does not become Ready.** Check its scheduling and logs; it must land on the `ingress-ready=true` worker:

  ```powershell
  kubectl describe pod -n ingress-nginx -l app.kubernetes.io/component=controller
  kubectl logs -n ingress-nginx -l app.kubernetes.io/component=controller --tail=100
  ```

- **`kubectl` tries `localhost:8080`.** No kind context is selected:

  ```powershell
  kubectl config get-contexts
  kubectl config use-context kind-k8s-lab
  ```

  If the context is missing, the cluster no longer exists; recreate it.

- **Pods stay `Pending`.** Inspect events and node allocation. The control-plane taint keeps workloads on workers, and requests must fit:

  ```powershell
  kubectl describe pod <pod> -n <namespace>
  kubectl describe node k8s-lab-worker | Select-String 'Allocated resources' -Context 0,6
  ```

  Node capacities are not additive — each kind node reports the full Docker Desktop VM size.

---

## 4. Module 0 Checklist

- [ ] `docker info` succeeds and Docker Desktop uses Linux containers.
- [ ] `kind get clusters` returns `k8s-lab`.
- [ ] Three nodes are `Ready`.
- [ ] The Ingress controller pod is `Running`.
- [ ] You can stop and start the node containers and recover the cluster.

Next: [Module 1 — Multi-Module Maven Skeleton & Shared Event Contracts](module-01-common-events.md).
