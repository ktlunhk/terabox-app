package com.terafiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Minimal tar reader: ustar, GNU long names (L) and PAX path/size headers (x). Links and devices are skipped. */
public class TarIn {

	public static class Entry {
		public String name = "";
		public long size;
		public long mtime;
		public boolean isDir;
		/** a regular file, or something to ignore (symlink, device, ...) */
		public boolean isFile;
	}

	InputStream in;
	long remaining; // unread data bytes of the current entry
	long padding; // padding bytes after the current entry's data
	boolean eof;
	String longName; // from a GNU 'L' header, for the next entry
	String paxPath;
	long paxSize = -1;

	public TarIn(InputStream in) {
		this.in = in;
	}

	boolean readFully(byte[] b, int len) throws IOException {
		int off = 0;
		while (off < len) {
			int n = in.read(b, off, len - off);
			if (n < 0) {
				if (off == 0)
					return false;
				throw new IOException("The tar file is cut short.");
			}
			off += n;
		}
		return true;
	}

	void skipFully(long n) throws IOException {
		byte[] buf = new byte[8192];
		while (n > 0) {
			int r = in.read(buf, 0, (int) Math.min(buf.length, n));
			if (r < 0)
				throw new IOException("The tar file is cut short.");
			n -= r;
		}
	}

	static String cstr(byte[] h, int off, int len) {
		int end = off;
		while (end < off + len && h[end] != 0)
			end++;
		try {
			return new String(h, off, end - off, "UTF-8");
		} catch (Exception e) {
			return "";
		}
	}

	static long num(byte[] h, int off, int len) throws IOException {
		if ((h[off] & 0x80) != 0) { // base-256 for huge values
			long v = h[off] & 0x7f;
			for (int i = 1; i < len; i++)
				v = (v << 8) | (h[off + i] & 0xff);
			return v;
		}
		long v = 0;
		boolean any = false;
		for (int i = off; i < off + len; i++) {
			int c = h[i];
			if (c == 0 || c == ' ') {
				if (any)
					break;
				continue;
			}
			if (c < '0' || c > '7')
				throw new IOException("Not a valid tar file.");
			v = v * 8 + (c - '0');
			any = true;
		}
		return v;
	}

	/** skip what is left of the current entry */
	void finishEntry() throws IOException {
		skipFully(remaining + padding);
		remaining = 0;
		padding = 0;
	}

	byte[] readData(long len) throws IOException {
		if (len > 1048576)
			throw new IOException("A tar header is too large.");
		byte[] d = new byte[(int) len];
		int off = 0;
		while (off < d.length) {
			int n = in.read(d, off, d.length - off);
			if (n < 0)
				throw new IOException("The tar file is cut short.");
			off += n;
		}
		remaining -= len;
		return d;
	}

	/** "len key=value\n" records */
	void parsePax(byte[] d) throws IOException {
		int p = 0;
		while (p < d.length) {
			int sp = p;
			while (sp < d.length && d[sp] != ' ')
				sp++;
			if (sp >= d.length)
				break;
			int len;
			try {
				len = Integer.parseInt(new String(d, p, sp - p, "UTF-8"));
			} catch (Exception e) {
				break;
			}
			if (len <= 0 || p + len > d.length)
				break;
			String rec = new String(d, sp + 1, p + len - sp - 2, "UTF-8"); // without trailing \n
			int eq = rec.indexOf('=');
			if (eq > 0) {
				String k = rec.substring(0, eq), v = rec.substring(eq + 1);
				if (k.equals("path"))
					paxPath = v;
				else if (k.equals("size")) {
					try {
						paxSize = Long.parseLong(v);
					} catch (NumberFormatException e) {
					}
				}
			}
			p += len;
		}
	}

	/** the next entry, or null at the end of the archive */
	public Entry next() throws IOException {
		if (eof)
			return null;
		finishEntry();
		byte[] h = new byte[512];
		while (true) {
			if (!readFully(h, 512)) {
				eof = true;
				return null;
			}
			boolean zero = true;
			for (int i = 0; i < 512 && zero; i++)
				if (h[i] != 0)
					zero = false;
			if (zero) {
				eof = true;
				return null;
			}
			long sum = 0;
			for (int i = 0; i < 512; i++)
				sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xff);
			long stored = num(h, 148, 8);
			if (sum != stored)
				throw new IOException("Not a valid tar file (checksum).");
			char type = (char) (h[156] & 0xff);
			long size = num(h, 124, 12);
			if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
				remaining = size;
				padding = (512 - size % 512) % 512;
				if (type == 'L') {
					byte[] d = readData(size);
					int end = d.length;
					while (end > 0 && d[end - 1] == 0)
						end--;
					longName = new String(d, 0, end, "UTF-8");
					finishEntry();
				} else if (type == 'x') {
					parsePax(readData(size));
					finishEntry();
				} else {
					finishEntry(); // 'g' global header and 'K' long link target: ignored
				}
				continue;
			}
			Entry e = new Entry();
			String name = cstr(h, 0, 100);
			String magic = cstr(h, 257, 5);
			if (magic.equals("ustar")) {
				String prefix = cstr(h, 345, 155);
				if (prefix.length() > 0)
					name = prefix + "/" + name;
			}
			if (longName != null)
				name = longName;
			if (paxPath != null)
				name = paxPath;
			if (paxSize >= 0)
				size = paxSize;
			longName = null;
			paxPath = null;
			paxSize = -1;
			e.name = name;
			e.size = size;
			e.mtime = num(h, 136, 12);
			e.isDir = type == '5' || (name.endsWith("/") && (type == '0' || type == 0));
			e.isFile = (type == '0' || type == 0 || type == '7') && !e.isDir;
			if (e.isFile || e.isDir) {
				remaining = size;
				padding = (512 - size % 512) % 512;
			} else {
				// links, devices, fifos: no data we need to read, but skip the declared size if any
				remaining = type == '1' || type == '2' ? 0 : size;
				padding = (512 - remaining % 512) % 512;
			}
			return e;
		}
	}

	/** copy the data of the current regular file; returns the number of bytes copied */
	public long copyTo(OutputStream out) throws IOException {
		byte[] buf = new byte[65536];
		long total = 0;
		while (remaining > 0) {
			int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
			if (n < 0)
				throw new IOException("The tar file is cut short.");
			out.write(buf, 0, n);
			remaining -= n;
			total += n;
		}
		return total;
	}
}
