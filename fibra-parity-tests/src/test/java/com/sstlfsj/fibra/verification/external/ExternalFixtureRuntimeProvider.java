package com.sstlfsj.fibra.verification.external;

import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.engine.RuntimeDriver;
import com.sstlfsj.fibra.engine.RuntimeHostServices;
import com.sstlfsj.fibra.engine.RuntimeProvider;
import com.sstlfsj.fibra.value.LiteralValue;

import java.util.List;
import java.util.Objects;

/**
 * 仅用于验证外部 runtime 能只消费公开 Engine SPI；不代表浏览器或产品 execution 实现。
 */
public final class ExternalFixtureRuntimeProvider implements RuntimeProvider {
    public static final RuntimeId RUNTIME_ID = new RuntimeId("external-fixture");
    public static final String CONTRACT_IDENTITY = "external-runtime-provider-tck:v1";
    public static final String DEFINITION_ID = "fixture";
    public static final String REMOTE_KIND_NAME = "fibra.verification.external.echo";
    /** Host registry 必须登记此对象本身，不能按名称复制一个等价 kind。 */
    public static final ContributionKind<String, String, String> REMOTE_KIND =
        ContributionKind.remote(REMOTE_KIND_NAME, String.class, String.class,
            String.class, new StringCodec());

    @Override
    public RuntimeId id() {
        return RUNTIME_ID;
    }

    @Override
    public String contractIdentity() {
        return CONTRACT_IDENTITY;
    }

    @Override
    public List<com.sstlfsj.fibra.engine.BuiltInPluginPackage> builtInPackages() {
        return List.of();
    }

    @Override
    public RuntimeDriver create(RuntimeHostServices services) {
        return new ExternalFixtureRuntimeDriver(Objects.requireNonNull(services,
            "services"));
    }

    private static final class StringCodec
        implements ContributionCodec<String, String, String> {
        @Override public int schemaVersion() { return 1; }
        @Override public String decodeDescriptor(LiteralValue descriptor) {
            return string(descriptor);
        }
        @Override public LiteralValue encodeInput(String input) {
            return LiteralValue.of(Objects.requireNonNull(input, "input"));
        }
        @Override public String decodeInput(LiteralValue input) { return string(input); }
        @Override public LiteralValue encodeOutput(String output) {
            return LiteralValue.of(Objects.requireNonNull(output, "output"));
        }
        @Override public String decodeOutput(LiteralValue output) { return string(output); }
        private static String string(LiteralValue value) {
            if (!(Objects.requireNonNull(value, "value") instanceof LiteralValue.StringValue text)) {
                throw new IllegalArgumentException("external fixture codec requires a string literal");
            }
            return text.value();
        }
    }
}
