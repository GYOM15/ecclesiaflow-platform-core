package com.ecclesiaflow.platform.architecture.fixture.violating.grpc;

import com.google.protobuf.Descriptors;
import com.google.protobuf.ProtocolMessageEnum;

/** Shaped like protoc output: generated types live outside io.grpc and com.google.protobuf. */
public enum ActivationStatus implements ProtocolMessageEnum {
    ACTIVATED;

    @Override
    public int getNumber() {
        return 0;
    }

    @Override
    public Descriptors.EnumValueDescriptor getValueDescriptor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Descriptors.EnumDescriptor getDescriptorForType() {
        throw new UnsupportedOperationException();
    }
}
