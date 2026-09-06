package io.legado.app.ui.main.ai

import androidx.annotation.Keep
import java.util.UUID

@Keep
data class AiProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val headers: String? = "",
    /**
     * 是否支持多模态图片输入；null=老数据缺字段，视为支持。
     * 开关只长在供应商上，模型永远只当参数。
     */
    val supportVision: Boolean? = null
) {
    /** 只有显式 false 才走纯文本替换，其余一律按支持处理 */
    val supportsVision: Boolean get() = supportVision != false
}

@Keep
data class AiModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val modelId: String
)
