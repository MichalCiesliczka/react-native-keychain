package com.oblador.keychain.cipherStorage;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactContext;
import com.oblador.keychain.SecurityLevel;
import com.oblador.keychain.exceptions.CryptoFailedException;
import com.oblador.keychain.exceptions.KeyStoreAccessException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.NoSuchPaddingException;

/** Fingerprint biometry protected storage. */
@SuppressWarnings({"unused", "WeakerAccess"})
public class CipherStorageKeystoreRsaEcb extends CipherStorageBase {
  //region Constants
  /** Storage name. */
  public static final String CIPHER_STORAGE_NAME_RSAECB = "KeystoreRSAECB";
  /** Selected algorithm. */
  public static final String ALGORITHM_RSA = KeyProperties.KEY_ALGORITHM_RSA;
  /** Selected block mode. */
  public static final String BLOCK_MODE_ECB = KeyProperties.BLOCK_MODE_ECB;
  /** Selected padding transformation. */
  public static final String PADDING_PKCS1 = KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1;
  /** Composed transformation algorithms. */
  public static final String TRANSFORMATION_RSA_ECB_PKCS1 =
    ALGORITHM_RSA + "/" + BLOCK_MODE_ECB + "/" + PADDING_PKCS1;
  /** Selected encryption key size. */
  public static final int ENCRYPTION_KEY_SIZE = 3072;
  //endregion

  //region Members
  /** Reference on the application context. */
  private final ReactContext reactContext;
  /** Reference on keyguard manager service. */
  private final KeyguardManager keyguardManager;
  //endregion

