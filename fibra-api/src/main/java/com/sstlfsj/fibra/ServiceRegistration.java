package com.sstlfsj.fibra;

public interface ServiceRegistration<T> extends Disposable {
    ServiceKey<T> key();

    T value();
}
