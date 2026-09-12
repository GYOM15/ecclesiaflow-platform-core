package com.ecclesiaflow.platform.ratelimit;

/**
 * WHOSE requests a rule counts.
 *
 * <p>The distinction is not cosmetic. A tenant-scoped operation counted per USER
 * lets one church exhaust a shared resource with ten accounts, while the limit
 * appears to be respected by each of them; counted per CHURCH, the tenant is the
 * unit of abuse and one church can never starve another. Anything that reads or
 * writes a church's data is therefore {@link #PER_CHURCH}.
 *
 * <p>{@link #PER_USER} is for operations that belong to a person rather than to
 * a congregation — changing one's own credentials, for instance — where counting
 * per church would let one member's behaviour lock out the rest.
 */
public enum RateLimitScope {

    /** Counted across the whole tenant. The default for anything tenant-scoped. */
    PER_CHURCH,

    /** Counted per authenticated person, whatever church they act in. */
    PER_USER
}
