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
     * 酷狗概念版歌词适配（参考 SuperLyric 实测可用的方案）。
     * 概念版通过状态栏/魅族歌词通道输出 LyricData，hook 其渲染方法读取内存中的
     * 逐字数据，转成 RichLyricLine 走现有通道。
     * 两条路径对应不同版本混淆结构，用 try/catch 包裹，存在的那条生效。
     */
    fun hookLyric() {
        hookMeizuLyric1()
        hookMeizuLyric2()
    }

    /**
     * 路径一：com.kugou.android.lyric.j.d(Context, String, boolean)
     * LyricData 通过 uv.b.c().f(41) 获取，字段 f/i/j/k。
     */
    private fun hookMeizuLyric1() {
        try {
            val loader = appClassLoader ?: return
            val jClass = "com.kugou.android.lyric.j".toClass(loader) ?: return
            val method = try {
                jClass.getDeclaredMethod("d", android.content.Context::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                return
            }

            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    runCatching {
                        val lyric = param?.args?.getOrNull(1) as? String
                        val isClose = param?.args?.getOrNull(2) as? Boolean ?: true
                        if (!isClose && !lyric.isNullOrEmpty()) {
                            val c = callStaticMethod("uv.b", "c", loader)
                            val lyricData = c?.let { callMethod(it, "f", 41, loader) }
                            if (lyricData != null) {
                                val currentLine = getField(param.thisObject, "a") as? Int ?: 0
                                val wordss = getField(lyricData, "f") as? Array<*>
                                val wordBegins = getField(lyricData, "i") as? Array<*>
                                val wordDelays = getField(lyricData, "j") as? Array<*>
                                val translateWordss = getField(lyricData, "k") as? Array<*>
                                parseData(currentLine, wordss, translateWordss, wordBegins, wordDelays)
                            }
                        } else {
                            // isClose -> 停止（本项目暂无 stop 通道，忽略）
                        }
                    }
                }
            })
        } catch (_: Throwable) {
            // 该路径不适用于此版本
        }
    }

    /**
     * 路径二：com.kugou.android.lyric.e.a(Context, String, boolean)
     * LyricData 通过 com.kugou.framework.lyric.l.a().k() 获取，字段 e/f/g/h。
     */
    private fun hookMeizuLyric2() {
        try {
            val loader = appClassLoader ?: return
            val eClass = "com.kugou.android.lyric.e".toClass(loader) ?: return
            val method = try {
                eClass.getDeclaredMethod("a", android.content.Context::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                return
            }

            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    runCatching {
                        val lyric = param?.args?.getOrNull(1) as? String
                        val isClose = param?.args?.getOrNull(2) as? Boolean ?: true
                        if (!isClose && !lyric.isNullOrEmpty()) {
                            val lInst = callStaticMethod("com.kugou.framework.lyric.l", "a", loader)
                            val lyricData = lInst?.let { callMethod(it, "k", loader) }
                            if (lyricData != null) {
                                val currentLine = getField(param.thisObject, "a") as? Int ?: 0
                                val wordss = getField(lyricData, "e") as? Array<*>
                                val wordBegins = getField(lyricData, "f") as? Array<*>
                                val wordDelays = getField(lyricData, "g") as? Array<*>
                                val translateWordss = getField(lyricData, "h") as? Array<*>
                                parseData(currentLine, wordss, translateWordss, wordBegins, wordDelays)
                            }
                        }
                    }
                }
            })
        } catch (_: Throwable) {
            // 该路径不适用于此版本
        }
    }

    private fun parseData(
        currentLine: Int,
        wordss: Array<*>?,
        translateWordss: Array<*>?,
        wordBegins: Array<*>?,
        wordDelays: Array<*>?
    ) {
        if (wordss == null || wordBegins == null || wordDelays == null) return
        if (currentLine < 0 || currentLine >= wordss.size) return

        val lineWords = wordss[currentLine] as? Array<*> ?: return
        val lineBegins = wordBegins[currentLine] as? LongArray ?: return
        val lineDelays = wordDelays[currentLine] as? LongArray ?: return
        if (lineWords.isEmpty() || lineWords.size != lineBegins.size || lineWords.size != lineDelays.size) return

        val wordList = mutableListOf<LyricWord>()
        val sb = StringBuilder()
        for (j in lineWords.indices) {
            val w = lineWords[j] as? String ?: continue
            val b = lineBegins[j]
            val d = lineDelays[j]
            wordList.add(LyricWord(begin = b, end = b + d, duration = d, text = w))
            sb.append(w)
        }
        if (wordList.isEmpty()) return

        val lineBegin = wordList.first().begin
        val lineEnd = wordList.last().end
        val translation = translateWordss?.let { t ->
            (t[currentLine] as? Array<*>)?.joinToString("") { (it as? String) ?: "" }?.takeIf { it.isNotBlank() }
        }

        onReceiveLyrics(
            listOf(
                RichLyricLine(
                    begin = lineBegin,
                    end = lineEnd,
                    duration = (lineEnd - lineBegin).coerceAtLeast(0L),
                    text = sb.toString(),
                    words = wordList,
                    translation = translation
                )
            )
        )
    }

    private fun getField(obj: Any?, name: String): Any? {
        if (obj == null) return null
        return try {
            obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
        } catch (_: Throwable) {
            null
        }
    }

    private fun callStaticMethod(className: String, methodName: String, loader: ClassLoader): Any? {
        return try {
            val clazz = className.toClass(loader) ?: return null
            clazz.getDeclaredMethod(methodName).apply { isAccessible = true }.invoke(null)
        } catch (_: Throwable) {
            null
        }
    }

    private fun callMethod(obj: Any, methodName: String, arg: Int, loader: ClassLoader): Any? {
        return try {
            val clazz = obj.javaClass
            clazz.getDeclaredMethod(methodName, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(obj, arg)
        } catch (_: Throwable) {
            null
        }
    }

    override fun onAppCreate() {
        hookLyric()
    }
}
