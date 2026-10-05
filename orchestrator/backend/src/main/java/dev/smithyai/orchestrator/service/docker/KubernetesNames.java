package dev.smithyai.orchestrator.service.docker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Container names as Kubernetes accepts them.
 *
 * <p>A StatefulSet name ends up in its pods' {@code controller-revision-hash}
 * label, which leaves about 52 characters, and must be a lower-case DNS label.
 * Orchestrator names can be longer and carry dots or underscores, so they are
 * rewritten here; the original travels along as an annotation.
 */
final class KubernetesNames {

    static final int MAX_LENGTH = 40;
    private static final int HASH_LENGTH = 8;

    private KubernetesNames() {}

    /** A stable DNS-label name for {@code name}; unchanged when it already fits. */
    static String objectName(String name) {
        String cleaned = clean(name.toLowerCase(Locale.ROOT), "[^a-z0-9-]");
        if (cleaned.equals(name) && cleaned.length() <= MAX_LENGTH) {
            return cleaned;
        }
        String prefix = cleaned.substring(0, Math.min(cleaned.length(), MAX_LENGTH - HASH_LENGTH - 1));
        prefix = prefix.replaceAll("-+$", "");
        String hash = hash(name);
        return prefix.isEmpty() ? "t" + hash : prefix + "-" + hash;
    }

    /** A valid label value: at most 63 characters of [A-Za-z0-9._-], alphanumeric at both ends. */
    static String labelValue(String value) {
        String cleaned = clean(value, "[^A-Za-z0-9._-]").replaceAll("^[._-]+|[._-]+$", "");
        if (cleaned.length() > 63) {
            cleaned = cleaned.substring(0, 63).replaceAll("[._-]+$", "");
        }
        return cleaned;
    }

    private static String clean(String value, String invalid) {
        return value.replaceAll(invalid, "-").replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
