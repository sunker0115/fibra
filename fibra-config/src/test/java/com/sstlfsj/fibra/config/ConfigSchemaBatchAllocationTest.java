package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

import static com.sstlfsj.fibra.config.ConfigSchemaTest.source;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSchemaBatchAllocationTest {
    @Test
    void rejectsFirstOversizedValueBeforeMaterializingOrReadingTheRestOfALazyBatch() {
        var rule = source(Map.of("type", "string"));
        var tooLarge = new ConfigValidationInput(rule, LiteralValue.of("x".repeat(65537)));
        var inputs = new SequentialList<>(10_000_000, index -> {
            assertEquals(0, index, "budget rejection must stop scanning");
            return tooLarge;
        });

        assertFailure(ConfigSchema.validateAll(inputs), 0, "BUDGET_EXCEEDED");
        assertEquals(1, inputs.reads);
    }

    @Test
    void rejectsRepeatedSchemaBudgetFailureBeforeAccumulatingFailuresOrCopies() {
        var tooLarge = source(Map.of("type", "null", "description", "x".repeat(4097)));
        var sources = new SequentialList<>(10_000_000, index -> {
            assertEquals(0, index, "first known schema budget failure must stop scanning");
            return tooLarge;
        });

        assertFailure(ConfigSchema.checkSources(sources), 0, "BUDGET_EXCEEDED");
        assertEquals(1, sources.reads);
    }

    @Test
    void capturesInputsOnceAndCompilesDuplicateSourcesWithoutCopyingCallerLists() {
        var rule = source(Map.of("type", "string"));
        var inputs = new SequentialList<>(3, index -> new ConfigValidationInput(rule,
            LiteralValue.of(index == 1 ? 42 : "valid")));
        assertFailure(ConfigSchema.validateAll(inputs), 1, "VALUE_MISMATCH");
        assertEquals(3, inputs.reads, "all budgets precede instance validation");

        var sources = new SequentialList<>(1000, index -> rule);
        assertTrue(ConfigSchema.checkSources(sources).isEmpty());
        assertEquals(1000, sources.reads);
        assertTrue(ConfigSchema.validateAll(List.of(new ConfigValidationInput(rule, LiteralValue.of("valid")))).isEmpty());
    }

    @Test
    void stillScansAfterASemanticFailureForLaterBudgetRejection() {
        var valid = source(Map.of("type", "string"));
        var unknown = new ConfigSchemaSource("future", valid.schema());
        var inputs = new SequentialList<>(10_000_000, index -> {
            assertTrue(index <= 2, "must stop at the first definite budget rejection");
            return new ConfigValidationInput(index == 0 ? unknown : valid,
                LiteralValue.of(index == 2 ? "x".repeat(65537) : "valid"));
        });

        assertFailure(ConfigSchema.validateAll(inputs), 2, "BUDGET_EXCEEDED");
        assertEquals(3, inputs.reads);
    }

    @Test
    void rejectsNullApiParametersSynchronously() {
        assertThrows(NullPointerException.class, () -> ConfigSchema.checkSources(null));
        assertThrows(NullPointerException.class, () -> ConfigSchema.validateAll(null));
        assertThrows(NullPointerException.class, () -> ConfigSchema.checkSources(new SequentialList<>(1, index -> null)));
        assertThrows(NullPointerException.class, () -> ConfigSchema.validateAll(new SequentialList<>(1, index -> null)));
    }

    private static void assertFailure(Optional<ConfigBatchFailure> result, int index, String code) {
        assertTrue(result.isPresent());
        assertEquals(index, result.orElseThrow().inputIndex());
        assertEquals(code, result.orElseThrow().violation().code());
    }

    /** 调用者可以是常量空间惰性 List；接纳不能依赖 toArray 或再次读取值。 */
    private static final class SequentialList<T> extends AbstractList<T> {
        private final int size;
        private final IntFunction<T> values;
        private int reads;

        private SequentialList(int size, IntFunction<T> values) {
            this.size = size;
            this.values = values;
        }

        @Override
        public T get(int index) {
            assertEquals(reads++, index, "input must be captured in one forward pass");
            return values.apply(index);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public Object[] toArray() {
            throw new AssertionError("must admit before copying the whole caller collection");
        }

        @Override
        public <E> E[] toArray(E[] target) {
            throw new AssertionError("must admit before copying the whole caller collection");
        }
    }
}
