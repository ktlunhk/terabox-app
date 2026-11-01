package com.terafiles;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared UI helpers: every screen (file list, editor, preview) builds its bars, buttons and text fields here. No AndroidX, no lambdas. */
final class UI {
	private UI() {
	}

	static int dp(Context c, int v) {
		return v * Math.max(1, Math.round(c.getResources().getDisplayMetrics().density));
	}

	static TextView text(Context c, String s, int sp, int color) {
		TextView t = new TextView(c);
		t.setText(s);
		t.setTextSize(sp);
		t.setTextColor(color);
		return t;
	}

	/** primary rounded button */
	static Button button(Context c, String s) {
		Button bt = new Button(c);
		bt.setText(s);
		bt.setAllCaps(false);
		bt.setTextColor(Color.WHITE);
		bt.setTextSize(16);
		GradientDrawable g = new GradientDrawable();
		g.setColor(MainActivity.ACCENT);
		g.setCornerRadius(dp(c, 10));
		bt.setBackground(g);
		return bt;
	}

	/** compact rounded button */
	static Button smallButton(Context c, String s, int color) {
		Button bt = button(c, s);
		bt.setTextSize(14);
		bt.setMinWidth(0);
		bt.setMinimumWidth(0);
		bt.setMinHeight(0);
		bt.setMinimumHeight(0);
		bt.setPadding(dp(c, 18), 0, dp(c, 18), 0);
		GradientDrawable g = new GradientDrawable();
		g.setColor(color);
		g.setCornerRadius(dp(c, 8));
		bt.setBackground(g);
		return bt;
	}

	/** a text glyph button (back / refresh / more) filling the title bar row */
	static TextView barGlyph(Context c, String glyph, int padLeft, int padRight) {
		TextView t = text(c, glyph, 22, MainActivity.TEXT);
		t.setGravity(Gravity.CENTER);
		t.setPadding(dp(c, padLeft), 0, dp(c, padRight), 0);
		t.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(c, MainActivity.BAR_ROW_DP)));
		return t;
	}

	/** layout params every title bar uses (same height on every screen) */
	static LinearLayout.LayoutParams barParams(Context c) {
		return new LinearLayout.LayoutParams(-1, dp(c, MainActivity.BAR_TOP_DP + MainActivity.BAR_ROW_DP));
	}

	/** the one title bar every screen uses: [left glyph] title [refresh] [more]; refresh / more are optional */
	static LinearLayout titleBar(Context c, String glyph, TextView[] titleOut, View.OnClickListener left,
			View.OnClickListener refresh, View.OnClickListener more, TextView[] moreOut, TextUtils.TruncateAt ellipsize) {
		LinearLayout bar = new LinearLayout(c);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setBackgroundColor(MainActivity.PANEL);
		bar.setPadding(0, dp(c, MainActivity.BAR_TOP_DP), 0, 0);
		bar.setMinimumHeight(dp(c, MainActivity.BAR_TOP_DP + MainActivity.BAR_ROW_DP));
		TextView g = barGlyph(c, glyph, 16, 12);
		g.setOnClickListener(left);
		bar.addView(g);
		TextView t = text(c, "", 17, MainActivity.TEXT);
		t.setSingleLine(true);
		t.setEllipsize(ellipsize);
		t.setGravity(Gravity.CENTER_VERTICAL);
		bar.addView(t, new LinearLayout.LayoutParams(0, dp(c, MainActivity.BAR_ROW_DP), 1));
		titleOut[0] = t;
		if (refresh != null) {
			TextView rf = barGlyph(c, "\u21BB", 12, 12);
			rf.setOnClickListener(refresh);
			bar.addView(rf);
		}
		if (more != null) {
			TextView mb = barGlyph(c, "\u22EE", 12, 16);
			mb.setOnClickListener(more);
			bar.addView(mb);
			if (moreOut != null)
				moreOut[0] = mb;
		}
		return bar;
	}

	/** title bar with just a back glyph and a fixed title (editor, preview) */
	static LinearLayout titleBar(Context c, String glyph, String title, View.OnClickListener back, TextUtils.TruncateAt ellipsize) {
		TextView[] t = new TextView[1];
		LinearLayout bar = titleBar(c, glyph, t, back, null, null, null, ellipsize);
		t[0].setText(title);
		return bar;
	}

	/** a flat text button in a tool row (editor: Undo, Redo, Save ...) */
	static TextView toolButton(Context c, String label, int color) {
		TextView b = text(c, label, 13, color);
		b.setGravity(Gravity.CENTER);
		b.setSingleLine(true);
		b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
		return b;
	}

	/** compact single-line field for a search bar */
	static void searchEdit(EditText e, String hint) {
		e.setHint(hint);
		e.setTextSize(14);
		e.setTextColor(MainActivity.TEXT);
		e.setHintTextColor(MainActivity.MUTED);
		e.setSingleLine(true);
	}

	/** same text style for every text field inside a dialog */
	static void dialogEdit(EditText e) {
		e.setTextSize(16);
		e.setTextColor(MainActivity.TEXT);
		e.setHintTextColor(MainActivity.MUTED);
	}

	static void styleEdit(EditText e, String hint) {
		e.setHint(hint);
		e.setHintTextColor(MainActivity.MUTED);
		e.setTextColor(MainActivity.TEXT);
		e.setSingleLine(true);
		e.setTextSize(16);
	}
}
