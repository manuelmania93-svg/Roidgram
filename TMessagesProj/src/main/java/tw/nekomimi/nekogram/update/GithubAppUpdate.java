package tw.nekomimi.nekogram.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * In-app updater for Roidgram, mirroring the File Explorer CL flow: check the latest
 * published GitHub release, download its APK only after the user taps, verify the
 * download (SHA-256 digest from the signed release manifest, then package name,
 * versionCode and signing certificate against the installed app), and hand the verified
 * file to the system package installer. The user confirms the system install sheet;
 * the app never installs silently.
 *
 * Release layout this expects: the latest GitHub release carries a `roidgram-update.json`
 * asset with {"versionCode", "versionName", "apkUrl", "sha256", "notes"?} pointing at the
 * APK asset on this repository's own release page.
 */
public final class GithubAppUpdate {

    public static class UpdateInfo {
        public final long versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String sha256;
        public final String notes;

        UpdateInfo(long versionCode, String versionName, String apkUrl, String sha256, String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.notes = notes;
        }
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/manuelmania93-svg/Roidgram/releases/latest";
    static final String MANIFEST_ASSET_NAME = "roidgram-update.json";
    private static final String RELEASE_DOWNLOAD_PREFIX =
            "https://github.com/manuelmania93-svg/Roidgram/releases/download/";

    // Instrumentation hooks (package-visible, set only by androidTest in the app
    // process): point the check at a local fixture server instead of the live release.
    static String latestReleaseUrlOverride = null;
    static String allowedApkUrlPrefixOverride = null;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private GithubAppUpdate() {
    }

    public static long currentVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    public static String currentVersionName() {
        return BuildConfig.VERSION_NAME;
    }

    private static String get(String urlString) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "roidgram-updater");
            if (conn.getResponseCode() != 200) throw new IllegalStateException("http " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    static String findAssetUrl(String releaseJson, String assetName) throws Exception {
        JSONArray assets = new JSONObject(releaseJson).optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && assetName.equals(asset.optString("name"))) {
                String url = asset.optString("browser_download_url", null);
                return (url == null || url.isEmpty()) ? null : url;
            }
        }
        return null;
    }

    static UpdateInfo parseManifest(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            long versionCode = obj.getLong("versionCode");
            String versionName = obj.getString("versionName");
            String apkUrl = obj.getString("apkUrl");
            String sha256 = obj.getString("sha256");
            String prefix = allowedApkUrlPrefixOverride != null ? allowedApkUrlPrefixOverride : RELEASE_DOWNLOAD_PREFIX;
            if (!apkUrl.startsWith(prefix)) return null;
            if (!sha256.matches("[0-9a-fA-F]{64}")) return null;
            String notes = obj.optString("notes", null);
            return new UpdateInfo(versionCode, versionName, apkUrl, sha256.toLowerCase(), notes);
        } catch (Exception e) {
            return null;
        }
    }

    /** Latest published release manifest, or null when none exists or the fetch fails. */
    public static UpdateInfo fetchLatest() {
        try {
            String releaseUrl = latestReleaseUrlOverride != null ? latestReleaseUrlOverride : LATEST_RELEASE_URL;
            String manifestUrl = findAssetUrl(get(releaseUrl), MANIFEST_ASSET_NAME);
            if (manifestUrl == null) return null;
            return parseManifest(get(manifestUrl));
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isNewer(UpdateInfo info, long installedVersionCode) {
        return info.versionCode > installedVersionCode;
    }

    /** Downloads to the private updates cache dir with progress; deletes partial files. */
    public static File download(Context context, UpdateInfo info, ProgressListener listener) throws Exception {
        File dir = new File(context.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, "roidgram-" + info.versionCode + ".apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(info.apkUrl).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "roidgram-updater");
            if (conn.getResponseCode() != 200) throw new IllegalStateException("http " + conn.getResponseCode());
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int lastPercent = -1;
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    done += n;
                    if (total > 0 && listener != null) {
                        int percent = (int) (done * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            listener.onProgress(percent);
                        }
                    }
                }
            }
            return out;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static PackageInfo packageInfo(PackageManager pm, String packageName) {
        try {
            // Request the legacy GET_SIGNATURES alongside GET_SIGNING_CERTIFICATES: on
            // API 29 emulators getPackageArchiveInfo can return signingInfo=null for a
            // valid v1+v2 signed archive (proven on the 8cloud CI emulator), and the
            // legacy signatures array is the fallback then.
            if (Build.VERSION.SDK_INT >= 33) {
                return pm.getPackageInfo(packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES));
            }
            //noinspection deprecation
            return pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] signerSha256(PackageInfo info) {
        try {
            android.content.pm.Signature[] signers = null;
            if (info.signingInfo != null) {
                signers = info.signingInfo.getApkContentsSigners();
            }
            if ((signers == null || signers.length == 0) && info.signatures != null && info.signatures.length > 0) {
                //noinspection deprecation
                signers = info.signatures;
            }
            if (signers == null || signers.length == 0) return null;
            return MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private static long packageVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    /**
     * Verifies digest, package name, versionCode and signer certificate of the downloaded
     * APK against this installed build. Anything that cannot be verified is rejected;
     * the APK file is deleted on rejection so a bad file can never be installed later.
     */
    public static boolean verifyApk(Context context, File apk, UpdateInfo info) {
        boolean ok = false;
        try {
            if (!sha256Hex(apk).equalsIgnoreCase(info.sha256)) return false;
            PackageManager pm = context.getPackageManager();
            PackageInfo apkInfo;
            if (Build.VERSION.SDK_INT >= 33) {
                apkInfo = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES));
            } else {
                //noinspection deprecation
                apkInfo = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES);
            }
            if (apkInfo == null) return false;
            if (!context.getPackageName().equals(apkInfo.packageName)) return false;
            if (packageVersionCode(apkInfo) <= currentVersionCode()) return false;
            byte[] apkSigner = signerSha256(apkInfo);
            PackageInfo own = packageInfo(pm, context.getPackageName());
            byte[] ownSigner = own == null ? null : signerSha256(own);
            if (apkSigner == null || ownSigner == null) return false;
            ok = Arrays.equals(apkSigner, ownSigner);
            return ok;
        } catch (Exception e) {
            return false;
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            }
        }
    }

    public static boolean canInstall(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /** Opens the one-time unknown-sources settings screen for this app. */
    public static void openUnknownSourcesSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Hands the verified APK to the system package installer; the user confirms. */
    public static void promptInstall(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }
}
