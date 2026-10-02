package com.ecclesiaflow.platform.architecture.fixture.violating.io.members;

import com.ecclesiaflow.platform.architecture.fixture.violating.business.services.MemberLookup;

public class MemberLookupClient implements MemberLookup {

    @Override
    public String lookup(String id) {
        return id;
    }
}
