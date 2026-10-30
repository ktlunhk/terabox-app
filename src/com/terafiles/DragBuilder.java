package com.terafiles;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;

/** AlertDialog.Builder whose dialogs can be dragged around the screen with a finger. */
class DragBuilder extends AlertDialog.Builder {

	boolean keepWindow;

	DragBuilder(Context c) {
		super(c);
	}

	/** the caller sizes and positions the window itself (list popup menus) */
	DragBuilder keepWindow() {
		keepWindow = true;
		return this;
	}

	public AlertDialog create() {
		final AlertDialog d = super.create();
		d.setOnShowListener(new DialogInterface.OnShowListener() {
			public void onShow(DialogInterface di) {
				makeDraggable(d);
			}
		});
		return d;
	}

	/**
	 * Drag the dialog by touching any part of it that is not a button, list or field
	 * (title, text, empty space, the grab handle of list menus).
	 */
	static void makeDraggable(final Dialog d) {
		final Window w = d.getWindow();
		if (w == null)
			return;
		final View decor = w.getDecorView();
		final int slop = ViewConfiguration.get(decor.getContext()).getScaledTouchSlop();
		final DisplayMetrics dm = decor.getContext().getResources().getDisplayMetrics();
		decor.setOnTouchListener(new View.OnTouchListener() {
			float downX, downY;
			int startX, startY;
			boolean moving, active;

			public boolean onTouch(View v, MotionEvent e) {
				switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					if (e.getX() < 0 || e.getY() < 0 || e.getX() > v.getWidth() || e.getY() > v.getHeight())
						return false; // outside tap: let the dialog close itself
					WindowManager.LayoutParams lp = w.getAttributes();
					downX = e.getRawX();
					downY = e.getRawY();
					startX = lp.x;
					startY = lp.y;
					moving = false;
					active = true;
					return true;
				case MotionEvent.ACTION_MOVE:
					if (!active)
						return false;
					float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
					if (!moving && Math.abs(dx) < slop && Math.abs(dy) < slop)
						return true;
					moving = true;
					WindowManager.LayoutParams p = w.getAttributes();
					int lim = Math.round(48 * dm.density);
					int vw = v.getWidth(), vh = v.getHeight();
					int hg = p.gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
					int vg = p.gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK;
					int minX, maxX, minY, maxY;
					if (hg == android.view.Gravity.LEFT) {
						minX = lim - vw;
						maxX = dm.widthPixels - lim;
					} else {
						maxX = dm.widthPixels / 2 + vw / 2 - lim;
						minX = -maxX;
					}
					if (vg == android.view.Gravity.TOP) {
						minY = 0;
						maxY = dm.heightPixels - lim;
					} else {
						maxY = dm.heightPixels / 2 + vh / 2 - lim;
						minY = -maxY;
					}
					p.x = Math.max(minX, Math.min(maxX, startX + Math.round(dx)));
					p.y = Math.max(minY, Math.min(maxY, startY + Math.round(dy)));
					w.setAttributes(p);
					return true;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					boolean was = active;
					active = false;
					return was;
				}
				return false;
			}
		});
	}
}
