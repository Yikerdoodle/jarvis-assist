package io.github.jarvisassist;

import android.service.voice.VoiceInteractionService;

/**
 * Makes the app selectable as the phone's default assistant. It does nothing until the owner picks
 * it in Settings; the real work happens in {@link JarvisSession} when the assistant gesture fires.
 */
public class JarvisInteractionService extends VoiceInteractionService {
}
