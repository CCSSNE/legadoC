package io.legado.app.help.bdtts;

import android.text.TextUtils;

import com.baidu.tts.jni.ETtsError;
import com.baidu.tts.jni.IEmbeddedSynthesizerEngine;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 离线合成器（MultiTTS b2.C + b2.A 合并等价物）：
 * 引擎初始化 / 模型装配 / 单次合成。
 */
public class BdOfflineSynth {

    public final String speakerCode;
    public final IEmbeddedSynthesizerEngine engine;
    public BdOfflineParams params;
    public long engineHandle = 0L;
    private String loadedSpeakerName = null;
    private String loadedModelJson = null;

    public BdOfflineSynth(String speakerCode, IEmbeddedSynthesizerEngine engine) {
        this.speakerCode = speakerCode;
        this.engine = engine;
    }

    public static byte[] readFileBytes(String path) {
        if (path == null) {
            return null;
        }
        File file = new File(path);
        if (!file.isFile()) {
            return null;
        }
        byte[] out = new byte[(int) file.length()];
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            int read = 0;
            while (read < out.length) {
                int n = in.read(out, read, out.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return out;
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 模型装配（b2.C）：params.textDatPath 非空时，text/speech/ext/speakerAttr
     * 已由 BdEngineAdapter 按 param 直接装配完成，此处仅做同 speaker 去重；
     * 否则解析 params.modelJson 的四个路径。
     *
     * @return 0=无需刷新 1=已刷新 -1=错误
     */
    public int reloadModel() {
        BdOfflineParams p = this.params;
        if (p.textDatPath != null) {
            if (TextUtils.equals(this.loadedSpeakerName, p.speakerCode)) {
                return 0;
            }
            this.loadedSpeakerName = p.speakerCode;
            this.loadedModelJson = null;
            return 1;
        }
        if (p.modelJson == null) {
            return 0;
        }
        if (TextUtils.equals(this.loadedModelJson, p.modelJson)) {
            return 0;
        }
        try {
            JSONObject json = new JSONObject(p.modelJson);
            p.textDatPath = json.optString("TEXT_DAT_PATH");
            p.speechDatPath = json.optString("SPEECH_DAT_PATH");
            p.speechExtDatPath = json.optString("SPEECH_EXT_DAT_PATH");
            p.speakerAttr = json.optString("TAC_SUBGAN_SPEAKER_ATTR");
            this.loadedModelJson = p.modelJson;
            this.loadedSpeakerName = null;
            return 1;
        } catch (JSONException e) {
            return -1;
        }
    }

    /**
     * 引擎初始化（b2.C.a）。必须先保证 params.textDatPath/speechDatPath 有效。
     *
     * @return null=成功
     */
    public ETtsError initEngine() {
        BdOfflineParams p = this.params;
        String textPath = p.textDatPath;
        String speechPath = p.speechDatPath;
        String extPath = p.speechExtDatPath;
        String tacAttr = p.speakerAttr;
        if (TextUtils.isEmpty(speechPath) || TextUtils.isEmpty(textPath)) {
            return null;
        }
        byte[] textDat = readFileBytes(textPath);
        byte[] speechDat = readFileBytes(speechPath);
        byte[] extDat = TextUtils.isEmpty(extPath) ? null : readFileBytes(extPath);
        byte[] resDat = readFileBytes(p.resourceRoot);
        String authorize = "";
        if (fileExists(speechPath)) {
            try {
                authorize = new JSONObject(this.engine._bdTTSGetDatParam(speechPath)).optString("authorize");
            } catch (JSONException ignored) {
            }
        }
        long[] handleOut = new long[1];
        ETtsError err = this.engine.bdTTSEngineInit(
                p.hostPackage, textDat, speechDat, extDat, resDat, authorize, handleOut);
        this.engineHandle = handleOut.length > 0 ? handleOut[0] : 0L;
        if (err == null) {
            return null;
        }
        if (err.getRet() == 0 && !TextUtils.isEmpty(tacAttr)) {
            long handle = this.engineHandle;
            try {
                JSONObject attr = new JSONObject(tacAttr);
                String speakerId = attr.optString("model_speaker_id");
                String styleId = attr.optString("model_style_id");
                if (!TextUtils.isEmpty(speakerId)) {
                    this.engine.bdTTSSetParam(handle, 9, Long.parseLong(speakerId));
                }
                if (!TextUtils.isEmpty(styleId)) {
                    this.engine.bdTTSSetParam(handle, 10, Long.parseLong(styleId));
                }
            } catch (Exception ignored) {
            }
        }
        return err;
    }

    private static boolean fileExists(String path) {
        return path != null && new File(path).isFile();
    }

    /**
     * 单次合成（b2.A）。PCM 数据经 engine 的 OnNewDataListener 异步回调。
     *
     * @return null=成功
     */
    public ETtsError synthesize(String text) {
        BdOfflineParams p = this.params;
        long handle = this.engineHandle;
        IEmbeddedSynthesizerEngine engine = this.engine;
        engine.bdTTSSetParam(handle, 0, 0L);
        engine.bdTTSSetParamFloat(handle, 1, p.pitch);
        engine.bdTTSSetParamFloat(handle, 2, p.speed);
        engine.bdTTSSetParamFloat(handle, 3, p.volume);
        try {
            String emo = TextUtils.isEmpty(p.emoJson)
                    ? "normal" : new JSONObject(p.emoJson).optString("emo", "normal");
            String silence = TextUtils.isEmpty(p.puncSilenceJson)
                    ? "default" : new JSONObject(p.puncSilenceJson).optString("punc_silence_enum", "default");
            engine.bdTTSSetParamString(handle, "emo", emo);
            engine.bdTTSSetParamString(handle, "punc_silence_enum", silence);
        } catch (JSONException ignored) {
        }
        byte[] gbk;
        try {
            gbk = text.getBytes("GBK");
        } catch (Exception e) {
            gbk = text.getBytes();
        }
        return engine.bdTTSSynthesis(handle, gbk, gbk.length, 0);
    }
}
