package io.kazuki.v0.internal.helper;

import io.kazuki.v0.store.KazukiException;
import io.kazuki.v0.store.Key;

import java.nio.ByteBuffer;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.KeySpec;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class KeyObfuscator {
  private static final String keyString = System.getProperty("key.encrypt.password", "changeme");
  private static final byte[] saltBytes = System.getProperty("key.encrypt.salt", "asalt")
      .getBytes();
  private static final byte[] ivBytes;

  static {
    try {
      ivBytes =
          Hex.decodeHex(System.getProperty("key.encrypt.iv", "0123456789ABCDEF").toCharArray());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static final AlgorithmParameterSpec paramSpec = new IvParameterSpec(ivBytes);

  private static ConcurrentHashMap<String, SecretKey> keyCache =
      new ConcurrentHashMap<String, SecretKey>();

  public static String encrypt(String type, Long id) throws KazukiException {
    StringBuilder encryptedIdentifier = new StringBuilder();
    encryptedIdentifier.append("@");
    encryptedIdentifier.append(type);
    encryptedIdentifier.append(":");

    try {
      byte[] plain = ByteBuffer.allocate(8).putLong(id).array();
      byte[] encrypted = getCipher(type, Cipher.ENCRYPT_MODE).doFinal(plain);

      encryptedIdentifier.append(Hex.encodeHex(encrypted));

      return encryptedIdentifier.toString();
    } catch (Exception e) {
      if (e instanceof KazukiException) {
        throw (KazukiException) e;
      }

      throw new KazukiException("error while encrypting id!", e);
    }
  }

  public static Key decrypt(String encryptedText) throws KazukiException {
    if (encryptedText == null || encryptedText.length() == 0 || !encryptedText.contains(":")) {
      throw new KazukiException("Invalid key");
    }

    if (!encryptedText.startsWith("@")) {
      return Key.valueOf(encryptedText);
    }

    String[] parts = encryptedText.substring(1).split(":");

    if (parts.length != 2 || parts[1].length() != 16) {
      throw new KazukiException("Invalid key");
    }

    String type = parts[0];

    try {
      byte[] encrypted = Hex.decodeHex(parts[1].toCharArray());
      byte[] decrypted = getCipher(type, Cipher.DECRYPT_MODE).doFinal(encrypted);

      Long id = ByteBuffer.allocate(8).put(decrypted).getLong(0);

      return new Key(type, id);
    } catch (Exception e) {
      throw new KazukiException(e);
    }
  }

  private static Cipher getCipher(String type, int mode) throws KazukiException {
    try {
      Cipher cipher = Cipher.getInstance("DESede/CBC/NoPadding");
      cipher.init(mode, getKey(type), paramSpec);

      return cipher;
    } catch (Exception e) {
      throw new KazukiException("error while creating cipher instance!", e);
    }
  }

  private static SecretKey getKey(String type) throws Exception {
    if (!keyCache.containsKey(type)) {
      SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
      String password = keyString + ":" + type;
      KeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, 1024, 192);
      SecretKey tmp = factory.generateSecret(spec);
      SecretKey key = new SecretKeySpec(tmp.getEncoded(), "DESede");

      keyCache.put(type, key);
    }

    return keyCache.get(type);
  }
}
