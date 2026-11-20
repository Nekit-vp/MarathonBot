package com.nekitvp.marathonbot.gamification.rule;

import com.nekitvp.marathonbot.gamification.model.RewardKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RewardCatalogTest {

    private final RewardCatalog catalog = new RewardCatalog();

    @Test
    void exposesEveryAndOnlyLaunchRewardWithVisibleMetadata() {
        Map<RewardCode, ExpectedDefinition> expected = expectedDefinitions();

        assertThat(catalog.rulesVersion()).isEqualTo(1);
        assertThat(catalog.definitions()).hasSize(36);
        assertThat(catalog.definitions())
                .extracting(RewardDefinition::code)
                .containsExactlyElementsOf(expected.keySet());

        expected.forEach((code, value) -> {
            RewardDefinition actual = catalog.definition(code);
            assertThat(actual.rulesVersion()).as(code + " rules version").isEqualTo(1);
            assertThat(actual.kind()).as(code + " kind").isEqualTo(value.kind());
            assertThat(actual.emoji()).as(code + " emoji").isEqualTo(value.emoji());
            assertThat(actual.title()).as(code + " title").isEqualTo(value.title());
            assertThat(actual.description()).as(code + " description").isEqualTo(value.description());
            assertThat(actual.repeatable()).as(code + " repeatability").isEqualTo(value.repeatable());
            assertThat(actual.visibleCondition()).as(code + " visible condition").isEqualTo(value.condition());
            assertThat(actual.occurrenceKeyStrategy()).as(code + " occurrence strategy").isNotNull();
        });
    }

    @Test
    void keepsDeferredRewardsOutOfLaunchDefinitions() {
        assertThat(RewardCode.names()).doesNotContain(
                "BALANCED_WEEK",
                "PERSONAL_BEST",
                "SECOND_WAVE",
                "EQUATOR",
                "STRONG_FINISH",
                "MARATHON_LEGEND"
        );
    }

    @Test
    void exposesOrderedTierRelationships() {
        assertThat(catalog.tier("REPORT_SERIES"))
                .extracting(RewardDefinition::code)
                .containsExactly(
                        RewardCode.FIRST_STEP,
                        RewardCode.REPORT_STREAK_3,
                        RewardCode.REPORT_STREAK_7,
                        RewardCode.REPORT_STREAK_14,
                        RewardCode.REPORT_STREAK_21
                );
        assertThat(catalog.tier("GOAL_STREAK"))
                .extracting(RewardDefinition::code)
                .containsExactly(RewardCode.GOAL_STREAK_7, RewardCode.GOAL_STREAK_14, RewardCode.GOAL_STREAK_21);
        assertThat(catalog.tier("GOALS_DONE"))
                .extracting(RewardDefinition::code)
                .containsExactly(
                        RewardCode.GOALS_DONE_25,
                        RewardCode.GOALS_DONE_50,
                        RewardCode.GOALS_DONE_100,
                        RewardCode.GOALS_DONE_200
                );
        assertThat(catalog.tier("GREEN_DAYS"))
                .extracting(RewardDefinition::code)
                .containsExactly(RewardCode.GREEN_DAYS_3, RewardCode.GREEN_DAYS_7, RewardCode.GREEN_DAYS_14);
        assertThat(catalog.definitions()).allSatisfy(definition -> {
            assertThat(definition.shortHint()).as(definition.code() + " short hint").isNotBlank();
            assertThat(definition.shortHint().length()).as(definition.code() + " short hint length")
                    .isLessThanOrEqualTo(RewardDefinition.MAX_SHORT_HINT_LENGTH);
        });
        assertThat(catalog.definitions())
                .extracting(RewardDefinition::shortHint)
                .doesNotHaveDuplicates();
        assertThat(catalog.definition(RewardCode.GREEN_DAYS_3).shortHint()).isEqualTo("3 зелёных дня всего");
        assertThat(catalog.definition(RewardCode.GREEN_STREAK_3).shortHint()).isEqualTo("3 зелёных дня подряд");
        assertThat(catalog.tier("GREEN_STREAK"))
                .extracting(RewardDefinition::code)
                .containsExactly(
                        RewardCode.GREEN_STREAK_3, RewardCode.GREEN_STREAK_5, RewardCode.GREEN_STREAK_10);
        assertThat(catalog.tier("EXPEDITION"))
                .extracting(RewardDefinition::code)
                .containsExactly(
                        RewardCode.EXPEDITION_25,
                        RewardCode.EXPEDITION_50,
                        RewardCode.EXPEDITION_75,
                        RewardCode.EXPEDITION_100
                );

        assertThat(catalog.definition(RewardCode.GOALS_DONE_100).tier())
                .isEqualTo(new RewardDefinition.Tier("GOALS_DONE", 3, 100));
    }

    @Test
    void createsDeterministicOccurrenceKeysForEverySupportedScope() {
        LocalDate date = LocalDate.of(2026, 8, 1);

        assertThat(catalog.definition(RewardCode.GREEN_DAY).occurrenceKey(OccurrenceKeyContext.onDate(date)))
                .isEqualTo("date:2026-08-01");
        assertThat(catalog.definition(RewardCode.EARLY_BIRD_WEEK).occurrenceKey(
                OccurrenceKeyContext.onDate(LocalDate.of(2027, 1, 1))))
                .isEqualTo("week:2026-W53");
        assertThat(catalog.definition(RewardCode.GOAL_STREAK_14).occurrenceKey(OccurrenceKeyContext.forGoal(42L)))
                .isEqualTo("goal:42");
        assertThat(catalog.definition(RewardCode.REPORT_STREAK_7).occurrenceKey(OccurrenceKeyContext.empty()))
                .isEqualTo("tier:REPORT_STREAK_7");
        assertThat(catalog.definition(RewardCode.FULL_DISTANCE).occurrenceKey(OccurrenceKeyContext.empty()))
                .isEqualTo("finish");
        assertThat(catalog.definition(RewardCode.EXPEDITION_75).occurrenceKey(OccurrenceKeyContext.empty()))
                .isEqualTo("checkpoint:75");
        assertThat(catalog.definition(RewardCode.ALL_ON_BOARD).occurrenceKey(OccurrenceKeyContext.onDate(date)))
                .isEqualTo("tier:ALL_ON_BOARD");
    }

    @Test
    void rejectsIncompleteOrAmbiguousCataloguesAtStartup() {
        List<RewardDefinition> valid = catalog.definitions();
        List<RewardDefinition> duplicate = new ArrayList<>(valid);
        duplicate.add(valid.getFirst());

        assertThatThrownBy(() -> RewardCatalog.validateVersion(1, duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate reward code");
        assertThatThrownBy(() -> RewardCatalog.validateVersion(1, valid.subList(1, valid.size())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing reward codes");

        RewardDefinition withoutStrategy = valid.getFirst().withOccurrenceKeyStrategy(null);
        List<RewardDefinition> missingStrategy = new ArrayList<>(valid);
        missingStrategy.set(0, withoutStrategy);
        assertThatThrownBy(() -> RewardCatalog.validateVersion(1, missingStrategy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("occurrence-key strategy");
    }

    @Test
    void preservesRawMarkdownSensitiveDomainMetadata() {
        RewardDefinition pushed = catalog.definition(RewardCode.PUSHED_TO_FINISH);
        RewardDefinition strongDay = catalog.definition(RewardCode.STRONG_DAY);

        assertThat(pushed.title()).isEqualTo("Дожила — и дожала!");
        assertThat(strongDay.visibleCondition()).contains("80–99%");
        assertThat(pushed.title()).doesNotContain("\\!");
        assertThat(strongDay.visibleCondition()).doesNotContain("\\%");
        assertThat(catalog.renderVisibleDefinition(RewardCode.PUSHED_TO_FINISH))
                .isEqualTo("🏁 Дожила — и дожала! — Финальные 5 активных дней: без пропусков отчётов и не менее 90% выполненных личных целей.");
    }

    @Test
    void snapshotsAreImmutableAndKeepDaySemanticsSeparate() {
        List<UserDaySnapshot.PersonalGoalSnapshot> mutableGoals = new ArrayList<>(List.of(
                new UserDaySnapshot.PersonalGoalSnapshot(10L, "Зарядка [15 минут]", true),
                new UserDaySnapshot.PersonalGoalSnapshot(11L, "Вода 2.0", false)
        ));
        UserDaySnapshot activeDay = new UserDaySnapshot(
                LocalDate.of(2026, 8, 1),
                false,
                false,
                new UserDaySnapshot.ReportGoalSnapshot(true, true, LocalTime.of(11, 59)),
                mutableGoals
        );
        mutableGoals.clear();

        assertThat(activeDay.configuredRestDay()).isFalse();
        assertThat(activeDay.personalDayOff()).isFalse();
        assertThat(activeDay.reportGoal().done()).isTrue();
        assertThat(activeDay.reportGoal().onTime()).isTrue();
        assertThat(activeDay.personalGoals()).hasSize(2);
        assertThatThrownBy(() -> activeDay.personalGoals().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        UserDaySnapshot configuredRest = UserDaySnapshot.configuredRest(LocalDate.of(2026, 8, 2));
        UserDaySnapshot personalDayOff = UserDaySnapshot.personalDayOff(LocalDate.of(2026, 8, 3));
        assertThat(configuredRest.configuredRestDay()).isTrue();
        assertThat(configuredRest.personalDayOff()).isFalse();
        assertThat(configuredRest.reportGoal().done()).isNull();
        assertThat(personalDayOff.configuredRestDay()).isFalse();
        assertThat(personalDayOff.personalDayOff()).isTrue();
        assertThat(personalDayOff.reportGoal().done()).isTrue();
        assertThat(personalDayOff.personalGoals()).isEmpty();
    }

    @Test
    void evaluationContextDefensivelyCopiesAndOrdersItsWindow() {
        UserDaySnapshot later = UserDaySnapshot.configuredRest(LocalDate.of(2026, 8, 2));
        UserDaySnapshot earlier = UserDaySnapshot.personalDayOff(LocalDate.of(2026, 8, 1));
        List<UserDaySnapshot> mutableWindow = new ArrayList<>(List.of(later, earlier));

        RewardEvaluationContext context = new RewardEvaluationContext(
                7L,
                99L,
                1,
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 8, 23),
                ZoneId.of("Europe/Moscow"),
                false,
                mutableWindow
        );
        mutableWindow.clear();

        assertThat(context.days()).extracting(UserDaySnapshot::activityDate)
                .containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2));
        assertThatThrownBy(() -> context.days().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private Map<RewardCode, ExpectedDefinition> expectedDefinitions() {
        Map<RewardCode, ExpectedDefinition> definitions = new EnumMap<>(RewardCode.class);
        add(definitions, RewardCode.STRONG_DAY, RewardKind.MEDAL, "💪", "Крепкий день", true,
                "Почти идеальный день — большая часть целей выполнена.",
                "Выполнить 80–99% личных целей за активный день.");
        add(definitions, RewardCode.GREEN_DAY, RewardKind.MEDAL, "✨", "Зелёный день", true,
                "Все личные цели дня выполнены.",
                "Выполнить 100% личных целей за активный день.");
        add(definitions, RewardCode.EARLY_START, RewardKind.MEDAL, "🌅", "Ранний старт", true,
                "Отчёт отправлен в первой половине дня.",
                "Отправить своевременный отчёт до 12:00 по Москве.");
        add(definitions, RewardCode.BEFORE_REMINDER, RewardKind.MEDAL, "🔕", "Без напоминания", true,
                "Отчёт отправлен до вечернего напоминания.",
                "Отправить своевременный отчёт с 12:00 до 21:59 по Москве.");
        add(definitions, RewardCode.COMEBACK, RewardKind.MEDAL, "🔄", "Камбэк", true,
                "После трудного дня все цели снова закрыты.",
                "Провести зелёный своевременный день сразу после активного дня с невыполненной личной целью.");
        add(definitions, RewardCode.EARLY_BIRD_WEEK, RewardKind.MEDAL, "🐦", "Ранняя пташка недели", true,
                "Самый ранний утренний отчёт завершённой недели.",
                "Отправить самый ранний своевременный отчёт до 12:00 в завершённой неделе понедельник–воскресенье.");
        add(definitions, RewardCode.TEAM_DAY, RewardKind.TEAM, "🌈", "Командный день", true,
                "Команда держит сильный средний темп.",
                "Набрать командой в среднем не менее 8 энергии за активный день.");
        add(definitions, RewardCode.FIRST_STEP, RewardKind.ACHIEVEMENT, "👟", "Первый шаг", false,
                "Первый своевременный отчёт положил начало дистанции.",
                "Отправить первый своевременный отчёт в марафоне.");
        add(definitions, RewardCode.REPORT_STREAK_3, RewardKind.ACHIEVEMENT, "🎵", "В ритме", false,
                "Три активных дня отчётов без паузы.",
                "Отправлять отчёты своевременно 3 активных дня подряд.");
        add(definitions, RewardCode.REPORT_STREAK_7, RewardKind.ACHIEVEMENT, "🔥", "Железная неделя", false,
                "Семь активных дней отчётов без паузы.",
                "Отправлять отчёты своевременно 7 активных дней подряд.");
        add(definitions, RewardCode.REPORT_STREAK_14, RewardKind.ACHIEVEMENT, "🚂", "Не сбавляя темп", false,
                "Четырнадцать активных дней отчётов без паузы.",
                "Отправлять отчёты своевременно 14 активных дней подряд.");
        add(definitions, RewardCode.REPORT_STREAK_21, RewardKind.ACHIEVEMENT, "🛡️", "Марафонский характер", false,
                "Двадцать один активный день отчётов без паузы.",
                "Отправлять отчёты своевременно 21 активный день подряд.");
        add(definitions, RewardCode.FULL_DISTANCE, RewardKind.ACHIEVEMENT, "🗺️", "Всю дистанцию", false,
                "Весь марафон пройден без пропущенных отчётов.",
                "Завершить марафон без пропущенных отчётных дней.");
        add(definitions, RewardCode.GOAL_STREAK_7, RewardKind.ACHIEVEMENT, "🎯", "Верность цели", false,
                "Одна цель выполнена семь активных дней подряд.",
                "Выполнять одну и ту же личную цель 7 активных дней подряд.");
        add(definitions, RewardCode.GOAL_STREAK_14, RewardKind.ACHIEVEMENT, "💎", "Мастер цели", false,
                "Одна цель выполнена четырнадцать активных дней подряд.",
                "Выполнять одну и ту же личную цель 14 активных дней подряд.");
        add(definitions, RewardCode.GOAL_STREAK_21, RewardKind.ACHIEVEMENT, "👑", "Железная привычка", false,
                "Одна цель выполнена двадцать один активный день подряд.",
                "Выполнять одну и ту же личную цель 21 активный день подряд.");
        add(definitions, RewardCode.GOALS_DONE_25, RewardKind.ACHIEVEMENT, "🌱", "Первые 25", false,
                "Первые двадцать пять выполненных личных целей.",
                "Выполнить 25 проверок личных целей в марафоне.");
        add(definitions, RewardCode.GOALS_DONE_50, RewardKind.ACHIEVEMENT, "🌿", "Полсотни", false,
                "Пятьдесят выполненных личных целей.",
                "Выполнить 50 проверок личных целей в марафоне.");
        add(definitions, RewardCode.GOALS_DONE_100, RewardKind.ACHIEVEMENT, "💯", "Соточка", false,
                "Сто выполненных личных целей.",
                "Выполнить 100 проверок личных целей в марафоне.");
        add(definitions, RewardCode.GOALS_DONE_200, RewardKind.ACHIEVEMENT, "🌳", "Двести зелёных", false,
                "Двести выполненных личных целей.",
                "Выполнить 200 проверок личных целей в марафоне.");
        add(definitions, RewardCode.GREEN_DAYS_3, RewardKind.ACHIEVEMENT, "🍀", "Зелёный хет-трик", false,
                "Три дня со всеми выполненными личными целями.",
                "Провести 3 зелёных дня в марафоне.");
        add(definitions, RewardCode.GREEN_DAYS_7, RewardKind.ACHIEVEMENT, "💚", "Изумрудная неделя", false,
                "Семь дней со всеми выполненными личными целями.",
                "Провести 7 зелёных дней в марафоне.");
        add(definitions, RewardCode.GREEN_DAYS_14, RewardKind.ACHIEVEMENT, "💚", "Изумрудный характер", false,
                "Четырнадцать дней со всеми выполненными личными целями.",
                "Провести 14 зелёных дней в марафоне.");
        add(definitions, RewardCode.GREEN_STREAK_3, RewardKind.ACHIEVEMENT, "🍃", "Зелёная волна", false,
                "Три зелёных дня подряд.",
                "Провести 3 зелёных дня подряд; выходной серию не прерывает.");
        add(definitions, RewardCode.GREEN_STREAK_5, RewardKind.ACHIEVEMENT, "🌲", "Зелёный марш", false,
                "Пять зелёных дней подряд.",
                "Провести 5 зелёных дней подряд; выходной серию не прерывает.");
        add(definitions, RewardCode.GREEN_STREAK_10, RewardKind.ACHIEVEMENT, "🏵️", "Зелёная легенда", false,
                "Десять зелёных дней подряд.",
                "Провести 10 зелёных дней подряд; выходной серию не прерывает.");
        add(definitions, RewardCode.STABILITY, RewardKind.ACHIEVEMENT, "🧱", "Стабильность", false,
                "Устойчивая неделя без пропущенных отчётов.",
                "Выполнить не менее 80% личных целей за 7 активных дней без пропущенных отчётов.");
        add(definitions, RewardCode.PUSHED_TO_FINISH, RewardKind.ACHIEVEMENT, "🏁", "Дожила — и дожала!", false,
                "Сильные финальные дни марафона.",
                "Финальные 5 активных дней: без пропусков отчётов и не менее 90% выполненных личных целей.");
        add(definitions, RewardCode.EXPEDITION_25, RewardKind.TEAM, "🏕️", "Лагерь первых привычек", false,
                "Команда прошла первую четверть маршрута.",
                "Достичь 25% маршрута экспедиции.");
        add(definitions, RewardCode.EXPEDITION_50, RewardKind.TEAM, "🧭", "Плато стабильности", false,
                "Команда прошла половину маршрута.",
                "Достичь 50% маршрута экспедиции.");
        add(definitions, RewardCode.EXPEDITION_75, RewardKind.TEAM, "🚩", "Перевал характера", false,
                "Команда прошла три четверти маршрута.",
                "Достичь 75% маршрута экспедиции.");
        add(definitions, RewardCode.EXPEDITION_100, RewardKind.TEAM, "🏔️", "Вершина марафона", false,
                "Команда достигла вершины маршрута.",
                "Достичь 100% маршрута экспедиции.");
        add(definitions, RewardCode.ALL_ON_BOARD, RewardKind.TEAM, "🤝", "Все на борту", false,
                "Все участники вовремя отметились в один день.",
                "Всем участникам дневного состава отправить своевременный отчёт в один активный день.");
        add(definitions, RewardCode.TEAM_RHYTHM, RewardKind.TEAM, "⚡", "Общий ритм", false,
                "Команда держит общий ритм три активных дня.",
                "Не менее 90% участников дневного состава отчитываются вовремя 3 активных дня подряд.");
        add(definitions, RewardCode.PERFECT_TEAM_DAY, RewardKind.TEAM, "🌈", "Идеальный командный день", false,
                "Вся команда отчиталась и почти все цели выполнены.",
                "Всем отчитаться вовремя и выполнить не менее 95% личных целей за активный день.");
        add(definitions, RewardCode.TEAM_WEEK, RewardKind.TEAM, "🔥", "Командная неделя", false,
                "Семь сильных командных дней без пропущенных отчётов.",
                "Провести 7 активных дней без пропущенных отчётов и выполнить не менее 85% личных целей.");
        return definitions;
    }

    private void add(
            Map<RewardCode, ExpectedDefinition> definitions,
            RewardCode code,
            RewardKind kind,
            String emoji,
            String title,
            boolean repeatable,
            String description,
            String condition
    ) {
        definitions.put(code, new ExpectedDefinition(kind, emoji, title, repeatable, description, condition));
    }

    private record ExpectedDefinition(
            RewardKind kind,
            String emoji,
            String title,
            boolean repeatable,
            String description,
            String condition
    ) {
    }
}
