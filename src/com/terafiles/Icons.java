package com.terafiles;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.Locale;

/** Small outlined line icons drawn in code (no image files), used in file rows and popup menus. */
class Icons {

	static Drawable get(String name, int color) {
		return new Glyph(name, color);
	}

	/** icon name for a file or folder row */
	static String forEntry(TeraEntry e) {
		if (e.isDir)
			return "folder";
		String n = e.name.toLowerCase(Locale.US);
		int dot = n.lastIndexOf('.');
		String x = dot >= 0 ? n.substring(dot + 1) : "";
		if (x.matches("jpg|jpeg|png|gif|webp|bmp|heic|svg"))
			return "image";
		if (x.matches("mp4|mkv|avi|mov|wmv|flv|webm|3gp|m4v|ts"))
			return "video";
		if (x.matches("mp3|wav|flac|aac|m4a|ogg|opus"))
			return "audio";
		if (x.matches("zip|rar|7z|tar|gz|tgz|bz2|xz"))
			return "archive";
		return "file";
	}

	/** icon name for a menu item, by its label */
	static String forTitle(String t) {
		String s = t.toLowerCase(Locale.US);
		if (s.startsWith("delete") || s.equals("trash") || s.equals("rubbish bin") || s.startsWith("empty"))
			return "trash";
		if (s.equals("rename"))
			return "edit";
		if (s.equals("edit"))
			return "edit";
		if (s.startsWith("copy"))
			return "copy";
		if (s.startsWith("move"))
			return "move";
		if (s.endsWith("to folder"))
			return "folder";
		if (s.equals("zip") || s.equals("tar") || s.startsWith("unzip") || s.startsWith("untar"))
			return "archive";
		if (s.startsWith("download"))
			return "download";
		if (s.startsWith("share") || s.startsWith("create link") || s.startsWith("links"))
			return "link";
		if (s.equals("details"))
			return "info";
		if (s.equals("create") || s.startsWith("create in ") || s.equals("new item") || s.startsWith("new "))
			return "plus";
		if (s.startsWith("upload"))
			return "upload";
		if (s.startsWith("search"))
			return "search";
		if (s.equals("settings"))
			return "gear";
		if (s.contains("cookie"))
			return "key";
		if (s.startsWith("sign out"))
			return "signout";
		if (s.startsWith("restore"))
			return "restore";
		if (s.equals("view"))
			return "eye";
		if (s.equals("select all") || s.startsWith("select"))
			return "check";
		return null;
	}

	static class Glyph extends Drawable {
		final String name;
		final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

		Glyph(String name, int color) {
			this.name = name;
			p.setColor(color);
			p.setStyle(Paint.Style.STROKE);
			p.setStrokeCap(Paint.Cap.ROUND);
			p.setStrokeJoin(Paint.Join.ROUND);
		}

		public void draw(Canvas c) {
			Rect b = getBounds();
			float s = Math.min(b.width(), b.height()) / 24f;
			c.save();
			c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
			c.scale(s, s);
			p.setStrokeWidth(1.9f);
			p.setStyle(Paint.Style.STROKE);
			shape(c);
			c.restore();
		}

		void line(Canvas c, float... v) { // polyline x1,y1,x2,y2,...
			Path q = new Path();
			q.moveTo(v[0], v[1]);
			for (int i = 2; i + 1 < v.length; i += 2)
				q.lineTo(v[i], v[i + 1]);
			c.drawPath(q, p);
		}

		void poly(Canvas c, float... v) { // closed
			Path q = new Path();
			q.moveTo(v[0], v[1]);
			for (int i = 2; i + 1 < v.length; i += 2)
				q.lineTo(v[i], v[i + 1]);
			q.close();
			c.drawPath(q, p);
		}

		void page(Canvas c) {
			poly(c, 5.5f, 2.5f, 14f, 2.5f, 19f, 7.5f, 19f, 21.5f, 5.5f, 21.5f);
			line(c, 14f, 2.5f, 14f, 7.5f, 19f, 7.5f);
		}

