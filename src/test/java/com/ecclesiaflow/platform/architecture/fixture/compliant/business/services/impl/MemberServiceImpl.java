package com.ecclesiaflow.platform.architecture.fixture.compliant.business.services.impl;

import com.ecclesiaflow.platform.architecture.fixture.compliant.business.domain.member.Member;
import com.ecclesiaflow.platform.architecture.fixture.compliant.business.domain.member.MemberRepository;
import com.ecclesiaflow.platform.architecture.fixture.compliant.business.services.MemberService;
import org.springframework.stereotype.Service;

/** Spring stereotypes are allowed in services; only transport and persistence types are not. */
@Service
public class MemberServiceImpl implements MemberService {

    private final MemberRepository members;

    public MemberServiceImpl(MemberRepository members) {
        this.members = members;
    }

    @Override
    public Member get(String id) {
        return members.findById(id).orElseThrow(IllegalArgumentException::new);
    }
}
