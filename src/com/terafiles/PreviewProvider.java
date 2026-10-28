package com.terafiles;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only access to cached previews for installed image/video/PDF viewers. */
public class PreviewProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment().toLowerCase(java.util.Locale.US);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".heic")) return "image/heic";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".3gp")) return "video/3gpp";
        return n.matches(".*\\.(jpg|jpeg)$") ? "image/jpeg" : "application/octet-stream";
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        String name = uri.getLastPathSegment();
        if (name == null || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0) throw new FileNotFoundException();
        File dir = new File(getContext().getCacheDir(), "previews");
        File file = new File(dir, name);
        try { if (!file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new FileNotFoundException(); }
        catch (java.io.IOException ex) { throw new FileNotFoundException(); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String[] cols = projection == null ? new String[]{"_display_name", "_size"} : projection;
        android.database.MatrixCursor c = new android.database.MatrixCursor(cols);
        Object[] values = new Object[cols.length];
        File f = new File(new File(getContext().getCacheDir(), "previews"), uri.getLastPathSegment());
        for (int i=0; i<cols.length; i++) {
            if ("_display_name".equals(cols[i])) values[i] = f.getName();
            else if ("_size".equals(cols[i])) values[i] = f.length();
        }
        c.addRow(values);
        return c;
    }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
