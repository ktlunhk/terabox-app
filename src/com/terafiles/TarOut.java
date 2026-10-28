package com.terafiles;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Minimal tar (ustar + GNU long names) writer, enough for regular files and folders. */
public class TarOut {
	OutputStream out;

	public TarOut(OutputStream o) {
		out = o;
	}

	static void octal(byte[] h, int off, int len, long v) {
		String s = Long.toOctalString(v);
		while (s.length() < len - 1)
			s = "0" + s;
		byte[] b = s.getBytes();
		System.arraycopy(b, 0, h, off, Math.min(b.length, len - 1));
		h[off + len - 1] = 0;
	}

	static byte[] header(byte[] name, long size, long mtime, char type) {
		byte[] h = new byte[512];
		System.arraycopy(name, 0, h, 0, Math.min(100, name.length));
		octal(h, 100, 8, type == '5' ? 493 : 420); // 0755 / 0644
		octal(h, 108, 8, 0);
		octal(h, 116, 8, 0);
		octal(h, 124, 12, size);
		octal(h, 136, 12, Math.max(0, mtime));
		for (int i = 148; i < 156; i++)
			h[i] = ' ';
		h[156] = (byte) type;
		byte[] magic = "ustar".getBytes();
		System.arraycopy(magic, 0, h, 257, magic.length);
		h[262] = 0;
		h[263] = '0';
		h[264] = '0';
		long sum = 0;
		for (int i = 0; i < 512; i++)
			sum += h[i] & 0xff;
		String cs = Long.toOctalString(sum);
		while (cs.length() < 6)
			cs = "0" + cs;
		System.arraycopy(cs.getBytes(), 0, h, 148, 6);
		h[154] = 0;
		h[155] = ' ';
		return h;
	}

	void pad(long len) throws IOException {
		int rest = (int) (len % 512);
		if (rest != 0)
			out.write(new byte[512 - rest]);
	}

	void writeName(String name, long size, long mtime, char type) throws IOException {
		byte[] nb = name.getBytes("UTF-8");
		if (nb.length > 100) {
			byte[] data = new byte[nb.length + 1];
			System.arraycopy(nb, 0, data, 0, nb.length);
			out.write(header("././@LongLink".getBytes(), data.length, 0, 'L'));
			out.write(data);
			pad(data.length);
		}
		out.write(header(nb, size, mtime, type));
	}

	/** a folder entry; name without trailing slash */
	public void dir(String name, long mtime) throws IOException {
		writeName(name + "/", 0, mtime, '5');
	}

	/** a regular file whose bytes are read from f (its current length is used) */
	public void file(String name, File f, long mtime) throws IOException {
		long size = f.length();
		writeName(name, size, mtime, '0');
		InputStream in = new FileInputStream(f);
		try {
			byte[] buf = new byte[65536];
			long left = size;
			while (left > 0) {
				int n = in.read(buf, 0, (int) Math.min(buf.length, left));
				if (n <= 0)
					throw new IOException("File changed while archiving.");
				out.write(buf, 0, n);
				left -= n;
			}
		} finally {
			in.close();
		}
		pad(size);
	}

	public void finish() throws IOException {
		out.write(new byte[1024]);
		out.flush();
	}
}
