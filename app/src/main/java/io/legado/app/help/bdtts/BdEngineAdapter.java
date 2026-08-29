package io.legado.app.help.bdtts;

import android.content.Context;
import android.text.TextUtils;

import com.baidu.tts.jni.EmbeddedSynthesizerEngine;
import com.baidu.tts.jni.EmbeddedSynthesizerEnginf;
import com.baidu.tts.jni.EmbeddedSynthesizerEnginm;
import com.baidu.tts.jni.ETtsError;
import com.baidu.tts.jni.IEmbeddedSynthesizerEngine;
import com.baidu.tts.jni.LicenseInfo;
import com.baidu.tts.jni.OnNewDataListener;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 百度离线引擎适配器（MultiTTS Z1.b + Z1.e + c2 初始化链的等价实现）。
 * <p>
 * 授权模型：语音包内 licence.dat / 授权ID + 冒充宿主包名，喂给 bdTTSEngineInit。
 * 引擎选择：90013→Enginm(bd_ettm)；9294715→Enginf(bd_ettf)；其余→Engine(bd_etts)。
 * 6082724（tacotron+subgan）：voc/ext 路径互换，音色编号默认 "5"。
 */
public class BdEngineAdapter implements BdTtsEngine, OnNewDataListener {

    /** 授权ID → 冒充宿主包名。 */
    private static final ConcurrentHashMap<String, String> HOST_PACKAGES = new ConcurrentHashMap<>();

    private static final String APP_CODE = "1337";
    private static final String HOST_TAG = "com.baidu.tts.voicesearch";
    private static final String ENGINE_NEW_TAC = "6082724";

    private final Context context;
    private final String speakerCode;
    private final BdSpeakerParam param;

    private final String resPrefix;
    private final String licenceDatPath;
    private String textDatPath;
    private String speechDatPath;
    private String extDatPath;
    private String resDatPath;
    private String speakerAttr;
    private final String engineId;

    private final IEmbeddedSynthesizerEngine nativeEngine;
    private final BdOfflineSynth synth;

    private BdSynthCallback callback;
    private volatile boolean cancelled = false;
    private String initError = null;

    public BdEngineAdapter(Context context, String speakerCode, String paramStr) {
        this.context = context.getApplicationContext();
        this.speakerCode = speakerCode;
        this.param = BdSpeakerParam.parse(paramStr);
        this.engineId = param.engineId;

        String dir = this.context.getExternalFilesDir("voice").getAbsolutePath()
                + "/bdetts/" + param.engineId;
        licenceDatPath = dir + "/licence.dat";
        textDatPath = dir + "/text.dat";
        speechDatPath = dir + "/acoustic/" + speakerCode + ".voc.dat";
        if (!TextUtils.isEmpty(param.acousticModel)) {
            extDatPath = dir + "/acoustic/" + param.acousticModel + ".ext.dat";
        }
        if (ENGINE_NEW_TAC.equals(engineId) && !TextUtils.isEmpty(extDatPath)) {
            String tmp = speechDatPath;
            speechDatPath = extDatPath;
            extDatPath = tmp;
            speakerAttr = "5";
        }
        if (!TextUtils.isEmpty(param.speakerAttr)) {
            speakerAttr = param.speakerAttr;
        }
        if (!TextUtils.isEmpty(param.textFile)) {
            textDatPath = dir + "/" + param.textFile + ".dat";
        }
        String resDat = dir + "/" + speakerCode + ".res.dat";
        resDatPath = new File(resDat).isFile() ? resDat : null;

        HOST_PACKAGES.put(param.engineId, param.hostPackage);

        nativeEngine = createEngine(param.engineId, speakerCode);
        nativeEngine.setOnNewDataListener(this);
        synth = new BdOfflineSynth(speakerCode, nativeEngine);
    }

