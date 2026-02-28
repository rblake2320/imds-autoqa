package autoqa.model;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link RecordingEncryption}.
 */
public class RecordingEncryptionTest {

    private SecretKey key;
    private RecordingEncryption encryption;

    @BeforeClass
    public void setUp() {
        key = RecordingEncryption.generateKey();
        encryption = new RecordingEncryption(key);
    }

    // ── generateKey ──────────────────────────────────────────────────────────

    @Test
    public void generateKey_returns256BitAesKey() {
        SecretKey k = RecordingEncryption.generateKey();
        assertThat(k.getAlgorithm()).isEqualTo("AES");
        assertThat(k.getEncoded()).hasSize(32); // 256 bits = 32 bytes
    }

    @Test
    public void generateKey_twoCallsReturnDifferentKeys() {
        SecretKey k1 = RecordingEncryption.generateKey();
        SecretKey k2 = RecordingEncryption.generateKey();
        assertThat(k1.getEncoded()).isNotEqualTo(k2.getEncoded());
    }

    // ── keyFromBytes / keyToBase64 ────────────────────────────────────────────

    @Test
    public void keyRoundTrip_fromBytesToBase64AndBack() {
        String b64 = RecordingEncryption.keyToBase64(key);
        SecretKey recovered = RecordingEncryption.keyFromBase64(b64);
        assertThat(recovered.getEncoded()).isEqualTo(key.getEncoded());
    }

    @Test
    public void keyFromBytes_setsAlgorithmToAES() {
        SecretKey k = RecordingEncryption.keyFromBytes(key.getEncoded());
        assertThat(k.getAlgorithm()).isEqualTo("AES");
    }

    // ── encrypt / decrypt bytes ───────────────────────────────────────────────

    @Test
    public void encrypt_producesOutputLargerThanInput() {
        byte[] plain = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = encryption.encrypt(plain);
        // output = 4 (IV len) + 12 (IV) + len + 16 (GCM tag)
        assertThat(cipher.length).isGreaterThan(plain.length);
    }

    @Test
    public void encrypt_twoCallsProduceDifferentCiphertext() {
        byte[] plain = "same plain text".getBytes(StandardCharsets.UTF_8);
        byte[] c1 = encryption.encrypt(plain);
        byte[] c2 = encryption.encrypt(plain);
        // Different IVs → different ciphertexts
        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    public void encryptDecrypt_roundTrip() {
        byte[] plain = "{'session':'abc','events':[]}".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = encryption.encrypt(plain);
        byte[] decrypted = encryption.decrypt(cipher);
        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    public void decrypt_tamperedCiphertext_throwsException() {
        byte[] plain = "sensitive data".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = encryption.encrypt(plain);
        // Flip a byte in the ciphertext portion (after the IV)
        cipher[20] ^= 0xFF;
        assertThatThrownBy(() -> encryption.decrypt(cipher))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void decrypt_wrongKey_throwsException() {
        byte[] plain = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = encryption.encrypt(plain);

        SecretKey otherKey = RecordingEncryption.generateKey();
        RecordingEncryption otherEnc = new RecordingEncryption(otherKey);
        assertThatThrownBy(() -> otherEnc.decrypt(cipher))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── encryptString / decryptString ────────────────────────────────────────

    @Test
    public void encryptString_decryptString_roundTrip() {
        String json = "{\"sessionId\":\"test-123\",\"events\":[]}";
        String encoded = encryption.encryptString(json);
        String decoded = encryption.decryptString(encoded);
        assertThat(decoded).isEqualTo(json);
    }

    @Test
    public void encryptString_producesBase64Output() {
        String b64 = encryption.encryptString("test payload");
        // Should be valid Base64 (no exception when decoding)
        assertThatCode(() ->
                java.util.Base64.getDecoder().decode(b64)
        ).doesNotThrowAnyException();
    }

    // ── encryptFile / decryptFile ─────────────────────────────────────────────

    @Test
    public void encryptFile_createsEncFile() throws Exception {
        Path tmp = Files.createTempFile("recording-", ".json");
        Files.writeString(tmp, "{\"sessionId\":\"enc-test\",\"events\":[]}");
        try {
            Path enc = encryption.encryptFile(tmp);
            assertThat(enc).exists();
            assertThat(enc.getFileName().toString()).endsWith(RecordingEncryption.ENCRYPTED_EXT);
            // Encrypted bytes differ from original
            assertThat(Files.readAllBytes(enc)).isNotEqualTo(Files.readAllBytes(tmp));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void encryptFile_decryptFile_roundTrip() throws Exception {
        String originalContent = "{\"sessionId\":\"round-trip\",\"events\":[]}";
        Path tmp = Files.createTempFile("recording-", ".json");
        Files.writeString(tmp, originalContent);
        try {
            Path enc = encryption.encryptFile(tmp);
            Path dec = encryption.decryptFile(enc);
            assertThat(Files.readString(dec)).isEqualTo(originalContent);
            Files.deleteIfExists(enc);
            Files.deleteIfExists(dec);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void decryptFile_wrongExtension_throwsException() throws Exception {
        Path tmp = Files.createTempFile("not-encrypted-", ".json");
        try {
            assertThatThrownBy(() -> encryption.decryptFile(tmp))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(".enc");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
