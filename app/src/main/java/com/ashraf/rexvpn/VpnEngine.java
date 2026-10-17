package com.ashraf.rexvpn;

import android.content.Context;
import android.net.VpnService;
import android.util.Log;

import com.wireguard.android.backend.Backend;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class VpnEngine {

    private static final String TAG = "REXVPN";

    public enum EngineType {
        WIREGUARD,
        SING_BOX
    }

    private final Backend wireGuardBackend;
    private final Tunnel wireGuardTunnel;

    public VpnEngine(Context context) {
        Context appContext = context.getApplicationContext();

        wireGuardBackend = new GoBackend(appContext);

        wireGuardTunnel = new Tunnel() {
            @Override
            public String getName() {
                return "REXVPN";
            }

            @Override
            public void onStateChange(State state) {
                Log.i(TAG, "WireGuard state: " + state);
            }
        };
    }

    public static EngineType detectEngine(String config) {
        if (config == null || config.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "ملف الإعداد فارغ"
            );
        }

        String value = config.trim();
        String lower = value.toLowerCase(Locale.ROOT);

        if (lower.contains("[interface]")
                && lower.contains("[peer]")) {
            return EngineType.WIREGUARD;
        }

        if (lower.startsWith("vless://")
                || lower.startsWith("vmess://")
                || lower.startsWith("trojan://")
                || lower.startsWith("ss://")
                || lower.startsWith("hysteria2://")
                || lower.startsWith("hy2://")
                || lower.startsWith("tuic://")
                || (lower.startsWith("{")
                    && lower.contains("\"outbounds\""))) {
            return EngineType.SING_BOX;
        }

        throw new IllegalArgumentException(
                "نوع إعداد VPN غير مدعوم أو غير معروف"
        );
    }

    public synchronized void connectWireGuard(
            Context context,
            String configText
    ) throws Exception {

        if (VpnService.prepare(context) != null) {
            throw new SecurityException(
                    "لازم توافق على إذن VPN من أندرويد أولًا"
            );
        }

        if (detectEngine(configText) != EngineType.WIREGUARD) {
            throw new IllegalArgumentException(
                    "الإعداد ده مش ملف WireGuard"
            );
        }

        Config config;

        try (ByteArrayInputStream input =
                     new ByteArrayInputStream(
                             configText.getBytes(StandardCharsets.UTF_8))) {
            config = Config.parse(input);
        }

        wireGuardBackend.setState(
                wireGuardTunnel,
                Tunnel.State.UP,
                config
        );

        if (wireGuardBackend.getState(wireGuardTunnel)
                != Tunnel.State.UP) {
            throw new IllegalStateException(
                    "محرك WireGuard لم يؤكد تشغيل النفق"
            );
        }
    }

    public synchronized void disconnectWireGuard()
            throws Exception {

        if (wireGuardBackend.getState(wireGuardTunnel)
                == Tunnel.State.UP) {
            wireGuardBackend.setState(
                    wireGuardTunnel,
                    Tunnel.State.DOWN,
                    null
            );
        }
    }

    public synchronized boolean isWireGuardConnected() {
        try {
            return wireGuardBackend.getState(wireGuardTunnel)
                    == Tunnel.State.UP;
        } catch (Exception e) {
            Log.e(TAG, "Unable to read WireGuard state", e);
            return false;
        }
    }
}
