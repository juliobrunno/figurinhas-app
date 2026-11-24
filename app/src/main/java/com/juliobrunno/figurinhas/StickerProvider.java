package com.juliobrunno.figurinhas;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Entrega os pacotes de figurinhas para o WhatsApp (contrato oficial de pacotes de terceiros). */
public class StickerProvider extends ContentProvider {
    private static final String[] PACK_COLS = {
            "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
            "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
            "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website", "image_data_version",
            "whatsapp_will_not_cache_stickers", "animated_sticker_pack"};
    private static final String[] STICKER_COLS = {"sticker_file_name", "sticker_emoji", "sticker_accessibility_text"};

    @Override
    public boolean onCreate() { return true; }

    private String authority() { return getContext().getPackageName() + ".stickercontentprovider"; }

    private File packsDir() { return new File(getContext().getFilesDir(), "packs"); }

    private JSONObject info(File dir) {
        try (FileInputStream in = new FileInputStream(new File(dir, "info.json"))) {
            byte[] b = new byte[(int) new File(dir, "info.json").length()];
            int n = in.read(b);
            return new JSONObject(new String(b, 0, n, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private void addPackRow(MatrixCursor c, File dir) {
        JSONObject i = info(dir);
        if (i == null) return;
        c.addRow(new Object[]{dir.getName(), i.optString("name"), i.optString("publisher"), "tray.png",
                "", "", "", "", "", "", "1", 0, 0});
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        List<String> seg = uri.getPathSegments();
        if (seg.isEmpty()) return null;
        String kind = seg.get(0);
        if ("metadata".equals(kind)) {
            MatrixCursor c = new MatrixCursor(PACK_COLS);
            if (seg.size() > 1) {
                File d = new File(packsDir(), seg.get(1));
                if (d.isDirectory()) addPackRow(c, d);
            } else {
                File[] all = packsDir().listFiles();
                if (all != null) for (File d : all) if (d.isDirectory()) addPackRow(c, d);
            }
            c.setNotificationUri(getContext().getContentResolver(), uri);
            return c;
        }
        if ("stickers".equals(kind) && seg.size() > 1) {
            MatrixCursor c = new MatrixCursor(STICKER_COLS);
            File d = new File(packsDir(), seg.get(1));
            String[] names = d.list((dir, n) -> n.endsWith(".webp"));
            if (names != null) {
                Arrays.sort(names, (a, b) -> Integer.compare(num(a), num(b)));
                for (String n : names) c.addRow(new Object[]{n, "😀", "Figurinha"});
            }
            c.setNotificationUri(getContext().getContentResolver(), uri);
            return c;
        }
        return null;
    }

    private static int num(String n) {
        try { return Integer.parseInt(n.replace(".webp", "")); } catch (Exception e) { return 0; }
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 3 || !"stickers_asset".equals(seg.get(0))) throw new FileNotFoundException(uri.toString());
        String id = seg.get(1), file = seg.get(2);
        if (id.contains("..") || file.contains("..") || id.contains("/") || file.contains("/"))
            throw new FileNotFoundException(uri.toString());
        File f = new File(new File(packsDir(), id), file);
        if (!f.isFile()) throw new FileNotFoundException(uri.toString());
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public String getType(Uri uri) {
        List<String> seg = uri.getPathSegments();
        if (seg.isEmpty()) return null;
        switch (seg.get(0)) {
            case "metadata":
                return (seg.size() > 1 ? "vnd.android.cursor.item/vnd." : "vnd.android.cursor.dir/vnd.") + authority() + ".metadata";
            case "stickers":
                return "vnd.android.cursor.dir/vnd." + authority() + ".stickers";
            case "stickers_asset":
                return seg.get(seg.size() - 1).endsWith(".png") ? "image/png" : "image/webp";
            default:
                return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
