package com.jcstanghelini.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Full-screen slideshow of the family's Google Drive folder with a clock, shared by the
 * screensaver ({@link FotosDream}) and the "Probar" preview ({@link FotosActivity}).
 */
public class FotosView extends FrameLayout {

    private static final long SLIDE_MS = 20_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageView[] layers = new ImageView[2];
    private final TextView clock, date, message;
    private int front = 0;
    private List<String> ids = new ArrayList<String>();
    private int idx = 0;
    private boolean running;
    private Bitmap shown;

    public FotosView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            layers[i] = iv;
            addView(iv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout corner = new LinearLayout(c);
        corner.setOrientation(LinearLayout.VERTICAL);
        corner.setGravity(Gravity.END);
        int pad = dp(28);
        corner.setPadding(pad, pad, pad + dp(8), pad);
        clock = text(c, 44f, true);
        date = text(c, 16f, false);
        corner.addView(clock);
        corner.addView(date);
        addView(corner, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));

        message = text(c, 18f, false);
        message.setGravity(Gravity.CENTER);
        message.setText("Cargando fotos…");
        addView(message, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private TextView text(Context c, float sp, boolean bold) {
        TextView tv = new TextView(c);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.create(bold ? "sans-serif-light" : "sans-serif", Typeface.NORMAL));
        tv.setShadowLayer(dp(6), 0, dp(1), 0xCC000000);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        tickClock.run();
        loadList();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        main.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private final Runnable tickClock = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            Date now = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", Familia.AR).format(now));
            String d = new SimpleDateFormat("EEEE d 'de' MMMM", Familia.AR).format(now);
            date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
            main.postDelayed(this, 15_000L);
        }
    };

    private void loadList() {
        new Thread(() -> {
            try {
                List<String> list = Familia.listDrivePhotoIds(getContext());
                Collections.shuffle(list);
                main.post(() -> {
                    ids = list;
                    idx = 0;
                    showNext();
                });
            } catch (Throwable t) {
                final String msg = t.getMessage() != null ? t.getMessage() : "No se pudieron cargar las fotos";
                main.post(() -> {
                    message.setText(msg);
                    message.setVisibility(VISIBLE);
                    // Try again in a few minutes (Wi-Fi may come back).
                    if (running) main.postDelayed(this::loadList, 5 * 60_000L);
                });
            }
        }, "fotos-list").start();
    }

    private final Runnable next = this::showNext;

    private void showNext() {
        if (!running || ids.isEmpty()) return;
        final String id = ids.get(idx % ids.size());
        idx++;
        if (idx % ids.size() == 0) Collections.shuffle(ids);
        final int w = Math.max(getWidth(), getResources().getDisplayMetrics().widthPixels);
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                byte[] data = Familia.httpBytes(Familia.drivePhotoUrl(id, w));
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= w) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (Throwable ignored) {}
            final Bitmap b = bmp;
            main.post(() -> {
                if (!running) return;
                if (b == null) { // not an image (video, sub-folder…) or a network hiccup: move on quickly
                    main.postDelayed(next, 1500L);
                    return;
                }
                crossfade(b);
                main.postDelayed(next, SLIDE_MS);
            });
        }, "fotos-load").start();
    }

    private void crossfade(Bitmap b) {
        message.setVisibility(GONE);
        final ImageView out = layers[front];
        front = 1 - front;
        final ImageView in = layers[front];
        final Bitmap old = shown;
        shown = b;
        in.setImageBitmap(b);
        in.setScaleX(1.0f);
        in.setScaleY(1.0f);
        in.bringToFront();
        in.animate().alpha(1f).setDuration(1200).start();
        // Slow "Ken Burns" zoom while the photo is on screen.
        in.animate().scaleX(1.06f).scaleY(1.06f).setDuration(SLIDE_MS + 2000).start();
        out.animate().alpha(0f).setDuration(1200).withEndAction(() -> {
            out.setImageDrawable(null);
            if (old != null && old != shown && !old.isRecycled()) old.recycle();
        }).start();
        // keep the clock above the photos
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof LinearLayout) getChildAt(i).bringToFront();
        }
    }
}
