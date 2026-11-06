package kz.qauipsiz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static String api;
    private static JsonNode topics;
    private static JsonNode links;

    public static void main(String[] args) throws Exception {
        String token = System.getenv("BOT_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Укажите BOT_TOKEN в переменных окружения IntelliJ IDEA.");
            return;
        }
        api = "https://api.telegram.org/bot" + token + "/";
        try (InputStream stream = Main.class.getResourceAsStream("/topics.json")) {
            if (stream == null) throw new IllegalStateException("topics.json не найден");
            JsonNode data = JSON.readTree(stream);
            topics = data.path("topics");
            links = data.path("links");
        }
        JsonNode me = request("getMe", JSON.createObjectNode());
        if (!me.path("ok").asBoolean()) throw new IllegalStateException("Неверный токен или Telegram недоступен: " + me);
        System.out.println("Бот запущен: @" + me.path("result").path("username").asText());
        long offset = 0;
        while (true) {
            try {
                ObjectNode params = JSON.createObjectNode();
                params.put("offset", offset);
                params.put("timeout", 25);
                ArrayNode updates = JSON.createArrayNode();
                updates.add("message");
                updates.add("callback_query");
                params.set("allowed_updates", updates);
                JsonNode response = request("getUpdates", params);
                if (!response.path("ok").asBoolean()) throw new IllegalStateException(response.toString());
                for (JsonNode update : response.path("result")) {
                    offset = update.path("update_id").asLong() + 1;
                    try { handle(update); } catch (Exception e) { System.err.println("Ошибка сообщения: " + e.getMessage()); }
                }
            } catch (Exception e) {
                System.err.println("Ошибка подключения: " + e.getMessage());
                Thread.sleep(3000);
            }
        }
    }

    private static void handle(JsonNode update) throws Exception {
        if (update.has("callback_query")) {
            JsonNode callback = update.path("callback_query");
            ObjectNode acknowledge = JSON.createObjectNode().put("callback_query_id", callback.path("id").asText());
            request("answerCallbackQuery", acknowledge);
            long chatId = callback.path("message").path("chat").path("id").asLong();
            String action = callback.path("data").asText();
            if (action.equals("home")) menu(chatId);
            else if (action.startsWith("topic:")) topic(chatId, action.substring(6));
            else if (action.startsWith("sub:")) sub(chatId, action.substring(4));
        } else if (update.has("message")) {
            JsonNode msg = update.path("message");
            long chatId = msg.path("chat").path("id").asLong();
            if (!msg.has("text")) return;
            String value = msg.path("text").asText().strip();
            if (value.equals("/start") || value.equals("/menu") || value.startsWith("/start ")) menu(chatId);
            else if (value.equals("/help")) send(chatId, "Тақырыпты таңдаңыз немесе іздеу үшін сөз жазыңыз. /menu — бас мәзір.", buttons());
            else search(chatId, value);
        }
    }

    private static void menu(long chatId) throws Exception {
        send(chatId, "🛡️ Қауіпсіз интернет көмекшісі\n\nСәлем! 👋 Мен қауіпсіз интернет бойынша көмекшімін. Қай тақырып қызықтырады?", buttons());
    }

    private static ArrayNode buttons() {
        ArrayNode rows = JSON.createArrayNode();
        for (JsonNode t : topics) rows.add(row(button(t.path("icon").asText() + " " + t.path("title").asText(), "topic:" + t.path("id").asText())));
        return rows;
    }

    private static void topic(long chatId, String id) throws Exception {
        JsonNode t = findTopic(id);
        if (t == null) { menu(chatId); return; }
        ArrayNode rows = JSON.createArrayNode();
        for (JsonNode s : t.path("subs")) rows.add(row(button(s.path("title").asText(), "sub:" + s.path("id").asText())));
        appendLinks(rows, id);
        rows.add(row(button("🏠 Бас мәзір", "home")));
        send(chatId, t.path("icon").asText() + " " + t.path("title").asText() + "\n\nТақырыпшаны таңдаңыз:", rows);
    }

    private static void sub(long chatId, String id) throws Exception {
        JsonNode found = null, parent = null;
        for (JsonNode t : topics) for (JsonNode s : t.path("subs")) {
            if (s.path("id").asText().equals(id)) { found = s; parent = t; }
        }
        if (found == null) { menu(chatId); return; }
        StringBuilder msg = new StringBuilder("📌 ").append(found.path("title").asText())
                .append("\n\n").append(found.path("text").asText());
        for (JsonNode item : found.path("items")) msg.append("\n• ").append(item.asText());
        ArrayNode rows = JSON.createArrayNode();
        for (JsonNode related : found.path("rel")) {
            String relatedId = related.asText();
            JsonNode relatedSub = findSub(relatedId);
            if (relatedSub != null) rows.add(row(button("→ " + relatedSub.path("title").asText(), "sub:" + relatedId)));
        }
        appendLinks(rows, id);
        rows.add(row(button("↩ " + parent.path("title").asText(), "topic:" + parent.path("id").asText())));
        rows.add(row(button("🏠 Бас мәзір", "home")));
        send(chatId, msg.toString(), rows);
    }

    private static void search(long chatId, String query) throws Exception {
        String term = query.toLowerCase(Locale.ROOT).strip();
        if (term.isEmpty()) return;
        List<JsonNode> hits = new ArrayList<>();
        for (JsonNode t : topics) for (JsonNode s : t.path("subs")) {
            StringBuilder searchable = new StringBuilder(t.path("title").asText()).append(' ')
                    .append(s.path("title").asText()).append(' ').append(s.path("text").asText());
            for (JsonNode item : s.path("items")) searchable.append(' ').append(item.asText());
            if (searchable.toString().toLowerCase(Locale.ROOT).contains(term)) hits.add(s);
        }
        if (hits.isEmpty()) {
            send(chatId, "Кешіріңіз, мұндай тақырып таппадым. 🤔 Бас мәзірден таңдап көріңіз.", rowSet(button("🏠 Бас мәзір", "home")));
            return;
        }
        ArrayNode rows = JSON.createArrayNode();
        for (int i = 0; i < Math.min(6, hits.size()); i++) {
            JsonNode s = hits.get(i);
            rows.add(row(button(s.path("title").asText(), "sub:" + s.path("id").asText())));
        }
        rows.add(row(button("🏠 Бас мәзір", "home")));
        send(chatId, "🔎 Табылған тақырыпшалар:", rows);
    }

    private static JsonNode findTopic(String id) {
        for (JsonNode t : topics) if (t.path("id").asText().equals(id)) return t;
        return null;
    }
    private static JsonNode findSub(String id) {
        for (JsonNode t : topics) for (JsonNode s : t.path("subs"))
            if (s.path("id").asText().equals(id)) return s;
        return null;
    }

    private static ObjectNode button(String label, String callback) {
        ObjectNode node = JSON.createObjectNode();
        node.put("text", label);
        node.put("callback_data", callback);
        return node;
    }
    private static ArrayNode row(ObjectNode button) {
        ArrayNode row = JSON.createArrayNode(); row.add(button); return row;
    }
    private static ArrayNode rowSet(ObjectNode button) {
        ArrayNode rows = JSON.createArrayNode(); rows.add(row(button)); return rows;
    }
    private static void appendLinks(ArrayNode rows, String id) {
        JsonNode list = links.path(id);
        if (!list.isArray()) return;
        for (JsonNode pair : list) {
            if (pair.size() < 2) continue;
            String url = pair.get(1).asText();
            if (!url.startsWith("https://")) continue;
            ObjectNode btn = JSON.createObjectNode();
            btn.put("text", "🔗 " + pair.get(0).asText());
            btn.put("url", url);
            rows.add(row(btn));
        }
    }
    private static void send(long chatId, String text, ArrayNode rows) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        params.put("chat_id", chatId);
        params.put("text", text);
        ObjectNode markup = JSON.createObjectNode();
        markup.set("inline_keyboard", rows);
        params.set("reply_markup", markup);
        JsonNode result = request("sendMessage", params);
        if (!result.path("ok").asBoolean()) System.err.println("Ошибка отправки: " + result);
    }
    private static JsonNode request(String method, ObjectNode params) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api + method))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(method.equals("getUpdates") ? 40 : 20))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(params), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IllegalStateException(method + ": HTTP " + response.statusCode() + " " + response.body());
        return JSON.readTree(response.body());
    }
}
