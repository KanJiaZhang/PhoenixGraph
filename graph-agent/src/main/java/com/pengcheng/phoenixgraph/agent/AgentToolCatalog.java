package com.pengcheng.phoenixgraph.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tool catalog: the seven tools as a function-calling model wants them - a name, what it is for, and
 * the parameters with their types and defaults.
 *
 * <p>{@code GET /api/v1/agent/tools} returns exactly this, so an agent does not have to be told the
 * interface in a prompt that can drift out of date: it fetches the catalog, registers the tools, and calls
 * them over HTTP. The description of each tool says when to prefer it, because that is the part a model
 * gets wrong - {@code similar} and {@code related} are easy to swap.
 */
public final class AgentToolCatalog {

    private AgentToolCatalog() {
    }

    /** The catalog, in the order an agent should try the tools. */
    public static List<Map<String, Object>> tools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("find_entities",
                "Which entities is this text about? Name matches first, then the entities whose vector is "
                        + "closest to the text's. Call this first when you have a question but no entity id.",
                params(required("question", "string", "the user's question or a phrase from the document"),
                        optional("k", "integer", "how many entities to return (default 5)"))));
        tools.add(tool("related",
                "What is this entity connected to? Returns the neighbours with the relation names and the "
                        + "sentence each relation was read from. Use it for 'who/what is connected to X'.",
                params(required("entity", "string", "entity id (the name an index entry uses)"),
                        optional("relation", "string", "only this relation name, e.g. killed, serves"),
                        optional("hops", "integer", "1 (default), 2 or 3 hops"))));
        tools.add(tool("similar",
                "Which entities are most alike this one in vector space (the HNSW index the graph keeps)? "
                        + "Use it for 'who/what is like X' - it is *not* about graph connections.",
                params(required("entity", "string", "entity id"),
                        optional("k", "integer", "how many neighbours (default 5)"))));
        tools.add(tool("related_similar",
                "The lookalikes of everything around an entity: graph first, then vector. Use it to widen a "
                        + "question from one entity to its whole neighbourhood.",
                params(required("entity", "string", "entity id"),
                        optional("k", "integer", "lookalikes per neighbour (default 3)"))));
        tools.add(tool("similar_related",
                "What the lookalikes of an entity are connected to: vector first, then graph. Use it after "
                        + "similar() when you want facts, not more names.",
                params(required("entity", "string", "entity id"),
                        optional("k", "integer", "lookalikes to expand (default 3)"))));
        tools.add(tool("path_between",
                "Whether two entities are connected and by which walk (up to maxHops). Use it for "
                        + "'how are X and Y related'.",
                params(required("from", "string", "entity id"), required("to", "string", "entity id"),
                        optional("maxHops", "integer", "how far to look (default 3, at most 6)"))));
        tools.add(tool("context",
                "The whole context of a question: which entities it names or resembles, what sits around "
                        + "them, and the source sentences - the answer shape to hand to a model with sources.",
                params(required("question", "string", "the user's question"),
                        optional("k", "integer", "how many seed entities (default 5)"),
                        optional("hops", "integer", "how far to expand each seed (default 1, at most 2)"))));
        return tools;
    }

    /** The catalog as one JSON-ready document, with the answer shape described once. */
    public static Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", tools());
        out.put("answerShape", Map.of(
                "tool", "which tool answered",
                "entities", "id, type, name, score, via (name|vector|related|...), cites",
                "relations", "head, relation, tail, rule, cite, sentence (the provenance)",
                "paths", "walks between entities, when the tool walked any",
                "citations", "chunkId, documentId, cite, text - what to quote",
                "coverage", "entities, relations, citations, plus what the tool counted (and truncated)"));
        out.put("example", "POST /api/v1/agent/context {\"graph\":\"agent-demo\",\"question\":\"谁斩杀了关羽\",\"k\":3,\"hops\":1}");
        return out;
    }

    private static Map<String, Object> tool(String name, String description, List<Map<String, Object>> parameters) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("parameters", parameters);
        return tool;
    }

    private static List<Map<String, Object>> params(Map<String, Object>... parameters) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> parameter : parameters) {
            list.add(parameter);
        }
        return list;
    }

    private static Map<String, Object> required(String name, String type, String description) {
        return parameter(name, type, description, true);
    }

    private static Map<String, Object> optional(String name, String type, String description) {
        return parameter(name, type, description, false);
    }

    private static Map<String, Object> parameter(String name, String type, String description, boolean required) {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("name", name);
        parameter.put("type", type);
        parameter.put("description", description);
        parameter.put("required", required);
        return parameter;
    }
}
