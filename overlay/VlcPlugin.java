package com.tem.minerador;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Reprodutor VLC de reserva: so e usado quando o reprodutor do sistema nao toca o video.
 * O video aparece atras do WebView (que fica transparente na area do video).
 */
@CapacitorPlugin(name = "Vlc")
public class VlcPlugin extends Plugin {

    private LibVLC vlc;
    private MediaPlayer mp;
    private VLCVideoLayout layout;
    private FrameLayout box;
    private ParcelFileDescriptor pfd;
    private Uri cur;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int vw = 0, vh = 0;

    @Override
    public void load() {
        /* guarda o endereco de cada arquivo escolhido no app, para o VLC abrir sem pedir de novo */
        main.post(() -> {
            try {
                WebView wv = getBridge().getWebView();
                wv.setWebChromeClient(new BridgeWebChromeClient(getBridge()) {
                    @Override
                    public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
                        return super.onShowFileChooser(v, uris -> {
                            try {
                                if (uris != null) for (Uri u : uris) remember(u);
                            } catch (Exception e) {
                                // ignora
                            }
                            cb.onReceiveValue(uris);
                        }, p);
                    }
                });
            } catch (Exception e) {
                // sem isso o VLC pede para escolher o arquivo de novo
            }
        });
    }

    private SharedPreferences sp() {
        return getContext().getSharedPreferences("vlc-uris", Context.MODE_PRIVATE);
    }

    private String[] nameSize(Uri u) {
        String name = null;
        long size = -1;
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(u, new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null);
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (Exception e) {
            // ignora
        } finally {
            if (c != null) c.close();
        }
        return new String[] { name, String.valueOf(size) };
    }

    private void remember(Uri u) {
        if (u == null) return;
        try {
            getContext().getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // nem todo arquivo aceita permissao permanente
        }
        String[] ns = nameSize(u);
        if (ns[0] == null) return;
        sp().edit().putString(ns[0] + "|" + ns[1], u.toString()).apply();
    }

    private boolean readable(Uri u) {
        try {
            ParcelFileDescriptor f = getContext().getContentResolver().openFileDescriptor(u, "r");
            if (f == null) return false;
            f.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private LibVLC lib() {
        if (vlc == null) {
            ArrayList<String> a = new ArrayList<>();
            a.add("--audio-time-stretch");
            a.add("--avcodec-skiploopfilter=1");
            a.add("--no-sub-autodetect-file");
            a.add("--audio-language=kor,ko,kr");
            vlc = new LibVLC(getContext(), a);
        }
        return vlc;
    }

    private void emit(String type, JSObject extra) {
        JSObject o = extra == null ? new JSObject() : extra;
        o.put("type", type);
        notifyListeners("vlc", o);
    }

    /** procura o arquivo pelo nome e tamanho (o que o app ja abriu) */
    @PluginMethod
    public void find(PluginCall call) {
        String key = call.getString("name", "") + "|" + (long) Math.floor(call.getDouble("size", -1.0));
        String s = sp().getString(key, null);
        JSObject r = new JSObject();
        if (s != null && readable(Uri.parse(s))) r.put("uri", s);
        call.resolve(r);
    }

    /** pede para escolher o video (so quando o app nao achou o arquivo sozinho) */
    @PluginMethod
    public void pick(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "video/*", "application/octet-stream", "application/x-matroska" });
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, i, "picked");
    }

    @ActivityCallback
    private void picked(PluginCall call, ActivityResult res) {
        if (call == null) return;
        Intent d = res.getData();
        if (res.getResultCode() != Activity.RESULT_OK || d == null || d.getData() == null) {
            call.reject("nenhum arquivo escolhido");
            return;
        }
        Uri u = d.getData();
        remember(u);
        String[] ns = nameSize(u);
        JSObject r = new JSObject();
        r.put("uri", u.toString());
        r.put("name", ns[0]);
        r.put("size", Long.parseLong(ns[1]));
        call.resolve(r);
    }

    @PluginMethod
    public void open(PluginCall call) {
        final String s = call.getString("uri");
        final double at = call.getDouble("at", 0.0);
        if (s == null) {
            call.reject("sem arquivo");
            return;
        }
        main.post(() -> {
            try {
                closeNow();
                Uri u = Uri.parse(s);
                pfd = getContext().getContentResolver().openFileDescriptor(u, "r");
                if (pfd == null) throw new Exception("nao consegui abrir o arquivo");
                cur = u;
                ViewGroup parent = (ViewGroup) getBridge().getWebView().getParent();
                box = new FrameLayout(getContext());
                box.setBackgroundColor(Color.BLACK);
                layout = new VLCVideoLayout(getContext());
                box.addView(layout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                parent.addView(box, 0, new ViewGroup.LayoutParams(1, 1));
                box.setVisibility(View.INVISIBLE);
                getBridge().getWebView().setBackgroundColor(Color.TRANSPARENT);
                mp = new MediaPlayer(lib());
                mp.attachViews(layout, null, false, true);
                Media m = new Media(lib(), pfd.getFileDescriptor());
                m.addOption(":audio-language=kor,ko,kr");
                if (at > 0) m.addOption(":start-time=" + String.format(Locale.US, "%.3f", at));
                mp.setMedia(m);
                m.release();
                vw = 0;
                vh = 0;
                mp.setEventListener(ev -> {
                    JSObject o = new JSObject();
                    switch (ev.type) {
                        case MediaPlayer.Event.TimeChanged:
                            o.put("t", ev.getTimeChanged() / 1000.0);
                            emit("time", o);
                            break;
                        case MediaPlayer.Event.LengthChanged:
                            o.put("dur", ev.getLengthChanged() / 1000.0);
                            emit("length", o);
                            break;
                        case MediaPlayer.Event.Playing:
                            emit("playing", null);
                            break;
                        case MediaPlayer.Event.Paused:
                            emit("paused", null);
                            break;
                        case MediaPlayer.Event.EndReached:
                            emit("ended", null);
                            break;
                        case MediaPlayer.Event.EncounteredError:
                            emit("error", null);
                            break;
                        case MediaPlayer.Event.Vout:
                            try {
                                Media.VideoTrack vt = mp.getCurrentVideoTrack();
                                if (vt != null) {
                                    vw = vt.width;
                                    vh = vt.height;
                                }
                            } catch (Exception e) {
                                // ignora
                            }
                            o.put("w", vw);
                            o.put("h", vh);
                            emit("vout", o);
                            break;
                        default:
                            break;
                    }
                });
                mp.play();
                call.resolve();
            } catch (Exception e) {
                closeNow();
                call.reject("VLC: " + e.getMessage());
            }
        });
    }

    /** posicao do video na tela (em px CSS) */
    @PluginMethod
    public void rect(PluginCall call) {
        final double x = call.getDouble("x", 0.0), y = call.getDouble("y", 0.0), w = call.getDouble("w", 0.0), h = call.getDouble("h", 0.0);
        final double dpr = call.getDouble("dpr", 1.0);
        final String bg = call.getString("bg");
        main.post(() -> {
            if (box != null) {
                if (w < 2 || h < 2) {
                    box.setVisibility(View.INVISIBLE);
                } else {
                    WebView wv = getBridge().getWebView();
                    ViewGroup.LayoutParams lp = box.getLayoutParams();
                    lp.width = (int) Math.round(w * dpr);
                    lp.height = (int) Math.round(h * dpr);
                    box.setLayoutParams(lp);
                    box.setX((float) (wv.getX() + x * dpr));
                    box.setY((float) (wv.getY() + y * dpr));
                    box.setVisibility(View.VISIBLE);
                }
                if (bg != null) {
                    try {
                        ((View) getBridge().getWebView().getParent()).setBackgroundColor(Color.parseColor(bg));
                    } catch (Exception e) {
                        // ignora
                    }
                }
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void play(PluginCall call) {
        main.post(() -> {
            if (mp != null) mp.play();
            call.resolve();
        });
    }

    @PluginMethod
    public void pause(PluginCall call) {
        main.post(() -> {
            if (mp != null) mp.pause();
            call.resolve();
        });
    }

    @PluginMethod
    public void seek(PluginCall call) {
        final double t = call.getDouble("t", 0.0);
        main.post(() -> {
            if (mp != null) mp.setTime((long) Math.max(0, t * 1000));
            call.resolve();
        });
    }

    @PluginMethod
    public void rate(PluginCall call) {
        final double r = call.getDouble("r", 1.0);
        main.post(() -> {
            if (mp != null) mp.setRate((float) r);
            call.resolve();
        });
    }

    @PluginMethod
    public void volume(PluginCall call) {
        final int v = call.getInt("v", 100);
        main.post(() -> {
            if (mp != null) mp.setVolume(v);
            call.resolve();
        });
    }

    @PluginMethod
    public void state(PluginCall call) {
        main.post(() -> {
            JSObject r = new JSObject();
            if (mp != null) {
                r.put("t", mp.getTime() / 1000.0);
                r.put("dur", mp.getLength() / 1000.0);
                r.put("playing", mp.isPlaying());
                r.put("w", vw);
                r.put("h", vh);
            }
            call.resolve(r);
        });
    }

    private TextureView findTexture(View v) {
        if (v instanceof TextureView) return (TextureView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextureView t = findTexture(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    /** print do quadro atual (JPEG em base64) */
    @PluginMethod
    public void shot(PluginCall call) {
        final int w = call.getInt("w", 360);
        main.post(() -> {
            try {
                TextureView tv = layout == null ? null : findTexture(layout);
                if (tv == null || !tv.isAvailable()) throw new Exception("sem imagem");
                int sw = Math.max(1, tv.getWidth()), sh = Math.max(1, tv.getHeight());
                Bitmap b = tv.getBitmap(w, Math.max(1, Math.round(w * (float) sh / sw)));
                if (b == null) throw new Exception("sem imagem");
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 70, os);
                b.recycle();
                JSObject r = new JSObject();
                r.put("jpg", Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP));
                call.resolve(r);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        });
    }

    /** audio de um trecho (WAV 22050 Hz mono), para o cartao; o VLC decodifica qualquer formato */
    @PluginMethod
    public void clip(PluginCall call) {
        final double a = call.getDouble("a", 0.0), b = call.getDouble("b", 0.0);
        final Uri u = cur;
        if (u == null || b <= a) {
            call.reject("sem video");
            return;
        }
        new Thread(() -> {
            ParcelFileDescriptor f = null;
            MediaPlayer p = null;
            File out = new File(getContext().getCacheDir(), "vlc-clip.wav");
            try {
                if (out.exists()) out.delete();
                f = getContext().getContentResolver().openFileDescriptor(u, "r");
                Media m = new Media(lib(), f.getFileDescriptor());
                m.addOption(":audio-language=kor,ko,kr");
                m.addOption(":start-time=" + String.format(Locale.US, "%.3f", a));
                m.addOption(":stop-time=" + String.format(Locale.US, "%.3f", b));
                m.addOption(":no-sout-video");
                m.addOption(":sout=#transcode{vcodec=none,acodec=s16l,channels=1,samplerate=22050}:std{access=file,mux=wav,dst=" + out.getAbsolutePath() + "}");
                m.addOption(":sout-keep");
                p = new MediaPlayer(lib());
                p.setMedia(m);
                m.release();
                final Object lock = new Object();
                final boolean[] done = { false };
                p.setEventListener(ev -> {
                    if (ev.type == MediaPlayer.Event.EndReached || ev.type == MediaPlayer.Event.EncounteredError || ev.type == MediaPlayer.Event.Stopped) {
                        synchronized (lock) {
                            done[0] = true;
                            lock.notifyAll();
                        }
                    }
                });
                p.play();
                long lim = System.currentTimeMillis() + 30000 + (long) ((b - a) * 3000);
                synchronized (lock) {
                    while (!done[0] && System.currentTimeMillis() < lim) lock.wait(500);
                }
                p.stop();
                if (!out.exists() || out.length() < 1000) throw new Exception("o VLC nao gravou o audio");
                byte[] buf = new byte[(int) out.length()];
                FileInputStream in = new FileInputStream(out);
                int o = 0;
                while (o < buf.length) {
                    int n = in.read(buf, o, buf.length - o);
                    if (n < 0) break;
                    o += n;
                }
                in.close();
                JSObject r = new JSObject();
                r.put("wav", Base64.encodeToString(buf, 0, o, Base64.NO_WRAP));
                call.resolve(r);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            } finally {
                try {
                    if (p != null) p.release();
                } catch (Exception e) {
                    // ignora
                }
                try {
                    if (f != null) f.close();
                } catch (Exception e) {
                    // ignora
                }
                out.delete();
            }
        }).start();
    }

    private void closeNow() {
        try {
            if (mp != null) {
                mp.setEventListener(null);
                mp.stop();
                mp.detachViews();
                mp.release();
            }
        } catch (Exception e) {
            // ignora
        }
        mp = null;
        try {
            if (box != null) ((ViewGroup) box.getParent()).removeView(box);
        } catch (Exception e) {
            // ignora
        }
        box = null;
        layout = null;
        try {
            if (pfd != null) pfd.close();
        } catch (Exception e) {
            // ignora
        }
        pfd = null;
        cur = null;
        try {
            getBridge().getWebView().setBackgroundColor(Color.WHITE);
        } catch (Exception e) {
            // ignora
        }
    }

    @PluginMethod
    public void close(PluginCall call) {
        main.post(() -> {
            closeNow();
            call.resolve();
        });
    }

    @Override
    protected void handleOnPause() {
        main.post(() -> {
            if (mp != null && mp.isPlaying()) mp.pause();
        });
    }

    @Override
    protected void handleOnDestroy() {
        main.post(() -> {
            closeNow();
            if (vlc != null) {
                vlc.release();
                vlc = null;
            }
        });
    }
}
