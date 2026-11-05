package org.telegram.ui;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.telegram.messenger.ApiCredentials;

import tw.nekomimi.nekogram.NekoXConfig;

/**
 * Roidgram first-launch screen: the user enters their own Telegram api_id/api_hash
 * (created at my.telegram.org) before the normal phone-number/code login. Shown only
 * while ApiCredentials.needsSetup() is true; ApplicationLoader.postInitApplication is
 * gated on the same condition, so no account or network service has started yet.
 * Values are validated here and stored via ApiCredentials (hash encrypted with an
 * AndroidKeyStore-backed key); nothing entered on this screen is logged.
 */
public class ApiSetupActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        // SDK 35 enforces edge-to-edge: the window stays full-height even with
        // adjustResize, so without inset handling the IME covers the lower field and
        // the save button and a fully covered control drops out of the accessibility
        // tree (unreachable by keyboard focus navigation). Apply system-bar and IME
        // insets as ScrollView padding: the scrollable area shrinks to the visible
        // region, focus changes scroll the focused field back into view, and every
        // control stays reachable on short screens.
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(),
                    Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        // Light screen, so status bar icons must be dark: SDK 35 enforces
        // edge-to-edge and otherwise draws them white on this light background.
        new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);

        TextView title = new TextView(this);
        title.setText("Roidgram setup");
        title.setTextSize(22);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Roidgram needs your own Telegram API credentials before the first login.\n\n"
                + "1. Open https://my.telegram.org and sign in with your Telegram account.\n"
                + "2. Open \"API development tools\" and create an application.\n"
                + "3. Enter the api_id and api_hash below, then continue to the normal login.\n\n"
                + "The api_hash is stored encrypted on this device (Android Keystore); the api_id is kept in the app's private settings.\n\n"
                + "Roidgram uses them only to connect to Telegram, like any Telegram app.");
        info.setTextSize(15);
        info.setPadding(0, dp(12), 0, dp(16));
        root.addView(info);

        EditText apiId = new EditText(this);
        apiId.setHint("api_id");
        apiId.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(apiId, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText apiHash = new EditText(this);
        apiHash.setHint("api_hash");
        apiHash.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        root.addView(apiHash, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView error = new TextView(this);
        error.setTextColor(0xFFD32F2F);
        error.setPadding(0, dp(8), 0, 0);
        root.addView(error);

        Button save = new Button(this);
        save.setText("Save and continue");
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        save.setOnClickListener(v -> {
            String idText = apiId.getText().toString().trim();
            String hash = apiHash.getText().toString().trim();
            int id;
            try {
                id = Integer.parseInt(idText);
            } catch (NumberFormatException e) {
                id = 0;
            }
            if (id <= 0) {
                error.setText("api_id must be a positive number.");
                return;
            }
            if (!hash.matches("[0-9a-fA-F]{32}")) {
                error.setText("api_hash must be 32 hexadecimal characters.");
                return;
            }
            try {
                ApiCredentials.storeHash(this, hash);
            } catch (Throwable t) {
                error.setText("Could not store the credentials securely on this device.");
                return;
            }
            NekoXConfig.customApi = NekoXConfig.API_TYPE_CUSTOM;
            NekoXConfig.customAppId = id;
            NekoXConfig.customAppHash = hash;
            NekoXConfig.saveCustomApi();
            Toast.makeText(this, "Saved. Restarting Roidgram...", Toast.LENGTH_SHORT).show();

            // The native connection layer reads the api id once at process start, so
            // relaunch into a fresh process to initialise it with these credentials.
            Intent relaunch = new Intent(this, LaunchActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pending = PendingIntent.getActivity(this, 0, relaunch,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            alarms.set(AlarmManager.RTC, System.currentTimeMillis() + 300, pending);
            finish();
            Runtime.getRuntime().exit(0);
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
