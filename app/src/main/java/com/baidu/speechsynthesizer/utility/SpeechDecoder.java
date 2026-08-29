package com.baidu.speechsynthesizer.utility;

import io.legado.app.help.bdtts.BdDecodedListener;

public class SpeechDecoder {
    private BdDecodedListener mDecodedDataListener;
    private final String mInstanceId;
    private final String tag;

    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (Throwable unused) {
        }
        try {
            System.loadLibrary("BDSpeechDecoder_V1");
        } catch (Throwable unused2) {
        }
    }

    public SpeechDecoder(String str) {
        this.tag = "SpeechDecoder_" + str;
        this.mInstanceId = str;
    }

    public static native String bdTTSGetDecoderLibVersion();

    public static native int bdTTSSetNativeLogLevel(int i);

    public static native boolean isIpv4Reachable();

    public static native boolean isIpv6Reachable();

    public native int decode(byte[] bArr, int i, short[] sArr, int[] iArr, int i4, int i5);

    public void decodeAudioCallback(byte[] bArr) {
        BdDecodedListener listener = this.mDecodedDataListener;
        if (listener == null || bArr.length <= 0) {
            return;
        }
        listener.onDecodedData(bArr);
    }

    public native int decodeWithCallback(String str, byte[] bArr, Object obj, int i);

    public int decodeWithCallback(byte[] bArr, int i) {
        return decodeWithCallback(this.mInstanceId, bArr, this, i);
    }

    public native int release(String str);

    public void setOnDecodedDataListener(BdDecodedListener listener) {
        this.mDecodedDataListener = listener;
    }
}
