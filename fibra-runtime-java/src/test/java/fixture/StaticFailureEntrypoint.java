package fixture;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;

/** 只允许静态类型校验；任何初始化都是测试失败。 */
public final class StaticFailureEntrypoint implements PluginEntrypoint<Void> {
    static { fail(); }

    private static void fail() { throw new IllegalStateException("static initialization must not run during prepare"); }

    @Override public PluginDefinition<Void> definition() {
        throw new IllegalStateException("definition must not run during prepare");
    }
}
