package io.github.jarvisassist;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/**
 * One assistant invocation (power-button hold or similar): open the ask screen, listening, and then close the
 * session at once. The session itself has no screen. Leaving it open kept a system overlay over the bottom of
 * the display that swallowed taps, so this must always call hide().
 */
public class JarvisSession extends VoiceInteractionSession {

    public JarvisSession(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setUiEnabled(false); // no session window of our own
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        // A normal activity in the app's own task, so it does not stay tied to the session.
        Intent intent = new Intent(getContext(), AskActivity.class)
                .putExtra("voice", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            getContext().startActivity(intent);
        } catch (RuntimeException e) {
            startAssistantActivity(new Intent(getContext(), AskActivity.class).putExtra("voice", true));
        }
        hide();
    }
}
