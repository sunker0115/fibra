package external.consumer.contract;

import com.sstlfsj.fibra.ServiceKey;

public interface Greeting {
    ServiceKey<Greeting> KEY =
        ServiceKey.of("external.consumer.provider.greeting", Greeting.class);

    String greeting();
}
