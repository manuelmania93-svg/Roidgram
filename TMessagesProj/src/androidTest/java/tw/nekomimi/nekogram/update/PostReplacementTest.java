package tw.nekomimi.nekogram.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;

/**
 * Runs AFTER the real Roidgram-to-Roidgram A->B replacement (driven by the
 * updater-controller): asserts the app now runs as versionCode B and that private app
 * data written before the update survived it. This is what upgrades the proof from
 * "installer plumbing" to "a real same-signature update preserving app data".
 *
 * Expected instrumentation arg: -e expectedVersionCode <N>
 */
@RunWith(AndroidJUnit4.class)
public class PostReplacementTest {

    @Test
    public void dataSurvivesAndVersionAdvanced() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long expectedVc = Long.parseLong(
                InstrumentationRegistry.getArguments().getString("expectedVersionCode", "0"));
        assertTrue("expectedVersionCode arg missing", expectedVc > 0);

        assertEquals("installed build is not the updated one",
                expectedVc, GithubAppUpdate.currentVersionCode());

        File marker = new File(ctx.getFilesDir(), "updater-proof-marker.txt");
        assertTrue("pre-update data marker missing after replacement", marker.exists());
        byte[] body = new byte[(int) marker.length()];
        FileInputStream in = new FileInputStream(marker);
        int off = 0;
        int n;
        while (off < body.length && (n = in.read(body, off, body.length - off)) != -1) off += n;
        in.close();
        assertEquals("updater-proof-marker-v1", new String(body, "UTF-8"));
    }
}
