package com.juliobrunno.figurinhas;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Liga o robô (Node.js) que envia figurinhas com som. Só pode ser ligado uma vez por processo. */
public class NodeRunner {
    private static final String TAG = "FiguritasRobo";
    private static boolean started = false;
    public static volatile String problem = null;

    static native int startNodeWithArguments(String[] arguments);

    public static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(null, () -> {
            try {
                System.loadLibrary("node");
                System.loadLibrary("native-lib");
                File dir = new File(app.getFilesDir(), "nodejs-project");
                File mark = new File(app.getFilesDir(), "nodejs-project.versao");
                String stamp = String.valueOf(app.getPackageManager().getPackageInfo(app.getPackageName(), 0).lastUpdateTime);
                if (!dir.isDirectory() || !stamp.equals(readText(mark))) {
                    deleteAll(dir);
                    unzipAsset(app, "nodejs-project.zip", dir);
                    try (FileOutputStream o = new FileOutputStream(mark)) { o.write(stamp.getBytes("UTF-8")); }
                }
                File data = new File(app.getFilesDir(), "robo-dados");
                data.mkdirs();
                Log.i(TAG, "ligando o robô");
                int code = startNodeWithArguments(new String[]{"node", new File(dir, "main.mjs").getAbsolutePath(), data.getAbsolutePath()});
                problem = "O robô parou (código " + code + ").";
                Log.e(TAG, problem);
            } catch (Throwable e) {
                problem = "Não consegui ligar o robô: " + e;
                Log.e(TAG, problem, e);
            }
        }, "robo-node", 16 * 1024 * 1024);
        t.start();
    }

    private static String readText(File f) {
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteAll(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteAll(k);
        }
        f.delete();
    }

    private static void unzipAsset(Context ctx, String name, File dest) throws Exception {
        dest.mkdirs();
        String root = dest.getCanonicalPath() + File.separator;
        byte[] buf = new byte[64 * 1024];
        try (InputStream raw = ctx.getAssets().open(name); ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) continue; // proteção contra caminhos estranhos
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
    }
}
