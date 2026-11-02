package com.terafiles;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/** Keeps the process (and the transfer threads) alive while the app is in the background. The work itself runs in
 * MainActivity's executors; this service only holds the foreground notification and a partial wake lock. */
public class TransferService extends Service {

	static volatile TransferService inst;
	PowerManager.WakeLock wl;
	long lockAt;
	static final long LOCK_MS = 6L * 3600 * 1000;
	/** renew while there is still an hour left, so a very long transfer never loses the lock */
	static final long RENEW_AFTER_MS = 5L * 3600 * 1000;

	public IBinder onBind(Intent i) {
		return null;
	}

	public int onStartCommand(Intent i, int flags, int startId) {
		inst = this;
		TransferNotifier.promote(this);
		if (wl == null) {
			try {
				PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
				wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TeraFiles:transfer");
				wl.setReferenceCounted(false);
				wl.acquire(LOCK_MS);
				lockAt = SystemClock.elapsedRealtime();
			} catch (Exception e) {
				wl = null;
			}
		}
		if (TransferNotifier.jobs.isEmpty())
			shutDown();
		return START_NOT_STICKY;
	}

	void refresh() {
		TransferNotifier.promote(this);
		renewWakeLock();
	}

	/** acquire() on the non-counted lock restarts its timeout; called with every progress refresh */
	void renewWakeLock() {
		PowerManager.WakeLock l = wl;
		if (l == null)
			return;
		long now = SystemClock.elapsedRealtime();
		if (l.isHeld() && now - lockAt < RENEW_AFTER_MS)
			return;
		try {
			l.acquire(LOCK_MS);
			lockAt = now;
		} catch (Exception e) {
			Log.w("TeraFiles", "TransferService.renewWakeLock failed", e);
		}
	}

	void shutDown() {
		stopForeground(true);
		stopSelf();
	}

	public void onDestroy() {
		inst = null;
		if (wl != null && wl.isHeld())
			wl.release();
		super.onDestroy();
	}
}
