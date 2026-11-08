package app.roidgram.updatercontroller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Drives the final step of Roidgram's A->B updater proof from a process that survives
 * Roidgram being replaced: clicks the system package-installer sheet's Install/Update
 * button, waits for the replacement, then asserts the new versionCode (host dumpsys
 * agrees) and that the signing certificate is still Roidgram's stable cert.
 *
 * Expected instrumentation args:
 *   -e expectedVersionCode <N>   (fixture B's versionCode = installed A + 1)
 *   -e expectedCertSha256 <hex>  (Roidgram stable signing cert sha256)
 */
@RunWith(AndroidJUnit4.class)
public class InstallControllerTest {

    private static final String ROIDGRAM = "app.roidgram";

    @Test
    public void driveSheetAndAssertReplacement() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        long expectedVc = Long.parseLong(args.getString("expectedVersionCode", "0"));
        String expectedCert = args.getString("expectedCertSha256", "");
        assertTrue("expectedVersionCode arg missing", expectedVc > 0);
        assertEquals(64, expectedCert.length());

        // The in-app test already called promptInstall; the sheet belongs to the
        // system package installer and survives that test's process ending.
        UiObject2 button = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && button == null) {
            // API 29 labels the update confirmation INSTALL (8cloud 99f989a); newer
            // platform installers may say Update.
            for (String label : new String[]{"Update", "Install", "UPDATE", "INSTALL"}) {
                button = device.findObject(By.text(label));
                if (button != null) break;
            }
            if (button == null) Thread.sleep(1_000);
        }
        shot(device, "roidgram-controller-sheet");
        assertNotNull("system installer sheet button (Update/Install) never found", button);
        button.click();

        // Poll dumpsys from the shell identity until the package reports fixture B.
        boolean replaced = false;
        deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            String dump = shell(device, "dumpsys package " + ROIDGRAM + " | grep -m1 versionCode");
            if (dump.contains("versionCode=" + expectedVc + " ")) {
                replaced = true;
                break;
            }
            Thread.sleep(2_000);
        }
        if (!replaced) {
            shot(device, "roidgram-controller-stuck");
            fail("package never reached versionCode=" + expectedVc + "; last dumpsys: "
                    + shell(device, "dumpsys package " + ROIDGRAM + " | grep -m1 versionCode"));
        }
        shot(device, "roidgram-controller-post-replacement");

        // Signing cert of the replaced package must still be Roidgram's stable cert.
        PackageManager pm = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageManager();
        PackageInfo info;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            info = pm.getPackageInfo(ROIDGRAM,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
        } else {
            //noinspection deprecation
            info = pm.getPackageInfo(ROIDGRAM, PackageManager.GET_SIGNING_CERTIFICATES);
        }
        assertNotNull(info.signingInfo);
        android.content.pm.Signature[] signers = info.signingInfo.getApkContentsSigners();
        assertNotNull(signers);
        assertTrue(signers.length > 0);
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
        StringBuilder hex = new StringBuilder(64);
        for (byte b : sha) hex.append(String.format("%02x", b));
        assertEquals("post-replacement signer changed", expectedCert, hex.toString());

        long actualVc = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        assertEquals(expectedVc, actualVc);
    }

    private static void shot(UiDevice device, String name) {
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("screencap -p /data/local/tmp/" + name + ".png").close();
        } catch (Exception ignored) {
        }
    }

    private static String shell(UiDevice device, String command) throws Exception {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            pfd.close();
        }
    }
}
