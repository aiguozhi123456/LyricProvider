/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine

class KuGouLite : KuGou() {

    /**
     * 酷狗概念版 3.0.1 独立适配：
     * 3.0.1 移除了 LyricManager，改为 com.kugou.framework.lyric.l.a(String) 直接返回 LyricData。
     * 这里 hook 该方法，从内存中的 LyricData 读取逐字歌词，绕过失效的文件拦截。
     */
    fun hookLyricData301() {
        try {
            val loader = appClassLoader ?: return
            val lClass = "com.kugou.framework.lyric.l".toClass(loader) ?: return

            val method = try {
                lClass.getDeclaredMethod("a", String::class.java).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                return
            }

            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    runCatching {
                        val kObj = param?.result ?: return
                        // k.e -> LyricData
                        val lyricData = getField(kObj, "e") ?: return
                        val words = getField(lyricData, "e") as? Array<*> ?: return
                        val begins = getField(lyricData, "f") as? Array<*> ?: return
                        val delays = getField(lyricData, "g") as? Array<*> ?: return
                        val translates = getField(lyricData, "h") as? Array<*>

                        val lineCount = words.size
                        if (lineCount == 0) return

                        val lines = mutableListOf<RichLyricLine>()
                        for (i in 0 until lineCount) {
                            val lineWords = words[i] as? Array<*> ?: continue
                            val lineBegins = begins[i] as? LongArray ?: continue
                            val lineDelays = delays[i] as? LongArray ?: continue
                            if (lineWords.isEmpty() || lineWords.size != lineBegins.size || lineWords.size != lineDelays.size) continue

                            val wordList = mutableListOf<LyricWord>()
                            val sb = StringBuilder()
                            for (j in lineWords.indices) {
                                val w = lineWords[j] as? String ?: continue
                                val b = lineBegins[j]
                                val d = lineDelays[j]
                                wordList.add(LyricWord(begin = b, end = b + d, duration = d, text = w))
                                sb.append(w)
                            }
                            if (wordList.isEmpty()) continue

                            val lineBegin = wordList.first().begin
                            val lineEnd = wordList.last().end
                            val translation = translates?.let { t ->
                                (t[i] as? Array<*>)?.joinToString("") { (it as? String) ?: "" }?.takeIf { it.isNotBlank() }
                            }
                            lines.add(
                                RichLyricLine(
                                    begin = lineBegin,
                                    end = lineEnd,
                                    duration = (lineEnd - lineBegin).coerceAtLeast(0L),
                                    text = sb.toString(),
                                    words = wordList,
                                    translation = translation
                                )
                            )
                        }
                        if (lines.isNotEmpty()) onReceiveLyrics(lines)
                    }.onFailure {
                        // log if needed
                    }
                }
            })
        } catch (_: Throwable) {
            // hook not applicable for this version; fall back to base file interception
        }
    }

    private fun getField(obj: Any, name: String): Any? {
        return try {
            obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
        } catch (_: Throwable) {
            null
        }
    }

    override fun onAppCreate() {
        hookLyricData301()
    }
}
