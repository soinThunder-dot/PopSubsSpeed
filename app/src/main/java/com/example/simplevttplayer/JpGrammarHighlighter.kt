package com.example.simplevttplayer

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import org.atilika.kuromoji.Token
import org.atilika.kuromoji.Tokenizer

object JpGrammarHighlighter {

    // 全 app 共用一個 tokenizer（lazy init）
    private val tokenizer: Tokenizer by lazy {
        Tokenizer.builder().build()
    }

    fun highlight(line: String): CharSequence {
        if (line.isBlank()) return line

        val tokens: List<Token> = tokenizer.tokenize(line)
        val spannable = SpannableString(line)

        var index = 0

        for (t in tokens) {
            val surface = t.surfaceForm
            val start = line.indexOf(surface, index)
            if (start < 0) continue
            val end = start + surface.length
            index = end

            val mainPos = t.partOfSpeech.split("-")[0] // 取「名詞」「動詞」這一級

            when (mainPos) {
                "名詞" -> applyUnderline(spannable, start, end, Color.parseColor("#42A5F5"))
                "動詞" -> applyUnderline(spannable, start, end, Color.parseColor("#FFEE58"))
                "助詞" -> applyUnderline(spannable, start, end, Color.parseColor("#BA68C8"))
                "形容詞" -> applyUnderline(spannable, start, end, Color.parseColor("#66BB6A"))
                else -> { /* 其它不標 */ }
            }
        }

        return spannable
    }

    private fun applyUnderline(spannable: SpannableString, start: Int, end: Int, color: Int) {
        // 下劃線 + 顏色
        spannable.setSpan(
            UnderlineSpan(),
            start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            ForegroundColorSpan(color),
            start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}
