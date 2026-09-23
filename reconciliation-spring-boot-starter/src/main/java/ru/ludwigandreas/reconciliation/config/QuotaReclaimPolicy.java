package ru.ludwigandreas.reconciliation.config;

/**
 * What to do with a quota lease whose holder stopped heartbeating.
 *
 * <p>The trap this setting exists around: <b>an expired lease does not mean the remote work
 * stopped.</b> The instance holding it may be gone while the job it submitted is still running on the
 * partner's side, still occupying the capacity the quota is counting.
 */
public enum QuotaReclaimPolicy {

    /**
     * Ask the partner first. Poll the handle the lease was held for, and reclaim the slot only if the
     * job has reached a terminal state or outlived {@code max-lifetime} - cancelling it remotely where
     * the fetcher implements cancellation. The default, and the only setting that keeps the slot count
     * an honest description of what the partner is doing.
     */
    VERIFY_REMOTE,

    /**
     * Reclaim on expiry, without checking.
     *
     * <p>Available for partners with no status endpoint, where there is nothing to verify against.
     * Document it where the integration is described: under this policy the number of jobs actually
     * running at the partner can exceed the configured limit, and the excess is invisible from here.
     */
    ON_EXPIRY
}
