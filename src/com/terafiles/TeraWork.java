package com.terafiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.BufferedOutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;
import java.io.BufferedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Multi-file jobs: download folders / selections, build zip or tar archives from TeraBox files. */
public class TeraWork {

	public interface Status {
		void message(String m);

		/** a short temporary remark (for example "Connection lost, retrying...") that does not replace the file label */
		void note(String m);

		void progress(long done, long total);

		boolean cancelled();
	}

	public static class FileRef {
		public String remote = "", rel = "", fsId = "";
		public long size, mtime;
		public boolean isDir;
	}

	static String safeSegment(String n) {
		if (n.equals("..") || n.equals(".") || n.length() == 0)
			return "_";
		return n.replace('\\', '_').replace("\0", "");
	}

	/** the entry and everything below it; rel is the path inside the archive / target folder */
	static void collect(TeraClient c, TeraEntry e, String rel, ArrayList<FileRef> out, int depth) throws Exception {
		FileRef r = new FileRef();
		r.remote = e.path;
		r.rel = rel;
		r.fsId = e.fsId;
		r.size = e.size;
		r.mtime = e.mtime;
		r.isDir = e.isDir;
		out.add(r);
		if (out.size() > 20000)
			throw new IOException("Too many files (limit 20000).");
		if (!e.isDir)
			return;
		if (depth > 30)
			throw new IOException("Folder nesting is too deep.");
		JSONObject j = c.listAll(e.path);
		if (j.optInt("errno", 0) != 0)
			throw new IOException("Could not list " + e.path + " (errno " + j.optInt("errno", -1) + ").");
		JSONArray a = j.optJSONArray("list");
		for (int i = 0; a != null && i < a.length(); i++) {
			TeraEntry ce = TeraEntry.from(a.getJSONObject(i));
			collect(c, ce, rel + "/" + safeSegment(ce.name), out, depth + 1);
		}
	}

