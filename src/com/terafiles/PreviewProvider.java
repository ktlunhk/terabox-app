package com.terafiles;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;

/** Read-only access to cached previews for installed image/video/PDF viewers. */
public class PreviewProvider extends ContentProvider {
	public boolean onCreate() {
		return true;
	}

	public String getType(Uri uri) {
		String n = uri.getLastPathSegment();
		if (n == null)
			return "application/octet-stream";
		n = n.toLowerCase(java.util.Locale.US);
		String ext = n.substring(n.lastIndexOf('.') + 1);
		// explicit table first (the platform table lacks or varies for some of these), MimeTypeMap as the fallback
		String t = EXT_TYPES.get(ext);
		if (t == null)
			t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
		return t != null ? t : "application/octet-stream";
	}

	static final HashMap<String, String> EXT_TYPES = new HashMap<String, String>();
	static {
		String[][] t = { { "pdf", "application/pdf" }, { "png", "image/png" }, { "gif", "image/gif" }, { "webp", "image/webp" },
				{ "bmp", "image/bmp" }, { "heic", "image/heic" }, { "jpg", "image/jpeg" }, { "jpeg", "image/jpeg" },
				{ "svg", "image/svg+xml" }, { "mp3", "audio/mpeg" }, { "m4a", "audio/mp4" }, { "ogg", "audio/ogg" },
				{ "wav", "audio/x-wav" }, { "flac", "audio/flac" }, { "mp4", "video/mp4" }, { "mkv", "video/x-matroska" },
				{ "webm", "video/webm" }, { "mov", "video/quicktime" }, { "avi", "video/x-msvideo" }, { "3gp", "video/3gpp" } };
		for (String[] e : t)
			EXT_TYPES.put(e[0], e[1]);
	}

	/** the cached preview file for this uri, or null if the name is unsafe (the same check serves openFile and query) */
	private File resolve(Uri uri) {
		String name = uri.getLastPathSegment();
		if (name == null || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0)
			return null;
		File dir = new File(getContext().getCacheDir(), "previews");
		File file = new File(dir, name);
		try {
			if (!file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator))
				return null;
		} catch (java.io.IOException ex) {
			return null;
		}
		return file;
	}

	public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		if (!"r".equals(mode))
			throw new FileNotFoundException("Read only");
		File file = resolve(uri);
		if (file == null)
			throw new FileNotFoundException();
		return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
	}

	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		String[] cols = projection == null ? new String[] { "_display_name", "_size" } : projection;
		android.database.MatrixCursor c = new android.database.MatrixCursor(cols);
		File f = resolve(uri);
		if (f == null || !f.isFile())
			return c; // unsafe name or file gone: an empty cursor, not a row with size 0
		Object[] values = new Object[cols.length];
		for (int i = 0; i < cols.length; i++) {
			if ("_display_name".equals(cols[i]))
				values[i] = f.getName();
			else if ("_size".equals(cols[i]))
				values[i] = f.length();
		}
		c.addRow(values);
		return c;
	}

	public Uri insert(Uri uri, ContentValues values) {
		throw new UnsupportedOperationException();
	}

	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}

	public int delete(Uri uri, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}
}
