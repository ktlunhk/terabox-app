package com.terafiles;

import android.util.Base64;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.math.BigInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Crypto used by the TeraBox web login and download signature (port of TeraBoxAuth.php / terabox-api-ruby). */
public class TeraCrypto {

	/** mode 1: standard base64 -> url-safe ("+/" to "-_"); mode 2: the reverse */
	public static String changeBase64Type(String s, int mode) {
		if (mode == 1)
			return s.replace('+', '-').replace('/', '_');
		return s.replace('-', '+').replace('_', '/');
	}

	static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < b.length; i++) {
			int v = b[i] & 0xff;
			if (v < 16)
				sb.append('0');
			sb.append(Integer.toHexString(v));
		}
		return sb.toString();
	}

	public static String md5Hex(String s) throws Exception {
		return hex(MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")));
	}

	public static String sha1Hex(String s) throws Exception {
		return hex(MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8")));
	}

	static byte[] fit16(byte[] k) {
		byte[] out = new byte[16];
		System.arraycopy(k, 0, out, 0, Math.min(16, k.length));
		return out;
	}

	/** getpubkey answer (pp1, pp2) -> PEM public key */
	public static String decryptAes(String pp1, String pp2) throws Exception {
		pp1 = changeBase64Type(pp1, 2);
		pp2 = changeBase64Type(pp2, 2);
		if (pp1.length() <= 16)
			throw new Exception("AES: invalid payload");
		byte[] iv = pp1.substring(0, 16).getBytes("ISO-8859-1");
		byte[] ct = Base64.decode(pp1.substring(16), Base64.DEFAULT);
		byte[] key = fit16(pp2.getBytes("ISO-8859-1"));
		Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
		c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
		return new String(c.doFinal(ct), "UTF-8");
	}

	// ---- minimal DER reader for PKCS#1 "RSA PUBLIC KEY" blocks
	static int derLen(byte[] d, int[] p) {
		int b = d[p[0]++] & 0xff;
		if (b < 0x80)
			return b;
		int n = b & 0x7f, v = 0;
		for (int i = 0; i < n; i++)
			v = (v << 8) | (d[p[0]++] & 0xff);
		return v;
	}

	static BigInteger derInt(byte[] d, int[] p) throws Exception {
		if ((d[p[0]++] & 0xff) != 0x02)
			throw new Exception("RSA: bad key");
		int len = derLen(d, p);
		byte[] v = new byte[len];
		System.arraycopy(d, p[0], v, 0, len);
		p[0] += len;
		return new BigInteger(1, v);
	}

	static PublicKey parsePublicKey(String pem) throws Exception {
		boolean pkcs1 = pem.indexOf("BEGIN RSA PUBLIC KEY") >= 0;
		String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
		byte[] der = Base64.decode(body, Base64.DEFAULT);
		KeyFactory kf = KeyFactory.getInstance("RSA");
		if (!pkcs1)
			return kf.generatePublic(new X509EncodedKeySpec(der));
		int[] p = new int[] { 0 };
		if ((der[p[0]++] & 0xff) != 0x30)
			throw new Exception("RSA: bad key");
		derLen(der, p);
		BigInteger n = derInt(der, p);
		BigInteger e = derInt(der, p);
		return kf.generatePublic(new RSAPublicKeySpec(n, e));
	}

	/** mode 2 = TeraBox password form: encrypt md5hex + its length (32) */
	public static String encryptRsa(String message, String pem, int mode) throws Exception {
		if (mode == 2) {
			String md5 = md5Hex(message);
			message = md5 + md5.length();
		}
		PublicKey key = parsePublicKey(pem);
		Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		c.init(Cipher.ENCRYPT_MODE, key);
		return Base64.encodeToString(c.doFinal(message.getBytes("UTF-8")), Base64.NO_WRAP);
	}

	public static String prandGen(String seval, String encpwd, String email, String random, String client,
			String browserid) throws Exception {
		return sha1Hex(client + "-" + seval + "-" + encpwd + "-" + email + "-" + browserid + "-" + random);
	}

	/** RC4-like download signature (home/info sign3 = key, sign1 = data) */
	public static String signDownload(String s1, String s2) throws Exception {
		byte[] key = s1.getBytes("UTF-8");
		byte[] data = s2.getBytes("UTF-8");
		if (key.length == 0)
			throw new Exception("Empty signing key");
		int[] p = new int[256];
		int[] a = new int[256];
		for (int i = 0; i < 256; i++) {
			p[i] = i;
			a[i] = key[i % key.length] & 0xff;
		}
		int j = 0;
		for (int i = 0; i < 256; i++) {
			j = (j + p[i] + a[i]) % 256;
			int t = p[i];
			p[i] = p[j];
			p[j] = t;
		}
		byte[] out = new byte[data.length];
		int i = 0;
		j = 0;
		for (int q = 0; q < data.length; q++) {
			i = (i + 1) % 256;
			j = (j + p[i]) % 256;
			int t = p[i];
			p[i] = p[j];
			p[j] = t;
			int k = p[(p[i] + p[j]) % 256];
			out[q] = (byte) ((data[q] & 0xff) ^ k);
		}
		return Base64.encodeToString(out, Base64.NO_WRAP);
	}
}