	static File uniqueFile(File dir, String name) {
		File f = new File(dir, name);
		if (!f.exists())
			return f;
		String base = name, ext = "";
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			base = name.substring(0, dot);
			ext = name.substring(dot);
		}
		for (int i = 1; i < 10000; i++) {
			f = new File(dir, base + " (" + i + ")" + ext);
			if (!f.exists())
				return f;
		}
		return new File(dir, base + "_" + System.currentTimeMillis() + ext);
	}

	static boolean retryable(Exception e) {
		String m = e.getMessage() == null ? "" : e.getMessage();
		if (m.equals("Cancelled") || m.startsWith("TeraBox did not return") || m.startsWith("Refusing"))
			return false;
		return e instanceof IOException;
	}

	/**
	 * Download one file into dest. dest may already hold a partial download, which is continued. A dropped connection or an
	 * expired link is retried (with a fresh link) up to 6 times before giving up; the partial data is kept for a later resume.
	 */
	static void fetch(TeraClient c, FileRef r, File dest, final Status st, final int index, final int count)
			throws Exception {
		if (r.size <= 0) {
			new FileOutputStream(dest).close();
			return;
		}
		TeraClient.Progress pg = new TeraClient.Progress() {
			public void onProgress(long done, long total) {
				st.progress(done, total);
			}

			public boolean cancelled() {
				return st.cancelled();
			}
		};
		for (int tr = 1;; tr++) {
			if (st.cancelled())
				throw new IOException("Cancelled");
			try {
				String link = c.getDownloadLink(r.remote, r.fsId);
				c.downloadTo(link, dest, r.size, pg);
				return;
			} catch (Exception e) {
				if (tr >= 6 || !retryable(e))
					throw e;
				st.note("Connection problem, retrying (" + tr + "/5)...");
				TeraClient.sleepCancellable(Math.min(30000L, 2000L * tr), pg);
			}
		}
	}

	static String leaf(String rel) {
		return rel.substring(rel.lastIndexOf('/') + 1);
	}

	static String candidateName(String name, int i) {
		if (i == 0)
			return name;
		String base = name, ext = "";
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			base = name.substring(0, dot);
			ext = name.substring(dot);
		}
		return base + " (" + i + ")" + ext;
	}

	/** true when f (a file's ".part" next to it, or a folder holding any ".part" file) is an unfinished download */
	static boolean hasPart(File f, int depth) {
		if (f.isDirectory()) {
			File[] kids = f.listFiles();
			if (kids == null || depth > 30)
				return false;
			for (int i = 0; i < kids.length; i++) {
				if (kids[i].isDirectory() ? hasPart(kids[i], depth + 1) : kids[i].getName().endsWith(".part"))
					return true;
			}
			return false;
		}
		return new File(f.getPath() + ".part").exists();
	}

	/** the name an interrupted download of this entry was saved under, or null when there is none to continue */
	static File findPartial(File dir, String name) {
		for (int i = 0; i < 20; i++) {
			File f = new File(dir, candidateName(name, i));
			if (hasPart(f, 0))
				return f;
		}
		return null;
	}

	/** Download the selection (folders recursively) into targetDir. Returns the number of files saved. */
	public static int downloadTree(TeraClient c, List<TeraEntry> sel, File targetDir, Status st) throws Exception {
		ArrayList<FileRef> refs = new ArrayList<FileRef>();
		ArrayList<File> roots = new ArrayList<File>();
		st.message("Reading folders...");
		for (int i = 0; i < sel.size(); i++) {
			TeraEntry e = sel.get(i);
			File root = findPartial(targetDir, safeSegment(e.name)); // an interrupted earlier download: continue it
			if (root == null)
				root = uniqueFile(targetDir, safeSegment(e.name));
			roots.add(root);
			collect(c, e, root.getName(), refs, 0);
		}
		String base = targetDir.getCanonicalPath() + File.separator;
		int files = 0, total = 0;
		for (int i = 0; i < refs.size(); i++)
			if (!refs.get(i).isDir)
				total++;
		for (int i = 0; i < refs.size(); i++) {
			if (st.cancelled())
				throw new IOException("Cancelled");
			FileRef r = refs.get(i);
			File dest = new File(targetDir, r.rel);
			if (!dest.getCanonicalPath().startsWith(base))
				throw new IOException("Unsafe file name rejected.");
			if (r.isDir) {
				dest.mkdirs();
				continue;
			}
			files++;
			st.message("(" + files + "/" + total + ")  " + leaf(r.rel));
			if (dest.isFile() && dest.length() == r.size) { // finished before the interruption
				st.progress(r.size, r.size);
				continue;
			}
			dest.getParentFile().mkdirs();
			File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
			boolean keep = false;
			try {
				fetch(c, r, tmp, st, files, total);
				if (!tmp.renameTo(dest))
					throw new IOException("Could not save " + dest.getName());
			} catch (Exception e) {
				keep = !"Cancelled".equals(e.getMessage()); // keep the partial file so the download can be resumed
				throw e;
			} finally {
				if (!keep && tmp.exists())
					tmp.delete();
			}
		}
		return files;
	}

	/** Build a zip or tar of the selection in cacheDir (files are fetched one at a time). */
	public static File makeArchive(TeraClient c, List<TeraEntry> sel, boolean tar, File cacheDir, Status st)
			throws Exception {
		ArrayList<FileRef> refs = new ArrayList<FileRef>();
		st.message("Reading folders...");
		for (int i = 0; i < sel.size(); i++)
			collect(c, sel.get(i), safeSegment(sel.get(i).name), refs, 0);
		int total = 0;
		for (int i = 0; i < refs.size(); i++)
			if (!refs.get(i).isDir)
				total++;
		cacheDir.mkdirs();
		File out = new File(cacheDir, "archive_" + System.nanoTime() + (tar ? ".tar" : ".zip"));
		BufferedOutputStream bo = new BufferedOutputStream(new FileOutputStream(out), 65536);
		boolean ok = false;
		try {
			ZipOutputStream zos = tar ? null : new ZipOutputStream(bo);
			TarOut to = tar ? new TarOut(bo) : null;
			int n = 0;
			for (int i = 0; i < refs.size(); i++) {
				if (st.cancelled())
					throw new IOException("Cancelled");
				FileRef r = refs.get(i);
				if (r.isDir) {
					if (tar)
						to.dir(r.rel, r.mtime);
					else {
						ZipEntry ze = new ZipEntry(r.rel + "/");
						if (r.mtime > 0)
							ze.setTime(r.mtime * 1000);
						zos.putNextEntry(ze);
						zos.closeEntry();
					}
					continue;
				}
				n++;
				st.message("(" + n + "/" + total + ")  " + leaf(r.rel));
				File tmp = new File(cacheDir, "dl_" + System.nanoTime() + ".tmp");
				try {
					fetch(c, r, tmp, st, n, total);
					if (tar)
						to.file(r.rel, tmp, r.mtime);
					else {
						ZipEntry ze = new ZipEntry(r.rel);
						if (r.mtime > 0)
							ze.setTime(r.mtime * 1000);
						zos.putNextEntry(ze);
						InputStream in = new FileInputStream(tmp);
						try {
							byte[] buf = new byte[65536];
							int k;
							while ((k = in.read(buf)) > 0)
								zos.write(buf, 0, k);
						} finally {
							in.close();
						}
						zos.closeEntry();
					}
				} finally {
					tmp.delete();
				}
			}
			if (tar)
				to.finish();
			else
				zos.finish();
			bo.flush();
			ok = true;
		} finally {
			bo.close();
			if (!ok)
				out.delete();
		}
		if (out.length() == 0)
			throw new IOException("The archive is empty.");
		return out;
	}

	// ================================================================== extracting zip / tar archives

	public static class ExtractResult {
		public int files, dirs, skipped;
		public String destDir = "";
	}

	static final int MAX_ENTRIES = 20000;
	static final long MAX_BYTES = 16L * 1024 * 1024 * 1024;

	static String parentOf(String p) {
		if (p.equals("/") || p.length() == 0)
			return "/";
		int i = p.lastIndexOf('/');
		return i <= 0 ? "/" : p.substring(0, i);
	}

	static String join(String dir, String rel) {
		return (dir.equals("/") ? "" : dir) + "/" + rel;
	}

	/** an archive name without .zip / .tar / .tar.gz / .tgz */
	public static String baseName(String name) {
		String l = name.toLowerCase(Locale.US);
		String[] exts = new String[] { ".tar.gz", ".tgz", ".tar", ".zip" };
		for (int i = 0; i < exts.length; i++)
			if (l.endsWith(exts[i]) && name.length() > exts[i].length())
				return name.substring(0, name.length() - exts[i].length());
		return name;
	}

	public static boolean isZipName(String n) {
		return n.toLowerCase(Locale.US).endsWith(".zip");
	}

	public static boolean isTarName(String n) {
		String l = n.toLowerCase(Locale.US);
		return l.endsWith(".tar") || l.endsWith(".tar.gz") || l.endsWith(".tgz");
	}

	/** a safe relative path from an archive entry name, or null when it must be skipped (absolute, "..", empty) */
	static String cleanName(String n) {
		n = n.replace('\\', '/');
		StringBuilder sb = new StringBuilder();
		String[] parts = n.split("/");
		for (int i = 0; i < parts.length; i++) {
			String seg = parts[i].replaceAll("[\\x00-\\x1f]", "");
			if (seg.length() == 0 || seg.equals("."))
				continue;
			if (seg.equals(".."))
				return null;
			if (sb.length() > 0)
				sb.append('/');
			sb.append(seg);
		}
		if (sb.length() == 0 || sb.length() > 1000)
			return null;
		return sb.toString();
	}

	static TeraClient.Progress prog(final Status st) {
		return new TeraClient.Progress() {
			public void onProgress(long done, long total) {
				st.progress(done, total);
			}

			public boolean cancelled() {
				return st.cancelled();
			}
		};
	}

	/** a folder name that is not used in parent yet: name, name (1), name (2) ... */
	static String uniqueRemoteName(TeraClient c, String parent, String name) throws Exception {
		JSONObject j = c.listAll(parent);
		if (j.optInt("errno", 0) != 0)
			throw new IOException("Could not read " + parent + " (errno " + j.optInt("errno", -1) + ").");
		HashSet<String> used = new HashSet<String>();
		JSONArray a = j.optJSONArray("list");
		for (int i = 0; a != null && i < a.length(); i++)
			used.add(a.getJSONObject(i).optString("server_filename", "").toLowerCase(Locale.US));
		if (!used.contains(name.toLowerCase(Locale.US)))
			return name;
		for (int i = 1; i < 10000; i++)
			if (!used.contains((name + " (" + i + ")").toLowerCase(Locale.US)))
				return name + " (" + i + ")";
		return name + "_" + System.currentTimeMillis();
	}

	/** Download an archive of the account and unpack it into the same folder, or into a new folder named after it. */
	public static ExtractResult extract(TeraClient c, TeraEntry arc, boolean toFolder, File cacheDir, Status st)
			throws Exception {
		String parent = parentOf(arc.path);
		String dest = parent;
		if (toFolder) {
			String folder = uniqueRemoteName(c, parent, safeSegment(baseName(arc.name)));
			dest = join(parent, folder);
			JSONObject m = c.mkdir(dest);
			if (m.optInt("errno", -1) != 0)
				throw new IOException("Could not create the folder " + folder + " (errno " + m.optInt("errno", -1) + ").");
		}
		cacheDir.mkdirs();
		if (cacheDir.getUsableSpace() < arc.size + 64L * 1024 * 1024)
			throw new IOException("Not enough free space on the phone to unpack this archive.");
		File local = new File(cacheDir, "ex_" + System.nanoTime() + ".arc");
		try {
			st.message("Downloading " + arc.name);
			FileRef r = new FileRef();
			r.remote = arc.path;
			r.fsId = arc.fsId;
			r.size = arc.size;
			fetch(c, r, local, st, 1, 1);
			return extractLocal(c, local, !isZipName(arc.name), arc.name.toLowerCase(Locale.US).endsWith("gz"), dest, cacheDir, st);
		} finally {
			local.delete();
		}
	}

	static void ensureDirs(TeraClient c, String dest, String relDir, HashSet<String> made) {
		if (relDir.length() == 0)
			return;
		String[] parts = relDir.split("/");
		String path = dest;
		for (int i = 0; i < parts.length; i++) {
			path = join(path, parts[i]);
			if (made.add(path)) {
				try {
					c.mkdir(path); // "already exists" answers are harmless
				} catch (Exception e) {
				}
			}
		}
	}

	/** unpack a local zip / tar / tar.gz into the TeraBox folder dest */
	public static ExtractResult extractLocal(TeraClient c, File archive, boolean tar, boolean gz, String dest,
			File cacheDir, Status st) throws Exception {
		ExtractResult res = new ExtractResult();
		res.destDir = dest;
		HashSet<String> made = new HashSet<String>();
		long bytes = 0;
		int count = 0;
		if (!tar) {
			ZipFile zf = new ZipFile(archive);
			try {
				int total = zf.size();
				Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
				while (en.hasMoreElements()) {
					if (st.cancelled())
						throw new IOException("Cancelled");
					java.util.zip.ZipEntry ze = en.nextElement();
					if (++count > MAX_ENTRIES)
						throw new IOException("Too many files in the archive (limit " + MAX_ENTRIES + ").");
					String rel = cleanName(ze.getName());
					if (rel == null) {
						res.skipped++;
						continue;
					}
					if (ze.isDirectory()) {
						ensureDirs(c, dest, rel, made);
						res.dirs++;
						continue;
					}
					int slash = rel.lastIndexOf('/');
					String relDir = slash < 0 ? "" : rel.substring(0, slash);
					String leaf = rel.substring(slash + 1);
					ensureDirs(c, dest, relDir, made);
					st.message("(" + count + "/" + total + ")  " + leaf);
					File tmp = new File(cacheDir, "ex_" + System.nanoTime() + ".tmp");
					try {
						InputStream in = zf.getInputStream(ze);
						FileOutputStream fo = new FileOutputStream(tmp);
						try {
							byte[] buf = new byte[65536];
							int n;
							while ((n = in.read(buf)) > 0) {
								bytes += n;
								if (bytes > MAX_BYTES)
									throw new IOException("The archive is too large to unpack (limit 16 GB).");
								fo.write(buf, 0, n);
							}
						} finally {
							fo.close();
							in.close();
						}
						c.uploadFile(tmp, relDir.length() == 0 ? dest : join(dest, relDir), leaf, prog(st));
						res.files++;
					} finally {
						tmp.delete();
					}
				}
			} finally {
				zf.close();
			}
		} else {
			InputStream raw = new BufferedInputStream(new FileInputStream(archive), 65536);
			if (gz)
				raw = new GZIPInputStream(raw, 65536);
			TarIn ti = new TarIn(raw);
			try {
				TarIn.Entry e;
				while ((e = ti.next()) != null) {
					if (st.cancelled())
						throw new IOException("Cancelled");
					if (++count > MAX_ENTRIES)
						throw new IOException("Too many files in the archive (limit " + MAX_ENTRIES + ").");
					if (!e.isDir && !e.isFile) {
						res.skipped++; // links, devices
						continue;
					}
					String rel = cleanName(e.name);
					if (rel == null) {
						res.skipped++;
						continue;
					}
					if (e.isDir) {
						ensureDirs(c, dest, rel, made);
						res.dirs++;
						continue;
					}
					int slash = rel.lastIndexOf('/');
					String relDir = slash < 0 ? "" : rel.substring(0, slash);
					String leaf = rel.substring(slash + 1);
					ensureDirs(c, dest, relDir, made);
					st.message("(" + count + ")  " + leaf);
					if (bytes + e.size > MAX_BYTES)
						throw new IOException("The archive is too large to unpack (limit 16 GB).");
					File tmp = new File(cacheDir, "ex_" + System.nanoTime() + ".tmp");
					try {
						FileOutputStream fo = new FileOutputStream(tmp);
						try {
							bytes += ti.copyTo(fo);
						} finally {
							fo.close();
						}
						c.uploadFile(tmp, relDir.length() == 0 ? dest : join(dest, relDir), leaf, prog(st));
						res.files++;
					} finally {
						tmp.delete();
					}
				}
			} finally {
				raw.close();
			}
		}
		return res;
	}
}
