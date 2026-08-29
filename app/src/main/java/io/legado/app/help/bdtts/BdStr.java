package io.legado.app.help.bdtts;

import java.nio.charset.StandardCharsets;

/**
 * MultiTTS 字符串解密等价物：密钥循环异或。
 */
public final class BdStr {

    private BdStr() {
    }

    public static String dec(byte[] data, byte[] key) {
        if (key == null || key.length == 0) {
            key = new byte[]{0};
        }
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
