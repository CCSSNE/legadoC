package io.legado.app.help.bdtts;

/**
 * 离线 TTS 引擎接口（MultiTTS L6.h 等价物）。
 */
public interface BdTtsEngine {

    void init();

    void cancel();

    void synthesize(float speed, float volume, String text, BdSynthCallback callback);

    void release();
}
