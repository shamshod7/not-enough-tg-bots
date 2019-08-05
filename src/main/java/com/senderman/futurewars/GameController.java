package com.senderman.futurewars;

import com.annimon.tgbotsmodule.api.methods.Methods;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.inlinequery.ChosenInlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.inputmessagecontent.InputTextMessageContent;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultArticle;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.*;

class GameController {

    private static Map<Long, Game> games;
    private final FutureWarsHandler handler;

    GameController(FutureWarsHandler handler) {
        this.handler = handler;
        games = new HashMap<>();
    }

    static void stopjoin(long chatId) {
        games.remove(chatId);
    }

    static void endgame(Game game) {
        game.getTurnTimer().shutdown();
        games.remove(game.getChatId());
    }

    void create(Message message) {
        final var chatId = message.getChatId();
        if (games.containsKey(chatId)) {
            handler.sendMessage(chatId, "Ushbu chatda o'yin allaqachon boshlangan, yuqoriroqqa qarang!");
            return;
        }

        var gameMsg = handler.sendMessage(chatId, "🌇O'yin boshlandi! Guruh yaratish uchun /newteam buyurug'ini yuboring!");
        var timer = new JoinTimer(chatId, handler);
        games.put(chatId, new Game(gameMsg.getChatId(), gameMsg.getMessageId(), timer, handler));
    }

    void newteam(Message message) {
        final var chatId = message.getChatId();
        if (!games.containsKey(chatId)) {
            handler.sendMessage(chatId, "O'yin allaqachon boshlangan!");
            return;
        }

        var game = games.get(chatId);

        if (game.getTeams().size() == 5) {
            handler.sendMessage(chatId, "Guruhlar ko'payib ketdi! Faqatgina mavjudlariga qo'shilishingiz mumkin!");
            return;
        }

        if (game.isStarted()) {
            handler.sendMessage(chatId, "O'yin allaqachon bo'lyabd!");
            return;
        }

        var userId = message.getFrom().getId();

        if (isInGame(userId)) {
            handler.sendMessage(Methods.sendMessage(chatId, "Siz allaqachon qayergadir qo'shilgansiz!")
                    .setReplyToMessageId(message.getMessageId()));
            return;
        }

        try {
            handler.execute(new SendMessage((long) userId, "Siz o'yinga omadli qo'shildingiz!"));
            var teamId = game.getTeams().keySet().size(); // increase teamId if exists
            while (game.getTeams().containsKey(teamId))
                teamId++;
            var player = new Player(userId, message.getFrom().getFirstName(), teamId);
            game.getPlayers().put(userId, player);
            Set<Player> playersSet = new HashSet<>();
            playersSet.add(player);
            game.getTeams().put(teamId, playersSet);
            handler.sendMessage(chatId, player.name + " o'yinga qo'shildi!");

            Methods.editMessageText()
                    .setChatId(chatId)
                    .setText(getTextForJoin(game))
                    .setMessageId(game.getMessageId())
                    .setReplyMarkup(getMarkupForJoin(game))
                    .setParseMode(ParseMode.HTML)
                    .call(handler);
        } catch (TelegramApiException e) {
            handler.sendMessage(Methods.sendMessage(chatId, "Oldin bot lichkasiga nimadir deb yozing!")
                    .setReplyToMessageId(message.getMessageId()));
        }
    }

    void begin(Message message) {
        final var chatId = message.getChatId();
        if (!games.containsKey(chatId)) {
            handler.sendMessage(chatId, "Ushbu chatda o'yin holi boshlanmagan! O'yinni boshlash uchun - /create");
            return;
        }

        var game = games.get(chatId);

        if (game.isStarted()) {
            handler.sendMessage(chatId, "Ushbu chatda o'yin allaqachon boshlangan!");
            return;
        }
        if (game.getTeams().size() < 2) {
            handler.sendMessage(chatId, "Guruhlar yetarli emas!");
            return;
        }

        game.start();
        game.getJoinTimer().stop(false);

        var text = new StringBuilder();
        for (int teamId : game.getTeams().keySet()) {
            text.append(String.format("<b>Guruh %1$d:</b>\n", teamId));

            for (Player player : game.getTeams().get(teamId)) {
                text.append("- ").append(player.name).append("\n");
            }
            text.append("\n");
        }
        Methods.editMessageText()
                .setChatId(game.getChatId())
                .setMessageId(game.getMessageId())
                .setText(text.toString())
                .setReplyMarkup(null)
                .setParseMode(ParseMode.HTML)
                .call(handler);

        handler.sendMessage(chatId, "<b>🏙O'yin boshlandi!</b>\n" +
                "📈Hisobotni lichkaga jo'natishni O'chirish/Yoqish - /pmreports (Yoqilgan odatda)\n" +
                "📉Hisobotni chatga jo'natish O'chirish/Yoqish - /chatreports (Yoqilgan odatda)");
    }

