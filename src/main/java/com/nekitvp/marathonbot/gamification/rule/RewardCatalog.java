package com.nekitvp.marathonbot.gamification.rule;

import com.nekitvp.marathonbot.gamification.model.RewardKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed, visible and versioned source of truth for launch reward metadata. */
@Component
public final class RewardCatalog {

    public static final int RULES_VERSION_1 = 1;

    private final Map<Integer, Map<RewardCode, RewardDefinition>> versions;

    public RewardCatalog() {
        List<RewardDefinition> launchDefinitions = launchDefinitions();
        validateVersion(RULES_VERSION_1, launchDefinitions);

        Map<RewardCode, RewardDefinition> byCode = new EnumMap<>(RewardCode.class);
        launchDefinitions.forEach(definition -> byCode.put(definition.code(), definition));
        versions = Map.of(RULES_VERSION_1, Collections.unmodifiableMap(byCode));
    }

    public int rulesVersion() {
        return RULES_VERSION_1;
    }

    public List<RewardDefinition> definitions() {
        return definitions(RULES_VERSION_1);
    }

    public List<RewardDefinition> definitions(int rulesVersion) {
        return List.copyOf(version(rulesVersion).values());
    }

    public RewardDefinition definition(RewardCode code) {
        return definition(RULES_VERSION_1, code);
    }

