package com.termux.view;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class TerminalCopyAllSessionTest {
    @Test
    public void aReusedViewCopiesFreshOutputFromItsCurrentlyAttachedSession() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = ApplicationProvider.getApplicationContext();
            List<String> copies = new ArrayList<>();
            TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
                TerminalSessionClient.class.getClassLoader(), new Class<?>[]{TerminalSessionClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("onCopyTextToClipboard")) copies.add((String) args[1]);
                    if (method.getReturnType() == Integer.class || method.getReturnType() == int.class) return 0;
                    return null;
                });
            TerminalView view = new TerminalView(context, null);
            TerminalSession first = session(client, "first session");
            // The unattached zero-size view never starts a PTY or subprocess.
            view.attachSession(first);
            view.copyAllText();
            append(first.getEmulator(), "\r\nnew output");
            view.copyAllText();
            TerminalSession second = session(client, "replacement session");
            view.attachSession(second);
            view.copyAllText();
            assertEquals(3, copies.size());
            assertEquals("first session", copies.get(0));
            assertEquals("first session\nnew output", copies.get(1));
            assertEquals("replacement session", copies.get(2));
        });
    }

    private static TerminalSession session(TerminalSessionClient client, String text) {
        TerminalSession session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        TerminalEmulator emulator = new TerminalEmulator(session, 40, 4, 100, client);
        try {
            Field field = TerminalSession.class.getDeclaredField("mEmulator");
            field.setAccessible(true);
            field.set(session, emulator);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
        append(emulator, text);
        return session;
    }
    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }
}