    void jointeam(CallbackQuery query) {
        var chatId = query.getMessage().getChatId();

        if (!games.containsKey(chatId))
            return;

        var game = games.get(chatId);
        var team = Integer.parseInt(query.getData().split(" ")[1]);

        if (!game.getTeams().containsKey(team))
            return;

        var userId = query.getFrom().getId();

        if (isInGame(userId)) {
            Methods.answerCallbackQuery()
                    .setShowAlert(true)
                    .setText("Siz allaqachon qayergadir qo'shilgansiz!")
                    .setCallbackQueryId(query.getId())
                    .call(handler);
            return;
        }

        if (game.getPlayers().size() == 25) {
            Methods.answerCallbackQuery()
                    .setShowAlert(true)
                    .setText("O'yinchilar ko'payib ketdi! (Maks. 25)")
                    .setCallbackQueryId(query.getId())
                    .call(handler);
            return;
        }

        var userName = query.getFrom().getFirstName();
        var player = new Player(userId, userName, team);

        try {
            handler.execute(new SendMessage((long) userId, "Siz o'yinga omadli qo'shildingiz!"));
            game.getPlayers().put(userId, player);
            game.getTeams().get(team).add(player);
            Methods.answerCallbackQuery()
                    .setShowAlert(false)
                    .setText("Siz o'yinga omadli qo'shildingiz!")
                    .setCallbackQueryId(query.getId())
                    .call(handler);
            handler.sendMessage(chatId, player.name + " o'yinga qo'shildi!");
            Methods.editMessageText()
                    .setChatId(chatId)
                    .setText(getTextForJoin(game))
                    .setMessageId(game.getMessageId())
                    .setParseMode(ParseMode.HTML)
                    .setReplyMarkup(getMarkupForJoin(game))
                    .call(handler);

        } catch (TelegramApiException e) {
            Methods.answerCallbackQuery()
                    .setShowAlert(true)
                    .setText("Oldin bot lichkasiga nimadir deb yozing!")
                    .setCallbackQueryId(query.getId())
                    .call(handler);
        }

    }

    void escape(Message message) {
        final var chatId = message.getChatId();
        if (!games.containsKey(chatId)) {
            handler.sendMessage(chatId, "Ushbu chatda o'yin boshlanmagan!");
            return;
        }

        var game = games.get(chatId);

        if (game.isStarted()) {
            handler.sendMessage(chatId, "Boshlangan o'yindan chiqib bo'lmaydi!");
            return;
        }

        var player = game.getPlayers().remove(message.getFrom().getId());
        if (player == null) {
            handler.sendMessage(chatId, "Siz jadvalda yo'qsiz!");
            return;
        }
        var team = game.getTeams().get(player.team);
        team.remove(player);
        if (team.size() == 0)
            game.getTeams().remove(player.team);


        handler.sendMessage(chatId, player.name + " qochib ketdi!");
        Methods.editMessageText()
                .setChatId(chatId)
                .setText(getTextForJoin(game))
                .setMessageId(game.getMessageId())
                .setParseMode(ParseMode.HTML)
                .setReplyMarkup(getMarkupForJoin(game))
                .call(handler);

    }

    void attack(CallbackQuery query) {

        var params = query.getData().split(" ");
        var chatId = Long.parseLong(params[2]);

        if (!games.containsKey(chatId))
            return;

        var game = games.get(chatId);
        var targetId = Integer.parseInt(params[1]);
        game.setTarget(query.getFrom().getId(), targetId);
        game.doAction(query.getFrom().getId(), Player.ACTION.ATTACK);
    }

    void showTargets(CallbackQuery query) {
        var chatId = Long.parseLong(query.getData().split(" ")[1]);

        if (!games.containsKey(chatId))
            return;

        var game = games.get(chatId);
        game.showTargets(query.getFrom().getId());
    }

    void simpleAction(CallbackQuery query, Player.ACTION action, boolean isFinalAction) {
        var chatId = Long.parseLong(query.getData().split(" ")[1]);

        if (!games.containsKey(chatId))
            return;

        var game = games.get(chatId);

        if (isFinalAction) {
            game.doAction(query.getFrom().getId(), action);
        } else {
            if (action == Player.ACTION.DEFENCE || action == Player.ACTION.DEF_FLAG)
                game.setupShield(query.getFrom().getId(), action);
        }
    }

