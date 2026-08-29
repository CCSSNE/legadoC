package io.legado.app.help.bdtts;

/**
 * bdetts 发音人 param 四段式解析。
 * param = 授权ID,冒充包名,声学模型名,音色编号[,文本前端文件]
 * 例: 90013,com.baidu.BaiduMap
 *     6082724,com.baidu.searchbox.tts.plugin,f33ms,34
 */
public class BdSpeakerParam {

    public String engineId = "90013";
    public String hostPackage = "com.baidu.BaiduMap";
    public String acousticModel = null;
    public String speakerAttr = null;
    public String textFile = null;

    public static BdSpeakerParam parse(String param) {
        BdSpeakerParam p = new BdSpeakerParam();
        if (param == null || param.isEmpty()) {
            return p;
        }
        String[] parts = param.split(",");
        if (parts.length >= 1 && !parts[0].trim().isEmpty()) {
            p.engineId = parts[0].trim();
        }
        if (parts.length >= 2 && !parts[1].trim().isEmpty()) {
            p.hostPackage = parts[1].trim();
        }
        if (parts.length >= 3 && !parts[2].trim().isEmpty()) {
            p.acousticModel = parts[2].trim();
        }
        if (parts.length >= 4 && !parts[3].trim().isEmpty()) {
            p.speakerAttr = parts[3].trim();
        }
        if (parts.length >= 5 && !parts[4].trim().isEmpty()) {
            p.textFile = parts[4].trim();
        }
        return p;
    }
}
