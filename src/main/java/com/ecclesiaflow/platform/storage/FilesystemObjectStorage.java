package com.ecclesiaflow.platform.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Local-disk {@link ObjectStorage} adapter — the default backend for development
 * and tests, so the platform runs with images off the database and without any
 * external service. Objects are written as plain files under a base directory,
 * keyed exactly like the S3 adapter ({@code <prefix>/<uuid>.<ext>}).
 *
 * <p>Not intended for multi-instance production (a shared object store is);
 * switch {@code ecclesiaflow.object-storage.provider} to {@code s3} there.</p>
 */
public class FilesystemObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemObjectStorage.class);

    private final Path baseDir;

    public FilesystemObjectStorage(String basePath) {
        String resolved = (basePath == null || basePath.isBlank())
                ? Paths.get(System.getProperty("java.io.tmpdir"), "ecclesiaflow-object-storage").toString()
                : basePath;
        this.baseDir = Paths.get(resolved).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new ObjectStorageException("could not create object-storage base directory", e);
        }
        log.info("OBJECT-STORAGE: filesystem adapter active, base={}", baseDir);
    }

    @Override
    public StoredObjectRef put(String keyPrefix, byte[] data, String contentType) {
        requireBytes(data);
        String key = StorageKeys.newKey(keyPrefix, contentType);
        Path target = resolveWithinBase(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new ObjectStorageException("could not write object", e);
        }
        return new StoredObjectRef(key);
    }

    @Override
    public Optional<StoredObject> get(String key) {
        Path source = resolveWithinBase(key);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            byte[] data = Files.readAllBytes(source);
            return Optional.of(new StoredObject(data, StorageKeys.contentTypeForKey(key)));
        } catch (IOException e) {
            throw new ObjectStorageException("could not read object", e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolveWithinBase(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new ObjectStorageException("could not delete object", e);
        }
    }

    @Override
    public String providerName() {
        return "filesystem";
    }

    private void requireBytes(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be empty");
        }
    }

    /**
     * Resolves {@code key} under the base directory and rejects any result that
     * escapes it — defence in depth against a malformed/hostile key even though
     * keys are provider-generated.
     */
    private Path resolveWithinBase(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        Path resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new ObjectStorageException("resolved object path escapes the storage base directory");
        }
        return resolved;
    }
}
