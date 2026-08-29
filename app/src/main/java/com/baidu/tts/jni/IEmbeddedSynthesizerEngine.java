package com.baidu.tts.jni;

public interface IEmbeddedSynthesizerEngine {
    int _bdTTSBindCore(String str);

    int _bdTTSCheckDomainFile(byte[] bArr);

    String _bdTTSGetDatParam(String str);

    long _bdTTSGetDomainSampleRate(byte[] bArr);

    String _bdTTSGetEngineLibVersion();

    String _bdTTSGetEngineParam();

    long _bdTTSGetSpeechSampleRate(byte[] bArr);

    int _bdTTSResEngineMatch(byte[] bArr);

    int _bdTTSSetLogFilePath(String str);

    int _bdTTSSetNativeLogLevel(int i);

    int _bdTTSVerifyDataFile(byte[] bArr);

    LicenseInfo _bdTTSVerifyLicense(String str, int i, String str2, String str3, String str4, String str5, byte[] bArr);

    String _getSpeechInfo(String str);

    int bdTTSDoPostProcess(long j2, byte[] bArr, byte[] bArr2, boolean z4);

    int bdTTSDomainDataInit(byte[] bArr, long j2);

    int bdTTSDomainDataUninit(long j2);

    ETtsError bdTTSEngineInit(String str, byte[] bArr, byte[] bArr2, byte[] bArr3, byte[] bArr4, String str2, long[] jArr);

    int bdTTSEnginePggInit(String str, byte[] bArr, long j2);

    int bdTTSEngineUninit(long j2);

    long bdTTSGetParam(long j2, int i);

    int bdTTSInitPostProcesser(float f3, float f4, float f5, int i, long[] jArr);

    int bdTTSSetParam(long j2, int i, long j4);

    int bdTTSSetParamFloat(long j2, int i, float f3);

    int bdTTSSetParamString(long j2, String str, String str2);

    ETtsError bdTTSSynthesis(long j2, byte[] bArr, int i, int i4);

    int bdTTSUninitPostProcesser(long j2);

    ETtsError loadSuitedEngine(String str, byte[] bArr, byte[] bArr2, byte[] bArr3, byte[] bArr4, String str2, long j2);

    int newAudioDataCallback(byte[] bArr, int i);

    void setOnNewDataListener(OnNewDataListener onNewDataListener);
}
