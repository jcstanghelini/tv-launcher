package com.jcstanghelini.tvlauncher;

import android.service.dreams.DreamService;

/** System screensaver: the family photo slideshow from Google Drive. */
public class FotosDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        setContentView(new FotosView(this));
    }
}
