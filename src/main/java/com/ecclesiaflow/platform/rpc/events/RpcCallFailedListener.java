package com.ecclesiaflow.platform.rpc.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * The interceptor that publishes {@link RpcCallFailed} is called by gRPC outside any Spring proxy,
 * so the log line is written here.
 */
@Slf4j
public class RpcCallFailedListener {

    @EventListener
    public void onRpcCallFailed(RpcCallFailed event) {
        String method = event.fullMethodName();
        Status.Code code = event.code();
        String cause = describe(event.error());
        switch (code) {
            case INTERNAL, UNKNOWN, DATA_LOSS, UNIMPLEMENTED ->
                    log.error("GRPC-RPC: ❌ {} failed with {} — {}", method, code, cause, event.error());
            case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED ->
                    log.warn("GRPC-RPC: ⚠ {} failed with {} — {}", method, code, cause);
            // The s2s interceptor already logs its own refusals at WARN.
            case UNAUTHENTICATED, PERMISSION_DENIED, CANCELLED ->
                    log.debug("GRPC-RPC: {} refused with {} — {}", method, code, cause);
            default -> log.info("GRPC-RPC: {} refused with {} — {}", method, code, cause);
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "no exception";
        }
        return error.getClass().getSimpleName() + ": " + SecurityMaskingUtils.rootMessage(error);
    }
}
