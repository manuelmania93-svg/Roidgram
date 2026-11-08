package tw.nekomimi.nekogram.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-device proof of the Roidgram in-app updater against a local fixture server
 * (adb reverse tcp:8799). Covers: manifest fetch/parse, not-newer rejection,
 * bad-digest rejection, wrong-signer rejection, download with progress, verification
 * of the real signed fixture, the unknown-sources grant flow, and the handoff to the
 * system package installer. The final sheet click and post-replacement assertions are
 * done by the separate updater-controller test app, because replacing Roidgram kills
 * this test's process.
 *
 * The CI driver (.github/scripts/roidgram-updater-proof.sh) serves fixtures and asserts
 * on dumpsys afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class GithubAppUpdateFlowTest {

    private static final String FIXTURE_BASE = "http://127.0.0.1:8799";

    private Context ctx;
    private UiDevice device;

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        GithubAppUpdate.allowedApkUrlPrefixOverride = FIXTURE_BASE + "/";
        // An API 29 emulator can sit at the keyguard; dismiss it so the system sheet
        // and grant page actually draw (8cloud runs 42445755149/42446867338/42448007797).
        try {
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().executeShellCommand("wm dismiss-keyguard").close();
        } catch (Exception ignored) {
        }
        try {
            device.wakeUp();
        } catch (Exception ignored) {
        }
    }

    private void useFixture(String name) {
        GithubAppUpdate.latestReleaseUrlOverride = FIXTURE_BASE + "/release-" + name + ".json";
    }

    @After
    public void tearDown() {
        GithubAppUpdate.latestReleaseUrlOverride = null;
        GithubAppUpdate.allowedApkUrlPrefixOverride = null;
    }

    private void shot(String name) {
        try {
            android.os.ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/" + name + ".png");
            pfd.close();
        } catch (Exception ignored) {
        }
    }

    @Test
    public void a_fixtureServerReachable() throws Exception {        HttpURLConnection conn = (HttpURLConnection) new URL(FIXTURE_BASE + "/health.txt").openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        assertEquals(200, conn.getResponseCode());
        try (InputStream in = conn.getInputStream()) {
            byte[] body = new byte[64];
            int n = in.read(body);
            assertTrue("health body empty", n > 0);
        } finally {
            conn.disconnect();
        }
    }

    @Test
    public void notNewerIsRejected() {
        useFixture("old");
        GithubAppUpdate.UpdateInfo info = GithubAppUpdate.fetchLatest();
        assertNotNull("old fixture manifest did not parse", info);
        assertFalse("same versionCode must not be offered as an update",
                GithubAppUpdate.isNewer(info, GithubAppUpdate.currentVersionCode()));
    }

    @Test
    public void badShaIsRejectedAndDeleted() throws Exception {
        useFixture("badsha");
        GithubAppUpdate.UpdateInfo info = GithubAppUpdate.fetchLatest();
        assertNotNull("badsha fixture manifest did not parse", info);
        assertTrue(GithubAppUpdate.isNewer(info, GithubAppUpdate.currentVersionCode()));
        File apk = GithubAppUpdate.download(ctx, info, null);
        assertTrue(apk.exists());
        assertFalse("tampered digest must be rejected", GithubAppUpdate.verifyApk(ctx, apk, info));
        assertFalse("rejected APK must be deleted", apk.exists());
    }

    @Test
    public void wrongSignerIsRejectedAndDeleted() throws Exception {
        useFixture("wrongsig");
        GithubAppUpdate.UpdateInfo info = GithubAppUpdate.fetchLatest();
        assertNotNull("wrongsig fixture manifest did not parse", info);
        assertTrue(GithubAppUpdate.isNewer(info, GithubAppUpdate.currentVersionCode()));
        File apk = GithubAppUpdate.download(ctx, info, null);
        assertTrue(apk.exists());
        assertFalse("foreign signer must be rejected", GithubAppUpdate.verifyApk(ctx, apk, info));
        assertFalse("rejected APK must be deleted", apk.exists());
    }

    /**
     * API 29 quirk (8cloud run 42453757686): getPackageArchiveInfo can return
     * signingInfo=null for a valid signed archive; verifyApk requests GET_SIGNATURES
     * too and falls back to the legacy array. Pin that the legacy array is readable on
     * this platform for the real fixture.
     */
    @Test
    public void legacySignaturesReadable() throws Exception {
        useFixture("good");
        GithubAppUpdate.UpdateInfo info = GithubAppUpdate.fetchLatest();
        assertNotNull(info);
        File apk = GithubAppUpdate.download(ctx, info, null);
        PackageManager pm = ctx.getPackageManager();
        //noinspection deprecation
        PackageInfo legacy = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        assertNotNull(legacy);
        //noinspection deprecation
        assertNotNull("legacy signatures array null on this platform", legacy.signatures);
        //noinspection deprecation
        assertTrue(legacy.signatures.length > 0);
        apk.delete();
    }

    @Test
    public void fullFlowHandsOffToSystemInstaller() throws Exception {
        useFixture("good");
        GithubAppUpdate.UpdateInfo info = GithubAppUpdate.fetchLatest();
        assertNotNull("good fixture manifest did not parse", info);
        assertTrue("fixture B must be newer than the installed build",
                GithubAppUpdate.isNewer(info, GithubAppUpdate.currentVersionCode()));

        AtomicInteger progressEvents = new AtomicInteger(0);
        File apk = GithubAppUpdate.download(ctx, info, percent -> progressEvents.incrementAndGet());
        assertTrue("downloaded APK missing", apk.exists());
        assertTrue("progress listener never fired", progressEvents.get() > 0);

        assertTrue("real signed fixture B must verify", GithubAppUpdate.verifyApk(ctx, apk, info));
        assertTrue("verified APK must survive", apk.exists());

        if (!GithubAppUpdate.canInstall(ctx)) {
            GithubAppUpdate.openUnknownSourcesSettings(ctx);
            // The grant page toggle is the framework switch widget; clicking the label
            // does not toggle it (8cloud run 4245series, fixed by clicking switch_widget).
            UiObject2 toggle = device.wait(Until.findObject(By.res("android:id/switch_widget")), 5_000);
            if (toggle == null) toggle = device.findObject(By.clazz("android.widget.Switch"));
            if (toggle == null) {
                UiObject2 label = device.wait(Until.findObject(By.textContains("Allow from this source")), 5_000);
                assertNotNull("unknown-sources page did not render", label);
                label.click();
            } else {
                toggle.click();
            }
            Thread.sleep(1_000);
            shot("roidgram-unknown-sources-grant");
            device.pressBack();
            Thread.sleep(1_000);
            assertTrue("grant did not take effect", GithubAppUpdate.canInstall(ctx));
        }

        GithubAppUpdate.promptInstall(ctx, apk);
        boolean sheet = device.wait(Until.hasObject(
                By.pkg("com.android.packageinstaller").depth(0)), 10_000)
                || device.wait(Until.hasObject(
                By.pkg("com.google.android.packageinstaller").depth(0)), 10_000);
        shot("roidgram-system-install-sheet");
        assertTrue("system package installer sheet never appeared", sheet);
        // The updater-controller test app clicks INSTALL and asserts the replacement;
        // this process cannot, because replacing Roidgram kills it.
    }
}
