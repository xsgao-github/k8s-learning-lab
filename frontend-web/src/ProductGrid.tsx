import type { Product } from './types';

interface ProductGridProps {
  products: Product[];
  disabled: boolean;
  onAdd: (product: Product) => void;
}

export function ProductGrid({ products, disabled, onAdd }: ProductGridProps) {
  if (products.length === 0) {
    return <p className="muted">Loading the menu…</p>;
  }

  return (
    <ul className="products">
      {products.map((product) => (
        <li key={product.id} className="product">
          <div>
            <h3>{product.name}</h3>
            <p className="price">${product.price.toFixed(2)}</p>
          </div>
          <button type="button" onClick={() => onAdd(product)} disabled={disabled}>
            Add
          </button>
        </li>
      ))}
    </ul>
  );
}
