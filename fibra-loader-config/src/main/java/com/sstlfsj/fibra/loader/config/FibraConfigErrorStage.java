package com.sstlfsj.fibra.loader.config;

/** 配置装载失败发生的稳定阶段。 */
public enum FibraConfigErrorStage {
    READ,
    PARSE,
    VALIDATE,
    RESOLVE,
    CONVERT,
    DISPOSE,
    APPLY,
    WRITE,
    ROLLBACK
}
