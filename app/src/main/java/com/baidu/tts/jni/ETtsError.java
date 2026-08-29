package com.baidu.tts.jni;

public class ETtsError {
    private String message;
    private int ret;

    public String getMessage() {
        return this.message;
    }

    public int getRet() {
        return this.ret;
    }

    @Override
    public String toString() {
        return "ETtsError{ret=" + this.ret + ", message='" + this.message + "'}";
    }
}
