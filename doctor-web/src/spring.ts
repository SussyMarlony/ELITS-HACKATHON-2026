// Apple WWDC "Designing Fluid Interfaces" physics primitives for the web:
// interruptible springs (dampingRatio + response), momentum projection, and rubber-banding.

/** True when the user prefers reduced vestibular motion. */
export const prefersReducedMotion = () =>
  typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Apple's exponential momentum projection: landing offset from release velocity (px/s). */
export function project(velocityPxPerSec: number, decelerationRate = 0.998): number {
  return ((velocityPxPerSec / 1000) * decelerationRate) / (1 - decelerationRate);
}

/** Progressive boundary resistance when dragging past an edge. */
export function rubberband(overshoot: number, dimension: number, constant = 0.55): number {
  if (dimension <= 0) return 0;
  return (overshoot * dimension * constant) / (dimension + constant * Math.abs(overshoot));
}

export interface SpringOptions {
  dampingRatio?: number;
  response?: number;
  velocity?: number;
  onUpdate: (value: number) => void;
  onComplete?: () => void;
}

/**
 * Interruptible 1D spring driven by requestAnimationFrame.
 * Calling `to()` while running preserves current position AND current velocity.
 */
export class SpringValue {
  value: number;
  velocity = 0;
  private target: number;
  private raf = 0;
  private lastT = 0;

  constructor(initial: number) {
    this.value = initial;
    this.target = initial;
  }

  snap(v: number) {
    this.stop();
    this.value = v;
    this.target = v;
    this.velocity = 0;
  }

  stop() {
    if (this.raf) {
      cancelAnimationFrame(this.raf);
      this.raf = 0;
    }
  }

  to(target: number, opts: SpringOptions) {
    this.target = target;
    if (opts.velocity !== undefined) this.velocity = opts.velocity;
    if (prefersReducedMotion()) {
      this.snap(target);
      opts.onUpdate(target);
      opts.onComplete?.();
      return;
    }
    const zeta = opts.dampingRatio ?? 1.0;
    const response = Math.max(0.05, opts.response ?? 0.35);
    const omega = (2 * Math.PI) / response;
    const stiffness = omega * omega;
    const damping = 2 * zeta * omega;

    this.stop();
    this.lastT = performance.now();
    const step = (now: number) => {
      const dt = Math.min(0.032, Math.max(0.001, (now - this.lastT) / 1000));
      this.lastT = now;

      // Sub-step twice for stability on snappy responses.
      const h = dt / 2;
      for (let i = 0; i < 2; i++) {
        const disp = this.value - this.target;
        const accel = -stiffness * disp - damping * this.velocity;
        this.velocity += accel * h;
        this.value += this.velocity * h;
      }

      opts.onUpdate(this.value);
      if (Math.abs(this.value - this.target) < 0.35 && Math.abs(this.velocity) < 8) {
        this.value = this.target;
        this.velocity = 0;
        this.raf = 0;
        opts.onUpdate(this.target);
        opts.onComplete?.();
        return;
      }
      this.raf = requestAnimationFrame(step);
    };
    this.raf = requestAnimationFrame(step);
  }
}

export interface DragDismissOptions {
  sheet: HTMLElement;
  backdrop: HTMLElement;
  onDismiss: () => void;
  threshold?: number;
}

/**
 * Attaches 1:1 pointer-tracked drag-to-dismiss to a modal sheet or full-screen presenter.
 * Upward drag rubber-bands; downward release projects momentum and hands velocity to the spring.
 */
