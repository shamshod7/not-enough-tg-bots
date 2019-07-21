package com.senderman.lastkatkabot;

import com.annimon.tgbotsmodule.BotHandler;
import com.annimon.tgbotsmodule.api.methods.Methods;
import com.annimon.tgbotsmodule.api.methods.send.SendMessageMethod;
import com.senderman.lastkatkabot.Handlers.*;
import com.senderman.lastkatkabot.TempObjects.BullsAndCowsGame;
import com.senderman.lastkatkabot.TempObjects.VeganTimer;
import org.jetbrains.annotations.NotNull;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.util.*;

public class LastkatkaBotHandler extends BotHandler {

    public final Set<Integer> admins;
    public final Set<Integer> blacklist;
    public final Set<Integer> premiumUsers;
    public final Set<Long> allowedChats;
    public final Map<Long, VeganTimer> veganTimers;
    public final Map<Long, BullsAndCowsGame> bullsAndCowsGames;
    private final AdminHandler adminHandler;
    private final UsercommandsHandler usercommandsHandler;
    private final DuelController duelController;
    private CallbackHandler callbackHandler;

    LastkatkaBotHandler() {

        var mainAdmin = Services.botConfig().getMainAdmin();
        sendMessage(mainAdmin, "Initialization...");

        // settings
        Services.setHandler(this);
        Services.setDBService(new MongoDBService());
        Services.db().cleanup();

        admins = Services.db().getTgUsersIds(DBService.COLLECTION_TYPE.ADMINS);
        premiumUsers = Services.db().getTgUsersIds(DBService.COLLECTION_TYPE.PREMIUM);
        blacklist = Services.db().getTgUsersIds(DBService.COLLECTION_TYPE.BLACKLIST);

        allowedChats = Services.db().getAllowedChatsSet();
        allowedChats.add(Services.botConfig().getLastvegan());
        allowedChats.add(Services.botConfig().getTourgroup());

        adminHandler = new AdminHandler(this);
        usercommandsHandler = new UsercommandsHandler(this);
        callbackHandler = new CallbackHandler(this);
        duelController = new DuelController(this);
        veganTimers = new HashMap<>();
        bullsAndCowsGames = Services.db().getBnCGames();

        sendMessage(mainAdmin, "Бот готов к работе!");
    }

    @Override
    public BotApiMethod onUpdate(@NotNull Update update) {

        // first we will handle callbacks
        if (update.hasCallbackQuery()) {
            processCallbackQuery(update.getCallbackQuery());
            return null;
        }

        if (!update.hasMessage())
            return null;

        final var message = update.getMessage();

        // don't handle old messages
        if (message.getDate() + 120 < System.currentTimeMillis() / 1000)
            return null;

        var newMembers = message.getNewChatMembers();

        if (newMembers != null && newMembers.size() != 0) {
            processNewMembers(message);
            return null;
        }

        final var chatId = message.getChatId();

        if (message.getMigrateFromChatId() != null && allowedChats.contains(message.getMigrateFromChatId())) {
            migrateChat(message.getMigrateFromChatId(), chatId);
            sendMessage(message.getMigrateFromChatId(), "Id чата обновлен!");
        }

        if (!allowedChats.contains(chatId) && !message.isUserMessage()) // do not respond in not allowed chats
            return null;

        if (message.getMigrateFromChatId() != null) {
            migrateChat(message.getMigrateFromChatId(), chatId);
        }

        if (message.getLeftChatMember() != null && !message.getLeftChatMember().getUserName().equals(getBotUsername())) {
            Methods.sendDocument()
                    .setChatId(chatId)
                    .setFile(Services.botConfig().getLeavesticker())
                    .setReplyToMessageId(message.getMessageId())
                    .call(this);
            Services.db().removeUserFromChatDB(message.getLeftChatMember().getId(), chatId);
        }

        if (!message.hasText())
            return null;

        if (message.isGroupMessage() || message.isSuperGroupMessage()) // add user to DB
            Services.db().addUserToChatDB(message);

        var text = message.getText();

        // for bulls and cows
        if (text.matches("\\d{4,10}") && bullsAndCowsGames.containsKey(chatId) && isNotInBlacklist(message)) {
            bullsAndCowsGames.get(chatId).check(message);
            return null;
        }

        if (!message.isCommand())
            return null;

        if (!message.isUserMessage()) { // handle other's bots commands
            if (processVeganCommands(message))
                return null;
        }

        /* bot should only trigger on general commands (like /command) or on commands for this bot (/command@mybot),
         * and NOT on commands for another bots (like /command@notmybot)
         */

        final var command = text.split("\\s+", 2)[0].toLowerCase(Locale.ENGLISH).replace("@" + getBotUsername(), "");

        if (command.contains("@"))
            return null;

        if (isNotInBlacklist(message) && processUserCommand(message, command))
            return null;

        // commands for main admin only
        if (message.getFrom().getId().equals(Services.botConfig().getMainAdmin()) && processMainAdminCommand(message, command))
            return null;

        // commands for all admins
        if (isFromAdmin(message) && processAdminCommand(message, command))
            return null;

        // commands for tournament
        if (TournamentHandler.isEnabled && isFromAdmin(message)) {
            switch (command) {
                case "/score":
                    TournamentHandler.score(message, this);
                    return null;
                case "/win":
                    TournamentHandler.win(message, this);
                    return null;
                case "/rt":
                    TournamentHandler.rt(this);
            }
        }
        return null;
    }

