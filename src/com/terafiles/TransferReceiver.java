package com.terafiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** the Cancel button of a transfer notification */
public class TransferReceiver extends BroadcastReceiver {
	public void onReceive(Context c, Intent i) {
		if (i != null && TransferNotifier.ACTION_CANCEL.equals(i.getAction()))
			TransferNotifier.cancel(i.getIntExtra("id", -1));
	}
}
