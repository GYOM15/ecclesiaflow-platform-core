package com.ecclesiaflow.platform.ratelimit;

/**
 * Counted per church, the tenant is the unit of abuse and one church cannot starve another; counted per
 * user, ten accounts could exhaust a shared resource. Anything that touches a church's data is
 * {@link #PER_CHURCH}; {@link #PER_USER} is for a person's own operations (e.g. credentials), where
 * per-church counting would let one member lock out the rest.
 */
public enum RateLimitScope {

    PER_CHURCH,

    PER_USER
}
