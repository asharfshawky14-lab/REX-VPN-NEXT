
package com.ashraf.rexvpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;
import android.util.Log;

public class RexVpnService extends VpnService {

    public static final String ACTION_START =
            "com.ashraf.rexvpn.action.START";

    public static final String ACTION_STOP =
            "com.ashraf.rexvpn.action.STOP";

    public static final String ACTION_STATUS =
            "com.ashraf.rexvpn.action.STATUS";

    public static final String EXTRA_CONFIG =
            "com.ashraf.rexvpn.extra.CONFIG";

    public static final String EXTRA_STATUS =
            "com.ashraf.rexvpn.extra.STATUS";

    public static final String STATUS_DISCONNECTED =
            "DISCONNECTED";

    public static final String STATUS_CONNECTING =
            "CONNECTING";

    public static final String STATUS_CONNECTED =
            "CONNECTED";

    public static final String STATUS_ERROR =
            "ERROR";

    private static final String TAG = "RexVpnService";

    private String currentStatus = STATUS_DISCONNECTED;
    private String currentConfig = "";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "VPN service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopVpn();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            String config = intent.getStringExtra(EXTRA_CONFIG);

            if (config == null || config.trim().isEmpty()) {
                setStatus(STATUS_ERROR);
                stopSelf();
                return START_NOT_STICKY;
            }

            currentConfig = config.trim();
            setStatus(STATUS_CONNECTING);

            /*
             * The actual tunnel engine is not connected yet.
             * Do not report CONNECTED until a real engine
             * successfully establishes the tunnel.
             */
            Log.w(TAG, "Tunnel engine has not been attached");
            setStatus(STATUS_ERROR);
            stopSelf();

            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void stopVpn() {
        currentConfig = "";
        setStatus(STATUS_DISCONNECTED);
        stopSelf();
    }

    private void setStatus(String newStatus) {
        currentStatus = newStatus;

        Intent broadcast = new Intent(ACTION_STATUS);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_STATUS, currentStatus);
        sendBroadcast(broadcast);

        Log.i(TAG, "Status: " + currentStatus);
    }

    @Override
    public void onRevoke() {
        setStatus(STATUS_DISCONNECTED);
        currentConfig = "";
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        currentConfig = "";
        currentStatus = STATUS_DISCONNECTED;
        Log.i(TAG, "VPN service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
