package com.terafiles;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.zip.CRC32;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * TeraBox web client in plain Java (HttpURLConnection + org.json).
 * Port of TeraBoxAuth.php: password login (prelogin / RSA / login), slide captcha, session check, list, quota, download.
 * Every network method throws on failure, so call them from a background thread.
 */
public class TeraClient {

	static final String DOMAIN = "terabox.com";
	static final String UA = "terabox;1.40.0.132;PC;PC-Windows;10.0.26100;WindowsTeraBox";
	static final int TIMEOUT = 20000;
	static final Pattern TEMPLATE = Pattern.compile("<script>var templateData = (.*?);</script>", Pattern.DOTALL);
	static final Pattern HOST_RE = Pattern.compile("(?:^|\\.)(?:1024)?terabox\\.com$", Pattern.CASE_INSENSITIVE);

	String whost = "https://www." + DOMAIN;
	LinkedHashMap<String, String> jar = new LinkedHashMap<String, String>();
	String pcftoken = "";
	String pubkey = "";
	/** last request summary for the log line shown on errors: "POST /passport/login -> 200" */
	public String lastStep = "";

	public static class Resp {
		public int status;
		public Map<String, String> headers = new LinkedHashMap<String, String>();
		public List<String> setCookies = new ArrayList<String>();
		public String body = "";
	}

	public static class LoginResult {
		public boolean ok;
		public String ndus = "";
		public int code;
		public String msg = "";
		public boolean captchaRequired;
		public String requestId = "";
	}

	public interface Progress {
		void onProgress(long done, long total);

		boolean cancelled();
	}

	public TeraClient(String cookieOrNdus, String host) {
		jar.put("lang", "en");
		if (cookieOrNdus != null && cookieOrNdus.length() > 0) {
			if (cookieOrNdus.indexOf('=') < 0)
				jar.put("ndus", cookieOrNdus.trim());
			else
				jar = parseCookies(cookieOrNdus);
		}
		if (host != null && host.matches("(?i)^https://(?:[a-z0-9-]+\\.)*terabox\\.com$"))
			whost = host;
	}

	// ------------------------------------------------------------ cookies

	static LinkedHashMap<String, String> parseCookies(String s) {
		LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
		String[] parts = s.split(";");
		for (int i = 0; i < parts.length; i++) {
			String p = parts[i].trim();
			if (p.length() == 0)
				continue;
			int eq = p.indexOf('=');
			if (eq < 0)
				out.put(p, "");
			else
				out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
		}
		return out;
	}