		void tray(Canvas c) {
			line(c, 4f, 16f, 4f, 20.5f, 20f, 20.5f, 20f, 16f);
		}

		void shape(Canvas c) {
			if (name.equals("home")) { // solid house
				p.setStyle(Paint.Style.FILL_AND_STROKE);
				p.setStrokeWidth(1.2f);
				poly(c, 12f, 3f, 2.5f, 11.5f, 5.5f, 11.5f, 5.5f, 20.5f, 10f, 20.5f, 10f, 14.5f, 14f, 14.5f, 14f, 20.5f, 18.5f, 20.5f, 18.5f, 11.5f, 21.5f, 11.5f);
			} else if (name.equals("updir")) { // arrow turning up out of a folder level
				p.setStrokeWidth(3f);
				p.setStrokeCap(Paint.Cap.BUTT);
				line(c, 5f, 19f, 17f, 19f, 17f, 7f);
				p.setStrokeCap(Paint.Cap.ROUND);
				line(c, 11.5f, 10.5f, 17f, 5f, 22.5f, 10.5f);
			} else if (name.equals("folder")) {
				poly(c, 3f, 5f, 9.5f, 5f, 11.5f, 7.5f, 21f, 7.5f, 21f, 19.5f, 3f, 19.5f);
			} else if (name.equals("file")) {
				page(c);
				line(c, 8.5f, 12.5f, 16f, 12.5f);
				line(c, 8.5f, 15.5f, 16f, 15.5f);
				line(c, 8.5f, 18.5f, 12.5f, 18.5f);
			} else if (name.equals("image")) {
				c.drawRoundRect(new RectF(3f, 4.5f, 21f, 19.5f), 2f, 2f, p);
				c.drawCircle(8.5f, 9.5f, 1.7f, p);
				line(c, 3.5f, 17f, 9f, 12f, 13f, 16f, 16f, 13f, 20.5f, 17.5f);
			} else if (name.equals("video")) {
				c.drawRoundRect(new RectF(3f, 5f, 21f, 19f), 2f, 2f, p);
				poly(c, 10f, 9f, 16f, 12f, 10f, 15f);
			} else if (name.equals("audio")) {
				line(c, 9f, 17.5f, 9f, 5.5f, 19f, 3.5f, 19f, 15.5f);
				c.drawOval(new RectF(4.8f, 15.3f, 9.2f, 19.7f), p);
				c.drawOval(new RectF(14.8f, 13.3f, 19.2f, 17.7f), p);
			} else if (name.equals("archive")) {
				page(c);
				line(c, 11f, 2.8f, 11f, 5f);
				line(c, 11f, 7f, 11f, 9f);
				c.drawRect(new RectF(9.2f, 11f, 12.8f, 15.5f), p);
			} else if (name.equals("trash")) {
				line(c, 4f, 6.5f, 20f, 6.5f);
				line(c, 9f, 6.5f, 9f, 3.8f, 15f, 3.8f, 15f, 6.5f);
				line(c, 6f, 6.5f, 7f, 20.5f, 17f, 20.5f, 18f, 6.5f);
				line(c, 10f, 10f, 10f, 17f);
				line(c, 14f, 10f, 14f, 17f);
			} else if (name.equals("edit")) {
				line(c, 11f, 5.5f, 4.5f, 5.5f, 4.5f, 19.5f, 18.5f, 19.5f, 18.5f, 13f);
				poly(c, 9f, 15f, 9.6f, 11.8f, 18.8f, 2.8f, 21.2f, 5.2f, 12.2f, 14.4f);
			} else if (name.equals("copy")) {
				c.drawRoundRect(new RectF(8.5f, 3.5f, 20.5f, 15.5f), 2f, 2f, p);
				line(c, 15.5f, 19.5f, 15.5f, 20.5f, 3.5f, 20.5f, 3.5f, 8.5f, 4.5f, 8.5f);
				line(c, 3.5f, 8.5f, 8.5f, 8.5f);
			} else if (name.equals("move")) {
				line(c, 3f, 12f, 16f, 12f);
				line(c, 11f, 6.5f, 16.5f, 12f, 11f, 17.5f);
				line(c, 21f, 5f, 21f, 19f);
			} else if (name.equals("link")) {
				c.save();
				c.rotate(-45f, 12f, 12f);
				c.drawRoundRect(new RectF(2.5f, 8.5f, 14.5f, 15.5f), 3.5f, 3.5f, p);
				c.drawRoundRect(new RectF(9.5f, 8.5f, 21.5f, 15.5f), 3.5f, 3.5f, p);
				c.restore();
			} else if (name.equals("download")) {
				line(c, 12f, 3f, 12f, 14.5f);
				line(c, 7f, 9.8f, 12f, 14.8f, 17f, 9.8f);
				tray(c);
			} else if (name.equals("upload")) {
				line(c, 12f, 15f, 12f, 3.5f);
				line(c, 7f, 8.2f, 12f, 3.2f, 17f, 8.2f);
				tray(c);
			} else if (name.equals("search")) {
				c.drawCircle(10.5f, 10.5f, 6.5f, p);
				line(c, 15.3f, 15.3f, 21f, 21f);
			} else if (name.equals("gear")) {
				c.drawCircle(12f, 12f, 3f, p);
				c.drawCircle(12f, 12f, 7f, p);
				for (int i = 0; i < 8; i++) {
					double a = Math.PI / 4 * i;
					float cx = (float) Math.cos(a), cy = (float) Math.sin(a);
					line(c, 12f + cx * 7f, 12f + cy * 7f, 12f + cx * 10f, 12f + cy * 10f);
				}
			} else if (name.equals("signout")) {
				line(c, 10f, 4f, 4.5f, 4f, 4.5f, 20f, 10f, 20f);
				line(c, 9f, 12f, 20f, 12f);
				line(c, 16f, 8f, 20f, 12f, 16f, 16f);
			} else if (name.equals("plus")) {
				c.drawRoundRect(new RectF(3.5f, 3.5f, 20.5f, 20.5f), 3f, 3f, p);
				line(c, 12f, 7.5f, 12f, 16.5f);
				line(c, 7.5f, 12f, 16.5f, 12f);
			} else if (name.equals("info")) {
				c.drawCircle(12f, 12f, 9f, p);
				line(c, 12f, 11f, 12f, 17f);
				line(c, 12f, 7.6f, 12f, 7.7f);
			} else if (name.equals("key")) {
				c.drawCircle(8f, 15f, 4.2f, p);
				line(c, 11f, 12f, 20.5f, 2.8f);
				line(c, 17.5f, 5.8f, 20f, 8.3f);
				line(c, 14.5f, 8.5f, 16.8f, 10.8f);
			} else if (name.equals("restore")) {
				Path q = new Path();
				q.moveTo(8f, 9f);
				q.lineTo(14.5f, 9f);
				q.arcTo(new RectF(9f, 9f, 20f, 20f), -90f, 180f);
				q.lineTo(8f, 20f);
				c.drawPath(q, p);
				line(c, 8.5f, 4.5f, 4f, 9f, 8.5f, 13.5f);
			} else if (name.equals("eye")) {
				Path q = new Path();
				q.moveTo(2f, 12f);
				q.quadTo(12f, 3f, 22f, 12f);
				q.quadTo(12f, 21f, 2f, 12f);
				c.drawPath(q, p);
				c.drawCircle(12f, 12f, 3f, p);
			} else if (name.equals("check")) {
				line(c, 4.5f, 12.5f, 10f, 18f, 20f, 6.5f);
			}
		}

		public void setAlpha(int a) {
			p.setAlpha(a);
		}

		public void setColorFilter(ColorFilter f) {
			p.setColorFilter(f);
		}

		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}
}
