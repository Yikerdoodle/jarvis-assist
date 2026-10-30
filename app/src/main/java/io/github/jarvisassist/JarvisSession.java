package io.github.jarvisassist;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/** One assistant invocation (power-button hold or similar): open the ask screen, listening. */
public class JarvisSession extends VoiceInteractionSession {

    public JarvisSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Intent intent = new Intent(getContext(), AskActivity.class).putExtra("voice", true);
        try {
            startAssistantActivity(intent);
        } catch (RuntimeException e) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        }
    }
}
