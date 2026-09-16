package com.sstlfsj.fibra.runtime.client;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.FacetRole;
import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginFacet;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.client.protocol.ClientMessage;
import com.sstlfsj.fibra.engine.ArtifactRuntime;
import com.sstlfsj.fibra.engine.DeploymentTargetCompiler;
import com.sstlfsj.fibra.engine.PreparedArtifact;
import com.sstlfsj.fibra.engine.PreparedArtifactUpdate;
import com.sstlfsj.fibra.engine.RuntimeArtifactInspection;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Host 侧 client facet 的纯静态制品准备；不拥有 transport 或 execution。 */
public final class ClientArtifactRuntime implements ArtifactRuntime {
    public static final RuntimeId ID = new RuntimeId("client");
    public static final String DESCRIPTOR = "fibra-client.yaml";

    private static final byte[] DIGEST_PREFIX =
        "fibra-content-v1\0".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> DESCRIPTOR_FIELDS = Set.of(
        "format", "entryModule", "resources");
    private static final Set<String> RESOURCE_FIELDS = Set.of(
        "path", "digest", "byteLength");
    private static final YAMLMapper YAML = YAMLMapper.builder(YAMLFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(4096)
                .maxDocumentLength(64 * 1024).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private final AtomicLong resourceSequence = new AtomicLong();
    private Map<ArtifactId, StoredArtifact> active = Map.of();
    private Update pending;
    private boolean closing;
    private Mono<Void> close;

    @Override
    public RuntimeId id() { return ID; }

    @Override
    public Mono<Void> probe(PluginFacet source) {
        return Mono.fromRunnable(() -> validateFacet(source));
    }

    @Override
    public Mono<RuntimeArtifactInspection> inspect(ManagedFacet facet) {
        return Mono.fromSupplier(() -> {
            validateFacet(facet.facet());
            var descriptor = readPayload(facet);
            return new RuntimeArtifactInspection(ID, facet.artifactId(), Map.of(
                "entryModule", descriptor.entryModule(),
                "resourceCount", descriptor.resources().size()));
        });
    }

    @Override
    public synchronized PreparedArtifactUpdate createUpdate(
        List<DeploymentTargetCompiler.CompiledFacet> target) {
        Objects.requireNonNull(target, "target");
        if (closing) throw new IllegalStateException("client artifact runtime is closing");
        if (pending != null) throw new IllegalStateException(
            "client artifact update is not closed");
        var frozenTarget = List.copyOf(target);
        frozenTarget.forEach(value -> {
            if (!ID.equals(value.facet().facet().runtimeId())) {
                throw new IllegalArgumentException("client artifact runtime received another runtime facet");
            }
        });
        pending = new Update(frozenTarget, Map.copyOf(active));
        return pending;
    }

    @Override
    public synchronized Snapshot snapshot() {
        var resources = new ArrayList<Resource>();
        active.values().stream()
            .sorted(Comparator.comparing(value -> value.prepared().facet().artifactId().value()))
            .forEach(value -> resources.add(value.resource(ResourceState.ACTIVE)));
        if (pending != null) resources.addAll(pending.resources());
        return new Snapshot(ID, resources);
    }

    @Override
    public synchronized Mono<Void> closeAsync() {
        if (close == null) {
            closing = true;
            var update = pending;
            close = Mono.defer(() -> update == null ? Mono.<Void>empty() : update.closeAsync())
                .then(Mono.<Void>fromRunnable(() -> {
                    synchronized (ClientArtifactRuntime.this) {
                        active = Map.of();
                    }
                })).cache();
        }
        return close;
    }

    /** Task 9 的资源 provider 只可在同包内从当前静态制品读取已验证 bytes。 */
    byte[] read(ArtifactId artifactId, ClientMessage.ResourceDescriptor descriptor,
                long maximumBytes) {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(descriptor, "descriptor");
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must be non-negative");
        }
        if (descriptor.byteLength() > maximumBytes
            || descriptor.byteLength() >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("client resource exceeds maximumBytes");
        }
        final Path source;
        synchronized (this) {
            var artifact = active.get(artifactId);
            if (artifact == null) throw new IllegalArgumentException("client artifact is not active");
            source = artifact.locations().get(descriptor);
            if (source == null) throw new IllegalArgumentException("client resource is not active");
        }
        try (var input = Files.newInputStream(source)) {
            var bytes = input.readNBytes(Math.toIntExact(descriptor.byteLength()) + 1);
            if (bytes.length != descriptor.byteLength()
                || !descriptor.digest().equals(sha256(bytes))) {
                throw new IllegalArgumentException(
                    "client resource changed after static preparation");
            }
            return bytes;
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read client resource", failure);
        }
    }

