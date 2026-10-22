import { useEffect, useRef, useState } from "react";
import { useMotion } from "@/state/motion";

/**
 * Counts a number up to its target, but only when motion is allowed.
 *
 * The final value is exact: the animation is a presentation of a real number, never a
 * placeholder that settles on something else. Reduced motion jumps straight to it.
 */
export function useCountUp(target: number, durationMs = 900): number {
  const { reduced } = useMotion();
  const [value, setValue] = useState(reduced ? target : 0);
  const fromRef = useRef(0);

  useEffect(() => {
    if (reduced) {
      fromRef.current = target;
      setValue(target);
      return;
    }
    const from = fromRef.current;
    if (from === target) {
      setValue(target);
      return;
    }
    let frame = 0;
    const startedAt = performance.now();
    const step = (now: number) => {
      const progress = Math.min(1, (now - startedAt) / durationMs);
      const eased = 1 - Math.pow(1 - progress, 3);
      const next = from + (target - from) * eased;
      fromRef.current = progress >= 1 ? target : next;
      setValue(progress >= 1 ? target : next);
      if (progress < 1) {
        frame = window.requestAnimationFrame(step);
      }
    };
    frame = window.requestAnimationFrame(step);
    return () => window.cancelAnimationFrame(frame);
  }, [target, durationMs, reduced]);

  return reduced ? target : value;
}
