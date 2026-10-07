package com.ecclesiaflow.platform.rpc.events;

import io.grpc.Status;

/**
 * @param error {@code null} when the service closed the call with a bare status
 */
public record RpcCallFailed(String fullMethodName, Status.Code code, Throwable error) {
}
