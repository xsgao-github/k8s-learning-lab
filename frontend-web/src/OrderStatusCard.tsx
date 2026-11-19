import type { Order, OrderState } from './types';

const LABELS: Record<OrderState, string> = {
  PENDING_PAYMENT: 'Waiting for payment',
  PAID: 'Payment approved',
  PAYMENT_FAILED: 'Payment declined',
  PRINTED: 'Receipt printed',
};

interface OrderStatusCardProps {
  order: Order;
}

export function OrderStatusCard({ order }: OrderStatusCardProps) {
  const paid = order.status === 'PAID' || order.status === 'PRINTED';
  const printed = order.status === 'PRINTED';
  const failed = order.status === 'PAYMENT_FAILED';
  const tone = failed ? 'failed' : printed ? 'done' : 'pending';

  return (
    <section className="order" aria-live="polite">
      <header className="order__header">
        <h2>Order</h2>
        <span className={`badge badge--${tone}`}>{LABELS[order.status]}</span>
      </header>
      <p className="muted order__id">
        #{order.id.slice(0, 8)} · ${order.totalAmount.toFixed(2)}
      </p>
      <ol className="steps">
        <li className={failed ? 'step step--failed' : paid ? 'step step--done' : 'step step--active'}>Payment</li>
        <li className={printed ? 'step step--done' : 'step'}>Print</li>
      </ol>
    </section>
  );
}
