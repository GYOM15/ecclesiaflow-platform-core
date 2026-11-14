package com.ecclesiaflow.platform.idempotency;

import org.springframework.util.ReflectionUtils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.SequencedCollection;

/**
 * Fingerprints a creation request as the hex SHA-256 of its parts. Each part is written with its length, so
 * {@code ("ab", "c")} and {@code ("a", "bc")} differ, and {@code null} differs from the text {@code "null"}.
 * A part is read through {@code String.valueOf}, so pass plain values (text, ids, numbers, enums, instants)
 * and ordered lists of them: a record holding a set prints it in no fixed order. An unordered collection, a
 * map, a primitive array or an object without its own {@code toString} is refused, since its text could
 * differ between two identical requests and turn a retry into a conflict.
 */
public final class IdempotencyFingerprint {

    private static final byte NULL = 0;
    private static final byte VALUE = 1;
    private static final byte SEQUENCE = 2;

    private IdempotencyFingerprint() {
    }

    /**
     * @param parts every field of the request, path parameters included; an ordered collection or an object
     *              array is fingerprinted element by element
     * @throws IllegalArgumentException on a part without a stable text form
     */
    public static String of(Object... parts) {
        return HexFormat.of().formatHex(sha256().digest(canonicalForm(parts)));
    }

    /**
     * The bytes {@link #of(Object...)} digests, for a keyed digest that must tell requests apart exactly as the
     * fingerprint does.
     *
     * @throws IllegalArgumentException on a part without a stable text form
     */
    public static byte[] canonicalForm(Object... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSequence(out, Arrays.asList(parts == null ? new Object[]{null} : parts));
        return out.toByteArray();
    }

    private static void writeSequence(ByteArrayOutputStream out, Collection<?> parts) {
        out.write(SEQUENCE);
        out.writeBytes(lengthOf(parts.size()));
        for (Object part : parts) {
            write(out, part);
        }
    }

    private static void write(ByteArrayOutputStream out, Object part) {
        if (part == null) {
            out.write(NULL);
            return;
        }
        if (part instanceof SequencedCollection<?> sequence) {
            writeSequence(out, sequence);
            return;
        }
        if (part instanceof Object[] array) {
            writeSequence(out, Arrays.asList(array));
            return;
        }
        refuseUnstable(part);
        byte[] text = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
        out.write(VALUE);
        out.writeBytes(lengthOf(text.length));
        out.writeBytes(text);
    }

    private static void refuseUnstable(Object part) {
        String type = part.getClass().getName();
        if (part instanceof Collection<?> || part instanceof Map<?, ?>) {
            throw new IllegalArgumentException("A " + type + " has no stable order: fingerprint its elements "
                    + "sorted, as a List");
        }
        // Object's own toString is found for every class, so the lookup never comes back empty.
        Method text = ReflectionUtils.findMethod(part.getClass(), "toString");
        if (part.getClass().isArray() || text.getDeclaringClass() == Object.class) {
            throw new IllegalArgumentException("A " + type + " prints its identity, not its content: "
                    + "fingerprint its fields");
        }
    }

    private static byte[] lengthOf(int length) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(length).array();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM ships SHA-256", e);
        }
    }
}
