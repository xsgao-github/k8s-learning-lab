export interface Product {
  id: number;
  name: string;
  price: number;
}

export interface CartLine {
  productId: number;
  name: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

export interface Cart {
  userId: string;
  items: CartLine[];
  total: number;
}

export type OrderState = 'PENDING_PAYMENT' | 'PAID' | 'PAYMENT_FAILED' | 'PRINTED';

export interface OrderLine {
  productId: number;
  name: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

export interface Order {
  id: string;
  userId: string;
  totalAmount: number;
  status: OrderState;
  createdAt: string;
  updatedAt: string;
  lines: OrderLine[];
}
