package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.bdtts.BdEngineAdapter
import io.legado.app.help.bdtts.BdImportException
import io.legado.app.help.bdtts.BdSpeakerRecord
import io.legado.app.help.bdtts.BdSpeakerStore
import io.legado.app.help.bdtts.BdSynthCallback
import io.legado.app.help.bdtts.BdVoicePackImporter
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 本地百度 TTS 引擎管理：语音包导入、发音人列表、旁白/对白池标记、试听、编辑。
 * 手势：短按=加入/移出旁白池，长按=加入/移出对白池（两池互斥）；"编辑"=编辑发音人；"试听"=合成试听。
 */
class BdEngineManageActivity : BaseActivity<BdEngineManageActivity.ContentBinding>() {

    class ContentBinding(private val contentView: View) : ViewBinding {
        override fun getRoot(): View = contentView
    }

    override val binding: ContentBinding by lazy { ContentBinding(buildContentView()) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val records = mutableListOf<BdSpeakerRecord>()
    private lateinit var listAdapter: SpeakerAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var auditionAdapter: BdEngineAdapter? = null

    private val importZipResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { importZip(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reload()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) = Unit

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        auditionAdapter?.release()
        auditionAdapter = null
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primaryColor)
        }
        val title = TextView(this).apply {
            text = "本地百度 TTS"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 40, 48, 24)
        }
        val importBtn = Button(this).apply {
            text = "导入语音包（zip）"
            setOnClickListener { pickZip() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 0, 24, 8)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(importBtn)
        }
        val tip = TextView(this).apply {
            text = "短按发音人：旁白池　长按：对白池（互斥）"
            textSize = 12f
            setPadding(48, 4, 48, 12)
        }
        val listView = ListView(this).apply { id = android.R.id.list }
        listAdapter = SpeakerAdapter()
        listView.adapter = listAdapter
        root.addView(header)
        root.addView(tip)
        root.addView(
            listView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        return root
    }

    private fun pickZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        importZipResult.launch(intent)
    }

    private fun importZip(uri: Uri) {
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        BdVoicePackImporter.import(this@BdEngineManageActivity, input)
                    } ?: throw BdImportException("无法读取文件")
                }
                toastOnUi("导入成功，共 $count 个发音人")
                reload()
            } catch (e: BdImportException) {
                AppLog.putDebug("[百度TTS] 导入失败：${e.message}")
                toastOnUi("导入失败：${e.message}")
            } catch (e: Exception) {
                AppLog.putDebug("[百度TTS] 导入异常：${e.message}")
                toastOnUi("导入失败（zip 损坏？）：${e.message}")
            }
        }
    }

    private fun reload() {
        records.clear()
        records.addAll(BdSpeakerStore.load())
        listAdapter.notifyDataSetChanged()
    }

    private fun togglePool(id: String, narration: Boolean) {
        if (narration) {
            val set = getPrefStringSet(PreferKey.bdNarrationVoices)?.toMutableSet() ?: mutableSetOf()
            if (!set.remove(id)) set.add(id)
            putPrefStringSet(PreferKey.bdNarrationVoices, set)
            val other = getPrefStringSet(PreferKey.bdDialogueVoices)?.toMutableSet()
            if (other?.remove(id) == true) {
                putPrefStringSet(PreferKey.bdDialogueVoices, other)
            }
            toastOnUi(if (set.contains(id)) "已加入旁白池" else "已移出旁白池")
        } else {
            val set = getPrefStringSet(PreferKey.bdDialogueVoices)?.toMutableSet() ?: mutableSetOf()
            if (!set.remove(id)) set.add(id)
            putPrefStringSet(PreferKey.bdDialogueVoices, set)
            val other = getPrefStringSet(PreferKey.bdNarrationVoices)?.toMutableSet()
            if (other?.remove(id) == true) {
                putPrefStringSet(PreferKey.bdNarrationVoices, other)
            }
            toastOnUi(if (set.contains(id)) "已加入对白池" else "已移出对白池")
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun showEditDialog(record: BdSpeakerRecord?) {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        fun field(hint: String, value: String?): EditText {
            val et = EditText(ctx)
            et.hint = hint
            et.setText(value ?: "")
            et.setSingleLine(true)
            container.addView(et)
            return et
        }
        val isNew = record == null
        val etCode = field("代号（code）", record?.code)
        val etName = field("名称", record?.name)
        val etParam = field("参数（授权ID,宿主包名,声学模型,音色编号[,文本文件]）", record?.param)
        val etRate = field("采样率", record?.sampleRate?.toString())
        val etDesc = field("描述", record?.desc)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (isNew) "新建发音人" else "编辑发音人")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val code = etCode.text.toString().trim()
                if (code.isEmpty()) {
                    toastOnUi("代号不能为空")
                    return@setPositiveButton
                }
                val r = record ?: BdSpeakerRecord(group = "bdetts")
                r.code = code
                r.name = etName.text.toString().ifEmpty { code }
                r.param = etParam.text.toString().trim()
                r.sampleRate = etRate.text.toString().toIntOrNull() ?: 16000
                r.desc = etDesc.text.toString()
                r.id = "bdetts_$code"
                BdSpeakerStore.upsert(r)
                reload()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private class Audition(val wav: File? = null, val error: String? = null)

    private fun audition(record: BdSpeakerRecord) {
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    auditionInternal(record)
                }
                val wav = outcome.wav
                if (wav == null) {
                    toastOnUi("试听失败：${outcome.error}")
                    return@launch
                }
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    setDataSource(wav.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        mediaPlayer = null
                        wav.delete()
                    }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                AppLog.putDebug("[百度TTS] 试听异常：${e.message}")
                toastOnUi("试听异常：${e.message}")
            }
        }
    }

    private fun auditionInternal(record: BdSpeakerRecord): Audition {
        auditionAdapter?.release()
        val adapter = BdEngineAdapter(this, record.code, record.param)
        auditionAdapter = adapter
        adapter.init()
        adapter.initError?.let { return Audition(error = it) }
        val pcm = mutableListOf<ByteArray>()
        var done = false
        var synthError: String? = null
        adapter.synthesize(1.0f, 1.0f, "你好，这是语音试听。", object : BdSynthCallback {
            override fun onStart() = Unit
            override fun onError(message: String) {
                AppLog.putDebug("[百度TTS] 试听合成失败：$message")
                synthError = message
                done = true
            }

            override fun onDone(message: String) {
                done = true
            }

            override fun onAudioData(length: Int, data: ByteArray) {
                synchronized(pcm) { pcm.add(data.copyOf(length)) }
            }
        })
        var waited = 0
        while (!done && waited < 8000) {
            Thread.sleep(100)
            waited += 100
        }
        if (pcm.isEmpty()) {
            return Audition(error = synthError ?: "合成超时且未收到音频数据（done=$done）")
        }
        val total = pcm.sumOf { it.size }
        val dir = File(cacheDir, "bdtts_audition").apply { mkdirs() }
        val file = File(dir, "audition.wav")
        FileOutputStream(file).use { out ->
            out.write(wavHeader(total, record.sampleRate))
            for (chunk in pcm) out.write(chunk)
        }
        return Audition(wav = file)
    }

    private fun wavHeader(pcmLength: Int, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) = System.arraycopy(
            s.toByteArray(Charsets.US_ASCII), 0, header, off, s.length
        )
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = (v shr 8 and 0xff).toByte()
            header[off + 2] = (v shr 16 and 0xff).toByte()
            header[off + 3] = (v shr 24 and 0xff).toByte()
        }
        putStr(0, "RIFF"); putInt(4, 36 + pcmLength); putStr(8, "WAVE"); putStr(12, "fmt ")
        putInt(16, 16); header[20] = 1; header[22] = 1; putInt(24, sampleRate)
        putInt(28, sampleRate * 2); header[32] = 2; header[34] = 16
        putStr(36, "data"); putInt(40, pcmLength)
        return header
    }

    private inner class SpeakerAdapter : BaseAdapter() {
        override fun getCount() = records.size
        override fun getItem(position: Int) = records[position]
        override fun getItemId(position: Int) = position.toLong()

        @SuppressLint("SetTextI18n")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val record = records[position]
            val holder: ViewHolder
            val view: View
            if (convertView == null) {
                val row = LinearLayout(this@BdEngineManageActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(48, 24, 48, 24)
                }
                val info = LinearLayout(this@BdEngineManageActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val name = TextView(this@BdEngineManageActivity).apply { textSize = 16f }
                val sub = TextView(this@BdEngineManageActivity).apply {
                    textSize = 12f
                    alpha = 0.7f
                }
                info.addView(name)
                info.addView(sub)
                val editBtn = Button(this@BdEngineManageActivity).apply { text = "编辑" }
                val playBtn = Button(this@BdEngineManageActivity).apply { text = "试听" }
                row.addView(info)
                row.addView(editBtn)
                row.addView(playBtn)
                view = row
                holder = ViewHolder(row, name, sub, editBtn, playBtn)
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as ViewHolder
            }
            val narration = getPrefStringSet(PreferKey.bdNarrationVoices).orEmpty().contains(record.id)
            val dialogue = getPrefStringSet(PreferKey.bdDialogueVoices).orEmpty().contains(record.id)
            val marks = buildString {
                if (narration) append("［旁白］")
                if (dialogue) append("［对白］")
            }
            holder.name.text = marks + record.name
            holder.sub.text = "${record.code} · ${record.param} · ${record.sampleRate}Hz"
            holder.row.setOnClickListener { togglePool(record.id, narration = true) }
            holder.row.setOnLongClickListener {
                togglePool(record.id, narration = false)
                true
            }
            holder.editBtn.setOnClickListener { showEditDialog(record) }
            holder.playBtn.setOnClickListener { audition(record) }
            return view
        }

        private inner class ViewHolder(
            val row: View,
            val name: TextView,
            val sub: TextView,
            val editBtn: Button,
            val playBtn: Button,
        )
    }
}
