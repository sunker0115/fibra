package com.sstlfsj.fibra.bridge;

public interface ContributionCodec<D, I, O> {
    int schemaVersion();

    D decodeDescriptor(Object descriptor);

    Object encodeInput(I input);

    I decodeInput(Object input);

    Object encodeOutput(O output);

    O decodeOutput(Object output);
}
