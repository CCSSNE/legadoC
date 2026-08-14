package io.legado.app.ui.book.group

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup

class GroupViewModel(application: Application) : BaseViewModel(application) {

    fun upGroup(vararg bookGroup: BookGroup, finally: (() -> Unit)? = null) {
        execute {
            if (bookGroup.any { it.defaultShow }) {
                appDb.bookGroupDao.clearDefaultShow()
            }
            appDb.bookGroupDao.update(*bookGroup)
        }.onFinally {
            finally?.invoke()
        }
    }

    fun addGroup(
        groupName: String,
        bookSort: Int,
        enableRefresh: Boolean,
        onlyUpdateRead: Boolean,
        defaultShow: Boolean,
        cover: String?,
        finally: () -> Unit
    ) {
        execute {
            if (defaultShow) {
                appDb.bookGroupDao.clearDefaultShow()
            }
            val groupId = appDb.bookGroupDao.getUnusedId()
            val bookGroup = BookGroup(
                groupId = groupId,
                groupName = groupName,
                cover = cover,
                bookSort = bookSort,
                enableRefresh = enableRefresh,
                onlyUpdateRead = onlyUpdateRead,
                defaultShow = defaultShow,
                order = appDb.bookGroupDao.maxOrder.plus(1)
            )
            appDb.bookGroupDao.getByID(groupId) ?: appDb.bookDao.removeGroup(groupId)
            appDb.bookGroupDao.insert(bookGroup)
        }.onFinally {
            finally()
        }
    }

    fun delGroup(bookGroup: BookGroup, finally: () -> Unit) {
        execute {
            appDb.bookGroupDao.delete(bookGroup)
            appDb.bookDao.removeGroup(bookGroup.groupId)
        }.onFinally {
            finally()
        }
    }


}