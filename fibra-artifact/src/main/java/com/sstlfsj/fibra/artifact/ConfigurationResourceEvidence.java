package com.sstlfsj.fibra.artifact;

/** 原根摘要遍历的派生事实，不是新的持久身份。 */
record ConfigurationResourceEvidence(Kind kind, String rawDigest, long length) {
    static final String FILE_NAME = "fibra-config.json";

    enum Kind { ABSENT, NONREGULAR, REGULAR }

    static ConfigurationResourceEvidence absent() {
        return new ConfigurationResourceEvidence(Kind.ABSENT, null, 0);
    }

    static ConfigurationResourceEvidence nonregular() {
        return new ConfigurationResourceEvidence(Kind.NONREGULAR, null, 0);
    }

    static ConfigurationResourceEvidence regular(String rawDigest, long length) {
        return new ConfigurationResourceEvidence(Kind.REGULAR, rawDigest, length);
    }
}
