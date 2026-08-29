package com.baidu.tts.jni;

public class EmbeddedSynthesizerEnginf implements IEmbeddedSynthesizerEnginf {
    private OnNewDataListener mNewDataListener;
    private final String tag;

    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("BDSpeechDecoder_V1");
        System.loadLibrary("bd_ettf");
        bdTTSSetNativeLogLevel(3);
    }

    public EmbeddedSynthesizerEnginf(String str) {
        this.tag = "EmbeddedSynthesizerEnginf_" + str;
    }

    public static native int bdTTSBindCore(String str);

    public static native int bdTTSCheckDomainFile(byte[] bArr);

    public static native String bdTTSGetDatParam(String str);

    public static native long bdTTSGetDomainSampleRate(byte[] bArr);

    public static native String bdTTSGetEngineLibVersion();

    public static native String bdTTSGetEngineParam();

    public static native long bdTTSGetSpeechSampleRate(byte[] bArr);

    public static native int bdTTSResEngineMatch(byte[] bArr);

    public static native int bdTTSSetLogFilePath(String str);

    public static native int bdTTSSetNativeLogLevel(int i);

    public static native int bdTTSVerifyDataFile(byte[] bArr);

    public static native synchronized LicenseInfo bdTTSVerifyLicense(String str, int i, String str2, String str3, String str4, String str5, byte[] bArr);

    public static String getSpeechInfo(String str) {
        String bdTTSGetDatParam = bdTTSGetDatParam(str);
        if (bdTTSGetDatParam != null) {
            return bdTTSGetDatParam.replaceAll("\n", ",");
        }
        return null;
    }

    @Override
    public int _bdTTSBindCore(String str) {
        return bdTTSBindCore(str);
    }

    @Override
    public int _bdTTSCheckDomainFile(byte[] bArr) {
        return bdTTSCheckDomainFile(bArr);
    }

    @Override
    public String _bdTTSGetDatParam(String str) {
        return bdTTSGetDatParam(str);
    }

    @Override
    public long _bdTTSGetDomainSampleRate(byte[] bArr) {
        return bdTTSGetDomainSampleRate(bArr);
    }

    @Override
    public String _bdTTSGetEngineLibVersion() {
        return bdTTSGetEngineLibVersion();
    }

    @Override
    public String _bdTTSGetEngineParam() {
        return bdTTSGetEngineParam();
    }

    @Override
    public long _bdTTSGetSpeechSampleRate(byte[] bArr) {
        return bdTTSGetSpeechSampleRate(bArr);
    }

    @Override
    public int _bdTTSResEngineMatch(byte[] bArr) {
        return bdTTSResEngineMatch(bArr);
    }

    @Override
    public int _bdTTSSetLogFilePath(String str) {
        return bdTTSSetLogFilePath(str);
    }

    @Override
    public int _bdTTSSetNativeLogLevel(int i) {
        return bdTTSSetNativeLogLevel(i);
    }

    @Override
    public int _bdTTSVerifyDataFile(byte[] bArr) {
        return bdTTSVerifyDataFile(bArr);
    }

    @Override
    public LicenseInfo _bdTTSVerifyLicense(String str, int i, String str2, String str3, String str4, String str5, byte[] bArr) {
        return bdTTSVerifyLicense(str, i, str2, str3, str4, str5, bArr);
    }

    @Override
    public String _getSpeechInfo(String str) {
        return getSpeechInfo(str);
    }

    @Override
    public native int bdTTSDoPostProcess(long j2, byte[] bArr, byte[] bArr2, boolean z4);

    @Override
    public native int bdTTSDomainDataInit(byte[] bArr, long j2);

    @Override
    public native int bdTTSDomainDataUninit(long j2);

    @Override
    public native ETtsError bdTTSEngineInit(String str, byte[] bArr, byte[] bArr2, byte[] bArr3, byte[] bArr4, String str2, long[] jArr);

    @Override
    public native int bdTTSEnginePggInit(String str, byte[] bArr, long j2);

    @Override
    public native int bdTTSEngineUninit(long j2);

    @Override
    public native long bdTTSGetParam(long j2, int i);

    @Override
    public native int bdTTSInitPostProcesser(float f3, float f4, float f5, int i, long[] jArr);

    @Override
    public native int bdTTSSetParam(long j2, int i, long j4);

    @Override
    public native int bdTTSSetParamFloat(long j2, int i, float f3);

    @Override
    public native int bdTTSSetParamString(long j2, String str, String str2);

    @Override
    public native ETtsError bdTTSSynthesis(long j2, byte[] bArr, int i, int i4);

    @Override
    public native int bdTTSUninitPostProcesser(long j2);

    @Override
    public native ETtsError loadSuitedEngine(String str, byte[] bArr, byte[] bArr2, byte[] bArr3, byte[] bArr4, String str2, long j2);

    @Override
    public int newAudioDataCallback(byte[] bArr, int i) {
        OnNewDataListener onNewDataListener = this.mNewDataListener;
        return onNewDataListener != null ? onNewDataListener.onNewData(bArr, i) : 0;
    }

    @Override
    public void setOnNewDataListener(OnNewDataListener onNewDataListener) {
        this.mNewDataListener = onNewDataListener;
    }
}
