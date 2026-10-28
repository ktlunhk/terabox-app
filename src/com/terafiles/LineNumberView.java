package com.terafiles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.TextView;

/**
 * Gutter that draws ONLY the rows currently visible, using the text view's own Layout for
 * positions (same idea as the text editor). Wrapped continuation rows get no number, and
 * zooming never needs a giant gutter string to be rebuilt.
 */
public class LineNumberView extends TextView {
	private PreviewTextView target;
	private int colorNormal = 0xFF888888;
	private int colorCurrent = 0xFF7B1FA2;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final char[] buf = new char[4096];
	private int anchorOff = 0, anchorNl = 0;
	private GestureDetector tapDetector;
	private int totalLines = 1;

	public LineNumberView(Context context) {
		super(context);
		tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
			public boolean onDown(MotionEvent e) {
				return true;
			}

			public boolean onDoubleTap(MotionEvent e) {
				if (target != null) {
					if (e.getY() < getHeight() / 2f) target.jumpToTop();
					else target.jumpToEnd();
				}
				return true;
			}
		});
	}

	public boolean onTouchEvent(MotionEvent ev) {
		tapDetector.onTouchEvent(ev);
		return true;
	}

	public void attach(PreviewTextView t) {
		target = t;
	}

	public void setNumberColor(int c) {
		colorNormal = c;
	}

	/** colour (drawn bold) of the number of the line that holds the cursor */
	public void setCurrentColor(int c) {
		colorCurrent = c;
	}

	/** Counts lines once for the loaded text and sizes the gutter to fit the widest number. */
	public void setTotalLines(int n) {
		totalLines = Math.max(1, n);
		anchorOff = 0;
		anchorNl = 0;
		updateWidth();
	}

	public void updateWidth() {
		paint.setTypeface(getTypeface());
		paint.setTextSize(getTextSize());
		int w = (int) Math.ceil(paint.measureText(String.valueOf(totalLines))) + getPaddingLeft() + getPaddingRight();
		if (getLayoutParams() != null && getLayoutParams().width != w) {
			getLayoutParams().width = w;
			requestLayout();
		}
	}

	private int countNewlines(CharSequence t, int from, int to) {
		int n = 0, i = from;
		while (i < to) {
			int e = Math.min(to, i + buf.length);
			TextUtils.getChars(t, i, e, buf, 0);
			for (int k = 0; k < e - i; k++)
				if (buf[k] == '\n') n++;
			i = e;
		}
		return n;
	}

	protected void onDraw(Canvas canvas) {
		if (target == null) return;
		Layout l = target.getLayout();
		if (l == null || l.getLineCount() == 0) return;
		CharSequence text = target.getText();
		int textLen = text.length();
		int scrollY = target.getScrollY();
		int padTop = target.getExtendedPaddingTop();
		int first = l.getLineForVertical(Math.max(0, scrollY - padTop));
		int last = Math.min(l.getLineCount() - 1, l.getLineForVertical(Math.max(0, scrollY - padTop + getHeight())));

		int ls = Math.min(l.getLineStart(first), textLen);
		int nl = ls >= anchorOff ? anchorNl + countNewlines(text, anchorOff, ls)
				: anchorNl - countNewlines(text, ls, Math.min(anchorOff, textLen));
		anchorOff = ls;
		anchorNl = nl;

		// visual row where the logical line of the cursor begins
		int sel = target.getSelectionStart();
		int curStartRow = -1;
		if (sel >= 0 && sel <= textLen) {
			int cv = l.getLineForOffset(sel);
			while (cv > 0) {
				int s = l.getLineStart(cv);
				if (s <= 0 || s > textLen || text.charAt(s - 1) == '\n') break;
				cv--;
			}
			curStartRow = cv;
		}

		paint.setTypeface(getTypeface());
		paint.setTextSize(getTextSize());
		paint.setTextAlign(Paint.Align.RIGHT);
		float x = getWidth() - getPaddingRight();
		int cur = nl + 1;
		for (int v = first; v <= last; v++) {
			int s = l.getLineStart(v);
			boolean start = s <= 0 || (s <= textLen && text.charAt(s - 1) == '\n');
			if (v > first && start) cur++;
			if (!start) continue;
			boolean isCur = v == curStartRow;
			paint.setColor(isCur ? colorCurrent : colorNormal);
			paint.setFakeBoldText(isCur);
			canvas.drawText(String.valueOf(cur), x, padTop + l.getLineBaseline(v) - scrollY, paint);
		}
	}
}
