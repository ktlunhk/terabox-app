package com.terafiles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.LinkedHashMap;

/**
 * Background progress notifications for uploads / downloads. Each running job has an ongoing notification with a
 * progress bar and a Cancel action; a foreground service (TransferService) keeps the process alive while any job runs.
 */
public class TransferNotifier {

	static final String CHANNEL = "transfers";
	static final String ACTION_CANCEL = "com.terafiles.CANCEL_TRANSFER";
	static final int STUB_ID = 999;

	static class Entry {
		int id;
		String title = "", text = "";
		boolean up;
		boolean[] cancel;
		long done, total, lastNotify;
	}

	static Context app;
	static int nextId = 1000;
	static int fgId = STUB_ID;
	/** set by MainActivity: true while the app is on screen (then no "finished" notification is posted) */
	static volatile boolean appVisible = true;
	static final LinkedHashMap<Integer, Entry> jobs = new LinkedHashMap<Integer, Entry>();

	static NotificationManager nm() {
		return (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
	}

	static void ensureChannel() {
		if (Build.VERSION.SDK_INT >= 26) {
			NotificationChannel ch = new NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW);
			ch.setDescription("Progress of uploads and downloads");
			nm().createNotificationChannel(ch);
		}
	}

	static int flags() {
		return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
	}

	static PendingIntent openApp() {
		Intent i = new Intent(app, MainActivity.class);
		i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		return PendingIntent.getActivity(app, 0, i, flags());
	}

	static PendingIntent cancelIntent(int id) {
		Intent i = new Intent(app, TransferReceiver.class);
		i.setAction(ACTION_CANCEL);
		i.putExtra("id", id);
		return PendingIntent.getBroadcast(app, id, i, flags());
	}

	static Notification.Builder builder() {
		return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
	}

	static Notification build(Entry j) {
		Notification.Builder b = builder();
		b.setSmallIcon(j.up ? android.R.drawable.stat_sys_upload : android.R.drawable.stat_sys_download);
		b.setContentTitle(j.title);
		b.setContentText(j.text);
		b.setStyle(new Notification.BigTextStyle().bigText(j.text));
		b.setOngoing(true);
		b.setOnlyAlertOnce(true);
		if (Build.VERSION.SDK_INT < 26)
			b.setPriority(Notification.PRIORITY_LOW);
		if (j.total > 0)
			b.setProgress(100, (int) Math.min(100, j.done * 100 / j.total), false);
		else
			b.setProgress(0, 0, true);
		b.setContentIntent(openApp());
		b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent(j.id));
		return b.build();
	}

	/** start tracking a job; returns its id */
	static synchronized int begin(Context c, String title, boolean up, boolean[] cancel) {
		app = c.getApplicationContext();
		ensureChannel();
		Entry j = new Entry();
		j.id = nextId++;
		j.title = title;
		j.up = up;
		j.cancel = cancel;
		j.text = "Starting...";
		boolean first = jobs.isEmpty();
		jobs.put(j.id, j);
		nm().notify(j.id, build(j));
		if (first) {
			Intent si = new Intent(app, TransferService.class);
			try {
				if (Build.VERSION.SDK_INT >= 26)
					app.startForegroundService(si);
				else
					app.startService(si);
			} catch (Exception e) {
				// the transfer still runs, just without foreground protection
			}
		}
		return j.id;
	}

	/** text is the file line; done / total in bytes (total <= 0 = unknown) */
	static synchronized void update(int id, String text, long done, long total) {
		Entry j = jobs.get(id);
		if (j == null)
			return;
		j.text = text;
		j.done = done;
		j.total = total;
		long now = System.currentTimeMillis();
		if (now - j.lastNotify < 700 && !(total > 0 && done >= total))
			return;
		j.lastNotify = now;
		nm().notify(id, build(j));
	}

	/** the job is over; its ongoing notification goes away */
	static synchronized void end(int id) {
		if (jobs.remove(id) == null)
			return;
		if (jobs.isEmpty()) {
			TransferService s = TransferService.inst;
			if (s != null)
				s.shutDown();
			else
				app.stopService(new Intent(app, TransferService.class));
		} else if (id == fgId && TransferService.inst != null) {
			TransferService.inst.refresh(); // another job takes over as the foreground notification
		}
		nm().cancel(id);
	}

	/** Cancel pressed on the notification */
	static synchronized void cancel(int id) {
		Entry j = jobs.get(id);
		if (j == null)
			return;
		j.cancel[0] = true;
		j.text = "Cancelling...";
		nm().notify(id, build(j));
	}

	/** the notification the service shows in the foreground (the oldest running job) */
	static synchronized Notification foreground() {
		if (jobs.isEmpty()) {
			Notification.Builder b = builder();
			b.setSmallIcon(android.R.drawable.stat_sys_download);
			b.setContentTitle("TeraFiles");
			b.setContentText("Finishing...");
			fgId = STUB_ID;
			return b.build();
		}
		Entry j = jobs.values().iterator().next();
		fgId = j.id;
		return build(j);
	}

	/** make s a foreground service showing the current foreground notification */
	static synchronized void promote(android.app.Service s) {
		Notification n = foreground(); // also sets fgId
		s.startForeground(fgId, n);
	}

	/** a "finished" / "failed" notification, only while the app is not on screen */
	static synchronized void result(Context c, String title, String text, boolean ok) {
		if (appVisible || text == null)
			return;
		app = c.getApplicationContext();
		ensureChannel();
		Notification.Builder b = builder();
		b.setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error);
		b.setContentTitle(title);
		b.setContentText(text);
		b.setStyle(new Notification.BigTextStyle().bigText(text));
		b.setAutoCancel(true);
		b.setContentIntent(openApp());
		nm().notify(nextId++, b.build());
	}
}