	public synchronized String getCookie() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : jar.entrySet()) {
			if (sb.length() > 0)
				sb.append("; ");
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.toString();
	}

	public String getWhost() {
		return whost;
	}

	public synchronized String getNdus() {
		String v = jar.get("ndus");
		return v == null ? "" : v;
	}

	public synchronized String getBrowserId() {
		String v = jar.get("browserid");
		return v == null ? "" : v;
	}

	public synchronized void useBrowserId(String id) {
		if (id != null && id.matches("^[A-Za-z0-9_-]{8,128}$"))
			jar.put("browserid", id);
	}

	static boolean expiredCookie(String[] attrs) {
		for (int i = 1; i < attrs.length; i++) {
			String a = attrs[i].trim();
			String l = a.toLowerCase(Locale.US);
			if (l.startsWith("max-age")) {
				int eq = a.indexOf('=');
				if (eq > 0) {
					try {
						if (Long.parseLong(a.substring(eq + 1).trim()) <= 0)
							return true;
					} catch (Exception e) {
					}
				}
			} else if (l.startsWith("expires")) {
				int eq = a.indexOf('=');
				if (eq > 0) {
					String d = a.substring(eq + 1).trim();
					String[] fmts = new String[] { "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd-MMM-yyyy HH:mm:ss zzz" };
					for (int k = 0; k < fmts.length; k++) {
						try {
							Date dt = new SimpleDateFormat(fmts[k], Locale.US).parse(d);
							if (dt != null && dt.getTime() <= System.currentTimeMillis())
								return true;
							break;
						} catch (Exception e) {
						}
					}
				}
			}
		}
		return false;
	}

	synchronized void mergeCookies(List<String> headers) {
		for (int i = 0; i < headers.size(); i++) {
			String[] parts = headers.get(i).split(";");
			if (parts.length == 0)
				continue;
			String first = parts[0].trim();
			int eq = first.indexOf('=');
			if (eq < 0)
				continue;
			String name = first.substring(0, eq).trim();
			if (!name.matches("^[A-Za-z0-9_-]{1,128}$"))
				continue;
			String value = first.substring(eq + 1).trim();
			if (value.length() == 0 || expiredCookie(parts))
				jar.remove(name);
			else
				jar.put(name, value);
		}
	}

	// ------------------------------------------------------------ HTTP

	static String enc(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
		} catch (Exception e) {
			return s;
		}
	}

	static String query(LinkedHashMap<String, String> m) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : m.entrySet()) {
			if (sb.length() > 0)
				sb.append('&');
			sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
		}
		return sb.toString();
	}

	static String readAll(InputStream in) throws IOException {
		if (in == null)
			return "";
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0)
			bo.write(buf, 0, n);
		in.close();
		return bo.toString("UTF-8");
	}

	Resp request(String method, String url, LinkedHashMap<String, String> form, String[] extra, boolean sendCookie,
			boolean multipart, int timeout) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		try {
			c.setInstanceFollowRedirects(false);
			c.setConnectTimeout(10000);
			c.setReadTimeout(timeout);
			c.setRequestMethod(method);
			c.setRequestProperty("User-Agent", UA);
			if (sendCookie)
				c.setRequestProperty("Cookie", getCookie());
			if (extra != null) {
				for (int i = 0; i < extra.length; i++) {
					int colon = extra[i].indexOf(':');
					if (colon > 0)
						c.setRequestProperty(extra[i].substring(0, colon).trim(), extra[i].substring(colon + 1).trim());
				}
			}
			if ("POST".equals(method)) {
				byte[] body;
				if (multipart) {
					String boundary = "----TeraFiles" + System.currentTimeMillis();
					StringBuilder sb = new StringBuilder();
					if (form != null) {
						for (Map.Entry<String, String> e : form.entrySet()) {
							sb.append("--").append(boundary).append("\r\n");
							sb.append("Content-Disposition: form-data; name=\"").append(e.getKey()).append("\"\r\n\r\n");
							sb.append(e.getValue()).append("\r\n");
						}
					}
					sb.append("--").append(boundary).append("--\r\n");
					body = sb.toString().getBytes("UTF-8");
					c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
				} else {
					body = (form == null ? "" : query(form)).getBytes("UTF-8");
					c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
				}
				c.setDoOutput(true);
				c.setFixedLengthStreamingMode(body.length);
				OutputStream os = c.getOutputStream();
				os.write(body);
				os.close();
			}
			Resp r = new Resp();
			r.status = c.getResponseCode();
			String path = new URL(url).getPath();
			lastStep = method + " " + path + " -> " + r.status;
			for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
				if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty())
					continue;
				if (e.getKey().equalsIgnoreCase("set-cookie")) {
					r.setCookies.addAll(e.getValue());
				} else {
					r.headers.put(e.getKey().toLowerCase(Locale.US), e.getValue().get(0));
				}
			}
			InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
			r.body = readAll(in);
			if (sendCookie && !r.setCookies.isEmpty())
				mergeCookies(r.setCookies);
			return r;
		} finally {
			c.disconnect();
		}
	}

	Resp get(String url) throws Exception {
		return request("GET", url, null, null, true, false, TIMEOUT);
	}

	Resp post(String url, LinkedHashMap<String, String> form) throws Exception {
		return request("POST", url, form, null, true, false, TIMEOUT);
	}

	JSONObject json(Resp r) throws Exception {
		if (r.status != 200)
			throw new IOException("HTTP error! Status: " + r.status);
		try {
			return new JSONObject(r.body);
		} catch (Exception e) {
			throw new IOException("Invalid JSON response from TeraBox");
		}
	}

	static int codeOf(JSONObject j) {
		if (j.has("code"))
			return j.optInt("code", -1);
		if (j.has("errno"))
			return j.optInt("errno", -1);
		return -1;
	}

	// ------------------------------------------------------------ login

	/** Full password login. When TeraBox wants the slide captcha, ok=false and captchaRequired=true (code 460030). */
	public LoginResult login(String email, String password, String captchaToken) throws Exception {
		if (captchaToken == null)
			captchaToken = "";
		LoginResult res = new LoginResult();
		JSONObject pre = passportPreLogin(email);
		boolean required = codeOf(pre) == 460030;
		String rid = requestIdOf(pre);
		boolean haveSeval = pre.has("seval") && pre.has("random");
		if (required && (captchaToken.length() == 0 || !haveSeval)) {
			res.code = 460030;
			res.captchaRequired = true;
			res.requestId = rid;
			res.msg = "TeraBox requires human verification.";
			return res;
		}
		boolean okCode = codeOf(pre) == 0 || (required && captchaToken.length() > 0);
		if (!okCode || !haveSeval) {
			res.code = codeOf(pre);
			res.msg = pre.optString("msg", pre.optString("errmsg", "Pre-login failed"));
			return res;
		}
		JSONObject j = passportLogin(pre, email, password, captchaToken);
		int code = codeOf(j);
		String ndus = getNdus();
		if (code == 0 && ndus.length() > 0) {
			JSONObject chk;
			try {
				chk = checkLogin(0);
			} catch (Exception e) {
				res.code = -2;
				res.msg = "Session verification was unavailable.";
				return res;
			}
			if (chk.optInt("errno", -1) != 0) {
				res.code = -2;
				res.msg = "Session verification was not accepted.";
				return res;
			}
			res.ok = true;
			res.ndus = ndus;
			res.msg = "OK";
			return res;
		}
		res.code = code;
		res.msg = j.optString("msg", j.optString("errmsg", "Login failed"));
		if (code == 460030) {
			res.captchaRequired = true;
			res.requestId = requestIdOf(j);
		}
		return res;
	}

	static String requestIdOf(JSONObject r) {
		JSONObject data = r.optJSONObject("data");
		String[] keys = new String[] { "request_id_string", "request_id" };
		for (int i = 0; i < keys.length; i++) {
			String v = r.has(keys[i]) ? r.optString(keys[i], "") : "";
			if (v.length() == 0 && data != null && data.has(keys[i]))
				v = data.optString(keys[i], "");
			if (v.matches("^[0-9]{8,24}$"))
				return v;
		}
		return "";
	}

	JSONObject passportPreLogin(String email) throws Exception {
		if (pcftoken.length() == 0)
			updateAppData("wap/outlogin/login", 0);
		LinkedHashMap<String, String> f = new LinkedHashMap<String, String>();
		f.put("client", "web");
		f.put("pass_version", "2.8");
		f.put("clientfrom", "h5");
		f.put("pcftoken", pcftoken);
		f.put("email", email);
		Resp r = request("POST", whost + "/passport/prelogin", f, new String[] { "Referer: " + whost }, true, false,
				TIMEOUT);
		JSONObject j = json(r);
		JSONObject data = j.optJSONObject("data");
		if (!j.has("seval") && data != null && data.has("seval")) {
			Iterator<String> it = data.keys();
			while (it.hasNext()) {
				String k = it.next();
				j.put(k, data.get(k));
			}
		}
		return j;
	}

	JSONObject passportLogin(JSONObject pre, String email, String pass, String captchaToken) throws Exception {
		if (pubkey.length() == 0)
			getPublicKey();
		String browserid = getBrowserId();
		String encpwd = TeraCrypto.changeBase64Type(TeraCrypto.encryptRsa(pass, pubkey, 2), 1);
		String seval = pre.optString("seval", "");
		String random = pre.optString("random", "");
		String prand = TeraCrypto.prandGen(seval, encpwd, email, random, "web", browserid);
		LinkedHashMap<String, String> f = new LinkedHashMap<String, String>();
		f.put("client", "web");
		f.put("pass_version", "2.8");
		f.put("clientfrom", "h5");
		f.put("pcftoken", pcftoken);
		f.put("prand", prand);
		f.put("email", email);
		f.put("pwd", encpwd);
		f.put("seval", seval);
		f.put("random", random);
		f.put("timestamp", pre.optString("timestamp", ""));
		if (captchaToken.length() > 0)
			f.put("capt_token", captchaToken);
		Resp r = request("POST", whost + "/passport/login", f, new String[] { "Referer: " + whost }, true, false,
				TIMEOUT);
		return json(r);
	}

	void getPublicKey() throws Exception {
		Resp r = request("GET", whost + "/passport/getpubkey", null, null, false, false, TIMEOUT);
		JSONObject j = json(r);
		if (j.optInt("code", -1) != 0)
			throw new IOException("getPublicKey: " + j.optString("msg", "unexpected response"));
		JSONObject d = j.getJSONObject("data");
		pubkey = TeraCrypto.decryptAes(d.getString("pp1"), d.getString("pp2"));
	}

	/** Fetch a TeraBox page and scrape pcftoken (the login form token). */
	void updateAppData(String path, int hops) throws Exception {
		while (path.startsWith("/"))
			path = path.substring(1);
		Resp r = get(whost + "/" + path);
		if (r.status == 302 && hops < 5) {
			String loc = r.headers.get("location");
			if (loc == null || loc.length() == 0 || loc.equals("/login"))
				loc = whost + "/login";
			URL u = new URL(new URL(whost + "/"), loc);
			if (!"https".equals(u.getProtocol()) || !u.getHost().matches("(?i)^(?:[a-z0-9-]+\\.)*terabox\\.com$")
					|| u.getUserInfo() != null || u.getPort() != -1)
				throw new IOException("Untrusted login redirect rejected.");
			whost = "https://" + u.getHost();
			String next = u.getPath() + (u.getQuery() != null ? "?" + u.getQuery() : "");
			updateAppData(next, hops + 1);
			return;
		}
		mergeCookies(r.setCookies);
		Matcher m = TEMPLATE.matcher(r.body);
		if (m.find()) {
			try {
				JSONObject t = new JSONObject(m.group(1));
				String tok = t.optString("pcftoken", "");
				if (tok.length() > 0)
					pcftoken = tok;
			} catch (Exception e) {
			}
		}
		if (pcftoken.length() == 0)
			throw new IOException("TeraBox did not return a login token (HTTP " + r.status
					+ "). It may be temporarily limiting requests from this network.");
	}

	// ------------------------------------------------------------ slide captcha

	public JSONObject getSlide(String requestId) throws Exception {
		if (!requestId.matches("^[0-9]{8,24}$"))
			throw new IOException("Invalid challenge request ID.");
		LinkedHashMap<String, String> q = new LinkedHashMap<String, String>();
		q.put("type", "dragdrop");
		q.put("lang", "en");
		q.put("platform", "web");
		q.put("isDark", "false");
		q.put("relation_logid", requestId);
		q.put("_t", String.valueOf(System.currentTimeMillis()));
		Resp r = request("GET", "https://www.terabox.com/captcha/getslide?" + query(q), null,
				new String[] { "Referer: https://www.terabox.com/anticapt?type=dragdrop&requestId=" + requestId
						+ "&lang=en&platform=web" }, true, false, TIMEOUT);
		return json(r);
	}

	public JSONObject checkSlide(String requestId, String challengeId, int dx, int dy) throws Exception {
		if (!requestId.matches("^[0-9]{8,24}$") || challengeId.length() == 0 || challengeId.length() > 512)
			throw new IOException("Invalid challenge.");
		LinkedHashMap<String, String> f = new LinkedHashMap<String, String>();
		f.put("challenge_id", challengeId);
		f.put("delta_x", String.valueOf(dx));
		f.put("delta_y", String.valueOf(dy));
		f.put("type", "dragdrop");
		f.put("lang", "en");
		f.put("isDark", "false");
		f.put("platform", "web");
		f.put("relation_logid", requestId);
		Resp r = request("POST", "https://www.terabox.com/captcha/checkslide", f,
				new String[] { "Referer: https://www.terabox.com/anticapt?type=dragdrop&requestId=" + requestId
						+ "&lang=en&platform=web" }, true, true, TIMEOUT);
		return json(r);
	}

	/** Returns the object (the response itself or data / data.challenge / challenge) that holds key, or null. */
	public static JSONObject pick(JSONObject r, String key) {
		JSONObject data = r.optJSONObject("data");
		JSONObject[] c = new JSONObject[] { r, data, data == null ? null : data.optJSONObject("challenge"),
				r.optJSONObject("challenge") };
		for (int i = 0; i < c.length; i++)
			if (c[i] != null && c[i].has(key))
				return c[i];
		return null;
	}

	// ------------------------------------------------------------ session / files

	/** GET /api/check/login (follows the region host redirect hint) */
	public JSONObject checkLogin(int depth) throws Exception {
		Resp r = get(whost + "/api/check/login");
		String prefix = r.headers.get("region-domain-prefix");
		if (prefix != null && prefix.matches("(?i)^[a-z0-9-]{1,63}$") && depth < 3
				&& !(prefix + "." + DOMAIN).equalsIgnoreCase(new URL(whost).getHost())) {
			whost = "https://" + prefix + "." + DOMAIN;
			return checkLogin(depth + 1);
		}
		return json(r);
	}

	public JSONObject listDir(String dir, int page, int num) throws Exception {
		LinkedHashMap<String, String> f = new LinkedHashMap<String, String>();
		f.put("order", "name");
		f.put("desc", "0");
		f.put("dir", dir);
		f.put("num", String.valueOf(num));
		f.put("page", String.valueOf(page));
		f.put("showempty", "0");
		JSONObject j = json(post(whost + "/api/list", f));
		if (!j.has("list")) {
			JSONObject d = j.optJSONObject("data");
			if (d != null && d.has("list"))
				j.put("list", d.get("list"));
		}
		return j;
	}

	/** All entries of a folder: {"errno":0,"list":[...]} or the raw error response */
	public JSONObject listAll(String dir) throws Exception {
		JSONArray all = new JSONArray();
		int num = 1000;
		for (int page = 1; page <= 100; page++) {
			JSONObject j = listDir(dir, page, num);
			if (j.optInt("errno", 0) != 0)
				return j;
			JSONArray items = j.optJSONArray("list");
			int n = items == null ? 0 : items.length();
			for (int i = 0; i < n; i++)
				all.put(items.get(i));
			if (n < num)
				break;
		}
		JSONObject out = new JSONObject();
		out.put("errno", 0);
		out.put("list", all);
		return out;
	}

	public JSONObject getQuota() throws Exception {
		return json(get(whost + "/api/quota?checkexpire=1&checkfree=1"));
	}

	/** a dlink value can be a plain string, a list [{"fs_id":..,"dlink":..}] or an object */
	static String dlinkFrom(Object v) {
		if (v instanceof String) {
			String t = ((String) v).trim();
			return t.startsWith("https://") ? t : "";
		}
		if (v instanceof JSONArray) {
			JSONArray a = (JSONArray) v;
			for (int i = 0; i < a.length(); i++) {
				String r = dlinkFrom(a.opt(i));
				if (r.length() > 0)
					return r;
			}
			return "";
		}
		if (v instanceof JSONObject)
			return dlinkFrom(((JSONObject) v).opt("dlink"));
		return "";
	}

	static String findDlink(JSONObject j) {
		JSONObject d = j.optJSONObject("data");
		Object[] cands = new Object[] { j.opt("dlink"), d == null ? null : d.opt("list"), j.opt("list"), j.opt("info"),
				d == null ? null : d.opt("dlink") };
		for (int i = 0; i < cands.length; i++) {
			String r = dlinkFrom(cands[i]);
			if (r.length() > 0)
				return r;
		}
		return "";
	}

	/** Temporary direct link for one file (home/info signature + /api/download, filemetas as fallback). */
	public String getDownloadLink(String path, String fsId) throws Exception {
		JSONObject h = json(get(whost + "/api/home/info"));
		String d = "";
		JSONObject hd = h.optJSONObject("data");
		if (h.optInt("errno", -1) == 0 && hd != null && hd.optString("sign1", "").length() > 0
				&& hd.optString("sign3", "").length() > 0) {
			String sign = TeraCrypto.signDownload(hd.optString("sign3"), hd.optString("sign1"));
			LinkedHashMap<String, String> f = new LinkedHashMap<String, String>();
			f.put("fidlist", "[" + fsId + "]");
			f.put("type", "dlink");
			f.put("vip", "2");
			f.put("sign", sign);
			f.put("timestamp", hd.optString("timestamp", ""));
			f.put("need_speed", "1");
			d = findDlink(json(post(whost + "/api/download", f)));
		}
		if (d.length() == 0) {
			LinkedHashMap<String, String> f = new LinkedHashMap<String, String>();
			f.put("dlink", "1");
			f.put("origin", "dlna");
			f.put("target", new JSONArray().put(path).toString());
			d = findDlink(json(post(whost + "/api/filemetas", f)));
		}
		if (d.length() == 0)
			throw new IOException("TeraBox did not return a download link for this file.");
		return d;
	}

	/**
	 * Stream a dlink to a local file (https only; the cookie is sent to TeraBox hosts only). If dest already holds a
	 * partial download (smaller than expectedSize) the transfer continues from there with a Range request; a server that
	 * ignores Range simply restarts the file. A failed transfer keeps what it wrote so the next call can resume.
	 */
	public void downloadTo(String url, File dest, long expectedSize, Progress pg) throws Exception {
		long have = dest.exists() ? dest.length() : 0;
		if (expectedSize > 0 && have > expectedSize) {
			dest.delete();
			have = 0;
		}
		if (expectedSize > 0 && have == expectedSize) {
			if (pg != null)
				pg.onProgress(have, expectedSize);
			return;
		}
		for (int hop = 0; hop <= 6; hop++) {
			URL u = new URL(url);
			if (!"https".equals(u.getProtocol()) || u.getHost().length() == 0 || u.getUserInfo() != null)
				throw new IOException("Refusing a non-HTTPS download link.");
			HttpURLConnection c = (HttpURLConnection) u.openConnection();
			try {
				c.setInstanceFollowRedirects(false);
				c.setConnectTimeout(15000);
				c.setReadTimeout(60000);
				c.setRequestProperty("User-Agent", UA);
				if (HOST_RE.matcher(u.getHost()).find())
					c.setRequestProperty("Cookie", getCookie());
				if (have > 0)
					c.setRequestProperty("Range", "bytes=" + have + "-");
				int status = c.getResponseCode();
				if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
					String loc = c.getHeaderField("Location");
					if (loc == null || loc.length() == 0)
						throw new IOException("Bad redirect from TeraBox.");
					url = new URL(u, loc).toString();
					continue;
				}
				if (status == 416 && have > 0) { // the partial file does not fit the remote file: start over
					dest.delete();
					have = 0;
					continue;
				}
				boolean partial = status == 206 && have > 0;
				if (partial) {
					String cr = c.getHeaderField("Content-Range");
					if (cr == null || !cr.trim().startsWith("bytes " + have + "-")) {
						dest.delete();
						have = 0;
						continue;
					}
				} else if (status != 200) {
					throw new IOException("Download failed (HTTP " + status + ").");
				}
				if (!partial)
					have = 0; // 200: the whole file is coming, the old partial data is overwritten
				long len = -1;
				try {
					len = Long.parseLong(c.getHeaderField("Content-Length"));
				} catch (Exception e) {
				}
				String contentType = c.getContentType();
				if (contentType != null && (contentType.toLowerCase(java.util.Locale.US).contains("text/html") ||
					contentType.toLowerCase(java.util.Locale.US).contains("application/json"))) {
					throw new IOException("Download server returned " + contentType + " instead of file data (HTTP " + status + ").");
				}
				long total = len > 0 ? len + have : expectedSize;
				InputStream in = c.getInputStream();
				FileOutputStream out = new FileOutputStream(dest, partial);
				try {
					byte[] buf = new byte[65536];
					long done = have, lastTick = 0;
					int n;
					while ((n = in.read(buf)) > 0) {
						if (pg != null && pg.cancelled())
							throw new IOException("Cancelled");
						out.write(buf, 0, n);
						done += n;
						long now = System.currentTimeMillis();
						if (pg != null && now - lastTick > 150) {
							lastTick = now;
							pg.onProgress(done, total);
						}
					}
					if (pg != null)
						pg.onProgress(done, total);
					if (expectedSize > 0 && done != expectedSize)
						throw new IOException("Size mismatch: got " + done + " of " + expectedSize + " bytes.");
				} finally {
					out.close();
					in.close();
				}
				return;
			} finally {
				c.disconnect();
			}
		}
		throw new IOException("Too many redirects.");
	}


	/** Fetch with a fresh download URL after a truncated or expired response.
	 * Never expose an incomplete file to the editor or previewer. */
	public void downloadEntry(String path, String fsId, File dest, long expectedSize, Progress pg) throws Exception {
		IOException last = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			if (pg != null && pg.cancelled()) throw new IOException("Cancelled");
			if (attempt > 0 && dest.exists()) dest.delete();
			try {
				String link = getDownloadLink(path, fsId);
				downloadTo(link, dest, expectedSize, pg);
				return;
			} catch (IOException ex) {
				last = ex;
				String msg = ex.getMessage() == null ? "" : ex.getMessage();
				if ("Cancelled".equals(msg) || msg.startsWith("Refusing a non-HTTPS")) throw ex;
				if (dest.exists()) dest.delete();
			}
		}
		throw new IOException("Unable to download the complete file after 3 attempts. " +
			(last == null ? "" : last.getMessage()));
	}

	// ================================================================== file operations (stage 2 / 3)

	static final LinkedHashMap<String, String> APP = new LinkedHashMap<String, String>();
	static {
		APP.put("app_id", "250528");
		APP.put("web", "1");
		APP.put("channel", "dubox");
		APP.put("clienttype", "0");
	}

	String jsToken = "";
	String uhost = "https://c-jp.terabox.com";

	/** app parameters + extra, RFC3986-encoded */
	String q(LinkedHashMap<String, String> extra) {
		LinkedHashMap<String, String> m = new LinkedHashMap<String, String>(APP);
		if (extra != null)
			m.putAll(extra);
		return query(m);
	}

	static LinkedHashMap<String, String> map(String... kv) {
		LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
		for (int i = 0; i + 1 < kv.length; i += 2)
			m.put(kv[i], kv[i + 1]);
		return m;
	}

	static int errnoOf(JSONObject j) {
		if (j.has("errno"))
			return j.optInt("errno", -1);
		if (j.has("error_code"))
			return j.optInt("error_code", -1);
		return -1;
	}

	/** Fetch the web app page and scrape the jsToken that file-changing APIs need. */
	public void refreshAppData(String path, int hops) throws Exception {
		while (path.startsWith("/"))
			path = path.substring(1);
		Resp r = get(whost + "/" + path);
		if (r.status == 302 && hops < 5) {
			String loc = r.headers.get("location");
			if (loc == null || loc.length() == 0 || loc.startsWith("/login"))
				throw new IOException("TeraBox session is not signed in.");
			URL u = new URL(new URL(whost + "/"), loc);
			if (!"https".equals(u.getProtocol()) || !HOST_RE.matcher(u.getHost()).find() || u.getUserInfo() != null
					|| u.getPort() != -1)
				throw new IOException("Untrusted redirect rejected.");
			whost = "https://" + u.getHost();
			refreshAppData(u.getPath() + (u.getQuery() != null ? "?" + u.getQuery() : ""), hops + 1);
			return;
		}
		if (r.status != 200)
			throw new IOException("HTTP error! Status: " + r.status);
		String tok = "";
		Matcher m = TEMPLATE.matcher(r.body);
		if (m.find()) {
			try {
				String js = new JSONObject(m.group(1)).optString("jsToken", "");
				if (js.length() > 0) {
					Matcher mm = Pattern.compile("%28%22(.*)%22%29").matcher(js);
					tok = mm.find() ? mm.group(1) : js;
				}
			} catch (Exception e) {
			}
		}
		if (tok.length() == 0) {
			Matcher mm = Pattern.compile("window\\.jsToken%20%3D%20a%7D%3Bfn%28%22(.*?)%22%29").matcher(r.body);
			if (mm.find())
				tok = mm.group(1);
		}
		if (tok.length() == 0)
			throw new IOException("Could not obtain the TeraBox page token (jsToken).");
		if (!tok.matches("^[A-Za-z0-9%_.\\-]{8,4096}$"))
			throw new IOException("Unexpected TeraBox page token format.");
		jsToken = tok;
	}

	void needToken() throws Exception {
		if (jsToken.length() == 0)
			refreshAppData("main", 0);
	}

	/** op: delete | copy | move | rename. list: [{"path":..,"dest":..,"newname":..}] */
	public JSONObject fileManager(String op, JSONArray list) throws Exception {
		return fileManager(op, list, "", false);
	}

	/** ondup: "" (default: fail on an existing name), "overwrite" or "newcopy" (keep both) */
	public JSONObject fileManager(String op, JSONArray list, String ondup) throws Exception {
		return fileManager(op, list, ondup, false);
	}

	JSONObject fileManager(String op, JSONArray list, String ondup, boolean retried) throws Exception {
		needToken();
		String url = whost + "/api/filemanager?" + q(map("jsToken", jsToken, "onnest", "fail", "opera", op));
		LinkedHashMap<String, String> form = map("filelist", compactJson(list));
		if (ondup != null && ondup.length() > 0)
			form.put("ondup", ondup);
		JSONObject j = json(post(url, form));
		int e = j.optInt("errno", 0);
		if (e == 450016 && !retried) {
			refreshAppData("main", 0);
			return fileManager(op, list, ondup, true);
		}
		return j;
	}

	// ---- JSON without escaped slashes (TeraBox's filemanager rejects "\/" in paths; Android's org.json always writes it)

	public static String compactJson(Object o) {
		StringBuilder sb = new StringBuilder();
		writeJson(sb, o);
		return sb.toString();
	}

	static void writeJson(StringBuilder sb, Object o) {
		if (o == null || o == JSONObject.NULL) {
			sb.append("null");
		} else if (o instanceof JSONObject) {
			JSONObject j = (JSONObject) o;
			sb.append('{');
			Iterator<String> it = j.keys();
			boolean first = true;
			while (it.hasNext()) {
				String k = it.next();
				if (!first)
					sb.append(',');
				first = false;
				writeStr(sb, k);
				sb.append(':');
				writeJson(sb, j.opt(k));
			}
			sb.append('}');
		} else if (o instanceof JSONArray) {
			JSONArray a = (JSONArray) o;
			sb.append('[');
			for (int i = 0; i < a.length(); i++) {
				if (i > 0)
					sb.append(',');
				writeJson(sb, a.opt(i));
			}
			sb.append(']');
		} else if (o instanceof Number || o instanceof Boolean) {
			sb.append(o.toString());
		} else {
			writeStr(sb, o.toString());
		}
	}

	static void writeStr(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"':
				sb.append("\\\"");
				break;
			case '\\':
				sb.append("\\\\");
				break;
			case '\n':
				sb.append("\\n");
				break;
			case '\r':
				sb.append("\\r");
				break;
			case '\t':
				sb.append("\\t");
				break;
			default:
				if (c < 0x20 || c == 0x2028 || c == 0x2029) {
					String h = Integer.toHexString(c);
					sb.append("\\u");
					for (int k = h.length(); k < 4; k++)
						sb.append('0');
					sb.append(h);
				} else {
					sb.append(c);
				}
			}
		}
		sb.append('"');
	}

	/** per-item failures of a file operation: "name: reason" lines (TeraBox answers errno 12 for a partial failure) */
	public static String fmDetails(JSONObject j) {
		JSONArray info = j.optJSONArray("info");
		if (info == null)
			return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < info.length() && i < 8; i++) {
			JSONObject it = info.optJSONObject(i);
			if (it == null || it.optInt("errno", 0) == 0)
				continue;
			int e = it.optInt("errno", 0);
			String path = it.optString("path", "");
			String why = e == -8 ? "already exists in the destination" : e == -9 ? "not found"
					: e == -7 ? "name not allowed" : "error " + e;
			if (sb.length() > 0)
				sb.append('\n');
			sb.append(path.length() > 0 ? path.substring(path.lastIndexOf('/') + 1) + ": " : "").append(why);
		}
		return sb.toString();
	}

	public static boolean hasConflict(JSONObject j) {
		JSONArray info = j.optJSONArray("info");
		for (int i = 0; info != null && i < info.length(); i++) {
			JSONObject it = info.optJSONObject(i);
			if (it != null && it.optInt("errno", 0) == -8)
				return true;
		}
		return false;
	}

	public JSONObject mkdir(String path) throws Exception {
		return json(post(whost + "/api/create?a=commit", map("path", path, "isdir", "1", "block_list", "[]")));
	}

	public JSONObject search(String term, int page) throws Exception {
		return json(get(whost + "/api/search?" + query(map("order", "name", "desc", "0", "num", "1000", "page",
				String.valueOf(page), "recursion", "1", "key", term))));
	}

	// ---- upload (precreate -> superfile2 chunks -> create)

	public static int chunkSize(long size) {
		int[] limits = new int[] { 4, 8, 16, 32, 64, 128 };
		for (int i = 0; i < limits.length; i++)
			if (size <= limits[i] * 1073741824L)
				return limits[i] * 1048576;
		return 128 * 1048576;
	}

	public static class Hashes {
		public ArrayList<String> chunks = new ArrayList<String>();
		public String slice = "", file = "";
		public long crc32;
		public int chunkSize;
	}

	/** One pass over the file: chunk MD5 list, MD5 of the first 256 KiB, whole-file MD5 and CRC32. */
	public static Hashes hashFile(File f, long size) throws Exception {
		Hashes h = new Hashes();
		h.chunkSize = chunkSize(size);
		MessageDigest file = MessageDigest.getInstance("MD5"), slice = MessageDigest.getInstance("MD5"),
				chunk = MessageDigest.getInstance("MD5");
		CRC32 crc = new CRC32();
		FileInputStream in = new FileInputStream(f);
		try {
			byte[] buf = new byte[65536];
			long all = 0;
			int inChunk = 0, n;
			while ((n = in.read(buf)) > 0) {
				file.update(buf, 0, n);
				crc.update(buf, 0, n);
				int off = 0;
				while (off < n) {
					int room = Math.min(n - off, h.chunkSize - inChunk);
					if (all < 262144)
						room = (int) Math.min(room, 262144 - all);
					chunk.update(buf, off, room);
					if (all < 262144)
						slice.update(buf, off, room);
					off += room;
					all += room;
					inChunk += room;
					if (inChunk >= h.chunkSize) {
						h.chunks.add(TeraCrypto.hex(chunk.digest()));
						inChunk = 0;
					}
				}
			}
			if (inChunk > 0)
				h.chunks.add(TeraCrypto.hex(chunk.digest()));
		} finally {
			in.close();
		}
		h.slice = TeraCrypto.hex(slice.digest());
		h.file = TeraCrypto.hex(file.digest());
		h.crc32 = crc.getValue();
		return h;
	}

	void locateUploadHost() {
		try {
			JSONObject j = json(get(whost + "/rest/2.0/pcs/file?method=locateupload"));
			String h = j.optString("host", "");
			if (h.length() > 0 && HOST_RE.matcher(h).find())
				uhost = "https://" + h;
		} catch (Exception e) {
		}
	}

	// ---- upload resume state: one small JSON file per (remote path, size, content MD5, chunk size)

	/** set by the app (e.g. new File(getFilesDir(), "resume")); without it uploads simply cannot be resumed */
	public static File resumeDir;
	static final long RESUME_MAX_AGE = 24L * 3600 * 1000;

	/** the server refused our saved upload session or a chunk (as opposed to a network failure) */
	static class Reject extends IOException {
		Reject(String m) {
			super(m);
		}
	}

	static File stateFile(String remote, long size, String md5, int chunk) {
		if (resumeDir == null)
			return null;
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] d = md.digest((remote + "|" + size + "|" + md5 + "|" + chunk).getBytes("UTF-8"));
			resumeDir.mkdirs();
			return new File(resumeDir, "up_" + TeraCrypto.hex(d) + ".json");
		} catch (Exception e) {
			return null;
		}
	}

	static JSONObject loadState(File f) {
		if (f == null || !f.exists())
			return null;
		try {
			byte[] b = new byte[(int) Math.min(f.length(), 1048576)];
			FileInputStream in = new FileInputStream(f);
			try {
				int off = 0, n;
				while (off < b.length && (n = in.read(b, off, b.length - off)) > 0)
					off += n;
			} finally {
				in.close();
			}
			JSONObject j = new JSONObject(new String(b, "UTF-8"));
			if (System.currentTimeMillis() - j.optLong("time", 0) > RESUME_MAX_AGE) {
				f.delete();
				return null;
			}
			return j;
		} catch (Exception e) {
			f.delete();
			return null;
		}
	}

	static void saveState(File f, String uploadId, String rtype, Collection<Integer> done) {
		if (f == null)
			return;
		try {
			JSONObject j = new JSONObject();
			j.put("uploadid", uploadId);
			j.put("rtype", rtype);
			j.put("time", System.currentTimeMillis());
			JSONArray a = new JSONArray();
			for (Integer i : done)
				a.put(i.intValue());
			j.put("done", a);
			File tmp = new File(f.getPath() + ".tmp");
			FileOutputStream o = new FileOutputStream(tmp);
			try {
				o.write(j.toString().getBytes("UTF-8"));
			} finally {
				o.close();
			}
			if (!tmp.renameTo(f)) {
				f.delete();
				tmp.renameTo(f);
			}
		} catch (Exception e) {
		}
	}

	/** Upload a local file into remoteDir (an existing file with that name is replaced). Returns the create response.
	 * An interrupted upload keeps its TeraBox upload session; calling this again with the same file continues with the
	 * chunks that are still missing. */
	public JSONObject uploadFile(File local, String remoteDir, String name, Progress pg) throws Exception {
		return uploadFile(local, remoteDir, name, pg, false);
	}

	JSONObject uploadFile(File local, String remoteDir, String name, Progress pg, boolean fresh) throws Exception {
		long size = local.length();
		String dir = "/" + remoteDir.replaceAll("^/+|/+$", "");
		String remote = (dir.equals("/") ? "" : dir) + "/" + name;
		if (size <= 0)
			return createEmpty(remote);
		Hashes h = hashFile(local, size);
		needToken();
		JSONArray blocks = new JSONArray();
		for (int i = 0; i < h.chunks.size(); i++)
			blocks.put(h.chunks.get(i));
		File stf = stateFile(remote, size, h.file, h.chunkSize);
		TreeSet<Integer> done = new TreeSet<Integer>();
		String rtype = "3";
		String uploadId = "";
		boolean resumed = false;
		JSONObject saved = fresh ? null : loadState(stf);
		if (saved != null && saved.optString("uploadid", "").length() > 0) {
			uploadId = saved.optString("uploadid");
			rtype = saved.optString("rtype", "3");
			JSONArray da = saved.optJSONArray("done");
			for (int i = 0; da != null && i < da.length(); i++)
				if (da.optInt(i, -1) >= 0 && da.optInt(i, -1) < h.chunks.size())
					done.add(da.optInt(i));
			resumed = true;
		}
		if (!resumed) {
			LinkedHashMap<String, String> form = map("path", remote, "autoinit", "1", "size", String.valueOf(size),
					"file_limit_switch_v34", "true", "block_list", blocks.toString(), "rtype", rtype, "content-md5", h.file,
					"slice-md5", h.slice, "content-crc32", String.valueOf(h.crc32));
			JSONObject pre = new JSONObject();
			for (int tr = 0; tr < 3; tr++) {
				pre = json(post(whost + "/api/precreate?" + q(map("jsToken", jsToken)), form));
				int e = pre.optInt("errno", 0);
				if (e == 4000023 && tr == 0) {
					refreshAppData("main", 0);
					continue;
				}
				if (e != 0 && rtype.equals("3")) {
					rtype = "2";
					form.put("rtype", "2");
					continue;
				}
				break;
			}
			uploadId = pre.optString("uploadid", "");
			if (pre.optInt("errno", 0) != 0 || uploadId.length() == 0)
				throw new IOException("TeraBox precreate failed (errno " + pre.optInt("errno", -1) + ").");
			saveState(stf, uploadId, rtype, done);
		}
		locateUploadHost();
		for (int seq = 0; seq < h.chunks.size(); seq++) {
			long start = (long) seq * h.chunkSize;
			long want = Math.min((long) h.chunkSize, size - start);
			if (done.contains(seq)) {
				if (pg != null)
					pg.onProgress(Math.min(size, start + want), size);
				continue;
			}
			boolean ok = false;
			boolean rejected = false;
			String last = "";
			for (int tr = 1; tr <= 6 && !ok; tr++) {
				try {
					if (pg != null && pg.cancelled())
						throw new IOException("Cancelled");
					String url = uhost + "/rest/2.0/pcs/superfile2?" + query(mapPlus(
							map("method", "upload", "path", remote, "uploadid", uploadId, "partseq", String.valueOf(seq)), APP));
					JSONObject j = postChunk(url, local, start, want, pg, size);
					if (j.optInt("error_code", 0) != 0)
						throw new Reject("Upload failed! Error Code #" + j.optInt("error_code"));
					if (!h.chunks.get(seq).equals(j.optString("md5", "")))
						throw new Reject("Chunk checksum mismatch (part " + (seq + 1) + ").");
					ok = true;
				} catch (Exception e) {
					last = e.getMessage();
					if ("Cancelled".equals(last))
						throw e;
					if (e instanceof Reject && resumed && done.isEmpty()) { // a stale saved session: no point in retrying
						rejected = true;
						break;
					}
					if (tr < 6)
						sleepCancellable(Math.min(30000L, 2000L * tr), pg);
				}
			}
			if (!ok) {
				if (resumed && rejected && done.isEmpty()) { // the saved session is no longer valid: begin again
					if (stf != null)
						stf.delete();
					return uploadFile(local, remoteDir, name, pg, true);
				}
				throw new IOException("Upload failed at part " + (seq + 1) + " of " + h.chunks.size() + ": " + last);
			}
			done.add(seq);
			saveState(stf, uploadId, rtype, done);
			if (pg != null)
				pg.onProgress(Math.min(size, start + want), size);
		}
		JSONObject res = json(post(whost + "/api/create", map("path", remote, "size", String.valueOf(size), "isdir", "0",
				"content-md5", h.file, "slice-md5", h.slice, "content-crc32", String.valueOf(h.crc32), "block_list",
				blocks.toString(), "uploadid", uploadId, "rtype", rtype)));
		if (res.optInt("errno", 0) != 0) {
			if (resumed && !fresh) { // maybe the old session expired: one clean attempt
				if (stf != null)
					stf.delete();
				return uploadFile(local, remoteDir, name, pg, true);
			}
			throw new IOException("TeraBox create failed (errno " + res.optInt("errno", -1) + ").");
		}
		if (stf != null)
			stf.delete();
		fixAutoRename(res, remote, name);
		return res;
	}

	/** sleep in small steps so Cancel is noticed */
	static void sleepCancellable(long ms, Progress pg) throws Exception {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (pg != null && pg.cancelled())
				throw new IOException("Cancelled");
			Thread.sleep(250);
		}
	}

	static LinkedHashMap<String, String> mapPlus(LinkedHashMap<String, String> a, LinkedHashMap<String, String> b) {
		LinkedHashMap<String, String> m = new LinkedHashMap<String, String>(a);
		m.putAll(b);
		return m;
	}

	/** multipart/form-data POST of one chunk of a file (field "file", filename "blob") */
	JSONObject postChunk(String url, File src, long start, long len, Progress pg, long totalSize) throws Exception {
		String boundary = "----TeraFiles" + System.nanoTime();
		byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"blob\"\r\n"
				+ "Content-Type: application/octet-stream\r\n\r\n").getBytes("UTF-8");
		byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		RandomAccessFile raf = new RandomAccessFile(src, "r");
		try {
			c.setInstanceFollowRedirects(false);
			c.setConnectTimeout(15000);
			c.setReadTimeout(600000);
			c.setRequestMethod("POST");
			c.setRequestProperty("User-Agent", UA);
			c.setRequestProperty("Cookie", getCookie());
			c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
			c.setDoOutput(true);
			c.setFixedLengthStreamingMode(head.length + len + tail.length);
			OutputStream os = c.getOutputStream();
			os.write(head);
			raf.seek(start);
			byte[] buf = new byte[65536];
			long left = len, sent = 0, lastTick = 0;
			while (left > 0) {
				int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
				if (n <= 0)
					throw new IOException("Short read while uploading.");
				os.write(buf, 0, n);
				left -= n;
				sent += n;
				long now = System.currentTimeMillis();
				if (pg != null) {
					if (pg.cancelled())
						throw new IOException("Cancelled");
					if (now - lastTick > 150) {
						lastTick = now;
						pg.onProgress(Math.min(totalSize, start + sent), totalSize);
					}
				}
			}
			os.write(tail);
			os.close();
			int status = c.getResponseCode();
			String body = readAll(status >= 400 ? c.getErrorStream() : c.getInputStream());
			if (status != 200)
				throw new IOException("HTTP error! Status: " + status);
			return new JSONObject(body);
		} finally {
			raf.close();
			c.disconnect();
		}
	}

	JSONObject createEmpty(String remote) throws Exception {
		int last = -1;
		String[] variants = new String[] { "[]", "[\"d41d8cd98f00b204e9800998ecf8427e\"]" };
		for (int i = 0; i < variants.length; i++) {
			JSONObject res = json(post(whost + "/api/create",
					map("path", remote, "size", "0", "isdir", "0", "block_list", variants[i], "rtype", "3")));
			last = res.optInt("errno", -1);
			if (last == 0) {
				String name = remote.substring(remote.lastIndexOf('/') + 1);
				fixAutoRename(res, remote, name);
				return res;
			}
		}
		throw new IOException("TeraBox refused to create an empty file (errno " + last + ").");
	}

	/** If TeraBox kept the old file and saved ours as "name(1).ext", replace the old one with it. */
	void fixAutoRename(JSONObject res, String remote, String name) {
		String got = res.optString("path", "");
		if (got.length() == 0 || got.equals(remote))
			return;
		int slash = remote.lastIndexOf('/');
		String dir = slash <= 0 ? "/" : remote.substring(0, slash);
		String base = remote.substring(slash + 1);
		int dot = base.lastIndexOf('.');
		String stem = dot <= 0 ? base : base.substring(0, dot);
		String ext = dot <= 0 ? "" : base.substring(dot);
		String re = "^" + Pattern.quote((dir.equals("/") ? "" : dir) + "/" + stem) + "\\(\\d{1,3}\\)" + Pattern.quote(ext) + "$";
		if (!got.matches(re))
			return;
		try {
			JSONArray del = new JSONArray().put(new JSONObject().put("path", remote));
			JSONObject d = fileManager("delete", del);
			if (d.optInt("errno", -1) != 0)
				return;
			fileManager("rename", new JSONArray().put(new JSONObject().put("path", got).put("newname", name)));
		} catch (Exception e) {
		}
	}

	// ---- recycle bin

	public JSONObject recycleList(String dir) throws Exception {
		LinkedHashMap<String, String> m = map("desc", "0", "num", "20000", "page", "1");
		if (dir.length() > 0)
			m.put("dir", dir);
		return json(get(whost + "/api/recycle/list?" + query(m)));
	}

	static JSONArray recycleItems(JSONObject j) {
		JSONArray a = j.optJSONArray("list");
		if (a != null)
			return a;
		JSONObject d = j.optJSONObject("data");
		if (d != null && d.optJSONArray("list") != null)
			return d.optJSONArray("list");
		return new JSONArray();
	}

	/** Contents of a deleted folder; TeraBox ignores unknown parameters, so a variant counts only when it differs from the top level. */
	public JSONArray recycleListIn(String dir, String fid) throws Exception {
		JSONObject top = recycleList("");
		if (top.optInt("errno", 0) != 0)
			return new JSONArray();
		ArrayList<String> topIds = new ArrayList<String>();
		JSONArray ta = recycleItems(top);
		for (int i = 0; i < ta.length(); i++)
			topIds.add(ta.getJSONObject(i).optString("fs_id", ""));
		java.util.Collections.sort(topIds);
		ArrayList<LinkedHashMap<String, String>> variants = new ArrayList<LinkedHashMap<String, String>>();
		variants.add(map("dir", dir));
		if (fid.length() > 0) {
			variants.add(map("dir", dir, "fid", fid));
			variants.add(map("fid", fid));
			variants.add(map("fs_id", fid));
			variants.add(map("parent_id", fid));
			variants.add(map("dir_fsid", fid));
		}
		variants.add(map("path", dir));
		for (int v = 0; v < variants.size(); v++) {
			LinkedHashMap<String, String> m = map("desc", "0", "num", "20000", "page", "1");
			m.putAll(variants.get(v));
			JSONObject j;
			try {
				j = json(get(whost + "/api/recycle/list?" + query(m)));
			} catch (Exception e) {
				continue;
			}
			if (j.optInt("errno", 0) != 0)
				continue;
			JSONArray list = recycleItems(j);
			if (list.length() == 0)
				continue;
			ArrayList<String> ids = new ArrayList<String>();
			boolean self = false;
			for (int i = 0; i < list.length(); i++) {
				String id = list.getJSONObject(i).optString("fs_id", "");
				ids.add(id);
				if (fid.length() > 0 && id.equals(fid))
					self = true;
			}
			java.util.Collections.sort(ids);
			if (self || ids.equals(topIds))
				continue;
			return list;
		}
		return new JSONArray();
	}

	public JSONObject recycleClear() throws Exception {
		return json(get(whost + "/api/recycle/clear?async=1"));
	}

	/** op: restore | delete (permanently). fsIds: numeric ids. */
	public JSONObject recycleAction(String op, List<String> fsIds) throws Exception {
		if (!op.equals("restore") && !op.equals("delete"))
			throw new IllegalArgumentException("Unsupported operation.");
		needToken();
		StringBuilder ids = new StringBuilder("[");
		for (int i = 0; i < fsIds.size(); i++) {
			if (!fsIds.get(i).matches("^[0-9]{1,20}$"))
				continue;
			if (ids.length() > 1)
				ids.append(',');
			ids.append(fsIds.get(i));
		}
		ids.append(']');
		String[][] variants = new String[][] { { "fsidlist", "1" }, { "fidlist", "1" }, { "fsidlist", "" },
				{ "fidlist", "" } };
		JSONObject last = new JSONObject();
		for (int v = 0; v < variants.length; v++) {
			JSONObject j = null;
			int e = 0;
			for (int attempt = 0; attempt < 2; attempt++) {
				LinkedHashMap<String, String> extra = map("jsToken", jsToken, "_t", String.valueOf(System.currentTimeMillis() / 1000));
				if (variants[v][1].length() > 0)
					extra.put("async", variants[v][1]);
				j = json(request("POST", whost + "/api/recycle/" + op + "?" + q(extra), map(variants[v][0], ids.toString()),
						new String[] { "Referer: " + whost }, true, false, TIMEOUT));
				e = j.optInt("errno", 0);
				if ((e == 450016 || e == 4000020) && attempt == 0) {
					refreshAppData("main", 0);
					continue;
				}
				break;
			}
			if (e != 2 && e != -1)
				return j;
			last = j;
		}
		return last;
	}

	// ---- share links

	public JSONObject shareSet(List<String> paths) throws Exception {
		JSONArray pl = new JSONArray();
		for (int i = 0; i < paths.size(); i++)
			pl.put(paths.get(i));
		return json(request("POST", whost + "/share/pset", map("schannel", "0", "channel_list", "[]", "period", "0",
				"path_list", pl.toString(), "pwd", ""), new String[] { "Referer: " + whost }, true, false, TIMEOUT));
	}

	public JSONObject shareList(int page) throws Exception {
		needToken();
		return json(request("GET", whost + "/share/teratransfer/sharelist?" + q(map("jsToken", jsToken, "page_size", "100",
				"page", String.valueOf(page), "_t", String.valueOf(System.currentTimeMillis() / 1000))), null,
				new String[] { "Referer: " + whost, "Cache-Control: no-cache", "Pragma: no-cache" }, true, false, TIMEOUT));
	}

	/** errno of /api/shorturlinfo for a public share code, or Integer.MIN_VALUE when the request failed */
	public int shortUrlErrno(String code) {
		try {
			Resp r = request("GET", whost + "/api/shorturlinfo?" + q(map("shorturl", code, "root", "1", "_t",
					String.valueOf(System.currentTimeMillis() / 1000))), null,
					new String[] { "Referer: " + whost, "Cache-Control: no-cache" }, true, false, 4500);
			if (r.status != 200)
				return Integer.MIN_VALUE;
			JSONObject j = new JSONObject(r.body);
			if (j.has("errno") || j.has("error_code"))
				return errnoOf(j);
		} catch (Exception e) {
		}
		return Integer.MIN_VALUE;
	}

	public JSONObject shareCancel(List<String> ids) throws Exception {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < ids.size(); i++) {
			if (!ids.get(i).matches("^[0-9]{1,20}$"))
				continue;
			if (sb.length() > 1)
				sb.append(',');
			sb.append(ids.get(i));
		}
		sb.append(']');
		return json(request("POST", whost + "/share/cancel", map("shareid_list", sb.toString()),
				new String[] { "Referer: " + whost }, true, false, TIMEOUT));
	}
}
