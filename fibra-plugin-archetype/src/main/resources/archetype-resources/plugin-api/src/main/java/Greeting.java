package ${package}.api;

import com.sstlfsj.fibra.ServiceKey;

@FunctionalInterface
public interface Greeting {
    ServiceKey<Greeting> KEY = ServiceKey.of("${pluginId}.greeting", Greeting.class);

    String greet(String name);
}
