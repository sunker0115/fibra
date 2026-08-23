package com.sstlfsj.fibra.loader.pf4j;

/** 同步 loader 管理操作因事务占用或线程边界不允许而被拒绝。 */
public final class FibraPluginLoaderBusyException extends IllegalStateException {
    FibraPluginLoaderBusyException(String message) {
        super(message);
    }
}
