package com.terafiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared links: parsing of TeraBox's share list (port of TeraBox wrapper sharedLinks) */
public class TeraShares {

	public static class Share {
		public String id = "", url = "", path = "", name = "", state = "unknown";
		public boolean isDir;
	}

	static final Pattern PUBLIC_URL = Pattern.compile(
			"^https?://(?:[a-z0-9-]+\\.)*(?:terabox\\.com|teraboxapp\\.com|1024terabox\\.com|teraboxlink\\.com)/(?:s/)?[A-Za-z0-9_\\-/%]+(?:\\?[A-Za-z0-9_=&%\\-]+)?$",
			Pattern.CASE_INSENSITIVE);

	static String normalizeUrl(String v) {
		v = v.trim();
		return PUBLIC_URL.matcher(v).matches() ? v : "";
	}

	/** a usable public link from the share's fields */
	public static String urlFrom(JSONObject it) {
		if (it == null)
			return "";
		String[] keys = new String[] { "link", "shortlink", "short_link", "share_url", "share_link", "shareurl", "url",
				"shorturl", "short_url", "surl", "shorturl_id" };
		for (int i = 0; i < keys.length; i++) {
			if (!it.has(keys[i]) || it.isNull(keys[i]))
				continue;
			String v = it.optString(keys[i], "").trim();
			if (v.length() == 0)
				continue;
			String url = normalizeUrl(v);
			if (url.length() > 0)
				return url;
			boolean shortKey = keys[i].equals("shorturl") || keys[i].equals("short_url") || keys[i].equals("surl")
					|| keys[i].equals("shorturl_id");
			if (shortKey && v.matches("^(?:s/)?[A-Za-z0-9_-]{3,128}$"))
				return "https://www.terabox.com/s/" + (v.startsWith("s/") ? v.substring(2) : v);
			if (v.matches("^/s/[A-Za-z0-9_-]{3,128}$"))
				return "https://www.terabox.com" + v;
		}
		return "";
	}

	static JSONArray records(Object o) {
		if (o instanceof JSONArray)
			return (JSONArray) o;
		if (!(o instanceof JSONObject))
			return null;
		JSONObject j = (JSONObject) o;
		String[] k1 = new String[] { "list", "records", "share_list", "shareList", "shares", "items" };
		for (int i = 0; i < k1.length; i++) {
			Object v = j.opt(k1[i]);
			if (v instanceof JSONArray || v instanceof JSONObject) {
				JSONArray r = records(v);
				if (r != null)
					return r;
			}
		}
		String[] k2 = new String[] { "data", "result", "info" };
		for (int i = 0; i < k2.length; i++) {
			Object v = j.opt(k2[i]);
			if (v instanceof JSONArray || v instanceof JSONObject) {
				JSONArray r = records(v);
				if (r != null)
					return r;
			}
		}
		return null;
	}

	static String stateOf(JSONObject it) {
		String[] flags = new String[] { "is_cancelled", "is_deleted", "is_expired", "cancelled", "deleted" };
		for (int i = 0; i < flags.length; i++) {
			if (it.has(flags[i])) {
				String v = it.optString(flags[i], "");
				if (v.equals("true") || v.equals("1"))
					return "cancelled";
			}
		}
		String[] keys = new String[] { "status", "share_status", "shareStatus", "state", "expiredType", "expired_type" };
		for (int i = 0; i < keys.length; i++) {
			if (!it.has(keys[i]))
				continue;
			String st = it.optString(keys[i], "").trim().toLowerCase();
			if (st.equals("-1"))
				return "cancelled";
			if (st.equals("cancelled") || st.equals("canceled") || st.equals("deleted") || st.equals("expired")
					|| st.equals("invalid") || st.equals("inactive") || st.equals("revoked"))
				return "cancelled";
			if (st.equals("active") || st.equals("valid") || st.equals("sharing"))
				return "active";
		}
		return "unknown";
	}

	static String stateFromErrno(int err) {
		if (err == Integer.MIN_VALUE)
			return "unknown";
		if (err == 0 || err == -9)
			return "active";
		int[] bad = new int[] { 105, 145, 115, -21, 200025, 200026, 2131, 110, 112, 114 };
		for (int i = 0; i < bad.length; i++)
			if (bad[i] == err)
				return "cancelled";
		return "unknown";
	}

