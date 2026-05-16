package com.example.simplevttplayer

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

object JpGrammarHighlighter {

    // 全 app 共用一個 Tokenizer 實例
    private val tokenizer: Tokenizer by lazy {
        Tokenizer()
    }

    fun highlight(line: String): CharSequence {
        val tokens: List<Token> = tokenizer.tokenize(line)
        val spannable = SpannableString(line)

        var index = 0

        for (t in tokens) {
            val surface = t.surface // 注意：是 surface，不是 surfaceForm
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
        val features = t.allFeatures.split(",")
        val mainPos = features.getOrNull(0) ?: ""
        val detail1 = features.getOrNull(1) ?: ""
    
        return when (mainPos) {
            "名詞" -> when (detail1) {
                "固有名詞" -> TokenClass.NOUN_PROPER
                "代名詞"   -> TokenClass.NOUN_PRONOUN
                "サ変接続" -> TokenClass.NOUN_SA_VERB
                "数", "副詞可能" -> TokenClass.NOUN_TEMP_OR_NUM
                else       -> TokenClass.NOUN_GENERAL
            }
            "動詞" -> when (detail1) {
                "自立" -> TokenClass.VERB_MAIN
                "非自立" -> TokenClass.VERB_AUX
                else -> TokenClass.VERB_MAIN
            }
            "形容詞" -> TokenClass.ADJ_I
            "形容動詞語幹" -> TokenClass.ADJ_NA
            "副詞" -> TokenClass.ADV
    
            "助詞" -> when (detail1) {
                "格助詞" -> TokenClass.PART_CASE
                "係助詞" -> TokenClass.PART_BINDING
                "副助詞" -> TokenClass.PART_ADVERBIAL
                "接続助詞" -> TokenClass.PART_CONNECTIVE
                "終助詞" -> TokenClass.PART_SENTENCE_END
                else -> TokenClass.PART_ADVERBIAL
            }
    
            "助動詞" -> TokenClass.AUX_VERB
            "連体詞" -> TokenClass.PRENOUN_ADJ
            "接続詞" -> TokenClass.CONJ
            "感動詞" -> TokenClass.INTERJECTION
            "記号", "フィラー", "その他" -> TokenClass.SYMBOL_OTHER
    
            else -> TokenClass.OTHER
        }
    }

    private fun applySpan(spannable: SpannableString, start: Int, end: Int, cls: TokenClass) {
        // 統一先畫底線
        if (cls != TokenClass.OTHER) {
            spannable.setSpan(
                UnderlineSpan(),
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    
        val color = when (cls) {
            TokenClass.NOUN_GENERAL       -> Color.parseColor("#2196F3")
            TokenClass.NOUN_PROPER        -> Color.parseColor("#FF9800")
            TokenClass.NOUN_PRONOUN       -> Color.parseColor("#E91E63")
            TokenClass.NOUN_SA_VERB       -> Color.parseColor("#9C27B0")
            TokenClass.NOUN_TEMP_OR_NUM   -> Color.parseColor("#3F51B5")
    
            TokenClass.VERB_MAIN          -> Color.parseColor("#FFEB3B")
            TokenClass.VERB_AUX           -> Color.parseColor("#FFC107")
    
            TokenClass.ADJ_I              -> Color.parseColor("#4CAF50")
            TokenClass.ADJ_NA             -> Color.parseColor("#8BC34A")
    
            TokenClass.ADV                -> Color.parseColor("#00BCD4")
    
            TokenClass.PART_CASE          -> Color.parseColor("#03A9F4")
            TokenClass.PART_BINDING       -> Color.parseColor("#FF5722")
            TokenClass.PART_ADVERBIAL     -> Color.parseColor("#9E9E9E")
            TokenClass.PART_CONNECTIVE    -> Color.parseColor("#795548")
            TokenClass.PART_SENTENCE_END  -> Color.parseColor("#AB47BC")
    
            TokenClass.AUX_VERB           -> Color.parseColor("#F06292")
            TokenClass.PRENOUN_ADJ        -> Color.parseColor("#009688")
            TokenClass.CONJ               -> Color.parseColor("#CDDC39")
            TokenClass.INTERJECTION       -> Color.parseColor("#F44336")
            TokenClass.SYMBOL_OTHER       -> Color.parseColor("#607D8B")
    
            TokenClass.OTHER              -> null
        }
    
        if (color != null) {
            spannable.setSpan(
                ForegroundColorSpan(color),
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    
    private enum class TokenClass {
        NOUN_GENERAL, NOUN_PROPER, NOUN_PRONOUN, NOUN_SA_VERB, NOUN_TEMP_OR_NUM,
        VERB_MAIN, VERB_AUX,
        ADJ_I, ADJ_NA,
        ADV,
        PART_CASE, PART_BINDING, PART_ADVERBIAL, PART_CONNECTIVE, PART_SENTENCE_END,
        AUX_VERB, PRENOUN_ADJ, CONJ, INTERJECTION,
        SYMBOL_OTHER,
        OTHER
    }
}
