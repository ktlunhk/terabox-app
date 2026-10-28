package com.terafiles;

import org.json.JSONObject;

/** One file or folder in a TeraBox listing. */
public class TeraEntry {
	public String name = "";
	public String path = "";
	public boolean isDir;
	public long size;
	public long mtime;
	public String fsId = "";

	public static TeraEntry from(JSONObject o) {
		TeraEntry e = new TeraEntry();
		e.path = o.optString("path", "");
		e.name = o.optString("server_filename", "");
		if (e.name.length() == 0) {
			int slash = e.path.lastIndexOf('/');
			e.name = slash >= 0 ? e.path.substring(slash + 1) : e.path;
		}
		e.isDir = o.optInt("isdir", 0) == 1;
		e.size = o.optLong("size", 0);
		e.mtime = o.optLong("server_mtime", o.optLong("local_mtime", 0));
		e.fsId = o.optString("fs_id", "");
		return e;
	}
}
