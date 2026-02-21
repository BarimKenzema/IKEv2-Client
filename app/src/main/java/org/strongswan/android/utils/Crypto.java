package org.strongswan.android.utils;

import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Arrays;

public class Crypto {
    private static final String MASTER_KEY = "IKEv2SecureProfileKey2025";

    public static String encrypt(String plainText) throws Exception {
        byte[] key = deriveKey(MASTER_KEY);
        byte[] iv = new byte[16];
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return Base64.encodeToString(cipher.doFinal(plainText.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    public static String decrypt(String encryptedText) throws Exception {
        byte[] key = deriveKey(MASTER_KEY);
        byte[] iv = new byte[16];
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(Base64.decode(encryptedText.trim(), Base64.DEFAULT)), "UTF-8");
    }

    private static byte[] deriveKey(String password) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        return Arrays.copyOf(sha.digest(password.getBytes("UTF-8")), 16);
    }
}
