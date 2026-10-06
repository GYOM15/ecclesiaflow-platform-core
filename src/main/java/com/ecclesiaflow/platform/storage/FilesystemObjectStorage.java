package com.ecclesiaflow.platform.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Local-disk adapter for development and tests; multi-instance production needs the {@code s3} provider. */
public class FilesystemObjectStorage implements ObjectStorage {

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
    }

    public Path baseDir() {
        return baseDir;
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

    /** Rejects a key that escapes the base directory: defence in depth, although keys are generated. */
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
