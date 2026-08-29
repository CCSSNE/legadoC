package com.baidu.tts.jni;

public class LicenseInfo {
    private static final String TAG = "LicenseInfo";
    public String appDesc;
    public String appId;
    public String packageName;
    public int ret;
    public String time;

    public String getAppDesc() {
        return this.appDesc;
    }

    public String getAppId() {
        return this.appId;
    }

    public String getPackageName() {
        return this.packageName;
    }

    public int getRet() {
        return this.ret;
    }

    public String getTime() {
        return this.time;
    }

    @Override
    public String toString() {
        return "LicenseInfo{ret=" + this.ret
                + ", time='" + this.time
                + "', packageName='" + this.packageName
                + "', appDesc='" + this.appDesc
                + "', appId='" + this.appId + "'}";
    }
}
