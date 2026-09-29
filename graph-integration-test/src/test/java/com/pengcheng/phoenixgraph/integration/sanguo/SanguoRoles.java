package com.pengcheng.phoenixgraph.integration.sanguo;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方案 ⑫：人物角色分类（君主 / 军师 / 谋士 / 武将 / 文臣），<b>由图谱里的关系推断</b>，
 * 不引入新的人工数据。
 *
 * <p>这是把 TuGraph 三国场景的「主公 / 文臣 / 武将」顶点分类落到本图谱上的做法 —— 分类不是
 * 写死的，而是从已有的边读出来的：有臣属的人是君主，有 {@code strategistOf} 的是军师，
 * 有 {@code advisorOf} 的是谋士，有 {@code killed} / {@code fought} 的是武将，任官职的是文臣。
 *
 * <p>一个人可能同时满足多个条件（关羽既任官又交战），所以按 {@link #PRIORITY} 取<b>最高</b>的一个，
 * 每个名字只会得到一个角色 —— 这样按角色分组时不会重复计数。
 */
public final class SanguoRoles {

    /** 角色，从高到低：一个人只取最高的那一个。 */
    public static final List<String> PRIORITY = List.of("君主", "军师", "谋士", "武将", "文臣");

    private SanguoRoles() {
    }

    /** The role of every person the relations describe, keyed by name. */
    public static Map<String, String> of(SanguoDataset dataset) {
        Map<String, Set<String>> byRole = new LinkedHashMap<>();
        for (String role : PRIORITY) {
            byRole.put(role, new LinkedHashSet<>());
        }
        for (SanguoDataset.Relation relation : dataset.relations()) {
            switch (relation.relation()) {
                // 有人效忠 → 他是主公（lord 的 head 是君主，tail 是臣；
                // 如 孙权 --lord--> 周瑜，孙权是周瑜的主公）
                case "lord" -> byRole.get("君主").add(relation.head());
                case "strategistOf" -> byRole.get("军师").add(relation.head());
                case "advisorOf" -> byRole.get("谋士").add(relation.head());
                case "killed", "fought" -> byRole.get("武将").add(relation.head());
                case "holds" -> byRole.get("文臣").add(relation.head());
                default -> {
                    // 其他关系不参与分类
                }
            }
        }
        Map<String, String> roles = new LinkedHashMap<>();
        for (String role : PRIORITY) {
            for (String person : byRole.get(role)) {
                // 一个人只拿最高优先级的角色，按角色分组时不重复
                roles.putIfAbsent(person, role);
            }
        }
        return roles;
    }

    /** How many people carry each role (diagnostics and the report). */
    public static Map<String, Integer> counts(Map<String, String> roles) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String role : PRIORITY) {
            counts.put(role, 0);
        }
        for (String role : roles.values()) {
            counts.merge(role, 1, Integer::sum);
        }
        return counts;
    }
}
