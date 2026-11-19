import { useEffect, useState } from 'react';
import * as api from './api';
import { CartPanel } from './CartPanel';
import { OrderStatusCard } from './OrderStatusCard';
import { ProductGrid } from './ProductGrid';
import type { Cart, CartLine, Order, Product } from './types';

const USER_STORAGE_KEY = 'coffee-platform-user';
const TERMINAL_STATES = new Set(['PRINTED', 'PAYMENT_FAILED']);

function resolveUserId(): string {
  const created = `user-${Math.random().toString(36).slice(2, 10)}`;
  try {
    const existing = window.localStorage.getItem(USER_STORAGE_KEY);
    if (existing) {
      return existing;
    }
    window.localStorage.setItem(USER_STORAGE_KEY, created);
  } catch {
    // Storage can be unavailable (private mode); the cart is still usable.
  }
  return created;
}

function messageOf(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
}

export default function App() {
  const [userId] = useState(resolveUserId);
  const [products, setProducts] = useState<Product[]>([]);
  const [cart, setCart] = useState<Cart | null>(null);
  const [order, setOrder] = useState<Order | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.getProducts(), api.getCart(userId)])
      .then(([loadedProducts, loadedCart]) => {
        if (cancelled) return;
        setProducts(loadedProducts);
        setCart(loadedCart);
      })
      .catch((reason) => {
        if (!cancelled) setError(messageOf(reason));
      });
    return () => {
      cancelled = true;
    };
  }, [userId]);

  useEffect(() => {
    if (!order || TERMINAL_STATES.has(order.status)) {
      return;
    }

    const orderId = order.id;
    const startedAt = Date.now();
    let cancelled = false;
    const timer = window.setInterval(() => {
      if (cancelled) return;
      if (Date.now() - startedAt > 60_000) {
        window.clearInterval(timer);
        setError('The order is taking longer than expected. Check the status again in a moment.');
        return;
      }
      api
        .getOrder(orderId)
        .then((latest) => {
          if (!cancelled) setOrder(latest);
        })
        .catch((reason) => {
          if (!cancelled) setError(messageOf(reason));
        });
    }, 1000);

    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [order?.id, order?.status]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (reason) {
      setError(messageOf(reason));
    } finally {
      setBusy(false);
    }
  }

  const addToCart = (product: Product) =>
    run(async () => setCart(await api.addItem(userId, product.id)));

  const changeQuantity = (line: CartLine, delta: number) =>
    run(async () => {
      if (delta > 0) {
        setCart(await api.addItem(userId, line.productId, delta));
        return;
      }
      const remaining = line.quantity - 1;
      await api.removeItem(userId, line.productId);
      setCart(remaining > 0 ? await api.addItem(userId, line.productId, remaining) : await api.getCart(userId));
    });

  const removeLine = (line: CartLine) =>
    run(async () => setCart(await api.removeItem(userId, line.productId)));

  const checkout = () =>
    run(async () => {
      const placed = await api.placeOrder(userId);
      setOrder(placed);
      setCart(await api.getCart(userId));
    });

  return (
    <div className="app">
      <header className="app__header">
        <div>
          <h1>Coffee Platform</h1>
          <p className="muted">Add something to your cart and watch the order flow through Kafka.</p>
        </div>
        <span className="badge badge--user">user: {userId}</span>
      </header>

      {error && (
        <div className="banner banner--error" role="alert">
          {error}
        </div>
      )}

      <main className="layout">
        <section className="panel">
          <h2>Menu</h2>
          <ProductGrid products={products} disabled={busy} onAdd={addToCart} />
        </section>

        <aside className="panel panel--cart">
          <h2>Your cart</h2>
          <CartPanel
            cart={cart}
            busy={busy}
            onChangeQuantity={changeQuantity}
            onRemove={removeLine}
            onCheckout={checkout}
          />
          {order && <OrderStatusCard order={order} />}
        </aside>
      </main>
    </div>
  );
}
