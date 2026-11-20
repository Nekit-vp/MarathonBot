package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.rule.ExpeditionCalculator;
import com.nekitvp.marathonbot.gamification.rule.PersonalRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.rule.RewardCode;
import com.nekitvp.marathonbot.gamification.rule.TeamRewardEvaluator;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, RewardCatalog.class, PersonalRewardEvaluator.class,
        com.nekitvp.marathonbot.gamification.rule.StabilityAndFinishRewardEvaluator.class,
        ExpeditionCalculator.class, TeamRewardEvaluator.class, AchievementService.class,
        ExpeditionService.class, GamificationService.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GamificationServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDateTime NOW = DATE.atTime(10, 30);

    @Autowired
    private GamificationService gamificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long marathonId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        marathonId = insertMarathon(true);
        insertUser();
        insertGoal(1001L, "Отчёт", 0);
        insertGoal(1002L, "Зарядка", 1);
        insertHistory(1001L, true);
        insertHistory(1002L, true);
        insertState();
        jdbcTemplate.update(
                "INSERT INTO marathon_participation(marathon_id,user_id,joined_at) VALUES (?,?,?)",
                marathonId, 101L, DATE.atStartOfDay());
    }

    @Test
    void immediateRecalculationReturnsOnlyPersonalRewardsAndDefersTeamContribution() {
        GamificationService.RecalculationRequest request = request(RewardNotificationMode.IMMEDIATE);

        AchievementService.NewRewards first = gamificationService.recalculate(request);
        AchievementService.NewRewards repeated = gamificationService.recalculate(request);

        assertThat(first.rewards()).extracting(AchievementService.NewReward::code)
                .contains(RewardCode.GREEN_DAY, RewardCode.FIRST_STEP);
        assertThat(repeated.rewards()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expedition_contribution", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reward_award WHERE notification_mode='IMMEDIATE' AND shown_at IS NOT NULL",
                Long.class)).isPositive();
    }

    @Test
    void pendingModeLeavesNewAwardsForTheNextInteractiveConfirmation() {
        AchievementService.NewRewards rewards = gamificationService.recalculate(
                request(RewardNotificationMode.PENDING));

        assertThat(rewards.rewards()).isNotEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reward_award WHERE notification_mode='PENDING' AND shown_at IS NULL",
                Long.class)).isEqualTo(rewards.rewards().size());
    }

    @Test
    void disabledGamificationWritesNothing() {
        jdbcTemplate.update("UPDATE marathon SET gamification_enabled=false WHERE id=?", marathonId);

        AchievementService.NewRewards rewards = gamificationService.recalculate(request(RewardNotificationMode.IMMEDIATE));

        assertThat(rewards.rewards()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reward_award", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expedition_contribution", Long.class)).isZero();
    }

    @Test
    void safeRecalculationDoesNotInvalidateSavedHistoryWhenGameStateIsDamaged() {
        jdbcTemplate.update("DELETE FROM expedition_state WHERE marathon_id=?", marathonId);
        long savedHistories = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM history", Long.class);

        AchievementService.NewRewards rewards = gamificationService.recalculateSafely(
                request(RewardNotificationMode.IMMEDIATE));

        assertThat(rewards.rewards()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM history", Long.class)).isEqualTo(savedHistories);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reward_award", Long.class)).isZero();
    }

    private GamificationService.RecalculationRequest request(RewardNotificationMode mode) {
        return new GamificationService.RecalculationRequest(marathonId, 101L, 1, DATE, NOW, mode, false);
    }

    private void cleanDatabase() {
        for (String table : List.of("gamification_notification", "marathon_participation", "expedition_state",
                "expedition_contribution", "reward_award", "history", "goal", "marathon_manager", "users",
                "motivation", "marathon")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    private Long insertMarathon(boolean enabled) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO marathon(name,date_start,date_end,gamification_enabled,status) VALUES ('Тест',?,?,?,'ACTIVE') RETURNING id",
                Long.class, DATE.atStartOfDay(), DATE.plusDays(3).atTime(23, 59), enabled);
    }

    private void insertUser() {
        jdbcTemplate.update(
                "INSERT INTO users(telegram_id,telegram_first_name,marathon_id,created_at,updated_at,state,admin,count_change_state,count_change_state_all) VALUES (101,'Алекс',?,?,?,'START',false,0,0)",
                marathonId, NOW, NOW);
    }

    private void insertGoal(Long id, String name, int position) {
        jdbcTemplate.update(
                "INSERT INTO goal(id,user_id,name,position,marathon_id,asked_count) VALUES (?,101,?,?,?,0)",
                id, name, position, marathonId);
    }

    private void insertHistory(Long goalId, boolean done) {
        jdbcTemplate.update("INSERT INTO history(created_at,goal_id,done) VALUES (?,?,?)", NOW, goalId, done);
    }

    private void insertState() {
        jdbcTemplate.update("""
                INSERT INTO expedition_state(
                    marathon_id,rule_version,activated_at,route_start_date,route_end_date,
                    rest_on_saturday,rest_on_sunday,planned_active_days,distance,last_announced_checkpoint,version
                ) VALUES (?,1,?,?,?,?,?,?,0,0,0)
                """, marathonId, NOW, DATE, DATE.plusDays(3), false, false, 4);
    }
}
