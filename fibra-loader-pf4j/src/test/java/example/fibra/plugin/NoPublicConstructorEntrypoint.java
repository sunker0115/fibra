package example.fibra.plugin;

import com.sstlfsj.fibra.Plugin;
import com.sstlfsj.fibra.pf4j.VoidFibraPluginEntrypoint;

public final class NoPublicConstructorEntrypoint implements VoidFibraPluginEntrypoint {
    private NoPublicConstructorEntrypoint() {
    }

    @Override
    public Plugin<Void> create(String entryId) {
        throw new UnsupportedOperationException();
    }
}
