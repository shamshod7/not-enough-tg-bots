package com.senderman.futurewars;

import org.telegram.telegrambots.meta.logging.BotLogger;

class JoinTimer {
    private final long chatId;
    private boolean runTimer = true;
    private final FutureWarsHandler handler;

    JoinTimer(long chatId, FutureWarsHandler handler) {
        this.chatId = chatId;
        this.handler = handler;
        new Thread(this::startTimer).start();
    }

    private void startTimer() {
        int i;
        for (i = 299; i > 0 && runTimer; i--) {

            if (i % 60 == 0) {
                handler.sendMessage(chatId,
                        "<b>⏱Qo'shilish uchun " + (i / 60) + " daqiqa qoldi!</b>");
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                BotLogger.error("THREAD SLEEP", e.toString());
            }
        }
        stop(i == 0);
    }

    void stop(boolean timeIsUp) {
        if (timeIsUp) {
            GameController.stopjoin(chatId);
            handler.sendMessage(chatId, "Qo'shilish vaqti tugadi!");
        } else {
            runTimer = false;
        }
    }
}
