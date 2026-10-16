package xyz.moshushi.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import xyz.moshushi.app.util.LogFile
import java.io.File

/**
 * 只读分享运行日志。项目没接 AndroidX，自己写个最小 ContentProvider，
 * 不申请 INTERNET 权限，走系统分享面板把日志发出去。
 */
class LogShareProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "xyz.moshushi.app.logshare"
        val URI: Uri = Uri.parse("content://$AUTHORITY/log")
        private const val CODE_LOG = 1
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "log", CODE_LOG)
        }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        if (matcher.match(uri) == CODE_LOG) "text/plain" else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (matcher.match(uri) != CODE_LOG) return null
        val ctx = context ?: return null
        LogFile.init(ctx)
        val f = LogFile.file() ?: return null
        if (!f.exists()) return null
        // 只允许读
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
