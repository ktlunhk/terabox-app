package com.terafiles;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** TeraFiles: a small TeraBox file browser (login with slide captcha, browse folders, download files). */
public class MainActivity extends Activity {

	// colours: set by applyPalette() from the Theme setting (System default / Light / Dark)
	static int BG = 0xFF14171C, PANEL = 0xFF1E232B, TEXT = 0xFFE8EAED, MUTED = 0xFF9AA0A6, ACCENT = 0xFF2D8CFF,
			ERR = 0xFFFF6B6B, OK = 0xFF5BD68A, DIVIDER = 0xFF262C36, BTN = 0xFF374451;
	static boolean darkMode = true;

	static void applyPalette(boolean dark) {
		darkMode = dark;
		if (dark) {
			BG = 0xFF14171C;
			PANEL = 0xFF1E232B;
			TEXT = 0xFFE8EAED;
			MUTED = 0xFF9AA0A6;
			ACCENT = 0xFF2D8CFF;
			ERR = 0xFFFF6B6B;
			OK = 0xFF5BD68A;
			DIVIDER = 0xFF262C36;
			BTN = 0xFF374451;
		} else {
			BG = 0xFFF3F4F6;
			PANEL = 0xFFFFFFFF;
			TEXT = 0xFF1B1F24;
			MUTED = 0xFF5F6670;
			ACCENT = 0xFF1A73E8;
			ERR = 0xFFD93025;
			OK = 0xFF1E8E3E;
			DIVIDER = 0xFFD9DCE1;
			BTN = 0xFF6B7684;
		}
	}

	/** mode: 0 = follow the device, 1 = light, 2 = dark */
	boolean resolveDark(int mode) {
		if (mode == 1)
			return false;
		if (mode == 2)
			return true;
		return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
				== android.content.res.Configuration.UI_MODE_NIGHT_YES;
	}

	SharedPreferences prefs;
	ExecutorService exec = Executors.newSingleThreadExecutor();
	ExecutorService execDl = Executors.newCachedThreadPool();
	int dp1;

	// session
	TeraClient client;

	// login screen
	TeraClient loginClient;
	String loginEmailUsed = "";
	String captchaRid = "";
	String captchaChallengeId = "";
	String pendingToken = "";
	boolean loginBusy;
	EditText loginEmail, loginPass;
	TextView loginStatus;
	Button loginBtn;
	LinearLayout captchaPanel;
	SlideCaptchaView captchaView;

	// screens
	static final int SCREEN_LOGIN = 0, SCREEN_FILES = 1, SCREEN_TRASH = 2, SCREEN_SHARES = 3, SCREEN_SEARCH = 4, SCREEN_EDIT = 5, SCREEN_SETTINGS = 6;
	TextEditor editor; // open text editor, if any
	int screen;

	// files screen
	String curDir = "/";
	String searchTerm; // non-null while the list shows search results
	String uploadDir = "/";
	String quotaText = "";
	ArrayList<TeraEntry> entries = new ArrayList<TeraEntry>();
	HashMap<String, ArrayList<TeraEntry>> cache = new HashMap<String, ArrayList<TeraEntry>>();
	HashSet<String> sel = new HashSet<String>();
	HashSet<String> cancelledShares = new HashSet<String>();
	RowAdapter adapter;
	TextView titleView, infoView, emptyView, quotaTop;
	CheckBox selAll;
	TextView sortLabel, sortArrow;
	ProgressBar loadBar;
	int navSeq;
	JobStatus activeFolderUpload;

	// trash screen
	String trashDir = "", trashFid = "";
	ArrayList<String[]> trashStack = new ArrayList<String[]>();
	ArrayList<TeraEntry> trashEntries = new ArrayList<TeraEntry>();
	HashSet<String> tsel = new HashSet<String>();
	RowAdapter trashAdapter;
	TextView trashTitle, trashInfo, trashEmpty;
	ProgressBar trashLoad;

	// shared links screen
	ArrayList<TeraShares.Share> shares = new ArrayList<TeraShares.Share>();
	ShareAdapter shareAdapter;
	ListView shareList;
	TextView shareInfo;
	ProgressBar shareLoad;
	int shareLoadGeneration = 0;

