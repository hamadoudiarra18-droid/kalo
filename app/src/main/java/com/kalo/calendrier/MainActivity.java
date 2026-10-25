package com.kalo.calendrier;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_GEO = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#173f37"));
            getWindow().setNavigationBarColor(Color.parseColor("#f5f4ee"));
        }

        // Cadre qui laisse la place à la barre d'état (en haut) et aux touches (en bas)
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#173f37"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#f5f4ee"));
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setGeolocationDatabasePath(getFilesDir().getPath());
        s.setAllowFileAccess(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "KaloAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file:")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, true);
                } else {
                    geoOrigin = origin;
                    geoCallback = callback;
                    if (Build.VERSION.SDK_INT >= 23) {
                        requestPermissions(new String[]{
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION}, REQ_GEO);
                    } else {
                        callback.invoke(origin, true, true);
                    }
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choisir une sauvegarde Kalo"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_GEO && geoCallback != null) {
            boolean ok = hasLocationPermission();
            geoCallback.invoke(geoOrigin, ok, ok);
            geoCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void toast(final String m) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Enregistre une image (carte WhatsApp) dans la Galerie, dossier Pictures/Kalo. */
    private Uri writeImage(String name, String base64, String mime) throws Exception {
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, mime == null ? "image/png" : mime);
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Kalo");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("insertion impossible");
            OutputStream out = getContentResolver().openOutputStream(uri);
            out.write(bytes);
            out.close();
            return uri;
        }
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        String url = MediaStore.Images.Media.insertImage(getContentResolver(), bmp, name, "Kalo");
        if (url == null) throw new Exception("insertion impossible");
        return Uri.parse(url);
    }

    /** Fonctions appelées par la page du calendrier. */
    private class Bridge {

        @JavascriptInterface
        public void updateWidget(String json) {
            getSharedPreferences(KaloWidget.PREFS, MODE_PRIVATE).edit().putString(KaloWidget.KEY, json).apply();
            KaloWidget.refreshAll(getApplicationContext());
        }

        /** Couleurs des barres système selon le mode d'interface (Kalo, Nuit, Bogolan). */
        @JavascriptInterface
        public void setSystemBars(final String status, final String nav, final boolean lightNav) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int sc = Color.parseColor(status), nc = Color.parseColor(nav);
                        if (Build.VERSION.SDK_INT >= 21) {
                            getWindow().setStatusBarColor(sc);
                            getWindow().setNavigationBarColor(nc);
                        }
                        View root = (View) web.getParent();
                        if (root != null) root.setBackgroundColor(sc);
                        if (Build.VERSION.SDK_INT >= 26) {
                            int f = getWindow().getDecorView().getSystemUiVisibility();
                            f = lightNav ? (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                            getWindow().getDecorView().setSystemUiVisibility(f);
                        }
                    } catch (Exception ignored) { }
                }
            });
        }

        /** Rappels : la page envoie la liste, l'appli les programme. */
        @JavascriptInterface
        public void scheduleReminders(String json) {
            ReminderReceiver.save(getApplicationContext(), json);
        }

        /** Demande l'autorisation d'afficher des notifications (Android 13 et plus). */
        @JavascriptInterface
        public void requestNotifications() {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
                    }
                });
            }
        }

        /** « Télécharger » la carte : enregistrée dans la Galerie. */
        @JavascriptInterface
        public void saveImage(String name, String base64, String mime) {
            try {
                writeImage(name, base64, mime);
                toast("Carte enregistrée dans la Galerie (dossier Kalo).");
            } catch (Exception e) {
                toast("Impossible d'enregistrer la carte.");
            }
        }

        /** « Partager » la carte : ouvre le menu de partage (WhatsApp, etc.). */
        @JavascriptInterface
        public void shareImage(String name, String base64, String mime) {
            shareImage(name, base64, mime, null);
        }

        @JavascriptInterface
        public void shareImage(final String name, final String base64, final String mime, final String caption) {
            try {
                final Uri uri = writeImage(name, base64, mime);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType(mime == null ? "image/png" : mime);
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        if (caption != null && caption.length() > 0) send.putExtra(Intent.EXTRA_TEXT, caption);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(Intent.createChooser(send, "Partager la carte Kalo"));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Aucune appli de partage trouvée.", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            } catch (Exception e) {
                toast("Impossible de préparer la carte.");
            }
        }

        /** Sauvegarde des données ou copie du calendrier dans « Téléchargements ». */
        @JavascriptInterface
        public void saveFile(final String name, final String text) {
            String message;
            try {
                byte[] bytes = text.getBytes(Charset.forName("UTF-8"));
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, name.endsWith(".json") ? "application/json" : "text/html");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    out.write(bytes);
                    out.close();
                    message = "Enregistré dans Téléchargements : " + name;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, name);
                    FileOutputStream out = new FileOutputStream(f);
                    out.write(bytes);
                    out.close();
                    message = "Enregistré : " + f.getAbsolutePath();
                }
            } catch (Exception e) {
                message = "Impossible d'enregistrer le fichier.";
            }
            toast(message);
        }
    }
}
