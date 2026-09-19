package verification.distribution;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoreConsumerTest {
    @Test
    void consumesCoreOnlyFromPublishedCoordinates() {
        try (var runtime = FibraRuntime.create()) {
            var definition = PluginDefinition.builder("sample", Void.class,
                () -> (context, config) -> Mono.empty()).build();
            var instance = runtime.rootScope().context().plugins()
                .mount("sample", definition.prepare(null));
            instance.settled().block();
            assertEquals("ACTIVE", instance.state().name());
        }
    }
}
