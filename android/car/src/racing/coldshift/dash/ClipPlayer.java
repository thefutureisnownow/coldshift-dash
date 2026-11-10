package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Plays a dashcam clip inside the app, over the whole screen (the kiosk never hands over to another app):
 * the video letterboxed in the middle, and a bar with play or pause, back and forward 10 s, a seek bar
 * with the time, and Close. The music pauses while it plays and carries on after (Player).
 */
final class ClipPlayer extends FrameLayout implements SurfaceHolder.Callback, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnVideoSizeChangedListener {
    private static final float BAR_T = 630;
    private static final RectF PLAY = new RectF(24, 644, 104, 706), BACK = new RectF(116, 644, 196, 706);
    private static final RectF FWD = new RectF(208, 644, 288, 706), CLOSE = new RectF(1106, 644, 1260, 706);
    private static final float SEEK_L = 320, SEEK_R = 940, SEEK_Y = 675;      // the time to its right, before Close

    private final Main act;
    private final File file;
    private final SurfaceView video;
    private final Controls controls;
    private MediaPlayer mp;
    private boolean ready, dragging, musicWasPlaying;
    private int vw, vh;

    static void open(Main a, File f) {
        a.root().addView(new ClipPlayer(a, f), new FrameLayout.LayoutParams(-1, -1));
    }

    private ClipPlayer(Main a, File f) {
        super(a);
        act = a;
        file = f;
        setBackgroundColor(0xff000000);
        video = new SurfaceView(a);
        video.getHolder().addCallback(this);
        addView(video, new FrameLayout.LayoutParams(-1, -1));
        controls = new Controls();
        addView(controls, new FrameLayout.LayoutParams(-1, -1));
        Player p = Player.get(a);
        musicWasPlaying = p.isPlaying();
        if (musicWasPlaying) p.toggle();
    }

    // ---- the video ----

    public void surfaceCreated(SurfaceHolder h) {
        try {
            mp = new MediaPlayer();
            mp.setDataSource(file.getPath());
            mp.setDisplay(h);
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnVideoSizeChangedListener(this);
            mp.prepareAsync();
        } catch (Exception e) {
            android.util.Log.w("ClipPlayer", "cannot play " + file, e);
            close();
        }
    }

    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {}

    public void surfaceDestroyed(SurfaceHolder h) { release(); }

    public void onPrepared(MediaPlayer m) {
        ready = true;
        m.start();
        controls.tick();
    }

    public void onCompletion(MediaPlayer m) { controls.invalidate(); }

    public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
        vw = w;
        vh = h;
        requestLayout();
    }

    /** The video as large as fits above the bar, keeping its shape. */
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        int w = r - l, h = Math.round((b - t) * BAR_T / 720f);
        if (vw <= 0 || vh <= 0) return;
        float k = Math.min((float) w / vw, (float) h / vh);
        int dw = Math.round(vw * k), dh = Math.round(vh * k);
        video.layout((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
    }

    private void release() {
        if (mp != null) {
            try { mp.release(); } catch (Exception e) { /* gone */ }
            mp = null;
        }
        ready = false;
    }

    private void close() {
        release();
        act.root().removeView(this);
        if (musicWasPlaying) Player.get(act).toggle();
    }

    // ---- the bar ----

    private final class Controls extends View {
        Controls() { super(act); }

        void tick() {
            invalidate();
            if (mp != null) postDelayed(new Runnable() {
                public void run() { tick(); }
            }, 250);
        }

        private String time(int ms) {
            int s = Math.max(0, ms / 1000);
            return s / 60 + ":" + String.format(Locale.US, "%02d", s % 60);
        }

        private final Press press = new Press(this);      // the pressed look and the tap buzz, as everywhere

        @Override protected void onDraw(Canvas c) {
            c.save();
            Screen.begin(c, this);
            press.begin();
            Theme.P.setShader(null);
            Theme.P.setStyle(android.graphics.Paint.Style.FILL);
            Theme.P.setColor(0xf0050912);
            float e = Screen.extra(this);
            c.drawRect(-e, BAR_T, 1280 + e, 720, Theme.P);
            boolean playing = ready && mp != null && mp.isPlaying();
            int pos = ready && mp != null ? mp.getCurrentPosition() : 0, len = ready && mp != null ? mp.getDuration() : 0;
            Theme.button(c, PLAY, true, 12);
            if (playing) Icons.pause(c, PLAY.centerX(), PLAY.centerY(), 14, 0xffffffff);
            else Icons.play(c, PLAY.centerX() + 2, PLAY.centerY(), 14, 0xffffffff);
            Theme.button(c, BACK, false, 12);
            Theme.text(c, "−10", BACK.centerX(), BACK.centerY() + 7, 20, Theme.TEXT, Theme.bold, Theme.CENTER);
            Theme.button(c, FWD, false, 12);
            Theme.text(c, "+10", FWD.centerX(), FWD.centerY() + 7, 20, Theme.TEXT, Theme.bold, Theme.CENTER);
            Theme.slider(c, SEEK_L, SEEK_R, SEEK_Y, len > 0 ? (float) pos / len : 0);
            Theme.text(c, time(pos) + " / " + time(len), SEEK_R + 16, SEEK_Y + 7, 18, Theme.SUB, Theme.sans, Theme.LEFT);
            Theme.button(c, CLOSE, false, 12);
            Theme.text(c, "Close", CLOSE.centerX(), CLOSE.centerY() + 8, 22, Theme.TEXT, Theme.bold, Theme.CENTER);
            Theme.text(c, Theme.fit(new SimpleDateFormat("EEE d MMM  HH:mm:ss", Locale.US).format(new Date(file.lastModified()))
                + "  ·  " + file.getName(), 900, 18, Theme.sans), 24, 30, 18, 0xccffffff, Theme.sans, Theme.LEFT);
            press.end();
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            press.touch(e);
            act.ui().poke();
            float x = Screen.x(this, e.getX()), y = Screen.y(this, e.getY());
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN && y > BAR_T && x >= SEEK_L - 10 && x <= SEEK_R + 10) dragging = true;
            if (dragging && (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) && ready && mp != null) {
                float f = Math.max(0, Math.min(1, (x - SEEK_L) / (SEEK_R - SEEK_L)));
                mp.seekTo(Math.round(f * mp.getDuration()));
                invalidate();
            }
            if (a == MotionEvent.ACTION_UP) {
                boolean wasDragging = dragging;
                dragging = false;
                if (wasDragging) return true;
                if (CLOSE.contains(x, y)) close();
                else if (ready && mp != null) {
                    if (PLAY.contains(x, y) || y < BAR_T) {           // the video itself also pauses and plays
                        if (mp.isPlaying()) mp.pause();
                        else mp.start();
                    } else if (BACK.contains(x, y)) {
                        mp.seekTo(Math.max(0, mp.getCurrentPosition() - 10000));
                    } else if (FWD.contains(x, y)) {
                        mp.seekTo(Math.min(mp.getDuration(), mp.getCurrentPosition() + 10000));
                    }
                    invalidate();
                }
            }
            return true;
        }
    }
}
