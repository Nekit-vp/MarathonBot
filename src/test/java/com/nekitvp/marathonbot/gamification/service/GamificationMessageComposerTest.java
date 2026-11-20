package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.service.GamificationMessageComposer.Highlight;
import com.nekitvp.marathonbot.gamification.service.GamificationMessageComposer.RewardLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GamificationMessageComposerTest {

    private GamificationMessageComposer composer;

    @BeforeEach
    void setUp() throws Exception {
        composer = new GamificationMessageComposer(new com.nekitvp.marathonbot.service.TemplatePhraseService());
    }

    @Test
    void composesOneCompactAggregateRewardBlock() {
        String block = composer.composeRewardBlock(List.of(
                new RewardLine("✨", "Зелёный день", null),
                new RewardLine("✨", "Зелёный день", null),
                new RewardLine("🎯", "Верность цели", "Бег 5 км")
        ), 0);

        assertThat(block).isEqualTo("""

                🎉 *Новые награды\\!*
                ✨ Зелёный день ×2
                🎯 Верность цели — Бег 5 км
                _Отличный шаг к вершине 🏔️_
                """.strip());
    }

    @Test
    void composesRouteCheckpointWeeklyAndLaunchSnapshots() {
        assertThat(composer.composeRouteProgress("Марафон v8", 37, 8, 0)).isEqualTo("""
                🥾 *Команда в пути*
                ▰▰▰▱▱▱▱▱▱▱ 37%
                ⚡ Твоя энергия сегодня: 8
                _Темп ровный — лагерь уже близко 🏕️_
                """.strip());

        assertThat(composer.composeCheckpointCelebration(
                "Марафон v8", 50, "🧭", "Плато стабильности")).isEqualTo("""
                🎉 *Новая точка маршрута\\!*
                🧭 *Плато стабильности* — 50%
                ▰▰▰▰▰▱▱▱▱▱
                Марафон v8: это общая победа 🤝
                """.strip());

        String weekly = composer.composeWeeklySummary(
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9), List.of(
                        new Highlight("Анна", "🔥", "Железная неделя"),
                        new Highlight("Борис", "🎯", "Верность цели"),
                        new Highlight("Вика", "💯", "Соточка"),
                        new Highlight("Глеб", "🌱", "Первые 25"),
                        new Highlight("Даша", "🧱", "Стабильность"),
                        new Highlight("Егор", "👑", "Железная привычка")
                ));
        assertThat(weekly).contains("3–9 августа", "Анна", "Даша", "Егор");

        assertThat(composer.composeLaunchPresentation("Марафон v8", 28)).singleElement()
                .asString().contains("🏔️ *MarathonBot 7\\.12: новое в марафоне\\!*",
                        "Повторяемые медали", "серии отчётов и целей",
                        "2 за своевременный отчёт", "от 0 до 6", "округлённой доле", "максимум 10",
                        "Поздний отчёт не даёт 2", "пропуск даёт 0",
                        "Средняя энергия 8", "125%", "25%", "50%", "75%", "100%",
                        "видимые условия");
    }

    @Test
    void listsEveryRewardWithoutCollapsingRemainders() {
        List<Highlight> highlights = new java.util.ArrayList<>();
        for (int index = 0; index < 12; index++) {
            highlights.add(new Highlight("Участник" + index, "🏅", "Награда " + index, "условие " + index));
        }

        String page = composer.composeDailyDigest(LocalDate.of(2026, 8, 6), highlights, "", "").getFirst();

        assertThat(page).doesNotContain("И ещё наград");
        for (int index = 0; index < 12; index++) {
            assertThat(page).contains("Награда " + index).contains("Участник" + index);
        }
    }

    @Test
    void weeklySummaryAlsoListsEveryReward() {
        List<Highlight> highlights = new java.util.ArrayList<>();
        for (int index = 0; index < 10; index++) {
            highlights.add(new Highlight("Участник" + index, "🏅", "Награда " + index, "условие"));
        }

        String weekly = composer.composeWeeklySummary(
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9), highlights);

        assertThat(weekly).doesNotContain("И ещё наград").contains("Награда 9", "Участник9");
    }

    @Test
    void printsShortHintNextToTheRewardTitle() {
        List<String> pages = composer.composeDailyDigest(
                LocalDate.of(2026, 8, 6), List.of(
                        new Highlight("Вова", "✨", "Зелёный день", "все цели дня"),
                        new Highlight("Тима", "🍀", "Зелёный хет-трик", "3 зелёных дня всего"),
                        new Highlight("Паша", "🍃", "Зелёная волна", "3 зелёных дня подряд")
                ), "", "");

        assertThat(pages).singleElement().asString()
                .contains("• ✨ Зелёный день \\(все цели дня\\) — Вова")
                .contains("• 🍀 Зелёный хет\\-трик \\(3 зелёных дня всего\\) — Тима")
                .contains("• 🍃 Зелёная волна \\(3 зелёных дня подряд\\) — Паша");
    }

    @Test
    void keepsOnlyFirstNamesInSummariesAndLeaderLine() {
        List<String> pages = composer.composeDailyDigest(
                LocalDate.of(2026, 8, 6), List.of(
                        new Highlight("Вова Петров", "✨", "Зелёный день", "все цели дня"),
                        new Highlight("Тима   Сидоров", "✨", "Зелёный день", "все цели дня")
                ), "", "");

        assertThat(pages).singleElement().asString()
                .contains("— Вова, Тима")
                .doesNotContain("Петров", "Сидоров");
        assertThat(composer.composeGreenLeader("Вова Петров", 5))
                .isEqualTo("🏆 Самая длинная зелёная серия: Вова — 5");
    }

    @Test
    void collapsesTheSamePersonListedUnderOneRewardByFirstName() {
        List<String> pages = composer.composeDailyDigest(
                LocalDate.of(2026, 8, 6), List.of(
                        new Highlight("Вова Петров", "🎯", "Верность цели", "одна цель 7 дней подряд"),
                        new Highlight("Вова Петров", "🎯", "Верность цели", "одна цель 7 дней подряд")
                ), "", "");

        assertThat(pages).singleElement().asString().contains("— Вова").doesNotContain("Вова, Вова");
    }

    @Test
    void blankParticipantNameFallsBackWithoutBreakingTheLine() {
        assertThat(GamificationMessageComposer.firstNameOnly("  ")).isEqualTo("Участник");
        assertThat(GamificationMessageComposer.firstNameOnly(null)).isEqualTo("Участник");
        assertThat(GamificationMessageComposer.firstNameOnly("Вова")).isEqualTo("Вова");
    }

    @Test
    void groupsDailyRewardsByRewardAndNamesEveryRecipient() {
        List<String> pages = composer.composeDailyDigest(
                LocalDate.of(2026, 8, 6), List.of(
                        new Highlight("Вова", "✨", "Зелёный день"),
                        new Highlight("Тима", "✨", "Зелёный день"),
                        new Highlight("Паша", "✨", "Зелёный день"),
                        new Highlight("Миша", "✨", "Зелёный день"),
                        new Highlight("Эд", "✨", "Зелёный день"),
                        new Highlight("Руслан", "🎯", "Верность цели")
                ), "🥾 *Экспедиция*: ▰▱▱▱▱▱▱▱▱▱ 5%", "");

        assertThat(pages).singleElement().asString()
                .contains("🌅 *Вчера в команде — 6 августа*")
                .contains("• ✨ Зелёный день — Вова, Тима, Паша, Миша, Эд")
                .contains("• 🎯 Верность цели — Руслан")
                .contains("🥾 *Экспедиция*: ▰▱▱▱▱▱▱▱▱▱ 5%")
                .doesNotContain("и ещё");
    }

    @Test
    void dailyDigestStaysReadableWithoutRewardsAndMergesExtraBlock() {
        assertThat(composer.composeDailyDigest(
                LocalDate.of(2026, 8, 6), List.of(), "прогресс", "🎉 чекпоинт"))
                .singleElement().asString()
                .contains("Тихий день на маршруте", "прогресс", "🎉 чекпоинт");
    }

    @Test
    void greenLeaderLineAppearsOnlyForARealRun() {
        assertThat(composer.composeGreenLeader("Вова", 5)).isEqualTo("🏆 Самая длинная зелёная серия: Вова — 5");
        assertThat(composer.composeGreenLeader("Вова", 0)).isEmpty();
    }

    @Test
    void reportTailShowsGreenRunAndReportNumberOnlyForGreenDays() {
        assertThat(composer.composeReportTail(true, 5, 12)).isEqualTo("💚 Зелёная серия: 5 · Отчёт №12");
        assertThat(composer.composeReportTail(true, 1, 3)).isEqualTo("💚 Зелёный день · Отчёт №3");
        assertThat(composer.composeReportTail(false, 4, 9)).isEmpty();
        assertThat(composer.composeReportTail(true, 2, 0)).isEmpty();
    }

    @Test
    void escapesEveryMarkdownSpecialExactlyOnceAndKeepsEmojiAndDuplicateNames() {
        String specials = "_*[]()~`>#+-=|{}.!\\";
        List<String> pages = composer.composeDailyDigest(
                LocalDate.of(2026, 8, 6), List.of(
                        new Highlight("🚀Анна" + specials, "🎯", "Goal " + specials),
                        new Highlight("🚀Анна" + specials, "✨", "Again " + specials)
                ), "", "");

        assertThat(pages).singleElement().asString()
                .contains("🚀Анна\\_\\*\\[\\]\\(\\)\\~\\`\\>\\#\\+\\-\\=\\|\\{\\}\\.\\!\\\\");
        assertThat(pages.getFirst()).doesNotContain("\\\\_");
    }

    @Test
    void paginatesLongGoalLabelsWithoutCuttingEscapeOrSurrogatePairs() {
        String longGoal = ("🚀_*[]()~`>#+-=|{}.! ").repeat(500);
        List<String> pages = composer.composeDailyDigest(
                LocalDate.of(2026, 8, 6),
                List.of(new Highlight("Анна", "🎯", longGoal)), "", "");

        assertThat(pages).hasSizeGreaterThan(1).allSatisfy(page -> {
            assertThat(page.length()).isLessThanOrEqualTo(GamificationMessageComposer.TELEGRAM_SAFE_LIMIT);
            assertThat(page).doesNotEndWith("\\");
            assertThat(Character.isHighSurrogate(page.charAt(page.length() - 1))).isFalse();
        });
        assertThat(String.join("", pages)).contains("\\_\\*\\[\\]");
    }
}
