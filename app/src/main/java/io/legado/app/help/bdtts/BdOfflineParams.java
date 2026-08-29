package io.legado.app.help.bdtts;

/**
 * 离线合成参数（MultiTTS b2.D 中 bdetts 实际用到的字段）。
 */
public class BdOfflineParams {

    public String speakerCode;
    public String engineId;
    public String hostPackage;
    public String textDatPath;
    public String speechDatPath;
    public String speechExtDatPath;
    public String resourceRoot;
    public String speakerAttr;
    public String modelJson;
    public String licencePath;
    public String emoJson;
    public String puncSilenceJson;
    public float speed = 5.0f;
    public float volume = 5.0f;
    public float pitch = 5.0f;
    public int sampleRate = 16000;
}
