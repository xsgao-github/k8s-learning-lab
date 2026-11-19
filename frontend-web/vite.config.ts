import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // Local development only: the cluster routes /api through the Ingress.
    proxy: {
      '/api': 'http://localhost:8081',
    },
  },
});