    void updateShield(CallbackQuery query) {
        var params = query.getData().split(" ");

        var chatId = Long.parseLong(params[2]);
        if (!games.containsKey(chatId))
            return;

        var game = games.get(chatId);
        var player = game.getPlayers().get(query.getFrom().getId());
        var defence = Integer.parseInt(params[1]);
        if (defence > player.shield)
            defence = player.shield;
        player.currentShield += defence;
        if (player.currentShield > 5)
            player.currentShield = 5;
        else if (player.currentShield < 0)
            player.currentShield = 0;
        game.setupShield(query.getFrom().getId(), player.action);
    }

    void showMainMenu(CallbackQuery query) {
        var chatId = Long.parseLong(query.getData().split(" ")[1]);

        if (!games.containsKey(chatId))
            return;

        var game = games.get(chatId);
        game.sendMainMenu(query.getFrom().getId());
    }

    void handleTeamMessage(InlineQuery query) {
        Game game = null;
        for (Game g : games.values()) {
            if (g.getPlayers().containsKey(query.getFrom().getId())) {
                game = g;
                break;
            }
        }
        if (game == null) {
            var content = new InputTextMessageContent()
                    .setMessageText("Men o'yin o'ynamasdan nimadir demoqchi bo'ldim!");
            var result = new InlineQueryResultArticle()
                    .setId("deny_team_message")
                    .setTitle("Siz o'yinda emassiz!")
                    .setInputMessageContent(content);
            Methods.answerInlineQuery()
                    .setInlineQueryId(query.getId())
                    .setResults(result)
                    .call(handler);
            return;
        }
        var content = new InputTextMessageContent()
                .setMessageText("Men o'z guruhimga nimadir dedim!");
        var result = new InlineQueryResultArticle()
                .setId("send_to_team")
                .setTitle("Guruhga yuborish")
                .setDescription(query.getQuery())
                .setInputMessageContent(content);
        Methods.answerInlineQuery()
                .setInlineQueryId(query.getId())
                .setResults(result)
                .call(handler);
    }

    void sendTeamMessage(ChosenInlineQuery query) {
        Game game = null;
        for (Game g : games.values()) {
            if (g.getPlayers().containsKey(query.getFrom().getId())) {
                game = g;
                break;
            }
        }
        if (game == null)
            return;

        int team = game.getPlayers().get(query.getFrom().getId()).team;
        for (Player teammate : game.getTeams().get(team)) {
            if (teammate.id > 0) {
                handler.sendMessage(teammate.id, "✉️" + query.getFrom().getFirstName() + ": " + query.getQuery());
            }
        }
    }

    void pmReports(Message message) {
        var chatId = message.getChatId();
        if (!games.containsKey(chatId))
            return;

        var game = games.get(chatId);
        var playerId = message.getFrom().getId();
        if (!game.getPlayers().containsKey(playerId))
            return;

        var player = game.getPlayers().get(playerId);
        player.pmReports = !player.pmReports;
        if (player.pmReports)
            handler.sendMessage(chatId, "Lichkaga hisobot " + player.name + " uchun yoqildi!");
        else
            handler.sendMessage(chatId, "Lichkaga hisobot " + player.name + " uchun o'chirildi!");
    }

    void chatReports(Message message) {
        var chatId = message.getChatId();
        if (!games.containsKey(chatId))
            return;

        var game = games.get(chatId);
        game.chatReports = !game.chatReports;
        if (game.chatReports)
            handler.sendMessage(chatId, "Guruhga hisobot yuborilishi yoqildi!");
        else
            handler.sendMessage(chatId, "Guruhga hisobot yuborilishi o'chirildi!");
    }

    private boolean isInGame(int playerId) {
        for (Game game : games.values()) {
            if (game.getPlayers().containsKey(playerId))
                return true;
        }
        return false;
    }

    private String getTextForJoin(Game game) {
        var text = new StringBuilder();
        if (game.getTeams().size() < 2)
            text.append("O'yin boshlandi! Guruh yaratish uchun /newteam buyurug'ini yuboring!\n\n");
        else
            text.append("O'yin boshlandi! Guruh yaratish yoki mavjudiga qo'shilish uchun /newteam buyurug'ini yuboring!\n\n");

        for (int teamId : game.getTeams().keySet()) {
            text.append(String.format("<b>Guruh %1$d:</b>\n", teamId));

            for (Player player : game.getTeams().get(teamId)) {
                text.append("- ").append(player.name).append("\n");
            }
            text.append("\n");
        }

        return text.toString();
    }

    private InlineKeyboardMarkup getMarkupForJoin(Game game) {
        if (game.getTeams().size() < 2) {
            return null;
        }

        var markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();
        for (int team : game.getTeams().keySet()) {
            var row = List.of(new InlineKeyboardButton()
                    .setText("📝" + team + "- Guruhga qo'shilish!")
                    .setCallbackData(FutureWarsBot.CALLBACK_JOIN_TEAM + team));
            buttons.add(row);
        }
        markup.setKeyboard(buttons);
        return markup;
    }
}
