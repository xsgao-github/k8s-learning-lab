import type { Cart, Order, Product } from './types';

export class ApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  });

  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const problem = (await response.json()) as { title?: string; detail?: string };
      message = problem.detail ?? problem.title ?? message;
    } catch {
      // The body was not a ProblemDetail; keep the status-based message.
    }
    throw new ApiError(message);
  }

  return (await response.json()) as T;
}

export const getProducts = () => request<Product[]>('/api/products');

export const getCart = (userId: string) => request<Cart>(`/api/carts/${userId}`);

export const addItem = (userId: string, productId: number, quantity = 1) =>
  request<Cart>(`/api/carts/${userId}/items`, {
    method: 'POST',
    body: JSON.stringify({ productId, quantity }),
  });

export const removeItem = (userId: string, productId: number) =>
  request<Cart>(`/api/carts/${userId}/items/${productId}`, { method: 'DELETE' });

export const placeOrder = (userId: string) =>
  request<Order>('/api/orders', {
    method: 'POST',
    body: JSON.stringify({ userId }),
  });

export const getOrder = (orderId: string) => request<Order>(`/api/orders/${orderId}`);
