package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import tw.nekomimi.nekogram.NekoXConfig;

/**
 * Roidgram first-launch API credential gate.
 *
 * The user's own Telegram api_id/api_hash (created at my.telegram.org) are entered
 * once in ApiSetupActivity before the normal phone-number/code login. The api_hash
 * is stored AES/GCM-encrypted with an AndroidKeyStore-backed key (keystore keys are
 * non-exportable by design). System backup goes through a BackupAgent that only
 * registers saved_tokens helpers; OEM device-transfer tools may copy app data
 * outside that channel, so no cross-device confidentiality claim is made.
 * The hash is never logged.
 */
public class ApiCredentials {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "roidgram_api_hash_v1";
    private static final String PREFS = "nekox_config";
    private static final String KEY_BLOB = "custom_app_hash_enc";
    private static final String KEY_LEGACY_PLAINTEXT = "custom_app_hash";

    private ApiCredentials() {
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        SecretKey existing = (SecretKey) ks.getKey(ALIAS, null);
        if (existing != null) {
            return existing;
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    /** Encrypts and stores the hash. Any previous blob is replaced. */
    public static void storeHash(Context context, String hash) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // AndroidKeyStore keys default to setRandomizedEncryptionRequired(true), which
        // rejects a caller-provided IV for ENCRYPT_MODE ("Caller-provided IV not
        // permitted"). Let the keystore generate the IV and read it back; decryption
        // still supplies the stored IV, which stays permitted.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(hash.getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(encrypted, 0, blob, iv.length, encrypted.length);
        prefs(context).edit()
                .putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
                .remove(KEY_LEGACY_PLAINTEXT)
                .apply();
    }

    /**
     * Decrypts the stored hash. Returns "" when none is stored or the blob cannot be
     * decrypted (e.g. keystore key invalidated, or data restored onto another device);
     * an undecryptable blob is wiped so the setup screen comes back instead of a
     * silently broken client. Never throws.
     */
    public static String loadHash(Context context) {
        try {
            SharedPreferences prefs = prefs(context);
            String legacy = prefs.getString(KEY_LEGACY_PLAINTEXT, null);
            if (legacy != null && !legacy.isEmpty()) {
                // Migrate plaintext written by older builds, then keep only the blob.
                try {
                    storeHash(context, legacy);
                } catch (Throwable t) {
                    prefs.edit().remove(KEY_LEGACY_PLAINTEXT).apply();
                    return "";
                }
            }
            String encoded = prefs.getString(KEY_BLOB, null);
            if (encoded == null || encoded.isEmpty()) {
                return "";
            }
            byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            System.arraycopy(blob, 0, iv, 0, iv.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(blob, iv.length, blob.length - iv.length);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            try {
                prefs(context).edit().remove(KEY_BLOB).apply();
            } catch (Throwable ignore) {
            }
            return "";
        }
    }

    /** The public sample credentials Telegram ships in its source tree. Never used. */
    private static final int SAMPLE_APP_ID = 6;
    private static final String SAMPLE_APP_HASH = "eb06d4abfb49dc3eeb1aeb98ae0f581e";

    public static boolean isCompiledSample(int id, String hash) {
        return id == SAMPLE_APP_ID && SAMPLE_APP_HASH.equals(hash);
    }

    /**
     * True while the client has no usable Telegram API credentials of its own: the
     * first-launch setup screen must run before any account or network service
     * starts. There is deliberately NO existing-account exemption: an account whose
     * stored credential blob was lost (device transfer, key invalidation) must be
     * re-collected through setup, never silently run on the compiled-in sample keys.
     * Account state is irrelevant here, so nothing depends on pre-init config loads.
     */
    public static boolean needsSetup(Context context) {
        if (NekoXConfig.customApi == NekoXConfig.API_TYPE_CUSTOM) {
            return NekoXConfig.customAppId <= 0 || loadHash(context).isEmpty();
        }
        // API_TYPE_DEFAULT: only acceptable when the build itself carries real,
        // user-supplied credentials (LOCAL_PROPERTIES at build time); the public
        // sample keys compiled into the source tree never count as configured.
        // Read BuildConfig directly - currentAppId() fail-closes the sample to 0.
        return isCompiledSample(BuildConfig.APP_ID, BuildConfig.APP_HASH);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
