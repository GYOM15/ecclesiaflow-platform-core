package com.ecclesiaflow.platform.architecture.fixture.compliant.web;

import com.ecclesiaflow.platform.architecture.fixture.compliant.business.domain.member.Member;
import com.ecclesiaflow.platform.architecture.fixture.compliant.business.services.MemberService;
import org.springframework.http.ResponseEntity;

public class MemberController {

    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    public ResponseEntity<Member> get(String id) {
        return ResponseEntity.ok(members.get(id));
    }
}
