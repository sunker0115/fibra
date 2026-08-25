package verification.distribution.contract;

import com.sstlfsj.fibra.ServiceKey;

public interface Greeting {
    ServiceKey<Greeting> KEY =
        ServiceKey.of("verification.distribution.provider.greeting", Greeting.class);

    String greeting();
}
