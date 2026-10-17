
package com.ashraf.rexvpn;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import java.util.Locale;

public final class VpnManager {

    public enum ConfigType {
        WIREGUARD,
        SING_BOX_JSON,
        VLESS,
        VMESS,
        TROJAN,
        SHADOWSOCKS,
        HYSTERIA2,
        TUIC,
        UNKNOWN
    }

    private VpnManager() {
        // Utility class
    }

    /**
     * Checks whether Android requires user approval
     * before this app can start a VPN service.
     *
     * A non-null Intent must be shown to the user
     * before starting the VPN service.
     */
    public static Intent getPermissionIntent(Context context) {
        return VpnService.prepare(context);
    }

    /**
     * Identifies common VPN configuration formats.
     * This does not establish a VPN connection.
     */
    public static ConfigType detectConfigType(String config) {
        if (config == null || config.trim().isEmpty()) {
            return ConfigType.UNKNOWN;
        }

        String value = config.trim();
        String lower = value.toLowerCase(Locale.ROOT);

        if (lower.startsWith("vless://")) {
            return ConfigType.VLESS;
        }

        if (lower.startsWith("vmess://")) {
            return ConfigType.VMESS;
        }

        if (lower.startsWith("trojan://")) {
            return ConfigType.TROJAN;
        }

        if (lower.startsWith("ss://")) {
            return ConfigType.SHADOWSOCKS;
        }

        if (lower.startsWith("hysteria2://")
                || lower.startsWith("hy2://")) {
            return ConfigType.HYSTERIA2;
        }

        if (lower.startsWith("tuic://")) {
            return ConfigType.TUIC;
        }

        if (lower.startsWith("{")
                && (lower.contains("\"outbounds\"")
                || lower.contains("\"inbounds\"")
                || lower.contains("\"route\""))) {
            return ConfigType.SING_BOX_JSON;
        }

        if (lower.contains("[interface]")
                && lower.contains("[peer]")) {
            return ConfigType.WIREGUARD;
        }

        return ConfigType.UNKNOWN;
    }

    public static String getConfigTypeName(ConfigType type) {
        if (type == null) {
            return "غير معروف";
        }

        switch (type) {
            case WIREGUARD:
                return "WireGuard";

            case SING_BOX_JSON:
                return "sing-box JSON";

            case VLESS:
                return "VLESS";

            case VMESS:
                return "VMess";

            case TROJAN:
                return "Trojan";

            case SHADOWSOCKS:
                return "Shadowsocks";

            case HYSTERIA2:
                return "Hysteria2";

            case TUIC:
                return "TUIC";

            default:
                return "صيغة غير معروفة";
        }
    }

    public static boolean isSupportedConfigFormat(String config) {
        return detectConfigType(config) != ConfigType.UNKNOWN;
    }
}
