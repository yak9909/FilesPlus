package app.filesplus.extension.smb;

import android.util.Base64;

import app.filesplus.extension.DiagLog;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * AES-256-GCM encryption with a non-exportable Android Keystore key. The blob is Base64 of IV (12 bytes)
 * followed by ciphertext and tag. Never throws and never logs plaintext or blobs.
 */
public final class SmbSecretBox {
    private static final String AREA = "smb";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "filesplus_smb";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static SecretKey key;

    private SmbSecretBox() {}

    /** Returns the blob, or null when encryption is not possible (the caller must then not store the secret). */
    public static String encrypt(String plain) {
        if (plain == null) return null;
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        try {
            // The Keystore generates the IV itself; randomized encryption is required and a caller IV is refused.
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) return null;
            byte[] sealed = cipher.doFinal(data);
            byte[] blob = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(sealed, 0, blob, iv.length, sealed.length);
            return Base64.encodeToString(blob, Base64.NO_WRAP);
        } catch (Throwable t) {
            DiagLog.i(AREA, "Secret encryption failed: " + t.getClass().getSimpleName());
            return null;
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    /** Returns the plaintext, or null on any failure (bad blob, missing or changed key). */
    public static String decrypt(String blob) {
        if (blob == null || blob.isEmpty()) return null;
        byte[] data = null;
        try {
            byte[] raw = Base64.decode(blob, Base64.NO_WRAP);
            if (raw.length <= IV_BYTES + TAG_BITS / 8) return null;
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(raw, 0, IV_BYTES)));
            data = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            DiagLog.i(AREA, "Secret decryption failed: " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (data != null) Arrays.fill(data, (byte) 0);
        }
    }

    /** Existing key, or a new one created on first use. Synchronized so two threads cannot both create it. */
    private static synchronized SecretKey key() throws Exception {
        if (key != null) return key;
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) {
            key = (SecretKey) existing;
            return key;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        key = generator.generateKey();
        return key;
    }
}
