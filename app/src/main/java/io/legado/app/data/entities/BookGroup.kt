package io.legado.app.data.entities

import android.content.Context
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import kotlinx.parcelize.Parcelize

@Suppress("ConstPropertyName")
@Parcelize
@Entity(tableName = "book_groups")
data class BookGroup(
    @PrimaryKey
    val groupId: Long = 0b1,
    var groupName: String = "",
    var cover: String? = null,
    var order: Int = 0,
    @ColumnInfo(defaultValue = "1")
    var enableRefresh: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    var show: Boolean = true,
    @ColumnInfo(defaultValue = "-1")
    var bookSort: Int = -1,
    // 只更新已读
    @ColumnInfo(defaultValue = "0")
    var onlyUpdateRead: Boolean = false
) : Parcelable {

    companion object {
        const val IdRoot = -100L
        const val IdPrimaryAll = -101L
        const val IdNovel = -102L
        const val IdAll = -1L
        const val IdLocal = -2L
        const val IdAudio = -3L
        const val IdUngrouped = -4L
        const val IdVideo = -6L
        const val IdImage = -7L
        const val IdError = -11L

        /** 书架 style1 顶部主分类（默认顺序） */
        val primaryGroupIds = listOf(IdPrimaryAll, IdNovel, IdImage, IdAudio, IdVideo)

        /** 分组管理界面不展示的系统主分类 */
        val hiddenInGroupManageIds = setOf(IdImage, IdAudio, IdVideo)
    }

    fun getManageName(context: Context): String {
        return when (groupId) {
            IdAll -> "$groupName(${context.getString(R.string.all)})"
            IdAudio -> "$groupName(${context.getString(R.string.audio)})"
            IdImage -> "$groupName(${context.getString(R.string.manga)})"
            IdLocal -> "$groupName(${context.getString(R.string.local)})"
            IdUngrouped -> "$groupName(${context.getString(R.string.no_group)})"
            IdVideo -> "$groupName(${context.getString(R.string.video)})"
            IdError -> "$groupName(${context.getString(R.string.update_book_fail)})"
            else -> groupName
        }
    }

    fun getRealBookSort(): Int {
        if (bookSort < 0) {
            return AppConfig.bookshelfSort
        }
        return bookSort
    }

    override fun hashCode(): Int {
        return groupId.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is BookGroup) {
            return other.groupId == groupId
                    && other.groupName == groupName
                    && other.cover == cover
                    && other.bookSort == bookSort
                    && other.enableRefresh == enableRefresh
                    && other.onlyUpdateRead == onlyUpdateRead
                    && other.show == show
                    && other.order == order
        }
        return false
    }

}
