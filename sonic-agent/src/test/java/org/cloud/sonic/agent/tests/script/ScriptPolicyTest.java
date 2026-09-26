package org.cloud.sonic.agent.tests.script;

import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.models.HandleContext;
import org.cloud.sonic.agent.tests.RunStepThread;
import org.cloud.sonic.agent.tests.handlers.AndroidStepHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ScriptPolicyTest {
    private File marker;

    @Before
    public void setUp() throws Exception {
        File dir = Files.createTempDirectory("script-policy").toFile();
        dir.deleteOnExit();
        marker = new File(dir, "ran");
    }

    @After
    public void tearDown() {
        new ScriptPolicy().setEnabled(false);
        marker.delete();
    }

    @Test
    public void scriptStepsAreRefusedByDefault() throws Exception {
        HandleContext context = runGroovyStep("new File('" + markerPath() + "').createNewFile()");

        assertTrue(context.getE() instanceof IllegalStateException);
        assertTrue(context.getE().getMessage().contains("sonic.agent.script.enable"));
        assertFalse(marker.exists());
    }

    @Test
    public void scriptStepsRunWhenTheAgentOwnerOptsIn() throws Exception {
        new ScriptPolicy().setEnabled(true);

        HandleContext context = runGroovyStep("new File('" + markerPath() + "').createNewFile()");

        assertNull(context.getE());
        assertTrue(marker.exists());
    }

    private String markerPath() {
        return marker.getAbsolutePath().replace("\\", "/");
    }

    private static HandleContext runGroovyStep(String script) throws InterruptedException {
        AndroidStepHandler handler = new AndroidStepHandler();
        handler.setTestMode(0, 0, "serial", DeviceStatus.TESTING, "");
        HandleContext context = new HandleContext();
        // Script runners only execute on a RunStepThread, as they do during a real test run.
        RunStepThread thread = new RunStepThread() {
            @Override
            public void run() {
                handler.runScript(context, script, "Groovy");
            }
        };
        thread.start();
        thread.join(30_000);
        return context;
    }
}
