package com.sstlfsj.fibra.config;

/** 批量首个失败；只有集合预算错误使用 -1。 */
public record ConfigBatchFailure(int inputIndex, ConfigViolation violation) { }
