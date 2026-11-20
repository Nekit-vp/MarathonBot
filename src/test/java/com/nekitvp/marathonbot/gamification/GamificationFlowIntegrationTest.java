package com.nekitvp.marathonbot.gamification;

import com.nekitvp.marathonbot.bot.TelegramBot;
import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationType;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.repository.GamificationNotificationRepository;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.gamification.rule.ExpeditionCalculator;
import com.nekitvp.marathonbot.gamification.rule.PersonalRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.rule.RewardCode;
import com.nekitvp.marathonbot.gamification.rule.StabilityAndFinishRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.TeamRewardEvaluator;
import com.nekitvp.marathonbot.gamification.service.AchievementService;
import com.nekitvp.marathonbot.gamification.service.ExpeditionService;
import com.nekitvp.marathonbot.gamification.service.GamificationActivationService;
import com.nekitvp.marathonbot.gamification.service.GamificationMessageComposer;
import com.nekitvp.marathonbot.gamification.service.GamificationNotificationService;
import com.nekitvp.marathonbot.gamification.service.GamificationSchedulingService;
import com.nekitvp.marathonbot.gamification.service.GamificationService;
import com.nekitvp.marathonbot.gamification.service.GamificationViewService;
import com.nekitvp.marathonbot.gamification.service.MarathonParticipationService;
import com.nekitvp.marathonbot.gamification.service.ReportGamificationService;
import com.nekitvp.marathonbot.gamification.support.GamificationTestFixture;
import com.nekitvp.marathonbot.gamification.support.PostgresTestContainerConfig;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.repository.UserRepository;
import com.nekitvp.marathonbot.service.LetterSender;
import com.nekitvp.marathonbot.service.TemplatePhraseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PostgresTestContainerConfig.class,
        RewardCatalog.class,
        PersonalRewardEvaluator.class,
        StabilityAndFinishRewardEvaluator.class,
        ExpeditionCalculator.class,
        TeamRewardEvaluator.class,
        AchievementService.class,
        ExpeditionService.class,
        GamificationService.class,
        MarathonParticipationService.class,
        GamificationActivationService.class,
        GamificationViewService.class,
        ReportGamificationService.class,
        TemplatePhraseService.class,
        GamificationMessageComposer.class,
        GamificationNotificationService.class,
        GamificationSchedulingService.class
})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GamificationFlowIntegrationTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 3);
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MarathonRepository marathonRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private GamificationActivationService activationService;
    @Autowired private GamificationService gamificationService;
    @Autowired private ReportGamificationService reportGamificationService;
    @Autowired private GamificationViewService viewService;
    @Autowired private GamificationSchedulingService schedulingService;
    @Autowired private GamificationNotificationRepository notificationRepository;
    @Autowired private ExpeditionStateRepository expeditionStateRepository;
    @Autowired private MarathonParticipationService participationService;
    @MockBean private LetterSender letterSender;
    @MockBean private TelegramBot telegramBot;

    private GamificationTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new GamificationTestFixture(jdbc);
        fixture.clean();
    }

    @Test
    void managerEnableSilentlyBackfillsQueuesOneLaunchAndOpensParticipantCabinet() {
        LocalDate today = LocalDate.now(MOSCOW);
        Long marathonId = fixture.marathon("Тихий запуск", today.minusDays(2), today.plusDays(6),
                false, false, false, MarathonStatus.ACTIVE);
        fixture.user(101L, "Алекс", marathonId, today.minusDays(3).atStartOfDay());
        fixture.goal(1_010L, 101L, marathonId, "Отчёт", 0);
        fixture.goal(1_011L, 101L, marathonId, "Зарядка", 1);
        fixture.report(1_010L, List.of(1_011L), today.minusDays(1), LocalTime.of(9, 30), true);

        GamificationActivationService.ActivationResult first = activationService.enable(marathonId);
        GamificationActivationService.ActivationResult replay = activationService.enable(marathonId);

        UserEntity user = userRepository.findById(101L).orElseThrow();
        GamificationCabinetView cabinet = viewService.getCabinet(user);

        assertThat(first.firstActivation()).isTrue();
        assertThat(first.launchRequired()).isTrue();
        assertThat(replay.launchRequired()).isFalse();
        assertThat(fixture.count("SELECT COUNT(*) FROM reward_award WHERE notification_mode <> 'BACKFILL'"))
                .isZero();
        assertThat(fixture.count("SELECT COUNT(*) FROM gamification_notification WHERE notification_type='LAUNCH'"))
                .isOne();
        assertThat(jdbc.queryForObject("""
                SELECT payload FROM gamification_notification
                WHERE marathon_id=? AND notification_type='LAUNCH'
                """, String.class, marathonId)).contains("MarathonBot 7\\.12", "Тихий запуск");
        assertThat(cabinet.medals()).extracting(GamificationCabinetView.MedalItem::title)
                .contains("Зелёный день", "Ранний старт");
        assertThat(cabinet.achievements()).extracting(GamificationCabinetView.AchievementItem::title)
                .contains("Первый шаг");
    }

    @Test
    void eightPersonMarathonHandlesNormalGreenMissingLateRestAndPersonalDayOffDays() {
        TeamFixture team = createEightPersonTeam("Восемь сценариев", MONDAY, MONDAY.plusDays(6), 5, true, true);
        for (long userId : team.userIds()) {
            fixture.report(reportGoal(userId), List.of(personalGoal(userId)), MONDAY, LocalTime.of(10, 0),
                    userId != team.userIds().getFirst());
            fixture.report(reportGoal(userId), List.of(personalGoal(userId)), MONDAY.plusDays(1),
                    LocalTime.of(13, 0), true);
        }
        fixture.lateReport(reportGoal(1_002L), List.of(personalGoal(1_002L)), MONDAY.plusDays(2));
        fixture.personalDayOff(reportGoal(1_003L), MONDAY.plusDays(2));
        for (long userId = 1_004L; userId <= 1_008L; userId++) {
            fixture.report(reportGoal(userId), List.of(personalGoal(userId)), MONDAY.plusDays(2),
                    LocalTime.of(20, 0), true);
        }

        for (LocalDate date : List.of(MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2), MONDAY.plusDays(5))) {
            for (long userId : team.userIds()) {
                recalculate(team.marathonId(), userId, date, false, RewardNotificationMode.PENDING);
            }
        }

        assertThat(fixture.count("SELECT COUNT(*) FROM expedition_contribution WHERE marathon_id=?",
                team.marathonId())).isEqualTo(40);
        assertThat(energy(team.marathonId(), 1_001L, MONDAY)).isEqualTo(2);
        assertThat(energy(team.marathonId(), 1_001L, MONDAY.plusDays(2))).isZero();
        assertThat(energy(team.marathonId(), 1_002L, MONDAY.plusDays(2))).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT personal_day_off FROM expedition_contribution
                WHERE marathon_id=? AND user_id=1003 AND activity_date=?
                """, Boolean.class, team.marathonId(), MONDAY.plusDays(2))).isTrue();
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM expedition_contribution
                WHERE marathon_id=? AND activity_date=?
                """, team.marathonId(), MONDAY.plusDays(5))).isZero();
    }

    @Test
    void reportConfirmationBundlesMultipleMedalsAndAchievementsIntoOneBlock() {
        Long marathonId = singleUserMarathon("Один блок", MONDAY, MONDAY.plusDays(2), 3, 201L);
        for (int offset = 0; offset < 3; offset++) {
            fixture.report(reportGoal(201L), List.of(personalGoal(201L)), MONDAY.plusDays(offset),
                    LocalTime.of(9, 0), true);
        }

        ReportGamificationService.ReportRewards rewards = reportGamificationService.recalculateForReport(
                marathonId, 201L, MONDAY.plusDays(2), MONDAY.plusDays(2).atTime(9, 5));
        String confirmation = rewards.appendTo("Отчёт принят ✅");

        assertThat(rewards.rewards().size()).isGreaterThanOrEqualTo(6);
        assertThat(rewards.rewards()).extracting(ReportGamificationService.VisibleReward::title)
                .contains("Зелёный день", "Ранний старт", "Первый шаг", "В ритме");
        assertThat(confirmation).contains("Отчёт принят", "🏅 Новые награды:", "Зелёный день", "В ритме");
        assertThat(countOccurrences(confirmation, "🏅 Новые награды:")).isOne();
    }

    @Test
    void expeditionCrossesAllCheckpointsAndAwardsEachParticipantExactlyOnce() {
        TeamFixture team = createEightPersonTeam("Четыре лагеря", MONDAY, MONDAY.plusDays(3), 4, false, false);

        for (int offset = 0; offset < 4; offset++) {
            LocalDate date = MONDAY.plusDays(offset);
            for (long userId : team.userIds()) {
                fixture.report(reportGoal(userId), List.of(personalGoal(userId)), date, LocalTime.of(10, 0), true);
            }
            recalculate(team.marathonId(), team.userIds().getFirst(), date, false, RewardNotificationMode.PENDING);
        }

        for (RewardCode checkpoint : List.of(RewardCode.EXPEDITION_25, RewardCode.EXPEDITION_50,
                RewardCode.EXPEDITION_75, RewardCode.EXPEDITION_100)) {
            assertThat(fixture.count("SELECT COUNT(*) FROM reward_award WHERE marathon_id=? AND reward_code=?",
                    team.marathonId(), checkpoint.name())).isEqualTo(8);
            assertThat(fixture.count("""
                    SELECT COUNT(DISTINCT user_id) FROM reward_award
                    WHERE marathon_id=? AND reward_code=?
                    """, team.marathonId(), checkpoint.name())).isEqualTo(8);
        }
        assertThat(jdbc.queryForObject("SELECT distance FROM expedition_state WHERE marathon_id=?",
                java.math.BigDecimal.class, team.marathonId())).isEqualByComparingTo("100");
    }

    @Test
    void duplicateSchedulersRestartAndParallelReportsCannotDuplicateAwardsOrMessages() throws Exception {
        Long reportMarathon = singleUserMarathon("Параллельный отчёт", MONDAY, MONDAY.plusDays(3), 4, 301L);
        fixture.report(reportGoal(301L), List.of(personalGoal(301L)), MONDAY, LocalTime.of(10, 0), true);
        raceReports(reportMarathon, 301L, MONDAY);

        Long scheduledMarathon = singleUserMarathon("Повтор планировщика", MONDAY, MONDAY.plusDays(3), 4, 302L);
        fixture.report(reportGoal(302L), List.of(personalGoal(302L)), MONDAY, LocalTime.of(10, 0), true);
        LocalDateTime schedulerRun = MONDAY.plusDays(1).atTime(0, 5);
        schedulingService.reconcileAfterMidnight(schedulerRun);
        schedulingService.reconcileAfterMidnight(schedulerRun);

        GamificationNotificationService restarted = new GamificationNotificationService(
                notificationRepository, jdbc, letterSender, telegramBot, expeditionStateRepository);
        restarted.enqueueDailyHighlight(scheduledMarathon, schedulerRun.toLocalDate(), -100_000L,
                "payload after restart", schedulerRun.plusMinutes(1));

        assertThat(fixture.count("""
                SELECT COUNT(*) FROM (
                    SELECT marathon_id,user_id,reward_code,occurrence_key,rule_version
                    FROM reward_award GROUP BY 1,2,3,4,5 HAVING COUNT(*) > 1
                ) duplicates
                """)).isZero();
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM gamification_notification
                WHERE marathon_id=? AND notification_type='DAILY_HIGHLIGHT' AND occurrence_key=?
                """, scheduledMarathon, schedulerRun.toLocalDate().toString())).isOne();
    }

    @Test
    void movingUserBetweenMarathonsKeepsHistoriesContributionsAndCabinetsScoped() {
        LocalDate firstDate = MONDAY;
        LocalDate secondDate = MONDAY.plusDays(1);
        Long first = fixture.marathon("Первый маршрут", firstDate, firstDate.plusDays(3), true,
                false, false, MarathonStatus.ACTIVE);
        Long second = fixture.marathon("Второй маршрут", secondDate, secondDate.plusDays(3), true,
                false, false, MarathonStatus.ACTIVE);
        fixture.user(401L, "Путешественник", first, firstDate.minusDays(1).atStartOfDay());
        fixture.goal(4_010L, 401L, first, "Отчёт A", 0);
        fixture.goal(4_011L, 401L, first, "Цель A", 1);
        fixture.expedition(first, firstDate, firstDate.plusDays(3), 4, false, false);
        fixture.expedition(second, secondDate, secondDate.plusDays(3), 4, false, false);
        fixture.participation(first, 401L, firstDate.atStartOfDay());
        fixture.report(4_010L, List.of(4_011L), firstDate, LocalTime.of(10, 0), true);
        recalculate(first, 401L, firstDate, false, RewardNotificationMode.IMMEDIATE);

        LocalDateTime switchTime = secondDate.atStartOfDay();
        participationService.switchMarathon(first, second, 401L, switchTime);
        fixture.moveUser(401L, second, switchTime);
        fixture.goal(4_020L, 401L, second, "Отчёт B", 0);
        fixture.goal(4_021L, 401L, second, "Цель B", 1);
        fixture.report(4_020L, List.of(4_021L), secondDate, LocalTime.of(13, 0), true);
        recalculate(second, 401L, secondDate, false, RewardNotificationMode.IMMEDIATE);

        GamificationCabinetView cabinet = viewService.getCabinet(userRepository.findById(401L).orElseThrow());

        assertThat(fixture.count("SELECT COUNT(*) FROM reward_award WHERE marathon_id=? AND user_id=401", first))
                .isPositive();
        assertThat(fixture.count("SELECT COUNT(*) FROM reward_award WHERE marathon_id=? AND user_id=401", second))
                .isPositive();
        assertThat(fixture.count("SELECT COUNT(*) FROM expedition_contribution WHERE marathon_id=?", first)).isOne();
        assertThat(fixture.count("SELECT COUNT(*) FROM expedition_contribution WHERE marathon_id=?", second)).isOne();
        assertThat(cabinet.marathonName()).isEqualTo("Второй маршрут");
        assertThat(cabinet.medals()).extracting(GamificationCabinetView.MedalItem::title)
                .contains("Без напоминания")
                .doesNotContain("Ранний старт");
    }

    @Test
    void earnedGoalSnapshotAndCreditedDistanceSurviveRenameArchiveAndManagerCorrection() {
        Long marathonId = singleUserMarathon("Неотзывные награды", MONDAY, MONDAY.plusDays(6), 7, 501L);
        List<Long> personalHistoryIds = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = MONDAY.plusDays(offset);
            fixture.history(reportGoal(501L), date, LocalTime.of(10, 0), true);
            personalHistoryIds.add(fixture.history(personalGoal(501L), date, LocalTime.of(10, 0), true));
        }
        recalculate(marathonId, 501L, MONDAY.plusDays(6), false, RewardNotificationMode.IMMEDIATE);
        int creditedBefore = creditedEnergy(marathonId, 501L, MONDAY.plusDays(6));

        fixture.renameGoal(personalGoal(501L), "Новое имя");
        fixture.archiveGoal(personalGoal(501L), MONDAY.plusDays(7).atStartOfDay());
        fixture.correctHistory(personalHistoryIds.getLast(), false);
        recalculate(marathonId, 501L, MONDAY.plusDays(6), false, RewardNotificationMode.IMMEDIATE);

        assertThat(fixture.count("""
                SELECT COUNT(*) FROM reward_award
                WHERE marathon_id=? AND user_id=501 AND reward_code='GOAL_STREAK_7'
                """, marathonId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT goal_name_snapshot FROM reward_award
                WHERE marathon_id=? AND user_id=501 AND reward_code='GOAL_STREAK_7'
                """, String.class, marathonId)).isEqualTo("Цель 501");
        assertThat(fixture.count("SELECT COUNT(*) FROM goal WHERE id=? AND archived_at IS NULL",
                personalGoal(501L))).isZero();
        assertThat(creditedEnergy(marathonId, 501L, MONDAY.plusDays(6))).isEqualTo(creditedBefore);
        assertThat(energy(marathonId, 501L, MONDAY.plusDays(6))).isEqualTo(2);
    }

    @Test
    void finishedMarathonAwardsFinalAchievementsAndCabinetRemainsReadOnly() {
        Long marathonId = singleUserMarathon("Финиш", MONDAY, MONDAY.plusDays(4), 5, 601L);
        for (int offset = 0; offset < 5; offset++) {
            fixture.report(reportGoal(601L), List.of(personalGoal(601L)), MONDAY.plusDays(offset),
                    LocalTime.of(10, 0), true);
        }
        MarathonEntity marathon = marathonRepository.findById(marathonId).orElseThrow();
        schedulingService.reconcileBeforeFinish(
                marathon, MONDAY.plusDays(4), MONDAY.plusDays(5).atStartOfDay());
        jdbc.update("UPDATE marathon SET status='FINISHED' WHERE id=?", marathonId);
        UserEntity user = userRepository.findById(601L).orElseThrow();
        long awardsBefore = fixture.count("SELECT COUNT(*) FROM reward_award WHERE marathon_id=?", marathonId);
        long contributionsBefore = fixture.count(
                "SELECT COUNT(*) FROM expedition_contribution WHERE marathon_id=?", marathonId);

        GamificationCabinetView first = viewService.getCabinet(user);
        GamificationCabinetView second = viewService.getCabinet(userRepository.findById(601L).orElseThrow());

        assertThat(first.finished()).isTrue();
        assertThat(first.achievements()).extracting(GamificationCabinetView.AchievementItem::title)
                .contains("Всю дистанцию", "Дожила — и дожала!");
        assertThat(second).isEqualTo(first);
        assertThat(fixture.count("SELECT COUNT(*) FROM reward_award WHERE marathon_id=?", marathonId))
                .isEqualTo(awardsBefore);
        assertThat(fixture.count("SELECT COUNT(*) FROM expedition_contribution WHERE marathon_id=?", marathonId))
                .isEqualTo(contributionsBefore);
    }

    private TeamFixture createEightPersonTeam(
            String name,
            LocalDate start,
            LocalDate end,
            int plannedDays,
            boolean restSaturday,
            boolean restSunday
    ) {
        Long marathonId = fixture.marathon(name, start, end, true, restSaturday, restSunday, MarathonStatus.ACTIVE);
        List<Long> users = java.util.stream.LongStream.rangeClosed(1_001L, 1_008L).boxed().toList();
        users.forEach(userId -> {
            fixture.user(userId, "Участник " + userId, marathonId, start.minusDays(1).atStartOfDay());
            fixture.goal(reportGoal(userId), userId, marathonId, "Отчёт", 0);
            fixture.goal(personalGoal(userId), userId, marathonId, "Цель " + userId, 1);
            fixture.participation(marathonId, userId, start.atStartOfDay());
        });
        fixture.expedition(marathonId, start, end, plannedDays, restSaturday, restSunday);
        return new TeamFixture(marathonId, users);
    }

    private Long singleUserMarathon(String name, LocalDate start, LocalDate end, int plannedDays, long userId) {
        Long marathonId = fixture.marathon(name, start, end, true, false, false, MarathonStatus.ACTIVE);
        fixture.user(userId, "Участник " + userId, marathonId, start.minusDays(1).atStartOfDay());
        fixture.goal(reportGoal(userId), userId, marathonId, "Отчёт", 0);
        fixture.goal(personalGoal(userId), userId, marathonId, "Цель " + userId, 1);
        fixture.participation(marathonId, userId, start.atStartOfDay());
        fixture.expedition(marathonId, start, end, plannedDays, false, false);
        return marathonId;
    }

    private void recalculate(
            Long marathonId,
            long userId,
            LocalDate date,
            boolean finished,
            RewardNotificationMode mode
    ) {
        gamificationService.recalculate(new GamificationService.RecalculationRequest(
                marathonId, userId, RewardCatalog.RULES_VERSION_1, date, date.atTime(21, 0), mode, finished, true));
    }

    private void raceReports(Long marathonId, long userId, LocalDate date) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ReportGamificationService.ReportRewards>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(ignored -> pool.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("parallel reports did not start");
                        }
                        return reportGamificationService.recalculateForReport(
                                marathonId, userId, date, date.atTime(10, 5));
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<ReportGamificationService.ReportRewards> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private int energy(Long marathonId, long userId, LocalDate date) {
        return jdbc.queryForObject("""
                SELECT calculated_energy FROM expedition_contribution
                WHERE marathon_id=? AND user_id=? AND activity_date=?
                """, Integer.class, marathonId, userId, date);
    }

    private int creditedEnergy(Long marathonId, long userId, LocalDate date) {
        return jdbc.queryForObject("""
                SELECT credited_energy FROM expedition_contribution
                WHERE marathon_id=? AND user_id=? AND activity_date=?
                """, Integer.class, marathonId, userId, date);
    }

    private static long reportGoal(long userId) {
        return userId * 10;
    }

    private static long personalGoal(long userId) {
        return userId * 10 + 1;
    }

    private static int countOccurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }

    private record TeamFixture(Long marathonId, List<Long> userIds) {
    }
}
