package com.ecclesiaflow.platform.architecture.fixture.violating.web;

import com.ecclesiaflow.platform.architecture.fixture.violating.io.persistence.MemberStore;

public class LeakyController {

    private final MemberStore store = new MemberStore();

    public String get(String id) {
        return store.load(id);
    }
}
