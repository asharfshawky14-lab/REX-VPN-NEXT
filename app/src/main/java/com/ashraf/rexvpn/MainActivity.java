package com.ashraf.rexvpn;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQUEST_CONFIG = 101;
    private static final int REQUEST_VPN_PERMISSION = 102;

    private final int bg = Color.rgb(8, 10, 20);
    private final int panel = Color.rgb(20, 23, 39);
    private final int purple = Color.rgb(133, 84, 255);
    private final int cyan = Color.rgb(0, 225, 255);
    private final int white = Color.WHITE;
    private final int muted = Color.rgb(160, 166, 190);

    private TextView status;
    private TextView configName;
    private TextView configInfo;
    private Button connectButton;

    private String configText = "";
    private String selectedName = "";

    private VpnEngine vpnEngine;
    private boolean connecting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        vpnEngine = new VpnEngine(this);
        showHome();
        refreshConnectionState();
    }

    private void showHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(20));
        root.setBackgroundColor(bg);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        TextView brand = text("REX VPN", 30, white, true);
        brand.setLetterSpacing(0.08f);
        page.addView(brand);

        TextView tagline = text(
                "THE POWER BEHIND THE SHADOW",
                11, cyan, true
        );
        tagline.setPadding(0, dp(4), 0, dp(22));
        page.addView(tagline);

        LinearLayout statusCard = card();
        statusCard.addView(text("حالة الاتصال", 15, muted, false));

        status = text("غير متصل", 23, white, true);
        status.setPadding(0, dp(10), 0, dp(6));
        statusCard.addView(status);

        statusCard.addView(text(
                "استورد ملف WireGuard صالحًا للاتصال",
                12, muted, false
        ));

        page.addView(statusCard, matchWrap());

        Button importButton = button("＋  استيراد كونفيج");
        importButton.setOnClickListener(v -> openConfigPicker());
        page.addView(importButton, marginTop(14));

        LinearLayout configCard = card();

        configName = text("لا يوجد ملف محدد", 16, white, true);
        configInfo = text(
                "استورد ملف إعدادات من جهازك",
                12, muted, false
        );

        configName.setPadding(0, 0, 0, dp(8));
        configCard.addView(configName);
        configCard.addView(configInfo);

        page.addView(configCard, marginTop(14));

        connectButton = button("اتصال");
        connectButton.setOnClickListener(v -> onConnectButtonPressed());
        page.addView(connectButton, marginTop(14));

        Button serversButton = secondaryButton("السيرفرات");
        serversButton.setOnClickListener(v -> showServers());
        page.addView(serversButton, marginTop(10));

        Button settingsButton = secondaryButton("الإعدادات");
        settingsButton.setOnClickListener(v -> showSettings());
        page.addView(settingsButton, marginTop(10));

        Button aboutButton = secondaryButton("حول التطبيق");
        aboutButton.setOnClickListener(v -> showAbout());
        page.addView(aboutButton, marginTop(10));

        TextView footer = text("REX VPN  •  NEXT", 11, muted, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(28), 0, dp(8));
        page.addView(footer);

        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private void openConfigPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(intent, REQUEST_CONFIG);
        } catch (Exception e) {
            toast("تعذر فتح مستعرض الملفات.");
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                startWireGuardConnection();
            } else {
                connecting = false;
                updateConnectionButton();
                setStatus("تم رفض إذن VPN");
                toast("لازم توافق على إذن VPN لتشغيل الاتصال.");
            }
            return;
        }

        if (requestCode != REQUEST_CONFIG
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        try {
            StringBuilder content = new StringBuilder();

            try (InputStream input =
                         getContentResolver().openInputStream(uri)) {

                if (input == null) {
                    throw new IllegalStateException(
                            "تعذر قراءة الملف."
                    );
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                input, StandardCharsets.UTF_8))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        content.append(line).append('\n');

                        if (content.length() > 1024 * 1024) {
                            throw new IllegalArgumentException(
                                    "حجم الملف أكبر من 1 ميجابايت."
                            );
                        }
                    }
                }
            }

            String imported = content.toString().trim();

            if (imported.isEmpty()) {
                throw new IllegalArgumentException(
                        "الملف فارغ."
                );
            }

            configText = imported;
            selectedName = getFileName(uri);

            configName.setText(
                    selectedName.isEmpty()
                            ? "ملف مستورد"
                            : selectedName
            );

            VpnEngine.EngineType type =
                    VpnEngine.detectEngine(configText);

            if (type == VpnEngine.EngineType.WIREGUARD) {
                configInfo.setText(
                        "تم استيراد ملف WireGuard."
                );
                toast("تم استيراد إعداد WireGuard.");
            } else {
                configInfo.setText(
                        "البروتوكول معروف، لكن محركه غير موصول بعد."
                );
                toast(
                        "ملف sing-box مستورد، لكن الاتصال به لم يُفعّل بعد."
                );
            }

        } catch (Exception e) {
            configText = "";
            selectedName = "";
            configName.setText("لا يوجد ملف محدد");
            configInfo.setText("تعذر تحميل الإعدادات.");
            toast("فشل استيراد الملف: " + errorMessage(e));
        }
    }

    private void onConnectButtonPressed() {
        if (vpnEngine.isWireGuardConnected()) {
            disconnectWireGuard();
            return;
        }

        if (connecting) {
            toast("جاري تجهيز الاتصال.");
            return;
        }

        if (configText.isEmpty()) {
            toast("استورد ملف إعدادات أولًا.");
            return;
        }

        try {
            VpnEngine.EngineType type =
                    VpnEngine.detectEngine(configText);

            if (type != VpnEngine.EngineType.WIREGUARD) {
                toast(
                        "الاتصال الفعلي بهذا البروتوكول لم يُربط بعد."
                );
                return;
            }

            Intent permissionIntent = VpnService.prepare(this);

            if (permissionIntent != null) {
                connecting = true;
                updateConnectionButton();
                startActivityForResult(
                        permissionIntent,
                        REQUEST_VPN_PERMISSION
                );
            } else {
                startWireGuardConnection();
            }

        } catch (Exception e) {
            connecting = false;
            updateConnectionButton();
            toast("الإعداد غير صالح: " + errorMessage(e));
        }
    }

    private void startWireGuardConnection() {
        connecting = true;
        setStatus("جاري الاتصال...");
        updateConnectionButton();

        new Thread(() -> {
            try {
                vpnEngine.connectWireGuard(
                        getApplicationContext(),
                        configText
                );

                runOnUiThread(() -> {
                    connecting = false;

                    if (vpnEngine.isWireGuardConnected()) {
                        setStatus("WireGuard متصل");
                        configInfo.setText(
                                "تم تشغيل نفق WireGuard."
                        );
                        toast("تم تشغيل نفق WireGuard.");
                    } else {
                        setStatus("لم يتأكد الاتصال");
                        toast("المحرك لم يؤكد تشغيل النفق.");
                    }

                    updateConnectionButton();
                });

            } catch (Exception e) {
                String message = errorMessage(e);

                runOnUiThread(() -> {
                    connecting = false;
                    setStatus("فشل الاتصال");
                    updateConnectionButton();
                    toast("فشل الاتصال: " + message);
                });
            }
        }).start();
    }

    private void disconnectWireGuard() {
        connecting = true;
        updateConnectionButton();
        setStatus("جاري الفصل...");

        new Thread(() -> {
            try {
                vpnEngine.disconnectWireGuard();

                runOnUiThread(() -> {
                    connecting = false;
                    setStatus("غير متصل");
                    updateConnectionButton();
                    toast("تم فصل اتصال WireGuard.");
                });

            } catch (Exception e) {
                String message = errorMessage(e);

                runOnUiThread(() -> {
                    connecting = false;
                    refreshConnectionState();
                    toast("تعذر الفصل: " + message);
                });
            }
        }).start();
    }

    private void refreshConnectionState() {
        if (vpnEngine != null && vpnEngine.isWireGuardConnected()) {
            setStatus("WireGuard متصل");
        } else {
            setStatus("غير متصل");
        }

        updateConnectionButton();
    }

    private void updateConnectionButton() {
        if (connectButton == null) {
            return;
        }

        if (connecting) {
            connectButton.setText("جاري التنفيذ...");
        } else if (vpnEngine != null
                && vpnEngine.isWireGuardConnected()) {
            connectButton.setText("فصل الاتصال");
        } else {
            connectButton.setText("اتصال");
        }
    }

    private void setStatus(String value) {
        if (status != null) {
            status.setText(value);
        }
    }

    private String getFileName(Uri uri) {
        String name = uri.getLastPathSegment();

        if (name == null || name.trim().isEmpty()) {
            return "config.txt";
        }

        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private String errorMessage(Exception e) {
        String message = e.getMessage();

        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }

        return message;
    }

    private void showServers() {
        setSimplePage(
                "السيرفرات",
                "إدارة السيرفرات ستُضاف في مرحلة لاحقة."
        );
    }

    private void showSettings() {
        setSimplePage(
                "الإعدادات",
                "إعدادات المظهر والاتصال ستُضاف لاحقًا."
        );
    }

    private void showAbout() {
        setSimplePage(
                "حول REX VPN",
                "واجهة مشروع REX VPN NEXT.\n\n"
                        + "تم ربط زر الاتصال بمحرك WireGuard الموجود "
                        + "في المشروع. نجاح تشغيل النفق لا يضمن وحده "
                        + "أن الخادم يتيح الوصول إلى الإنترنت."
        );
    }

    private void setSimplePage(String title, String message) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setBackgroundColor(bg);

        TextView heading = text(title, 25, white, true);
        root.addView(heading);

        TextView body = text(message, 15, muted, false);
        body.setPadding(0, dp(20), 0, dp(24));
        root.addView(body);

        Button back = button("العودة للرئيسية");
        back.setOnClickListener(v -> {
            showHome();
            refreshConnectionState();
        });
        root.addView(back);

        setContentView(root);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(panel);
        shape.setCornerRadius(dp(18));
        shape.setStroke(dp(1), Color.rgb(42, 46, 69));
        layout.setBackground(shape);

        return layout;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(white);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{purple, Color.rgb(83, 57, 185)}
        );
        shape.setCornerRadius(dp(14));
        b.setBackground(shape);

        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(cyan);
        b.setTextSize(14);
        b.setAllCaps(false);

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(panel);
        shape.setCornerRadius(dp(14));
        shape.setStroke(dp(1), Color.rgb(49, 55, 80));
        b.setBackground(shape);

        return b;
    }

    private TextView text(
            String value,
            int size,
            int color,
            boolean bold
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        return params;
    }

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
        ).show();
    }
}
