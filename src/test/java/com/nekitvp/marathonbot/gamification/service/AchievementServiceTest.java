package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.rule.RewardCode;
import com.nekitvp.marathonbot.gamification.rule.RewardEvaluationContext;
import com.nekitvp.marathonbot.gamification.support.PostgresTestContainerConfig;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
        com.nekitvp.marathonbot.gamification.rule.PersonalRewardEvaluator.class,
        com.nekitvp.marathonbot.gamification.rule.StabilityAndFinishRewardEvaluator.class,
        AchievementService.class
})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AchievementServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDateTime NOW = DATE.atTime(10, 30);

    @Autowired
    private AchievementService achievementService;

    @Autowired
    private MarathonRepository marathonRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long marathonId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        marathonId = insertMarathon("Экспедиция");
        insertUser(101L, "Алекс", marathonId);
        insertGoal(1001L, 101L, marathonId, "Отчёт", 0);
        insertGoal(1002L, 101L, marathonId, "Зарядка", 1);
        insertHistory(1001L, NOW, true);
        insertHistory(1002L, NOW, true);
    }

    @Test
    void firstEvaluationReturnsNewRewardsAndExactRepeatReturnsNothing() {
        MarathonEntity marathon = marathonRepository.findById(marathonId).orElseThrow();
        RewardEvaluationContext context = achievementService.buildContext(
                marathon,
                101L,
                RewardCatalog.RULES_VERSION_1,
                DATE,
                false
        );

        AchievementService.NewRewards first = achievementService.evaluateAndPersist(
                context,
                RewardNotificationMode.IMMEDIATE,
                NOW
        );
        AchievementService.NewRewards repeated = achievementService.evaluateAndPersist(
                context,
                RewardNotificationMode.IMMEDIATE,
                NOW.plusMinutes(1)
        );

        assertThat(first.rewards()).extracting(AchievementService.NewReward::code)
                .contains(RewardCode.GREEN_DAY, RewardCode.EARLY_START, RewardCode.FIRST_STEP);
        assertThat(repeated.rewards()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reward_award", Long.class))
                .isEqualTo(first.rewards().size());
    }

    @Test
    void historySnapshotIsScopedByMarathonAndUsesExplicitMoscowCalendarDates() {
        Long otherMarathon = insertMarathon("Другой");
        insertGoal(2001L, 101L, otherMarathon, "Чужая цель", 1);
        insertHistory(2001L, NOW, true);
        insertHistory(1002L, DATE.minusDays(1).atTime(23, 59, 59), false);

        RewardEvaluationContext context = achievementService.buildContext(
                marathonRepository.findById(marathonId).orElseThrow(),
                101L,
                1,
                DATE,
                false
        );

        assertThat(context.rulesZone().getId()).isEqualTo("Europe/Moscow");
        assertThat(context.days()).singleElement().satisfies(day -> {
            assertThat(day.activityDate()).isEqualTo(DATE);
            assertThat(day.personalGoals()).extracting(goal -> goal.goalName())
                    .containsExactly("Зарядка");
        });
    }

    @Test
    void completedReportGoalWithoutPersonalGoalsIsARealReportNotDayOff() {
        jdbcTemplate.update("DELETE FROM history WHERE goal_id=1002");
        jdbcTemplate.update("DELETE FROM goal WHERE id=1002");

        RewardEvaluationContext context = achievementService.buildContext(
                marathonRepository.findById(marathonId).orElseThrow(), 101L, 1, DATE, false);

        assertThat(context.days()).singleElement().satisfies(day -> {
            assertThat(day.personalDayOff()).isFalse();
            assertThat(day.reportSubmitted()).isTrue();
            assertThat(day.personalGoals()).isEmpty();
        });
    }

    @Test
    void concurrentEvaluationCanInsertEveryLogicalAwardOnlyOnce() throws Exception {
        MarathonEntity marathon = marathonRepository.findById(marathonId).orElseThrow();
        RewardEvaluationContext context = achievementService.buildContext(marathon, 101L, 1, DATE, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<AchievementService.NewRewards>> futures = List.of(
                    pool.submit(() -> raceEvaluation(context, ready, start)),
                    pool.submit(() -> raceEvaluation(context, ready, start))
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int returned = 0;
            for (Future<AchievementService.NewRewards> future : futures) {
                returned += future.get(20, TimeUnit.SECONDS).rewards().size();
            }

            long stored = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reward_award", Long.class);
            assertThat(returned).isEqualTo(stored);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT marathon_id,user_id,reward_code,occurrence_key,rule_version FROM reward_award GROUP BY 1,2,3,4,5) a",
                    Long.class
            )).isEqualTo(stored);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void notificationModesControlWhetherAnAwardRemainsPending() {
        RewardEvaluationContext context = achievementService.buildContext(
                marathonRepository.findById(marathonId).orElseThrow(), 101L, 1, DATE, false);

        achievementService.evaluateAndPersist(context, RewardNotificationMode.BACKFILL, NOW);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reward_award WHERE notification_mode = 'BACKFILL' AND shown_at IS NOT NULL",
                Long.class
        )).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reward_award WHERE shown_at IS NULL",
                Long.class
        )).isZero();
    }

    private AchievementService.NewRewards raceEvaluation(
            RewardEvaluationContext context,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("race did not start");
        }
        return achievementService.evaluateAndPersist(context, RewardNotificationMode.IMMEDIATE, NOW);
    }

    private void cleanDatabase() {
        for (String table : List.of("gamification_notification", "marathon_participation", "expedition_state",
                "expedition_contribution", "reward_award", "history", "goal", "marathon_manager", "users",
                "motivation", "marathon")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    private Long insertMarathon(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO marathon(name,date_start,date_end,gamification_enabled,status) VALUES (?, ?, ?, true, 'ACTIVE') RETURNING id",
                Long.class,
                name,
                DATE.atStartOfDay(),
                DATE.plusDays(30).atTime(23, 59)
        );
    }

    private void insertUser(Long id, String name, Long currentMarathonId) {
        jdbcTemplate.update(
                "INSERT INTO users(telegram_id,telegram_first_name,marathon_id,created_at,updated_at,state,admin,count_change_state,count_change_state_all) VALUES (?, ?, ?, ?, ?, 'START', false, 0, 0)",
                id, name, currentMarathonId, NOW, NOW
        );
    }

    private void insertGoal(Long id, Long userId, Long goalMarathonId, String name, int position) {
        jdbcTemplate.update(
                "INSERT INTO goal(id,user_id,name,position,marathon_id,asked_count) VALUES (?, ?, ?, ?, ?, 0)",
                id, userId, name, position, goalMarathonId
        );
    }

    private void insertHistory(Long goalId, LocalDateTime at, Boolean done) {
        jdbcTemplate.update("INSERT INTO history(created_at,goal_id,done) VALUES (?, ?, ?)", at, goalId, done);
    }
}
