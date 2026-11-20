package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.model.ExpeditionContributionEntity;
import com.nekitvp.marathonbot.gamification.model.ExpeditionStateEntity;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionContributionRepository;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.gamification.rule.ExpeditionCalculator;
import com.nekitvp.marathonbot.gamification.rule.RewardCode;
import com.nekitvp.marathonbot.gamification.rule.TeamRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.UserDaySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persists monotonic expedition contributions and serializes team checkpoint advancement. */
@Service
public class ExpeditionService {

    private static final String UPSERT_CONTRIBUTION = """
            INSERT INTO expedition_contribution(
                marathon_id,user_id,activity_date,rule_version,report_on_time,personal_day_off,
                personal_goal_count,completed_goal_count,calculated_energy,credited_energy,updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (marathon_id,user_id,activity_date,rule_version) DO UPDATE SET
                report_on_time = EXCLUDED.report_on_time,
                personal_day_off = EXCLUDED.personal_day_off,
                personal_goal_count = EXCLUDED.personal_goal_count,
                completed_goal_count = EXCLUDED.completed_goal_count,
                calculated_energy = EXCLUDED.calculated_energy,
                credited_energy = GREATEST(expedition_contribution.credited_energy, EXCLUDED.credited_energy),
                updated_at = EXCLUDED.updated_at
            RETURNING id
            """;

    private final ExpeditionCalculator calculator;
    private final TeamRewardEvaluator teamRewardEvaluator;
    private final ExpeditionContributionRepository contributionRepository;
    private final ExpeditionStateRepository stateRepository;
    private final AchievementService achievementService;
    private final JdbcTemplate jdbcTemplate;

    public ExpeditionService(
            ExpeditionCalculator calculator,
            TeamRewardEvaluator teamRewardEvaluator,
            ExpeditionContributionRepository contributionRepository,
            ExpeditionStateRepository stateRepository,
            AchievementService achievementService,
            JdbcTemplate jdbcTemplate
    ) {
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.teamRewardEvaluator = Objects.requireNonNull(teamRewardEvaluator, "teamRewardEvaluator");
        this.contributionRepository = Objects.requireNonNull(contributionRepository, "contributionRepository");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.achievementService = Objects.requireNonNull(achievementService, "achievementService");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Transactional
    public ExpeditionContributionEntity upsertContribution(
            Long marathonId,
            Long userId,
            LocalDate activityDate,
            int rulesVersion,
            UserDaySnapshot day,
            LocalDateTime updatedAt
    ) {
        ExpeditionCalculator.EnergyScore score = calculator.calculateEnergy(day);
        Long id = jdbcTemplate.queryForObject(
                UPSERT_CONTRIBUTION,
                Long.class,
                marathonId,
                userId,
                Date.valueOf(activityDate),
                rulesVersion,
                score.reportOnTime(),
                day.personalDayOff(),
                score.personalGoalCount(),
                score.completedGoalCount(),
                score.energy(),
                score.energy(),
                Timestamp.valueOf(updatedAt)
        );
        return contributionRepository.findById(Objects.requireNonNull(id)).orElseThrow();
    }

    @Transactional
    public TeamUpdate recalculateTeam(
            Long marathonId,
            int rulesVersion,
            LocalDate evaluationDate,
            List<TeamRewardEvaluator.TeamDaySnapshot> teamDays,
            RewardNotificationMode notificationMode,
            Long immediateUserId,
            LocalDateTime evaluatedAt
    ) {
        ExpeditionStateEntity state = stateRepository.findLockedByMarathonIdAndRuleVersion(marathonId, rulesVersion)
                .orElseThrow(() -> new IllegalStateException("Expedition is not activated for marathon " + marathonId));
        List<ExpeditionContributionEntity> contributions = contributionRepository
                .findAllByMarathonIdAndRuleVersionAndActivityDateBetweenOrderByActivityDateAscUserIdAsc(
                        marathonId, rulesVersion, state.getRouteStartDate(), evaluationDate);
        Map<LocalDate, List<ExpeditionCalculator.EnergyScore>> scoresByDate = new LinkedHashMap<>();
        contributions.forEach(row -> scoresByDate.computeIfAbsent(row.getActivityDate(), ignored -> new ArrayList<>())
                .add(new ExpeditionCalculator.EnergyScore(
                        !Boolean.TRUE.equals(row.getPersonalDayOff()),
                        Boolean.TRUE.equals(row.getReportOnTime()),
                        row.getPersonalGoalCount(),
                        row.getCompletedGoalCount(),
                        row.getCreditedEnergy()
                )));
        List<BigDecimal> averages = scoresByDate.values().stream().map(calculator::teamAverageEnergy).toList();
        BigDecimal recalculated = calculator.routeDistance(state.getPlannedActiveDays(), averages);
        BigDecimal monotonic = calculator.monotonicDistance(state.getDistance(), recalculated);

        TeamRewardEvaluator.TeamEvaluationContext context = new TeamRewardEvaluator.TeamEvaluationContext(
                rulesVersion,
                evaluationDate,
                teamDays,
                BigDecimal.valueOf(state.getLastAnnouncedCheckpoint()),
                monotonic
        );
        List<TeamRewardEvaluator.TeamRewardOccurrence> occurrences = teamRewardEvaluator.evaluate(context);
        AchievementService.NewRewards newRewards = achievementService.persistTeamRewards(
                marathonId, rulesVersion, occurrences, notificationMode, immediateUserId, evaluatedAt);

        state.setDistance(monotonic);
        state.setLastReconciledDate(maxDate(state.getLastReconciledDate(), evaluationDate));
        stateRepository.save(state);
        return new TeamUpdate(monotonic, state.getLastAnnouncedCheckpoint(), newRewards);
    }

    private static LocalDate maxDate(LocalDate first, LocalDate second) {
        return first == null || first.isBefore(second) ? second : first;
    }

    public record TeamUpdate(
            BigDecimal distance,
            int lastAnnouncedCheckpoint,
            AchievementService.NewRewards newRewards
    ) {
        public static TeamUpdate empty() {
            return new TeamUpdate(BigDecimal.ZERO, 0, AchievementService.NewRewards.empty());
        }
    }
}
