package io.legado.app.ui.about

import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

enum class ReadRecordComponentType(
    @StringRes val titleRes: Int,
    @StringRes val hintRes: Int
) {
    OVERVIEW(R.string.read_record_component_overview, R.string.read_record_component_hint_overview),
    HEATMAP(R.string.read_record_component_heatmap, R.string.read_record_component_hint_heatmap),
    RECENT_BOOKS(R.string.read_record_component_recent_books, R.string.read_record_component_hint_recent_books),
    DAILY_RECORDS(R.string.read_record_component_daily_records, R.string.read_record_component_hint_daily_records),
    RECENT_COVERS(R.string.read_record_component_recent_covers, R.string.read_record_component_hint_recent_covers),
    READ_RANK(R.string.read_record_component_read_rank, R.string.read_record_component_hint_read_rank),
    GOAL_CARD(R.string.read_record_component_goal_card, R.string.read_record_component_hint_goal_card);

    companion object {
        fun fromKey(key: String?): ReadRecordComponentType? {
            return entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
        }
    }
}

data class ReadRecordComponentItem(
    val type: ReadRecordComponentType,
    var enabled: Boolean
)

object ReadRecordComponents {

    private val defaultOrder = listOf(
        ReadRecordComponentType.GOAL_CARD,
        ReadRecordComponentType.OVERVIEW,
        ReadRecordComponentType.HEATMAP,
        ReadRecordComponentType.RECENT_COVERS,
        ReadRecordComponentType.RECENT_BOOKS,
        ReadRecordComponentType.READ_RANK,
        ReadRecordComponentType.DAILY_RECORDS
    )

    fun load(): MutableList<ReadRecordComponentItem> {
        val raw = appCtx.getPrefString(PreferKey.readRecordComponents).orEmpty().trim()
        if (raw.isEmpty()) {
            return defaultOrder.map { ReadRecordComponentItem(it, true) }.toMutableList()
        }
        val parsed = raw.split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":")
                val type = ReadRecordComponentType.fromKey(parts.getOrNull(0)?.trim())
                val enabled = parts.getOrNull(1)?.trim() != "0"
                type?.let { ReadRecordComponentItem(it, enabled) }
            }
            .toMutableList()
        defaultOrder.forEach { type ->
            if (parsed.none { it.type == type }) {
                parsed += ReadRecordComponentItem(type, true)
            }
        }
        return parsed
    }

    fun save(items: List<ReadRecordComponentItem>) {
        val normalized = items.distinctBy { it.type }.ifEmpty {
            defaultOrder.map { ReadRecordComponentItem(it, true) }
        }
        val raw = normalized.joinToString(",") {
            "${it.type.name}:${if (it.enabled) 1 else 0}"
        }
        appCtx.putPrefString(PreferKey.readRecordComponents, raw)
    }
}
