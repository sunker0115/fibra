package fixture.hostapi;

import com.sstlfsj.fibra.ServiceKey;
import reactor.core.publisher.Mono;

/** 与业务合同分层的测试观察端口，服务绑定仍只通过 HostServiceRegistry。 */
public interface DocumentProbe {
    ServiceKey<DocumentProbe> KEY = ServiceKey.of("fixture.document.probe", DocumentProbe.class);

    void started(String name, ClassLoader loader);
    void stopped(String name);
    Mono<DocumentApi.Result> invoke(String name, DocumentApi.Request request);
}
