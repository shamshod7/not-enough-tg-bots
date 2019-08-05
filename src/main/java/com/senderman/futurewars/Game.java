package com.senderman.futurewars;

import com.annimon.tgbotsmodule.api.methods.Methods;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.*;
import java.util.concurrent.*;

class Game {
    private final FutureWarsHandler handler;
    private final long chatId;
    private final int messageId;
    private final JoinTimer joinTimer;
    private final ScheduledExecutorService scheduler;
    boolean chatReports = true;
    private int turnCounter = 1;
    private final Map<Integer, Set<Player>> teams;
    private boolean isStarted = false;
    private ScheduledFuture scheduledFuture;
    private final Map<Integer, Player> players;

    Game(long chatId, int messageId, JoinTimer joinTimer, FutureWarsHandler handler) {
        this.handler = handler;
        this.chatId = chatId;
        this.messageId = messageId;
        this.joinTimer = joinTimer;
        teams = new HashMap<>();
        players = new HashMap<>();
        scheduler = Executors.newScheduledThreadPool(1);
    }

    void start() {
        for (int team : teams.keySet()) { // create flags for all teams
            var flag = new TeamFlag(team);
            players.put(flag.id, flag);
        }

        teams.put(228, new HashSet<>()); // summon CoinMonsters
        for (int i = 0; i < 5; i++) {
            int monsterId;
            do {
                monsterId = ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE);
            } while (players.containsKey(monsterId));
            var monster = new CoinMonster(monsterId);
            players.put(monsterId, monster);
            teams.get(228).add(monster);
        }
        isStarted = true;
        for (Player player : players.values()) {
            if (player.id > 0 && player.team != 228)
                sendMainMenu(player.id);
        }
        scheduledFuture = scheduler.schedule(this::makeTurn, 1, TimeUnit.MINUTES);
    }

    private void makeTurn() {
        scheduledFuture.cancel(true);
        var result = new StringBuilder("🗓Yurish " + turnCounter + ":\n\n");
        var endOfResult = new StringBuilder("\n📊 " + turnCounter + " - yurish natijalari: " + "\n\n");
        turnCounter++;

        for (int team : teams.keySet()) { // handle players actions

            if (!teamIsAlive(team))
                continue;

            result.append("<b>Guruh ").append(team).append("</b>:\n");
            for (Player player : teams.get(team)) {

                if (player.isDead)
                    continue;

                if (!player.isReady) { // AFK
                    player.afkTurns++;
                    Methods.editMessageText()
                            .setChatId(player.id)
                            .setMessageId(player.message.getMessageId())
                            .setText("Vaqt tugadi!")
                            .setReplyMarkup(null)
                            .call(handler);
                    result.append(String.format("\uD83D\uDE34 %1$s yurishni o'tkazib yubormoqda\n", player.name));
                    continue;
                }

                switch (player.action) {
                    case ATTACK:
                        result.append(String.format("\uD83D\uDD34 %1$s %2$sga otayabdi!\n",
                                player.name, player.target.name));
                        break;
                    case DEFENCE:
                        result.append(String.format("\uD83D\uDD35 %1$s qalqonni %2$dga quvvatlayabdi!\n",
                                player.name, player.currentShield));
                        break;
                    case DEF_FLAG:
                        result.append(String.format("\uD83D\uDEE1 %1$s bayroqning %2$d zаrarini himoyaladi!\n",
                                player.name, player.currentShield));
                        break;
                    case CHARGE_LASER:
                        result.append(String.format("\uD83D\uDD0B %1$s lazer energiyasini 3 birlikka quvvatladi!\n",
                                player.name));
                        break;
                    case CHARGE_SHIELD:
                        result.append(String.format("\uD83D\uDD0B %1$s qalqon energiyasini 3 birlikka quvvatladi!\n",
                                player.name));
                        break;
                    case ROLL:
                        result.append(String.format("\uD83D\uDC40 %1$s chetlashmoqda!\n",
                                player.name));
                        break;
                    case SUMMON_CLONE:
                        var clone = new PlayerClone(player);
                        player.clone = clone;
                        player.usedClone = true;
                        teams.get(player.team).add(clone);
                        players.put(clone.id, clone);
                        result.append(String.format("\uD83D\uDE08 %1$s klonni chiqarayabdi!\n",
                                player.name));
                        break;
                }
            }
        }

        for (int team : teams.keySet()) { // handle results

            if (!teamIsAlive(team))
                continue;

            endOfResult.append("<b>Guruh ").append(team).append("</b>:\n");

            for (Player player : teams.get(team)) {

                if (player.isDead)
                    continue;

                if (player.dmgTaken > 0) {

                    if (player.action != Player.ACTION.DEFENCE) {
                        if (player.action != Player.ACTION.ROLL) {
                            player.hp -= player.dmgTaken;
                            endOfResult.append(String.format("\uD83D\uDC94 %1$s %2$d jon yo'qotmoqda. Unda %3$d jon qoldi!\n",
                                    player.name, player.dmgTaken, player.hp));
                        } else {
                            var chance = ThreadLocalRandom.current().nextInt(100);
                            if (chance > 60) { // chance to avoid attack is 60%
                                player.hp -= player.dmgTaken;
                                endOfResult.append(String.format("\uD83D\uDC94 %1$s %2$d jon yo'qotmoqda. Unda %3$d jon qoldi!\n",
                                        player.name, player.dmgTaken, player.hp));
                            }
                        }
                    } else {
                        int gotEnergy;
                        if (player.dmgTaken <= player.currentShield) {
                            gotEnergy = 2;
                            endOfResult.append(String.format("\uD83D\uDC99 %1$s yetkazilgan barcha zarbani qaytardi!", player.name));
                        } else {
                            gotEnergy = 1;
                            var hpLost = player.dmgTaken - player.currentShield;
                            player.hp -= hpLost;
                            endOfResult.append(String.format("\uD83D\uDC94 %1$s %2$d zarbani qaytardi va %3$d jon yo'qotdi. Unda %4$d jon qoldi!",
                                    player.name, player.currentShield, hpLost, player.hp));
                        }
                        player.laser += gotEnergy;
                        endOfResult.append(String.format(" shuningdek %1$d lazer energiyasini ham tiklab oldi!\n", gotEnergy));
                    }

                    if (player.hp <= 0) {
                        player.isDead = true;
                        int lootForEveryOne = player.coins / player.attackers.size() + 1;
                        for (Player killer : player.attackers) {
                            killer.coins += lootForEveryOne;
                        }
                        endOfResult.append(String.format("\uD83D\uDC80 %1$s o'layabdi\n", player.name));
                    }
                }

                if (player.afkTurns == 2 && !player.isDead) { // death from AFK (and if he is still alive :)
                    player.isDead = true;
                    endOfResult.append(String.format("\uD83D\uDC80 %1$s AFK tufayli o'lmoqda\n", player.name));
                }

                if (player.clone != null && player.clone.turnsLeft == 0) { // clone's timeout death
                    player.clone.isDead = true;
                    endOfResult.append(String.format("\uD83D\uDC80 Klon %1$s o'lmoqda\n", player.clone.name));
                    player.clone = null;
                    continue;
                }

                if (player.clone != null && player.isDead) { // clone cannot survive without owner
                    player.clone.isDead = true;
                    endOfResult.append(String.format("\uD83D\uDC80 Klon %1$s xo'jayinisiz o'lmoqda\n", player.clone.name));
                    continue;
                }

                player.prepareForNextTurn();

            }

            var flag = players.get(team * -1);
            if (flag != null) { // monsters does not have flag
                if (flag.dmgTaken > 0) {
                    flag.hp -= flag.dmgTaken;
                    endOfResult.append(String.format("\uD83D\uDC94 %1$s %2$d jon yo'qotayabdi. Unda %3$d jon qoldi!\n",
                            flag.name, flag.dmgTaken, flag.hp));
                }
                if (flag.hp <= 0) {
                    int lootForEveryOne = flag.coins / flag.attackers.size() + 3;
                    for (Player killer : flag.attackers) {
                        killer.coins += lootForEveryOne;
                    }
                    for (Player player : teams.get(team)) {
                        player.isDead = true;
                    }
                    endOfResult.append(String.format("\uD83D\uDC80 Guruh %1$d mag'lubiyatga uchradi!\n", team));
                }
                flag.prepareForNextTurn();
            }
        }

        teams.get(228).removeIf(monster -> monster.isDead); // free memory from dead monsters
        players.values().removeIf(monster -> monster.isDead);

        int aliveTeams = 0;
        int winner = -1;
        for (int team : teams.keySet()) {
            if (team != 228 && teamIsAlive(team)) {
                aliveTeams++;
                winner = team;
            }
        }

        result.append(endOfResult);
        if (chatReports)
            handler.sendMessage(chatId, result.toString());
        for (Player player : players.values()) {
            if (player.pmReports)
                handler.sendMessage(player.id, result.toString());
        }

        if (aliveTeams == 1) {
            var winText = new StringBuilder();
            winText.append("\uD83D\uDC51 Guruh ")
                    .append(winner)
                    .append(" g'alaba qildi! Tirik qolganlar:\n");
            for (Player player : teams.get(winner)) { // TODO stats for mongodb
                if (!player.isDead && player.id > 0)
                    winText.append("- ").append(player.name).append("\n");
            }

            handler.sendMessage(chatId, winText.toString());
            GameController.endgame(this);
        } else if (aliveTeams == 0) {
            handler.sendMessage(chatId, "\uD83D\uDC80 Barcha mag'lubiyatga uchradi!");
            GameController.endgame(this);
        } else {
            if (turnCounter % 5 == 0) { // summon CoinMonsters
                while (teams.get(228).size() < 5) {
                    int monsterId;
                    do {
                        monsterId = ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE);
                    } while (players.containsKey(monsterId));
                    var monster = new CoinMonster(monsterId);
                    players.put(monsterId, monster);
                    teams.get(228).add(monster);
                }
            }
            scheduledFuture = scheduler.schedule(this::makeTurn, 1, TimeUnit.MINUTES); // next turn
            for (Player player : players.values()) {
                if (!player.isDead && player.id > 0 && player.team != 228)
                    sendMainMenu(player.id);
            }
        }

    }

    void doAction(int playerId, Player.ACTION action) {
        var player = players.get(playerId);
        if (player == null)
            return;

        player.action = action;
        var text = new StringBuilder("Yurish " + turnCounter + ": ");

        switch (action) {
            case ATTACK:
                player.laser--;
                player.target.dmgTaken++;
                player.target.attackers.add(player);
                text.append("⚔️xujum");
                if (player.clone != null) {
                    player.target.dmgTaken++;
                    player.target.attackers.add(player.clone);
                }
                break;
            case DEFENCE:
                player.shield -= player.currentShield;
                text.append("🛡himoya");
                break;
            case DEF_FLAG:
                players.get(player.team * -1).dmgTaken -= player.currentShield;
                player.shield -= player.currentShield;
                text.append("🚩bayroq himoyasi");
                if (player.clone != null)
                    players.get(player.team * -1).dmgTaken -= player.currentShield;
                break;
            case CHARGE_LASER:
                player.laser += 3;
                text.append("⚡️lazer quvvatlash");
                break;
            case CHARGE_SHIELD:
                player.shield += 3;
                text.append("🔋qalqonni quvvatlash");
                break;
            case ROLL:
                text.append("🌫chetlashish");
                player.rollCounter = 0;
                break;
            case SUMMON_CLONE:
                text.append("👥klonni chiqarish");
                break;
        }
        player.isReady = true;
        player.afkTurns = 0;
        if (player.clone != null && action != Player.ACTION.SUMMON_CLONE)
            player.clone.syncStats();

        Methods.editMessageText()
                .setChatId(playerId)
                .setMessageId(player.message.getMessageId())
                .setText(text.toString())
                .setReplyMarkup(null)
                .call(handler);
        check();
    }

    void setupShield(int playerId, Player.ACTION action) {
        var player = players.get(playerId);
        if (player == null)
            return;

        player.action = action;

        int[][] rowsArray = {
                {1, 2, 3, 4, 5},
                {-1, -2, -3, -4, -5}
        };
        var markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();
        for (int[] row : rowsArray) {
            List<InlineKeyboardButton> buttonsRow = new ArrayList<>();
            for (int value : row) {
                buttonsRow.add(new InlineKeyboardButton()
                        .setText(String.valueOf(value))
                        .setCallbackData(FutureWarsBot.CALLBACK_DEFENCE_VALUE + value + " " + chatId));
            }
            buttons.add(buttonsRow);
        }

        if (player.currentShield > 0) {
            var data = (action == Player.ACTION.DEFENCE) ?
                    FutureWarsBot.CALLBACK_CONFIRM_DEFENCE :
                    FutureWarsBot.CALLBACK_CONFIRM_FLAG_DEFENCE;
            buttons.add(List.of(new InlineKeyboardButton()
                    .setText("Himoya")
                    .setCallbackData(data + chatId)));
        }

        buttons.add(List.of(new InlineKeyboardButton()
                .setText("Bekor qilish")
                .setCallbackData(FutureWarsBot.CALLBACK_MAIN_MENU + chatId)));
        markup.setKeyboard(buttons);
        Methods.editMessageText()
                .setChatId(playerId)
                .setMessageId(players.get(playerId).message.getMessageId())
                .setText("Qalqon quvvati: " + players.get(playerId).currentShield)
                .setReplyMarkup(markup)
                .call(handler);
    }

    void showTargets(int playerId) {
        var player = players.get(playerId);
        if (player == null)
            return;

        var markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();
        for (Player target : players.values()) {
            if (target.team == player.team || target.isDead)
                continue;
            List<InlineKeyboardButton> row = List.of(new InlineKeyboardButton()
                    .setText(target.name)
                    .setCallbackData(FutureWarsBot.CALLBACK_ATTACK + target.id + " " + chatId));
            buttons.add(row);
        }
        buttons.add(List.of(new InlineKeyboardButton()
                .setText("Bekor qilish")
                .setCallbackData(FutureWarsBot.CALLBACK_MAIN_MENU + chatId)));
        markup.setKeyboard(buttons);
        Methods.editMessageText()
                .setChatId(playerId)
                .setMessageId(player.message.getMessageId())
                .setText("Raqibni tanlang")
                .setReplyMarkup(markup)
                .call(handler);
    }

    void sendMainMenu(int playerId) {
        var player = players.get(playerId);
        if (player == null)
            return;

        player.currentShield = 0;

        var text = new StringBuilder("Sizning guruhingiz:\n\n");
        var flag = players.get(player.team * -1);
        text.append(String.format("%1$s: %2$d♥\n\n", flag.name, flag.hp));
        for (Player teammate : teams.get(player.team)) { // for each player's teammate
            if (teammate.isDead)
                continue;
            if (teammate.id == player.id) { // highlight current player's name and show coins
                text.append(String.format("<b>%1$s</b>: %2$d♥️ %3$d\uD83D\uDD34, %4$d\uD83D\uDD35, %5$d\uD83D\uDCB5\n",
                        player.name, player.hp, player.laser, player.shield, player.coins));
            } else if (teammate.id < 0) { // show clones
                if (teammate.id * -1 == playerId) { // higlight current player's clone
                    text.append(String.format("<b>%1$s (klon)</b>: %2$d♥️ %3$d\uD83D\uDD34, %4$d\uD83D\uDD35\n",
                            player.name, player.hp, player.laser, player.shield));
                } else {
                    text.append(String.format("%1$s (klon): %2$d♥️ %3$d\uD83D\uDD34, %4$d\uD83D\uDD35\n",
                            player.name, player.hp, player.laser, player.shield));
                }
            } else { // other players
                text.append(String.format("%1$s: %2$d♥️ %3$d\uD83D\uDD34, %4$d\uD83D\uDD35\n",
                        teammate.name, teammate.hp, teammate.laser, teammate.shield));
            }
        }
        var markup = new InlineKeyboardMarkup();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        if (player.laser > 0)
            row1.add(new InlineKeyboardButton()
                    .setText("⚔️Xujum")
                    .setCallbackData(FutureWarsBot.CALLBACK_SELECT_TARGET + chatId));
        if (player.shield > 0) {
            row1.add(new InlineKeyboardButton()
                    .setText("🛡Himoya")
                    .setCallbackData(FutureWarsBot.CALLBACK_DEFENCE + chatId));
        }
        
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        row2.add(new InlineKeyboardButton()
                    .setText("🚩Bayroqni himoyalash")
                    .setCallbackData(FutureWarsBot.CALLBACK_FLAG_DEFENCE + chatId));


        List<InlineKeyboardButton> row3 = new ArrayList<>();
        row3.add(new InlineKeyboardButton()
                .setText("⚡️Lazerni quvvatlash")
                .setCallbackData(FutureWarsBot.CALLBACK_CHARGE_LASER + chatId));
        row3.add(new InlineKeyboardButton()
                .setText("🔋Qalqonni quvvatlash")
                .setCallbackData(FutureWarsBot.CALLBACK_CHARGE_SHIELD + chatId));

        List<InlineKeyboardButton> row4 = new ArrayList<>();
        if (!player.usedClone) {
            row4.add(new InlineKeyboardButton()
                    .setText("👥Klon chiqarish")
                    .setCallbackData(FutureWarsBot.CALLBACK_SUMMON_CLONE + chatId));
        }
        if (player.rollCounter == 6) {
            row4.add(new InlineKeyboardButton()
                    .setText("🌫Chetlashish")
                    .setCallbackData(FutureWarsBot.CALLBACK_ROLL + chatId));
        }

        var lastrow = List.of(new InlineKeyboardButton()
                .setText("Guruhga xat yo'llash")
                .setSwitchInlineQueryCurrentChat(""));

        markup.setKeyboard(List.of(row1, row2, row3, row4, lastrow));
        text.append("\nHarakatni tanlang:");
        if (player.message == null) {
            player.message = handler.sendMessage(Methods.sendMessage()
                    .setChatId(player.id)
                    .setText(text.toString())
                    .setParseMode(ParseMode.HTML)
                    .setReplyMarkup(markup));
        } else {
            Methods.editMessageText()
                    .setChatId(player.id)
                    .setMessageId(player.message.getMessageId())
                    .setText(text.toString())
                    .setParseMode(ParseMode.HTML)
                    .setReplyMarkup(markup)
                    .call(handler);
        }
    }

    void setTarget(int playerId, int targetId) {
        var player = players.get(playerId);
        if (player == null)
            return;

        player.target = players.get(targetId);
    }

    private void check() { // end turn before time's up
        for (Player player : players.values()) {
            if (!player.isDead && !player.isReady) {
                return;
            }
        }
        makeTurn();
    }

    private boolean teamIsAlive(int team) {
        for (Player player : teams.get(team)) {
            if (!player.isDead) {
                return true;
            }
        }
        return false;
    }

    ScheduledExecutorService getTurnTimer() {
        return scheduler;
    }

    JoinTimer getJoinTimer() {
        return joinTimer;
    }

    long getChatId() {
        return chatId;
    }

    int getMessageId() {
        return messageId;
    }

    boolean isStarted() {
        return isStarted;
    }

    Map<Integer, Set<Player>> getTeams() {
        return teams;
    }

    Map<Integer, Player> getPlayers() {
        return players;
    }
}