    @Override
    public String getBotUsername() {
        return Services.botConfig().getUsername().split(" ")[Services.botConfig().getPosition()];
    }

    @Override
    public String getBotToken() {
        return Services.botConfig().getToken().split(" ")[Services.botConfig().getPosition()];
    }

    private void processCallbackQuery(CallbackQuery query) {
        String data = query.getData();

        if (data.startsWith(LastkatkaBot.CALLBACK_CAKE_OK)) {
            callbackHandler.cake(query, CallbackHandler.CAKE_ACTIONS.CAKE_OK);
        } else if (data.startsWith(LastkatkaBot.CALLBACK_CAKE_NOT)) {
            callbackHandler.cake(query, CallbackHandler.CAKE_ACTIONS.CAKE_NOT);
        } else if (data.startsWith(LastkatkaBot.CALLBACK_ALLOW_CHAT)) {
            callbackHandler.addChat(query);
        } else if (data.startsWith(LastkatkaBot.CALLBACK_DONT_ALLOW_CHAT)) {
            callbackHandler.denyChat(query);
        } else if (data.startsWith(LastkatkaBot.CALLBACK_DELETE_CHAT)) {
            callbackHandler.deleteChat(query);
            adminHandler.chats(query.getMessage());
        } else if (data.startsWith("deleteuser_")) {
            callbackHandler.deleteUser(query);
        } else {
            switch (data) {
                case LastkatkaBot.CALLBACK_REGISTER_IN_TOURNAMENT:
                    callbackHandler.registerInTournament(query);
                    return;
                case LastkatkaBot.CALLBACK_PAY_RESPECTS:
                    callbackHandler.payRespects(query);
                    return;
                case LastkatkaBot.CALLBACK_CLOSE_MENU:
                    callbackHandler.closeMenu(query);
                    return;
                case LastkatkaBot.CALLBACK_JOIN_DUEL:
                    duelController.joinDuel(query);
                    return;
                case LastkatkaBot.CALLBACK_VOTE_BNC:
                    bullsAndCowsGames.get(query.getMessage().getChatId()).addVote(query);
            }
        }
    }

