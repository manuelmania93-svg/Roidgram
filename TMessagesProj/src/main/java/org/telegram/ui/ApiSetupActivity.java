package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import tw.nekomimi.nekogram.helpers.AppRestartHelper;

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
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        // Fields-first ordering is the reliable layer: on the API-35 emulator
        // the IME gives this window no signal at all - no adjustResize relayout
        // (manifest + programmatic), ime() insets stay 0, and the visible display
        // frame keeps full height (smoke 41960442210: keyboard visibly covering
        // api_hash while keyboardBottom measured 0) - so NO padding computation
        // can find the keyboard there. With the interactive controls at the top
        // they stay above the keyboard top on small screens without any signal;
        // the system-bar padding below still applies where insets do dispatch.
        final int[] sysInsets = {0, 0};
        final int[] keyboardBottom = {0};
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            sysInsets[0] = bars.top;
            sysInsets[1] = bars.bottom;
            v.setPadding(0, sysInsets[0], 0, Math.max(sysInsets[1], keyboardBottom[0]));
            return insets;
        });
        final View decor = getWindow().getDecorView();
        final android.graphics.Rect visibleFrame = new android.graphics.Rect();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (decor.getHeight() <= 0) return;
            decor.getWindowVisibleDisplayFrame(visibleFrame);
            keyboardBottom[0] = Math.max(0, decor.getHeight() - visibleFrame.bottom);
            scroll.setPadding(0, sysInsets[0], 0, Math.max(sysInsets[1], keyboardBottom[0]));
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

        EditText apiId = new EditText(this);
        apiId.setHint("api_id");
        apiId.setContentDescription("api_id");
        apiId.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(apiId, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText apiHash = new EditText(this);
        apiHash.setHint("api_hash");
        apiHash.setContentDescription("api_hash");
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

        TextView info = new TextView(this);
        info.setText("Roidgram needs your own Telegram API credentials before the first login.\n\n"
                + "1. Open https://my.telegram.org and sign in with your Telegram account.\n"
                + "2. Open \"API development tools\" and create an application.\n"
                + "3. Enter the api_id and api_hash above, then continue to the normal login.\n\n"
                + "The api_hash is stored encrypted on this device (Android Keystore); the api_id is kept in the app's private settings.\n\n"
                + "Roidgram uses them only to connect to Telegram, like any Telegram app.");
        info.setTextSize(15);
        info.setPadding(0, dp(12), 0, dp(16));
        root.addView(info);

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
                // The hash itself is never logged; the exception type/stack is needed to
                // diagnose keystore/cipher failures on devices where secure storage misbehaves.
                android.util.Log.e("ApiCredentials", "storeHash failed", t);
                error.setText("Could not store the credentials securely on this device.");
                return;
            }
            NekoXConfig.customApi = NekoXConfig.API_TYPE_CUSTOM;
            NekoXConfig.customAppId = id;
            NekoXConfig.customAppHash = hash;
            NekoXConfig.saveCustomApi();
            Toast.makeText(this, "Saved. Restarting Roidgram...", Toast.LENGTH_SHORT).show();

            // storeHash()/saveCustomApi() persist with apply(); flush the shared
            // "nekox_config" queue synchronously before the restart helper kills
            // this process, so the saved credentials are guaranteed on disk.
            getSharedPreferences("nekox_config", MODE_PRIVATE).edit().commit();

            // Restart through the app's own two-process helper: every activity start
            // happens while one of its windows is foreground, so no blocked
            // background activity launch is involved (the alarm + activity
            // PendingIntent relaunch used here before is blocked for targetSdk 35+,
            // and opting the PendingIntent in cannot help because a killed process
            // satisfies no background-launch exception when the alarm fires). The
            // fresh main process boots the normal path and initialises the native
            // layer with the credentials just saved.
            AppRestartHelper.triggerRebirth(this, new Intent(this, LaunchActivity.class));
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
