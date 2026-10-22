package com.pengcheng.phoenixgraph.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * A conservative traditional-to-simplified Chinese mapping, for the deduper's safe canonicalization tier.
 *
 * <p>It is many-to-one and deterministic: 關 and 关 both become 关, so two entities that differ only by
 * traditional/simplified spelling are the same entity with the same certainty as a case difference in a
 * Latin name. It is a curated subset of the standard 繁→简 table - a character the table does not cover is
 * left as it is, which is safe: a missing pair stays two entities, never a wrong merge.
 */
public final class ChineseSimplifier {

    /** Each entry is one 繁→简 pair; keeping each pair its own string means a typo cannot shift the rest. */
    private static final String[] PAIRS = {
            // names and the words that describe people
            "關关", "張张", "劉刘", "孫孙", "趙赵", "馬马", "黃黄", "吳吴", "諸诸", "雲云",
            "飛飞", "龍龙", "鳳凤", "華华", "國国", "軍军", "師师", "將将", "帥帅", "漢汉",
            "陳陈", "鄭郑", "楊杨", "歐欧", "陽阳", "東东", "戰战", "勝胜", "敗败", "敵敌",
            "備备", "權权", "歲岁", "樂乐", "樓楼", "樹树", "橋桥", "機机", "樣样", "檢检",
            "歷历", "沒没", "決决", "況况", "測测", "準准", "澤泽", "濟济", "溫温", "灣湾",
            "爭争", "獨独", "獲获", "產产", "畫画", "眾众", "種种", "積积", "稱称", "簡简",
            "類类", "紅红", "約约", "級级", "紀纪", "統统", "絲丝", "經经", "綠绿", "維维",
            "網网", "線线", "編编", "總总", "續续", "說说", "話话", "語语", "讀读", "書书",
            "寫写", "學学", "見见", "問问", "門门", "開开", "無无", "為为", "與与", "於于",
            "長长", "當当", "黨党", "發发", "愛爱", "氣气", "兒儿", "爾尔", "單单", "會会",
            "們们", "來来", "時时", "對对", "過过", "還还", "進进", "這这", "裏里", "後后",
            "從从", "萬万", "電电", "頭头", "買买", "賣卖", "錢钱", "銀银", "鐵铁", "車车",
            "輪轮", "轉转", "較较", "輕轻", "點点", "實实", "寶宝", "聽听", "覺觉", "觀观",
            "現现", "規规", "則则", "剛刚", "別别", "創创", "動动", "務务", "勞劳", "勢势",
            "區区", "醫医", "雙双", "難难", "項项", "順顺", "須须", "領领", "風风", "飯饭",
            "飲饮", "鳥鸟", "魚鱼",
    };

    private static final Map<Character, Character> SIMPLIFIED = table();

    private ChineseSimplifier() {
    }

    /** Maps each traditional character to its simplified form; characters not in the table are kept. */
    public static String toSimplified(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(SIMPLIFIED.getOrDefault(c, c));
        }
        return out.toString();
    }

    private static Map<Character, Character> table() {
        Map<Character, Character> m = new HashMap<>();
        for (String pair : PAIRS) {
            if (pair.length() == 2) {
                m.put(pair.charAt(0), pair.charAt(1));
            }
        }
        return m;
    }
}
