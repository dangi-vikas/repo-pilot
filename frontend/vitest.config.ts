import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * Vitest config for the pure layers (graph transforms, formatting, evidence mapping).
 *
 * There is no jsdom here on purpose: the canvas and the React shell are exercised in a
 * real browser, while the decisions that are easy to get subtly wrong are tested as
 * plain functions.
 */
export default defineConfig({
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
