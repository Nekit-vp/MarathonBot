package com.nekitvp.marathonbot.gamification.repository;

import com.nekitvp.marathonbot.gamification.model.ExpeditionContributionEntity;
import com.nekitvp.marathonbot.gamification.model.ExpeditionStateEntity;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationEntity;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationStatus;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationType;
import com.nekitvp.marathonbot.gamification.model.MarathonParticipationEntity;
import com.nekitvp.marathonbot.gamification.model.RewardAwardEntity;
import com.nekitvp.marathonbot.gamification.model.RewardKind;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.support.PostgresTestContainerConfig;
import com.nekitvp.marathonbot.model.GoalEntity;
import com.nekitvp.marathonbot.repository.GoalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GamificationRepositoryTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 1, 10, 30);

    @Autowired
    private RewardAwardRepository rewardAwardRepository;

    @Autowired
    private ExpeditionContributionRepository contributionRepository;

    @Autowired
    private ExpeditionStateRepository expeditionStateRepository;

    @Autowired
    private MarathonParticipationRepository participationRepository;

    @Autowired
    private GamificationNotificationRepository notificationRepository;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM gamification_notification");
        jdbcTemplate.update("DELETE FROM marathon_participation");
        jdbcTemplate.update("DELETE FROM expedition_state");
        jdbcTemplate.update("DELETE FROM expedition_contribution");
        jdbcTemplate.update("DELETE FROM reward_award");
        jdbcTemplate.update("DELETE FROM history");
        jdbcTemplate.update("DELETE FROM goal");
        jdbcTemplate.update("DELETE FROM marathon_manager");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM motivation");
        jdbcTemplate.update("DELETE FROM marathon");
    }

    @Test
    void liquibaseAddsFlagsArchiveColumnAndDatabaseDefaults() {
        Long marathonId = insertMarathon("Default game flag");
        insertUser(101L, "Одинаковое имя", marathonId);

        Boolean enabled = jdbcTemplate.queryForObject(
                "SELECT gamification_enabled FROM marathon WHERE id = ?",
                Boolean.class,
                marathonId
        );
        String archivedNullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'goal'
                  AND column_name = 'archived_at'
                """, String.class);

        assertThat(enabled).isFalse();
        assertThat(archivedNullable).isEqualTo("YES");
        assertThat(indexExists("uk_marathon_participation_open")).isTrue();
        assertThat(indexExists("idx_reward_award_cabinet")).isTrue();
        assertThat(indexExists("idx_reward_award_pending")).isTrue();
    }

    @Test
    void databaseRejectsInvalidGamificationCountsEnergyRoutesParticipationAndEnums() {
        Long marathonId = insertMarathon("Constraints");
        insertUser(111L, "Участник", marathonId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO expedition_contribution(marathon_id,user_id,activity_date,rule_version,
                    report_on_time,personal_day_off,personal_goal_count,completed_goal_count,
                    calculated_energy,credited_energy,updated_at)
                VALUES (?,?,CURRENT_DATE,1,false,false,1,2,-1,11,CURRENT_TIMESTAMP)
                """, marathonId, 111L)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO expedition_state(marathon_id,rule_version,activated_at,route_start_date,route_end_date,
                    rest_on_saturday,rest_on_sunday,planned_active_days,distance,last_announced_checkpoint,version)
                VALUES (?,1,CURRENT_TIMESTAMP,CURRENT_DATE,CURRENT_DATE - 1,false,false,0,101,30,0)
                """, marathonId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO marathon_participation(marathon_id,user_id,joined_at,left_at)
                VALUES (?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP - INTERVAL '1 second')
                """, marathonId, 111L)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO gamification_notification(marathon_id,notification_type,occurrence_key,chat_id,payload,
                    status,attempt_count,created_at)
                VALUES (?,'INVALID','x',-100,'x','INVALID',-1,CURRENT_TIMESTAMP)
                """, marathonId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reward_award(marathon_id,user_id,reward_code,reward_kind,occurrence_key,rule_version,
                    awarded_at,earned_on,notification_mode)
                VALUES (?,111,'X','INVALID','x',1,CURRENT_TIMESTAMP,CURRENT_DATE,'INVALID')
                """, marathonId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void cabinetAggregationAndPendingReadsAreScopedByMarathonAndUserIds() {
        Long firstMarathon = insertMarathon("Первый");
        Long secondMarathon = insertMarathon("Второй");
        insertUser(201L, "Алекс", firstMarathon);
        insertUser(202L, "Алекс", firstMarathon);

        rewardAwardRepository.saveAndFlush(award(firstMarathon, 201L, "STRONG_DAY", "2026-08-01", RewardKind.MEDAL, RewardNotificationMode.IMMEDIATE));
        rewardAwardRepository.saveAndFlush(award(firstMarathon, 201L, "STRONG_DAY", "2026-08-02", RewardKind.MEDAL, RewardNotificationMode.IMMEDIATE));
        rewardAwardRepository.saveAndFlush(award(firstMarathon, 201L, "FIRST_STEP", "marathon", RewardKind.ACHIEVEMENT, RewardNotificationMode.PENDING));
        rewardAwardRepository.saveAndFlush(award(firstMarathon, 202L, "STRONG_DAY", "2026-08-01", RewardKind.MEDAL, RewardNotificationMode.PENDING));
        rewardAwardRepository.saveAndFlush(award(secondMarathon, 201L, "STRONG_DAY", "2026-08-01", RewardKind.MEDAL, RewardNotificationMode.PENDING));

        List<RewardAwardRepository.MedalCountProjection> medalCounts =
                rewardAwardRepository.findMedalCounts(firstMarathon, 201L);
        List<RewardAwardRepository.PendingAwardProjection> pending =
                rewardAwardRepository.findPendingAwards(firstMarathon, 201L);

        assertThat(medalCounts)
                .singleElement()
                .satisfies(count -> {
                    assertThat(count.getRewardCode()).isEqualTo("STRONG_DAY");
                    assertThat(count.getAwardCount()).isEqualTo(2);
                    assertThat(count.getLatestAwardedAt()).isEqualTo(BASE_TIME.plusDays(1));
                });
        assertThat(pending)
                .singleElement()
                .satisfies(award -> {
                    assertThat(award.getRewardCode()).isEqualTo("FIRST_STEP");
                    assertThat(award.getRewardKind()).isEqualTo(RewardKind.ACHIEVEMENT);
                });
        assertThat(rewardAwardRepository.findMedalCounts(firstMarathon, 202L))
                .singleElement()
                .extracting(RewardAwardRepository.MedalCountProjection::getAwardCount)
                .isEqualTo(1L);
        assertThat(rewardAwardRepository.findMedalCounts(secondMarathon, 201L))
                .singleElement()
                .extracting(RewardAwardRepository.MedalCountProjection::getAwardCount)
                .isEqualTo(1L);
    }

    @Test
    void contributionAndNotificationReadsRemainMarathonScoped() {
        Long firstMarathon = insertMarathon("Изоляция один");
        Long secondMarathon = insertMarathon("Изоляция два");
        insertUser(301L, "Путешественник", firstMarathon);

        contributionRepository.saveAndFlush(contribution(firstMarathon, 301L, LocalDate.of(2026, 8, 1)));
        contributionRepository.saveAndFlush(contribution(secondMarathon, 301L, LocalDate.of(2026, 8, 1)));
        notificationRepository.saveAndFlush(notification(firstMarathon, "2026-08-01", GamificationNotificationStatus.PENDING, null));
        notificationRepository.saveAndFlush(notification(secondMarathon, "2026-08-01", GamificationNotificationStatus.PENDING, null));

        assertThat(contributionRepository.findAllByMarathonIdAndActivityDateAndRuleVersionOrderByUserId(
                firstMarathon,
                LocalDate.of(2026, 8, 1),
                1
        )).singleElement().extracting(ExpeditionContributionEntity::getMarathonId).isEqualTo(firstMarathon);
        assertThat(notificationRepository.findByMarathonIdAndNotificationTypeAndOccurrenceKey(
                firstMarathon,
                GamificationNotificationType.DAILY_HIGHLIGHT,
                "2026-08-01"
        )).get().extracting(GamificationNotificationEntity::getMarathonId).isEqualTo(firstMarathon);
        assertThat(notificationRepository.findReadyForDispatch(
                GamificationNotificationStatus.PENDING,
                BASE_TIME,
                PageRequest.of(0, 10)
        )).extracting(GamificationNotificationEntity::getMarathonId)
                .containsExactly(firstMarathon, secondMarathon);
    }

    @Test
    void databaseRejectsDuplicateExpeditionStateOpenParticipationAndNotificationKeys() {
        Long marathonId = insertMarathon("Составные ключи");
        insertUser(401L, "Участник", marathonId);

        expeditionStateRepository.saveAndFlush(expeditionState(marathonId, 1));
        assertThatThrownBy(() -> expeditionStateRepository.saveAndFlush(expeditionState(marathonId, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        expeditionStateRepository.saveAndFlush(expeditionState(marathonId, 2));

        participationRepository.saveAndFlush(openParticipation(marathonId, 401L, BASE_TIME));
        assertThatThrownBy(() -> participationRepository.saveAndFlush(
                openParticipation(marathonId, 401L, BASE_TIME.plusDays(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
        participationRepository.saveAndFlush(MarathonParticipationEntity.builder()
                .marathonId(marathonId)
                .userId(401L)
                .joinedAt(BASE_TIME.minusDays(2))
                .leftAt(BASE_TIME.minusDays(1))
                .build());

        notificationRepository.saveAndFlush(notification(
                marathonId,
                "2026-08-01",
                GamificationNotificationStatus.PENDING,
                null
        ));
        assertThatThrownBy(() -> notificationRepository.saveAndFlush(notification(
                marathonId,
                "2026-08-01",
                GamificationNotificationStatus.FAILED,
                BASE_TIME
        ))).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(expeditionStateRepository.findByMarathonIdAndRuleVersion(marathonId, 2)).isPresent();
        assertThat(participationRepository.findRosterAt(marathonId, BASE_TIME))
                .extracting(MarathonParticipationEntity::getUserId)
                .containsExactly(401L);
    }

    @Test
    void parallelTransactionsCannotDuplicateAwardsOrContributions() throws Exception {
        Long marathonId = insertMarathon("Параллельность");
        insertUser(501L, "Одновременный", marathonId);

        List<Boolean> awardResults = race(() -> rewardAwardRepository.saveAndFlush(
                award(marathonId, 501L, "GREEN_DAY", "2026-08-01", RewardKind.MEDAL, RewardNotificationMode.IMMEDIATE)
        ));
        List<Boolean> contributionResults = race(() -> contributionRepository.saveAndFlush(
                contribution(marathonId, 501L, LocalDate.of(2026, 8, 1))
        ));

        assertThat(awardResults).containsExactlyInAnyOrder(true, false);
        assertThat(contributionResults).containsExactlyInAnyOrder(true, false);
        assertThat(rewardAwardRepository.count()).isEqualTo(1);
        assertThat(contributionRepository.count()).isEqualTo(1);
    }

    @Test
    void archivingFiltersActiveGoalQueriesWhileHistoryAndAwardSnapshotsSurvive() {
        Long marathonId = insertMarathon("История целей");
        insertUser(601L, "Архив", marathonId);
        insertGoal(6001L, 601L, marathonId, "Архивная цель", 1, BASE_TIME);
        insertGoal(6002L, 601L, marathonId, "Активная цель", 2, null);
        jdbcTemplate.update(
                "INSERT INTO history (created_at, goal_id, done) VALUES (?, ?, ?)",
                BASE_TIME,
                6001L,
                true
        );

        List<GoalEntity> activeGoals = goalRepository.findAllByUserIdAndMarathonIdOrderByPosition(601L, marathonId);

        assertThat(activeGoals).extracting(GoalEntity::getId).containsExactly(6002L);
        assertThat(goalRepository.existsByUserIdAndMarathonIdAndPosition(601L, marathonId, 1)).isFalse();
        assertThat(goalRepository.findByIdAndArchivedAtIsNull(6001L)).isEmpty();
        assertThat(goalRepository.findGoalForEveningQuestion(marathonId).getId()).isEqualTo(6002L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM history WHERE goal_id = 6001", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM goal WHERE id = 6001"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertGoal(6003L, 601L, marathonId, "Удаляемый снимок", 3, null);
        RewardAwardEntity snapshotAward = award(
                marathonId,
                601L,
                "GOAL_STREAK_7",
                "goal:6003",
                RewardKind.ACHIEVEMENT,
                RewardNotificationMode.BACKFILL
        );
        snapshotAward.setGoalIdSnapshot(6003L);
        snapshotAward.setGoalNameSnapshot("Удаляемый снимок");
        Long awardId = rewardAwardRepository.saveAndFlush(snapshotAward).getId();

        jdbcTemplate.update("DELETE FROM goal WHERE id = 6003");

        assertThat(rewardAwardRepository.findById(awardId))
                .get()
                .satisfies(saved -> {
                    assertThat(saved.getGoalIdSnapshot()).isEqualTo(6003L);
                    assertThat(saved.getGoalNameSnapshot()).isEqualTo("Удаляемый снимок");
                });
    }

    private List<Boolean> race(Supplier<?> operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Supplier<Boolean> participant = () -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    ready.countDown();
                    await(start);
                    operation.get();
                });
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        };

        try {
            Future<Boolean> first = executor.submit(participant::get);
            Future<Boolean> second = executor.submit(participant::get);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent transaction start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent transaction start", exception);
        }
    }

    private Long insertMarathon(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO marathon (name) VALUES (?) RETURNING id",
                Long.class,
                name
        );
    }

    private void insertUser(Long userId, String displayName, Long marathonId) {
        jdbcTemplate.update("""
                INSERT INTO users (
                    telegram_id, created_at, updated_at, admin, state,
                    count_change_state, count_change_state_all, display_name, marathon_id
                ) VALUES (?, ?, ?, false, 'START', 0, 0, ?, ?)
                """, userId, BASE_TIME, BASE_TIME, displayName, marathonId);
    }

    private void insertGoal(
            Long goalId,
            Long userId,
            Long marathonId,
            String name,
            int position,
            LocalDateTime archivedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO goal (id, user_id, name, position, marathon_id, asked_count, archived_at)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """, goalId, userId, name, position, marathonId, archivedAt);
    }

    private boolean indexExists(String indexName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?)",
                Boolean.class,
                indexName
        ));
    }

    private RewardAwardEntity award(
            Long marathonId,
            Long userId,
            String rewardCode,
            String occurrenceKey,
            RewardKind rewardKind,
            RewardNotificationMode notificationMode
    ) {
        LocalDateTime awardedAt = occurrenceKey.equals("2026-08-02") ? BASE_TIME.plusDays(1) : BASE_TIME;
        return RewardAwardEntity.builder()
                .marathonId(marathonId)
                .userId(userId)
                .rewardCode(rewardCode)
                .rewardKind(rewardKind)
                .occurrenceKey(occurrenceKey)
                .ruleVersion(1)
                .awardedAt(awardedAt)
                .earnedOn(awardedAt.toLocalDate())
                .notificationMode(notificationMode)
                .build();
    }

    private ExpeditionContributionEntity contribution(Long marathonId, Long userId, LocalDate activityDate) {
        return ExpeditionContributionEntity.builder()
                .marathonId(marathonId)
                .userId(userId)
                .activityDate(activityDate)
                .ruleVersion(1)
                .reportOnTime(true)
                .personalGoalCount(3)
                .completedGoalCount(2)
                .calculatedEnergy(6)
                .creditedEnergy(6)
                .updatedAt(BASE_TIME)
                .build();
    }

    private ExpeditionStateEntity expeditionState(Long marathonId, int ruleVersion) {
        return ExpeditionStateEntity.builder()
                .marathonId(marathonId)
                .ruleVersion(ruleVersion)
                .activatedAt(BASE_TIME)
                .routeStartDate(LocalDate.of(2026, 8, 1))
                .routeEndDate(LocalDate.of(2026, 8, 21))
                .plannedActiveDays(15)
                .distance(new BigDecimal("12.3456"))
                .build();
    }

    private MarathonParticipationEntity openParticipation(
            Long marathonId,
            Long userId,
            LocalDateTime joinedAt
    ) {
        return MarathonParticipationEntity.builder()
                .marathonId(marathonId)
                .userId(userId)
                .joinedAt(joinedAt)
                .build();
    }

    private GamificationNotificationEntity notification(
            Long marathonId,
            String occurrenceKey,
            GamificationNotificationStatus status,
            LocalDateTime nextAttemptAt
    ) {
        return GamificationNotificationEntity.builder()
                .marathonId(marathonId)
                .notificationType(GamificationNotificationType.DAILY_HIGHLIGHT)
                .occurrenceKey(occurrenceKey)
                .chatId(-100L)
                .payload("payload")
                .status(status)
                .createdAt(BASE_TIME)
                .nextAttemptAt(nextAttemptAt)
                .build();
    }
}
