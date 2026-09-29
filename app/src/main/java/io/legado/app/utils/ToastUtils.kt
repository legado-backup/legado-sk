@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.legado.app.databinding.ViewToastBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import splitties.systemservices.layoutInflater

private var toast: Toast? = null

private var toastLegacy: Toast? = null

/** 极短提示的自动取消句柄（见 [toastOnUiBrief]）。 */
private var briefToastTimeout: Runnable? = null

/**
 * 「一闪而过」的提示（默认 0.5 秒）。
 *
 * 为什么不能只靠 `Toast.setDuration`：`Toast` 只接受 `LENGTH_SHORT`(0) /
 * `LENGTH_LONG`(1) 两个常量，**任何其它值都不是"毫秒"**——系统会当作未知常量
 * 落回 SHORT（约 2 秒，且受无障碍「提示显示时长」影响）。所以"0.5 秒淡出"
 * 必须在到点后主动 `cancel()`。
 *
 * 复用与应用内其它提示**同一个**主题化 Toast（[toastOnUi]），因此外观、
 * 主题色与「禁用所有提示」开关的行为完全一致；这里只额外接管消失时机。
 */
fun Context.toastOnUiBrief(message: Int, durationMs: Long = BRIEF_TOAST_MS) {
    toastOnUiBrief(getString(message), durationMs)
}

fun Context.toastOnUiBrief(message: CharSequence?, durationMs: Long = BRIEF_TOAST_MS) {
    toastOnUi(message, Toast.LENGTH_SHORT)
    // 到点主动取消，得到远短于 LENGTH_SHORT 的一闪效果。
    briefToastTimeout?.let { briefHandler.removeCallbacks(it) }
    val timeout = Runnable {
        toast?.cancel()
        briefToastTimeout = null
    }
    briefToastTimeout = timeout
    briefHandler.postDelayed(timeout, durationMs)
}

private val briefHandler by lazy { buildMainHandler() }

private const val BRIEF_TOAST_MS = 500L

private fun cancelToastsWhenDisabled(): Boolean {
    if (!AppConfig.disableAllToast) return false
    toast?.cancel()
    toastLegacy?.cancel()
    return true
}

fun Context.toastOnUi(message: Int, duration: Int = Toast.LENGTH_SHORT) {
    toastOnUi(getString(message), duration)
}

@SuppressLint("InflateParams")
@Suppress("DEPRECATION")
fun Context.toastOnUi(message: CharSequence?, duration: Int = Toast.LENGTH_SHORT) {
    runOnUI {
        if (cancelToastsWhenDisabled()) return@runOnUI
        kotlin.runCatching {
            toast?.cancel()
            toast = Toast(this)
            val isLight = ColorUtils.isColorLight(bottomBackground)
            ViewToastBinding.inflate(layoutInflater).run {
                toast?.view = root
                cvToast.setCardBackgroundColor(bottomBackground)
                tvText.setTextColor(getPrimaryTextColor(isLight))
                tvText.text = message
            }
            toast?.duration = duration
            toast?.show()
        }
    }
}

fun Context.toastOnUiLegacy(message: CharSequence) {
    runOnUI {
        if (cancelToastsWhenDisabled()) return@runOnUI
        kotlin.runCatching {
            if (toastLegacy == null || AppConfig.debugLogEnabled) {
                toastLegacy = Toast.makeText(this, message, Toast.LENGTH_SHORT)
            } else {
                toastLegacy?.setText(message)
                toastLegacy?.duration = Toast.LENGTH_SHORT
            }
            toastLegacy?.show()
        }
    }
}

fun Context.longToastOnUi(message: Int) {
    toastOnUi(message, Toast.LENGTH_LONG)
}

fun Context.longToastOnUi(message: CharSequence?) {
    toastOnUi(message, Toast.LENGTH_LONG)
}

fun Context.longToastOnUiLegacy(message: CharSequence) {
    runOnUI {
        if (cancelToastsWhenDisabled()) return@runOnUI
        kotlin.runCatching {
            if (toastLegacy == null || AppConfig.debugLogEnabled) {
                toastLegacy = Toast.makeText(this, message, Toast.LENGTH_LONG)
            } else {
                toastLegacy?.setText(message)
                toastLegacy?.duration = Toast.LENGTH_LONG
            }
            toastLegacy?.show()
        }
    }
}

fun Fragment.toastOnUi(message: Int) = requireActivity().toastOnUi(message)

fun Fragment.toastOnUi(message: CharSequence) = requireActivity().toastOnUi(message)

fun Fragment.longToast(message: Int) = requireContext().longToastOnUi(message)

fun Fragment.longToast(message: CharSequence) = requireContext().longToastOnUi(message)
