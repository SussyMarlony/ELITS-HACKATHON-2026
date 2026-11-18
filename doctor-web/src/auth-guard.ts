/**
 * In-memory passcode attempt throttle for a shared clinic workstation.
 * Slows down repeated wrong passcodes without permanently locking out an offline clinic.
 */
export class AuthThrottle {
  private failures = 0;
  private blockedUntil = 0;

  /** Remaining cooldown in whole seconds, or 0 if attempts are allowed. */
  cooldownSeconds(now = Date.now()): number {
    const ms = this.blockedUntil - now;
    return ms > 0 ? Math.ceil(ms / 1000) : 0;
  }

  recordFailure(now = Date.now()): number {
    this.failures += 1;
    const waitMs =
      this.failures >= 5 ? 30_000 :
      this.failures === 4 ? 15_000 :
      this.failures === 3 ? 5_000 : 0;
    this.blockedUntil = waitMs > 0 ? now + waitMs : 0;
    return this.cooldownSeconds(now);
  }

  reset() {
    this.failures = 0;
    this.blockedUntil = 0;
  }
}
