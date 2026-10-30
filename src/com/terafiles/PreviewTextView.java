package com.terafiles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.method.KeyListener;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.OverScroller;

/**
 * Read-only, selectable viewer modelled on the text editor's view: it scrolls ITSELF (no
 * ScrollView / HorizontalScrollView), so the gutter can stay glued to it, zoom is a real
 * (throttled) text-size change that keeps the top line in place, and big files fling fast.
 */
public class PreviewTextView extends EditText {
	public interface Listener {
		void onViewScrolled();
	}

	private Listener listener;
	private KeyListener editKeyListener;

	// ---- block / current line highlighting, same look as the text editor ----
	private final IndentGuides guides = new IndentGuides();
	private final Paint lineBgPaint = new Paint();
	private int textVersion; // grows with every text change, so the guides know when to recompute
	private boolean darkTheme = true;
	private int lnVer = -1, lnSel = -1, lnStart, lnEnd; // cached bounds of the line that holds the cursor
	private boolean hScrollEnabled, inTouch, allowX, hDragging, vDecided, vDragging, swallowGesture;
	private float downX, downY, lastX, lastY, scrollYf, dragSpeed;
	private long lastMoveTime;
	private int slop = -1, activePointerId = -1;
	private int cacheLen = -1, cacheLines = -1, cacheWidth = -1, cacheMax = 0;
	private float density = 1f;