	public static String codeOf(String url) {
		Matcher m = Pattern.compile("/s/([A-Za-z0-9_-]+)(?:[?#].*)?$").matcher(url);
		return m.find() ? m.group(1) : "";
	}

	// ---- verification cache: a link's public status rarely changes, and dead links stay in TeraBox's list forever,
	// so asking again on every visit is what made the page slow.

	static final HashMap<String, long[]> CACHE = new HashMap<String, long[]>(); // code -> {1 active | 2 cancelled, time ms}
	static final long ACTIVE_TTL = 10L * 60 * 1000; // an active link is re-checked after 10 minutes
	static final long DEAD_TTL = 7L * 24 * 3600 * 1000; // a stopped / expired link stays stopped

	/** "active", "cancelled" or null when there is no fresh answer */
	static synchronized String cached(String code) {
		long[] v = CACHE.get(code);
		if (v == null)
			return null;
		long age = System.currentTimeMillis() - v[1];
		if (v[0] == 1 && age <= ACTIVE_TTL)
			return "active";
		if (v[0] == 2 && age <= DEAD_TTL)
			return "cancelled";
		return null;
	}

	static synchronized void remember(String code, String state) {
		if (code.length() == 0)
			return;
		if (state.equals("active"))
			CACHE.put(code, new long[] { 1, System.currentTimeMillis() });
		else if (state.equals("cancelled"))
			CACHE.put(code, new long[] { 2, System.currentTimeMillis() });
	}

	public static synchronized void forget(String code) {
		CACHE.remove(code);
	}

	/** "code,1,time;code,2,time;..." for SharedPreferences */
	public static synchronized String exportCache() {
		StringBuilder sb = new StringBuilder();
		long now = System.currentTimeMillis();
		for (java.util.Map.Entry<String, long[]> e : CACHE.entrySet()) {
			if (now - e.getValue()[1] > DEAD_TTL)
				continue;
			if (sb.length() > 0)
				sb.append(';');
			sb.append(e.getKey()).append(',').append(e.getValue()[0]).append(',').append(e.getValue()[1]);
		}
		return sb.toString();
	}

	public static synchronized void importCache(String s) {
		if (s == null || s.length() == 0)
			return;
		String[] items = s.split(";");
		for (int i = 0; i < items.length; i++) {
			String[] f = items[i].split(",");
			if (f.length != 3 || !f[0].matches("^[A-Za-z0-9_-]{1,128}$"))
				continue;
			try {
				long st = Long.parseLong(f[1]), t = Long.parseLong(f[2]);
				if ((st == 1 || st == 2) && !CACHE.containsKey(f[0]))
					CACHE.put(f[0], new long[] { st, t });
			} catch (NumberFormatException e) {
			}
		}
	}

	/** called (from the loading thread) whenever the set of confirmed-active links changes */
	public interface Listener {
		void update(ArrayList<Share> active, int pending, boolean done);
	}

	static ArrayList<Share> activeOf(ArrayList<Share> all) {
		ArrayList<Share> r = new ArrayList<Share>();
		for (int i = 0; i < all.size(); i++)
			if ("active".equals(all.get(i).state))
				r.add(all.get(i));
		return r;
	}

	public static ArrayList<Share> fetchAll(final TeraClient c, HashSet<String> cancelledHere) throws Exception {
		return load(c, cancelledHere, null);
	}

