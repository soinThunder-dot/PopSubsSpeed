package com.yourapp.jpgrammar

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import org.atilika.kuromoji.Token
import org.atilika.kuromoji.Tokenizer

object JpGrammarHighlighter {

    // 全 app 共用一個 tokenizer
    private val tokenizer: Tokenizer by lazy {
        Tokenizer.builder().build()
    }

    fun highlight(line: String): CharSequence {
        val tokens: List<Token> = tokenizer.tokenize(line)
        val spannable = SpannableString(line)

        var index = 0

        for (t in tokens) {
            val surface = t.surfaceForm
            val start = line.indexOf(surface, index)
            if (start < 0) continue
            val end = start + surface.length
            index = end

            val cls = classifyToken(t)
            applySpan(spannable, start, end, cls)
        }

        return spannable
    }

    private fun classifyToken(t: Token): TokenClass {
        val pos = t.partOfSpeech.split("-")  // 有些實作會用 "-" 連結
        val mainPos = pos[0]  // 名詞／動詞／形容詞／助詞 等

        return when (mainPos) {
            "名詞" -> TokenClass.NOUN
            "動詞" -> TokenClass.VERB
            "助詞" -> TokenClass.PARTICLE
            "形容詞" -> TokenClass.ADJECTIVE
            else -> TokenClass.OTHER
        }
    }

    private fun applySpan(spannable: SpannableString, start: Int, end: Int, cls: TokenClass) {
        when (cls) {
            TokenClass.NOUN -> {
                spannable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#4FC3F7")),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            TokenClass.VERB -> {
                spannable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#FFEE58")),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            TokenClass.PARTICLE -> {
                spannable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#BA68C8")),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            TokenClass.ADJECTIVE -> {
                spannable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#81C784")),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            TokenClass.OTHER -> {
                // 不加底線或顏色
            }
        }
    }

    private enum class TokenClass {
        NOUN, VERB, PARTICLE, ADJECTIVE, OTHER
    }
}
