package com.ecclesiaflow.platform.architecture.fixture.violating.business.services;

/** A port declared outside business.domain. */
public interface MemberLookup {
    String lookup(String id);
}
