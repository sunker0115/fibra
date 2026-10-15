package com.sstlfsj.fibra.runtime.java;

import com.sstlfsj.fibra.artifact.ArtifactId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Provider-owned frozen composition; no loader, service or execution resources are created. */
final class JavaApiContracts {
    private static final List<String> DEFAULT_PARENT_PACKAGES = List.of(
        "java.", "javax.", "jdk.", "sun.", "com.sstlfsj.fibra.",
        "reactor.", "org.reactivestreams.", "org.slf4j.");

    private final Map<String, JavaApiContract> contracts;
    private final Map<String, Class<?>> classes;
    private final Map<String, String> owners;
    private final String identity;

    JavaApiContracts(List<JavaApiContract> values) {
        if (values == null) throw new IllegalArgumentException("Java API contracts must not be null");
        var byId = new TreeMap<String, JavaApiContract>();
        var byName = new TreeMap<String, Class<?>>();
        var ownerByName = new TreeMap<String, String>();
        for (var contract : values) {
            if (contract == null) throw new IllegalArgumentException("Java API contract must not be null");
            if (byId.putIfAbsent(contract.id(), contract) != null) {
                throw new IllegalArgumentException("duplicate Java API id: " + contract.id());
            }
            for (var type : contract.classes()) {
                try {
                    if (baseline(type.getName()) != null) {
                        throw new IllegalArgumentException("Java API overrides shared baseline: " + type.getName());
                    }
                } catch (LinkageError | SecurityException failure) {
                    throw new IllegalArgumentException("cannot resolve Java API baseline: " + type.getName(), failure);
                }
                if (byName.putIfAbsent(type.getName(), type) != null) {
                    throw new IllegalArgumentException("duplicate Java API owner for " + type.getName()
                        + ": " + ownerByName.get(type.getName()) + " and " + contract.id());
                }
                ownerByName.put(type.getName(), contract.id());
            }
        }
        contracts = Map.copyOf(byId);
        classes = Map.copyOf(byName);
        owners = Map.copyOf(ownerByName);
        JavaApiSignatures.validate(classes, owners);
        identity = fingerprint(byId);
    }

    static List<String> defaultParentPackages() { return DEFAULT_PARENT_PACKAGES; }

    String identity() { return identity; }

    Set<String> names() { return classes.keySet(); }

    Map<String, Class<?>> bind(ArtifactId owner, Map<String, String> imports) {
        try {
            if (imports == null) throw new IllegalArgumentException("Java API imports must not be null");
            var selected = new LinkedHashMap<String, Class<?>>();
            for (var entry : imports.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                    throw new IllegalArgumentException("Java API import id and version must not be blank");
                }
                var contract = contracts.get(entry.getKey());
                if (contract == null) {
                    throw new IllegalArgumentException("missing Java API contract: " + entry.getKey());
                }
                if (!contract.version().equals(entry.getValue())) {
                    throw new IllegalArgumentException("Java API version mismatch for " + entry.getKey()
                        + ": required " + entry.getValue() + ", available " + contract.version());
                }
                for (var type : contract.classes()) selected.put(type.getName(), type);
            }
            JavaApiSignatures.validate(selected, owners);
            return Map.copyOf(selected);
        } catch (IllegalArgumentException failure) {
            throw new JavaRuntimeException(JavaRuntimePhase.LOAD, owner,
                "cannot bind Java API imports for " + owner + ": " + failure.getMessage(), failure);
        }
    }

    /** Resolve actual shared identity rather than treating package preferences as a whitelist. */
    static Class<?> baseline(String name) {
        try {
            return PluginClassLoader.platformOrParentClass(name, JavaApiContracts.class.getClassLoader(),
                DEFAULT_PARENT_PACKAGES);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static String fingerprint(Map<String, JavaApiContract> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var contract : values.values()) {
                update(digest, contract.id());
                update(digest, contract.version());
                update(digest, Integer.toString(contract.classes().size()));
                for (var name : contract.classes().stream().map(Class::getName).sorted().toList()) {
                    update(digest, name);
                }
            }
            return JavaRuntimeProvider.CONTRACT_IDENTITY + ":" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
