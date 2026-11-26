package com.juliobrunno.figurinhas;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 100;
    private static final int REQ_PACK = 200;
    private static final int REQ_MIC = 300;
    private PermissionRequest pendingMic;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        startRobo();

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    boolean audio = false;
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                    }
                    if (!audio) { req.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        if (pendingMic != null) pendingMic.deny();
                        pendingMic = req;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                });
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        if (savedInstanceState == null) {
            web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
        } else {
            web.restoreState(savedInstanceState);
        }
    }

    private void startRobo() {
        try {
            startService(new Intent(this, RoboService.class));
        } catch (Exception e) {
            NodeRunner.writeLog(this, "não consegui iniciar o serviço do robô: " + e);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
        } else if (requestCode == REQ_PACK) {
            if (resultCode == RESULT_OK) {
                callJs(true, "Pacote adicionado ao WhatsApp!");
            } else {
                String err = data != null ? data.getStringExtra("validation_error") : null;
                callJs(false, err != null ? "O WhatsApp recusou: " + err : "Pacote não foi adicionado.");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC && pendingMic != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingMic.deny();
                toast("Sem permissão do microfone não dá para gravar a voz.");
            }
            pendingMic = null;
        }
    }

    private void callJs(boolean ok, String msg) {
        String safe = JSONObject.quote(msg);
        web.post(() -> web.evaluateJavascript("window.onPackResult&&window.onPackResult(" + ok + "," + safe + ")", null));
    }

    private void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    private static String clean(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String whatsappPackage() {
        for (String p : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            if (getPackageManager().getLaunchIntentForPackage(p) != null) return p;
        }
        return null;
    }

    class Bridge {
        @JavascriptInterface
        public void shareFile(String b64, String mime, String name) {
            try {
                File dir = new File(getCacheDir(), "share");
                dir.mkdirs();
                File f = new File(dir, clean(name));
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(Base64.decode(b64, Base64.DEFAULT));
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                String wa = whatsappPackage();
                if (wa != null) {
                    i.setPackage(wa);
                    runOnUiThread(() -> startActivity(i));
                } else {
                    runOnUiThread(() -> startActivity(Intent.createChooser(i, "Enviar com")));
                }
            } catch (Exception e) {
                toast("Não consegui enviar: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void saveFile(String b64, String mime, String name) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    boolean video = mime != null && mime.startsWith("video/");
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, clean(name));
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, video ? "Movies/Figuritas Zap" : "Pictures/Figuritas Zap");
                    Uri uri = getContentResolver().insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                        o.write(bytes);
                    }
                    toast(video ? "Salvo em Vídeos › Figuritas Zap" : "Salvo em Fotos › Figuritas Zap");
                } else {
                    shareFile(b64, mime, name);
                }
            } catch (Exception e) {
                toast("Não consegui salvar: " + e.getMessage());
            }
        }

        /** Conversa com o robô (Node.js) que roda no próprio celular. Responde em window.onRobo(id, código, texto). */
        @JavascriptInterface
        public void robo(String method, String path, String body, String id) {
            new Thread(() -> {
                int code;
                String text;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:47321" + path).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(120000);
                    if (body != null && !body.isEmpty()) {
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
                    }
                    code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    if (in != null) {
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                        in.close();
                    }
                    text = bo.toString("UTF-8");
                } catch (Exception e) {
                    code = 0;
                    startRobo(); // se o robô tinha parado, liga de novo (ele roda em outro processo)
                    String log = NodeRunner.tailLog(MainActivity.this, 600).trim();
                    String why = "O robô ainda está ligando. Espere alguns segundos e tente de novo." + (log.isEmpty() ? "" : "\n\nRegistro do robô:\n" + log);
                    try { text = new JSONObject().put("error", why).toString(); } catch (Exception x) { text = "{}"; }
                }
                final String js = "window.onRobo&&window.onRobo(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(text) + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public void addPack(String json) {
            try {
                if (whatsappPackage() == null) {
                    callJs(false, "WhatsApp não está instalado neste celular.");
                    return;
                }
                JSONObject j = new JSONObject(json);
                String id = "pack_" + System.currentTimeMillis();
                File dir = new File(new File(getFilesDir(), "packs"), id);
                dir.mkdirs();
                try (FileOutputStream o = new FileOutputStream(new File(dir, "tray.png"))) {
                    o.write(Base64.decode(j.getString("tray"), Base64.DEFAULT));
                }
                JSONArray st = j.getJSONArray("stickers");
                for (int k = 0; k < st.length(); k++) {
                    try (FileOutputStream o = new FileOutputStream(new File(dir, (k + 1) + ".webp"))) {
                        o.write(Base64.decode(st.getString(k), Base64.DEFAULT));
                    }
                }
                JSONObject info = new JSONObject();
                info.put("name", j.optString("name", "Minhas figurinhas"));
                info.put("publisher", j.optString("publisher", "Figuritas Zap"));
                info.put("count", st.length());
                info.put("animated", j.optBoolean("animated", false));
                try (FileOutputStream o = new FileOutputStream(new File(dir, "info.json"))) {
                    o.write(info.toString().getBytes("UTF-8"));
                }
                Intent i = new Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK");
                i.putExtra("sticker_pack_id", id);
                i.putExtra("sticker_pack_authority", getPackageName() + ".stickercontentprovider");
                i.putExtra("sticker_pack_name", info.getString("name"));
                runOnUiThread(() -> {
                    try {
                        startActivityForResult(i, REQ_PACK);
                    } catch (ActivityNotFoundException e) {
                        callJs(false, "Atualize o WhatsApp para adicionar pacotes.");
                    }
                });
            } catch (Exception e) {
                callJs(false, "Erro ao montar o pacote: " + e.getMessage());
            }
        }
    }
}
