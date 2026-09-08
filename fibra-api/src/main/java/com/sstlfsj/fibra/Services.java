package com.sstlfsj.fibra;

import java.util.Optional;

public interface Services {
    <T> ServiceRegistration<T> provide(ServiceKey<T> key, T value);

    <T> Optional<T> find(ServiceKey<T> key);

    <T> T require(ServiceKey<T> key);

    <T> ServiceRef<T> reference(ServiceKey<T> key);
}
