package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, RewardCatalog.class, ReportGamificationService.class,
        com.nekitvp.marathonbot.gamification.rule.PersonalRewardEvaluator.class,
        com.nekitvp.marathonbot.gamification.rule.StabilityAndFinishRewardEvaluator.class,
        AchievementService.class,
        com.nekitvp.marathonbot.service.TemplatePhraseService.class, GamificationMessageComposer.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RecordApplicationEvents
class ReportGamificationServiceIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDateTime NOW = DATE.atTime(10, 30);

    @Autowired private ReportGamificationService reportGamificationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private GamificationService gamificationService;
    @Autowired private ApplicationEvents applicationEvents;

    private Long marathonId;

    @BeforeEach
    void setUp() {
        for (String table : List.of("gamification_notification", "marathon_participation", "expedition_state",
                "expedition_contribution", "reward_award", "history", "goal", "marathon_manager", "users",
                "motivation", "marathon")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        marathonId = insertMarathon(true);
        insertUser();
    }

    @Test
    void atomicallyReturnsNewAndOlderPendingRewardsAndMarksPendingShown() {
        insertPending("STRONG_DAY", "2026-07-31");
        insertPending("FIRST_STEP", "REPORT_SERIES:1");
        insertPending("GREEN_DAY", DATE.toString());
        when(gamificationService.recalculate(any())).thenReturn(AchievementService.NewRewards.empty());

        ReportGamificationService.ReportRewards result = reportGamificationService.recalculateForReport(
                marathonId, 101L, DATE, NOW);

        assertThat(result.rewards()).extracting(ReportGamificationService.VisibleReward::title)
                .containsExactly("Крепкий день", "Первый шаг", "Зелёный день");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reward_award WHERE notification_mode='PENDING' AND shown_at IS NULL",
                Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reward_award WHERE notification_mode='PENDING' AND shown_at IS NOT NULL",
                Long.class)).isEqualTo(3L);
    }

    @Test
    void unavailableImmediateCalculationStillConsumesPreviouslyQueuedReward() {
        insertPending("FIRST_STEP", "REPORT_SERIES:1");
        when(gamificationService.recalculate(any())).thenThrow(new IllegalStateException("state missing"));

        ReportGamificationService.ReportRewards result = reportGamificationService.recalculateForReport(
                marathonId, 101L, DATE, NOW);

        assertThat(result.rewards()).extracting(ReportGamificationService.VisibleReward::title)
                .containsExactly("Первый шаг");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reward_award WHERE shown_at IS NULL", Long.class)).isZero();
        assertThat(applicationEvents.stream(HistoryMutationRecalculationEvent.class)).singleElement()
                .satisfies(event -> {
                    assertThat(event.marathonId()).isEqualTo(marathonId);
                    assertThat(event.userId()).isEqualTo(101L);
                    assertThat(event.activityDate()).isEqualTo(DATE);
                    assertThat(event.marathonFinished()).isFalse();
                });
    }

    @Test
    void disabledGamificationDoesNotCalculateConsumeOrWriteAwardsAndContributions() {
        jdbcTemplate.update("UPDATE marathon SET gamification_enabled=false WHERE id=?", marathonId);

        ReportGamificationService.ReportRewards result = reportGamificationService.recalculateForReport(
                marathonId, 101L, DATE, NOW);

        assertThat(result.rewards()).isEmpty();
        verify(gamificationService, never()).recalculate(any());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reward_award", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expedition_contribution", Long.class)).isZero();
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

    private void insertPending(String code, String occurrenceKey) {
        jdbcTemplate.update("""
                INSERT INTO reward_award(
                    marathon_id,user_id,reward_code,reward_kind,occurrence_key,rule_version,
                    awarded_at,earned_on,notification_mode,shown_at
                ) VALUES (?,?,?,?,?,1,?,?,'PENDING',NULL)
                """, marathonId, 101L, code,
                code.equals("FIRST_STEP") ? "ACHIEVEMENT" : "MEDAL", occurrenceKey,
                NOW.minusDays(1), DATE.minusDays(1));
    }
}
