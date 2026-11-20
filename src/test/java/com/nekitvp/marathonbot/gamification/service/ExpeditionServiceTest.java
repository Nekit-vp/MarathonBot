package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.model.ExpeditionContributionEntity;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.rule.ExpeditionCalculator;
import com.nekitvp.marathonbot.gamification.rule.PersonalRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.rule.TeamRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.UserDaySnapshot;
import com.nekitvp.marathonbot.gamification.support.PostgresTestContainerConfig;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, RewardCatalog.class, PersonalRewardEvaluator.class,
        com.nekitvp.marathonbot.gamification.rule.StabilityAndFinishRewardEvaluator.class,
        ExpeditionCalculator.class, TeamRewardEvaluator.class, AchievementService.class, ExpeditionService.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExpeditionServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDateTime NOW = DATE.atTime(20, 0);

    @Autowired
    private ExpeditionService expeditionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long marathonId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        marathonId = insertMarathon();
        insertUser(101L);
        insertState(4);
    }

    @Test
    void dailyUpsertKeepsCreditedEnergyMonotonicWhileCurrentFactsCanBeCorrected() {
        ExpeditionContributionEntity first = expeditionService.upsertContribution(
                marathonId, 101L, DATE, 1, greenDay(DATE), NOW);
        ExpeditionContributionEntity corrected = expeditionService.upsertContribution(
                marathonId, 101L, DATE, 1, failedDay(DATE), NOW.plusMinutes(1));

        assertThat(first.getCalculatedEnergy()).isEqualTo(10);
        assertThat(corrected.getCalculatedEnergy()).isZero();
        assertThat(corrected.getCreditedEnergy()).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expedition_contribution", Long.class)).isOne();
    }

    @Test
    void repeatedTeamCalculationKeepsRouteAndCheckpointAwardsMonotonic() {
        expeditionService.upsertContribution(marathonId, 101L, DATE, 1, greenDay(DATE), NOW);
        List<TeamRewardEvaluator.TeamDaySnapshot> days = List.of(teamDay(DATE, 10));

        ExpeditionService.TeamUpdate first = expeditionService.recalculateTeam(
                marathonId, 1, DATE, days, RewardNotificationMode.IMMEDIATE, 101L, NOW);
        expeditionService.upsertContribution(marathonId, 101L, DATE, 1, failedDay(DATE), NOW.plusMinutes(1));
        ExpeditionService.TeamUpdate repeated = expeditionService.recalculateTeam(
                marathonId, 1, DATE, List.of(teamDay(DATE, 0)), RewardNotificationMode.IMMEDIATE, 101L,
                NOW.plusMinutes(2));

        assertThat(first.distance()).isEqualByComparingTo("31.25");
        assertThat(first.lastAnnouncedCheckpoint()).isZero();
        assertThat(first.newRewards().rewards()).extracting(AchievementService.NewReward::code)
                .contains(com.nekitvp.marathonbot.gamification.rule.RewardCode.EXPEDITION_25);
        assertThat(repeated.distance()).isEqualByComparingTo(first.distance());
        assertThat(repeated.lastAnnouncedCheckpoint()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reward_award WHERE reward_code='EXPEDITION_25'", Long.class)).isOne();
    }

    @Test
    void stateLockPreventsConcurrentCheckpointAwardsAndAdvancement() throws Exception {
        expeditionService.upsertContribution(marathonId, 101L, DATE, 1, greenDay(DATE), NOW);
        List<TeamRewardEvaluator.TeamDaySnapshot> days = List.of(teamDay(DATE, 10));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<ExpeditionService.TeamUpdate> one = pool.submit(() -> race(days, ready, start));
            Future<ExpeditionService.TeamUpdate> two = pool.submit(() -> race(days, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int returnedCheckpoints = (int) List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))
                    .stream().flatMap(result -> result.newRewards().rewards().stream())
                    .filter(reward -> reward.code() == com.nekitvp.marathonbot.gamification.rule.RewardCode.EXPEDITION_25)
                    .count();

            assertThat(returnedCheckpoints).isOne();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM reward_award WHERE reward_code='EXPEDITION_25'", Long.class)).isOne();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT last_announced_checkpoint FROM expedition_state WHERE marathon_id=?", Integer.class,
                    marathonId)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private ExpeditionService.TeamUpdate race(
            List<TeamRewardEvaluator.TeamDaySnapshot> days,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("race did not start");
        }
        return expeditionService.recalculateTeam(
                marathonId, 1, DATE, days, RewardNotificationMode.IMMEDIATE, 101L, NOW);
    }

    private UserDaySnapshot greenDay(LocalDate date) {
        return new UserDaySnapshot(date, false, false,
                new UserDaySnapshot.ReportGoalSnapshot(true, true, LocalTime.of(10, 0)),
                List.of(new UserDaySnapshot.PersonalGoalSnapshot(1001L, "Цель", true)));
    }

    private UserDaySnapshot failedDay(LocalDate date) {
        return new UserDaySnapshot(date, false, false, UserDaySnapshot.ReportGoalSnapshot.absent(),
                List.of(new UserDaySnapshot.PersonalGoalSnapshot(1001L, "Цель", false)));
    }

    private TeamRewardEvaluator.TeamDaySnapshot teamDay(LocalDate date, int energy) {
        return new TeamRewardEvaluator.TeamDaySnapshot(date, false, List.of(
                new TeamRewardEvaluator.ParticipantDaySnapshot(
                        101L, energy > 0, energy > 0, false, 1, energy > 0 ? 1 : 0, energy)));
    }

    private void cleanDatabase() {
        for (String table : List.of("gamification_notification", "marathon_participation", "expedition_state",
                "expedition_contribution", "reward_award", "history", "goal", "marathon_manager", "users",
                "motivation", "marathon")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    private Long insertMarathon() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO marathon(name,date_start,date_end,gamification_enabled,status) VALUES ('Тест',?,?,true,'ACTIVE') RETURNING id",
                Long.class, DATE.atStartOfDay(), DATE.plusDays(3).atTime(23, 59));
    }

    private void insertUser(Long id) {
        jdbcTemplate.update(
                "INSERT INTO users(telegram_id,telegram_first_name,marathon_id,created_at,updated_at,state,admin,count_change_state,count_change_state_all) VALUES (?,'Алекс',?,?,?,?,false,0,0)",
                id, marathonId, NOW, NOW, "START");
    }

    private void insertState(int plannedDays) {
        jdbcTemplate.update("""
                INSERT INTO expedition_state(
                    marathon_id,rule_version,activated_at,route_start_date,route_end_date,
                    rest_on_saturday,rest_on_sunday,planned_active_days,distance,last_announced_checkpoint,version
                ) VALUES (?,1,?,?,?,?,?,?,0,0,0)
                """, marathonId, NOW, DATE, DATE.plusDays(3), false, false, plannedDays);
    }
}
