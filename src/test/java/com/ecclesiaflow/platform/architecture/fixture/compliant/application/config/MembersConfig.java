package com.ecclesiaflow.platform.architecture.fixture.compliant.application.config;

import com.ecclesiaflow.platform.architecture.fixture.compliant.business.services.impl.MemberServiceImpl;
import com.ecclesiaflow.platform.architecture.fixture.compliant.io.persistence.MemberRepositoryImpl;

public class MembersConfig {

    public MemberServiceImpl memberService() {
        return new MemberServiceImpl(new MemberRepositoryImpl());
    }
}
