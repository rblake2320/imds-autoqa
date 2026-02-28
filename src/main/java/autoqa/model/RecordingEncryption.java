package autoqa.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption wrapper for recording JSON files.
 *
 * <p>Encrypted file format (binary):
 * <pre>
 *   [4 bytes: IV length] [IV bytes] [cipher-text bytes]
 * </pre>
 *
 * <p>The session key is a 256-bit AES key managed by the caller.
 * Keys should be stored outside the recording file (e.g., environment variable,
 * key management system, or separate key file). Never commit keys to version control.
 *
 * <p>Usage example:
 * <pre>{@code
 *   // Encrypt
 *   SecretKey key = RecordingEncryption.generateKey();
 *   byte[] keyBytes = key.getEncoded();  // save keyBytes securely
 *   RecordingEncryption enc = new RecordingEncryption(key);
 *   Path encrypted = enc.encryptFile(plainJsonPath);
 *
 *   // Decrypt
 *   SecretKey key = RecordingEncryption.keyFromBytes(savedKeyBytes);
 *   RecordingEncryption enc = new RecordingEncryption(key);
 *   Path decrypted = enc.decryptFile(encryptedPath);
 * }</pre>
 */
public class RecordingEncryption {

    private static final Logger log = LoggerFactory.getLogger(RecordingEncryption.class);

    public static final String ALGORITHM = "AES";
    public static final String TRANSFORMATION = "AES/GCM/NoPadding";
    public static final int KEY_SIZE_BITS = 256;
    public static final int GCM_IV_LENGTH = 12;   // 96-bit IV recommended for GCM
    public static final int GCM_TAG_BITS = 128;

    /** File extension appended to encrypted recording files. */
    public static final String ENCRYPTED_EXT = ".enc";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public RecordingEncryption(SecretKey key) {
        this.key = key;
    }

    // ── Key management helpers ───────────────────────────────────────────────

    /**
     * Generates a new random 256-bit AES key.
     */
    public static SecretKey generateKey() {
        try {
            KeyGenerator gen = KeyGenerator.getInstance(ALGORITHM);
            gen.init(KEY_SIZE_BITS, new SecureRandom());
            return gen.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate AES key", e);
        }
    }

    /**
     * Reconstructs a SecretKey from raw bytes (e.g., read from a key file or env var).
     *
     * @param keyBytes 32 bytes for AES-256
     */
    public static SecretKey keyFromBytes(byte[] keyBytes) {
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * Decodes a Base64-encoded key string (e.g., stored in an environment variable).
     */
    public static SecretKey keyFromBase64(String base64Key) {
        return keyFromBytes(Base64.getDecoder().decode(base64Key));
    }

    /**
     * Encodes a key to Base64 for storage (e.g., in an environment variable).
     */
    public static String keyToBase64(SecretKey secretKey) {
        return Base64.getEncoder().encodeToString(secretKey.getEncoded());
    }

    // ── File-level encryption/decryption ────────────────────────────────────

    /**
     * Encrypts a JSON recording file. The output file has the same name with
     * {@value #ENCRYPTED_EXT} appended. The original file is not modified.
     *
     * @param plainPath path to the plain JSON recording
     * @return path to the encrypted output file
     */
    public Path encryptFile(Path plainPath) throws IOException {
        byte[] plaintext = Files.readAllBytes(plainPath);
        byte[] ciphertext = encrypt(plaintext);

        Path encPath = plainPath.resolveSibling(plainPath.getFileName() + ENCRYPTED_EXT);
        Files.write(encPath, ciphertext);
        log.info("Recording encrypted: {} -> {}", plainPath.getFileName(), encPath.getFileName());
        return encPath;
    }

    /**
     * Decrypts an encrypted recording file. The output file has the {@value #ENCRYPTED_EXT}
     * suffix removed. The original encrypted file is not modified.
     *
     * @param encPath path to the encrypted file (must end with {@value #ENCRYPTED_EXT})
     * @return path to the decrypted JSON file
     */
    public Path decryptFile(Path encPath) throws IOException {
        String encName = encPath.getFileName().toString();
        if (!encName.endsWith(ENCRYPTED_EXT)) {
            throw new IllegalArgumentException(
                    "Expected file with " + ENCRYPTED_EXT + " extension, got: " + encName);
        }
        String plainName = encName.substring(0, encName.length() - ENCRYPTED_EXT.length());
        Path plainPath = encPath.resolveSibling(plainName);

        byte[] ciphertext = Files.readAllBytes(encPath);
        byte[] plaintext = decrypt(ciphertext);
        Files.write(plainPath, plaintext);
        log.info("Recording decrypted: {} -> {}", encPath.getFileName(), plainPath.getFileName());
        return plainPath;
    }

    // ── String-level helpers (for testing / small payloads) ─────────────────

    /**
     * Encrypts a UTF-8 JSON string, returning Base64-encoded ciphertext.
     */
    public String encryptString(String json) {
        byte[] ciphertext = encrypt(json.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ciphertext);
    }

    /**
     * Decrypts a Base64-encoded ciphertext back to a UTF-8 JSON string.
     */
    public String decryptString(String base64Ciphertext) {
        byte[] plaintext = decrypt(Base64.getDecoder().decode(base64Ciphertext));
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    // ── Low-level encrypt/decrypt ────────────────────────────────────────────

    /**
     * Encrypts plaintext using AES-256-GCM with a random IV.
     * Output: [4 bytes ivLen][iv bytes][ciphertext+tag bytes]
     */
    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec paramSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, paramSpec);

            byte[] ciphertext = cipher.doFinal(plaintext);

            // Pack: [ivLen(4)][iv][ciphertext]
            ByteBuffer buf = ByteBuffer.allocate(4 + iv.length + ciphertext.length);
            buf.putInt(iv.length);
            buf.put(iv);
            buf.put(ciphertext);
            return buf.array();

        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypts ciphertext produced by {@link #encrypt(byte[])}.
     */
    public byte[] decrypt(byte[] packed) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(packed);
            int ivLen = buf.getInt();
            if (ivLen < 1 || ivLen > 64) {
                throw new IllegalArgumentException("Invalid IV length in packed data: " + ivLen);
            }

            byte[] iv = new byte[ivLen];
            buf.get(iv);

            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec paramSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, paramSpec);

            return cipher.doFinal(ciphertext);

        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
