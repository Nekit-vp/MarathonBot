package com.nekitvp.marathonbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
@Component
public class TemplatePhraseService {

    public static final String MISSED_TODAY = "missed_today";
    public static final String MISSED_YESTERDAY = "missed_yesterday";
    public static final String EVENING_QUESTIONS = "evening_questions";
    public static final String JOIN_PHRASES = "join_phrases";
    public static final String GAMIFICATION_REWARD = "gamification_reward";
    public static final String GAMIFICATION_EXPEDITION = "gamification_expedition";
    private static final Random RANDOM = new Random();
    private final Map<String, List<String>> phraseMap;

    public TemplatePhraseService() throws IOException {
        phraseMap = Map.of(
                MISSED_TODAY, loadPhrases("missed_report_today.txt"),
                MISSED_YESTERDAY, loadPhrases("missed_report_yesterday.txt"),
                EVENING_QUESTIONS, loadPhrases("evening_questions.txt"),
                JOIN_PHRASES, loadPhrases("join_phrases.txt"),
                GAMIFICATION_REWARD, loadGamificationPhrases(GAMIFICATION_REWARD),
                GAMIFICATION_EXPEDITION, loadGamificationPhrases(GAMIFICATION_EXPEDITION)
        );
        log.info("Phrases loaded successfully");
    }

    /**
     * Загружает список фраз из указанного файла ресурсов
     */
    private List<String> loadPhrases(String fileName) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(fileName)) {
            if (inputStream == null) {
                throw new IOException("Файл не найден: " + fileName);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .filter(line -> !line.isBlank())
                        .toList();
            }
        }
    }

    // ======== Методы получения фраз ========

    /**
     * 🕓 Для тех, кто не отправил отчёт сегодня
     */
    public String getRandomMissedToday() {
        return randomFromList(MISSED_TODAY);
    }

    /**
     * ⏰ Для тех, кто не отправил отчёт вчера
     */
    public String getRandomMissedYesterday() {
        return randomFromList(MISSED_YESTERDAY);
    }

    /**
     * 🌇 Случайная фраза для вечернего вопроса
     */
    public String getRandomEveningQuestion() {
        return randomFromList(EVENING_QUESTIONS);
    }

    /**
     * 👋 Случайная фраза для оповещения о вступлении участника
     */
    public String getRandomJoinPhrase(String firstName) {
        String template = randomFromList(JOIN_PHRASES);
        return String.format(template, firstName);
    }

    /** Returns a curated variant deterministically so retries render the same payload. */
    public String getGamificationPhrase(String category, int variantSeed) {
        List<String> phrases = phraseMap.get(category);
        if (phrases == null || phrases.isEmpty()) {
            throw new IllegalArgumentException("Неизвестная категория фраз: " + category);
        }
        return phrases.get(Math.floorMod(variantSeed, phrases.size()));
    }

    // ======== Вспомогательный метод ========

    private String randomFromList(String key) {
        List<String> phrases = phraseMap.get(key);
        if (phrases == null || phrases.isEmpty()) {
            throw new IllegalStateException("Нет загруженных фраз для ключа: " + key);
        }
        return phrases.get(RANDOM.nextInt(phrases.size()));
    }

    private List<String> loadGamificationPhrases(String category) throws IOException {
        return loadPhrases("gamification_phrases.txt").stream()
                .map(line -> line.split("\\|", 2))
                .filter(parts -> parts.length == 2 && parts[0].equals(category))
                .map(parts -> parts[1])
                .toList();
    }
}
