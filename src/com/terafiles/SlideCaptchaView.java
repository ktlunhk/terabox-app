package com.terafiles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** TeraBox drag-and-drop captcha: drag the puzzle piece into its gap, then release. */
public class SlideCaptchaView extends View {

	public interface Listener {
		void onDropped(int deltaX, int deltaY);
	}

	Bitmap master, tile;
	int mw, mh, tw, th, tx, ty;
	float left, top;
	Listener listener;
	boolean interactive = true;
	Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
	Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	float downX, downY, startLeft, startTop;
	boolean moved;

	public SlideCaptchaView(Context c) {
		super(c);
		stroke.setStyle(Paint.Style.STROKE);
		stroke.setColor(Color.WHITE);
		stroke.setStrokeWidth(3);
		text.setColor(Color.GRAY);
		text.setTextSize(36);
		text.setTextAlign(Paint.Align.CENTER);
	}

	public void setListener(Listener l) {
		listener = l;
	}

	public void setInteractive(boolean b) {
		interactive = b;
	}

	public void clear() {
		master = null;
		tile = null;
		mw = 0;
		mh = 0;
		requestLayout();
		invalidate();
	}

	public void setChallenge(Bitmap master, Bitmap tile, int mw, int mh, int tw, int th, int tx, int ty) {
		this.master = master;
		this.tile = tile;
		this.mw = mw;
		this.mh = mh;
		this.tw = tw;
		this.th = th;
		this.tx = tx;
		this.ty = ty;
		this.left = tx;
		this.top = ty;
		requestLayout();
		invalidate();
	}

	protected void onMeasure(int wSpec, int hSpec) {
		int w = MeasureSpec.getSize(wSpec);
		int h = mw > 0 ? Math.round(w * (float) mh / mw) : Math.round(w * 0.45f);
		setMeasuredDimension(w, h);
	}

	protected void onDraw(Canvas c) {
		if (master == null || mw <= 0) {
			c.drawColor(0xFF2A2F38);
			c.drawText("Loading challenge...", getWidth() / 2f, getHeight() / 2f, text);
			return;
		}
		float s = getWidth() / (float) mw;
		c.drawBitmap(master, null, new RectF(0, 0, getWidth(), getHeight()), paint);
		if (tile != null) {
			RectF r = new RectF(left * s, top * s, (left + tw) * s, (top + th) * s);
			c.drawBitmap(tile, null, r, paint);
			c.drawRect(r, stroke);
		}
	}

	public boolean onTouchEvent(MotionEvent e) {
		if (!interactive || master == null || mw <= 0)
			return false;
		float s = getWidth() / (float) mw;
		switch (e.getActionMasked()) {
		case MotionEvent.ACTION_DOWN:
			if (getParent() != null)
				getParent().requestDisallowInterceptTouchEvent(true);
			downX = e.getX();
			downY = e.getY();
			startLeft = left;
			startTop = top;
			moved = false;
			return true;
		case MotionEvent.ACTION_MOVE:
			float nx = startLeft + (e.getX() - downX) / s;
			float ny = startTop + (e.getY() - downY) / s;
			left = Math.max(0, Math.min(mw - tw, nx));
			top = Math.max(0, Math.min(mh - th, ny));
			if (Math.hypot(e.getX() - downX, e.getY() - downY) >= 2)
				moved = true;
			invalidate();
			return true;
		case MotionEvent.ACTION_UP:
			if (moved && listener != null)
				listener.onDropped(Math.round(left - tx), Math.round(top - ty));
			return true;
		case MotionEvent.ACTION_CANCEL:
			return true;
		}
		return true;
	}
}
