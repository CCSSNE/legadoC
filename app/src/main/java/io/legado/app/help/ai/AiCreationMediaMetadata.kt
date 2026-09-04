package io.legado.app.help.ai

import io.legado.app.utils.indexOf
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * AI 创作媒体元数据：把工作流 JSON 写进/读出文件字节，模仿 ComfyUI 的做法——
 * PNG 在 IHDR 后插入 iTXt 文本块（UTF-8，支持中文提示词）；
 * MP4 在文件尾追加顶层 meta box（hdlr=mdta 的 keys/ilst 结构）。
 * 追加与插入都不触碰 MP4 既有 box 的绝对偏移（stco 不受影响），不破坏播放。
 * 纯字节数组操作：文件签名不符或结构异常时如实返回原字节 / null，不伪装注入成功。
 */
object AiCreationMediaMetadata {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private val MP4_TOP_TYPES = setOf("hdlr", "keys", "ilst")

    private val ZERO_BYTE = byteArrayOf(0)

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)

    private fun isMp4(bytes: ByteArray): Boolean =
        bytes.size > 12 &&
            bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))

    /** 按文件签名读取工作流 JSON 文本：PNG 走文本块，MP4 走 meta box，其余返回 null */
    fun readWorkflowJson(bytes: ByteArray): String? {
        val text = when {
            isPng(bytes) -> runCatching { readPngText(bytes, AiCreationWorkflow.PNG_TEXT_KEY) }
                .getOrNull()

            isMp4(bytes) -> runCatching { readMp4Meta(bytes, AiCreationWorkflow.MP4_META_KEY) }
                .getOrNull()

            else -> null
        } ?: return null
        return text.takeIf { it.isNotBlank() }
    }

    /** PNG 在 IHDR 后插入 iTXt chunk；非 PNG 或结构异常时返回原字节 */
    fun injectPngWorkflow(png: ByteArray, json: String): ByteArray {
        if (!isPng(png)) return png
        return runCatching {
            val chunk = buildTextChunk(AiCreationWorkflow.PNG_TEXT_KEY, json)
            val ihdrEnd = 8 + readInt(png, 8) + 12
            if (ihdrEnd < 0 || ihdrEnd > png.size) return png
            val out = ByteArray(png.size + chunk.size)
            System.arraycopy(png, 0, out, 0, ihdrEnd)
            System.arraycopy(chunk, 0, out, ihdrEnd, chunk.size)
            System.arraycopy(png, ihdrEnd, out, ihdrEnd + chunk.size, png.size - ihdrEnd)
            out
        }.getOrDefault(png)
    }

    /** MP4 顶层追加 meta box（hdlr=mdta + keys + ilst）；非 MP4 时返回原字节 */
    fun injectMp4Workflow(mp4: ByteArray, json: String): ByteArray {
        if (!isMp4(mp4)) return mp4
        return runCatching {
            val meta = buildMp4MetaBox(json)
            val out = ByteArray(mp4.size + meta.size)
            System.arraycopy(mp4, 0, out, 0, mp4.size)
            System.arraycopy(meta, 0, out, mp4.size, meta.size)
            out
        }.getOrDefault(mp4)
    }

    // ———————— PNG chunk ————————

    /** iTXt data：keyword\0 压缩标志(0) 压缩方法(0) 语言\0 翻译关键字\0 UTF-8 文本（空语言与空翻译各占一个\0，不可省略） */
    private fun buildTextChunk(keyword: String, text: String): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(keyword.toByteArray(Charsets.ISO_8859_1))
            write(0) // keyword 结束符
            write(0) // 压缩标志：不压缩
            write(0) // 压缩方法
            write(0) // 空语言结束符
            write(0) // 空翻译关键字结束符
            write(text.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val chunkType = "iTXt".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(chunkType)
            update(payload)
        }
        return ByteArrayOutputStream(payload.size + 12).apply {
            write(intToBytes(payload.size))
            write(chunkType)
            write(payload)
            write(intToBytes(crc.value.toInt()))
        }.toByteArray()
    }

    /** 遍历 chunk 找到指定 keyword 的 tEXt/iTXt/zTXt 文本 */
    private fun readPngText(png: ByteArray, keyword: String): String? {
        var offset = 8
        while (offset + 8 <= png.size) {
            val length = readInt(png, offset)
            if (length < 0) return null
            val type = String(png, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            if (dataStart + length + 4 > png.size) return null
            if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                val pair = decodePngTextChunk(type, png, dataStart, length)
                if (pair?.first == keyword && pair.second.isNotBlank()) {
                    return pair.second
                }
            }
            offset = dataStart + length + 4
        }
        return null
    }

    private fun decodePngTextChunk(
        type: String,
        png: ByteArray,
        start: Int,
        length: Int
    ): Pair<String, String>? {
        val data = png.copyOfRange(start, start + length)
        val keywordEnd = data.indexOf(ZERO_BYTE)
        if (keywordEnd <= 0) return null
        val keyword = String(data, 0, keywordEnd, Charsets.ISO_8859_1)
        return when (type) {
            "tEXt" -> keyword to String(
                data,
                keywordEnd + 1,
                data.size - keywordEnd - 1,
                Charsets.ISO_8859_1
            )

            "zTXt" -> {
                // keyword\0 压缩方法(1B) zlib 数据
                if (data.size <= keywordEnd + 2) return null
                val inflated = inflate(data, keywordEnd + 2) ?: return null
                keyword to String(inflated, Charsets.ISO_8859_1)
            }

            else -> {
                // iTXt：keyword\0 压缩标志(1B) 压缩方法(1B) 语言\0 翻译关键字\0 文本
                if (data.size <= keywordEnd + 3) return null
                val compressed = data[keywordEnd + 1].toInt() == 1
                var pos = data.indexOf(ZERO_BYTE, keywordEnd + 3) + 1
                if (pos <= 0) return null
                pos = data.indexOf(ZERO_BYTE, pos) + 1
                if (pos <= 0 || pos > data.size) return null
                if (compressed) {
                    val inflated = inflate(data, pos) ?: return null
                    keyword to String(inflated, Charsets.UTF_8)
                } else {
                    keyword to String(data, pos, data.size - pos, Charsets.UTF_8)
                }
            }
        }
    }

    private fun inflate(data: ByteArray, start: Int): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(data, start, data.size - start)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, count)
        }
        inflater.end()
        out.toByteArray()
    }.getOrNull()

    // ———————— MP4 box ————————

    /** 顶层 meta box（fullbox）：hdlr=mdta + keys（单条 key）+ ilst（单条 UTF-8 data） */
    private fun buildMp4MetaBox(json: String): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)

        val hdlrPayload = ByteArrayOutputStream().apply {
            write(intToBytes(0)) // version/flags
            write(intToBytes(0)) // pre_defined
            write("mdta".toByteArray(Charsets.US_ASCII))
            write(ByteArray(12)) // reserved
        }.toByteArray()

        val keyName = AiCreationWorkflow.MP4_META_KEY.toByteArray(Charsets.UTF_8) + 0.toByte()
        val keysPayload = ByteArrayOutputStream().apply {
            write(intToBytes(0)) // version/flags
            write(intToBytes(1)) // entry_count
            write(intToBytes(keyName.size + 8)) // key_size：含 size 与 namespace 字段
            write(intToBytes(0)) // key_namespace
            write(keyName)
        }.toByteArray()

        val dataPayload = ByteArrayOutputStream().apply {
            write(intToBytes(1)) // version 0 + flags 1：UTF-8 文本
            write(intToBytes(0)) // locale
            write(payload)
        }.toByteArray()
        val dataBox = buildBox("data", dataPayload)

        // ilst 条目：4 字节 size + 4 字节 key 索引（big-endian 1）+ data box
        val entry = ByteArrayOutputStream(dataBox.size + 8).apply {
            write(intToBytes(dataBox.size + 8))
            write(intToBytes(1))
            write(dataBox)
        }.toByteArray()

        val metaPayload = ByteArrayOutputStream().apply {
            write(intToBytes(0)) // version/flags
            write(buildBox("hdlr", hdlrPayload))
            write(buildBox("keys", keysPayload))
            write(buildBox("ilst", entry))
        }.toByteArray()
        return buildBox("meta", metaPayload)
    }

    private fun buildBox(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream(payload.size + 8).apply {
            write(intToBytes(payload.size + 8))
            write(type.toByteArray(Charsets.US_ASCII))
            write(payload)
        }.toByteArray()

    /** 遍历顶层 box，找到 meta box 后按 keys/ilst 提取指定 key 的文本 */
    private fun readMp4Meta(mp4: ByteArray, key: String): String? {
        var offset = 0
        while (offset + 8 <= mp4.size) {
            var size = readInt(mp4, offset).toLong() and 0xFFFFFFFFL
            val type = String(mp4, offset + 4, 4, Charsets.US_ASCII)
            var headerSize = 8
            if (size == 1L) {
                if (offset + 16 > mp4.size) return null
                size = readLong(mp4, offset + 8)
                headerSize = 16
            } else if (size == 0L) {
                size = (mp4.size - offset).toLong()
            }
            if (size < headerSize || offset + size > mp4.size) return null
            if (type == "meta") {
                val text = readMp4MetaBox(
                    mp4,
                    offset + headerSize,
                    (offset + size).toInt(),
                    key
                )
                if (!text.isNullOrBlank()) return text
            }
            offset += size.toInt()
        }
        return null
    }

    private fun readMp4MetaBox(mp4: ByteArray, start: Int, end: Int, wantedKey: String): String? {
        var pos = start
        // meta 可能带 4 字节 version/flags（fullbox）也可能不带：开头 4 字节是子 box type 则不带
        if (pos + 4 <= end) {
            val head = String(mp4, pos, 4, Charsets.US_ASCII)
            if (head !in MP4_TOP_TYPES) {
                pos += 4
            }
        }
        var handlerIsMdta = false
        val keyNames = HashMap<Int, String>()
        while (pos + 8 <= end) {
            val size = readInt(mp4, pos)
            if (size < 8 || pos + size > end) break
            val type = String(mp4, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            when (type) {
                "hdlr" -> {
                    // size(4) type(4) version/flags(4) pre_defined(4) handler_type(4)
                    if (size >= 24 && dataStart + 20 <= end) {
                        handlerIsMdta =
                            String(mp4, dataStart + 16, 4, Charsets.US_ASCII) == "mdta"
                    }
                }

                "keys" -> {
                    var kpos = dataStart + 4 // version/flags
                    val count = readInt(mp4, kpos)
                    kpos += 4
                    var index = 1
                    while (index <= count && kpos + 8 <= pos + size) {
                        val keySize = readInt(mp4, kpos)
                        if (keySize < 8 || kpos + keySize > pos + size) break
                        var name = String(mp4, kpos + 8, keySize - 8, Charsets.UTF_8)
                        if (name.endsWith("\u0000")) {
                            name = name.dropLast(1)
                        }
                        keyNames[index] = name
                        index++
                        kpos += keySize
                    }
                }

                "ilst" -> {
                    if (!handlerIsMdta) break
                    val text = readMp4IlstEntry(
                        mp4,
                        dataStart,
                        pos + size,
                        keyNames.entries.firstOrNull { it.value == wantedKey }?.key
                    )
                    if (!text.isNullOrBlank()) return text
                }
            }
            pos += size
        }
        return null
    }

    /** ilst 条目：4 字节 size + 4 字节 key 索引 + data box（内部取 UTF-8 文本 payload） */
    private fun readMp4IlstEntry(
        mp4: ByteArray,
        start: Int,
        end: Int,
        wantedIndex: Int?
    ): String? {
        if (wantedIndex == null) return null
        var pos = start
        while (pos + 8 <= end) {
            val entrySize = readInt(mp4, pos)
            if (entrySize < 8 || pos + entrySize > end) break
            if (readInt(mp4, pos + 4) == wantedIndex) {
                var dpos = pos + 8
                val dend = pos + entrySize
                while (dpos + 8 <= dend) {
                    val dataSize = readInt(mp4, dpos)
                    if (dataSize < 8 || dpos + dataSize > dend) break
                    if (String(mp4, dpos + 4, 4, Charsets.US_ASCII) == "data") {
                        // data：size(4) type(4) version/flags(4) locale(4) payload
                        if (dataSize >= 16) {
                            return String(mp4, dpos + 16, dataSize - 16, Charsets.UTF_8)
                        }
                    }
                    dpos += dataSize
                }
            }
            pos += entrySize
        }
        return null
    }

    // ———————— 字节序工具 ————————

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readLong(bytes: ByteArray, offset: Int): Long =
        ((readInt(bytes, offset).toLong() and 0xFFFFFFFFL) shl 32) or
            (readInt(bytes, offset + 4).toLong() and 0xFFFFFFFFL)
}
