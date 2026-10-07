package com.fragpicker.digest;

import com.fasterxml.jackson.databind.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import com.fragpicker.integration.chat.ChatModelProperties;
import java.security.MessageDigest;
import java.util.*;

final class DigestRequestFingerprint {
    private DigestRequestFingerprint() { }
    static String hash(ChatRequest request, ChatModelProperties provider, ObjectMapper json) {
        try {
            var fields=new TreeMap<String,Object>();
            fields.put("protocol","fragpicker-digest-v1"); fields.put("baseUrl",provider.baseUrl()); fields.put("model",provider.model());
            fields.put("maxOutputTokens",request.parameters().maxOutputTokens()); fields.put("json",request.parameters().responseFormat()!=null);
            fields.put("system",((SystemMessage)request.messages().getFirst()).text());
            fields.put("input",canonical(json.readTree(((UserMessage)request.messages().get(1)).singleText()),json));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(fields)));
        } catch (Exception invalid) { throw new IllegalArgumentException("Cannot fingerprint digest request"); }
    }
    private static JsonNode canonical(JsonNode node, ObjectMapper json) {
        if (node.isObject()) {
            var sorted=json.createObjectNode(); var names=new ArrayList<String>(); node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names); names.forEach(name -> sorted.set(name,canonical(node.get(name),json))); return sorted;
        }
        if (node.isArray()) { var array=json.createArrayNode(); node.forEach(item -> array.add(canonical(item,json))); return array; }
        return node;
    }
}
