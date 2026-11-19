import type { Cart, CartLine } from './types';

interface CartPanelProps {
  cart: Cart | null;
  busy: boolean;
  onChangeQuantity: (line: CartLine, delta: number) => void;
  onRemove: (line: CartLine) => void;
  onCheckout: () => void;
}

export function CartPanel({ cart, busy, onChangeQuantity, onRemove, onCheckout }: CartPanelProps) {
  if (!cart) {
    return <p className="muted">Loading your cart…</p>;
  }

  if (cart.items.length === 0) {
    return <p className="muted">Your cart is empty.</p>;
  }

  return (
    <>
      <ul className="cart">
        {cart.items.map((line) => (
          <li key={line.productId} className="cart__line">
            <div className="cart__meta">
              <strong>{line.name}</strong>
              <span className="muted">${line.unitPrice.toFixed(2)} each</span>
            </div>
            <div className="cart__controls">
              <button
                type="button"
                onClick={() => onChangeQuantity(line, -1)}
                disabled={busy}
                aria-label={`Remove one ${line.name}`}
              >
                −
              </button>
              <span className="cart__quantity">{line.quantity}</span>
              <button
                type="button"
                onClick={() => onChangeQuantity(line, 1)}
                disabled={busy}
                aria-label={`Add one ${line.name}`}
              >
                +
              </button>
              <button type="button" className="link" onClick={() => onRemove(line)} disabled={busy}>
                Remove
              </button>
            </div>
            <span className="cart__total">${line.lineTotal.toFixed(2)}</span>
          </li>
        ))}
      </ul>

      <div className="cart__summary">
        <span>Total</span>
        <strong>${cart.total.toFixed(2)}</strong>
      </div>

      <button type="button" className="primary" onClick={onCheckout} disabled={busy || cart.items.length === 0}>
        {busy ? 'Working…' : 'Checkout'}
      </button>
    </>
  );
}
