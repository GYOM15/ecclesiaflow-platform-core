package com.ecclesiaflow.platform.architecture.fixture.compliant.io.persistence;

import com.ecclesiaflow.platform.architecture.fixture.compliant.business.domain.member.Member;
import com.ecclesiaflow.platform.architecture.fixture.compliant.business.domain.member.MemberRepository;

import java.util.Optional;

public class MemberRepositoryImpl implements MemberRepository {

    @Override
    public Optional<Member> findById(String id) {
        return Optional.empty();
    }
}
