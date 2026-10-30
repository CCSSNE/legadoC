# 眼动翻页模型

眼动识别代码移植自 Sun-QAQ/legado 的 28f718fa8b0dce3df3ca7210211fadbb165383b7，
代码按 GPL-3.0 协议提供，许可证见 ui/book/read/eye/LICENSE。

`face_landmarker.task` 是 Google MediaPipe Face Landmarker 官方 float16 v1 模型，
随 APK 打包，推理完全在设备本地执行。摄像头帧不保存、不上传。

- 模型地址：https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
- 官方说明：https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker
- SHA-256：`64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`
- Android 依赖：`com.google.mediapipe:tasks-vision:0.10.32`

识别结果包含 478 个脸部关键点，包括双眼虹膜。使用虹膜相对双眼眼角的位置
估计垂直视线，上下两点采样并通过中点校验后检测「底部停留 → 向上看」动作。
眨眼、多人、人脸丢失、明显头部位移及不连续采样会清除向上注视的累计时间；
已经就绪时保留原有的三秒窗口，只有有效底部注视才能续期。
这是一种基于前置摄像头的视线估计，效果受光线、眼镜反光、握持姿势影响。
需要 Android 7.0 及以上；仅用户主动启用后加载模型与打开摄像头。

眼睑仅用于过滤闭眼帧，不能用随视线移动的眼睑中点作为视线基准。
校准使用多帧视线和头部姿态的中位数；短暂眨眼暂停采样，长时间丢失人脸重置采样。
上下视线的差值必须同时大于最小幅度与采样噪声的三倍，避免仅放宽阈值造成误触。
上下点比较通过后，屏幕中央显示第三个注视点，复用相同的倒计时和稳定采样流程。
中点的归一化位置必须在 0.3～0.7 内，且加上两倍中点噪声后仍低于底部进入阈值 0.85；
中点姿态还需与上下点均接近，避免中部误判为底部。中点只用于验证，不修改上下映射。
通过后保存版本 2 的上下端点、噪声、中点及中点噪声。
上下点或中点校验失败会显示原因并自动从顶部重试一次。应用日志记录校准点统计、比较条件与超时原因，
开启日志记录后还会定期输出识别状态和处理延迟；不记录摄像头图像。
