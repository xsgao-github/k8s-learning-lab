# Module 04: React Frontend & Ingress

Build the browser client, serve it from an unprivileged nginx, and split the Ingress: `/` to the frontend and `/api` to `order-service`.

---

## 1. What the UI does

- Loads the catalog from `GET /api/products`.
- Keeps a cart drawer with add, increment, decrement and remove controls.
- Checks out with `POST /api/orders`, then polls `GET /api/orders/{id}` once per second until the order is `PRINTED` or `PAYMENT_FAILED`.
- Shows the payment and print steps, and refreshes the cart after checkout (the server clears it).

The user id is generated once per browser and kept in `localStorage` (`coffee-platform-user`). Clearing site data gives a fresh cart, which is handy for demos.

## 2. Layout

```text
frontend-web/
├── index.html
├── package.json / package-lock.json
├── tsconfig.json
├── vite.config.ts          # dev-only /api proxy to localhost:8081
├── nginx.conf              # listen 8080, /healthz, SPA fallback
├── Dockerfile              # node:22-alpine build -> nginx-unprivileged runtime
└── src/
    ├── main.tsx
    ├── App.tsx             # state, loading, checkout, status polling
    ├── api.ts              # fetch wrappers + ProblemDetail error handling
    ├── types.ts
    ├── ProductGrid.tsx
    ├── CartPanel.tsx
    ├── OrderStatusCard.tsx
    └── styles.css
```

The client only calls relative `/api/...` paths, so it works unchanged through the Ingress.

## 3. Build and Test

```powershell
npm --prefix frontend-web install
npm --prefix frontend-web run build      # tsc --noEmit && vite build
```

`npm run build` type-checks every `.ts`/`.tsx` file with `strict` enabled and emits `frontend-web/dist`.

## 4. Build, Load and Deploy

The build context is the repository root, like the other services.

```powershell
docker build -f frontend-web/Dockerfile -t frontend-web:0.1.0 .
kind load docker-image frontend-web:0.1.0 --name k8s-lab

kubectl apply -f infra/k8s/frontend-web.yaml -f infra/k8s/ingress.yaml
kubectl rollout status deployment/frontend-web -n k8s-lab --timeout=180s
kubectl get pods -n k8s-lab -l app.kubernetes.io/name=frontend-web
```

Expected: two `frontend-web` pods at `1/1 Running`.

## 5. Smoke Test

```powershell
pwsh scripts/module-04-smoke-test.ps1
```

It builds and loads the image, deploys, then asserts: the page and its hashed bundle are served, unknown paths fall back to `index.html`, `/api` still reaches `order-service`, and the exact request sequence the UI performs reaches `PRINTED` with a receipt in the log.

## 6. How it is served

- **Multi-stage build**: `node:22-alpine` runs `npm ci` and `npm run build`; the result is copied into `nginxinc/nginx-unprivileged:1.27-alpine`.
- **Non-root on 8080**: the image runs as UID 101 and needs no capabilities. The container uses `readOnlyRootFilesystem: true` with an `emptyDir` at `/tmp`, which is where the unprivileged image keeps its pid and temp files.
- **Probes**: nginx answers `GET /healthz` with `200 ok`; startup, readiness and liveness all use it.
- **SPA fallback**: `try_files $uri $uri/ /index.html` keeps deep links working; there is no client-side router today, but the fallback is what makes `/orders/example` return the app instead of a 404.
- **Ingress**:

  | Path | Backend |
  |---|---|
  | `/api` (Prefix) | `order-service:8081` |
  | `/` (Prefix) | `frontend-web:8080` |

  ingress-nginx matches the more specific `/api` first, so API calls never hit the static server.

## 7. Notes

- **Decrementing a cart line** is two calls (delete, then add `quantity - 1`) because the API only supports add/increment and delete. That is deliberate: Module 4 does not change the backend.
- **`npm run dev`** serves the app on Vite's port and proxies `/api` to `localhost:8081`, so the UI can be developed against a local service. The deployed path goes through the Ingress.
- **Rebuilds with the same tag** need both `kind load docker-image ...` and `kubectl rollout restart deployment/frontend-web -n k8s-lab`; `imagePullPolicy: IfNotPresent` otherwise keeps the old pod.
- **`frontend-web/node_modules` and `frontend-web/dist`** are ignored by Git and by `.dockerignore`.

## 8. Verification

- `http://localhost/` serves the menu; add items, change quantities, remove them.
- Checkout shows the order progress without any manual API call and ends at "Receipt printed".
- An order over 1000 shows "Payment declined".
- `http://localhost/api/products` still returns the JSON catalog.
