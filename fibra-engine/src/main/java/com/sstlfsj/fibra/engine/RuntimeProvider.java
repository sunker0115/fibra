package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.RuntimeId;
import java.util.List;

/**
 * Reusable runtime factory with immutable configuration and catalog metadata.
 * Each {@link #create(RuntimeHostServices)} call returns a new driver bound only
 * to the supplied Host; the Engine owns and closes that driver. Implementations
 * must not retain drivers or Host lifecycle resources. Concurrent create calls
 * are not part of this contract.
 */
public interface RuntimeProvider {
    RuntimeId id();

    String contractIdentity();

    List<BuiltInPluginPackage> builtInPackages();

    /** Creates a fresh, Host-bound driver. Sequential calls are supported. */
    RuntimeDriver create(RuntimeHostServices services);
}
