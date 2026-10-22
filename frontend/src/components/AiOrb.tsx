import { useMotion } from "@/state/motion";

export type AiOrbState = "idle" | "analyzing" | "generating" | "success" | "error" | "offline";

const STATE_LABEL: Record<AiOrbState, string> = {
  idle: "AI ready",
  analyzing: "Analyzing",
  generating: "Generating",
  success: "Grounded",
  error: "Attention",
  offline: "Offline",
};

/**
 * The AI activity indicator: a small constellation around a pulsing core.
 *
 * Its state is always derived from something real — readiness, an active job, a completed
 * verdict — so the animation is a status display, not ambient decoration. When motion is
 * reduced the particles stop and only the colour and the text carry the state.
 */
export function AiOrb({
  state = "idle",
  size = 30,
  label = true,
}: {
  state?: AiOrbState;
  size?: number;
  label?: boolean;
}) {
  const { reduced } = useMotion();
  return (
    <span
      className={`ai-orb ai-orb-${state}${reduced ? " is-still" : ""}`}
      style={{ width: size, height: size }}
      role="img"
      aria-label={`AI status: ${STATE_LABEL[state]}`}
      title={STATE_LABEL[state]}
    >
      <span className="ai-orb-halo" />
      <span className="ai-orb-core" />
      <span className="ai-orb-ring ai-orb-ring-a" />
      <span className="ai-orb-ring ai-orb-ring-b" />
      <span className="ai-orb-particle ai-orb-particle-a" />
      <span className="ai-orb-particle ai-orb-particle-b" />
      <span className="ai-orb-particle ai-orb-particle-c" />
      {label ? <span className="rp-sr-only">{STATE_LABEL[state]}</span> : null}
    </span>
  );
}

export function aiLabel(state: AiOrbState): string {
  return STATE_LABEL[state];
}
