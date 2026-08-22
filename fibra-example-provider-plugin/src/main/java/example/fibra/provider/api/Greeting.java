package example.fibra.provider.api;

import com.sstlfsj.fibra.ServiceKey;

/** 由 provider 插件 ClassLoader 拥有的跨插件服务契约。 */
public interface Greeting {
    ServiceKey<Greeting> KEY =
        ServiceKey.of("example.provider.greeting", Greeting.class);

    String greeting();
}
