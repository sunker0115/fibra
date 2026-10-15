package fixture.hostapi;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;

/** 仅测试失败物化的 loader 回收，不向生产合同增加资源入口。 */
public final class PreparationProbe {
    public static final AtomicReference<WeakReference<ClassLoader>> LOADER = new AtomicReference<>();

    private PreparationProbe() { }
}
