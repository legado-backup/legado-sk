package io.legado.app.ui.book.cache

import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.databinding.DialogDownloadChoiceBinding
import io.legado.app.help.cache.CacheCoordinator
import io.legado.app.help.cache.CacheRequestSource
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.NotificationPermission
import io.legado.app.model.ReadBook
import io.legado.app.utils.toastOnUi

/**
 * 「缓存章节范围」对话框：让用户填起止章，提交一次正文下载。
 *
 * 原先是听书页底部缓存按钮的内联实现；听书页底部改为功能胶囊栏后，缓存入口
 * 移入朗读设置，实现随之提取到这里，避免在设置对话框里再抄一份。
 *
 * 语义（保持与原实现一致）：显式指定范围即视为**重复下载也要补最新评论**，
 * 故对每章打 READER REVIEW 待刷新标记，再由 MEDIA 任务按标记走 force 整页重抓。
 */
fun Fragment.showBookDownloadRangeDialog(book: Book) {
    alert(titleResource = R.string.offline_cache) {
        val total = ReadBook.simulatedChapterSize
            .coerceAtLeast(book.totalChapterNum)
            .coerceAtLeast(1)
        val alertBinding = DialogDownloadChoiceBinding.inflate(layoutInflater).apply {
            editStart.setText((ReadBook.durChapterIndex + 1).coerceAtLeast(1).toString())
            editEnd.setText(total.toString())
        }
        customView { alertBinding.root }
        okButton {
            NotificationPermission.ensure(
                requireContext(),
                onGranted = {
                    val start = alertBinding.editStart.text?.toString()?.toIntOrNull()
                        ?.coerceIn(1, total) ?: 1
                    val end = alertBinding.editEnd.text?.toString()?.toIntOrNull()
                        ?.coerceIn(start, total) ?: total
                    val chapterIndexes = (start - 1)..(end - 1)
                    for (chapterIndex in chapterIndexes) {
                        CacheCoordinator.markReviewRefresh(book.bookUrl, chapterIndex)
                    }
                    CacheCoordinator.submitBookDownload(
                        book = book,
                        chapterIndexes = chapterIndexes,
                        source = CacheRequestSource.READER,
                        reviewIncremental = true,
                    )
                    toastOnUi(
                        getString(
                            R.string.cache_manage_cache_selected_done,
                            chapterIndexes.count(),
                        )
                    )
                },
                onDenied = {
                    toastOnUi(R.string.notification_permission_required_for_download)
                }
            )
        }
        cancelButton()
    }
}
