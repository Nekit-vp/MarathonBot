package com.nekitvp.marathonbot.handler.gamification;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.gamification.service.GamificationViewService;
import com.nekitvp.marathonbot.gamification.service.AchievementService;
import com.nekitvp.marathonbot.gamification.service.GamificationService;
import com.nekitvp.marathonbot.gamification.service.ReportGamificationService;
import com.nekitvp.marathonbot.gamification.rule.PersonalRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.rule.StabilityAndFinishRewardEvaluator;
import com.nekitvp.marathonbot.gamification.support.PostgresTestContainerConfig;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.AchievementItem;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.ExpeditionView;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.GoalStreak;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.MedalItem;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.ProgressItem;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.RewardItem;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.StreakView;
import com.nekitvp.marathonbot.handler.MarathonHandler;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.service.HistoryService;
import com.nekitvp.marathonbot.service.MarathonService;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.util.MarkdownUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.springframework.data.util.Pair;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RewardsCabinetHandlerTest {

    @Mock private UserService userService;
    @Mock private GamificationViewService viewService;
    @Mock private HistoryService historyService;
    @Mock private MarathonService marathonService;

    private UserEntity user;
    private Update update;

    @BeforeEach
    void setUp() {
        MarathonEntity marathon = MarathonEntity.builder()
                .id(35L)
                .name("Марафон [v8].")
                .status(MarathonStatus.ACTIVE)
                .build();
        user = UserEntity.builder().telegramId(101L).telegramFirstName("Алекс").marathon(marathon).build();
        when(userService.getUser(101L)).thenReturn(user);
        update = callbackUpdate(101L, "ignored", 42, true);
    }

    @Test
    void summaryShowsCountersPendingRewardsAndExplicitNavigation() {
        GamificationCabinetView view = view(false,
                List.of(new RewardItem("✨", "Зелёный день", null)),
                List.of(new MedalItem("💪", "Крепкий день", 12)),
                List.of(new AchievementItem("👟", "Первый шаг", null)),
                List.of(), streaks(), expedition());
        when(viewService.openCabinet(user)).thenReturn(view);

        Object result = new RewardsMainHandler(userService, viewService).handle(update);

        assertThat(text(result)).contains("Мои награды", "Новые награды", "Зелёный день", "Медалей: 12", "Достижений: 1");
        assertThat(parseMode(result)).isEqualTo("MarkdownV2");
        assertThat(callbacks(result)).containsExactly(
                StateBot.REWARD_MEDALS.name(), StateBot.REWARD_ACHIEVEMENTS.name(),
                StateBot.REWARD_STREAKS.name(), StateBot.EXPEDITION.name(), StateBot.MARATHON.name());
        assertThat(StateBot.REWARD_MEDALS.getButtonInThisState()).isNull();
        assertThat(StateBot.REWARD_ACHIEVEMENTS.getButtonInThisState()).isNull();
        assertThat(StateBot.REWARD_STREAKS.getButtonInThisState()).isNull();
        assertThat(StateBot.EXPEDITION.getButtonInThisState()).isNull();
    }

    @Test
    void medalsAggregateCountersAndEmptyCollectionIsWarm() {
        when(viewService.getCabinet(user)).thenReturn(view(false, List.of(),
                List.of(new MedalItem("✨", "Зелёный день", 5), new MedalItem("🌅", "Ранний старт", 3)),
                List.of(), List.of(), streaks(), expedition()));
        Object populated = new RewardMedalsHandler(userService, viewService).handle(update);
        assertThat(text(populated)).contains("Зелёный день ×5", "Ранний старт ×3");

        when(viewService.getCabinet(user)).thenReturn(view(false, List.of(), List.of(), List.of(), List.of(),
                streaks(), expedition()));
        Object empty = new RewardMedalsHandler(userService, viewService).handle(update);
        assertThat(text(empty)).contains("Коллекция пока пуста", "первая медаль");
    }

    @Test
    void achievementsShowUniqueAwardsGoalSnapshotsAndNearbyVisibleMilestonesEscapedOnce() {
        when(viewService.getCabinet(user)).thenReturn(view(false, List.of(), List.of(),
                List.of(new AchievementItem("🎯", "Верность цели", "Читать [20] стр.")),
                List.of(new ProgressItem("Первые 25", 17, 25)), streaks(), expedition()));

        Object result = new RewardAchievementsHandler(userService, viewService).handle(update);

        assertThat(text(result)).contains("Верность цели — Читать \\[20\\] стр\\.", "Первые 25: 17/25");
        assertThat(text(result)).doesNotContain("\\\\[");
    }

    @Test
    void everyMarkdownV2SpecialInDynamicGoalSnapshotIsEscapedExactlyOnce() {
        String special = "\\_*[]()~`>#+-=|{}.!";
        when(viewService.getCabinet(user)).thenReturn(view(false, List.of(), List.of(),
                List.of(new AchievementItem("🎯", "Верность цели", special)), List.of(), streaks(), expedition()));

        Object result = new RewardAchievementsHandler(userService, viewService).handle(update);

        String once = MarkdownUtil.escape(special);
        String twice = MarkdownUtil.escape(once);
        assertThat(text(result)).contains("— " + once);
        assertThat(text(result)).doesNotContain("— " + twice);
    }

    @Test
    void seriesShowCurrentBestAndGoalSnapshots() {
        when(viewService.getCabinet(user)).thenReturn(view(false, List.of(), List.of(), List.of(), List.of(),
                new StreakView(4, 9, List.of(new GoalStreak("Спорт (утро)", 7, 14))), expedition()));

        Object result = new RewardStreaksHandler(userService, viewService).handle(update);

        assertThat(text(result)).contains("Текущая серия: 4", "Лучшая серия: 9", "Спорт \\(утро\\): 7 сейчас, 14 максимум");
    }

    @Test
    void expeditionShowsPersonalEnergyTeamRouteAndCheckpointsForFinishedMarathon() {
        ExpeditionView finished = new ExpeditionView(new BigDecimal("73.2500"), 48, 8,
                List.of(new RewardItem("🏕️", "Лагерь первых привычек", null)));
        when(viewService.getCabinet(user)).thenReturn(view(true, List.of(), List.of(), List.of(), List.of(),
                streaks(), finished));

        Object result = new ExpeditionHandler(userService, viewService).handle(update);

        assertThat(text(result)).contains("Маршрут завершён", "73,25%", "Личная энергия: 48", "Сегодня: 8", "Лагерь первых привычек");
    }

    @Test
    void longCollectionsArePaginatedAndEveryScreenStaysBelowTelegramLimit() {
        List<AchievementItem> longCollection = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(index -> new AchievementItem("🏆", "Награда " + index + " " + "я".repeat(180), null))
                .toList();
        when(viewService.getCabinet(user)).thenReturn(view(false, List.of(), List.of(), longCollection, List.of(),
                streaks(), expedition()));

        Object first = new RewardAchievementsHandler(userService, viewService).handle(update);

        assertThat(text(first).length()).isLessThanOrEqualTo(GamificationCabinetView.TELEGRAM_SAFE_LIMIT);
        assertThat(callbacks(first)).contains("2", StateBot.REWARDS_MAIN.name());
    }

    @Test
    void marathonScreenShowsRewardsOnlyWhenPersistedGameStateExists() {
        MarathonEntity marathon = user.getMarathon();
        marathon.setDateStart(LocalDateTime.now().minusDays(2));
        marathon.setDateEnd(LocalDateTime.now().plusDays(2));
        marathon.setFreeFailCount(5);
        marathon.setPenaltyAmount(100);
        when(userService.userHasAnyMarathon(101L)).thenReturn(true);
        when(historyService.getCountFailByUserInMarathon(user)).thenReturn(Pair.of(0L, 0L));
        when(historyService.checkExistNullHistoryYesterday(101L)).thenReturn(true);
        when(viewService.hasGameState(35L)).thenReturn(true, false);

        MarathonHandler handler = new MarathonHandler(userService, historyService, marathonService, viewService);
        Object withGame = handler.handle(update);
        Object withoutGame = handler.handle(update);

        assertThat(callbacks(withGame)).contains(StateBot.REWARDS_MAIN.name());
        assertThat(callbacks(withoutGame)).doesNotContain(StateBot.REWARDS_MAIN.name());
    }

    private GamificationCabinetView view(
            boolean finished,
            List<RewardItem> pending,
            List<MedalItem> medals,
            List<AchievementItem> achievements,
            List<ProgressItem> progress,
            StreakView streaks,
            ExpeditionView expedition
    ) {
        return new GamificationCabinetView("Марафон [v8].", finished, pending, medals, achievements, progress,
                streaks, expedition);
    }

    private static StreakView streaks() {
        return new StreakView(2, 5, List.of());
    }

    private static ExpeditionView expedition() {
        return new ExpeditionView(new BigDecimal("24.5000"), 15, 7, List.of());
    }

    private static Update callbackUpdate(long userId, String data, int messageId, boolean withKeyboard) {
        org.telegram.telegrambots.meta.api.objects.User telegramUser = new org.telegram.telegrambots.meta.api.objects.User();
        telegramUser.setId(userId);
        Chat chat = new Chat();
        chat.setId(userId);
        chat.setType("private");
        Message message = new Message();
        message.setMessageId(messageId);
        message.setChat(chat);
        if (withKeyboard) {
            message.setReplyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of()).build());
        }
        CallbackQuery callback = new CallbackQuery();
        callback.setFrom(telegramUser);
        callback.setData(data);
        callback.setMessage(message);
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }

    private static String text(Object result) {
        return result instanceof SendMessage send ? send.getText() : ((EditMessageText) result).getText();
    }

    private static String parseMode(Object result) {
        return result instanceof SendMessage send ? send.getParseMode() : ((EditMessageText) result).getParseMode();
    }

    private static List<String> callbacks(Object result) {
        InlineKeyboardMarkup keyboard = result instanceof SendMessage send
                ? (InlineKeyboardMarkup) send.getReplyMarkup() : ((EditMessageText) result).getReplyMarkup();
        return keyboard.getKeyboard().stream().flatMap(List::stream).map(button -> button.getCallbackData()).toList();
    }
}

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PostgresTestContainerConfig.class,
        RewardCatalog.class,
        PersonalRewardEvaluator.class,
        StabilityAndFinishRewardEvaluator.class,
        AchievementService.class,
        GamificationViewService.class,
        ReportGamificationService.class,
        com.nekitvp.marathonbot.service.TemplatePhraseService.class,
        com.nekitvp.marathonbot.gamification.service.GamificationMessageComposer.class
})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GamificationViewServiceIntegrationTest {

    @Autowired private GamificationViewService viewService;
    @Autowired private ReportGamificationService reportService;
    @Autowired private MarathonRepository marathonRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private GamificationService gamificationService;

    private LocalDate today;
    private LocalDateTime now;
    private Long marathonId;
    private UserEntity user;

    @org.junit.jupiter.api.BeforeEach
    void setUpDatabase() {
        for (String table : List.of("gamification_notification", "marathon_participation", "expedition_state",
                "expedition_contribution", "reward_award", "history", "goal", "marathon_manager", "users",
                "motivation", "marathon")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        today = LocalDate.now(AchievementService.MOSCOW_ZONE);
        now = today.atTime(15, 0);
        marathonId = jdbcTemplate.queryForObject("""
                INSERT INTO marathon(name,date_start,date_end,gamification_enabled,status,rest_on_saturday,rest_on_sunday)
                VALUES ('Проект [v8].',?,?,true,'ACTIVE',false,false) RETURNING id
                """, Long.class, today.minusDays(3).atStartOfDay(), today.plusDays(3).atTime(23, 59));
        jdbcTemplate.update("""
                INSERT INTO users(telegram_id,telegram_first_name,marathon_id,created_at,updated_at,state,admin,
                                  count_change_state,count_change_state_all)
                VALUES (101,'Алекс',?,?,?,'START',false,0,0)
                """, marathonId, now, now);
        jdbcTemplate.update("""
                INSERT INTO expedition_state(marathon_id,rule_version,activated_at,route_start_date,route_end_date,
                    rest_on_saturday,rest_on_sunday,planned_active_days,distance,last_announced_checkpoint,version)
                VALUES (?,1,?,?,?,false,false,7,24.5000,0,0)
                """, marathonId, today.minusDays(3).atStartOfDay(), today.minusDays(3), today.plusDays(3));
        insertGoal(1000L, "Отчёт", 0);
        insertGoal(1001L, "Читать [20] стр.", 1);
        insertDay(today.minusDays(3), true, true);
        insertDay(today.minusDays(2), true, true);
        insertDay(today.minusDays(1), false, false);
        insertDay(today, true, true);
        insertContribution(today.minusDays(1), 7);
        insertContribution(today, 8);
        insertAward("STRONG_DAY", "MEDAL", "d1", null, "IMMEDIATE", now.minusDays(2), now.minusDays(2));
        insertAward("STRONG_DAY", "MEDAL", "d2", null, "IMMEDIATE", now.minusDays(1), now.minusDays(1));
        insertAward("FIRST_STEP", "ACHIEVEMENT", "REPORT_SERIES:1", null, "BACKFILL", now.minusDays(3), null);
        insertAward("GOAL_STREAK_7", "ACHIEVEMENT", "goal:1001", "Читать [20] стр.", "BACKFILL", now, null);
        MarathonEntity marathon = marathonRepository.findById(marathonId).orElseThrow();
        user = UserEntity.builder().telegramId(101L).telegramFirstName("Алекс").marathon(marathon).build();
    }

    @Test
    void buildsMarathonScopedCountersStreaksSnapshotsMilestonesEnergyAndRoute() {
        GamificationCabinetView view = viewService.getCabinet(user);

        assertThat(view.medals()).singleElement().satisfies(medal -> {
            assertThat(medal.title()).isEqualTo("Крепкий день");
            assertThat(medal.count()).isEqualTo(2);
        });
        assertThat(view.achievements()).extracting(AchievementItem::goalName)
                .contains("Читать [20] стр.");
        assertThat(view.streaks().currentReportStreak()).isEqualTo(1);
        assertThat(view.streaks().bestReportStreak()).isEqualTo(2);
        assertThat(view.streaks().goals()).singleElement().satisfies(goal -> {
            assertThat(goal.current()).isEqualTo(1);
            assertThat(goal.best()).isEqualTo(2);
        });
        assertThat(view.nearbyProgress()).extracting(ProgressItem::title)
                .contains("В ритме", "Первые 25", "Изумрудная неделя", "Мастер цели");
        assertThat(view.expedition().personalEnergy()).isEqualTo(15);
        assertThat(view.expedition().todayEnergy()).isEqualTo(8);
        assertThat(view.expedition().teamRoutePercent()).isEqualByComparingTo("24.5000");
    }

    @Test
    void cabinetAndReportAtomicallyConsumePendingAwardExactlyOnce() throws Exception {
        insertAward("GREEN_DAY", "MEDAL", today.toString(), null, "PENDING", now, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<GamificationCabinetView> cabinet = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return viewService.openCabinet(user);
            });
            Future<ReportGamificationService.ReportRewards> report = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return reportService.recalculateForReport(marathonId, 101L, today, now);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long visibleCount = cabinet.get(20, TimeUnit.SECONDS).newlyShown().size()
                    + report.get(20, TimeUnit.SECONDS).rewards().size();
            assertThat(visibleCount).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM reward_award WHERE notification_mode='PENDING' AND shown_at IS NULL",
                    Long.class)).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private void insertGoal(long id, String name, int position) {
        jdbcTemplate.update("""
                INSERT INTO goal(id,user_id,name,position,marathon_id,asked_count)
                VALUES (?,101,?,?,?,0)
                """, id, name, position, marathonId);
    }

    private void insertDay(LocalDate date, boolean reportDone, boolean goalDone) {
        jdbcTemplate.update("INSERT INTO history(created_at,goal_id,done) VALUES (?,?,?)",
                date.atTime(10, 0), 1000L, reportDone);
        jdbcTemplate.update("INSERT INTO history(created_at,goal_id,done) VALUES (?,?,?)",
                date.atTime(10, 0), 1001L, goalDone);
    }

    private void insertContribution(LocalDate date, int energy) {
        jdbcTemplate.update("""
                INSERT INTO expedition_contribution(marathon_id,user_id,activity_date,rule_version,report_on_time,
                    personal_day_off,personal_goal_count,completed_goal_count,calculated_energy,credited_energy,updated_at)
                VALUES (?,101,?,1,true,false,1,1,?,?,?)
                """, marathonId, date, energy, energy, now);
    }

    private void insertAward(
            String code,
            String kind,
            String occurrence,
            String goalName,
            String mode,
            LocalDateTime awardedAt,
            LocalDateTime shownAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO reward_award(marathon_id,user_id,reward_code,reward_kind,occurrence_key,rule_version,
                    goal_name_snapshot,awarded_at,earned_on,notification_mode,shown_at)
                VALUES (?,101,?,?,?,1,?,?,?,?,?)
                """, marathonId, code, kind, occurrence, goalName, awardedAt,
                awardedAt.toLocalDate(), mode, shownAt);
    }
}