export function attachDragDismiss({ sheet, backdrop, onDismiss, threshold = 110 }: DragDismissOptions): () => void {
  const springY = new SpringValue(0);
  const samples: Array<{ y: number; t: number }> = [];
  let pointerId: number | null = null;
  let startY = 0;
  let dragging = false;
  let baseOffset = 0;

  const apply = (y: number) => {
    const progress = Math.min(1, Math.max(0, y / (window.innerHeight * 0.45)));
    sheet.style.transform = y === 0 ? '' : `translate3d(0, ${y.toFixed(1)}px, 0)`;
    backdrop.style.opacity = y > 0 ? String(Math.max(0.15, 1 - progress * 0.85)) : '';
  };

  // Entrance settle: materialize from subtle offset with critically damped spring.
  if (!prefersReducedMotion()) {
    springY.snap(18);
    backdrop.style.opacity = '0';
    requestAnimationFrame(() => {
      backdrop.style.transition = 'opacity 200ms cubic-bezier(0.23, 1, 0.32, 1)';
      backdrop.style.opacity = '1';
      springY.to(0, { dampingRatio: 1.0, response: 0.32, onUpdate: apply });
    });
  }

  const onDown = (e: PointerEvent) => {
    if (e.button !== 0) return;
    const target = e.target as HTMLElement;
    // Never hijack clicks/drags inside scrollable text, inputs, video, or buttons.
    if (target.closest('input, textarea, select, button, a, label, video')) return;
    pointerId = e.pointerId;
    startY = e.clientY;
    baseOffset = springY.value;
    dragging = false;
    samples.length = 0;
    samples.push({ y: e.clientY, t: performance.now() });
    springY.stop();
    backdrop.style.transition = 'none';
  };

  const onMove = (e: PointerEvent) => {
    if (pointerId !== e.pointerId) return;
    const dy = e.clientY - startY;
    if (!dragging) {
      if (Math.abs(dy) < 10) return;
      dragging = true;
      sheet.setPointerCapture(e.pointerId);
    }
    const raw = baseOffset + dy;
    const y = raw < 0 ? -rubberband(-raw, 220, 0.45) : raw;
    springY.snap(y);
    apply(y);
    const now = performance.now();
    samples.push({ y: e.clientY, t: now });
    while (samples.length > 2 && now - samples[0].t > 90) samples.shift();
  };

  const onEnd = (e: PointerEvent) => {
    if (pointerId !== e.pointerId) return;
    pointerId = null;
    if (!dragging) return;
    dragging = false;
    sheet.dataset.justDragged = '1';
    setTimeout(() => delete sheet.dataset.justDragged, 60);
    try {
      sheet.releasePointerCapture(e.pointerId);
    } catch { /* already released */ }

    const first = samples[0];
    const last = samples[samples.length - 1];
    const dt = last && first ? (last.t - first.t) / 1000 : 0;
    const velocity = dt > 0.004 ? (last.y - first.y) / dt : 0;
    const projected = springY.value + project(velocity, 0.996);

    backdrop.style.transition = 'opacity 200ms cubic-bezier(0.23, 1, 0.32, 1)';
    if (projected > threshold && velocity >= -80) {
      backdrop.style.opacity = '0';
      springY.to(window.innerHeight * 0.55, {
        dampingRatio: 1.0,
        response: 0.28,
        velocity: Math.max(400, velocity),
        onUpdate: apply,
        onComplete: onDismiss,
      });
    } else {
      backdrop.style.opacity = '1';
      springY.to(0, {
        dampingRatio: Math.abs(velocity) > 300 ? 0.82 : 1.0,
        response: 0.34,
        velocity,
        onUpdate: apply,
      });
    }
  };

  sheet.addEventListener('pointerdown', onDown);
  sheet.addEventListener('pointermove', onMove);
  sheet.addEventListener('pointerup', onEnd);
  sheet.addEventListener('pointercancel', onEnd);
  return () => {
    springY.stop();
    sheet.removeEventListener('pointerdown', onDown);
    sheet.removeEventListener('pointermove', onMove);
    sheet.removeEventListener('pointerup', onEnd);
    sheet.removeEventListener('pointercancel', onEnd);
  };
}

let pillLeftSpring = new SpringValue(0);
let pillWidthSpring = new SpringValue(0);
let lastPillLeft: number | null = null;
let lastPillWidth: number | null = null;

/** Animates a segmented control indicator pill from its last live presentation bounds. */
export function syncSegmentedPill(container: HTMLElement | null) {
  if (!container) return;
  const pill = container.querySelector<HTMLElement>('.seg-pill');
  const active = container.querySelector<HTMLElement>('[aria-selected="true"]');
  if (!pill || !active) return;

  const targetLeft = active.offsetLeft;
  const targetWidth = active.offsetWidth;
  if (lastPillLeft === null || lastPillWidth === null) {
    pillLeftSpring.snap(targetLeft);
    pillWidthSpring.snap(targetWidth);
    pill.style.transform = `translate3d(${targetLeft}px, 0, 0)`;
    pill.style.width = `${targetWidth}px`;
  } else {
    pillLeftSpring.snap(lastPillLeft);
    pillWidthSpring.snap(lastPillWidth);
    pillLeftSpring.to(targetLeft, {
      dampingRatio: 1.0,
      response: 0.28,
      onUpdate: (x) => {
        lastPillLeft = x;
        pill.style.transform = `translate3d(${x}px, 0, 0)`;
      },
    });
    pillWidthSpring.to(targetWidth, {
      dampingRatio: 1.0,
      response: 0.28,
      onUpdate: (w) => {
        lastPillWidth = w;
        pill.style.width = `${w}px`;
      },
    });
  }
  lastPillLeft = targetLeft;
  lastPillWidth = targetWidth;
}
