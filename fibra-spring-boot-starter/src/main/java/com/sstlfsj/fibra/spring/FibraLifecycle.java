package com.sstlfsj.fibra.spring;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Fibra;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.loader.config.FibraConfigLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginLoader;
import com.sstlfsj.fibra.loader.pf4j.FibraPluginWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Optional;

/** 协调 Fibra 启动装载、就绪门禁与有序关闭；由 Spring 生命周期驱动。 */
public final class FibraLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(FibraLifecycle.class);

    private final Context root;
    private final FibraPluginLoader loader;
    private final FibraConfigLoader configLoader;
    private final FibraPluginWatcher watcher; // 可为 null
    private final FibraProperties props;
    private volatile boolean running = false;

    public FibraLifecycle(Context root, FibraPluginLoader loader,
                          FibraConfigLoader configLoader,
                          FibraPluginWatcher watcher, FibraProperties props) {
        this.root = root;
        this.loader = loader;
        this.configLoader = configLoader;
        this.watcher = watcher;
        this.props = props;
    }

    @Override
    public void start() {
        loader.loadArtifacts();
        configLoader.load();
        Duration timeout = props.getReadinessTimeout();
        for (String entryId : props.getStartupRequiredPlugins()) {
            Optional<Fibra> fibra = loader.fibra(entryId);
            if (fibra.isEmpty()) {
                throw new IllegalStateException(
                    "启动必需插件未装载: entryId=" + entryId);
            }
            Fibra f = fibra.get().ready().block(timeout);
            if (f == null || f.state() != FibraState.ACTIVE) {
                throw new IllegalStateException(
                    "启动必需插件未 ACTIVE: entryId=" + entryId
                        + " state=" + (f == null ? "null" : f.state()));
            }
        }
        running = true;
    }

    @Override
    public void stop() {
        try {
            if (watcher != null) {
                try {
                    watcher.close();
                } catch (RuntimeException e) {
                    log.error("关闭 FibraPluginWatcher 失败", e);
                }
            }
            try {
                loader.close();
            } catch (RuntimeException e) {
                log.error("关闭 FibraPluginLoader 失败", e);
            }
            try {
                root.closeAsync().block(props.getShutdownTimeout());
            } catch (RuntimeException e) {
                log.error("关闭 Fibra 根 Context 失败", e);
            }
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return DEFAULT_PHASE; }
}