  /** Main constructor. */
  public CipherStorageKeystoreRsaEcb(@NonNull final ReactContext reactContext) {
    this.reactContext = reactContext;

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      keyguardManager = null;
    } else {
      keyguardManager = (KeyguardManager) reactContext.getSystemService(Context.KEYGUARD_SERVICE);
    }
  }

  //region Overrides
  @Override
  @NonNull
  public EncryptionResult encrypt(@NonNull final String alias,
                                  @NonNull final String username,
                                  @NonNull final String password,
                                  @NonNull final SecurityLevel level)
    throws CryptoFailedException {

    throwIfInsufficientLevel(level);

    final String safeService = getDefaultAliasIfEmpty(alias);

    try {
      return innerEncryptedCredentials(username, password, safeService);

      // KeyStoreException | KeyStoreAccessException  | NoSuchAlgorithmException | InvalidKeySpecException |
      //    IOException | NoSuchPaddingException | InvalidKeyException e
    } catch (NoSuchAlgorithmException | InvalidKeySpecException | NoSuchPaddingException | InvalidKeyException e) {
      throw new CryptoFailedException("Could not encrypt data for service " + alias, e);
    } catch (KeyStoreException | KeyStoreAccessException e) {
      throw new CryptoFailedException("Could not access Keystore for service " + alias, e);
    } catch (IOException io) {
      throw new CryptoFailedException("I/O error: " + io.getMessage(), io);
    } catch (final Throwable ex) {
      throw new CryptoFailedException("Unknown error: " + ex.getMessage(), ex);
    }
  }

  @NonNull
  @Override
  public DecryptionResult decrypt(@NonNull String alias,
                                  @NonNull byte[] username,
                                  @NonNull byte[] password,
                                  @NonNull final SecurityLevel level)
    throws CryptoFailedException {

    throwIfInsufficientLevel(level);

    throw new AssertionError("Not implemented");
  }

  @Override
  @SuppressLint("NewApi")
  public void decrypt(@NonNull DecryptionResultHandler handler,
                      @NonNull String alias,
                      @NonNull byte[] username,
                      @NonNull byte[] password,
                      @NonNull final SecurityLevel level)
    throws CryptoFailedException {

    throwIfInsufficientLevel(level);

    final String safeAlias = getDefaultAliasIfEmpty(alias);
    final AtomicInteger retries = new AtomicInteger(1);
    boolean shouldAskPermissions = false;

    Key key = null;

    try {
      // key is always NOT NULL otherwise GeneralSecurityException raised
      key = extractGeneratedKey(safeAlias, level, retries);

      final DecryptionResult results = new DecryptionResult(
        decryptBytes(key, username),
        decryptBytes(key, password)
      );
      handler.onDecrypt(results, null);
    } catch (UserNotAuthenticatedException ex) {
      Log.d(LOG_TAG, "Unlock of keystore is needed. Error: " + ex.getMessage(), ex);

      // expected that KEY instance is extracted and we caught exception on decryptBytes operation
      @SuppressWarnings("ConstantConditions") final DecryptionContext context = new DecryptionContext(safeAlias, key, password, username);
      handler.askAccessPermissions(context);
    } catch (Throwable fail) {
      // any other exception treated as a failure
      handler.onDecrypt(null, fail);
    }
  }

  //endregion

  //region Configuration

  /** RSAECB. */
  @Override
  public String getCipherStorageName() {
    return CIPHER_STORAGE_NAME_RSAECB;
  }

  /** API23 is a requirement. */
  @Override
  public int getMinSupportedApiLevel() {
    return Build.VERSION_CODES.M;
  }

  /** Biometry is supported. */
  @Override
  public boolean isBiometrySupported() {
    return true;
  }

  /** RSA. */
  @NonNull
  @Override
  protected String getEncryptionAlgorithm() {
    return ALGORITHM_RSA;
  }

  /** RSA/ECB/PKCS1Padding */
  @NonNull
  @Override
  protected String getEncryptionTransformation() {
    return TRANSFORMATION_RSA_ECB_PKCS1;
  }
  //endregion

  //region Implementation

  /** Clean code without try/catch's that encrypt username and password with a key specified by alias. */
  @NonNull
  private EncryptionResult innerEncryptedCredentials(@NonNull final String username,
                                                     @NonNull final String password,
                                                     @NonNull final String alias)
    throws GeneralSecurityException, IOException {

    final KeyStore store = getKeyStoreAndLoad();
    final KeyFactory kf = KeyFactory.getInstance(ALGORITHM_RSA);
    final PublicKey publicKey = store.getCertificate(alias).getPublicKey();
    final X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKey.getEncoded());
    final PublicKey key = kf.generatePublic(keySpec);

    return new EncryptionResult(
      encryptString(key, username),
      encryptString(key, password),
      this);
  }

  /** Get builder for encryption and decryption operations with required user Authentication. */
  @NonNull
  @Override
  @SuppressLint("NewApi")
  protected KeyGenParameterSpec.Builder getKeyGenSpecBuilder(@NonNull final String alias)
    throws GeneralSecurityException {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      throw new KeyStoreAccessException("Unsupported API" + Build.VERSION.SDK_INT + " version detected.");
    }

    final int purposes = KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_ENCRYPT;

    return new KeyGenParameterSpec.Builder(alias, purposes)
      .setBlockModes(BLOCK_MODE_ECB)
      .setEncryptionPaddings(PADDING_PKCS1)
      .setRandomizedEncryptionRequired(true)
      .setUserAuthenticationRequired(true)
      .setUserAuthenticationValidityDurationSeconds(1)
      .setKeySize(ENCRYPTION_KEY_SIZE);
  }

  /** Get information about provided key. */
  @NonNull
  @Override
  protected KeyInfo getKeyInfo(@NonNull final Key key) throws GeneralSecurityException {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      throw new KeyStoreAccessException("Unsupported API" + Build.VERSION.SDK_INT + " version detected.");
    }

    final KeyFactory factory = KeyFactory.getInstance(key.getAlgorithm(), KEYSTORE_TYPE);

    return factory.getKeySpec(key, KeyInfo.class);
  }

  /** Try to generate key from provided specification. */
  @NonNull
  @Override
  protected Key generateKey(@NonNull final KeyGenParameterSpec spec) throws GeneralSecurityException {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      throw new KeyStoreAccessException("Unsupported API" + Build.VERSION.SDK_INT + " version detected.");
    }

    final KeyPairGenerator generator = KeyPairGenerator.getInstance(getEncryptionAlgorithm(), KEYSTORE_TYPE);
    generator.initialize(spec);

    return generator.generateKeyPair().getPrivate();
  }

  //endregion
}
