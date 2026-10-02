package com.ecclesiaflow.platform.architecture.fixture.violating.business.services;

import com.ecclesiaflow.platform.architecture.fixture.violating.application.AuditActorResolver;
import com.ecclesiaflow.platform.architecture.fixture.violating.grpc.ActivationStatus;
import com.ecclesiaflow.platform.architecture.fixture.violating.io.persistence.MemberStore;
import com.ecclesiaflow.platform.architecture.fixture.violating.web.LeakyController;
import io.grpc.Status;
import org.springframework.http.HttpStatus;

public class LeakyService {

    private final MemberStore store = new MemberStore();
    private final AuditActorResolver actors = new AuditActorResolver();

    public HttpStatus activate(String id) {
        store.load(id);
        actors.currentActor();
        new LeakyController();
        return ActivationStatus.ACTIVATED.getNumber() == 0 ? HttpStatus.OK : HttpStatus.CONFLICT;
    }

    public Status.Code grpcCode() {
        return Status.Code.OK;
    }
}
