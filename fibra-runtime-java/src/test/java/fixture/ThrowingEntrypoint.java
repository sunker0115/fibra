package fixture;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;

public final class ThrowingEntrypoint implements PluginEntrypoint<Void> {
    @Override
    public PluginDefinition<Void> definition() {
        throw new PreparationFailure();
    }

    public static final class PreparationFailure extends RuntimeException {
        public PreparationFailure() { super("fixture preparation failed"); }
    }
}
