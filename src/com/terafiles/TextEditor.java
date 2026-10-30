package com.terafiles;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

/**
 * Full-screen text editor for a TeraBox text file (txt, html, js, java ...).
 * The file is downloaded to the cache, edited here with the FileManager editor
 * views (syntax colours, line numbers, block guides, pinch zoom) and uploaded
 * back over the original when saved.
 */
class TextEditor {
	static final long MAX_BYTES = 2L * 1024L * 1024L;
	static final int MAX_HITS = 5000;

	/** file types the editor opens */
	static boolean isEditableName(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0)
			return false;
		String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.US);
		return ext.matches("txt|md|markdown|java|kt|kts|py|js|jsx|ts|tsx|json|xml|html|htm|css"
				+ "|c|cpp|h|hpp|sh|bash|gradle|properties|ini|yaml|yml|log|conf|cfg|toml|rs|go|rb|php|sql|csv");
	}

	final MainActivity a;
	final File local; // cached copy being edited
	final TeraEntry entry; // the TeraBox file
	final String remoteDir;
	String content;
	boolean dirty, loading, saved, wrap;
	static final int MAX_HISTORY = 10000;
	static class Change {
		int start;
		String before, after;
		Change(int start, String before, String after) {
			this.start = start; this.before = before; this.after = after;
		}
	}
	final ArrayList<Change> undoHistory = new ArrayList<Change>();
	final ArrayList<Change> redoHistory = new ArrayList<Change>();
	boolean applyingHistory;
	int changeStart;
	String changeBefore;
	TextView undoBtn, redoBtn;

	void refreshHistoryButtons() {
		if (undoBtn != null) {
			undoBtn.setEnabled(!undoHistory.isEmpty());
			undoBtn.setAlpha(undoHistory.isEmpty() ? 0.4f : 1f);
		}
		if (redoBtn != null) {
			redoBtn.setEnabled(!redoHistory.isEmpty());
			redoBtn.setAlpha(redoHistory.isEmpty() ? 0.4f : 1f);
		}
	}

	void applyHistory(boolean undo) {
		ArrayList<Change> source = undo ? undoHistory : redoHistory;
		if (source.isEmpty() || tv == null) return;
		Change c = source.remove(source.size() - 1);
		Editable e = tv.getText();
		String expected = undo ? c.after : c.before;
		String replacement = undo ? c.before : c.after;
		if (c.start < 0 || c.start + expected.length() > e.length()
				|| !e.subSequence(c.start, c.start + expected.length()).toString().equals(expected)) {
			source.add(c);
			refreshHistoryButtons();
			return;
		}
		applyingHistory = true;
		try {
			e.replace(c.start, c.start + expected.length(), replacement);
			tv.setSelection(Math.min(e.length(), c.start + replacement.length()));
			(undo ? redoHistory : undoHistory).add(c);
		} finally {
			applyingHistory = false;
			refreshHistoryButtons();
		}
	}


	PreviewTextView tv;
	LineNumberView lineNums;
	TextView wrapBtn, countTv, saveBtn;
	LinearLayout searchBar;
	EditText searchBox;
	final ArrayList<Integer> hits = new ArrayList<Integer>();
	final ArrayList<Object> hitSpans = new ArrayList<Object>();
	Object curSpan;
	int hitLen, cur = -1;
	boolean capped;

	TextEditor(MainActivity a, File local, TeraEntry entry, String content) {
		this.a = a;
		this.local = local;
		this.entry = entry;
		this.content = content;
		int slash = entry.path.lastIndexOf('/');
		String d = slash > 0 ? entry.path.substring(0, slash) : "/";
		this.remoteDir = d;
	}

	TextView toolBtn(String label) {
		TextView b = new TextView(a);
		b.setText(label);
		b.setTextSize(13);
		b.setTextColor(MainActivity.TEXT);
		b.setGravity(Gravity.CENTER);
		b.setSingleLine(true);
		b.setPadding(a.dp(14), 0, a.dp(14), 0);
		return b;
	}

	void show() {
		int lineCount = 1;
		for (int i = 0; i < content.length(); i++)
			if (content.charAt(i) == '\n')
				lineCount++;

		tv = new PreviewTextView(a);
		tv.setText(content, TextView.BufferType.SPANNABLE);
		tv.setTypeface(Typeface.MONOSPACE);
		tv.setBaseSp(12f);
		tv.setTextColor(MainActivity.TEXT);
		tv.setBackgroundColor(MainActivity.BG);
		tv.setGravity(Gravity.TOP | Gravity.LEFT);
		tv.setPadding(a.dp(10), a.dp(10), a.dp(14), a.dp(10));
		tv.setEditable(true);

		lineNums = new LineNumberView(a);
		lineNums.setTypeface(Typeface.MONOSPACE);
		lineNums.setTextSize(12);
		lineNums.setNumberColor(MainActivity.MUTED);
		lineNums.setCurrentColor(Color.rgb(187, 134, 252));
		lineNums.setBackgroundColor(MainActivity.PANEL);
		lineNums.setPadding(a.dp(10), a.dp(10), a.dp(8), a.dp(10));
		lineNums.attach(tv);
		tv.configureGuides("html".equals(EditorHighlighter.detectLanguage(entry.name)), true, 4);

		View divider = new View(a);
		divider.setBackgroundColor(0xFF3C4048);

		tv.setViewListener(new PreviewTextView.Listener() {
			public void onViewScrolled() {
				lineNums.invalidate();
			}
		});
		tv.setZoomHook(new Runnable() {
			public void run() {
				lineNums.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize());
				lineNums.updateWidth();
				lineNums.invalidate();
			}
		});

		LinearLayout zoomWrap = new LinearLayout(a);
		zoomWrap.setOrientation(LinearLayout.HORIZONTAL);
		zoomWrap.addView(lineNums, new LinearLayout.LayoutParams(a.dp(40), -1));
		zoomWrap.addView(divider, new LinearLayout.LayoutParams(a.dp(1), -1));
		zoomWrap.addView(tv, new LinearLayout.LayoutParams(0, -1, 1));
		lineNums.setTotalLines(lineCount);

		// ---- title bar ----
		LinearLayout bar = new LinearLayout(a);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setBackgroundColor(Color.rgb(45, 105, 160));
		TextView back = new TextView(a);
		back.setText("\u2190");
		back.setTextColor(Color.WHITE);
		back.setTextSize(20);
		back.setGravity(Gravity.CENTER);
		back.setPadding(a.dp(16), 0, a.dp(16), 0);
		back.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				onBack();
			}
		});
		bar.addView(back, new LinearLayout.LayoutParams(-2, -1));
		TextView titleTv = new TextView(a);
		titleTv.setText(entry.name);
		titleTv.setTextSize(16);
		titleTv.setTextColor(Color.WHITE);
		titleTv.setSingleLine(true);
		titleTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
		titleTv.setGravity(Gravity.CENTER_VERTICAL);
		bar.addView(titleTv, new LinearLayout.LayoutParams(0, -1, 1));
		saveBtn = new TextView(a);
		saveBtn.setText("Save");
		saveBtn.setTextColor(Color.WHITE);
		saveBtn.setTextSize(15);
		saveBtn.setGravity(Gravity.CENTER);
		saveBtn.setPadding(a.dp(18), 0, a.dp(18), 0);
		saveBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				save(null);
			}
		});
		bar.addView(saveBtn, new LinearLayout.LayoutParams(-2, -1));

		// ---- tool row ----
		LinearLayout tools = new LinearLayout(a);
		tools.setOrientation(LinearLayout.HORIZONTAL);
		wrapBtn = toolBtn("No wrap");
		TextView searchBtn = toolBtn("Search");
		TextView goBtn = toolBtn("Go");
		TextView copyBtn = toolBtn("Copy");
		TextView pasteBtn = toolBtn("Paste");
		undoBtn = toolBtn("Undo");
		redoBtn = toolBtn("Redo");
		tools.addView(undoBtn, new LinearLayout.LayoutParams(-2, -1));
		tools.addView(redoBtn, new LinearLayout.LayoutParams(-2, -1));
		undoBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) { applyHistory(true); }
		});
		redoBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) { applyHistory(false); }
		});
		refreshHistoryButtons();
		tools.addView(wrapBtn, new LinearLayout.LayoutParams(-2, -1));
		tools.addView(searchBtn, new LinearLayout.LayoutParams(-2, -1));
		tools.addView(goBtn, new LinearLayout.LayoutParams(-2, -1));
		tools.addView(copyBtn, new LinearLayout.LayoutParams(-2, -1));
		tools.addView(pasteBtn, new LinearLayout.LayoutParams(-2, -1));
		HorizontalScrollView toolsScroll = new HorizontalScrollView(a);
		toolsScroll.setFillViewport(true);
		toolsScroll.setBackgroundColor(MainActivity.PANEL);
		toolsScroll.setHorizontalScrollBarEnabled(false);
		toolsScroll.addView(tools, new HorizontalScrollView.LayoutParams(-2, -1));

		wrapBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				setWrap(!wrap);
			}
		});
		searchBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (searchBar.getVisibility() == View.VISIBLE)
					closeSearch();
				else
					openSearch();
			}
		});
		goBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showGoToLine();
			}
		});
		copyBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				copyText();
			}
		});
		pasteBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				pasteText();
			}
		});

		// ---- search bar ----
		searchBar = new LinearLayout(a);
		searchBar.setOrientation(LinearLayout.HORIZONTAL);
		searchBar.setGravity(Gravity.CENTER_VERTICAL);
		searchBar.setBackgroundColor(MainActivity.PANEL);
		searchBar.setPadding(a.dp(10), 0, 0, 0);
		searchBar.setVisibility(View.GONE);
		searchBox = new EditText(a);
		searchBox.setHint("Find in text");
		searchBox.setTextSize(14);
		searchBox.setTextColor(MainActivity.TEXT);
		searchBox.setHintTextColor(MainActivity.MUTED);
		searchBox.setSingleLine(true);
		searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		searchBar.addView(searchBox, new LinearLayout.LayoutParams(0, -2, 1));
		countTv = new TextView(a);
		countTv.setTextSize(12);
		countTv.setTextColor(MainActivity.MUTED);
		countTv.setPadding(a.dp(8), 0, a.dp(4), 0);
		searchBar.addView(countTv, new LinearLayout.LayoutParams(-2, -2));
		TextView prev = toolBtn("\u25B2");
		TextView next = toolBtn("\u25BC");
		TextView close = toolBtn("\u2715");
		searchBar.addView(prev, new LinearLayout.LayoutParams(-2, a.dp(44)));
		searchBar.addView(next, new LinearLayout.LayoutParams(-2, a.dp(44)));
		searchBar.addView(close, new LinearLayout.LayoutParams(-2, a.dp(44)));
		prev.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				step(-1);
			}
		});
		next.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				step(1);
			}
		});
		close.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				closeSearch();
			}
		});
		searchBox.addTextChangedListener(new TextWatcher() {
			public void beforeTextChanged(CharSequence s, int x, int y, int z) {
			}

			public void onTextChanged(CharSequence s, int x, int y, int z) {
			}

			public void afterTextChanged(Editable e) {
				updateHits(true);
			}
		});
		searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
			public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
				step(1);
				return true;
			}
		});

		LinearLayout screen = new LinearLayout(a);
		screen.setOrientation(LinearLayout.VERTICAL);
		screen.setBackgroundColor(MainActivity.BG);
		screen.addView(bar, new LinearLayout.LayoutParams(-1, a.dp(52)));
		screen.addView(toolsScroll, new LinearLayout.LayoutParams(-1, a.dp(40)));
		screen.addView(searchBar, new LinearLayout.LayoutParams(-1, -2));
		screen.addView(zoomWrap, new LinearLayout.LayoutParams(-1, 0, 1));

		tv.addTextChangedListener(new TextWatcher() {
			public void beforeTextChanged(CharSequence s, int x, int y, int z) {
				if (!loading && !applyingHistory) {
					changeStart = x;
					changeBefore = s.subSequence(x, x + y).toString();
				}
			}

			public void onTextChanged(CharSequence s, int x, int y, int z) {
				if (!loading && !applyingHistory && (y != 0 || z != 0)) {
					undoHistory.add(new Change(changeStart, changeBefore,
							s.subSequence(x, x + z).toString()));
					if (undoHistory.size() > MAX_HISTORY) undoHistory.remove(0);
					redoHistory.clear();
					refreshHistoryButtons();
				}
				if (!loading) {
					dirty = true;
					updateTitleMark();
				}
			}

			public void afterTextChanged(Editable e) {
			}
		});

		setWrap(false);
		a.screen = MainActivity.SCREEN_EDIT;
		a.editor = this;
		a.setContentView(screen);
		tv.requestFocus();

		// colour in the background so the file opens instantly
		final String text = content;
		new Thread(new Runnable() {
			public void run() {
				SpannableStringBuilder styled = null;
				try {
					styled = EditorHighlighter.styled(text, entry.name, MainActivity.darkMode);
					if (styled == null) {
						String n = entry.name;
						int dot = n.lastIndexOf('.');
						styled = new SyntaxHighlighter(MainActivity.darkMode).highlight(text, dot >= 0 ? n.substring(dot + 1) : "");
					}
				} catch (Throwable t) {
					styled = null; // colours are cosmetic
				}
				final SpannableStringBuilder fs = styled;
				if (fs == null)
					return;
				a.runOnUiThread(new Runnable() {
					public void run() {
						if (a.editor != TextEditor.this || dirty)
							return;
						int ka = tv.getSelectionStart(), kb = tv.getSelectionEnd();
						loading = true;
						tv.setText(fs, TextView.BufferType.SPANNABLE);
						loading = false;
						if (ka > 0 && kb >= 0 && ka <= fs.length() && kb <= fs.length()) {
							try {
								android.text.Selection.setSelection((Spannable) tv.getText(), ka, kb);
							} catch (RuntimeException e) {
							}
						}
						hitSpans.clear();
						curSpan = null;
						if (searchBar.getVisibility() == View.VISIBLE)
							updateHits(false);
					}
				});
			}
		}).start();
	}

	void updateTitleMark() {
		if (saveBtn != null)
			saveBtn.setText(dirty ? "Save *" : "Save");
	}

	// ------------------------------------------------------------ save / leave

	/** Write the text to the cache copy, upload it over the TeraBox file, then run after. */
	void save(final Runnable after) {
		final TeraClient c = a.client;
		if (c == null) {
			a.toast("Not signed in");
			return;
		}
		hideKeyboard();
		final String text = tv.getText().toString();
		final MainActivity.JobStatus st = a.new JobStatus("Saving " + entry.name);
		a.execDl.execute(new Runnable() {
			public void run() {
				String err = null;
				try {
					OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(local), "UTF-8");
					try {
						out.write(text);
						out.flush();
					} finally {
						try {
							out.close();
						} catch (Exception e) {
						}
					}
					st.message("Uploading " + entry.name);
					c.uploadFile(local, remoteDir, entry.name, st);
				} catch (Exception e) {
					err = e.getMessage() == null ? e.toString() : e.getMessage();
				}
				st.close();
				final String ferr = err;
				a.runOnUiThread(new Runnable() {
					public void run() {
						if (ferr == null) {
							content = text;
							dirty = false;
							saved = true;
							updateTitleMark();
							a.toast("Saved: " + entry.name);
							if (after != null)
								after.run();
						} else if (ferr.equals("Cancelled")) {
							a.toast("Save cancelled");
						} else {
							new DragBuilder(a).setTitle("Save failed").setMessage(ferr)
									.setPositiveButton("OK", null).show();
						}
					}
				});
			}
		});
	}

	/** Back: ask first if there are unsaved changes. */
	void onBack() {
		if (searchBar != null && searchBar.getVisibility() == View.VISIBLE) {
			closeSearch();
			return;
		}
		if (!dirty) {
			exit();
			return;
		}
		new DragBuilder(a).setTitle("Unsaved changes").setMessage("Save changes to " + entry.name + "?")
				.setPositiveButton("Save", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						save(new Runnable() {
							public void run() {
								exit();
							}
						});
					}
				}).setNegativeButton("Discard", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						exit();
					}
				}).setNeutralButton("Cancel", null).show();
	}

	void exit() {
		hideKeyboard();
		a.editor = null;
		local.delete();
		a.showFiles();
		if (saved) {
			a.cache.clear();
			a.afterFilesChange.run();
		}
	}

	// ------------------------------------------------------------ editor tools

	void showGoToLine() {
		final EditText input = new EditText(a);
		input.setHint("Line number");
		input.setSingleLine(true);
		input.setInputType(InputType.TYPE_CLASS_NUMBER);
		input.setTextColor(MainActivity.TEXT);
		input.setHintTextColor(MainActivity.MUTED);
		new DragBuilder(a).setTitle("Go to line").setView(input)
				.setPositiveButton("Go", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						try {
							int line = Integer.parseInt(input.getText().toString());
							if (line < 1)
								line = 1;
							String t = tv.getText().toString();
							int off = 0, curLine = 1;
							while (curLine < line && off < t.length()) {
								int n = t.indexOf('\n', off);
								if (n < 0) {
									off = t.length();
									break;
								}
								off = n + 1;
								curLine++;
							}
							final int pos = off;
							tv.setSelection(Math.min(pos, tv.length()));
							tv.post(new Runnable() {
								public void run() {
									tv.revealOffset(pos);
									lineNums.invalidate();
								}
							});
						} catch (Exception e) {
							a.toast("Enter a valid line number");
						}
					}
				}).setNegativeButton("Cancel", null).show();
	}

	void setWrap(boolean w) {
		wrap = w;
		wrapBtn.setText(w ? "Wrap" : "No wrap");
		tv.setHorizontallyScrolling(!w);
		if (cur >= 0 && cur < hits.size()) {
			tv.post(new Runnable() {
				public void run() {
					tv.revealOffset(hits.get(cur));
				}
			});
		}
	}

	void openSearch() {
		searchBar.setVisibility(View.VISIBLE);
		int s = tv.getSelectionStart(), e = tv.getSelectionEnd();
		if (s >= 0 && e >= 0 && s != e) {
			String all = tv.getText().toString();
			String sel = all.substring(Math.min(s, e), Math.max(s, e));
			if (sel.length() <= 100 && sel.indexOf('\n') < 0) {
				searchBox.setText(sel);
				searchBox.setSelection(sel.length());
			}
		}
		searchBox.requestFocus();
		InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.showSoftInput(searchBox, 0);
		updateHits(false);
	}

	void closeSearch() {
		hideKeyboard();
		clearHitSpans();
		hits.clear();
		cur = -1;
		searchBar.setVisibility(View.GONE);
		tv.requestFocus();
	}

	void hideKeyboard() {
		try {
			InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null)
				imm.hideSoftInputFromWindow(a.getWindow().getDecorView().getWindowToken(), 0);
		} catch (Exception e) {
		}
	}

	void clearHitSpans() {
		CharSequence t = tv.getText();
		if (t instanceof Spannable) {
			Spannable sp = (Spannable) t;
			for (Object o : hitSpans)
				sp.removeSpan(o);
			if (curSpan != null)
				sp.removeSpan(curSpan);
		}
		hitSpans.clear();
		curSpan = null;
	}

	void updateHits(boolean jump) {
		clearHitSpans();
		hits.clear();
		cur = -1;
		capped = false;
		String q = searchBox.getText().toString();
		if (q.length() == 0) {
			countTv.setText("");
			return;
		}
		String all = tv.getText().toString();
		hitLen = q.length();
		int n = all.length() - hitLen;
		for (int i = 0; i <= n; i++) {
			if (all.regionMatches(true, i, q, 0, hitLen)) {
				hits.add(i);
				if (hits.size() >= MAX_HITS) {
					capped = true;
					break;
				}
				i += hitLen - 1;
			}
		}
		if (hits.isEmpty()) {
			countTv.setText("0/0");
			return;
		}
		Spannable sp = (Spannable) tv.getText();
		int hitColor = Color.argb(110, 255, 214, 0);
		for (int h : hits) {
			BackgroundColorSpan b = new BackgroundColorSpan(hitColor);
			sp.setSpan(b, h, h + hitLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			hitSpans.add(b);
		}
		goTo(0, jump);
	}

	void step(int dir) {
		if (hits.isEmpty())
			return;
		goTo((cur + dir + hits.size()) % hits.size(), true);
	}

	void goTo(int idx, boolean scroll) {
		cur = idx;
		Spannable sp = (Spannable) tv.getText();
		if (curSpan != null)
			sp.removeSpan(curSpan);
		curSpan = new BackgroundColorSpan(Color.rgb(255, 143, 0));
		int h = hits.get(idx);
		sp.setSpan(curSpan, h, h + hitLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		countTv.setText((idx + 1) + "/" + hits.size() + (capped ? "+" : ""));
		if (scroll)
			tv.revealOffset(h);
	}

	void copyText() {
		int s = tv.getSelectionStart(), e = tv.getSelectionEnd();
		boolean sel = s >= 0 && e >= 0 && s != e;
		String all = tv.getText().toString();
		String t = sel ? all.substring(Math.min(s, e), Math.max(s, e)) : all;
		try {
			ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
			cm.setPrimaryClip(ClipData.newPlainText(entry.name, t));
			a.toast(sel ? "Copied selection" : "Copied all text");
		} catch (Exception ex) {
			a.toast("Too large to copy - select part of it instead");
		}
	}

	void pasteText() {
		try {
			ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
				a.toast("Clipboard is empty");
				return;
			}
			CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(a);
			if (t == null || t.length() == 0) {
				a.toast("Clipboard is empty");
				return;
			}
			int s = Math.max(0, tv.getSelectionStart()), e = Math.max(0, tv.getSelectionEnd());
			tv.getText().replace(Math.min(s, e), Math.max(s, e), t);
		} catch (Exception ex) {
			a.toast("Can't paste");
		}
	}
}
