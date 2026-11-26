package com.juliobrunno.figurinhas;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Roda o robô (Node.js) num processo separado (":robo", ver AndroidManifest).
 * Assim, se o robô der erro e fechar, só este processo morre: a tela do app continua aberta.
 */
public class RoboService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NodeRunner.start(this);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
