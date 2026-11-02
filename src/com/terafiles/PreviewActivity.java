package com.terafiles;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import android.media.MediaPlayer;
import android.util.Log;
import java.io.File;
import java.util.Locale;

/** Offline in-app image, video, MP3 and PDF viewer; no AndroidX dependencies. */
public class PreviewActivity extends Activity {
	private PdfRenderer renderer;
	private ParcelFileDescriptor descriptor;
	private Bitmap pageBitmap;
	private ImageView pageImage;
	private TextView pageLabel;
	private int pageIndex;
	private VideoView video;
	private String path;
	private MediaPlayer audio;
	private SeekBar seek;
	private TextView timeLabel;
	private Button playBtn;
	private boolean dragging;
	private final android.os.Handler ticker = new android.os.Handler();
	private final Runnable tick = new Runnable() {
		public void run() {
			updateAudio();
			if (audio != null)
				ticker.postDelayed(this, 500);
		}
	};
	@Override
	public void onCreate(Bundle b) {
		if (!MainActivity.darkMode) { // follow the app's Light / Dark theme setting
			int theme = getResources().getIdentifier("AppThemeLight", "style", getPackageName());
			setTheme(theme != 0 ? theme : android.R.style.Theme_Material_Light_NoActionBar);
		}
		super.onCreate(b);
		path = getIntent().getStringExtra("path");
		String name = getIntent().getStringExtra("name");
		if (path == null || !new File(path).isFile()) {
			finish();
			return;
		}
		if (name == null)
			name = new File(path).getName();
		String lower = name.toLowerCase(Locale.US);
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(MainActivity.BG);
		LinearLayout bar = UI.titleBar(this, "\u2190", name, new View.OnClickListener() {
			public void onClick(View v) {
				finish();
			}
		}, android.text.TextUtils.TruncateAt.END);
		root.addView(bar, UI.barParams(this));
		setContentView(root);
		try {
			if (lower.endsWith(".pdf")) {
				if (Build.VERSION.SDK_INT < 21)
					throw new Exception("PDF viewer requires Android 5.0 or newer");
				descriptor = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
				renderer = new PdfRenderer(descriptor);
				pageImage = new ImageView(this);
				pageImage.setBackgroundColor(MainActivity.darkMode ? Color.DKGRAY : Color.rgb(221, 224, 228));
				pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
				root.addView(pageImage, new LinearLayout.LayoutParams(-1, 0, 1));
				LinearLayout controls = new LinearLayout(this);
				controls.setGravity(android.view.Gravity.CENTER);
				Button prev = UI.smallButton(this, "Previous", MainActivity.BTN);
				prev.setOnClickListener(new View.OnClickListener() {
					public void onClick(View v) {
						showPage(pageIndex - 1);
					}
				});
				Button next = UI.smallButton(this, "Next", MainActivity.BTN);
				next.setOnClickListener(new View.OnClickListener() {
					public void onClick(View v) {
						showPage(pageIndex + 1);
					}
				});
				pageLabel = UI.text(this, "", 15, MainActivity.TEXT);
				pageLabel.setPadding(UI.dp(this, 14), 0, UI.dp(this, 14), 0);
				controls.setPadding(0, UI.dp(this, 8), 0, UI.dp(this, 8));
				controls.addView(prev);
				controls.addView(pageLabel);
				controls.addView(next);
				root.addView(controls);
				showPage(0);
			} else if (lower.endsWith(".mp3")) {
				showAudio(root, name);
			} else if (lower.matches(".*\\.(mp4|mkv|webm|mov|avi|3gp)$")) {
				video = new VideoView(this);
				root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
				MediaController controller = new MediaController(this);
				controller.setAnchorView(video);
				video.setMediaController(controller);
				video.setVideoPath(path);
				video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
					public void onPrepared(MediaPlayer mp) {
						video.start();
					}
				});
				video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
					public boolean onError(MediaPlayer mp, int what, int extra) {
						message("Video format or codec not supported on this device");
						return true;
					}
				});
			} else {
				final ImageView img = new ImageView(this);
				img.setScaleType(ImageView.ScaleType.MATRIX);
				root.addView(img, new LinearLayout.LayoutParams(-1, 0, 1));
				BitmapFactory.Options opts = new BitmapFactory.Options();
				opts.inJustDecodeBounds = true;
				BitmapFactory.decodeFile(path, opts);
				int sample = 1;
				while (opts.outWidth / sample > 4096 || opts.outHeight / sample > 4096)
					sample *= 2;
				opts.inJustDecodeBounds = false;
				opts.inSampleSize = sample;
				final Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
				if (bitmap == null)
					throw new Exception("Image format not supported on this device");
				img.setImageBitmap(bitmap);
				img.post(new Runnable() {
					public void run() {
						Matrix m = new Matrix();
						float scale =
							Math.min((float) img.getWidth() / bitmap.getWidth(), (float) img.getHeight() / bitmap.getHeight());
						m.postScale(scale, scale);
						m.postTranslate(
							(img.getWidth() - bitmap.getWidth() * scale) / 2, (img.getHeight() - bitmap.getHeight() * scale) / 2);
						img.setImageMatrix(m);
					}
				});
				img.setOnTouchListener(new View.OnTouchListener() {
					Matrix matrix = new Matrix();
					PointF last = new PointF();
					float distance = 0;
					int mode = 0;
					public boolean onTouch(View v, MotionEvent e) {
						switch (e.getActionMasked()) {
							case MotionEvent.ACTION_DOWN:
								matrix.set(img.getImageMatrix());
								last.set(e.getX(), e.getY());
								mode = 1;
								break;
							case MotionEvent.ACTION_POINTER_DOWN:
								if (e.getPointerCount() > 1) {
									distance = spacing(e);
									matrix.set(img.getImageMatrix());
									mode = 2;
								}
								break;
							case MotionEvent.ACTION_MOVE:
								if (mode == 1) {
									Matrix m = new Matrix(matrix);
									m.postTranslate(e.getX() - last.x, e.getY() - last.y);
									img.setImageMatrix(m);
								} else if (mode == 2 && e.getPointerCount() > 1 && distance > 10) {
									float factor = spacing(e) / distance;
									Matrix m = new Matrix(matrix);
									m.postScale(factor, factor, (e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2);
									img.setImageMatrix(m);
								}
								break;
							case MotionEvent.ACTION_UP:
							case MotionEvent.ACTION_CANCEL:
								mode = 0;
								break;
							case MotionEvent.ACTION_POINTER_UP:
								mode = 0;
								break;
						}
						return true;
					}
					float spacing(MotionEvent e) {
						float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
						return (float) Math.sqrt(dx * dx + dy * dy);
					}
				});
			}
		} catch (Exception ex) {
			message(ex.getMessage());
		}
	}

	private void showPage(int index) {
		if (renderer == null || index < 0 || index >= renderer.getPageCount())
			return;
		try {
			PdfRenderer.Page page = renderer.openPage(index);
			int w = page.getWidth(), h = page.getHeight();
			float scale = Math.min(2f, 2048f / Math.max(w, h));
			Bitmap b =
				Bitmap.createBitmap(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)), Bitmap.Config.ARGB_8888);
			b.eraseColor(Color.WHITE);
			page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
			page.close();
			Bitmap old = pageBitmap;
			pageBitmap = b;
			pageImage.setImageBitmap(b);
			if (old != null)
				old.recycle();
			pageIndex = index;
			pageLabel.setText((index + 1) + " / " + renderer.getPageCount());
		} catch (Exception ex) {
			message(ex.getMessage());
		}
	}

	private void showAudio(LinearLayout root, String name) throws Exception {
		audio = new MediaPlayer();
		audio.setDataSource(path);
		audio.prepare();
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(android.view.Gravity.CENTER);
		box.setPadding(UI.dp(this, 24), UI.dp(this, 24), UI.dp(this, 24), UI.dp(this, 24));
		TextView note = UI.text(this, "\u266A", 72, MainActivity.ACCENT);
		note.setGravity(android.view.Gravity.CENTER);
		TextView title = UI.text(this, name, 17, MainActivity.TEXT);
		title.setGravity(android.view.Gravity.CENTER);
		title.setPadding(0, UI.dp(this, 8), 0, UI.dp(this, 24));
		seek = new SeekBar(this);
		seek.setMax(Math.max(1, audio.getDuration()));
		timeLabel = UI.text(this, "", 14, MainActivity.MUTED);
		timeLabel.setGravity(android.view.Gravity.CENTER);
		timeLabel.setPadding(0, UI.dp(this, 4), 0, UI.dp(this, 16));
		playBtn = UI.button(this, "Pause");
		playBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (audio == null)
					return;
				if (audio.isPlaying()) {
					audio.pause();
					playBtn.setText("Play");
				} else {
					audio.start();
					playBtn.setText("Pause");
				}
			}
		});
		seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
				if (fromUser)
					timeLabel.setText(clock(progress) + " / " + clock(audio.getDuration()));
			}
			public void onStartTrackingTouch(SeekBar bar) {
				dragging = true;
			}
			public void onStopTrackingTouch(SeekBar bar) {
				dragging = false;
				if (audio != null)
					audio.seekTo(bar.getProgress());
			}
		});
		audio.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
			public void onCompletion(MediaPlayer mp) {
				playBtn.setText("Play");
				mp.seekTo(0);
				seek.setProgress(0);
				timeLabel.setText(clock(0) + " / " + clock(mp.getDuration()));
			}
		});
		audio.setOnErrorListener(new MediaPlayer.OnErrorListener() {
			public boolean onError(MediaPlayer mp, int what, int extra) {
				message("Audio format not supported on this device");
				return true;
			}
		});
		box.addView(note, new LinearLayout.LayoutParams(-1, -2));
		box.addView(title, new LinearLayout.LayoutParams(-1, -2));
		box.addView(seek, new LinearLayout.LayoutParams(-1, -2));
		box.addView(timeLabel, new LinearLayout.LayoutParams(-1, -2));
		box.addView(playBtn, new LinearLayout.LayoutParams(-2, -2));
		root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));
		updateAudio();
		audio.start();
		ticker.postDelayed(tick, 500);
	}

	private void updateAudio() {
		if (audio == null || seek == null)
			return;
		try {
			int pos = audio.getCurrentPosition();
			if (!dragging) {
				seek.setProgress(pos);
				timeLabel.setText(clock(pos) + " / " + clock(audio.getDuration()));
			}
		} catch (Exception ignored) {
			Log.w("TeraFiles", "PreviewActivity.updateAudio failed", ignored);
		}
	}

	private static String clock(int ms) {
		int s = ms / 1000;
		return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
	}

	@Override
	protected void onPause() {
		if (audio != null && audio.isPlaying()) {
			audio.pause();
			if (playBtn != null)
				playBtn.setText("Play");
		}
		super.onPause();
	}

	private void message(String s) {
		Toast.makeText(this, s == null ? "Unable to open file" : s, Toast.LENGTH_LONG).show();
	}

	@Override
	protected void onDestroy() {
		ticker.removeCallbacks(tick);
		if (audio != null) {
			audio.release();
			audio = null;
		}
		if (video != null)
			video.stopPlayback();
		if (pageImage != null)
			pageImage.setImageDrawable(null);
		if (pageBitmap != null)
			pageBitmap.recycle();
		if (renderer != null)
			renderer.close();
		if (descriptor != null)
			try {
				descriptor.close();
			} catch (Exception ignored) {
				Log.w("TeraFiles", "PreviewActivity.onDestroy failed", ignored);
			}
		super.onDestroy();
	}
}