    public RewardDefinition definition(int rulesVersion, RewardCode code) {
        RewardDefinition definition = version(rulesVersion).get(code);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown reward code " + code + " for rules version " + rulesVersion);
        }
        return definition;
    }

    public List<RewardDefinition> tier(String family) {
        if (family == null || family.isBlank()) {
            return List.of();
        }
        return definitions().stream()
                .filter(definition -> definition.tier() != null)
                .filter(definition -> family.equals(definition.tier().family()))
                .sorted((left, right) -> Integer.compare(left.tier().order(), right.tier().order()))
                .toList();
    }

    /** Returns raw domain metadata. Markdown escaping belongs to the Telegram view boundary. */
    public String renderVisibleDefinition(RewardCode code) {
        RewardDefinition definition = definition(code);
        return "%s %s — %s".formatted(definition.emoji(), definition.title(), definition.visibleCondition());
    }

    static void validateVersion(int expectedVersion, List<RewardDefinition> definitions) {
        if (definitions == null) {
            throw new IllegalStateException("Reward definitions are missing");
        }

        Set<RewardCode> seenCodes = EnumSet.noneOf(RewardCode.class);
        Map<String, Set<Integer>> tierOrders = new LinkedHashMap<>();
        Map<String, Set<Integer>> tierThresholds = new LinkedHashMap<>();
        for (RewardDefinition definition : definitions) {
            if (definition == null) {
                throw new IllegalStateException("Reward definition cannot be null");
            }
            if (definition.rulesVersion() != expectedVersion) {
                throw new IllegalStateException(
                        "Reward " + definition.code() + " belongs to rules version " + definition.rulesVersion());
            }
            if (!seenCodes.add(definition.code())) {
                throw new IllegalStateException("Duplicate reward code: " + definition.code());
            }
            if (definition.occurrenceKeyStrategy() == null) {
                throw new IllegalStateException("Missing occurrence-key strategy for " + definition.code());
            }
            validateOccurrenceStrategy(definition);
            validateTierUniqueness(definition, tierOrders, tierThresholds);
        }

        Set<RewardCode> missing = EnumSet.allOf(RewardCode.class);
        missing.removeAll(seenCodes);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing reward codes for rules version " + expectedVersion + ": " + missing);
        }
    }

    private static void validateOccurrenceStrategy(RewardDefinition definition) {
        if (definition.occurrenceKeyStrategy() == OccurrenceKeyStrategy.GOAL
                && (definition.tier() == null || !"GOAL_STREAK".equals(definition.tier().family()))) {
            throw new IllegalStateException("Goal occurrence-key strategy requires goal-streak tier metadata: "
                    + definition.code());
        }
        if (definition.occurrenceKeyStrategy() == OccurrenceKeyStrategy.TEAM_CHECKPOINT
                && (definition.tier() == null || !"EXPEDITION".equals(definition.tier().family()))) {
            throw new IllegalStateException("Team checkpoint occurrence-key strategy requires expedition tier metadata: "
                    + definition.code());
        }
    }

    private static void validateTierUniqueness(
            RewardDefinition definition,
            Map<String, Set<Integer>> tierOrders,
            Map<String, Set<Integer>> tierThresholds
    ) {
        RewardDefinition.Tier tier = definition.tier();
        if (tier == null) {
            return;
        }
        if (!tierOrders.computeIfAbsent(tier.family(), ignored -> new HashSet<>()).add(tier.order())) {
            throw new IllegalStateException("Duplicate tier order in " + tier.family() + ": " + tier.order());
        }
        if (!tierThresholds.computeIfAbsent(tier.family(), ignored -> new HashSet<>()).add(tier.threshold())) {
            throw new IllegalStateException("Duplicate tier threshold in " + tier.family() + ": " + tier.threshold());
        }
    }

    private Map<RewardCode, RewardDefinition> version(int rulesVersion) {
        Map<RewardCode, RewardDefinition> version = versions.get(rulesVersion);
        if (version == null) {
            throw new IllegalArgumentException("Unsupported reward rules version: " + rulesVersion);
        }
        return version;
    }

    private static List<RewardDefinition> launchDefinitions() {
        List<RewardDefinition> definitions = new ArrayList<>();
        definitions.add(d(RewardCode.STRONG_DAY, RewardKind.MEDAL, "💪", "Крепкий день", true,
                "Почти идеальный день — большая часть целей выполнена.",
                "Выполнить 80–99% личных целей за активный день.", OccurrenceKeyStrategy.DATE));
        definitions.add(d(RewardCode.GREEN_DAY, RewardKind.MEDAL, "✨", "Зелёный день", true,
                "Все личные цели дня выполнены.",
                "Выполнить 100% личных целей за активный день.", OccurrenceKeyStrategy.DATE));
        definitions.add(d(RewardCode.EARLY_START, RewardKind.MEDAL, "🌅", "Ранний старт", true,
                "Отчёт отправлен в первой половине дня.",
                "Отправить своевременный отчёт до 12:00 по Москве.", OccurrenceKeyStrategy.DATE));
        definitions.add(d(RewardCode.BEFORE_REMINDER, RewardKind.MEDAL, "🔕", "Без напоминания", true,
                "Отчёт отправлен до вечернего напоминания.",
                "Отправить своевременный отчёт с 12:00 до 21:59 по Москве.", OccurrenceKeyStrategy.DATE));
        definitions.add(d(RewardCode.COMEBACK, RewardKind.MEDAL, "🔄", "Камбэк", true,
                "После трудного дня все цели снова закрыты.",
                "Провести зелёный своевременный день сразу после активного дня с невыполненной личной целью.",
                OccurrenceKeyStrategy.DATE));
        definitions.add(d(RewardCode.EARLY_BIRD_WEEK, RewardKind.MEDAL, "🐦", "Ранняя пташка недели", true,
                "Самый ранний утренний отчёт завершённой недели.",
                "Отправить самый ранний своевременный отчёт до 12:00 в завершённой неделе понедельник–воскресенье.",
                OccurrenceKeyStrategy.ISO_WEEK));
        definitions.add(d(RewardCode.TEAM_DAY, RewardKind.TEAM, "🌈", "Командный день", true,
                "Команда держит сильный средний темп.",
                "Набрать командой в среднем не менее 8 энергии за активный день.", OccurrenceKeyStrategy.DATE));

        definitions.add(t(RewardCode.FIRST_STEP, RewardKind.ACHIEVEMENT, "👟", "Первый шаг",
                "Первый своевременный отчёт положил начало дистанции.",
                "Отправить первый своевременный отчёт в марафоне.", OccurrenceKeyStrategy.TIER,
                "REPORT_SERIES", 1, 1));
        definitions.add(t(RewardCode.REPORT_STREAK_3, RewardKind.ACHIEVEMENT, "🎵", "В ритме",
                "Три активных дня отчётов без паузы.",
                "Отправлять отчёты своевременно 3 активных дня подряд.", OccurrenceKeyStrategy.TIER,
                "REPORT_SERIES", 2, 3));
        definitions.add(t(RewardCode.REPORT_STREAK_7, RewardKind.ACHIEVEMENT, "🔥", "Железная неделя",
                "Семь активных дней отчётов без паузы.",
                "Отправлять отчёты своевременно 7 активных дней подряд.", OccurrenceKeyStrategy.TIER,
                "REPORT_SERIES", 3, 7));
        definitions.add(t(RewardCode.REPORT_STREAK_14, RewardKind.ACHIEVEMENT, "🚂", "Не сбавляя темп",
                "Четырнадцать активных дней отчётов без паузы.",
                "Отправлять отчёты своевременно 14 активных дней подряд.", OccurrenceKeyStrategy.TIER,
                "REPORT_SERIES", 4, 14));
        definitions.add(t(RewardCode.REPORT_STREAK_21, RewardKind.ACHIEVEMENT, "🛡️", "Марафонский характер",
                "Двадцать один активный день отчётов без паузы.",
                "Отправлять отчёты своевременно 21 активный день подряд.", OccurrenceKeyStrategy.TIER,
                "REPORT_SERIES", 5, 21));
        definitions.add(d(RewardCode.FULL_DISTANCE, RewardKind.ACHIEVEMENT, "🗺️", "Всю дистанцию", false,
                "Весь марафон пройден без пропущенных отчётов.",
                "Завершить марафон без пропущенных отчётных дней.", OccurrenceKeyStrategy.FINISH));

        definitions.add(t(RewardCode.GOAL_STREAK_7, RewardKind.ACHIEVEMENT, "🎯", "Верность цели",
                "Одна цель выполнена семь активных дней подряд.",
                "Выполнять одну и ту же личную цель 7 активных дней подряд.", OccurrenceKeyStrategy.GOAL,
                "GOAL_STREAK", 1, 7));
        definitions.add(t(RewardCode.GOAL_STREAK_14, RewardKind.ACHIEVEMENT, "💎", "Мастер цели",
                "Одна цель выполнена четырнадцать активных дней подряд.",
                "Выполнять одну и ту же личную цель 14 активных дней подряд.", OccurrenceKeyStrategy.GOAL,
                "GOAL_STREAK", 2, 14));
        definitions.add(t(RewardCode.GOAL_STREAK_21, RewardKind.ACHIEVEMENT, "👑", "Железная привычка",
                "Одна цель выполнена двадцать один активный день подряд.",
                "Выполнять одну и ту же личную цель 21 активный день подряд.", OccurrenceKeyStrategy.GOAL,
                "GOAL_STREAK", 3, 21));

        definitions.add(t(RewardCode.GOALS_DONE_25, RewardKind.ACHIEVEMENT, "🌱", "Первые 25",
                "Первые двадцать пять выполненных личных целей.",
                "Выполнить 25 проверок личных целей в марафоне.", OccurrenceKeyStrategy.TIER,
                "GOALS_DONE", 1, 25));
        definitions.add(t(RewardCode.GOALS_DONE_50, RewardKind.ACHIEVEMENT, "🌿", "Полсотни",
                "Пятьдесят выполненных личных целей.",
                "Выполнить 50 проверок личных целей в марафоне.", OccurrenceKeyStrategy.TIER,
                "GOALS_DONE", 2, 50));
        definitions.add(t(RewardCode.GOALS_DONE_100, RewardKind.ACHIEVEMENT, "💯", "Соточка",
                "Сто выполненных личных целей.",
                "Выполнить 100 проверок личных целей в марафоне.", OccurrenceKeyStrategy.TIER,
                "GOALS_DONE", 3, 100));
        definitions.add(t(RewardCode.GOALS_DONE_200, RewardKind.ACHIEVEMENT, "🌳", "Двести зелёных",
                "Двести выполненных личных целей.",
                "Выполнить 200 проверок личных целей в марафоне.", OccurrenceKeyStrategy.TIER,
                "GOALS_DONE", 4, 200));

        definitions.add(t(RewardCode.GREEN_DAYS_3, RewardKind.ACHIEVEMENT, "🍀", "Зелёный хет-трик",
                "Три дня со всеми выполненными личными целями.",
                "Провести 3 зелёных дня в марафоне.", OccurrenceKeyStrategy.TIER,
                "GREEN_DAYS", 1, 3));
        definitions.add(t(RewardCode.GREEN_DAYS_7, RewardKind.ACHIEVEMENT, "💚", "Изумрудная неделя",
                "Семь дней со всеми выполненными личными целями.",
                "Провести 7 зелёных дней в марафоне.", OccurrenceKeyStrategy.TIER,
                "GREEN_DAYS", 2, 7));
        definitions.add(t(RewardCode.GREEN_DAYS_14, RewardKind.ACHIEVEMENT, "💚", "Изумрудный характер",
                "Четырнадцать дней со всеми выполненными личными целями.",
                "Провести 14 зелёных дней в марафоне.", OccurrenceKeyStrategy.TIER,
                "GREEN_DAYS", 3, 14));
        definitions.add(t(RewardCode.GREEN_STREAK_3, RewardKind.ACHIEVEMENT, "🍃", "Зелёная волна",
                "Три зелёных дня подряд.",
                "Провести 3 зелёных дня подряд; выходной серию не прерывает.", OccurrenceKeyStrategy.TIER,
                "GREEN_STREAK", 1, 3));
        definitions.add(t(RewardCode.GREEN_STREAK_5, RewardKind.ACHIEVEMENT, "🌲", "Зелёный марш",
                "Пять зелёных дней подряд.",
                "Провести 5 зелёных дней подряд; выходной серию не прерывает.", OccurrenceKeyStrategy.TIER,
                "GREEN_STREAK", 2, 5));
        definitions.add(t(RewardCode.GREEN_STREAK_10, RewardKind.ACHIEVEMENT, "🏵️", "Зелёная легенда",
                "Десять зелёных дней подряд.",
                "Провести 10 зелёных дней подряд; выходной серию не прерывает.", OccurrenceKeyStrategy.TIER,
                "GREEN_STREAK", 3, 10));
        definitions.add(d(RewardCode.STABILITY, RewardKind.ACHIEVEMENT, "🧱", "Стабильность", false,
                "Устойчивая неделя без пропущенных отчётов.",
                "Выполнить не менее 80% личных целей за 7 активных дней без пропущенных отчётов.",
                OccurrenceKeyStrategy.TIER));
        definitions.add(d(RewardCode.PUSHED_TO_FINISH, RewardKind.ACHIEVEMENT, "🏁", "Дожила — и дожала!", false,
                "Сильные финальные дни марафона.",
                "Финальные 5 активных дней: без пропусков отчётов и не менее 90% выполненных личных целей.",
                OccurrenceKeyStrategy.FINISH));

        definitions.add(t(RewardCode.EXPEDITION_25, RewardKind.TEAM, "🏕️", "Лагерь первых привычек",
                "Команда прошла первую четверть маршрута.",
                "Достичь 25% маршрута экспедиции.", OccurrenceKeyStrategy.TEAM_CHECKPOINT,
                "EXPEDITION", 1, 25));
        definitions.add(t(RewardCode.EXPEDITION_50, RewardKind.TEAM, "🧭", "Плато стабильности",
                "Команда прошла половину маршрута.",
                "Достичь 50% маршрута экспедиции.", OccurrenceKeyStrategy.TEAM_CHECKPOINT,
                "EXPEDITION", 2, 50));
        definitions.add(t(RewardCode.EXPEDITION_75, RewardKind.TEAM, "🚩", "Перевал характера",
                "Команда прошла три четверти маршрута.",
                "Достичь 75% маршрута экспедиции.", OccurrenceKeyStrategy.TEAM_CHECKPOINT,
                "EXPEDITION", 3, 75));
        definitions.add(t(RewardCode.EXPEDITION_100, RewardKind.TEAM, "🏔️", "Вершина марафона",
                "Команда достигла вершины маршрута.",
                "Достичь 100% маршрута экспедиции.", OccurrenceKeyStrategy.TEAM_CHECKPOINT,
                "EXPEDITION", 4, 100));
        definitions.add(d(RewardCode.ALL_ON_BOARD, RewardKind.TEAM, "🤝", "Все на борту", false,
                "Все участники вовремя отметились в один день.",
                "Всем участникам дневного состава отправить своевременный отчёт в один активный день.",
                OccurrenceKeyStrategy.TIER));
        definitions.add(d(RewardCode.TEAM_RHYTHM, RewardKind.TEAM, "⚡", "Общий ритм", false,
                "Команда держит общий ритм три активных дня.",
                "Не менее 90% участников дневного состава отчитываются вовремя 3 активных дня подряд.",
                OccurrenceKeyStrategy.TIER));
        definitions.add(d(RewardCode.PERFECT_TEAM_DAY, RewardKind.TEAM, "🌈", "Идеальный командный день", false,
                "Вся команда отчиталась и почти все цели выполнены.",
                "Всем отчитаться вовремя и выполнить не менее 95% личных целей за активный день.",
                OccurrenceKeyStrategy.TIER));
        definitions.add(d(RewardCode.TEAM_WEEK, RewardKind.TEAM, "🔥", "Командная неделя", false,
                "Семь сильных командных дней без пропущенных отчётов.",
                "Провести 7 активных дней без пропущенных отчётов и выполнить не менее 85% личных целей.",
                OccurrenceKeyStrategy.TIER));
        return List.copyOf(definitions);
    }

    /**
     * Краткая расшифровка условия, которая печатается рядом с названием в групповых сводках.
     * Название награды образное, поэтому без подсказки читатель не понимает, за что её дали;
     * она же разводит накопительные семьи (GREEN_DAYS) и серии подряд (GREEN_STREAK).
     */
    private static final Map<RewardCode, String> SHORT_HINTS = Map.ofEntries(
            Map.entry(RewardCode.STRONG_DAY, "80%+ целей за день"),
            Map.entry(RewardCode.GREEN_DAY, "все цели дня"),
            Map.entry(RewardCode.EARLY_START, "отчёт до 12:00"),
            Map.entry(RewardCode.BEFORE_REMINDER, "отчёт до 22:00"),
            Map.entry(RewardCode.COMEBACK, "все цели после срыва"),
            Map.entry(RewardCode.EARLY_BIRD_WEEK, "самый ранний за неделю"),
            Map.entry(RewardCode.TEAM_DAY, "средняя энергия 8+"),
            Map.entry(RewardCode.FIRST_STEP, "первый отчёт"),
            Map.entry(RewardCode.REPORT_STREAK_3, "3 отчёта подряд"),
            Map.entry(RewardCode.REPORT_STREAK_7, "7 отчётов подряд"),
            Map.entry(RewardCode.REPORT_STREAK_14, "14 отчётов подряд"),
            Map.entry(RewardCode.REPORT_STREAK_21, "21 отчёт подряд"),
            Map.entry(RewardCode.FULL_DISTANCE, "марафон без пропусков"),
            Map.entry(RewardCode.GOAL_STREAK_7, "одна цель 7 дней подряд"),
            Map.entry(RewardCode.GOAL_STREAK_14, "одна цель 14 дней подряд"),
            Map.entry(RewardCode.GOAL_STREAK_21, "одна цель 21 день подряд"),
            Map.entry(RewardCode.GOALS_DONE_25, "25 выполненных целей"),
            Map.entry(RewardCode.GOALS_DONE_50, "50 выполненных целей"),
            Map.entry(RewardCode.GOALS_DONE_100, "100 выполненных целей"),
            Map.entry(RewardCode.GOALS_DONE_200, "200 выполненных целей"),
            Map.entry(RewardCode.GREEN_DAYS_3, "3 зелёных дня всего"),
            Map.entry(RewardCode.GREEN_DAYS_7, "7 зелёных дней всего"),
            Map.entry(RewardCode.GREEN_DAYS_14, "14 зелёных дней всего"),
            Map.entry(RewardCode.GREEN_STREAK_3, "3 зелёных дня подряд"),
            Map.entry(RewardCode.GREEN_STREAK_5, "5 зелёных дней подряд"),
            Map.entry(RewardCode.GREEN_STREAK_10, "10 зелёных дней подряд"),
            Map.entry(RewardCode.STABILITY, "80%+ целей за неделю"),
            Map.entry(RewardCode.PUSHED_TO_FINISH, "сильный финиш марафона"),
            Map.entry(RewardCode.EXPEDITION_25, "25% маршрута"),
            Map.entry(RewardCode.EXPEDITION_50, "50% маршрута"),
            Map.entry(RewardCode.EXPEDITION_75, "75% маршрута"),
            Map.entry(RewardCode.EXPEDITION_100, "100% маршрута"),
            Map.entry(RewardCode.ALL_ON_BOARD, "все отчитались вовремя"),
            Map.entry(RewardCode.TEAM_RHYTHM, "90%+ вовремя 3 дня"),
            Map.entry(RewardCode.PERFECT_TEAM_DAY, "все вовремя, 95%+ целей"),
            Map.entry(RewardCode.TEAM_WEEK, "85%+ целей за неделю")
    );

    private static String hint(RewardCode code) {
        String hint = SHORT_HINTS.get(code);
        if (hint == null) {
            throw new IllegalStateException("Missing short hint for reward " + code);
        }
        return hint;
    }

    private static RewardDefinition d(
            RewardCode code,
            RewardKind kind,
            String emoji,
            String title,
            boolean repeatable,
            String description,
            String visibleCondition,
            OccurrenceKeyStrategy occurrenceKeyStrategy
    ) {
        return new RewardDefinition(
                RULES_VERSION_1,
                code,
                kind,
                emoji,
                title,
                description,
                hint(code),
                repeatable,
                visibleCondition,
                occurrenceKeyStrategy,
                null
        );
    }

    private static RewardDefinition t(
            RewardCode code,
            RewardKind kind,
            String emoji,
            String title,
            String description,
            String visibleCondition,
            OccurrenceKeyStrategy occurrenceKeyStrategy,
            String tierFamily,
            int tierOrder,
            int threshold
    ) {
        return new RewardDefinition(
                RULES_VERSION_1,
                code,
                kind,
                emoji,
                title,
                description,
                hint(code),
                false,
                visibleCondition,
                occurrenceKeyStrategy,
                new RewardDefinition.Tier(tierFamily, tierOrder, threshold)
        );
    }
}
