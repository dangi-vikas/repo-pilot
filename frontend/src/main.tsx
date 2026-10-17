import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "@/App";
import "@/styles/tokens.css";
import "@/styles/global.css";
import "@/styles/app.css";

const container = document.getElementById("root");
if (container === null) {
  throw new Error("RepoPilot could not start: #root was not found in the document.");
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
