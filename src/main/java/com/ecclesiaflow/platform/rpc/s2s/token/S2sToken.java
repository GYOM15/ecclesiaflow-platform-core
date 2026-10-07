package com.ecclesiaflow.platform.rpc.s2s.token;

import java.time.Instant;

public record S2sToken(String accessToken, Instant expiry) {
}
