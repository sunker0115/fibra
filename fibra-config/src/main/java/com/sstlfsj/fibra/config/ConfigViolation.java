package com.sstlfsj.fibra.config;

/** 只包含稳定错误码、关键词和受控静态路径的公开诊断。 */
public record ConfigViolation(String code, String keyword, String instancePointer) { }
