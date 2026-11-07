package tw.nekomimi.nekogram.update;

import android.app.Activity;
import android.app.ProgressDialog;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;

/**
 * UI glue for the GitHub-release updater: check -> available dialog -> download with
 * progress -> verify -> system install sheet. Every step is user-tapped; the install
 * itself is confirmed in the system package installer, never silent.
 */
public final class GithubUpdateUi {

    private GithubUpdateUi() {
    }

    public static void checkAndShow(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setMessage(LocaleController.getString(R.string.Loading));
        progress.setCancelable(false);
        progress.show();
        long installed = GithubAppUpdate.currentVersionCode();
        Utilities.globalQueue.postRunnable(() -> {
            GithubAppUpdate.UpdateInfo info = GithubAppUpdate.fetchLatest();
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (activity.isFinishing()) return;
                if (info == null) {
                    Toast.makeText(activity, LocaleController.getString(R.string.ErrorOccurred), Toast.LENGTH_SHORT).show();
                } else if (!GithubAppUpdate.isNewer(info, installed)) {
                    Toast.makeText(activity, LocaleController.getString(R.string.RoidgramLatestVersion), Toast.LENGTH_SHORT).show();
                } else {
                    showAvailable(activity, info);
                }
            });
        });
    }

    private static void showAvailable(Activity activity, GithubAppUpdate.UpdateInfo info) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.RoidgramUpdateAvailableTitle));
        String notes = info.notes == null || info.notes.isEmpty() ? "" : "\n\n" + info.notes;
        builder.setMessage(info.versionName + " (" + info.versionCode + ")" + notes);
        builder.setPositiveButton(LocaleController.getString(R.string.Update), (dialog, which) -> downloadAndInstall(activity, info));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private static void downloadAndInstall(Activity activity, GithubAppUpdate.UpdateInfo info) {
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setMessage(LocaleController.getString(R.string.Downloading));
        progress.setCancelable(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            File apk;
            try {
                apk = GithubAppUpdate.download(activity, info, percent ->
                        AndroidUtilities.runOnUIThread(() -> progress.setProgress(percent)));
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    Toast.makeText(activity, LocaleController.getString(R.string.ErrorOccurred), Toast.LENGTH_SHORT).show();
                });
                return;
            }
            boolean verified = GithubAppUpdate.verifyApk(activity, apk, info);
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (activity.isFinishing()) return;
                if (!verified) {
                    Toast.makeText(activity, LocaleController.getString(R.string.ErrorOccurred), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (GithubAppUpdate.canInstall(activity)) {
                    GithubAppUpdate.promptInstall(activity, apk);
                } else {
                    // First update needs the one-time unknown-sources grant; the user taps
                    // Check for updates again afterwards to re-verify and install.
                    GithubAppUpdate.openUnknownSourcesSettings(activity);
                }
            });
        });
    }
}