	/**
	 * The account's active shares. Links the list itself reports as active (or the cache knows) appear at once; the rest
	 * are checked in parallel and appear as soon as they are confirmed. A link that cannot be confirmed is never shown
	 * as active.
	 */
	public static ArrayList<Share> load(final TeraClient c, HashSet<String> cancelledHere, Listener listener)
			throws Exception {
		ArrayList<Share> out = new ArrayList<Share>();
		HashSet<String> seen = new HashSet<String>();
		for (int page = 1; page <= 20; page++) {
			JSONObject r = c.shareList(page);
			int err = TeraClient.errnoOf(r);
			if (err == -6)
				throw new Exception("SESSION");
			if (r.has("errno") && err != 0)
				throw new Exception("TeraBox rejected the Shared Links request (errno " + err + ").");
			JSONArray list = records(r);
			if (list == null)
				list = new JSONArray();
			for (int i = 0; i < list.length(); i++) {
				JSONObject it = list.optJSONObject(i);
				if (it == null)
					continue;
				String id = "";
				String[] idk = new String[] { "share_id", "shareid", "shareId", "shareidstr" };
				for (int k = 0; k < idk.length && id.length() == 0; k++) {
					String v = it.optString(idk[k], "");
					if (v.matches("^[0-9]+$"))
						id = v;
				}
				String path = "";
				String[] pk = new String[] { "typicalPath", "typical_path", "path", "filename", "server_filename", "name",
						"title" };
				for (int k = 0; k < pk.length && path.length() == 0; k++) {
					String v = it.optString(pk[k], "");
					if (v.length() > 0)
						path = v;
				}
				if (path.length() == 0) {
					String[] fk = new String[] { "file_list", "filelist", "files" };
					for (int k = 0; k < fk.length && path.length() == 0; k++) {
						JSONArray fa = it.optJSONArray(fk[k]);
						if (fa != null && fa.length() > 0 && fa.optJSONObject(0) != null)
							path = fa.optJSONObject(0).optString("path", "");
					}
				}
				String url = urlFrom(it);
				if (url.length() == 0 && it.optJSONObject("data") != null)
					url = urlFrom(it.optJSONObject("data"));
				if (id.length() == 0 || url.length() == 0)
					continue;
				String st0 = stateOf(it);
				if (st0.equals("unknown")) {
					String known = cached(codeOf(url));
					if (known != null)
						st0 = known;
				}
				if (cancelledHere != null && cancelledHere.contains(id))
					st0 = "cancelled";
				
				// Only show shares explicitly reported active by the account API.
				if ("cancelled".equals(st0))
					continue;
				// Unknown is NOT active. Verify it after reading the list.
				if (!seen.add(id))
					continue;
				Share s = new Share();
				s.state = st0;
				s.id = id;
				s.url = url;
				s.path = path;
				String p = path.replaceAll("/+$", "");
				s.name = p.length() > 0 ? p.substring(p.lastIndexOf('/') + 1) : "Share #" + id;
				s.isDir = it.optInt("isdir", 0) == 1 || it.optInt("typical_category", 0) == -1;
				out.add(s);
			}
			if (list.length() < 100)
				break;
		}
		// Report what is already certain, then verify only the unknown entries in parallel.
		// Workers only return answers; this thread alone changes the shares, so the UI never sees a half-updated one.
		int pending = 0;
		for (int i = 0; i < out.size(); i++)
			if ("unknown".equals(out.get(i).state) && codeOf(out.get(i).url).length() > 0)
				pending++;
		if (listener != null)
			listener.update(activeOf(out), pending, pending == 0);
		if (pending == 0)
			return activeOf(out);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		ExecutorCompletionService<Object[]> done = new ExecutorCompletionService<Object[]>(pool);
		// One overall deadline for all checks; a link that cannot be confirmed by then stays hidden.
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		try {
			for (int i = 0; i < out.size(); i++) {
				final int index = i;
				final String code = codeOf(out.get(i).url);
				if (!"unknown".equals(out.get(i).state) || code.length() == 0)
					continue;
				done.submit(new Callable<Object[]>() {
					public Object[] call() {
						return new Object[] { Integer.valueOf(index), stateFromErrno(c.shortUrlErrno(code)), code };
					}
				});
			}
			int left = pending;
			while (left > 0) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0)
					break;
				Future<Object[]> f = done.poll(remaining, TimeUnit.NANOSECONDS);
				if (f == null)
					break;
				left--;
				try {
					Object[] r = f.get();
					String state = (String) r[1];
					out.get(((Integer) r[0]).intValue()).state = state;
					remember((String) r[2], state);
				} catch (Exception ex) {
					// an inconclusive check leaves the link "unknown" (hidden)
				}
				if (listener != null)
					listener.update(activeOf(out), left, left == 0);
			}
			if (left > 0 && listener != null)
				listener.update(activeOf(out), 0, true);
		} finally {
			pool.shutdownNow();
		}
		return activeOf(out);
	}
}