	protected void onCreate(Bundle b) {
		SharedPreferences p0 = getSharedPreferences("terafiles", MODE_PRIVATE);
		boolean dark = resolveDark(p0.getInt("theme", 0));
		applyPalette(dark);
		int themeRes = dark ? 0 : getResources().getIdentifier("AppThemeLight", "style", getPackageName());
		if (!dark)
			setTheme(themeRes != 0 ? themeRes : android.R.style.Theme_Material_Light_NoActionBar);
		super.onCreate(b);
		dp1 = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
		prefs = getSharedPreferences("terafiles", MODE_PRIVATE);
		TeraClient.resumeDir = new File(getFilesDir(), "resume");
		if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
			requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 8);
		String ck = prefs.getString("cookie", "");
		if (ck.indexOf("ndus=") >= 0) {
			client = new TeraClient(ck, prefs.getString("whost", ""));
			showFiles();
			loadDir("/", true);
			if (prefs.getBoolean("reopenSettings", false)) {
				prefs.edit().remove("reopenSettings").commit();
				showSettings(); // the theme was just changed from the Settings page
			}
		} else {
			showLogin(null);
		}
	}

	protected void onResume() {
		super.onResume();
		TransferNotifier.appVisible = true;
		if (activeFolderUpload != null) activeFolderUpload.showInsideApp();
	}

	protected void onPause() {
		TransferNotifier.appVisible = false;
		if (activeFolderUpload != null) activeFolderUpload.hideInsideApp();
		super.onPause();
	}

	// ------------------------------------------------------------------ helpers

	int dp(int v) {
		return v * dp1;
	}

	void toast(final String s) {
		runOnUiThread(new Runnable() {
			public void run() {
				Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
			}
		});
	}

	static String human(long b) {
		if (b < 1024)
			return b + " B";
		double v = b / 1024.0;
		String[] u = new String[] { "KB", "MB", "GB", "TB" };
		int i = 0;
		while (v >= 1024 && i < u.length - 1) {
			v /= 1024;
			i++;
		}
		return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
	}

	TextView mkText(String s, int sp, int color) {
		TextView t = new TextView(this);
		t.setText(s);
		t.setTextSize(sp);
		t.setTextColor(color);
		return t;
	}

	Button mkBtn(String s) {
		Button bt = new Button(this);
		bt.setText(s);
		bt.setAllCaps(false);
		bt.setTextColor(Color.WHITE);
		bt.setTextSize(16);
		GradientDrawable g = new GradientDrawable();
		g.setColor(ACCENT);
		g.setCornerRadius(dp(10));
		bt.setBackground(g);
		return bt;
	}

	/** compact rounded button (search page and similar) */
	/** line icon (see Icons) on the left of a text, e.g. the outlined folder */
	void setIcon(TextView tv, String icon, int color) {
		android.graphics.drawable.Drawable d = Icons.get(icon, color);
		d.setBounds(0, 0, dp(22), dp(22));
		tv.setCompoundDrawables(d, null, null, null);
		tv.setCompoundDrawablePadding(dp(10));
	}

	Button mkSmallBtn(String s, int color) {
		Button bt = mkBtn(s);
		bt.setTextSize(14);
		bt.setMinWidth(0);
		bt.setMinimumWidth(0);
		bt.setMinHeight(0);
		bt.setMinimumHeight(0);
		bt.setPadding(dp(18), 0, dp(18), 0);
		GradientDrawable g = new GradientDrawable();
		g.setColor(color);
		g.setCornerRadius(dp(8));
		bt.setBackground(g);
		return bt;
	}

	void styleEdit(EditText e, String hint) {
		e.setHint(hint);
		e.setHintTextColor(MUTED);
		e.setTextColor(TEXT);
		e.setSingleLine(true);
		e.setTextSize(16);
	}

	void saveSession(String email) {
		if (client == null)
			return;
		SharedPreferences.Editor ed = prefs.edit();
		ed.putString("cookie", client.getCookie());
		ed.putString("whost", client.getWhost());
		if (client.getBrowserId().length() > 0)
			ed.putString("browserid", client.getBrowserId());
		if (email != null)
			ed.putString("email", email);
		ed.commit();
	}

	/** Shows the signed-in session cookie (and the ndus value) so it can be copied into another app. */
	void showCookieDialog(boolean afterLogin) {
		if (client == null)
			return;
		final String cookie = client.getCookie();
		final String ndus = client.getNdus();
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(20), dp(8), dp(20), 0);
		TextView warn = mkText("Anyone with this cookie can use your TeraBox account. Keep it private and sign out here "
				+ "if it leaks.", 12, MUTED);
		warn.setPadding(0, 0, 0, dp(8));
		box.addView(warn);
		TextView l1 = mkText("ndus (login token)", 13, MUTED);
		box.addView(l1);
		TextView v1 = mkText(ndus.length() > 0 ? ndus : "(none)", 12, TEXT);
		v1.setTypeface(android.graphics.Typeface.MONOSPACE);
		v1.setTextIsSelectable(true);
		v1.setPadding(0, dp(2), 0, dp(10));
		box.addView(v1);
		TextView l2 = mkText("Full cookie", 13, MUTED);
		box.addView(l2);
		TextView v2 = mkText(cookie, 12, TEXT);
		v2.setTypeface(android.graphics.Typeface.MONOSPACE);
		v2.setTextIsSelectable(true);
		v2.setPadding(0, dp(2), 0, dp(8));
		box.addView(v2);
		ScrollView sv = new ScrollView(this);
		sv.addView(box);
		new DragBuilder(this).setTitle(afterLogin ? "Signed in - your login cookie" : "Login cookie").setView(sv)
				.setPositiveButton("Copy cookie", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						copyToClipboard("TeraBox cookie", cookie);
						toast("Cookie copied");
					}
				}).setNeutralButton("Copy ndus", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						copyToClipboard("TeraBox ndus", ndus);
						toast("ndus copied");
					}
				}).setNegativeButton("Close", null).show();
	}

	void copyToClipboard(String label, String text) {
		try {
			ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			cm.setPrimaryClip(ClipData.newPlainText(label, text));
		} catch (Exception e) {
			toast("Can't copy");
		}
	}

	void signOut(String msg) {
		prefs.edit().remove("cookie").remove("whost").commit();
		client = null;
		warmedUp = false;
		loginClient = null;
		cache.clear();
		entries = new ArrayList<TeraEntry>();
		sel.clear();
		searchTerm = null;
		quotaText = "";
		curDir = "/";
		showLogin(msg);
	}

	// ------------------------------------------------------------------ login screen

	void showLogin(String msg) {
		screen = SCREEN_LOGIN;
		ScrollView sv = new ScrollView(this);
		sv.setBackgroundColor(BG);
		sv.setFillViewport(true);
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(22), dp(40), dp(22), dp(24));
		sv.addView(box, new ViewGroup.LayoutParams(-1, -2));

		TextView logo = mkText("\u2601  TeraFiles", 30, TEXT);
		logo.setTypeface(Typeface.DEFAULT_BOLD);
		box.addView(logo);
		TextView sub = mkText("Sign in to your TeraBox account", 15, MUTED);
		sub.setPadding(0, dp(4), 0, dp(24));
		box.addView(sub);

		loginEmail = new EditText(this);
		styleEdit(loginEmail, "Email");
		loginEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
		loginEmail.setText(prefs.getString("email", ""));
		box.addView(loginEmail, new LinearLayout.LayoutParams(-1, -2));

		loginPass = new EditText(this);
		styleEdit(loginPass, "Password");
		loginPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
		pl.topMargin = dp(8);
		box.addView(loginPass, pl);

		CheckBox show = new CheckBox(this);
		show.setText("Show password");
		show.setTextColor(MUTED);
		show.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			public void onCheckedChanged(CompoundButton cb, boolean on) {
				int sel = loginPass.getSelectionEnd();
				loginPass.setInputType(InputType.TYPE_CLASS_TEXT
						| (on ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
				if (sel >= 0)
					loginPass.setSelection(sel);
			}
		});
		box.addView(show);

		loginStatus = mkText("", 14, MUTED);
		loginStatus.setPadding(0, dp(8), 0, dp(8));
		box.addView(loginStatus);
		if (msg != null)
			setLoginStatus(msg, false);

		// captcha panel (hidden until TeraBox asks for it)
		captchaPanel = new LinearLayout(this);
		captchaPanel.setOrientation(LinearLayout.VERTICAL);
		captchaPanel.setVisibility(View.GONE);
		TextView note = mkText("Drag the piece into the gap and let go.", 14, TEXT);
		note.setPadding(0, 0, 0, dp(6));
		captchaPanel.addView(note);
		captchaView = new SlideCaptchaView(this);
		captchaView.setListener(new SlideCaptchaView.Listener() {
			public void onDropped(int dx, int dy) {
				submitSlide(dx, dy);
			}
		});
		captchaPanel.addView(captchaView, new LinearLayout.LayoutParams(-1, -2));
		Button another = new Button(this);
		another.setText("Load another challenge");
		another.setAllCaps(false);
		another.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				loadChallenge();
			}
		});
		captchaPanel.addView(another);
		box.addView(captchaPanel, new LinearLayout.LayoutParams(-1, -2));

		loginBtn = mkBtn("Sign in");
		loginBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				doLogin();
			}
		});
		LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(52));
		bl.topMargin = dp(8);
		box.addView(loginBtn, bl);

		TextView tokenLink = mkText("Sign in with an ndus token instead", 14, ACCENT);
		tokenLink.setGravity(Gravity.CENTER);
		tokenLink.setPadding(0, dp(22), 0, dp(10));
		tokenLink.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				askToken();
			}
		});
		box.addView(tokenLink, new LinearLayout.LayoutParams(-1, -2));

		setContentView(sv);
	}

	void setLoginStatus(String s, boolean error) {
		if (loginStatus == null)
			return;
		loginStatus.setTextColor(error ? ERR : MUTED);
		loginStatus.setText(s);
	}

	void setLoginBusy(boolean b) {
		loginBusy = b;
		if (loginBtn != null) {
			loginBtn.setEnabled(!b);
			loginBtn.setAlpha(b ? 0.5f : 1f);
		}
	}

	void doLogin() {
		if (loginBusy)
			return;
		final String email = loginEmail.getText().toString().trim();
		final String pass = loginPass.getText().toString();
		if (email.length() == 0 || pass.length() == 0) {
			setLoginStatus("Enter your email and password.", true);
			return;
		}
		final String token = pendingToken;
		pendingToken = "";
		setLoginBusy(true);
		setLoginStatus(token.length() > 0 ? "Verification accepted. Signing in..." : "Signing in...", false);
		exec.execute(new Runnable() {
			public void run() {
				try {
					if (loginClient == null || !email.equalsIgnoreCase(loginEmailUsed)) {
						loginClient = new TeraClient(null, null);
						loginClient.useBrowserId(prefs.getString("browserid", ""));
						loginEmailUsed = email;
					}
					final TeraClient.LoginResult r = loginClient.login(email, pass, token);
					runOnUiThread(new Runnable() {
						public void run() {
							onLoginResult(r, email);
						}
					});
				} catch (final Exception e) {
					final String step = loginClient == null ? "" : loginClient.lastStep;
					runOnUiThread(new Runnable() {
						public void run() {
							setLoginBusy(false);
							setLoginStatus("Login error: " + e.getMessage() + (step.length() > 0 ? "  [" + step + "]" : ""),
									true);
						}
					});
				}
			}
		});
	}

	void onLoginResult(TeraClient.LoginResult r, String email) {
		setLoginBusy(false);
		if (r.ok) {
			client = loginClient;
			loginClient = null;
			saveSession(email);
			showFiles();
			loadDir("/", true);
			showCookieDialog(true);
			return;
		}
		if (r.captchaRequired) {
			if (r.requestId.length() == 0) {
				captchaPanel.setVisibility(View.GONE);
				setLoginStatus("TeraBox asked for a verification but gave no challenge id. Try again later, or sign in with an ndus token.",
						true);
				return;
			}
			captchaRid = r.requestId;
			captchaPanel.setVisibility(View.VISIBLE);
			setLoginStatus("Please complete the security verification below.", false);
			loadChallenge();
			return;
		}
		captchaPanel.setVisibility(View.GONE);
		setLoginStatus((r.msg.length() > 0 ? r.msg : "Login failed.") + " (code " + r.code + ")", true);
	}

	// ---- slide captcha

	void loadChallenge() {
		if (loginClient == null || captchaRid.length() == 0)
			return;
		captchaChallengeId = "";
		captchaView.clear();
		captchaView.setInteractive(false);
		setLoginStatus("Loading challenge...", false);
		final String rid = captchaRid;
		final TeraClient tc = loginClient;
		exec.execute(new Runnable() {
			public void run() {
				try {
					JSONObject r = tc.getSlide(rid);
					int code = r.has("errno") ? r.optInt("errno", 0) : r.optInt("code", 0);
					if (code != 0)
						throw new Exception("TeraBox did not provide a challenge (code " + code + ").");
					JSONObject d = TeraClient.pick(r, "master_image");
					if (d == null)
						throw new Exception("Unexpected challenge response from TeraBox.");
					JSONObject data = r.optJSONObject("data");
					String cid = d.optString("challenge_id", r.optString("challenge_id", data == null ? "" : data.optString("challenge_id", "")));
					if (cid.length() == 0 || cid.length() > 512)
						throw new Exception("Challenge has no id.");
					final int mw = d.optInt("master_width"), mh = d.optInt("master_height");
					final int tw = d.optInt("tile_width"), th = d.optInt("tile_height");
					final int tx = d.optInt("tile_x"), ty = d.optInt("tile_y");
					if (mw < 1 || mh < 1 || tw < 1 || th < 1 || tx < 0 || ty < 0 || tx + tw > mw || ty + th > mh)
						throw new Exception("Unsupported challenge size.");
					final Bitmap master = fetchBitmap(d.optString("master_image", ""));
					final Bitmap tile = fetchBitmap(d.optString("tile_image", ""));
					final String fcid = cid;
					runOnUiThread(new Runnable() {
						public void run() {
							captchaChallengeId = fcid;
							captchaView.setChallenge(master, tile, mw, mh, tw, th, tx, ty);
							captchaView.setInteractive(true);
							setLoginStatus("Drag the piece into place, then release.", false);
						}
					});
				} catch (final Exception e) {
					runOnUiThread(new Runnable() {
						public void run() {
							setLoginStatus("Challenge error: " + e.getMessage() + "  Tap \"Load another challenge\".", true);
						}
					});
				}
			}
		});
	}

	Bitmap fetchBitmap(String src) throws Exception {
		if (src.startsWith("data:image")) {
			int comma = src.indexOf(',');
			byte[] raw = Base64.decode(src.substring(comma + 1), Base64.DEFAULT);
			Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
			if (b == null)
				throw new Exception("Challenge image could not be read.");
			return b;
		}
		URL u = new URL(src);
		if (!"https".equals(u.getProtocol()) || !u.getHost().matches("(?i)^(?:.+\\.)?(?:terabox\\.com|teraboxcdn\\.com)$"))
			throw new Exception("Unsupported challenge image address.");
		HttpURLConnection c = (HttpURLConnection) u.openConnection();
		try {
			c.setConnectTimeout(10000);
			c.setReadTimeout(20000);
			c.setRequestProperty("User-Agent", TeraClient.UA);
			InputStream in = c.getInputStream();
			Bitmap b = BitmapFactory.decodeStream(in);
			in.close();
			if (b == null)
				throw new Exception("Challenge image could not be read.");
			return b;
		} finally {
			c.disconnect();
		}
	}

	void submitSlide(final int dx, final int dy) {
		if (loginClient == null || captchaChallengeId.length() == 0)
			return;
		captchaView.setInteractive(false);
		setLoginStatus("Checking your placement...", false);
		final String rid = captchaRid;
		final String cid = captchaChallengeId;
		captchaChallengeId = ""; // a challenge can be answered only once
		final TeraClient tc = loginClient;
		exec.execute(new Runnable() {
			public void run() {
				try {
					JSONObject r = tc.checkSlide(rid, cid, dx, dy);
					int code = r.has("errno") ? r.optInt("errno", 0) : r.optInt("code", 0);
					JSONObject holder = TeraClient.pick(r, "capt_token");
					final String token = holder == null ? "" : holder.optString("capt_token", "");
					if (code == 0 && token.length() > 0 && token.length() <= 4096) {
						runOnUiThread(new Runnable() {
							public void run() {
								pendingToken = token;
								captchaPanel.setVisibility(View.GONE);
								doLogin();
							}
						});
					} else {
						runOnUiThread(new Runnable() {
							public void run() {
								setLoginStatus("Position not accepted. Loading a new challenge...", true);
								loadChallenge();
							}
						});
					}
				} catch (final Exception e) {
					runOnUiThread(new Runnable() {
						public void run() {
							setLoginStatus("Verification error: " + e.getMessage(), true);
							loadChallenge();
						}
					});
				}
			}
		});
	}

	// ---- ndus token login

	void askToken() {
		final EditText e = new EditText(this);
		e.setHint("ndus value, or the whole cookie");
		e.setSingleLine(false);
		e.setMinLines(2);
		AlertDialog.Builder b = new DragBuilder(this);
		b.setTitle("Sign in with a token");
		b.setMessage("Paste the \"ndus\" cookie of a TeraBox session you are already signed in to in a browser.");
		b.setView(e);
		b.setPositiveButton("Sign in", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface d, int w) {
				final String t = e.getText().toString().trim();
				if (t.length() == 0)
					return;
				setLoginBusy(true);
				setLoginStatus("Checking token...", false);
				exec.execute(new Runnable() {
					public void run() {
						try {
							final TeraClient c = new TeraClient(t, null);
							JSONObject j = c.checkLogin(0);
							final boolean ok = j.optInt("errno", -1) == 0;
							runOnUiThread(new Runnable() {
								public void run() {
									setLoginBusy(false);
									if (ok) {
										client = c;
										saveSession(null);
										showFiles();
										loadDir("/", true);
									} else {
										setLoginStatus("That token was not accepted by TeraBox.", true);
									}
								}
							});
						} catch (final Exception ex) {
							runOnUiThread(new Runnable() {
								public void run() {
									setLoginBusy(false);
									setLoginStatus("Token check failed: " + ex.getMessage(), true);
								}
							});
						}
					}
				});
			}
		});
		b.setNegativeButton("Cancel", null);
		b.show();
	}

	// ------------------------------------------------------------------ shared UI pieces

	interface RowHost {
		void onMenu(TeraEntry e, View anchor);

		void onSelectionChanged();
	}

	static class VH {
		CheckBox cb;
		ImageView icon;
		TextView name, sub, menu;
	}

	/** one list of files / folders with a checkbox on every row */
	class RowAdapter extends BaseAdapter {
		ArrayList<TeraEntry> data = new ArrayList<TeraEntry>();
		HashSet<String> selSet;
		boolean fsKey, showPath, menuBtn;
		RowHost host;

		RowAdapter(HashSet<String> s, boolean fsKey, boolean menuBtn, RowHost h) {
			selSet = s;
			this.fsKey = fsKey;
			this.menuBtn = menuBtn;
			host = h;
		}

		void setData(ArrayList<TeraEntry> d) {
			data = d;
			notifyDataSetChanged();
		}

		String key(TeraEntry e) {
			return fsKey && e.fsId.length() > 0 ? e.fsId : e.path;
		}

		public int getCount() {
			return data.size();
		}

		public Object getItem(int i) {
			return data.get(i);
		}

		public long getItemId(int i) {
			return i;
		}

		public View getView(int pos, View cv, ViewGroup parent) {
			VH h;
			LinearLayout row;
			if (cv == null) {
				row = new LinearLayout(MainActivity.this);
				row.setOrientation(LinearLayout.HORIZONTAL);
				row.setGravity(Gravity.CENTER_VERTICAL);
				row.setPadding(dp(6), dp(1), dp(0), dp(1));
				h = new VH();
				h.cb = new CheckBox(MainActivity.this);
				h.cb.setFocusable(false);
				h.cb.setPadding(dp(6), dp(8), dp(6), dp(8));
				h.cb.setOnClickListener(new View.OnClickListener() {
					public void onClick(View v) {
						TeraEntry e = (TeraEntry) v.getTag();
						if (e == null)
							return;
						if (((CheckBox) v).isChecked())
							selSet.add(key(e));
						else
							selSet.remove(key(e));
						host.onSelectionChanged();
					}
				});
				row.addView(h.cb, new LinearLayout.LayoutParams(-2, -2));
				h.icon = new ImageView(MainActivity.this);
				LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
				ilp.leftMargin = dp(6);
				ilp.rightMargin = dp(12);
				row.addView(h.icon, ilp);
				LinearLayout col = new LinearLayout(MainActivity.this);
				col.setOrientation(LinearLayout.VERTICAL);
				h.name = mkText("", 16, TEXT);
				h.name.setMaxLines(2);
				h.name.setEllipsize(TextUtils.TruncateAt.END);
				h.sub = mkText("", 12, MUTED);
				h.sub.setSingleLine(true);
				h.sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
				col.addView(h.name);
				col.addView(h.sub);
				row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
				h.menu = mkText("\u22EE", 22, TEXT);
				h.menu.setPadding(dp(14), dp(8), dp(14), dp(8));
				h.menu.setVisibility(menuBtn ? View.VISIBLE : View.GONE);
				h.menu.setOnClickListener(new View.OnClickListener() {
					public void onClick(View v) {
						TeraEntry e = (TeraEntry) v.getTag();
						if (e != null)
							host.onMenu(e, v);
					}
				});
				row.addView(h.menu, new LinearLayout.LayoutParams(-2, -2));
				row.setTag(h);
			} else {
				row = (LinearLayout) cv;
				h = (VH) row.getTag();
			}
			TeraEntry e = data.get(pos);
			h.cb.setTag(e);
			h.menu.setTag(e);
			h.cb.setChecked(selSet.contains(key(e)));
			h.icon.setImageDrawable(Icons.get(Icons.forEntry(e), e.isDir ? ACCENT : TEXT));
			h.name.setText(e.name);
			h.name.setTypeface(Typeface.DEFAULT);
			String d = describe(e);
			if (showPath) {
				String par = parentOf(e.path);
				d = (d.length() > 0 ? d + "   " : "") + par;
			}
			h.sub.setText(d);
			return row;
		}
	}

	TextView barButton(String label, View.OnClickListener l) {
		TextView t = mkText(label, 15, ACCENT);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setPadding(dp(14), dp(12), dp(14), dp(12));
		t.setOnClickListener(l);
		return t;
	}

	/** top bar: [left glyph] title [refresh] [more] */
	LinearLayout makeTopBar(String leftGlyph, TextView[] titleOut, View.OnClickListener left, View.OnClickListener refresh,
			View.OnClickListener more, TextView[] moreOut) {
		LinearLayout bar = new LinearLayout(this);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setBackgroundColor(PANEL);
		bar.setPadding(0, dp(BAR_TOP_DP), 0, 0); // same height as the file list header (storage line + button row)
		bar.setMinimumHeight(dp(BAR_TOP_DP + BAR_ROW_DP));
		TextView lb = barGlyph(leftGlyph, 16, 12);
		lb.setOnClickListener(left);
		bar.addView(lb);
		TextView title = mkText("", 17, TEXT);
		title.setSingleLine(true);
		title.setEllipsize(TextUtils.TruncateAt.START);
		bar.addView(title, new LinearLayout.LayoutParams(0, dp(BAR_ROW_DP), 1));
		title.setGravity(Gravity.CENTER_VERTICAL);
		titleOut[0] = title;
		if (refresh != null) {
			TextView rf = barGlyph("\u21BB", 12, 12);
			rf.setOnClickListener(refresh);
			bar.addView(rf);
		}
		if (more != null) {
			TextView mb = barGlyph("\u22EE", 12, 16);
			mb.setOnClickListener(more);
			bar.addView(mb);
			if (moreOut != null)
				moreOut[0] = mb;
		}
		return bar;
	}

	void showError(String m) {
		new DragBuilder(this).setTitle("Error").setMessage(m).setPositiveButton("OK", null).show();
	}

	interface Job {
		/** returns null on success, or an error message ("SESSION" = the session expired) */
		String run(TeraClient c) throws Exception;
	}

	static String chk(JSONObject j, String what) {
		int e = TeraClient.errnoOf(j);
		if (e == 0)
			return null;
		if (e == -6)
			return "SESSION";
		String d = TeraClient.fmDetails(j);
		return what + " failed (errno " + e + ")." + (d.length() > 0 ? "\n" + d : "");
	}

	/** run a short server job with a spinner; afterwards the lists are refreshed */
	void runJob(String title, final Job job, final Runnable after) {
		final TeraClient c = client;
		if (c == null)
			return;
		final ProgressDialog pd = new ProgressDialog(this);
		pd.setMessage(title);
		pd.setIndeterminate(true);
		pd.setCancelable(false);
		pd.show();
		DragBuilder.makeDraggable(pd);
		execDl.execute(new Runnable() {
			public void run() {
				String msg;
				try {
					msg = job.run(c);
				} catch (Exception e) {
					msg = e.getMessage() == null ? e.toString() : e.getMessage();
				}
				final String m = msg;
				runOnUiThread(new Runnable() {
					public void run() {
						pd.dismiss();
						if (m == null) {
							cache.clear();
							if (after != null)
								after.run();
						} else if (m.equals("SESSION")) {
							signOut("Your session expired. Please sign in again.");
						} else if (m.equals("CONFLICT") && conflictAction != null) {
							conflictAction.run();
						} else {
							showError(m);
						}
					}
				});
			}
		});
	}

	Runnable conflictAction; // set by an operation that can ask the user how to resolve name conflicts

	Runnable afterFilesChange = new Runnable() {
		public void run() {
			sel.clear();
			reloadCurrent();
			refreshQuota();
		}
	};

	/**
	 * Progress of a long job. JobStatus(title) shows a modal dialog with Cancel (used while a file is opened for editing);
	 * JobStatus(title, upload) runs in the background: an ongoing notification with a progress bar and a Cancel action.
	 */
	class JobStatus implements TeraWork.Status, TeraClient.Progress {
		ProgressDialog pd; // null for background jobs
		int jobId = -1;
		boolean[] cancel = new boolean[1];
		String label = "";
		String title = "";
		boolean up;
		long lastDone, lastTotal;
		boolean insideApp;
		volatile boolean finished;

		void showInsideApp() {
			if (!insideApp || finished || isFinishing()) return;
			if (pd == null) {
				pd = new ProgressDialog(MainActivity.this);
				pd.setTitle(title);
				pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
				pd.setMax(100);
				pd.setCancelable(false);
				pd.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int which) { cancel[0] = true; }
				});
			}
			if (!pd.isShowing()) {
				pd.show();
				DragBuilder.makeDraggable(pd);
			}
			pd.setMessage(label + (lastTotal > 0 ? "\n" + human(lastDone) + " / " + human(lastTotal) : ""));
			pd.setIndeterminate(lastTotal <= 0);
			if (lastTotal > 0) pd.setProgress((int) Math.min(100, lastDone * 100 / lastTotal));
		}

		void hideInsideApp() {
			if (insideApp && pd != null && pd.isShowing()) pd.dismiss();
		}

		void updateInsideApp() {
			if (!insideApp) return;
			runOnUiThread(new Runnable() { public void run() {
				if (!finished && pd != null && pd.isShowing()) {
					pd.setMessage(label + (lastDone > 0 || lastTotal > 0 ? "\n" + human(lastDone) + (lastTotal > 0 ? " / " + human(lastTotal) : "") : ""));
					pd.setIndeterminate(lastTotal <= 0);
					if (lastTotal > 0) pd.setProgress((int) Math.min(100, lastDone * 100 / lastTotal));
				}
			} });
		}

		JobStatus(String title) {
			this.title = title;
			pd = new ProgressDialog(MainActivity.this);
			pd.setTitle(title);
			pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			pd.setMax(100);
			pd.setIndeterminate(true);
			pd.setCancelable(false);
			pd.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface d, int w) {
					cancel[0] = true;
				}
			});
			pd.show();
			DragBuilder.makeDraggable(pd);
		}

		JobStatus(String title, boolean upload) {
			this.title = title;
			this.up = upload;
			jobId = TransferNotifier.begin(MainActivity.this, title, upload, cancel);
		}

		String line(long done, long total) {
			return label + (done > 0 || total > 0 ? "\n" + human(done) + (total > 0 ? " / " + human(total) : "") : "");
		}

		public void message(final String m) {
			label = m;
			lastDone = 0;
			lastTotal = 0;
			if (jobId >= 0) {
				TransferNotifier.update(jobId, m, 0, 0);
				updateInsideApp();
				return;
			}
			runOnUiThread(new Runnable() {
				public void run() {
					pd.setMessage(m);
				}
			});
		}

		public void note(final String m) {
			if (jobId >= 0) {
				TransferNotifier.update(jobId, label + "\n" + m, lastDone, lastTotal);
				updateInsideApp();
				return;
			}
			runOnUiThread(new Runnable() {
				public void run() {
					pd.setMessage(label + "\n" + m);
				}
			});
		}

		public void progress(final long done, final long total) {
			lastDone = done;
			lastTotal = total;
			if (jobId >= 0) {
				TransferNotifier.update(jobId, line(done, total), done, total);
				updateInsideApp();
				return;
			}
			runOnUiThread(new Runnable() {
				public void run() {
					if (total > 0) {
						pd.setIndeterminate(false);
						pd.setProgress((int) Math.min(100, done * 100 / total));
					}
					pd.setMessage(label + "\n" + human(done) + (total > 0 ? " / " + human(total) : ""));
				}
			});
		}

		public void onProgress(long done, long total) {
			progress(done, total);
		}

		public boolean cancelled() {
			return cancel[0];
		}

		void close() {
			if (jobId >= 0) {
				TransferNotifier.end(jobId);
				finished = true;
				runOnUiThread(new Runnable() { public void run() {
					hideInsideApp();
					if (activeFolderUpload == JobStatus.this) activeFolderUpload = null;
				} });
				return;
			}
			runOnUiThread(new Runnable() {
				public void run() {
					pd.dismiss();
				}
			});
		}

		/** end the job and, when the app is in the background, post a "finished" / "failed" notification */
		void finish(String err, String okText) {
			close();
			if (err == null)
				TransferNotifier.result(MainActivity.this, title + " finished", okText, true);
			else if (!"Cancelled".equals(err))
				TransferNotifier.result(MainActivity.this, title + " interrupted", err + "\nOpen TeraFiles to resume.", false);
		}
	}

	/**
	 * A background job (notification) that also shows its progress inside the app, like copy / move do.
	 * Only one such window is shown at a time; further jobs run with the notification only.
	 */
	JobStatus inAppJob(String title, boolean upload) {
		JobStatus st = new JobStatus(title, upload);
		if (activeFolderUpload == null || activeFolderUpload.finished) {
			st.insideApp = true;
			activeFolderUpload = st;
			st.showInsideApp();
		}
		return st;
	}

	/** an interrupted transfer: Resume runs the same operation again, which continues from what was already transferred */
	void showErrorResume(String m, final Runnable again) {
		new DragBuilder(this).setTitle("Transfer interrupted")
				.setMessage(m + "\n\nWhat was already transferred is kept, Resume continues from there.")
				.setPositiveButton("Resume", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						again.run();
					}
				}).setNegativeButton("Close", null).show();
	}

	// ------------------------------------------------------------------ files screen

	void showFiles() {
		screen = SCREEN_FILES;
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(BG);

		TextView[] t = new TextView[1];
		TextView[] mo = new TextView[1];
		// File browser header: home, path, and parent navigation beside the path.
		LinearLayout fileBar = new LinearLayout(this);
		fileBar.setOrientation(LinearLayout.HORIZONTAL);
		fileBar.setGravity(Gravity.CENTER_VERTICAL);
		fileBar.setBackgroundColor(PANEL);
		View home = iconButton("home", ACCENT, 16, 8);
		home.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) { loadDir("/", false); }
		});
		fileBar.addView(home);
		// Scrollable, individually clickable path segments.
		HorizontalScrollView pathScroll = new HorizontalScrollView(this);
		pathScroll.setHorizontalScrollBarEnabled(false);
		pathScroll.setFillViewport(false);
		LinearLayout crumbs = new LinearLayout(this);
		crumbs.setOrientation(LinearLayout.HORIZONTAL);
		crumbs.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout pathRow = new LinearLayout(this);
		pathRow.setOrientation(LinearLayout.HORIZONTAL);
		pathRow.setGravity(Gravity.CENTER_VERTICAL);
		pathRow.addView(crumbs);
		pathScroll.addView(pathRow, new android.widget.FrameLayout.LayoutParams(-2, -1));
		fileBar.addView(pathScroll, new LinearLayout.LayoutParams(0, -2, 1));
		fileBreadcrumbs = crumbs;
		filePathScroll = pathScroll;
		TextView pathTitle = mkText("", 17, TEXT);
		pathTitle.setSingleLine(true);
		fileBar.addView(pathTitle, new LinearLayout.LayoutParams(0, -2, 1));
		titleView = pathTitle;
		View parentButton = iconButton("updir", ACCENT, 10, 10);
		parentButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) { goUp(); }
		});
		pathRow.addView(parentButton); // right beside the path
		fileParentButton = parentButton;
		TextView refreshButton = barGlyph("\u21BB", 12, 12);
		refreshButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) { reloadCurrent(); }
		});
		fileBar.addView(refreshButton);
		TextView moreButton = barGlyph("\u22EE", 12, 16);
		moreButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) { showTopMenu(v); }
		});
		fileBar.addView(moreButton);
		// storage used / total, small line at the top-left of the header
		LinearLayout header = new LinearLayout(this);
		header.setOrientation(LinearLayout.VERTICAL);
		header.setBackgroundColor(PANEL);
		quotaTop = mkText(quotaText, 11, MUTED);
		quotaTop.setSingleLine(true);
		quotaTop.setPadding(dp(16), 0, dp(16), 0);
		quotaTop.setGravity(Gravity.CENTER_VERTICAL);
		quotaTop.setVisibility(quotaText.length() > 0 ? View.VISIBLE : View.INVISIBLE); // keeps the header height
		header.addView(quotaTop, new LinearLayout.LayoutParams(-1, dp(BAR_TOP_DP)));
		header.addView(fileBar, new LinearLayout.LayoutParams(-1, dp(BAR_ROW_DP)));
		root.addView(header, new LinearLayout.LayoutParams(-1, -2));

		loadBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		loadBar.setIndeterminate(true);
		loadBar.setVisibility(View.INVISIBLE);
		root.addView(loadBar, new LinearLayout.LayoutParams(-1, dp(3)));

		infoView = mkText("", 12, MUTED);
		infoView.setPadding(dp(16), dp(6), dp(16), dp(2));
		root.addView(infoView, new LinearLayout.LayoutParams(-1, -2));

		selAll = new CheckBox(this);
		selAll.setText("Select all");
		selAll.setTextColor(TEXT);
		selAll.setPadding(dp(14), dp(4), dp(8), dp(4)); // box lines up with the row checkboxes below
		selAll.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				sel.clear();
				if (selAll.isChecked())
					for (int i = 0; i < entries.size(); i++)
						sel.add(entries.get(i).path);
				onSelChanged();
			}
		});
		LinearLayout sortRow = new LinearLayout(this);
		sortRow.setOrientation(LinearLayout.HORIZONTAL);
		sortRow.setGravity(Gravity.CENTER_VERTICAL);
		sortRow.addView(selAll, new LinearLayout.LayoutParams(0, -2, 1));
		sortLabel = mkText(sortName(), 14, TEXT);
		sortLabel.setGravity(Gravity.CENTER_VERTICAL);
		sortLabel.setPadding(dp(12), dp(10), dp(4), dp(10));
		sortLabel.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) { showSortMenu(v); }
		});
		sortRow.addView(sortLabel);
		TextView dropdown = mkText("\u2304", 20, TEXT);
		dropdown.setPadding(dp(4), dp(8), dp(12), dp(8));
		dropdown.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) { showSortMenu(v); }
		});
		sortRow.addView(dropdown);
		sortArrow = mkText("", 24, TEXT);
		sortArrow.setPadding(dp(8), dp(8), dp(16), dp(8));
		sortArrow.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				prefs.edit().putBoolean("sortDesc", !prefs.getBoolean("sortDesc", false)).apply();
				applySortChange();
			}
		});
		sortRow.addView(sortArrow);
		updateSortHeader();
		root.addView(sortRow, new LinearLayout.LayoutParams(-1, -2));

		RowHost host = new RowHost() {
			public void onMenu(TeraEntry e, View anchor) {
				showRowMenu(e, anchor);
			}

			public void onSelectionChanged() {
				onSelChanged();
			}
		};
		adapter = new RowAdapter(sel, false, true, host);
		adapter.setData(entries);
		ListView lv = new ListView(this);
		lv.setDividerHeight(1);
		lv.setDivider(new android.graphics.drawable.ColorDrawable(DIVIDER));
		lv.setAdapter(adapter);
		lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
				TeraEntry e = entries.get(pos);
				if (e.isDir) {
					searchTerm = null;
					loadDir(e.path, false);
				} // File taps intentionally do not open a details dialog.
			}
		});
		lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
				TeraEntry e = entries.get(pos);
				sel.add(e.path);
				onSelChanged();
				return true;
			}
		});
		root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));

		emptyView = mkText("This folder is empty.", 15, MUTED);
		emptyView.setGravity(Gravity.CENTER);
		emptyView.setPadding(0, dp(30), 0, dp(30));
		emptyView.setVisibility(View.GONE);
		root.addView(emptyView, new LinearLayout.LayoutParams(-1, -2));


		setContentView(root);
		updateTitle();
		onSelChanged();
	}

	ArrayList<TeraEntry> selectedEntries() {
		ArrayList<TeraEntry> r = new ArrayList<TeraEntry>();
		for (int i = 0; i < entries.size(); i++)
			if (sel.contains(entries.get(i).path))
				r.add(entries.get(i));
		return r;
	}

	void onSelChanged() {
		if (selAll == null || screen != SCREEN_FILES)
			return;
		int n = selectedEntries().size();
		selAll.setChecked(!entries.isEmpty() && n == entries.size());
		updateTitle();
		selAll.setVisibility(entries.isEmpty() ? View.INVISIBLE : View.VISIBLE); // keeps the sort controls on the right
		adapter.notifyDataSetChanged();
	}

	View fileParentButton;
	android.widget.HorizontalScrollView filePathScroll;

	/** a small tappable drawn icon (see Icons) */
	// Every title bar has the same height: a small top gap (the storage line on the file list) + one content row.
	static final int BAR_TOP_DP = 14, BAR_ROW_DP = 32, BAR_ICON_DP = 18;

	/** home / parent icon: drawn about as large as the 17sp path text, in a row exactly as high as the title bar row */
	android.widget.ImageView iconButton(String icon, int color, int left, int right) {
		return iconButton(icon, color, left, right, BAR_ICON_DP, BAR_ROW_DP);
	}

	android.widget.ImageView iconButton(String icon, int color, int left, int right, int sizeDp, int heightDp) {
		android.widget.ImageView v = new android.widget.ImageView(this);
		v.setImageDrawable(Icons.get(icon, color));
		v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
		int vp = Math.max(0, (heightDp - sizeDp) / 2);
		v.setPadding(dp(left), dp(vp), dp(right), dp(vp));
		v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp + left + right), dp(heightDp)));
		return v;
	}

	/** a text glyph button (back / refresh / more) filling the title bar row */
	TextView barGlyph(String glyph, int padLeft, int padRight) {
		TextView t = mkText(glyph, 22, TEXT);
		t.setGravity(Gravity.CENTER);
		t.setPadding(dp(padLeft), 0, dp(padRight), 0);
		t.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(BAR_ROW_DP)));
		return t;
	}
	LinearLayout fileBreadcrumbs;

	void updateTitle() {
		if (screen != SCREEN_FILES)
			return;
		boolean showCrumbs = searchTerm == null; // the path stays visible while files are selected
		if (titleView != null) {
			titleView.setText(searchTerm != null ? "\uD83D\uDD0D  " + searchTerm : "");
			titleView.setVisibility(showCrumbs ? View.GONE : View.VISIBLE);
		}
		if (filePathScroll != null)
			filePathScroll.setVisibility(showCrumbs ? View.VISIBLE : View.GONE);
		if (fileBreadcrumbs != null) {
			fileBreadcrumbs.setVisibility(showCrumbs ? View.VISIBLE : View.GONE);
			if (showCrumbs) updateBreadcrumbs();
		}
		if (fileParentButton != null)
			fileParentButton.setVisibility(curDir.equals("/") || searchTerm != null ? View.GONE : View.VISIBLE);
		if (infoView != null) {
			int dirs = 0, files = 0;
			for (int i = 0; i < entries.size(); i++) {
				if (entries.get(i).isDir)
					dirs++;
				else
					files++;
			}
			infoView.setText(dirs + " folders, " + files + " files" + (sel.isEmpty() ? "" : "  \u00B7  " + selectedEntries().size() + " selected"));
		}
		if (quotaTop != null) {
			quotaTop.setText(quotaText);
			quotaTop.setVisibility(quotaText.length() > 0 ? View.VISIBLE : View.INVISIBLE);
		}
		if (emptyView != null) {
			emptyView.setText(searchTerm != null ? "Nothing found." : "This folder is empty.");
			emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
		}
		if (adapter != null)
			adapter.showPath = searchTerm != null;
	}

	void updateBreadcrumbs() {
		fileBreadcrumbs.removeAllViews();
		if (curDir == null || curDir.equals("/")) {
			TextView slash = mkText("/", 17, TEXT);
			fileBreadcrumbs.addView(slash);
			return;
		}
		String[] parts = curDir.split("/");
		String path = "";
		for (int i = 0; i < parts.length; i++) {
			if (parts[i].length() == 0) continue;
			path += "/" + parts[i];
			final String destination = path;
			TextView segment = mkText("/" + parts[i], 17, TEXT);
			segment.setSingleLine(true);
			segment.setPadding(dp(3), dp(4), dp(3), dp(4));
			segment.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) { if (!curDir.equals(destination)) loadDir(destination, false); }
			});
			fileBreadcrumbs.addView(segment);
		}
		if (filePathScroll != null)
			filePathScroll.post(new Runnable() {
				public void run() { filePathScroll.fullScroll(View.FOCUS_RIGHT); }
			});
	}

	static String parentOf(String p) {
		if (p.equals("/") || p.length() == 0)
			return "/";
		int i = p.lastIndexOf('/');
		return i <= 0 ? "/" : p.substring(0, i);
	}

	void goUp() {
		if (searchTerm != null) {
			exitSearch();
			return;
		}
		if (curDir.equals("/"))
			toast("Already at the top");
		else
			loadDir(parentOf(curDir), false);
	}

	public void onBackPressed() {
		if (screen == SCREEN_EDIT && editor != null) {
			editor.onBack();
			return;
		}
		if (screen == SCREEN_FILES) {
			if (searchTerm != null) {
				exitSearch();
				return;
			}
			if (!sel.isEmpty()) {
				sel.clear();
				onSelChanged();
				return;
			}
			if (!curDir.equals("/")) {
				loadDir(parentOf(curDir), false);
				return;
			}
		} else if (screen == SCREEN_SEARCH) {
			++navSeq;
			showFiles();
			return;
		} else if (screen == SCREEN_TRASH) {
			trashBack();
			return;
		} else if (screen == SCREEN_SHARES || screen == SCREEN_SETTINGS) {
			showFiles();
			return;
		}
		super.onBackPressed();
	}

	String sortName() {
		int by = prefs.getInt("sortBy", 0);
		return by == 2 ? "Size" : by == 1 ? "Modified" : "Name";
	}

	void updateSortHeader() {
		if (sortLabel != null) sortLabel.setText(sortName());
		if (sortArrow != null) sortArrow.setText(prefs.getBoolean("sortDesc", false) ? "\u2193" : "\u2191");
	}

	void applySortChange() {
		sortList(entries);
		for (ArrayList<TeraEntry> list : cache.values()) sortList(list);
		if (adapter != null) adapter.setData(entries);
		updateSortHeader();
	}

	void showSortMenu(View anchor) {
		PopupMenu popup = newPopup(anchor);
		String[] names = { "Name", "Size", "Modified" };
		int[] values = { 0, 2, 1 };
		int selected = prefs.getInt("sortBy", 0);
		for (int i = 0; i < names.length; i++) {
			android.view.MenuItem item = popup.getMenu().add(0, values[i], i, names[i]);
			item.setCheckable(true);
			item.setChecked(values[i] == selected);
		}
		popup.getMenu().setGroupCheckable(0, true, true);
		popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			public boolean onMenuItemClick(android.view.MenuItem item) {
				prefs.edit().putInt("sortBy", item.getItemId()).apply();
				applySortChange();
				return true;
			}
		});
		showScrollablePopupItems(popup);
	}

	void sortList(ArrayList<TeraEntry> list) {
		final int by = prefs.getInt("sortBy", 0);
		final boolean desc = prefs.getBoolean("sortDesc", false);
		Collections.sort(list, new Comparator<TeraEntry>() {
			public int compare(TeraEntry a, TeraEntry b) {
				if (a.isDir != b.isDir)
					return a.isDir ? -1 : 1;
				int r;
				if (by == 1)
					r = a.mtime < b.mtime ? -1 : (a.mtime > b.mtime ? 1 : 0);
				else if (by == 2)
					r = a.size < b.size ? -1 : (a.size > b.size ? 1 : 0);
				else
					r = a.name.compareToIgnoreCase(b.name);
				if (r == 0)
					r = a.name.compareToIgnoreCase(b.name);
				return desc ? -r : r;
			}
		});
	}

	void reloadCurrent() {
		if (searchTerm != null)
			runSearch(searchTerm);
		else {
			cache.remove(curDir);
			loadDir(curDir, true);
		}
	}

	void loadDir(final String dir, boolean force) {
		if (client == null)
			return;
		sel.clear();
		ArrayList<TeraEntry> hit = force ? null : cache.get(dir);
		if (hit != null) {
			curDir = dir;
			entries = hit;
			adapter.setData(entries);
			updateTitle();
			onSelChanged();
			return;
		}
		final int seq = ++navSeq;
		loadBar.setVisibility(View.VISIBLE);
		final TeraClient c = client;
		exec.execute(new Runnable() {
			public void run() {
				try {
					final JSONObject r = c.listAll(dir);
					final int errno = r.optInt("errno", 0);
					final ArrayList<TeraEntry> list = new ArrayList<TeraEntry>();
					if (errno == 0 && r.optJSONArray("list") != null) {
						for (int i = 0; i < r.optJSONArray("list").length(); i++)
							list.add(TeraEntry.from(r.optJSONArray("list").getJSONObject(i)));
						sortList(list);
					}
					runOnUiThread(new Runnable() {
						public void run() {
							if (seq != navSeq || client != c)
								return;
							loadBar.setVisibility(View.INVISIBLE);
							if (errno == -6) {
								signOut("Your session expired. Please sign in again.");
								return;
							}
							if (errno != 0) {
								toast("Could not open the folder (error " + errno + ")");
								return;
							}
							searchTerm = null;
							curDir = dir;
							entries = list;
							cache.put(dir, list);
							adapter.setData(entries);
							updateTitle();
							onSelChanged();
							saveSession(null);
							if (quotaText.length() == 0)
								refreshQuota();
							warmUp();
						}
					});
				} catch (final Exception e) {
					runOnUiThread(new Runnable() {
						public void run() {
							if (seq != navSeq)
								return;
							loadBar.setVisibility(View.INVISIBLE);
							toast("Could not load: " + e.getMessage());
						}
					});
				}
			}
		});
	}

	void refreshQuota() {
		final TeraClient c = client;
		if (c == null)
			return;
		exec.execute(new Runnable() {
			public void run() {
				try {
					JSONObject q = c.getQuota();
					if (q.optInt("errno", -1) != 0)
						return;
					final String s = human(q.optLong("used")) + " / " + human(q.optLong("total"));
					runOnUiThread(new Runnable() {
						public void run() {
							quotaText = s;
							updateTitle();
						}
					});
				} catch (Exception e) {
				}
			}
		});
	}

	// ---- icons / text

	static String iconFor(TeraEntry e) {
		if (e.isDir)
			return "\uD83D\uDCC1";
		String n = e.name.toLowerCase(Locale.US);
		int dot = n.lastIndexOf('.');
		String x = dot >= 0 ? n.substring(dot + 1) : "";
		if (x.matches("jpg|jpeg|png|gif|webp|bmp|heic"))
			return "\uD83D\uDDBC";
		if (x.matches("mp4|mkv|avi|mov|wmv|flv|webm|3gp|m4v|ts"))
			return "\uD83C\uDFAC";
		if (x.matches("mp3|wav|flac|aac|m4a|ogg|opus"))
			return "\uD83C\uDFB5";
		if (x.matches("zip|rar|7z|tar|gz|bz2"))
			return "\uD83D\uDCE6";
		if (x.equals("apk"))
			return "\uD83E\uDD16";
		return "\uD83D\uDCC4";
	}

	String describe(TeraEntry e) {
		String when = e.mtime > 0
				? new SimpleDateFormat("MM/dd/yyyy h:mm a", Locale.getDefault()).format(new Date(e.mtime * 1000)) : "";
		String lead = e.isDir ? "Folder" : human(e.size);
		return lead + (when.length() > 0 ? " \u00B7 " + when : "");
	}

	// ---- menus

	void showSelectionMenu(View anchor) {
		PopupMenu pm = newPopup(anchor);
		pm.getMenu().add(0, 1, 0, "Delete");
		pm.getMenu().add(0, 2, 1, "Zip");
		pm.getMenu().add(0, 3, 2, "Tar");
		pm.getMenu().add(0, 4, 3, "Copy to...");
		pm.getMenu().add(0, 5, 4, "Move to...");
		pm.getMenu().add(0, 6, 5, "Download");
		pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			public boolean onMenuItemClick(android.view.MenuItem item) {
				ArrayList<TeraEntry> chosen = selectedEntries();
				switch (item.getItemId()) {
				case 1:
					confirmDelete(chosen);
					break;
				case 2:
					archiveEntries(chosen, false);
					break;
				case 3:
					archiveEntries(chosen, true);
					break;
				case 4:
					copyMoveTo(chosen, false);
					break;
				case 5:
					copyMoveTo(chosen, true);
					break;
				case 6:
					downloadEntries(chosen);
					break;
				}
				return true;
			}
		});
		showScrollablePopupItems(pm);
	}

	void showTopMenu(View anchor) {
		if (!selectedEntries().isEmpty()) {
			showSelectionMenu(anchor);
			return;
		}
		PopupMenu pm = newPopup(anchor);
		pm.getMenu().add(0, 1, 0, "Create");
		pm.getMenu().add(0, 2, 1, "Upload file");
		pm.getMenu().add(0, 8, 2, "Upload folder");
		pm.getMenu().add(0, 3, 3, "Search");
		pm.getMenu().add(0, 4, 3, "Trash");
		pm.getMenu().add(0, 5, 4, "Shared links");
		pm.getMenu().add(0, 6, 5, "Settings");
		pm.getMenu().add(0, 9, 6, "Show login cookie");
		pm.getMenu().add(0, 7, 7, "Sign out");
		pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			public boolean onMenuItemClick(android.view.MenuItem item) {
				switch (item.getItemId()) {
				case 1:
					askCreate();
					break;
				case 2:
					pickUploads();
					break;
				case 8:
					pickUploadFolder();
					break;
				case 3:
					askSearch();
					break;
				case 4:
					showTrash();
					break;
				case 5:
					showShares();
					break;
				case 6:
					showSettings();
					break;
				case 9:
					showCookieDialog(false);
					break;
				default:
					signOut(null);
				}
				return true;
			}
		});
		showScrollablePopupItems(pm);
	}

	/** A screen-bounded, automatically scrolling alternative to a tall PopupMenu. */
	View popupAnchor;

	PopupMenu newPopup(View anchor) {
		popupAnchor = anchor;
		return new PopupMenu(this, anchor);
	}

	void showScrollablePopupItems(final PopupMenu popup) {
		final android.view.Menu menu = popup.getMenu();
		final View anchor = popupAnchor;
		popupAnchor = null;
		final ArrayList<android.view.MenuItem> items = new ArrayList<android.view.MenuItem>();
		final ArrayList<String> labels = new ArrayList<String>();
		final ArrayList<String> icons = new ArrayList<String>();
		for (int i = 0; i < menu.size(); i++) {
			android.view.MenuItem item = menu.getItem(i);
			if (item.isVisible()) {
				items.add(item);
				String t = item.getTitle().toString();
				labels.add(t);
				icons.add(item.isCheckable() ? (item.isChecked() ? "check" : null) : Icons.forTitle(t));
			}
		}
		final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
		final int maxH = (int) (dm.heightPixels * 0.7f);
		// wraps its rows exactly (no empty space below the last item), scrolls when taller than maxH
		final ListView list = new ListView(this) {
			protected void onMeasure(int w, int h) {
				super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
			}
		};
		list.setDividerHeight(0);
		BaseAdapter ad = new BaseAdapter() {
			public int getCount() {
				return labels.size();
			}

			public Object getItem(int i) {
				return labels.get(i);
			}

			public long getItemId(int i) {
				return i;
			}

			public View getView(int pos, View cv, ViewGroup parent) {
				LinearLayout row = new LinearLayout(MainActivity.this);
				row.setOrientation(LinearLayout.HORIZONTAL);
				row.setGravity(Gravity.CENTER_VERTICAL);
				row.setPadding(dp(14), dp(11), dp(14), dp(11));
				ImageView iv = new ImageView(MainActivity.this);
				String ic = icons.get(pos);
				if (ic != null)
					iv.setImageDrawable(Icons.get(ic, TEXT));
				LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22));
				ip.rightMargin = dp(12);
				row.addView(iv, ip);
				TextView tv = mkText(labels.get(pos), 16, TEXT);
				tv.setSingleLine(true);
				row.addView(tv, new LinearLayout.LayoutParams(-2, -2));
				return row;
			}
		};
		list.setAdapter(ad);
		int widest = 0;
		for (int i = 0; i < ad.getCount(); i++) {
			View v = ad.getView(i, null, list);
			v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
			widest = Math.max(widest, v.getMeasuredWidth());
		}
		final int popW = Math.max(dp(130), Math.min(widest, Math.min(dp(260), dm.widthPixels - dp(32))));
		LinearLayout wrap = new LinearLayout(this);
		wrap.setOrientation(LinearLayout.VERTICAL);
		View handle = new View(this);
		android.graphics.drawable.GradientDrawable hg = new android.graphics.drawable.GradientDrawable();
		hg.setColor(MUTED);
		hg.setCornerRadius(dp(2));
		handle.setBackground(hg);
		LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
		hp.gravity = Gravity.CENTER_HORIZONTAL;
		hp.topMargin = dp(7);
		hp.bottomMargin = dp(2);
		wrap.addView(handle, hp);
		wrap.addView(list, new LinearLayout.LayoutParams(-1, -2));
		final AlertDialog dialog = new DragBuilder(this).keepWindow().setView(wrap).create();
		list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				int actionId = items.get(position).getItemId();
				dialog.dismiss();
				menu.performIdentifierAction(actionId, 0);
			}
		});
		final android.view.Window window = dialog.getWindow();
		if (window == null) {
			dialog.show();
			return;
		}
		// our own rounded background: no built-in dialog insets, so the width below is the real width
		android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
		bg.setColor(PANEL);
		bg.setCornerRadius(dp(12));
		bg.setStroke(1, DIVIDER);
		window.setBackgroundDrawable(bg);
		if (anchor == null || !anchor.isShown()) {
			window.setGravity(Gravity.CENTER);
			dialog.show();
			// Android resets a dialog's size while it builds its content, so the width is set right after show()
			// (still before the first frame is drawn)
			window.setLayout(popW, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
			return;
		}
		// Open next to the button that was tapped (right edge aligned), like a normal popup menu.
		// FLAG_LAYOUT_IN_SCREEN makes x / y plain screen coordinates; size and position are applied
		// synchronously right after show(), so the popup never appears elsewhere first.
		wrap.measure(View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(maxH + dp(24), View.MeasureSpec.AT_MOST));
		int h = wrap.getMeasuredHeight();
		int[] loc = new int[2];
		anchor.getLocationOnScreen(loc);
		boolean topBar = loc[1] < dm.heightPixels * 0.15f;
		int wantX = Math.max(dp(8), Math.min(loc[0] + anchor.getWidth() - popW, dm.widthPixels - popW - dp(8)));
		int wantY = topBar ? loc[1] + anchor.getHeight() : loc[1];
		wantY = Math.max(dp(28), Math.min(wantY, dm.heightPixels - h - dp(48)));
		window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
		window.setGravity(Gravity.TOP | Gravity.LEFT);
		dialog.show();
		android.view.WindowManager.LayoutParams lp = window.getAttributes();
		lp.width = popW;
		lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
		lp.x = wantX;
		lp.y = wantY;
		window.setAttributes(lp);
	}

	void showRowMenu(final TeraEntry e, View anchor) {
		PopupMenu pm = newPopup(anchor);
		if (!e.isDir && TextEditor.isEditableName(e.name))
			pm.getMenu().add(0, 14, 0, "Edit");
		pm.getMenu().add(0, 1, 0, "Delete");
		pm.getMenu().add(0, 2, 1, "Rename");
		pm.getMenu().add(0, 3, 2, "Copy to...");
		pm.getMenu().add(0, 4, 3, "Move to...");
		pm.getMenu().add(0, 5, 4, "Zip");
		pm.getMenu().add(0, 6, 5, "Tar");
		if (!e.isDir && TeraWork.isZipName(e.name)) {
			pm.getMenu().add(0, 10, 5, "Unzip");
			pm.getMenu().add(0, 11, 5, "Unzip to folder");
		} else if (!e.isDir && TeraWork.isTarName(e.name)) {
			pm.getMenu().add(0, 12, 5, "Untar");
			pm.getMenu().add(0, 13, 5, "Untar to folder");
		}
		if (canPreview(e)) pm.getMenu().add(0, 15, 0, "View");
		pm.getMenu().add(0, 7, 6, "Download");
		pm.getMenu().add(0, 8, 7, "Share link");
		pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			public boolean onMenuItemClick(android.view.MenuItem item) {
				ArrayList<TeraEntry> one = new ArrayList<TeraEntry>();
				one.add(e);
				switch (item.getItemId()) {
				case 1:
					confirmDelete(one);
					break;
				case 2:
					askRename(e);
					break;
				case 3:
					copyMoveTo(one, false);
					break;
				case 4:
					copyMoveTo(one, true);
					break;
				case 5:
					archiveEntries(one, false);
					break;
				case 6:
					archiveEntries(one, true);
					break;
				case 7:
					downloadEntries(one);
					break;
				case 8:
					createShare(e);
					break;
				case 15:
					viewEntry(e);
					break;
				case 14:
					editEntry(e);
					break;
				case 10:
				case 12:
					confirmExtract(e, false);
					break;
				case 11:
				case 13:
					runExtract(e, true);
					break;
				default:
					showEntryDialog(e);
				}
				return true;
			}
		});
		showScrollablePopupItems(pm);
	}

	boolean canPreview(TeraEntry e) {
        if (e.isDir || e.name == null) return false;
        String n = e.name.toLowerCase(Locale.US);
        return n.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic|mp4|mkv|webm|mov|avi|3gp|pdf)$");
    }

    void viewEntry(final TeraEntry e) {
        if (client == null) return;
        final TeraClient c = client;
        final File dir = new File(getCacheDir(), "previews");
        dir.mkdirs();
        final File local = new File(dir, System.nanoTime() + "_" + TeraWork.safeSegment(e.name));
        final JobStatus st = new JobStatus("Opening " + e.name);
        execDl.execute(new Runnable() {
            public void run() {
                String error = null;
                try {
                    String link = c.getDownloadLink(e.path, e.fsId);
                    c.downloadTo(link, local, e.size, st);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    local.delete();
                }
                st.close();
                final String failure = error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (failure != null) {
                            if (!"Cancelled".equals(failure)) showError(failure);
                            return;
                        }
                        Intent intent = new Intent(MainActivity.this, PreviewActivity.class);
                        intent.putExtra("path", local.getAbsolutePath());
                        intent.putExtra("name", e.name);
                        startActivity(intent);
                    }
                });
            }
        });
    }

	/** Download a text file to the cache and open it in the editor. */
	void editEntry(final TeraEntry e) {
		final TeraClient c = client;
		if (c == null)
			return;
		if (e.size > TextEditor.MAX_BYTES) {
			toast("File is too large to edit (limit " + human(TextEditor.MAX_BYTES) + ")");
			return;
		}
		final File dir = new File(getCacheDir(), "edit/" + System.nanoTime());
		dir.mkdirs();
		final File local = new File(dir, TeraWork.safeSegment(e.name));
		final JobStatus st = new JobStatus("Opening " + e.name);
		execDl.execute(new Runnable() {
			public void run() {
				String err = null;
				String text = null;
				try {
					if (e.size <= 0) {
						new FileOutputStream(local).close();
						text = "";
					} else {
						String link = c.getDownloadLink(e.path, e.fsId);
						c.downloadTo(link, local, e.size, st);
						byte[] b = new byte[(int) local.length()];
						FileInputStream in = new FileInputStream(local);
						try {
							int off = 0, n;
							while (off < b.length && (n = in.read(b, off, b.length - off)) > 0)
								off += n;
						} finally {
							in.close();
						}
						for (int i = 0; i < b.length && i < 8000; i++)
							if (b[i] == 0)
								throw new Exception("This looks like a binary file, not text");
						text = new String(b, "UTF-8");
					}
				} catch (Exception ex) {
					err = ex.getMessage() == null ? ex.toString() : ex.getMessage();
				}
				st.close();
				final String ferr = err, ftext = text;
				runOnUiThread(new Runnable() {
					public void run() {
						if (ferr != null) {
							local.delete();
							dir.delete();
							if (ferr.equals("Cancelled"))
								toast("Cancelled");
							else
								showError(ferr);
							return;
						}
						new TextEditor(MainActivity.this, local, e, ftext).show();
					}
				});
			}
		});
	}

	void showEntryDialog(final TeraEntry e) {
		AlertDialog.Builder b = new DragBuilder(this);
		b.setTitle(e.name);
		b.setMessage((e.isDir ? "Folder" : human(e.size)) + "\n" + describe(e) + "\n\n" + e.path);
		final ArrayList<TeraEntry> one = new ArrayList<TeraEntry>();
		one.add(e);
		if (e.isDir) {
			b.setPositiveButton("Open", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface d, int w) {
					searchTerm = null;
					loadDir(e.path, false);
				}
			});
		} else {
			b.setPositiveButton("Download", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface d, int w) {
					downloadEntries(one);
				}
			});
		}
		b.setNeutralButton("Copy path", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface d, int w) {
				copyText("path", e.path);
				toast("Path copied");
			}
		});
		b.setNegativeButton("Close", null);
		b.show();
	}

	void copyText(String label, String text) {
		ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		cm.setPrimaryClip(ClipData.newPlainText(label, text));
	}

	// ------------------------------------------------------------------ operations

	void confirmDelete(final ArrayList<TeraEntry> items) {
		if (items.isEmpty())
			return;
		if (!prefs.getBoolean("confirmDelete", true)) {
			runDelete(items);
			return;
		}
		String what = items.size() == 1 ? "\"" + items.get(0).name + "\"" : items.size() + " items";
		new DragBuilder(this).setTitle("Delete").setMessage("Delete " + what + "?\nThey are moved to the trash.")
				.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						runDelete(items);
					}
				}).setNegativeButton("Cancel", null).show();
	}

	void runDelete(final ArrayList<TeraEntry> items) {
		runJob("Deleting...", new Job() {
			public String run(TeraClient c) throws Exception {
				JSONArray objs = new JSONArray(), plain = new JSONArray();
				for (int i = 0; i < items.size(); i++) {
					if (items.get(i).path.equals("/"))
						continue;
					objs.put(new JSONObject().put("path", items.get(i).path));
					plain.put(items.get(i).path);
				}
				JSONObject j = null;
				try {
					j = c.fileManager("delete", objs);
				} catch (Exception ex) {
					j = null; // an HTTP error: try the other request shape below
				}
				if (j == null || (TeraClient.errnoOf(j) != 0 && TeraClient.errnoOf(j) != -6))
					j = c.fileManager("delete", plain); // some builds expect a plain path list
				return chk(j, "Delete");
			}
		}, afterFilesChange);
	}

	void askRename(final TeraEntry e) {
		final EditText in = new EditText(this);
		in.setText(e.name);
		in.setSingleLine(true);
		int dot = e.name.lastIndexOf('.');
		in.setSelection(0, !e.isDir && dot > 0 ? dot : e.name.length());
		new DragBuilder(this).setTitle("Rename").setView(in)
				.setPositiveButton("OK", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						final String nn = in.getText().toString().trim();
						if (nn.length() == 0 || nn.indexOf('/') >= 0 || nn.equals(e.name)) {
							if (nn.indexOf('/') >= 0)
								toast("A name cannot contain /");
							return;
						}
						runJob("Renaming...", new Job() {
							public String run(TeraClient c) throws Exception {
								JSONArray l = new JSONArray().put(new JSONObject().put("path", e.path).put("newname", nn));
								return chk(c.fileManager("rename", l), "Rename");
							}
						}, afterFilesChange);
					}
				}).setNegativeButton("Cancel", null).show();
	}

	/** path line of a folder picker: home icon, the path, and the go-to-parent icon right beside it */
	LinearLayout pickerHeader(TextView pathView, View.OnClickListener homeCb, View.OnClickListener upCb, View[] upOut) {
		LinearLayout bar = new LinearLayout(this);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		View home = iconButton("home", ACCENT, 12, 4, 15, 30);
		home.setOnClickListener(homeCb);
		bar.addView(home);
		HorizontalScrollView sc = new HorizontalScrollView(this);
		sc.setHorizontalScrollBarEnabled(false);
		LinearLayout row = new LinearLayout(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		pathView.setPadding(dp(4), dp(5), dp(4), dp(5));
		pathView.setSingleLine(true);
		row.addView(pathView);
		View up = iconButton("updir", ACCENT, 6, 8, 15, 30);
		up.setOnClickListener(upCb);
		row.addView(up);
		upOut[0] = up;
		sc.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -1));
		bar.addView(sc, new LinearLayout.LayoutParams(0, -2, 1));
		return bar;
	}

	interface FolderCb {
		void picked(String dir);
	}

	/** dialog to choose a TeraBox folder */
	void pickFolder(String title, String start, final FolderCb cb) {
		final String[] cur = new String[] { start };
		final ArrayList<String[]> rows = new ArrayList<String[]>(); // {label, path}
		final ArrayList<String> labels = new ArrayList<String>();
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		final TextView pathView = mkText("", 13, MUTED);
		final View[] upBtn = new View[1];
		final Runnable[] loader = new Runnable[1];
		final String[] target = new String[] { start };
		box.addView(pickerHeader(pathView, new View.OnClickListener() {
			public void onClick(View v) {
				target[0] = "/";
				loader[0].run();
			}
		}, new View.OnClickListener() {
			public void onClick(View v) {
				if (!cur[0].equals("/")) {
					target[0] = parentOf(cur[0]);
					loader[0].run();
				}
			}
		}, upBtn));
		ListView lv = new ListView(this);
		final ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
			public View getView(int pos, View cv, ViewGroup parent) {
				TextView tv = (TextView) super.getView(pos, cv, parent);
				String t = getItem(pos);
				if (t != null && t.startsWith("\u2B06"))
					tv.setCompoundDrawables(null, null, null, null);
				else
					setIcon(tv, "folder", ACCENT);
				return tv;
			}
		};
		lv.setAdapter(ad);
		box.addView(lv, new LinearLayout.LayoutParams(-1, dp(300)));
		loader[0] = new Runnable() {
			public void run() {
				final String dir = target[0];
				pathView.setText("Loading " + dir + " ...");
				exec.execute(new Runnable() {
					public void run() {
						try {
							JSONObject r = client.listAll(dir);
							final ArrayList<String[]> nr = new ArrayList<String[]>();
							JSONArray a = r.optJSONArray("list");
							ArrayList<TeraEntry> folders = new ArrayList<TeraEntry>();
							for (int i = 0; a != null && i < a.length(); i++) {
								TeraEntry e = TeraEntry.from(a.getJSONObject(i));
								if (e.isDir)
									folders.add(e);
							}
							sortList(folders);
							for (int i = 0; i < folders.size(); i++)
								nr.add(new String[] { folders.get(i).name, folders.get(i).path });
							runOnUiThread(new Runnable() {
								public void run() {
									cur[0] = dir;
									rows.clear();
									rows.addAll(nr);
									labels.clear();
									for (int i = 0; i < rows.size(); i++)
										labels.add(rows.get(i)[0]);
									ad.notifyDataSetChanged();
									pathView.setText(dir);
									upBtn[0].setVisibility(dir.equals("/") ? View.GONE : View.VISIBLE);
								}
							});
						} catch (final Exception e) {
							runOnUiThread(new Runnable() {
								public void run() {
									pathView.setText("Could not load: " + e.getMessage());
								}
							});
						}
					}
				});
			}
		};
		lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
				if (pos >= 0 && pos < rows.size()) {
					target[0] = rows.get(pos)[1];
					loader[0].run();
				}
			}
		});
		new DragBuilder(this).setTitle(title).setView(box)
				.setPositiveButton("Select this folder", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						cb.picked(cur[0]);
					}
				}).setNegativeButton("Cancel", null).show();
		loader[0].run();
	}

	void copyMoveTo(final ArrayList<TeraEntry> items, final boolean move) {
		if (items.isEmpty())
			return;
		pickFolder((move ? "Move" : "Copy") + " " + (items.size() == 1 ? "\"" + items.get(0).name + "\"" : items.size() + " items")
				+ " to...", curDir, new FolderCb() {
					public void picked(final String dest) {
						final ArrayList<TeraEntry> todo = new ArrayList<TeraEntry>();
						for (int i = 0; i < items.size(); i++) {
							TeraEntry e = items.get(i);
							if (e.isDir && (dest.equals(e.path) || dest.startsWith(e.path + "/"))) {
								toast("A folder cannot be put inside itself: " + e.name);
								return;
							}
							if (move && parentOf(e.path).equals(dest))
								continue; // already there
							todo.add(e);
						}
						if (todo.isEmpty()) {
							toast("Already in that folder");
							return;
						}
						doCopyMove(todo, dest, move, "");
					}
				});
	}

	void doCopyMove(final ArrayList<TeraEntry> todo, final String dest, final boolean move, final String ondup) {
		conflictAction = new Runnable() {
			public void run() {
				new DragBuilder(MainActivity.this).setTitle("Already exists")
						.setMessage("Some items already exist in " + dest + ".\nWhat should happen to them?")
						.setPositiveButton("Overwrite", new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface d, int w) {
								doCopyMove(todo, dest, move, "overwrite");
							}
						}).setNeutralButton("Keep both", new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface d, int w) {
								doCopyMove(todo, dest, move, "newcopy");
							}
						}).setNegativeButton("Cancel", null).show();
			}
		};
		runJob(move ? "Moving..." : "Copying...", new Job() {
			public String run(TeraClient c) throws Exception {
				JSONArray l = new JSONArray();
				for (int i = 0; i < todo.size(); i++) {
					TeraEntry e = todo.get(i);
					String nn = e.name;
					if (!move && parentOf(e.path).equals(dest) && ondup.length() == 0) {
						int dot = nn.lastIndexOf('.');
						nn = dot > 0 && !e.isDir ? nn.substring(0, dot) + " - Copy" + nn.substring(dot) : nn + " - Copy";
					}
					l.put(new JSONObject().put("path", e.path).put("dest", dest).put("newname", nn));
				}
				JSONObject j = c.fileManager(move ? "move" : "copy", l, ondup);
				if (ondup.length() == 0 && TeraClient.errnoOf(j) != 0 && TeraClient.hasConflict(j))
					return "CONFLICT";
				return chk(j, move ? "Move" : "Copy");
			}
		}, afterFilesChange);
	}

	// ---- create

	void askCreate() {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(20), dp(8), dp(20), 0);
		final RadioButton rf = new RadioButton(this);
		rf.setText("Folder");
		final RadioButton rt = new RadioButton(this);
		rt.setText("Empty file");
		RadioGroup rg = new RadioGroup(this);
		rg.addView(rf);
		rg.addView(rt);
		rf.setChecked(true);
		box.addView(rg);
		final EditText in = new EditText(this);
		in.setHint("Name");
		in.setSingleLine(true);
		box.addView(in);
		new DragBuilder(this).setTitle("Create in " + curDir).setView(box)
				.setPositiveButton("Create", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						final String nn = in.getText().toString().trim();
						if (nn.length() == 0 || nn.indexOf('/') >= 0) {
							toast("Enter a name without /");
							return;
						}
						final boolean folder = rf.isChecked();
						final String dirNow = curDir;
						final String remote = (dirNow.equals("/") ? "" : dirNow) + "/" + nn;
						runJob("Creating...", new Job() {
							public String run(TeraClient c) throws Exception {
								if (folder)
									return chk(c.mkdir(remote), "Create folder");
								c.uploadFile(emptyFile(), dirNow, nn, null);
								return null;
							}
						}, afterFilesChange);
					}
				}).setNegativeButton("Cancel", null).show();
	}

	File emptyFile() throws Exception {
		File f = new File(getCacheDir(), "empty.tmp");
		new FileOutputStream(f).close();
		return f;
	}

	// ---- search

	ProgressBar searchPageProgress;
	LinearLayout searchResultsContainer;
	void askSearch() {
		screen = SCREEN_SEARCH;
		final String scopeDir = curDir;
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(BG);

		// Keep the file-manager breadcrumb visible on the dedicated search page.
		LinearLayout header = new LinearLayout(this);
		header.setOrientation(LinearLayout.VERTICAL);
		header.setBackgroundColor(PANEL);
		LinearLayout navigation = new LinearLayout(this);
		navigation.setGravity(Gravity.CENTER_VERTICAL);
		navigation.setPadding(0, dp(BAR_TOP_DP), 0, 0); // same height as the other title bars
		navigation.setMinimumHeight(dp(BAR_TOP_DP + BAR_ROW_DP));
		View home = iconButton("home", ACCENT, 14, 8);
		home.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) { openSearchFolder("/"); }
		});
		navigation.addView(home);
		HorizontalScrollView pathScroll = new HorizontalScrollView(this);
		pathScroll.setHorizontalScrollBarEnabled(false);
		LinearLayout pathItems = new LinearLayout(this);
		pathItems.setGravity(Gravity.CENTER_VERTICAL);
		if (scopeDir.equals("/")) pathItems.addView(mkText("/", 17, TEXT));
		String[] pieces = scopeDir.split("/");
		String accumulated = "";
		for (int i = 0; i < pieces.length; i++) {
			if (pieces[i].length() == 0) continue;
			accumulated += "/" + pieces[i];
			final String destination = accumulated;
			TextView crumb = mkText("/" + pieces[i], 17, TEXT);
			crumb.setPadding(dp(3), dp(4), dp(3), dp(4));
			crumb.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) { openSearchFolder(destination); }
			});
			pathItems.addView(crumb);
		}
		if (!scopeDir.equals("/")) {
			View up = iconButton("updir", ACCENT, 10, 10);
			up.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) { openSearchFolder(parentOf(scopeDir)); }
			});
			pathItems.addView(up); // right beside the path
		}
		pathScroll.addView(pathItems);
		navigation.addView(pathScroll, new LinearLayout.LayoutParams(0, -2, 1));
		header.addView(navigation);
		root.addView(header, new LinearLayout.LayoutParams(-1, -2));
		TextView pageHeading = mkText("⌕  Search files", 20, TEXT);
		pageHeading.setPadding(dp(18), dp(10), dp(18), dp(4));
		pageHeading.setTypeface(Typeface.DEFAULT_BOLD);
		root.addView(pageHeading);

		ScrollView scroll = new ScrollView(this);
		LinearLayout content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(dp(18), dp(6), dp(18), dp(12));
		scroll.addView(content);
		root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

		TextView scope = mkText("Search scope: TeraBox root" + (scopeDir.equals("/") ? "" : " " + scopeDir) + " and its subfolders", 15, MUTED);
		content.addView(scope);
		final EditText input = new EditText(this);
		styleEdit(input, "Enter file or folder name");
		input.setSingleLine(true);
		LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(46));
		ip.topMargin = dp(6);
		content.addView(input, ip);

		LinearLayout options = new LinearLayout(this);
		options.setOrientation(LinearLayout.HORIZONTAL);
		final CheckBox exact = new CheckBox(this);
		exact.setText("Exact match");
		exact.setTextColor(TEXT);
		final CheckBox caseSensitive = new CheckBox(this);
		caseSensitive.setText("Case sensitive");
		caseSensitive.setTextColor(TEXT);
		options.addView(exact, new LinearLayout.LayoutParams(0, -2, 1));
		options.addView(caseSensitive, new LinearLayout.LayoutParams(0, -2, 1));
		LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
		op.topMargin = dp(0);
		content.addView(options, op);

		LinearLayout actions = new LinearLayout(this);
		actions.setOrientation(LinearLayout.HORIZONTAL);
		actions.setGravity(Gravity.CENTER_HORIZONTAL);
		LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(38));
		ap.topMargin = dp(8);
		content.addView(actions, ap);
		Button cancel = mkSmallBtn("✖  Cancel", BTN);
		LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -1);
		cp.rightMargin = dp(6);
		actions.addView(cancel, cp);
		cancel.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) { showFiles(); }
		});
		final Button search = mkSmallBtn("⌕  Search", ACCENT);
		LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -1);
		sp.leftMargin = dp(6);
		actions.addView(search, sp);
		search.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				String term = input.getText().toString().trim();
				if (term.length() == 0) { input.setError("Enter a file or folder name"); return; }
				runSearch(term, exact.isChecked(), caseSensitive.isChecked());
			}
		});
		searchPageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		searchPageProgress.setIndeterminate(true);
		searchPageProgress.setVisibility(View.GONE);
		LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(4));
		pp.topMargin = dp(6);
		content.addView(searchPageProgress, pp);
		searchResultsContainer = new LinearLayout(this);
		searchResultsContainer.setOrientation(LinearLayout.VERTICAL);
		LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
		rp.topMargin = dp(6);
		content.addView(searchResultsContainer, rp);
		setContentView(root);
	}

	void runSearch(final String term) {
		runSearch(term, false, false);
	}

	void runSearch(final String term, final boolean exact, final boolean caseSensitive) {
		final TeraClient c = client;
		if (c == null) return;
		final String base = curDir;
		sel.clear();
		if (screen == SCREEN_SEARCH && searchPageProgress != null) searchPageProgress.setVisibility(View.VISIBLE);
		else if (loadBar != null) loadBar.setVisibility(View.VISIBLE);
		final int seq = ++navSeq;
		exec.execute(new Runnable() {
			public void run() {
				try {
					final ArrayList<TeraEntry> out = new ArrayList<TeraEntry>();
					int errno = 0;
					// TeraBox search can be case-insensitive; filter matches locally.
					for (int page = 1; page <= 5; page++) {
						JSONObject j = c.search(term, page);
						errno = TeraClient.errnoOf(j);
						if (errno != 0) break;
						JSONArray list = j.optJSONArray("list");
						int n = list == null ? 0 : list.length();
						for (int i = 0; i < n; i++) {
							TeraEntry e = TeraEntry.from(list.getJSONObject(i));
							if (e.path.length() == 0) continue;
							if (!base.equals("/") && !e.path.startsWith(base + "/")) continue;
							String name = caseSensitive ? e.name : e.name.toLowerCase(Locale.US);
							String needle = caseSensitive ? term : term.toLowerCase(Locale.US);
							if (exact ? name.equals(needle) : name.contains(needle)) out.add(e);
						}
						if (n < 1000) break;
					}
					sortList(out);
					final int fe = errno;
					runOnUiThread(new Runnable() {
						public void run() {
							if (seq != navSeq) return;
							if (screen == SCREEN_SEARCH && searchPageProgress != null) searchPageProgress.setVisibility(View.GONE);
							else if (loadBar != null) loadBar.setVisibility(View.INVISIBLE);
							if (fe == -6) { signOut("Your session expired. Please sign in again."); return; }
							if (fe != 0) { toast("Search failed (error " + fe + ")"); return; }
							if (screen == SCREEN_SEARCH) {
								showSearchResults(out);
							} else {
								searchTerm = term;
								entries = out;
								if (adapter != null) adapter.setData(entries);
								updateTitle();
								onSelChanged();
							}
						}
					});
				} catch (final Exception e) {
					runOnUiThread(new Runnable() {
						public void run() {
							if (seq != navSeq) return;
							if (screen == SCREEN_SEARCH && searchPageProgress != null) searchPageProgress.setVisibility(View.GONE);
							else if (loadBar != null) loadBar.setVisibility(View.INVISIBLE);
							toast("Search failed: " + e.getMessage());
						}
					});
				}
			}
		});
	}

	void showSearchResults(final ArrayList<TeraEntry> results) {
		if (searchResultsContainer == null) return;
		searchResultsContainer.removeAllViews();
		TextView title = mkText("Search results (" + results.size() + ")", 16, MUTED);
		title.setPadding(dp(2), dp(2), dp(2), dp(6));
		searchResultsContainer.addView(title);
		if (results.isEmpty()) {
			searchResultsContainer.addView(mkText("No matching files or folders.", 15, MUTED));
			return;
		}
		for (int i = 0; i < results.size(); i++) {
			final TeraEntry e = results.get(i);
			LinearLayout card = new LinearLayout(this);
			card.setOrientation(LinearLayout.VERTICAL);
			card.setPadding(dp(12), dp(7), dp(12), dp(7));
			GradientDrawable cardBg = new GradientDrawable();
			cardBg.setColor(PANEL);
			cardBg.setCornerRadius(dp(8));
			card.setBackground(cardBg);
			TextView name = mkText(e.name + (e.isDir ? "" : "   " + human(e.size)), 17, TEXT);
			setIcon(name, Icons.forEntry(e), e.isDir ? ACCENT : TEXT);
			name.setTypeface(Typeface.DEFAULT_BOLD);
			card.addView(name);
			TextView location = mkText(parentOf(e.path), 14, MUTED);
			location.setPadding(dp(32), dp(1), 0, dp(1));
			location.setTextColor(ACCENT);
			location.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) { openSearchFolder(parentOf(e.path)); }
			});
			card.addView(location);
			card.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					openSearchFolder(e.isDir ? e.path : parentOf(e.path));
				}
			});
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
			params.bottomMargin = dp(4);
			searchResultsContainer.addView(card, params);
		}
	}

	void openSearchFolder(String folder) {
		++navSeq; // Cancel any outstanding search before changing screens.
		searchTerm = null;
		showFiles();
		loadDir(folder, false);
	}

	void exitSearch() {
		searchTerm = null;
		sel.clear();
		loadDir(curDir, false);
	}

	// ---- upload

	/** can the app browse the phone's storage itself (needed for the in-app pickers)? */
	boolean hasLocalAccess() {
		if (Build.VERSION.SDK_INT >= 30)
			return isStorageManager();
		if (Build.VERSION.SDK_INT >= 23)
			return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
		return true;
	}

	void askLocalAccess(final Runnable systemPicker) {
		new DragBuilder(this).setTitle("Allow access to files")
				.setMessage("To choose files inside TeraFiles, allow it to access the files on this phone.")
				.setPositiveButton("Allow", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						requestAccess();
					}
				}).setNeutralButton("Use system picker", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						systemPicker.run();
					}
				}).setNegativeButton("Cancel", null).show();
	}

	void pickUploads() {
		uploadDir = curDir;
		if (!hasLocalAccess()) {
			askLocalAccess(new Runnable() {
				public void run() {
					pickUploadsSystem();
				}
			});
			return;
		}
		showLocalPicker(false, new FilesCb() {
			public void picked(ArrayList<File> files) {
				uploadLocalFiles(files, uploadDir);
			}
		});
	}

	void pickUploadFolder() {
		uploadDir = curDir;
		if (!hasLocalAccess()) {
			askLocalAccess(new Runnable() {
				public void run() {
					pickUploadFolderSystem();
				}
			});
			return;
		}
		showLocalPicker(true, new FilesCb() {
			public void picked(ArrayList<File> files) {
				if (!files.isEmpty())
					uploadLocalFolder(files.get(0), uploadDir);
			}
		});
	}

	interface FilesCb {
		void picked(ArrayList<File> files);
	}

	static class LRow {
		File f;
		int type; // 0 = up, 1 = folder, 2 = file
	}

	/**
	 * In-app picker for files / folders on the phone, styled like the TeraBox folder picker.
	 * Files mode: tap files to tick them, "Select all" ticks every file of the open folder.
	 * Folder mode: browse and press "Select this folder".
	 */
	void showLocalPicker(final boolean foldersOnly, final FilesCb cb) {
		final File top = Environment.getExternalStorageDirectory();
		final File[] cur = new File[] { top };
		final ArrayList<LRow> rows = new ArrayList<LRow>();
		final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<String>();
		final AlertDialog[] dlg = new AlertDialog[1];
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		final TextView pathView = mkText("", 13, MUTED);
		final View[] upBtn = new View[1];
		final Runnable[] loadRef = new Runnable[1];
		box.addView(pickerHeader(pathView, new View.OnClickListener() {
			public void onClick(View v) {
				cur[0] = top;
				loadRef[0].run();
			}
		}, new View.OnClickListener() {
			public void onClick(View v) {
				File par = cur[0].getParentFile();
				if (par != null && !cur[0].getPath().equals("/storage") && !cur[0].getPath().equals("/")) {
					cur[0] = par;
					loadRef[0].run();
				}
			}
		}, upBtn));
		final CheckBox selAllBox = new CheckBox(this);
		selAllBox.setText("Select all");
		selAllBox.setTextColor(TEXT);
		selAllBox.setTextSize(16);
		selAllBox.setPadding(dp(8), dp(4), dp(8), dp(4));
		if (foldersOnly)
			selAllBox.setVisibility(View.GONE);
		LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
		sp.leftMargin = dp(12);
		box.addView(selAllBox, sp);
		final ListView lv = new ListView(this);
		lv.setDividerHeight(1);
		lv.setDivider(new android.graphics.drawable.ColorDrawable(DIVIDER));
		final BaseAdapter ad = new BaseAdapter() {
			public int getCount() {
				return rows.size();
			}

			public Object getItem(int i) {
				return rows.get(i);
			}

			public long getItemId(int i) {
				return i;
			}

			public View getView(int pos, View cv, ViewGroup parent) {
				// same rows as the TeraBox "Copy to..." folder picker: "up" row, folder emoji + name; files get a checkbox
				LRow r = rows.get(pos);
				LinearLayout row = new LinearLayout(MainActivity.this);
				row.setOrientation(LinearLayout.HORIZONTAL);
				row.setGravity(Gravity.CENTER_VERTICAL);
				row.setMinimumHeight(dp(48));
				row.setPadding(dp(16), dp(6), dp(16), dp(6));
				if (r.type == 2) {
					CheckBox cb2 = new CheckBox(MainActivity.this);
					cb2.setClickable(false);
					cb2.setFocusable(false);
					cb2.setChecked(picked.contains(r.f.getAbsolutePath()));
					row.addView(cb2, new LinearLayout.LayoutParams(-2, -2));
				}
				LinearLayout col = new LinearLayout(MainActivity.this);
				col.setOrientation(LinearLayout.VERTICAL);
				TextView nm = mkText(r.type == 0 ? "\u2B06  .." : r.f.getName(), 17, TEXT);
				if (r.type == 1)
					setIcon(nm, "folder", ACCENT);
				else if (r.type == 2)
					setIcon(nm, "file", TEXT);
				nm.setSingleLine(true);
				nm.setEllipsize(TextUtils.TruncateAt.MIDDLE);
				col.addView(nm);
				if (r.type == 2) {
					TextView sz = mkText(human(r.f.length()), 12, MUTED);
					sz.setPadding(dp(32), 0, 0, 0);
					col.addView(sz);
				}
				row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
				return row;
			}
		};
		lv.setAdapter(ad);
		box.addView(lv, new LinearLayout.LayoutParams(-1, dp(320)));

		final Runnable updateState = new Runnable() {
			public void run() {
				int files = 0, ticked = 0;
				for (int i = 0; i < rows.size(); i++) {
					if (rows.get(i).type == 2) {
						files++;
						if (picked.contains(rows.get(i).f.getAbsolutePath()))
							ticked++;
					}
				}
				selAllBox.setChecked(files > 0 && ticked == files);
				ad.notifyDataSetChanged();
				if (dlg[0] != null && !foldersOnly) {
					android.widget.Button b = dlg[0].getButton(DialogInterface.BUTTON_POSITIVE);
					if (b != null) {
						b.setText(picked.isEmpty() ? "Upload file" : "Upload (" + picked.size() + ")");
						b.setEnabled(!picked.isEmpty());
					}
				}
			}
		};
		final Runnable load = new Runnable() {
			public void run() {
				File dir = cur[0];
				rows.clear();
				File[] ls = dir.listFiles();
				String parent = dir.getParent();
				upBtn[0].setVisibility(parent != null && !dir.getPath().equals("/storage") && !dir.getPath().equals("/")
						? View.VISIBLE : View.GONE);
				if (ls == null) {
					pathView.setText(dir.getPath() + "  (cannot read this folder)");
				} else {
					pathView.setText(dir.getPath());
					java.util.Arrays.sort(ls, new java.util.Comparator<File>() {
						public int compare(File a, File b) {
							if (a.isDirectory() != b.isDirectory())
								return a.isDirectory() ? -1 : 1;
							return a.getName().compareToIgnoreCase(b.getName());
						}
					});
					for (int i = 0; i < ls.length; i++) {
						boolean isDir = ls[i].isDirectory();
						if (!isDir && foldersOnly)
							continue;
						LRow r = new LRow();
						r.f = ls[i];
						r.type = isDir ? 1 : 2;
						rows.add(r);
					}
				}
				lv.setSelection(0);
				updateState.run();
			}
		};
		loadRef[0] = load;
		lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
				if (pos < 0 || pos >= rows.size())
					return;
				LRow r = rows.get(pos);
				if (r.type == 2) {
					String k = r.f.getAbsolutePath();
					if (!picked.remove(k))
						picked.add(k);
					updateState.run();
				} else {
					cur[0] = r.f;
					load.run();
				}
			}
		});
		selAllBox.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				boolean on = selAllBox.isChecked();
				for (int i = 0; i < rows.size(); i++) {
					if (rows.get(i).type != 2)
						continue;
					String k = rows.get(i).f.getAbsolutePath();
					if (on)
						picked.add(k);
					else
						picked.remove(k);
				}
				updateState.run();
			}
		});
		AlertDialog.Builder b = new DragBuilder(this).setTitle(foldersOnly ? "Upload folder" : "Upload file").setView(box);
		b.setPositiveButton(foldersOnly ? "Select this folder" : "Upload file", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface d, int w) {
				ArrayList<File> out = new ArrayList<File>();
				if (foldersOnly) {
					out.add(cur[0]);
				} else {
					for (String k : picked) {
						File f = new File(k);
						if (f.isFile())
							out.add(f);
					}
				}
				if (!out.isEmpty())
					cb.picked(out);
			}
		}).setNegativeButton("Cancel", null);
		dlg[0] = b.create();
		dlg[0].show();
		load.run();
	}

	void pickUploadsSystem() {
		uploadDir = curDir;
		Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		i.addCategory(Intent.CATEGORY_OPENABLE);
		i.setType("*/*");
		i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
		try {
			startActivityForResult(i, 21);
		} catch (Exception e) {
			toast("No file picker available");
		}
	}

	void pickUploadFolderSystem() {
		uploadDir = curDir;
		try {
			Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
			i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
			startActivityForResult(i, 22);
		} catch (Exception e) { toast("No folder picker available"); }
	}

	protected void onActivityResult(int req, int res, Intent data) {
		super.onActivityResult(req, res, data);
		if (res != RESULT_OK || data == null) return;
		if (req == 22) {
			Uri tree = data.getData();
			if (tree != null) {
				try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
				uploadFolderTree(tree, uploadDir);
			}
			return;
		}
		if (req != 21) return;
		ArrayList<Uri> uris = new ArrayList<Uri>();
		if (data.getClipData() != null) {
			for (int i = 0; i < data.getClipData().getItemCount(); i++)
				uris.add(data.getClipData().getItemAt(i).getUri());
		} else if (data.getData() != null) {
			uris.add(data.getData());
		}
		if (!uris.isEmpty())
			uploadUris(uris, uploadDir);
	}

	static class FolderItem {
		Uri uri;
		String path;
		boolean directory;
		FolderItem(Uri u, String p, boolean d) { uri=u; path=p; directory=d; }
	}

	String safeFolderName(String name) {
		if (name == null || name.length() == 0) return "Folder";
		return name.replace('/', '_').replace('\\', '_');
	}

	String documentName(Uri uri) {
		android.database.Cursor cursor = null;
		try {
			cursor = getContentResolver().query(uri, new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null);
			if (cursor != null && cursor.moveToFirst()) return safeFolderName(cursor.getString(0));
		} catch (Exception ignored) {} finally { if (cursor != null) cursor.close(); }
		return "Folder";
	}

	void scanFolder(Uri tree, String docId, String relative, ArrayList<FolderItem> out, JobStatus st) throws Exception {
		if (st.cancelled()) throw new Exception("Cancelled");
		Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
		android.database.Cursor cursor = getContentResolver().query(children,
			new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null);
		if (cursor == null) throw new Exception("Cannot read selected folder");
		try {
			while (cursor.moveToNext()) {
				if (st.cancelled()) throw new Exception("Cancelled");
				String id = cursor.getString(0);
				String name = safeFolderName(cursor.getString(1));
				boolean folder = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
				Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
				String path = relative + "/" + name;
				out.add(new FolderItem(uri, path, folder));
				if (folder) scanFolder(tree, id, path, out, st);
			}
		} finally { cursor.close(); }
	}

	void uploadFolderTree(final Uri tree, final String destination) {
		final TeraClient c = client;
		if (c == null) return;
		final JobStatus st = new JobStatus("Uploading folder", true);
		st.insideApp = true;
		activeFolderUpload = st;
		st.showInsideApp();
		execDl.execute(new Runnable() {
			public void run() {
				int uploaded = 0;
				String error = null;
				File temp = new File(getCacheDir(), "folder-upload-" + System.nanoTime());
				try {
					String rootName = documentName(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)));
					ArrayList<FolderItem> items = new ArrayList<FolderItem>();
					st.message("Scanning " + rootName);
					scanFolder(tree, DocumentsContract.getTreeDocumentId(tree), "", items, st);
					String rootPath = (destination.equals("/") ? "" : destination) + "/" + rootName;
					ensureRemoteFolder(c, rootPath);
					int total = 0;
					for (FolderItem it : items) if (!it.directory) total++;
					for (FolderItem it : items) {
						if (st.cancelled()) throw new Exception("Cancelled");
						String remote = rootPath + it.path;
						if (it.directory) { ensureRemoteFolder(c, remote); continue; }
						int slash = remote.lastIndexOf('/');
						String parent = remote.substring(0, slash);
						String filename = remote.substring(slash + 1);
						st.message("(" + (uploaded + 1) + "/" + total + ") " + it.path + "\nPreparing...");
						InputStream in = getContentResolver().openInputStream(it.uri);
						if (in == null) throw new Exception("Cannot open " + it.path);
						FileOutputStream out = new FileOutputStream(temp);
						try {
							byte[] buffer = new byte[65536]; int n;
							while ((n = in.read(buffer)) != -1) {
								if (st.cancelled()) throw new Exception("Cancelled");
								out.write(buffer, 0, n);
							}
						} finally { out.close(); in.close(); }
						st.message("(" + (uploaded + 1) + "/" + total + ") " + it.path);
						c.uploadFile(temp, parent, filename, st);
						temp.delete(); uploaded++;
					}
				} catch (Exception e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
				finally { temp.delete(); }
				st.finish(error, uploaded + " file(s) uploaded");
				final String result = error;
				final int count = uploaded;
				runOnUiThread(new Runnable() { public void run() {
					cache.clear(); afterFilesChange.run();
					if (result == null) toast(count + " file(s) uploaded");
					else if ("Cancelled".equals(result)) toast("Folder upload cancelled (" + count + " done)");
					else showErrorResume("Folder upload stopped after " + count + " file(s):\n" + result,
						new Runnable() { public void run() { uploadFolderTree(tree, destination); } });
				} });
			}
		});
	}

	/** upload files chosen in the in-app picker (read straight from the phone, no copy first) */
	void uploadLocalFiles(final ArrayList<File> files, final String dir) {
		final TeraClient c = client;
		if (c == null || files.isEmpty())
			return;
		final JobStatus st = inAppJob("Uploading", true);
		execDl.execute(new Runnable() {
			public void run() {
				int ok = 0;
				String err = null;
				try {
					for (int i = 0; i < files.size(); i++) {
						if (st.cancelled())
							throw new Exception("Cancelled");
						File f = files.get(i);
						st.message("(" + (i + 1) + "/" + files.size() + ")  " + f.getName());
						c.uploadFile(f, dir, f.getName(), st);
						ok++;
					}
				} catch (Exception e) {
					err = e.getMessage() == null ? e.toString() : e.getMessage();
				}
				st.finish(err, ok + " file(s) uploaded");
				final int fok = ok;
				final String ferr = err;
				runOnUiThread(new Runnable() {
					public void run() {
						cache.clear();
						afterFilesChange.run();
						if (ferr == null)
							toast(fok + " file(s) uploaded");
						else if (ferr.equals("Cancelled"))
							toast("Upload cancelled (" + fok + " done)");
						else
							showErrorResume("Upload stopped after " + fok + " file(s):\n" + ferr, new Runnable() {
								public void run() {
									uploadLocalFiles(new ArrayList<File>(files.subList(fok, files.size())), dir);
								}
							});
					}
				});
			}
		});
	}

	void scanLocal(File dir, String rel, ArrayList<Object[]> out, JobStatus st) throws Exception {
		File[] ls = dir.listFiles();
		if (ls == null)
			throw new Exception("Cannot read " + dir.getPath());
		java.util.Arrays.sort(ls, new java.util.Comparator<File>() {
			public int compare(File a, File b) {
				return a.getName().compareToIgnoreCase(b.getName());
			}
		});
		for (int i = 0; i < ls.length; i++) {
			if (st.cancelled())
				throw new Exception("Cancelled");
			String path = rel + "/" + safeFolderName(ls[i].getName());
			if (ls[i].isDirectory()) {
				out.add(new Object[] { ls[i], path, Boolean.TRUE });
				scanLocal(ls[i], path, out, st);
			} else if (ls[i].isFile()) {
				out.add(new Object[] { ls[i], path, Boolean.FALSE });
			}
		}
	}

	/** upload a whole folder chosen in the in-app picker, keeping its sub-folders */
	void uploadLocalFolder(final File root, final String destination) {
		final TeraClient c = client;
		if (c == null)
			return;
		final JobStatus st = inAppJob("Uploading folder", true);
		execDl.execute(new Runnable() {
			public void run() {
				int uploaded = 0;
				String error = null;
				try {
					String rootName = safeFolderName(root.getName());
					ArrayList<Object[]> items = new ArrayList<Object[]>();
					st.message("Scanning " + rootName);
					scanLocal(root, "", items, st);
					String rootPath = (destination.equals("/") ? "" : destination) + "/" + rootName;
					ensureRemoteFolder(c, rootPath);
					int total = 0;
					for (Object[] it : items)
						if (!((Boolean) it[2]).booleanValue())
							total++;
					for (Object[] it : items) {
						if (st.cancelled())
							throw new Exception("Cancelled");
						File f = (File) it[0];
						String rel = (String) it[1];
						String remote = rootPath + rel;
						if (((Boolean) it[2]).booleanValue()) {
							ensureRemoteFolder(c, remote);
							continue;
						}
						int slash = remote.lastIndexOf('/');
						String parent = remote.substring(0, slash);
						String filename = remote.substring(slash + 1);
						st.message("(" + (uploaded + 1) + "/" + total + ") " + rel);
						c.uploadFile(f, parent, filename, st);
						uploaded++;
					}
				} catch (Exception e) {
					error = e.getMessage() == null ? e.toString() : e.getMessage();
				}
				st.finish(error, uploaded + " file(s) uploaded");
				final String result = error;
				final int count = uploaded;
				runOnUiThread(new Runnable() {
					public void run() {
						cache.clear();
						afterFilesChange.run();
						if (result == null)
							toast(count + " file(s) uploaded");
						else if ("Cancelled".equals(result))
							toast("Folder upload cancelled (" + count + " done)");
						else
							showErrorResume("Folder upload stopped after " + count + " file(s):\n" + result, new Runnable() {
								public void run() {
									uploadLocalFolder(root, destination);
								}
							});
					}
				});
			}
		});
	}

	void ensureRemoteFolder(TeraClient c, String path) throws Exception {
		JSONObject response = c.mkdir(path);
		int errno = response.optInt("errno", 0);
		if (errno != 0 && errno != -8 && errno != 12) throw new Exception("Cannot create folder " + path + ": " + response.toString());
	}

	String displayName(Uri u) {
		String name = null;
		android.database.Cursor cur = null;
		try {
			cur = getContentResolver().query(u, null, null, null, null);
			if (cur != null && cur.moveToFirst()) {
				int ix = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
				if (ix >= 0)
					name = cur.getString(ix);
			}
		} catch (Exception e) {
		} finally {
			if (cur != null)
				cur.close();
		}
		if (name == null || name.length() == 0)
			name = u.getLastPathSegment() == null ? "file" : u.getLastPathSegment();
		name = name.replace('/', '_').replace('\\', '_');
		return name;
	}

	void uploadUris(final ArrayList<Uri> uris, final String dir) {
		final TeraClient c = client;
		if (c == null)
			return;
		final JobStatus st = inAppJob("Uploading", true);
		execDl.execute(new Runnable() {
			public void run() {
				int ok = 0;
				String err = null;
				File tmpDir = new File(getCacheDir(), "up/" + System.nanoTime());
				tmpDir.mkdirs();
				try {
					for (int i = 0; i < uris.size(); i++) {
						if (st.cancelled())
							throw new Exception("Cancelled");
						Uri u = uris.get(i);
						String name = displayName(u);
						st.message("(" + (i + 1) + "/" + uris.size() + ")  " + name + "\nPreparing...");
						File local = new File(tmpDir, name);
						InputStream in = getContentResolver().openInputStream(u);
						FileOutputStream fo = new FileOutputStream(local);
						try {
							byte[] buf = new byte[65536];
							int n;
							while ((n = in.read(buf)) > 0)
								fo.write(buf, 0, n);
						} finally {
							fo.close();
							in.close();
						}
						st.message("(" + (i + 1) + "/" + uris.size() + ")  " + name);
						c.uploadFile(local, dir, name, st);
						local.delete();
						ok++;
					}
				} catch (Exception e) {
					err = e.getMessage() == null ? e.toString() : e.getMessage();
				} finally {
					File[] left = tmpDir.listFiles();
					if (left != null)
						for (int i = 0; i < left.length; i++)
							left[i].delete();
					tmpDir.delete();
				}
				st.finish(err, ok + " file(s) uploaded");
				final int fok = ok;
				final String ferr = err;
				runOnUiThread(new Runnable() {
					public void run() {
						cache.clear();
						afterFilesChange.run();
						if (ferr == null)
							toast(fok + " file(s) uploaded");
						else if (ferr.equals("Cancelled"))
							toast("Upload cancelled (" + fok + " done)");
						else
							showErrorResume("Upload stopped after " + fok + " file(s):\n" + ferr, new Runnable() {
								public void run() {
									uploadUris(new ArrayList<Uri>(uris.subList(fok, uris.size())), dir);
								}
							});
					}
				});
			}
		});
	}

	// ---- archives (zip / tar made from TeraBox files, saved back into the current folder)

	void archiveEntries(final ArrayList<TeraEntry> items, final boolean tar) {
		final TeraClient c = client;
		if (c == null || items.isEmpty())
			return;
		final String dir = curDir;
		final JobStatus st = inAppJob(tar ? "Creating TAR archive" : "Creating ZIP archive", true);
		execDl.execute(new Runnable() {
			public void run() {
				String err = null;
				File local = null;
				String finalName = "";
				try {
					local = TeraWork.makeArchive(c, items, tar, new File(getCacheDir(), "arch"), st);
					String stamp = new SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(new Date());
					finalName = (items.size() == 1 ? items.get(0).name : "archive") + "_" + stamp + (tar ? ".tar" : ".zip");
					st.message("Uploading " + finalName);
					c.uploadFile(local, dir, finalName, st);
				} catch (Exception e) {
					err = e.getMessage() == null ? e.toString() : e.getMessage();
				} finally {
					if (local != null)
						local.delete();
				}
				st.finish(err, "Created " + finalName);
				final String ferr = err, fname = finalName;
				runOnUiThread(new Runnable() {
					public void run() {
						cache.clear();
						afterFilesChange.run();
						if (ferr == null)
							toast("Created " + fname);
						else if (ferr.equals("Cancelled"))
							toast("Cancelled");
						else
							showError(ferr);
					}
				});
			}
		});
	}

	// ---- unpack zip / tar files (downloaded, unpacked on the phone, and uploaded back)

	void confirmExtract(final TeraEntry e, final boolean toFolder) {
		String verb = TeraWork.isZipName(e.name) ? "Unzip" : "Untar";
		new DragBuilder(this).setTitle(verb)
				.setMessage("Extract \"" + e.name + "\" into " + parentOf(e.path) + "?\nFiles with the same name are replaced.")
				.setPositiveButton("Extract", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						runExtract(e, toFolder);
					}
				}).setNegativeButton("Cancel", null).show();
	}

	void runExtract(final TeraEntry e, final boolean toFolder) {
		final TeraClient c = client;
		if (c == null)
			return;
		final JobStatus st = inAppJob(TeraWork.isZipName(e.name) ? "Unzipping" : "Untarring", false);
		execDl.execute(new Runnable() {
			public void run() {
				TeraWork.ExtractResult res = null;
				String err = null;
				try {
					res = TeraWork.extract(c, e, toFolder, new File(getCacheDir(), "ex"), st);
				} catch (Exception ex) {
					err = ex.getMessage() == null ? ex.toString() : ex.getMessage();
				}
				st.finish(err, res == null ? null : res.files + " file(s) unpacked");
				final TeraWork.ExtractResult fr = res;
				final String ferr = err;
				runOnUiThread(new Runnable() {
					public void run() {
						cache.clear();
						afterFilesChange.run();
						if (ferr == null) {
							new DragBuilder(MainActivity.this).setTitle("Extracted")
									.setMessage(fr.files + " file(s), " + fr.dirs + " folder(s) into\n" + fr.destDir
											+ (fr.skipped > 0 ? "\n\n" + fr.skipped + " unsafe or unsupported entries were skipped." : ""))
									.setPositiveButton("OK", null).show();
						} else if (ferr.equals("Cancelled")) {
							toast("Cancelled - files already unpacked stay in TeraBox");
						} else {
							showError(ferr);
						}
					}
				});
			}
		});
	}

	// ---- share link

	void createShare(final TeraEntry e) {
		final TeraClient c = client;
		if (c == null)
			return;
		final ProgressDialog pd = new ProgressDialog(this);
		pd.setMessage("Creating link...");
		pd.setIndeterminate(true);
		pd.setCancelable(false);
		pd.show();
		DragBuilder.makeDraggable(pd);
		execDl.execute(new Runnable() {
			public void run() {
				String url = "", err = null;
				try {
					ArrayList<String> paths = new ArrayList<String>();
					paths.add(e.path);
					JSONObject j = c.shareSet(paths);
					int en = TeraClient.errnoOf(j);
					if (en == -6)
						throw new Exception("SESSION");
					if (en != 0)
						throw new Exception("TeraBox could not create the link (errno " + en + ").");
					JSONObject data = j.optJSONObject("data");
					url = TeraShares.urlFrom(data != null && TeraShares.urlFrom(data).length() > 0 ? data : j);
					if (url.length() == 0) {
						ArrayList<TeraShares.Share> all = TeraShares.fetchAll(c, cancelledShares);
						for (int i = 0; i < all.size(); i++)
							if (all.get(i).path.length() > 0 && ("/" + all.get(i).path.replaceAll("^/+", "")).equals(e.path))
								url = all.get(i).url;
					}
					if (url.length() == 0)
						throw new Exception("TeraBox did not return a share link.");
				} catch (Exception ex) {
					err = ex.getMessage() == null ? ex.toString() : ex.getMessage();
				}
				final String furl = url, ferr = err;
				runOnUiThread(new Runnable() {
					public void run() {
						pd.dismiss();
						if (ferr != null) {
							if (ferr.equals("SESSION"))
								signOut("Your session expired. Please sign in again.");
							else
								showError(ferr);
							return;
						}
						showLinkDialog(e.name, furl);
					}
				});
			}
		});
	}

	void showLinkDialog(String name, final String url) {
		new DragBuilder(this).setTitle(name).setMessage(url)
				.setPositiveButton("Copy", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						copyText("link", url);
						toast("Link copied");
					}
				}).setNeutralButton("Share...", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						Intent i = new Intent(Intent.ACTION_SEND);
						i.setType("text/plain");
						i.putExtra(Intent.EXTRA_TEXT, url);
						startActivity(Intent.createChooser(i, "Share link"));
					}
				}).setNegativeButton("Close", null).show();
	}

	// ---- download (files and folders)

	File downloadDir() {
		try {
			File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TeraBox");
			d.mkdirs();
			if (!d.isDirectory())
				return null;
			File t = new File(d, ".w" + System.nanoTime());
			if (t.createNewFile()) {
				t.delete();
				return d;
			}
		} catch (Exception e) {
		}
		return null;
	}

	/** Android 11+: "All files access" (called by name so it compiles on any SDK level) */
	boolean isStorageManager() {
		if (Build.VERSION.SDK_INT < 30)
			return true;
		try {
			return ((Boolean) Environment.class.getMethod("isExternalStorageManager").invoke(null)).booleanValue();
		} catch (Exception e) {
			return true;
		}
	}

	void requestAccess() {
		if (Build.VERSION.SDK_INT >= 30 && !isStorageManager()) {
			try {
				Intent i = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
				i.setData(Uri.parse("package:" + getPackageName()));
				startActivity(i);
			} catch (Exception e) {
				startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
			}
		} else if (Build.VERSION.SDK_INT >= 23) {
			requestPermissions(new String[] { "android.permission.READ_EXTERNAL_STORAGE",
					"android.permission.WRITE_EXTERNAL_STORAGE" }, 7);
		}
	}

	void downloadEntries(final ArrayList<TeraEntry> items) {
		if (items.isEmpty())
			return;
		final File dir = downloadDir();
		if (dir == null) {
			new DragBuilder(this).setTitle("Storage access needed")
					.setMessage("TeraFiles saves downloads to Download/TeraBox. Allow access to files first, then download again.")
					.setPositiveButton("Allow", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface d, int w) {
							requestAccess();
						}
					}).setNegativeButton("Cancel", null).show();
			return;
		}
		final TeraClient c = client;
		final JobStatus st = inAppJob("Downloading", false);
		execDl.execute(new Runnable() {
			public void run() {
				int files = 0;
				String err = null;
				try {
					files = TeraWork.downloadTree(c, items, dir, st);
				} catch (Exception e) {
					err = e.getMessage() == null ? e.toString() : e.getMessage();
				}
				st.finish(err, files + " file(s) saved to " + dir.getAbsolutePath());
				final int ff = files;
				final String ferr = err;
				runOnUiThread(new Runnable() {
					public void run() {
						saveSession(null);
						if (ferr == null)
							new DragBuilder(MainActivity.this).setTitle("Download finished")
									.setMessage(ff + " file(s) saved to\n" + dir.getAbsolutePath()).setPositiveButton("OK", null).show();
						else if (ferr.equals("Cancelled"))
							toast("Download cancelled");
						else
							showErrorResume(ferr, new Runnable() {
								public void run() {
									downloadEntries(items);
								}
							});
					}
				});
			}
		});
	}

	// ------------------------------------------------------------------ settings

	TextView settingsHeader(String t) {
		TextView h = mkText(t, 13, MUTED);
		h.setPadding(dp(20), dp(14), dp(20), dp(2));
		return h;
	}

	/** one row of the settings page: title + optional subtitle on the left, a check / checkbox on the right */
	LinearLayout settingsRow(String title, String sub, View right, View.OnClickListener click) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(20), dp(5), dp(20), dp(5));
		android.util.TypedValue tv = new android.util.TypedValue();
		if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true))
			row.setBackgroundResource(tv.resourceId);
		LinearLayout left = new LinearLayout(this);
		left.setOrientation(LinearLayout.VERTICAL);
		left.addView(mkText(title, 17, TEXT));
		if (sub != null && sub.length() > 0) {
			TextView st = mkText(sub, 14, MUTED);
			st.setPadding(0, dp(1), 0, 0);
			left.addView(st);
		}
		row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
		if (right != null)
			row.addView(right, new LinearLayout.LayoutParams(-2, -2));
		row.setOnClickListener(click);
		return row;
	}

	View checkMark(boolean on) {
		TextView c = mkText("\u2713", 20, 0xFF2D6AA0);
		c.setTypeface(null, android.graphics.Typeface.BOLD);
		c.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
		return c;
	}

	CheckBox settingsBox(boolean on) {
		CheckBox cb = new CheckBox(this);
		cb.setChecked(on);
		cb.setClickable(false);
		cb.setFocusable(false);
		return cb;
	}

	void showSettings() {
		screen = SCREEN_SETTINGS;
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(BG);

		TextView[] settingsTitle = new TextView[1];
		root.addView(makeTopBar("\u2190", settingsTitle, new View.OnClickListener() {
			public void onClick(View v) {
				showFiles();
			}
		}, null, null, null), new LinearLayout.LayoutParams(-1, -2));
		settingsTitle[0].setText("Settings");

		ScrollView sv = new ScrollView(this);
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		sv.addView(box);
		root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

		// ---- theme
		box.addView(settingsHeader("THEME"));
		String[] tn = new String[] { "System default", "Light", "Dark" };
		String[] ts = new String[] { "Follows your device's Light/Dark setting", "Always use the light theme",
				"Always use the dark theme" };
		final int mode = prefs.getInt("theme", 0);
		for (int i = 0; i < tn.length; i++) {
			final int m = i;
			box.addView(settingsRow(tn[i], ts[i], checkMark(i == mode), new View.OnClickListener() {
				public void onClick(View v) {
					if (m == mode)
						return;
					// restart the screen so the whole app (dialogs, bars) picks up the new theme
					prefs.edit().putInt("theme", m).putBoolean("reopenSettings", true).commit();
					recreate();
				}
			}));
		}

		// ---- sort
		box.addView(settingsHeader("SORT"));
		String[] sn = new String[] { "Name", "Date", "Size" };
		int sortBy = prefs.getInt("sortBy", 0);
		for (int i = 0; i < sn.length; i++) {
			final int by = i;
			box.addView(settingsRow(sn[i], null, checkMark(i == sortBy), new View.OnClickListener() {
				public void onClick(View v) {
					prefs.edit().putInt("sortBy", by).commit();
					resortAll();
					showSettings();
				}
			}));
		}
		box.addView(settingsRow("Descending order", null, settingsBox(prefs.getBoolean("sortDesc", false)),
				new View.OnClickListener() {
					public void onClick(View v) {
						prefs.edit().putBoolean("sortDesc", !prefs.getBoolean("sortDesc", false)).commit();
						resortAll();
						showSettings();
					}
				}));

		// ---- general
		box.addView(settingsHeader("GENERAL"));
		box.addView(settingsRow("Confirm before deleting", "Ask before files are moved to the trash",
				settingsBox(prefs.getBoolean("confirmDelete", true)), new View.OnClickListener() {
					public void onClick(View v) {
						prefs.edit().putBoolean("confirmDelete", !prefs.getBoolean("confirmDelete", true)).commit();
						showSettings();
					}
				}));

		box.addView(settingsHeader("ABOUT"));
		TextView about = mkText("Downloads are saved to Download/TeraBox.\nTeraFiles 1.1", 14, MUTED);
		about.setPadding(dp(20), dp(4), dp(20), dp(24));
		box.addView(about);
		setContentView(root);
	}

	void resortAll() {
		sortList(entries);
		for (ArrayList<TeraEntry> l : cache.values())
			sortList(l);
		updateSortHeader();
	}

	// ------------------------------------------------------------------ trash screen

	void showTrash() {
		screen = SCREEN_TRASH;
		trashDir = "";
		trashFid = "";
		trashStack.clear();
		tsel.clear();
		trashEntries = new ArrayList<TeraEntry>();
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(BG);
		TextView[] t = new TextView[1];
		TextView[] mo = new TextView[1];
		root.addView(makeTopBar("\u2190", t, new View.OnClickListener() {
			public void onClick(View v) {
				trashBack();
			}
		}, new View.OnClickListener() {
			public void onClick(View v) {
				loadTrash(trashDir, trashFid);
			}
		}, new View.OnClickListener() {
			public void onClick(View v) {
				PopupMenu pm = newPopup(v);
				boolean any = !selectedTrash().isEmpty();
				if (any) {
					pm.getMenu().add(0, 1, 0, "Restore");
					pm.getMenu().add(0, 2, 1, "Delete forever");
				}
				pm.getMenu().add(0, 4, 3, "Empty trash");
				pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
					public boolean onMenuItemClick(android.view.MenuItem item) {
						switch (item.getItemId()) {
						case 1:
							trashAct("restore");
							break;
						case 2:
							confirmTrashDelete();
							break;
						default:
							confirmEmptyTrash();
						}
						return true;
					}
				});
				showScrollablePopupItems(pm);
			}
		}, mo), new LinearLayout.LayoutParams(-1, -2));
		trashTitle = t[0];
		trashLoad = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		trashLoad.setIndeterminate(true);
		trashLoad.setVisibility(View.INVISIBLE);
		root.addView(trashLoad, new LinearLayout.LayoutParams(-1, dp(3)));
		trashInfo = mkText("0 selected", 12, MUTED);
		trashInfo.setPadding(dp(16), dp(6), dp(16), dp(4));
		root.addView(trashInfo);
		RowHost host = new RowHost() {
			public void onMenu(final TeraEntry e, View anchor) {
				if (e.fsId.length() == 0) {
					toast("This item can't be restored or deleted from here");
					return;
				}
				PopupMenu pm = newPopup(anchor);
				pm.getMenu().add(0, 1, 0, "Restore");
				pm.getMenu().add(0, 2, 1, "Delete forever");
				pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
					public boolean onMenuItemClick(android.view.MenuItem item) {
						tsel.clear();
						tsel.add(e.fsId);
						onTrashSel();
						if (item.getItemId() == 1)
							trashAct("restore");
						else
							confirmTrashDelete();
						return true;
					}
				});
				showScrollablePopupItems(pm);
			}

			public void onSelectionChanged() {
				onTrashSel();
			}
		};
		trashAdapter = new RowAdapter(tsel, true, true, host);
		ListView lv = new ListView(this);
		lv.setDividerHeight(1);
		lv.setDivider(new android.graphics.drawable.ColorDrawable(DIVIDER));
		lv.setAdapter(trashAdapter);
		lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
				// folders no longer open: a tap selects, like a file
				TeraEntry e = trashEntries.get(pos);
				String k = e.fsId.length() > 0 ? e.fsId : e.path;
				if (!tsel.remove(k))
					tsel.add(k);
				onTrashSel();
			}
		});
		root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));
		trashEmpty = mkText("The trash is empty.", 15, MUTED);
		trashEmpty.setGravity(Gravity.CENTER);
		trashEmpty.setPadding(0, dp(30), 0, dp(30));
		trashEmpty.setVisibility(View.GONE);
		root.addView(trashEmpty);
		setContentView(root);
		loadTrash("", "");
	}

	void onTrashSel() {
		if (trashAdapter == null || screen != SCREEN_TRASH)
			return;
		trashInfo.setText(selectedTrash().size() + " selected");
		trashAdapter.notifyDataSetChanged();
	}

	ArrayList<String> selectedTrash() {
		ArrayList<String> r = new ArrayList<String>();
		for (int i = 0; i < trashEntries.size(); i++) {
			TeraEntry e = trashEntries.get(i);
			String k = e.fsId.length() > 0 ? e.fsId : e.path;
			if (tsel.contains(k) && e.fsId.length() > 0)
				r.add(e.fsId);
		}
		return r;
	}

	void trashBack() {
		if (!selectedTrash().isEmpty()) {
			tsel.clear();
			onTrashSel();
			return;
		}
		if (!trashStack.isEmpty()) {
			String[] top = trashStack.remove(trashStack.size() - 1);
			loadTrash(top[0], top[1]);
		} else {
			showFiles();
		}
	}

	void loadTrash(final String dir, final String fid) {
		final TeraClient c = client;
		if (c == null)
			return;
		tsel.clear();
		trashInfo.setText("0 selected");
		trashLoad.setVisibility(View.VISIBLE);
		trashTitle.setText(dir.length() == 0 ? "Trash" : "Trash  " + dir);
		exec.execute(new Runnable() {
			public void run() {
				try {
					JSONArray arr;
					int errno = 0;
					if (dir.length() == 0) {
						JSONObject j = c.recycleList("");
						errno = TeraClient.errnoOf(j);
						if (!j.has("errno") && !j.has("error_code"))
							errno = 0;
						arr = TeraClient.recycleItems(j);
					} else {
						arr = c.recycleListIn(dir, fid);
					}
					final ArrayList<TeraEntry> list = new ArrayList<TeraEntry>();
					for (int i = 0; i < arr.length(); i++)
						list.add(TeraEntry.from(arr.getJSONObject(i)));
					sortList(list);
					final int fe = errno;
					runOnUiThread(new Runnable() {
						public void run() {
							if (screen != SCREEN_TRASH)
								return;
							trashLoad.setVisibility(View.INVISIBLE);
							if (fe == -6) {
								signOut("Your session expired. Please sign in again.");
								return;
							}
							if (fe != 0) {
								toast("Could not read the trash (error " + fe + ")");
								return;
							}
							trashDir = dir;
							trashFid = fid;
							trashEntries = list;
							trashAdapter.setData(list);
							trashTitle.setText(dir.length() == 0 ? "Trash" : "Trash  " + dir);
							trashEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
							if (list.isEmpty() && dir.length() > 0)
								trashEmpty.setText("The contents of this folder are not available.");
							else
								trashEmpty.setText("The trash is empty.");
							onTrashSel();
						}
					});
				} catch (final Exception e) {
					runOnUiThread(new Runnable() {
						public void run() {
							trashLoad.setVisibility(View.INVISIBLE);
							toast("Could not read the trash: " + e.getMessage());
						}
					});
				}
			}
		});
	}

	void trashAct(final String op) {
		final ArrayList<String> ids = selectedTrash();
		if (ids.isEmpty())
			return;
		runJob(op.equals("restore") ? "Restoring..." : "Deleting...", new Job() {
			public String run(TeraClient c) throws Exception {
				return chk(c.recycleAction(op, ids), op.equals("restore") ? "Restore" : "Delete");
			}
		}, new Runnable() {
			public void run() {
				tsel.clear();
				loadTrash(trashDir, trashFid);
				refreshQuota();
			}
		});
	}

	void confirmTrashDelete() {
		int n = selectedTrash().size();
		if (n == 0)
			return;
		new DragBuilder(this).setTitle("Delete forever")
				.setMessage("Permanently delete " + n + " item(s)? This cannot be undone.")
				.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						trashAct("delete");
					}
				}).setNegativeButton("Cancel", null).show();
	}

	void confirmEmptyTrash() {
		new DragBuilder(this).setTitle("Empty trash")
				.setMessage("Permanently delete everything in the trash? This cannot be undone.")
				.setPositiveButton("Empty", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						runJob("Emptying the trash...", new Job() {
							public String run(TeraClient c) throws Exception {
								return chk(c.recycleClear(), "Empty trash");
							}
						}, new Runnable() {
							public void run() {
								trashStack.clear();
								loadTrash("", "");
								refreshQuota();
							}
						});
					}
				}).setNegativeButton("Cancel", null).show();
	}

	// ------------------------------------------------------------------ shared links screen

	void showShares() {
		screen = SCREEN_SHARES;
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(BG);
		TextView[] t = new TextView[1];
		root.addView(makeTopBar("\u2190", t, new View.OnClickListener() {
			public void onClick(View v) {
				showFiles();
			}
		}, new View.OnClickListener() {
			public void onClick(View v) {
				loadShares();
			}
		}, null, null), new LinearLayout.LayoutParams(-1, -2));
		t[0].setText("Shared links");
		shareLoad = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		shareLoad.setIndeterminate(true);
		root.addView(shareLoad, new LinearLayout.LayoutParams(-1, dp(3)));
		shareInfo = mkText("Loading...", 12, MUTED);
		shareInfo.setPadding(dp(16), dp(6), dp(16), dp(2));
		root.addView(shareInfo);
		//CheckBox hideBox = new CheckBox(this);
		//hideBox.setText("Hide inactive links");
		//hideBox.setTextColor(TEXT);
		//hideBox.setChecked(prefs.getBoolean("hideInactive", false));
		//hideBox.setOnClickListener(new View.OnClickListener() {
			//public void onClick(View v) {
				//prefs.edit().putBoolean("hideInactive", ((CheckBox) v).isChecked()).commit();
				//loadShares();
			//}
		//});
		LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
		hl.leftMargin = dp(8);
		//root.addView(hideBox, hl);
		shareList = new ListView(this);
		shareList.setDividerHeight(1);
		shareList.setDivider(new android.graphics.drawable.ColorDrawable(DIVIDER));
		shareAdapter = new ShareAdapter();
		shareList.setAdapter(shareAdapter);
		shareList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
				showShareDialog(shares.get(pos));
			}
		});
		root.addView(shareList, new LinearLayout.LayoutParams(-1, 0, 1));
		setContentView(root);
		loadShares();
	}

	class ShareAdapter extends BaseAdapter {
		public int getCount() {
			return shares.size();
		}

		public Object getItem(int i) {
			return shares.get(i);
		}

		public long getItemId(int i) {
			return i;
		}

		public View getView(int pos, View cv, ViewGroup parent) {
			LinearLayout col;
			TextView n, u;
			if (cv == null) {
				col = new LinearLayout(MainActivity.this);
				col.setOrientation(LinearLayout.VERTICAL);
				col.setPadding(dp(16), dp(10), dp(16), dp(10));
				n = mkText("", 16, TEXT);
				n.setMaxLines(2);
				n.setEllipsize(TextUtils.TruncateAt.END);
				u = mkText("", 12, MUTED);
				u.setSingleLine(true);
				u.setEllipsize(TextUtils.TruncateAt.MIDDLE);
				col.addView(n);
				col.addView(u);
				col.setTag(new TextView[] { n, u });
			} else {
				col = (LinearLayout) cv;
			}
			TextView[] h = (TextView[]) col.getTag();
			TeraShares.Share s = shares.get(pos);
			boolean dead = s.state.equals("cancelled");
			h[0].setText(s.name);
			setIcon(h[0], s.isDir ? "folder" : "file", s.isDir ? ACCENT : TEXT);
			h[0].setTextColor(dead ? MUTED : TEXT);
			h[1].setText((dead ? "Inactive (stopped or expired)   " : s.state.equals("active") ? "Active   " : "Status unknown   ") + s.url);
			h[1].setTextColor(dead ? ERR : s.state.equals("active") ? OK : MUTED);
			return col;
		}
	}

	boolean shareCacheLoaded, warmedUp;

	/** fetch the page token in the background so the first visit to Shared links does not have to */
	void warmUp() {
		if (warmedUp || client == null)
			return;
		warmedUp = true;
		final TeraClient c = client;
		exec.execute(new Runnable() {
			public void run() {
				try {
					c.needToken();
				} catch (Exception e) {
				}
			}
		});
	}

	void loadShares() {
		final TeraClient c = client;
		if (c == null)
			return;
		if (!shareCacheLoaded) {
			shareCacheLoaded = true;
			TeraShares.importCache(prefs.getString("shareCache", ""));
		}
		final int generation = ++shareLoadGeneration;
		shareLoad.setVisibility(View.VISIBLE);
		// Keep the previous results visible during refresh without a persistent Loading row.
		shareInfo.setText(shares.isEmpty() ? "Checking active shared links..." : shares.size() + " active shared link(s). Tap one for options.");
		execDl.execute(new Runnable() {
			public void run() {
				String err = null;
				try {
					TeraShares.load(c, cancelledShares, new TeraShares.Listener() {
						public void update(final ArrayList<TeraShares.Share> active, final int pending, final boolean done) {
							runOnUiThread(new Runnable() {
								public void run() {
									if (screen != SCREEN_SHARES || generation != shareLoadGeneration)
										return;
									shares = active;
									shareAdapter.notifyDataSetChanged();
									if (done) {
										shareLoad.setVisibility(View.INVISIBLE);
										shareInfo.setText(active.isEmpty() ? "No active shared links found."
												: active.size() + " active shared link(s). Tap one for options.");
									} else {
										shareInfo.setText(active.size() + " active shared link(s), checking " + pending + " more...");
									}
								}
							});
						}
					});
				} catch (Exception e) {
					err = e.getMessage() == null ? e.toString() : e.getMessage();
				}
				final String ferr = err;
				final String saved = TeraShares.exportCache();
				runOnUiThread(new Runnable() {
					public void run() {
						prefs.edit().putString("shareCache", saved).commit();
						if (screen != SCREEN_SHARES || generation != shareLoadGeneration)
							return;
						shareLoad.setVisibility(View.INVISIBLE);
						if (ferr != null) {
							if (ferr.equals("SESSION")) {
								signOut("Your session expired. Please sign in again.");
								return;
							}
							shareInfo.setText(ferr);
						}
					}
				});
			}
		});
	}

	void showShareDialog(final TeraShares.Share s) {
		new DragBuilder(this).setTitle(s.name).setMessage(s.url)
				.setPositiveButton("Copy link", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						copyText("link", s.url);
						toast("Link copied");
					}
}).setNegativeButton(s.state.equals("cancelled") ? "Close" : "Stop sharing", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						if (s.state.equals("cancelled"))
							return;
						runJob("Stopping the share...", new Job() {
							public String run(TeraClient c) throws Exception {
								ArrayList<String> ids = new ArrayList<String>();
								ids.add(s.id);
								String r = chk(c.shareCancel(ids), "Stop sharing");
								if (r == null) {
									cancelledShares.add(s.id);
									TeraShares.remember(TeraShares.codeOf(s.url), "cancelled");
								}
								return r;
							}
						}, new Runnable() {
							public void run() {
								loadShares();
							}
						});
					}
				}).show();
	}
}
