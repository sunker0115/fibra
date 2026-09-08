package com.sstlfsj.fibra.bridge;

import java.util.Objects;

public final class ContributionKind<D, I, O> {
    private final String name;
    private final Class<D> descriptorType;
    private final Class<I> inputType;
    private final Class<O> outputType;
    private final ContributionCodec<D, I, O> codec;

    private ContributionKind(String name, Class<D> descriptorType, Class<I> inputType,
                             Class<O> outputType, ContributionCodec<D, I, O> codec) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("contribution kind name must not be blank");
        }
        this.name = name;
        this.descriptorType = Objects.requireNonNull(descriptorType, "descriptorType");
        this.inputType = Objects.requireNonNull(inputType, "inputType");
        this.outputType = Objects.requireNonNull(outputType, "outputType");
        this.codec = codec;
    }

    public static <D, I, O> ContributionKind<D, I, O> local(
        String name, Class<D> descriptorType, Class<I> inputType, Class<O> outputType) {
        return new ContributionKind<>(name, descriptorType, inputType, outputType, null);
    }

    public static <D, I, O> ContributionKind<D, I, O> remote(
        String name, Class<D> descriptorType, Class<I> inputType, Class<O> outputType,
        ContributionCodec<D, I, O> codec) {
        return new ContributionKind<>(name, descriptorType, inputType, outputType,
            Objects.requireNonNull(codec, "codec"));
    }

    public String name() {
        return name;
    }

    public Class<D> descriptorType() {
        return descriptorType;
    }

    public Class<I> inputType() {
        return inputType;
    }

    public Class<O> outputType() {
        return outputType;
    }

    public java.util.Optional<ContributionCodec<D, I, O>> codec() {
        return java.util.Optional.ofNullable(codec);
    }
}
