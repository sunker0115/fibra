package example.fibra.contract;

import com.sstlfsj.fibra.ServiceKey;

@FunctionalInterface
public interface Greeting {
    ServiceKey<Greeting> KEY = ServiceKey.of("example.provider.greeting", Greeting.class);

    String greeting();
}