    private static void validateFacet(PluginFacet facet) {
        Objects.requireNonNull(facet, "facet");
        if (!ID.equals(facet.runtimeId()) || !FacetRole.CLIENT.equals(facet.role())) {
            throw new IllegalArgumentException("client artifact runtime requires a client facet");
        }
        if (!facet.executionTarget().value().startsWith("client:")) {
            throw new IllegalArgumentException("client facet requires a client execution target");
        }
        if (!Files.isDirectory(facet.payload(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("client facet payload must be a directory");
        }
    }

    private static ParsedPayload readPayload(ManagedFacet facet) {
        validateFacet(facet.facet());
        var payload = facet.facet().payload();
        var expectedDigest = facet.facet().payloadDigest();
        try {
            validateTree(payload);
            if (!expectedDigest.equals(contentDigest(payload))) {
                throw new IllegalArgumentException("client facet payload digest does not match managed content");
            }
            var descriptorPath = payload.resolve(DESCRIPTOR);
            if (!Files.isRegularFile(descriptorPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("client facet requires a regular " + DESCRIPTOR);
            }
            if (Files.size(descriptorPath) > 64 * 1024) {
                throw new IllegalArgumentException("client facet descriptor exceeds 64 KiB");
            }
            var values = exactMap(YAML.readValue(Files.readAllBytes(descriptorPath), Object.class),
                DESCRIPTOR_FIELDS, "client descriptor");
            if (!(values.get("format") instanceof Integer format) || format != 1) {
                throw new IllegalArgumentException("client descriptor format must be integer 1");
            }
            var entryModule = resourcePath(values.get("entryModule"), "entryModule");
            var resources = resources(payload, values.get("resources"));
            if (resources.descriptors().stream().noneMatch(resource -> entryModule.equals(resource.path()))) {
                throw new IllegalArgumentException("entryModule must reference a client resource");
            }
            var parsed = new ParsedPayload(entryModule, resources.descriptors(),
                resources.locations());
            if (!expectedDigest.equals(contentDigest(payload))) {
                throw new IllegalArgumentException(
                    "client facet payload changed while reading managed content");
            }
            return parsed;
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read client facet payload", failure);
        }
    }

    private static ParsedResources resources(Path payload, Object raw) throws IOException {
        if (!(raw instanceof List<?> entries)) {
            throw new IllegalArgumentException("resources must be an array");
        }
        var declared = new LinkedHashMap<String, ClientMessage.ResourceDescriptor>();
        var locations = new LinkedHashMap<ClientMessage.ResourceDescriptor, Path>();
        for (var entry : entries) {
            var values = exactMap(entry, RESOURCE_FIELDS, "client resource");
            var path = resourcePath(values.get("path"), "resources.path");
            if (DESCRIPTOR.equals(path)) {
                throw new IllegalArgumentException("client descriptor is not a data-plane resource");
            }
            var digest = digest(values.get("digest"), "resources.digest");
            var byteLength = byteLength(values.get("byteLength"));
            var source = payload.resolve(path).normalize();
            if (!source.startsWith(payload) || !Files.isRegularFile(source,
                LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
                throw new IllegalArgumentException("client resource must be a regular payload file: " + path);
            }
            verifyResource(source, digest, byteLength, path);
            var descriptor = new ClientMessage.ResourceDescriptor(path, digest, byteLength);
            if (declared.putIfAbsent(path, descriptor) != null) {
                throw new IllegalArgumentException("duplicate client resource path: " + path);
            }
            locations.put(descriptor, source);
        }
        var actual = payloadFiles(payload);
        if (!declared.keySet().equals(actual)) {
            throw new IllegalArgumentException("client resources must enumerate every payload file except "
                + DESCRIPTOR);
        }
        var descriptors = declared.values().stream()
            .sorted(Comparator.comparing(ClientMessage.ResourceDescriptor::path)).toList();
        var orderedLocations = new LinkedHashMap<ClientMessage.ResourceDescriptor, Path>();
        descriptors.forEach(descriptor -> orderedLocations.put(descriptor,
            locations.get(descriptor)));
        return new ParsedResources(descriptors, Map.copyOf(orderedLocations));
    }

    private static void verifyResource(Path source, String expectedDigest,
                                       long expectedLength, String path)
        throws IOException {
        if (Files.size(source) != expectedLength) {
            throw new IllegalArgumentException(
                "client resource byteLength does not match: " + path);
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            long actual = 0;
            try (var input = Files.newInputStream(source)) {
                var buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0;) {
                    digest.update(buffer, 0, read);
                    actual += read;
                }
            }
            if (actual != expectedLength
                || !expectedDigest.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IllegalArgumentException(
                    "client resource digest or byteLength does not match: " + path);
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Set<String> payloadFiles(Path payload) throws IOException {
        try (var paths = Files.walk(payload)) {
            var result = new LinkedHashSet<String>();
            for (var path : paths.toList()) {
                if (path.equals(payload) || path.equals(payload.resolve(DESCRIPTOR))) continue;
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    result.add(normalizedRelative(payload, path));
                }
            }
            return Set.copyOf(result);
        }
    }

    private static Map<String, Object> exactMap(Object raw, Set<String> fields,
                                                 String name) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        var values = new LinkedHashMap<String, Object>();
        rawMap.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(name + " keys must be strings");
            }
            values.put(text, value);
        });
        if (!values.keySet().equals(fields)) {
            var unknown = values.keySet().stream().filter(key -> !fields.contains(key)).toList();
            var missing = fields.stream().filter(key -> !values.containsKey(key)).toList();
            throw new IllegalArgumentException("invalid " + name + " fields; unknown=" + unknown
                + ", missing=" + missing);
        }
        return values;
    }

    private static String resourcePath(Object raw, String name) {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        if (value.startsWith("/") || value.contains("\\")) {
            throw new IllegalArgumentException(name + " must be a relative logical resource path");
        }
        var segments = value.split("/", -1);
        if (Arrays.stream(segments).anyMatch(segment -> segment.isEmpty()
            || segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException(name + " must use normalized logical segments");
        }
        var colon = segments[0].indexOf(':');
        if (colon > 0 && segments[0].substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            throw new IllegalArgumentException(name + " must not be a URI or host path");
        }
        return value;
    }

    private static String digest(Object raw, String name) {
        if (!(raw instanceof String value) || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    private static long byteLength(Object raw) {
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException("resources.byteLength must be an integer");
        }
        var value = ((Number) raw).longValue();
        if (value < 0) throw new IllegalArgumentException("resources.byteLength must be non-negative");
        return value;
    }

    private static void validateTree(Path payload) throws IOException {
        try (var paths = Files.walk(payload)) {
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException("client facet payload must not contain symbolic links");
                }
                var attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                    throw new IllegalArgumentException("client facet payload has an unsupported entry");
                }
            }
        }
    }

