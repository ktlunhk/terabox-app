package com.terafiles;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the process (and the transfer threads) alive while the app is in the background. The work itself runs in
 * MainActivity's executors; this service only holds the foreground notification and a partial wake lock. */
public class TransferService extends Service {

	static volatile TransferService inst;
	PowerManager.WakeLock wl;

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
				wl.acquire(6L * 3600 * 1000);
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