	public PreviewTextView(Context c) {
		super(c);
		density = getResources().getDisplayMetrics().density;
		setVerticalScrollBarEnabled(false);
		editKeyListener = getKeyListener();
		setKeyListener(null); // read-only by default: no keyboard, selection still works
		setTextIsSelectable(true);
		scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
			public boolean onScaleBegin(ScaleGestureDetector d) {
				pinching = true;
				return true;
			}

			public boolean onScale(ScaleGestureDetector d) {
				zoomSp = Math.max(MIN_SP, Math.min(MAX_SP, zoomSp * d.getScaleFactor()));
				long now = SystemClock.uptimeMillis();
				if (now - lastZoomApply >= 120 && Math.abs(zoomSp - appliedSp) >= 0.3f) applyZoom();
				return true;
			}

			public void onScaleEnd(ScaleGestureDetector d) {
				pinching = false;
				applyZoom();
			}
		});
	}

	public void setViewListener(Listener l) {
		listener = l;
	}

	/** Switch between the normal read-only viewer and an editable text field. */
	public void setEditable(boolean editable) {
		setKeyListener(editable ? editKeyListener : null);
		setTextIsSelectable(true);
		setCursorVisible(editable);
        // Read-only preview still needs focus to maintain a selection/caret
        // position for the active block and line guides. No KeyListener means
        // the soft keyboard cannot edit the document in View mode.
        setFocusable(true);
        setFocusableInTouchMode(true);
        if (editable) requestFocus();
        invalidate();
	}

	public boolean isEditable() { return getKeyListener() != null; }

	/** Turns the green block bar (and for HTML/XML the tag pair box) on; tagMode = HTML/XML. */
	public void configureGuides(boolean tagMode, boolean dark, int defaultIndent) {
		darkTheme = dark;
		lineBgPaint.setColor(MainActivity.darkMode ? 0xFF2A2A3A : 0xFFDDDFE3); // light grey on the light theme
		guides.configure(true, tagMode, dark, defaultIndent);
		invalidate();
	}

	protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
		super.onTextChanged(text, start, lengthBefore, lengthAfter);
		textVersion++;
	}

	/** a tap moves the (invisible) cursor: the current line, the green block and the gutter number follow it */
	protected void onSelectionChanged(int selStart, int selEnd) {
		super.onSelectionChanged(selStart, selEnd);
		invalidate();
		if (listener != null) listener.onViewScrolled();
	}

	/** shaded background of the line that holds the cursor (all its rows when wrapped) */
	private void drawCurrentLine(Canvas canvas) {
		Layout l = getLayout();
		if (l == null) return;
		CharSequence t = getText();
		int len = t.length();
		int sel = getSelectionStart();
		if (len == 0 || sel < 0) return;
		if (sel > len) sel = len;
		if (lnVer != textVersion || lnSel != sel) {
			lnVer = textVersion;
			lnSel = sel;
			int s = sel;
			while (s > 0 && t.charAt(s - 1) != '\n') s--;
			int e = sel;
			while (e < len && t.charAt(e) != '\n') e++;
			lnStart = s;
			lnEnd = e;
		}
		int r1 = l.getLineForOffset(Math.min(lnStart, len));
		int r2 = l.getLineForOffset(Math.min(lnEnd, len));
		float top = getExtendedPaddingTop() + l.getLineTop(r1);
		float bottom = getExtendedPaddingTop() + l.getLineBottom(r2);
		canvas.drawRect(getScrollX(), top, getScrollX() + getWidth(), bottom, lineBgPaint);
	}

	protected void onScrollChanged(int h, int v, int oh, int ov) {
		super.onScrollChanged(h, v, oh, ov);
		showThumb();
		if (listener != null) listener.onViewScrolled();
	}

	// ---- horizontal panning (wrap off) ----
	public void setHorizontallyScrolling(boolean whether) {
		super.setHorizontallyScrolling(whether);
		hScrollEnabled = whether;
		cacheLen = -1;
		if (!whether && getScrollX() != 0) scrollTo(0, getScrollY());
	}

	private int maxScrollX() {
		if (!hScrollEnabled) return 0;
		Layout l = getLayout();
		if (l == null) return 0;
		int len = getText().length(), lines = l.getLineCount(), w = getWidth();
		if (len == cacheLen && lines == cacheLines && w == cacheWidth) return cacheMax;
		float widest = 0;
		for (int i = 0; i < lines; i++) widest = Math.max(widest, l.getLineWidth(i));
		int max = (int) Math.ceil(widest) + getPaddingLeft() + getPaddingRight() - w;
		cacheLen = len; cacheLines = lines; cacheWidth = w; cacheMax = Math.max(0, max);
		return cacheMax;
	}

	public void scrollTo(int x, int y) {
		if (inTouch && !allowX) x = getScrollX();
		super.scrollTo(x, y);
	}

	private void setHScroll(int x) {
		allowX = true;
		try {
			scrollTo(Math.max(0, Math.min(x, maxScrollX())), getScrollY());
		} finally {
			allowX = false;
		}
	}

	private int maxScrollY() {
		Layout l = getLayout();
		if (l == null) return 0;
		int contentH = l.getHeight() + getCompoundPaddingTop() + getCompoundPaddingBottom();
		return Math.max(0, contentH - getHeight());
	}

	public void jumpToTop() {
		stopFling();
		scrollTo(getScrollX(), 0);
	}

	public void jumpToEnd() {
		stopFling();
		scrollTo(getScrollX(), maxScrollY());
	}

	/** Scroll so the character at offset is about a third from the top (and into view sideways). */
	public void revealOffset(int off) {
		Layout l = getLayout();
		if (l == null) return;
		stopFling();
		int line = l.getLineForOffset(Math.min(off, getText().length()));
		int y = Math.max(0, Math.min(maxScrollY(), l.getLineTop(line) - getHeight() / 3));
		int x = getScrollX();
		if (hScrollEnabled) {
			int cx = (int) l.getPrimaryHorizontal(off) + getCompoundPaddingLeft();
			if (cx < x || cx > x + getWidth() - (int) (40 * density)) x = Math.max(0, Math.min(maxScrollX(), cx - getWidth() / 3));
			else x = getScrollX();
		}
		allowX = true;
		try {
			scrollTo(x, y);
		} finally {
			allowX = false;
		}
	}

	// ---- zoom: real text-size change, throttled, top line kept in place ----
	static final float MIN_SP = 8f, MAX_SP = 32f;
	private final ScaleGestureDetector scaleDetector;
	private float zoomSp = 12f, appliedSp = 12f;
	private long lastZoomApply;
	private boolean pinching, anchoring;
	private int anchorOff = -1;
	private float anchorFrac;
	private Runnable zoomHook;

	public void setBaseSp(float sp) {
		zoomSp = appliedSp = sp;
		setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
	}

	/** Called after each applied zoom step so the gutter can follow. */
	public void setZoomHook(Runnable r) {
		zoomHook = r;
	}

	private void applyZoom() {
		if (Math.abs(zoomSp - appliedSp) < 0.01f) return;
		appliedSp = zoomSp;
		lastZoomApply = SystemClock.uptimeMillis();
		Layout l = getLayout();
		if (l != null && l.getLineCount() > 0) {
			int sy = getScrollY();
			int line = l.getLineForVertical(sy);
			int top = l.getLineTop(line), h = l.getLineBottom(line) - top;
			anchorOff = l.getLineStart(line);
			float f = h > 0 ? (sy - top) / (float) h : 0f;
			anchorFrac = Math.max(0f, Math.min(1f, f));
		} else anchorOff = -1;
		stopFling();
		anchoring = true;
		setTextSize(TypedValue.COMPLEX_UNIT_SP, appliedSp);
		if (zoomHook != null) zoomHook.run();
	}

	public boolean bringPointIntoView(int offset) {
		if (anchoring) return false;
		return super.bringPointIntoView(offset);
	}

	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		super.onLayout(changed, l, t, r, b);
		if (anchorOff >= 0) restoreAnchor();
	}

	private void restoreAnchor() {
		Layout l = getLayout();
		if (l == null || l.getLineCount() == 0) return;
		int line = l.getLineForOffset(Math.min(anchorOff, getText().length()));
		anchorOff = -1;
		int top = l.getLineTop(line), h = l.getLineBottom(line) - top;
		int y = Math.max(0, Math.min(maxScrollY(), top + (int) (anchorFrac * h)));
		cacheLen = -1;
		setHScroll(Math.min(getScrollX(), maxScrollX()));
		scrollTo(getScrollX(), y);
	}

	// ---- thumb ----
	private long thumbVisibleUntil;
	private boolean thumbDragging;
	private float thumbGrabOffset;
	private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF thumbRect = new RectF();

	private void showThumb() {
		thumbVisibleUntil = SystemClock.uptimeMillis() + 1250;
	}

	private float thumbVisibility() {
		if (thumbDragging) return 1f;
		long rem = thumbVisibleUntil - SystemClock.uptimeMillis();
		if (rem <= 0) return 0f;
		return rem >= 350 ? 1f : rem / 350f;
	}

	private float thumbHeight(int max) {
		int vh = getHeight();
		return Math.min(vh, Math.max(48f * density, vh * (float) vh / (max + vh)));
	}

	private float thumbTop(int max, float th) {
		float track = getHeight() - th;
		if (max <= 0 || track <= 0) return 0;
		return track * Math.min(1f, Math.max(0f, getScrollY() / (float) max));
	}

	protected void onDraw(Canvas canvas) {
		drawCurrentLine(canvas); // behind the text
		super.onDraw(canvas);
		anchoring = false;
		// after the text: the guides only sit in the blank indent area, and this way the line shade cannot hide them
		guides.draw(canvas, this, textVersion, density);
		int max = maxScrollY();
		if (max <= 0) return;
		float vis = thumbVisibility();
		if (vis <= 0f) return;
		float h = thumbHeight(max), top = thumbTop(max, h);
		float w = (thumbDragging ? 12f : 5f) * density;
		float right = getScrollX() + getWidth() - 2f * density;
		thumbRect.set(right - w, getScrollY() + top, right, getScrollY() + top + h);
		thumbPaint.setColor(((int) ((thumbDragging ? 0xDD : 0x99) * vis) << 24) | (thumbDragging ? 0xBB86FC : 0xA0A0A0));
		canvas.drawRoundRect(thumbRect, w / 2f, w / 2f, thumbPaint);
		if (vis < 1f) postInvalidateOnAnimation();
		else if (!thumbDragging) postInvalidateDelayed(920);
	}

	private boolean handleThumbTouch(MotionEvent ev) {
		int a = ev.getActionMasked();
		int max = maxScrollY();
		if (a == MotionEvent.ACTION_DOWN) {
			if (ev.getPointerCount() == 1 && max > 0) {
				float h = thumbHeight(max), top = thumbTop(max, h), pad = 16f * density;
				float zone = (thumbVisibility() > 0f ? 36f : 24f) * density;
				if (ev.getX() >= getWidth() - zone && ev.getY() >= top - pad && ev.getY() <= top + h + pad) {
					thumbGrabOffset = ev.getY() - top;
					thumbDragging = true;
					stopFling();
					if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
					invalidate();
					return true;
				}
			}
			return false;
		}
		if (!thumbDragging) return false;
		if (a == MotionEvent.ACTION_MOVE) {
			float track = getHeight() - thumbHeight(max);
			float frac = track > 0 ? (ev.getY() - thumbGrabOffset) / track : 0f;
			scrollTo(getScrollX(), (int) (Math.max(0f, Math.min(1f, frac)) * max));
			return true;
		}
		if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL || a == MotionEvent.ACTION_POINTER_DOWN) {
			thumbDragging = false;
			invalidate();
		}
		return true;
	}

	// ---- fling + drag with size-based gain ----
	private OverScroller flinger;
	private VelocityTracker velocity;

	private void stopFling() {
		if (flinger != null && !flinger.isFinished()) flinger.abortAnimation();
	}

	private boolean isFlinging() {
		return flinger != null && !flinger.isFinished();
	}

	private float maxGain() {
		float screens = (maxScrollY() + getHeight()) / (float) Math.max(1, getHeight());
		return Math.max(1f, Math.min(8f, screens / 10f));
	}

	private float gainForSpeed(float s, float maxGain) {
		float t = Math.max(0f, Math.min(1f, (s - 0.3f) / 1.2f));
		return 1f + (maxGain - 1f) * t;
	}

	public void computeScroll() {
		if (flinger != null && flinger.computeScrollOffset()) {
			scrollTo(getScrollX(), flinger.getCurrY());
			postInvalidateOnAnimation();
		} else super.computeScroll();
	}

	private void cancelSuper(MotionEvent ev) {
		MotionEvent c = MotionEvent.obtain(ev);
		c.setAction(MotionEvent.ACTION_CANCEL);
		super.onTouchEvent(c);
		c.recycle();
	}

	public boolean onTouchEvent(MotionEvent ev) {
		scaleDetector.onTouchEvent(ev);
		if (pinching || ev.getPointerCount() > 1) {
			if (!vDragging && !hDragging) cancelSuperOnce(ev);
			return true; // two fingers = zoom only
		}
		if (handleThumbTouch(ev)) return true;
		if (velocity == null) velocity = VelocityTracker.obtain();
		velocity.addMovement(ev);
		if (slop < 0) slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
		int action = ev.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			inTouch = true;
			hDragging = vDragging = vDecided = false;
			pinchCancelled = false;
			downX = lastX = ev.getX();
			downY = lastY = ev.getY();
			activePointerId = ev.getPointerId(0);
			dragSpeed = 0f;
			lastMoveTime = ev.getEventTime();
			showThumb();
			swallowGesture = isFlinging();
			if (swallowGesture) {
				stopFling();
				return true;
			}
		} else if (action == MotionEvent.ACTION_MOVE) {
			if (ev.getPointerId(0) != activePointerId) {
				activePointerId = ev.getPointerId(0);
				lastX = ev.getX();
				lastY = ev.getY();
				lastMoveTime = ev.getEventTime();
				return true;
			}
			if (swallowGesture && !vDragging && !hDragging && !vDecided) {
				if (Math.abs(ev.getX() - downX) <= slop && Math.abs(ev.getY() - downY) <= slop) return true;
			}
			if (hDragging) {
				float x = ev.getX();
				setHScroll(getScrollX() + (int) (lastX - x));
				lastX = x;
				return true;
			}
			if (vDragging) {
				float y = ev.getY();
				long now = ev.getEventTime(), dt = Math.max(1, now - lastMoveTime);
				float dy = lastY - y;
				dragSpeed = dragSpeed * 0.6f + (Math.abs(dy) / density / dt) * 0.4f;
				int max = maxScrollY();
				scrollYf = Math.max(0f, Math.min(max, scrollYf + dy * gainForSpeed(dragSpeed, maxGain())));
				scrollTo(getScrollX(), (int) scrollYf);
				lastY = y;
				lastMoveTime = now;
				return true;
			}
			if (!vDecided) {
				float dx = Math.abs(ev.getX() - downX), dy = Math.abs(ev.getY() - downY);
				if (hScrollEnabled && dx > slop && dx > dy) {
					if (maxScrollX() > 0) {
						hDragging = true;
						lastX = ev.getX();
						cancelSuper(ev);
						return true;
					}
				} else if (dy > slop) {
					vDecided = true;
					if ((swallowGesture || getSelectionStart() == getSelectionEnd()) && maxScrollY() > 0) {
						vDragging = true;
						scrollYf = getScrollY();
						lastY = ev.getY();
						lastMoveTime = ev.getEventTime();
						cancelSuper(ev);
						return true;
					}
				}
			}
		} else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
			inTouch = false;
			boolean wasV = vDragging, wasSwallow = swallowGesture;
			swallowGesture = false;
			if (wasV && action == MotionEvent.ACTION_UP) {
				velocity.computeCurrentVelocity(1000, 12000f * density);
				float vy = velocity.getYVelocity();
				if (Math.abs(vy) > 300f * density) {
					if (flinger == null) flinger = new OverScroller(getContext());
					int max = maxScrollY();
					if (max > 0) {
						flinger.fling(0, getScrollY(), 0, (int) (-vy * maxGain()), 0, 0, 0, max);
						postInvalidateOnAnimation();
					}
				}
			}
			velocity.recycle();
			velocity = null;
			if (hDragging || vDragging) {
				hDragging = vDragging = false;
				return true;
			}
			if (wasSwallow) return true;
		}
        boolean handled = super.onTouchEvent(ev);
        // In View mode Android's selectable TextView does not consistently
        // move its selection on a simple tap. Update the read-only caret so
        // IndentGuides can draw the same active block as in Edit mode.
        if (action == MotionEvent.ACTION_UP && !isEditable()
                && !scaleDetector.isInProgress()) {
            Layout layout = getLayout();
            if (layout != null) {
                int y = (int) ev.getY() + getScrollY() - getTotalPaddingTop();
                int line = layout.getLineForVertical(Math.max(0, y));
                float x = ev.getX() + getScrollX() - getTotalPaddingLeft();
                int offset = layout.getOffsetForHorizontal(line, x);
                int len = getText().length();
                setSelection(Math.max(0, Math.min(offset, len)));
                invalidate();
            }
        }
        return handled;
	}

	private boolean pinchCancelled;

	private void cancelSuperOnce(MotionEvent ev) {
		if (!pinchCancelled) {
			pinchCancelled = true;
			cancelSuper(ev);
		}
		int a = ev.getActionMasked();
		if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
			pinchCancelled = false;
			inTouch = false;
		}
	}
}
