package com.juliobrunno.figurinhas;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 100;
    private static final int REQ_PACK = 200;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

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
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        if (savedInstanceState == null) {
            web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
        } else {
            web.restoreState(savedInstanceState);
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
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME, clean(name));
                    v.put(MediaStore.Images.Media.MIME_TYPE, mime);
                    v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Figurinhas");
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                        o.write(bytes);
                    }
                    toast("Salvo em Fotos › Figurinhas");
                } else {
                    shareFile(b64, mime, name);
                }
            } catch (Exception e) {
                toast("Não consegui salvar: " + e.getMessage());
            }
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
                info.put("publisher", j.optString("publisher", "Fábrica de Figurinhas"));
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