    private void processNewMembers(Message message) {
        var chatId = message.getChatId();
        var newMembers = message.getNewChatMembers();

        if (chatId == Services.botConfig().getTourgroup()) { // restrict any user who isn't in tournament
            for (User user : newMembers) {
                if (TournamentHandler.membersIds == null || !TournamentHandler.membersIds.contains(user.getId())) {
                    Methods.Administration.restrictChatMember()
                            .setChatId(Services.botConfig().getTourgroup())
                            .setUserId(user.getId())
                            .setCanSendMessages(false).call(this);
                }
            }

        } else if (!newMembers.get(0).getBot()) {
            Methods.sendDocument(chatId)
                    .setFile(Services.botConfig().getHigif())
                    .setReplyToMessageId(message.getMessageId())
                    .call(this); // say hi to new member

        } else if (newMembers.get(0).getUserName().equals(getBotUsername())) {
            if (allowedChats.contains(chatId)) {// Say hello to new group if chat is allowed
                sendMessage(chatId, "Этот чат находится в списке разрешенных. Бот готов к работе здесь");
                return;
            }

            sendMessage(chatId, "Чата нет в списке разрешенных. Дождитесь решения разработчика");
            var row1 = List.of(new InlineKeyboardButton()
                    .setText("Добавить")
                    .setCallbackData(LastkatkaBot.CALLBACK_ALLOW_CHAT + chatId));
            var row2 = List.of(new InlineKeyboardButton()
                    .setText("Отклонить")
                    .setCallbackData(LastkatkaBot.CALLBACK_DONT_ALLOW_CHAT + chatId));
            var markup = new InlineKeyboardMarkup();
            markup.setKeyboard(List.of(row1, row2));
            sendMessage(Methods.sendMessage((long) Services.botConfig().getMainAdmin(),
                    String.format("Добавить чат %1$s (%2$d) в список разрешенных? - %3$s",
                            message.getChat().getTitle(), chatId, message.getFrom().getFirstName()))
                    .setReplyMarkup(markup));
        }
    }

    private boolean processVeganCommands(Message message) {
        var chatId = message.getChatId();
        var text = message.getText();

        if (Services.botConfig().getVeganWarsCommands().contains(text) && !veganTimers.containsKey(chatId)) { // start veganwars timer
            veganTimers.put(chatId, new VeganTimer(chatId));
            return true;

        } else if (text.startsWith("/join") && veganTimers.containsKey(chatId)) {
            veganTimers.get(chatId).addPlayer(message.getFrom().getId(), message);
            return true;

        } else if (text.startsWith("/flee") && veganTimers.containsKey(chatId)) {
            veganTimers.get(chatId).removePlayer(message.getFrom().getId());
            return true;

        } else if (text.startsWith("/fight") && veganTimers.containsKey(chatId)) {
            if (veganTimers.get(chatId).getVegansAmount() > 1) {
                veganTimers.get(chatId).stop();
            }
            return true;
        }
        return false;
    }

    private boolean processUserCommand(Message message, String command) {
        var chatId = message.getChatId();

        switch (command) {
            case "/pinlist":
                usercommandsHandler.pinlist(message);
                return true;
            case "/pair":
                usercommandsHandler.pair(message);
                return true;
            case "/lastpairs":
                usercommandsHandler.lastpairs(message);
                return true;
            case "/action":
                usercommandsHandler.action(message);
                return true;
            case "/f":
                usercommandsHandler.payRespects(message);
                return true;
            case "/dice":
                usercommandsHandler.dice(message);
                return true;
            case "/cake":
                usercommandsHandler.cake(message);
                return true;
            case "/duel":
                duelController.createNewDuel(message);
                return true;
            case "/stats":
                usercommandsHandler.dstats(message);
                return true;
            case "/weather":
                usercommandsHandler.weather(message);
                return true;
            case "/top":
                usercommandsHandler.bncTop(message);
                return true;
            case "/bnc":
                if (!bullsAndCowsGames.containsKey(chatId))
                    bullsAndCowsGames.put(chatId, new BullsAndCowsGame(message));
                else
                    sendMessage(chatId, "В этом чате игра уже идет!");
                return true;
            case "/bncinfo":
                if (bullsAndCowsGames.containsKey(chatId))
                    bullsAndCowsGames.get(chatId).sendGameInfo(message);
                return true;
            case "/bncstop":
                if (bullsAndCowsGames.containsKey(chatId))
                    bullsAndCowsGames.get(chatId).createPoll(message);
                return true;
            case "/bncruin":
                if (bullsAndCowsGames.containsKey(chatId))
                    bullsAndCowsGames.get(chatId).changeAntiRuin();
                return true;
            case "/bnchelp":
                usercommandsHandler.bnchelp(message);
                return true;
            case "/reset":
                if (veganTimers.containsKey(chatId)) {
                    veganTimers.get(chatId).stop();
                    sendMessage(chatId, "Список игроков сброшен!");
                }
                return true;
            case "/feedback":
                usercommandsHandler.feedback(message);
                return true;
            case "/help":
                usercommandsHandler.help(message);
                return true;
            case "/getinfo":
                usercommandsHandler.getinfo(message);
                return true;
            case "/regtest":
                usercommandsHandler.testRegex(message);
                return true;
        }
        return false;
    }

