package io.legado.app.ui.book.changesource

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemChangeSourceBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.showPopupMenu
import io.legado.app.utils.visible
import splitties.init.appCtx
import splitties.views.onLongClick


class ChangeBookSourceAdapter(
    context: Context,
    val viewModel: ChangeBookSourceViewModel,
    val callBack: CallBack
) : DiffRecyclerAdapter<SearchBook, ItemChangeSourceBinding>(context) {

    override val diffItemCallback = object : DiffUtil.ItemCallback<SearchBook>() {
        override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
            return oldItem.bookUrl == newItem.bookUrl
        }

        override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
            return oldItem.originName == newItem.originName
                    && oldItem.getDisplayLastChapterTitle() == newItem.getDisplayLastChapterTitle()
                    && oldItem.chapterWordCountText == newItem.chapterWordCountText
                    && oldItem.respondTime == newItem.respondTime
        }

    }

    override fun getViewBinding(parent: ViewGroup): ItemChangeSourceBinding {
        return ItemChangeSourceBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemChangeSourceBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            if (payloads.isEmpty()) {
                tvOrigin.text = item.originName
                tvAuthor.text = item.author
                tvLast.text = item.getDisplayLastChapterTitle()
                tvCurrentChapterWordCount.text = item.chapterWordCountText
                tvRespondTime.text = context.getString(R.string.respondTime, item.respondTime)
                if (callBack.isCurrentSource(item)) {
                    ivChecked.visible()
                } else {
                    ivChecked.invisible()
                }
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvOrigin.text = item.originName
                            "latest" -> tvLast.text = item.getDisplayLastChapterTitle()
                            "upCurSource" -> if (callBack.isCurrentSource(item)) {
                                ivChecked.visible()
                            } else {
                                ivChecked.invisible()
                            }
                        }
                    }
                }
            }
            val score = callBack.getBookScore(item)
            if (score > 0) {
                binding.ivBad.gone()
                binding.ivGood.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_A200)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
            } else if (score < 0) {
                binding.ivGood.gone()
                binding.ivBad.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_A200)
                )
            } else {
                binding.ivGood.visible()
                binding.ivBad.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
            }

            if (AppConfig.changeSourceLoadWordCount && !item.chapterWordCountText.isNullOrBlank()) {
                tvCurrentChapterWordCount.visible()
            } else {
                tvCurrentChapterWordCount.gone()
            }

            if (AppConfig.changeSourceLoadWordCount && item.respondTime >= 0) {
                tvRespondTime.visible()
            } else {
                tvRespondTime.gone()
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChangeSourceBinding) {
        binding.ivGood.setOnClickListener {
            if (binding.ivBad.isVisible) {
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_A200)
                )
                binding.ivBad.gone()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 1)
                }
            } else {
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                binding.ivBad.visible()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 0)
                }
            }
        }
        binding.ivBad.setOnClickListener {
            if (binding.ivGood.isVisible) {
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_A200)
                )
                binding.ivGood.gone()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, -1)
                }
            } else {
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
                binding.ivGood.visible()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 0)
                }
            }
        }
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                if (!callBack.isCurrentSource(it)) {
                    callBack.changeTo(it)
                } else {
                    // 该行就是当前书源（右侧带勾）。原实现直接静默返回，
                    // 点上去毫无反应，用户只会以为程序坏了。
                    callBack.onCurrentSourceClick(it)
                }
            }
        }
        holder.itemView.onLongClick {
            showMenu(holder.itemView, getItem(holder.layoutPosition))
        }
    }

    private fun showMenu(view: View, searchBook: SearchBook?) {
        searchBook ?: return
        view.showPopupMenu(
            R.menu.change_source_item,
        ) {
            when (it.itemId) {
                R.id.menu_top_source -> {
                    callBack.topSource(searchBook)
                }

                R.id.menu_bottom_source -> {
                    callBack.bottomSource(searchBook)
                }

                R.id.menu_edit_source -> {
                    callBack.editSource(searchBook)
                }

                R.id.menu_disable_source -> {
                    callBack.disableSource(searchBook)
                }

                R.id.menu_delete_source -> context.alert(R.string.draw) {
                    setMessage(context.getString(R.string.sure_del) + "\n" + searchBook.originName)
                    noButton()
                    yesButton {
                        callBack.deleteSource(searchBook)
                        updateItems(0, itemCount, listOf<Int>())
                    }
                }
            }
            true
        }
    }

    interface CallBack {
        /**
         * 判断某一行**是不是当前正在使用的书源**。
         *
         * ⚠️ 这里是本对话框唯一判定「当前源」的地方，两个 Adapter 的勾与点击拦截都走它，
         * 不要再各自内联比较字段。
         *
         * 为什么不能只看 `bookUrl`（原实现）也不能只看 `origin`：
         * - `bookUrl`：10054 起换源走 `BookUpsert` 身份归并，`BookUpsert.kt:99-103`
         *   会把合并结果的 `bookUrl` **强制回写成旧记录的**（主键兼缓存目录地址，不能变），
         *   于是库里 `bookUrl` 永远指不到「刚选中的那一行」⇒ 勾停在旧源那行。
         * - `origin`：只到**书源**粒度。一个书源（尤其聚合源）一次搜索返回多条，
         *   各行 `origin` 相同而 `bookUrl` 不同 ⇒ 会把同源所有行**全部打勾**。
         * - `tocUrl`：由该行自己的详情页解析（`BookInfo.kt:151`），**行级唯一**，
         *   且 `BookMergeRules.mergeInto:161` 保留了选中行的值 ⇒ 归并后仍能指回那一行。
         *   故以 `origin` 圈定书源、`tocUrl` 圈定具体哪一行。
         */
        fun isCurrentSource(searchBook: SearchBook): Boolean
        fun changeTo(searchBook: SearchBook)

        /** 点到「当前正在使用的书源」那一行：不能换源，但必须给出可见反馈。 */
        fun onCurrentSourceClick(searchBook: SearchBook)
        fun topSource(searchBook: SearchBook)
        fun bottomSource(searchBook: SearchBook)
        fun editSource(searchBook: SearchBook)
        fun disableSource(searchBook: SearchBook)
        fun deleteSource(searchBook: SearchBook)
        fun setBookScore(searchBook: SearchBook, score: Int)
        fun getBookScore(searchBook: SearchBook): Int
    }
}
