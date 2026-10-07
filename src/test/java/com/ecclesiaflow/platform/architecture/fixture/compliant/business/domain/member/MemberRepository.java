package com.ecclesiaflow.platform.architecture.fixture.compliant.business.domain.member;

import java.util.Optional;

public interface MemberRepository {
    Optional<Member> findById(String id);
}
