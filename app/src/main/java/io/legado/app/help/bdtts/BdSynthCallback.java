package io.legado.app.help.bdtts;

/**
 * 离线合成回调（MultiTTS c2.C0277a 的合成面等价物）。
 */
public interface BdSynthCallback {

    void onStart();

    void onError(String message);

    void onDone(String message);

    void onAudioData(int length, byte[] data);
}