    private static IEmbeddedSynthesizerEngine createEngine(String engineId, String speakerCode) {
        if ("90013".equals(engineId)) {
            return new EmbeddedSynthesizerEnginm(speakerCode);
        }
        if ("9294715".equals(engineId)) {
            return new EmbeddedSynthesizerEnginf(speakerCode);
        }
        return new EmbeddedSynthesizerEngine(speakerCode);
    }

    /** 授权ID → 冒充包名（bdTTSEngineInit 的 license 校验身份）。 */
    public static String hostPackageOf(String engineId) {
        String pkg = HOST_PACKAGES.get(engineId);
        return pkg != null ? pkg : "com.baidu.BaiduMap";
    }

    @Override
    public void init() {
        initError = null;
        BdOfflineParams p = new BdOfflineParams();
        p.speakerCode = speakerCode;
        p.engineId = engineId;
        p.hostPackage = hostPackageOf(engineId);
        p.textDatPath = textDatPath;
        p.speechDatPath = speechDatPath;
        p.speechExtDatPath = extDatPath;
        p.resourceRoot = resDatPath;
        p.speakerAttr = speakerAttr;
        p.licencePath = licenceDatPath;
        p.sampleRate = 16000;
        synth.params = p;

        // 授权（MultiTTS 经 b2 授权链调用 bdTTSVerifyLicense；离线包以 licence.dat 为凭据）
        try {
            byte[] licence = BdOfflineSynth.readFileBytes(licenceDatPath);
            LicenseInfo info = EmbeddedSynthesizerEngine.bdTTSVerifyLicense(
                    engineId, p.sampleRate, engineId, licenceDatPath,
                    APP_CODE, HOST_TAG, licence);
            if (info != null && info.getRet() != 0) {
                initError = "license ret=" + info.getRet() + " " + info.getAppDesc();
            }
        } catch (Throwable t) {
            initError = "license error: " + t.getMessage();
        }

        // 刷新模型路径（modelJson → params）并初始化引擎
        int reloaded = synth.reloadModel();
        if (reloaded == -1) {
            initError = "reload model failed";
            return;
        }
        if (initError == null) {
            ETtsError err = synth.initEngine();
            if (err != null && err.getRet() != 0) {
                initError = "engine init ret=" + err.getRet() + " " + err.getMessage();
                return;
            }
            // 域数据（<code>.res.dat）存在时注册
            if (resDatPath != null) {
                byte[] res = BdOfflineSynth.readFileBytes(resDatPath);
                if (res != null) {
                    nativeEngine.bdTTSDomainDataInit(res, synth.engineHandle);
                }
            }
        }
    }

    @Override
    public void synthesize(float speed, float volume, String text, BdSynthCallback cb) {
        if (initError != null) {
            cb.onError("init: " + initError);
            return;
        }
        cancelled = false;
        callback = cb;
        BdOfflineParams p = synth.params;
        p.speed = speed * 0.14f;
        p.pitch = volume * 0.15f;
        cb.onStart();
        try {
            ETtsError err = synth.synthesize(text);
            if (err == null || err.getRet() == 0 || err.getRet() == 530 || err.getRet() == 531) {
                cb.onDone("Task done.");
            } else if (err.getRet() == 9) {
                cb.onError("synth ret=9 " + err.getMessage());
            } else {
                cb.onError("synth ret=" + err.getRet() + " " + err.getMessage());
            }
        } catch (InterruptedException e) {
            cb.onDone("Task Interrupted.");
        }
    }

    @Override
    public int onNewData(byte[] data, int lenFlag) {
        BdSynthCallback cb = this.callback;
        if (cancelled || cb == null || data.length <= 0) {
            return cancelled ? -1 : 0;
        }
        cb.onAudioData(data.length, data);
        return cancelled ? -1 : 0;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public void release() {
        if (resDatPath != null) {
            nativeEngine.bdTTSDomainDataUninit(synth.engineHandle);
        }
        nativeEngine.bdTTSEngineUninit(synth.engineHandle);
        synth.engineHandle = 0L;
    }
}