    private static String contentDigest(Path source) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(DIGEST_PREFIX);
            digest.update((byte) 'T');
            try (var paths = Files.walk(source)) {
                var entries = paths.filter(path -> !path.equals(source))
                    .sorted(Comparator.comparing(path -> normalizedRelative(source, path),
                        ClientArtifactRuntime::compareUtf8)).toList();
                for (var path : entries) {
                    var relative = normalizedRelative(source, path).getBytes(StandardCharsets.UTF_8);
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        digest.update((byte) 'D');
                    } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        digest.update((byte) 'F');
                    } else {
                        throw new IOException("client payload contains an unsupported entry");
                    }
                    updateLength(digest, relative.length);
                    digest.update(relative);
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        updateFile(digest, path);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateFile(MessageDigest digest, Path path) throws IOException {
        var expected = Files.size(path);
        updateLength(digest, expected);
        long actual = 0;
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, read);
                actual += read;
            }
        }
        if (actual != expected) throw new IOException("client payload changed while reading");
    }

    private static void updateLength(MessageDigest digest, long length) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(length).array());
    }

    private static String normalizedRelative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private final class Update implements PreparedArtifactUpdate {
        private final List<DeploymentTargetCompiler.CompiledFacet> target;
        private Map<ArtifactId, StoredArtifact> current;
        private Map<ArtifactId, StoredArtifact> candidate;
        private Map<ArtifactId, StoredArtifact> fresh = Map.of();
        private Map<ArtifactId, StoredArtifact> retired = Map.of();
        private boolean prepared;
        private boolean adopted;
        private boolean closed;
        private Mono<Void> preparation;
        private Mono<Void> close;

        private Update(List<DeploymentTargetCompiler.CompiledFacet> target,
                       Map<ArtifactId, StoredArtifact> current) {
            this.target = target;
            this.current = current;
        }

        @Override
        public Mono<Void> prepareAsync() {
            synchronized (ClientArtifactRuntime.this) {
                if (closed || closing) return Mono.error(new IllegalStateException(
                    "client artifact update is closed"));
                if (preparation == null) preparation = Mono.<Void>fromRunnable(this::prepare).cache();
                return preparation;
            }
        }

        private void prepare() {
            var next = new LinkedHashMap<ArtifactId, StoredArtifact>();
            var created = new LinkedHashMap<ArtifactId, StoredArtifact>();
            for (var compiled : target) {
                var existing = current.get(compiled.facet().artifactId());
                if (matches(existing, compiled)) {
                    next.put(compiled.facet().artifactId(), existing);
                    continue;
                }
                var parsed = readPayload(compiled.facet());
                var preparedArtifact = new ClientPreparedArtifact(compiled.facet(),
                    compiled.dependencies(), parsed.entryModule(), parsed.resources(),
                    compiled.facet().facet().executionTarget(),
                    compiled.facet().facet().requiredCapabilities());
                var artifact = new StoredArtifact(preparedArtifact, parsed.locations(),
                    "client:" + compiled.facet().artifactId().value() + ':'
                        + resourceSequence.incrementAndGet());
                next.put(compiled.facet().artifactId(), artifact);
                created.put(compiled.facet().artifactId(), artifact);
            }
            synchronized (ClientArtifactRuntime.this) {
                if (closed || closing) throw new IllegalStateException(
                    "client artifact update is closed");
                candidate = Map.copyOf(next);
                fresh = Map.copyOf(created);
                prepared = true;
            }
        }

        private static boolean matches(StoredArtifact existing,
                                       DeploymentTargetCompiler.CompiledFacet compiled) {
            return existing != null && existing.prepared().facet().equals(compiled.facet())
                && existing.prepared().dependencies().equals(compiled.dependencies());
        }

        @Override
        public Map<ArtifactId, PreparedArtifact> preparedArtifacts() {
            synchronized (ClientArtifactRuntime.this) {
                requirePrepared();
                var result = new LinkedHashMap<ArtifactId, PreparedArtifact>();
                candidate.forEach((id, stored) -> result.put(id, stored.prepared()));
                return Map.copyOf(result);
            }
        }

        @Override
        public Set<ArtifactId> affectedArtifacts() {
            var affected = new LinkedHashSet<ArtifactId>();
            synchronized (ClientArtifactRuntime.this) {
                var next = new LinkedHashMap<ArtifactId,
                    DeploymentTargetCompiler.CompiledFacet>();
                target.forEach(compiled -> next.put(compiled.facet().artifactId(), compiled));
                current.forEach((artifactId, existing) -> {
                    var replacement = next.get(artifactId);
                    if (replacement == null || !matches(existing, replacement)) {
                        affected.add(artifactId);
                    }
                });
                next.forEach((artifactId, replacement) -> {
                    if (!matches(current.get(artifactId), replacement)) {
                        affected.add(artifactId);
                    }
                });
            }
            return Set.copyOf(affected);
        }

        @Override
        public void adopt() {
            synchronized (ClientArtifactRuntime.this) {
                requirePrepared();
                if (closed || adopted || closing) {
                    throw new IllegalStateException("client artifact update cannot be adopted");
                }
                var replaced = new LinkedHashMap<ArtifactId, StoredArtifact>();
                current.forEach((artifactId, existing) -> {
                    if (candidate.get(artifactId) != existing) replaced.put(artifactId, existing);
                });
                retired = Map.copyOf(replaced);
                active = candidate;
                adopted = true;
            }
        }

        @Override
        public synchronized Mono<Void> closeAsync() {
            if (close == null) {
                closed = true;
                var started = preparation == null ? Mono.<Void>empty()
                    : preparation.onErrorResume(ignored -> Mono.empty());
                close = started.then(Mono.<Void>fromRunnable(() -> {
                    synchronized (ClientArtifactRuntime.this) {
                        candidate = Map.of();
                        current = Map.of();
                        fresh = Map.of();
                        retired = Map.of();
                        prepared = false;
                        if (pending == this) pending = null;
                    }
                })).cache();
            }
            return close;
        }

        private List<Resource> resources() {
            if (closed || !prepared) return List.of();
            var values = adopted ? retired.values() : fresh.values();
            var state = adopted ? ResourceState.RETIRED : ResourceState.PREPARED;
            return values.stream()
                .sorted(Comparator.comparing(value -> value.prepared().facet().artifactId().value()))
                .map(value -> value.resource(state)).toList();
        }

        private void requirePrepared() {
            if (!prepared) throw new IllegalStateException("client artifact update is not prepared");
        }
    }

    private record ParsedPayload(String entryModule,
                                 List<ClientMessage.ResourceDescriptor> resources,
                                 Map<ClientMessage.ResourceDescriptor, Path> locations) {
        private ParsedPayload {
            resources = List.copyOf(resources);
            locations = Map.copyOf(locations);
        }
    }

    private record ParsedResources(List<ClientMessage.ResourceDescriptor> descriptors,
                                   Map<ClientMessage.ResourceDescriptor, Path> locations) {
        private ParsedResources {
            descriptors = List.copyOf(descriptors);
            locations = Map.copyOf(locations);
        }
    }

    private record StoredArtifact(ClientPreparedArtifact prepared,
                                  Map<ClientMessage.ResourceDescriptor, Path> locations,
                                  String identity) {
        private StoredArtifact {
            Objects.requireNonNull(prepared, "prepared");
            locations = Map.copyOf(locations);
            Objects.requireNonNull(identity, "identity");
        }

        private Resource resource(ResourceState state) {
            return new Resource(prepared.facet(), identity, state, null);
        }
    }

}
