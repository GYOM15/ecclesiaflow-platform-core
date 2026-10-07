package com.ecclesiaflow.platform.architecture.fixture.compliant.business.services;

import com.ecclesiaflow.platform.architecture.fixture.compliant.business.domain.member.Member;

public interface MemberService {
    Member get(String id);
}