    private boolean processMainAdminCommand(Message message, String command) {
        switch (command) {
            case "/owner":
                adminHandler.addOwner(message);
                return true;
            case "/addpremium":
                adminHandler.addPremium(message);
                return true;
            case "/update":
                adminHandler.update(message);
                return true;
            case "/announce":
                adminHandler.announce(message);
                return true;
            case "/chats":
                adminHandler.chats(message);
                return true;
            case "/cc":
                adminHandler.cleanChats(message);
                return true;
        }
        return false;
    }

    private boolean processAdminCommand(Message message, String command) {
        switch (command) {
            case "/badneko":
                adminHandler.badneko(message);
                return true;
            case "/goodneko":
                adminHandler.goodneko(message);
                return true;
            case "/nekos":
                adminHandler.listUsers(message, DBService.COLLECTION_TYPE.BLACKLIST);
                return true;
            case "/owners":
                adminHandler.listUsers(message, DBService.COLLECTION_TYPE.ADMINS);
                return true;
            case "/prem":
                adminHandler.listUsers(message, DBService.COLLECTION_TYPE.PREMIUM);
                return true;
            case "/critical":
                duelController.critical(message);
                return true;
            case "/setup":
                TournamentHandler.setup(message, this);
                return true;
            case "/go":
                TournamentHandler.startTournament(this);
                return true;
            case "/ct":
                TournamentHandler.cancelSetup(this);
                return true;
            case "/tourhelp":
                adminHandler.setupHelp(message);
                return true;
            case "/tourmessage":
                TournamentHandler.tourmessage(this, message);
                return true;
        }
        return false;
    }

    private boolean isFromAdmin(Message message) {
        return admins.contains(message.getFrom().getId());
    }

    // TODO uncomment when needed
    /*private boolean isPremiumUser(Message message) {
        return premiumUsers.contains(message.getFrom().getId());
    }*/

    private boolean isNotInBlacklist(Message message) {
        var result = blacklist.contains(message.getFrom().getId());
        if (result) {
            Methods.deleteMessage(message.getChatId(), message.getMessageId()).call(this);
        }
        return !result;
    }

    private void migrateChat(long oldChatId, long newChatId) {
        allowedChats.remove(oldChatId);
        allowedChats.add(newChatId);
        Services.db().updateChatId(oldChatId, newChatId);
    }

    private boolean isAbleToMigrateChat(long oldChatId, TelegramApiException e) {
        if (!(e instanceof TelegramApiRequestException))
            return false;

        var ex = (TelegramApiRequestException) e;
        if (ex.getParameters() == null)
            return false;
        if (ex.getParameters().getMigrateToChatId() == null)
            return false;

        migrateChat(oldChatId, ex.getParameters().getMigrateToChatId());
        return true;
    }

    public Message sendMessage(long chatId, String text) {
        return sendMessage(Methods.sendMessage(chatId, text));
    }

    public Message sendMessage(SendMessageMethod sm) {
        var sendMessage = new SendMessage(sm.getChatId(), sm.getText())
                .enableHtml(true)
                .disableWebPagePreview()
                .setReplyMarkup(sm.getReplyMarkup())
                .setReplyToMessageId(sm.getReplyToMessageId());
        try {
            return execute(sendMessage);
        } catch (TelegramApiException e) {
            if (!isAbleToMigrateChat(Long.parseLong(sm.getChatId()), e))
                return null;
            sm.setChatId(((TelegramApiRequestException) e).getParameters().getMigrateToChatId());
            return sm.call(this);
        }
    }
}
