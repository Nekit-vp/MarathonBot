package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.gamification.model.ExpeditionStateEntity;
import com.nekitvp.marathonbot.gamification.model.MarathonParticipationEntity;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.gamification.repository.MarathonParticipationRepository;
import com.nekitvp.marathonbot.gamification.rule.ExpeditionCalculator;
import com.nekitvp.marathonbot.gamification.rule.RewardEvaluationContext;
import com.nekitvp.marathonbot.gamification.rule.TeamRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.UserDaySnapshot;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Failure-isolated entry point used after report persistence and by scheduled reconciliation. */
@Slf4j
@Service
public class GamificationService {

    private final MarathonRepository marathonRepository;
    private final UserRepository userRepository;
    private final ExpeditionStateRepository stateRepository;
    private final MarathonParticipationRepository participationRepository;
    private final AchievementService achievementService;
    private final ExpeditionService expeditionService;
    private final ExpeditionCalculator expeditionCalculator;

    public GamificationService(
            MarathonRepository marathonRepository,
            UserRepository userRepository,
            ExpeditionStateRepository stateRepository,
            MarathonParticipationRepository participationRepository,
            AchievementService achievementService,
            ExpeditionService expeditionService,
            ExpeditionCalculator expeditionCalculator
    ) {
        this.marathonRepository = Objects.requireNonNull(marathonRepository, "marathonRepository");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.participationRepository = Objects.requireNonNull(participationRepository, "participationRepository");
        this.achievementService = Objects.requireNonNull(achievementService, "achievementService");
        this.expeditionService = Objects.requireNonNull(expeditionService, "expeditionService");
        this.expeditionCalculator = Objects.requireNonNull(expeditionCalculator, "expeditionCalculator");
    }

    public AchievementService.NewRewards recalculateSafely(RecalculationRequest request) {
        try {
            return recalculate(request);
        } catch (RuntimeException exception) {
            log.error(
                    "Gamification recalculation failed; report/history remains authoritative: marathon={}, user={}, date={}",
                    request.marathonId(), request.userId(), request.activityDate(), exception);
            return AchievementService.NewRewards.empty();
        }
    }

    public AchievementService.NewRewards recalculate(RecalculationRequest request) {
        Objects.requireNonNull(request, "request");
        MarathonEntity marathon = marathonRepository.findById(request.marathonId())
                .orElseThrow(() -> new IllegalArgumentException("Marathon not found: " + request.marathonId()));
        if (!Boolean.TRUE.equals(marathon.getGamificationEnabled())) {
            return AchievementService.NewRewards.empty();
        }
        boolean currentMember = userRepository
                .findByTelegramIdAndMarathonId(request.userId(), request.marathonId())
                .isPresent();
        boolean recordedParticipant = participationRepository.findRosterAt(
                        request.marathonId(), request.activityDate().atTime(LocalTime.MAX))
                .stream()
                .anyMatch(participation -> participation.getUserId().equals(request.userId()));
        if (!currentMember && !recordedParticipant) {
            throw new IllegalArgumentException(
                    "User %d does not belong to marathon %d".formatted(request.userId(), request.marathonId()));
        }
        ExpeditionStateEntity state = stateRepository.findByMarathonIdAndRuleVersion(
                        request.marathonId(), request.rulesVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "Expedition is not activated for marathon " + request.marathonId()));

        RewardEvaluationContext personalContext = achievementService.buildContext(
                marathon,
                request.userId(),
                request.rulesVersion(),
                request.activityDate(),
                request.marathonFinished() || marathon.getStatus() == MarathonStatus.FINISHED,
                Boolean.TRUE.equals(state.getRestOnSaturday()),
                Boolean.TRUE.equals(state.getRestOnSunday())
        );
        AchievementService.NewRewards personal = achievementService.evaluateAndPersist(
                personalContext, request.notificationMode(), request.evaluatedAt());

        if (!request.reconcileTeam()) {
            return personal;
        }
        return personal.plus(recalculateTeam(
                marathon, state, request.activityDate(), request.evaluatedAt(), request.notificationMode())
                .newRewards().forUser(request.userId()));
    }

    @Transactional
    public ExpeditionService.TeamUpdate recalculateTeamSafely(
            Long marathonId,
            int rulesVersion,
            LocalDate activityDate,
            LocalDateTime evaluatedAt,
            RewardNotificationMode notificationMode
    ) {
        try {
            MarathonEntity marathon = marathonRepository.findById(marathonId)
                    .orElseThrow(() -> new IllegalArgumentException("Marathon not found: " + marathonId));
            ExpeditionStateEntity state = stateRepository.findByMarathonIdAndRuleVersion(marathonId, rulesVersion)
                    .orElseThrow(() -> new IllegalStateException("Expedition is not activated for marathon " + marathonId));
            return recalculateTeam(marathon, state, activityDate, evaluatedAt, notificationMode);
        } catch (RuntimeException exception) {
            log.error("Team gamification recalculation failed: marathon={}, date={}", marathonId, activityDate, exception);
            return ExpeditionService.TeamUpdate.empty();
        }
    }

    private ExpeditionService.TeamUpdate recalculateTeam(
            MarathonEntity marathon,
            ExpeditionStateEntity state,
            LocalDate activityDate,
            LocalDateTime evaluatedAt,
            RewardNotificationMode notificationMode
    ) {
        List<TeamRewardEvaluator.TeamDaySnapshot> teamDays = materializeTeamDays(
                marathon, state, activityDate, evaluatedAt);
        return expeditionService.recalculateTeam(
                marathon.getId(), state.getRuleVersion(), activityDate, teamDays,
                notificationMode, null, evaluatedAt);
    }

    private List<TeamRewardEvaluator.TeamDaySnapshot> materializeTeamDays(
            MarathonEntity marathon,
            ExpeditionStateEntity state,
            LocalDate evaluationDate,
            LocalDateTime evaluatedAt
    ) {
        LocalDate lastDate = evaluationDate.isBefore(state.getRouteEndDate())
                ? evaluationDate : state.getRouteEndDate();
        List<TeamRewardEvaluator.TeamDaySnapshot> days = new ArrayList<>();
        for (LocalDate date = state.getRouteStartDate(); !date.isAfter(lastDate); date = date.plusDays(1)) {
            LocalDate activityDate = date;
            if (configuredRest(state, activityDate)) {
                days.add(TeamRewardEvaluator.TeamDaySnapshot.configuredRest(activityDate));
                continue;
            }
            List<MarathonParticipationEntity> roster = participationRepository.findRosterAt(
                    marathon.getId(), activityDate.atTime(LocalTime.MAX));
            List<TeamRewardEvaluator.ParticipantDaySnapshot> participants = new ArrayList<>();
            for (MarathonParticipationEntity participation : roster) {
                RewardEvaluationContext context = achievementService.buildContext(
                        marathon,
                        participation.getUserId(),
                        state.getRuleVersion(),
                        activityDate,
                        false,
                        activityDate,
                        activityDate,
                        Boolean.TRUE.equals(state.getRestOnSaturday()),
                        Boolean.TRUE.equals(state.getRestOnSunday())
                );
                UserDaySnapshot day = context.days().stream()
                        .filter(snapshot -> snapshot.activityDate().equals(activityDate))
                        .findFirst()
                        .orElseThrow();
                ExpeditionCalculator.EnergyScore score = expeditionCalculator.calculateEnergy(day);
                expeditionService.upsertContribution(
                        marathon.getId(), participation.getUserId(), activityDate, state.getRuleVersion(), day, evaluatedAt);
                boolean personalDayOff = day.personalDayOff();
                participants.add(new TeamRewardEvaluator.ParticipantDaySnapshot(
                        participation.getUserId(),
                        !personalDayOff && day.reportSubmitted(),
                        !personalDayOff && day.reportGoal().onTime(),
                        personalDayOff,
                        score.personalGoalCount(),
                        score.completedGoalCount(),
                        score.energy()
                ));
            }
            days.add(new TeamRewardEvaluator.TeamDaySnapshot(activityDate, false, participants));
        }
        return List.copyOf(days);
    }

    private static boolean configuredRest(ExpeditionStateEntity state, LocalDate date) {
        return Boolean.TRUE.equals(state.getRestOnSaturday())
                && date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || Boolean.TRUE.equals(state.getRestOnSunday())
                && date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
    }

    public record RecalculationRequest(
            Long marathonId,
            Long userId,
            int rulesVersion,
            LocalDate activityDate,
            LocalDateTime evaluatedAt,
            RewardNotificationMode notificationMode,
            boolean marathonFinished,
            boolean reconcileTeam
    ) {
        public RecalculationRequest(
                Long marathonId,
                Long userId,
                int rulesVersion,
                LocalDate activityDate,
                LocalDateTime evaluatedAt,
                RewardNotificationMode notificationMode,
                boolean marathonFinished
        ) {
            this(marathonId, userId, rulesVersion, activityDate, evaluatedAt,
                    notificationMode, marathonFinished, notificationMode != RewardNotificationMode.IMMEDIATE);
        }
        public RecalculationRequest {
            if (marathonId == null || marathonId <= 0 || userId == null || userId <= 0) {
                throw new IllegalArgumentException("Marathon and user IDs must be positive");
            }
            if (rulesVersion <= 0) {
                throw new IllegalArgumentException("rulesVersion must be positive");
            }
            Objects.requireNonNull(activityDate, "activityDate");
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
            Objects.requireNonNull(notificationMode, "notificationMode");
        }
    }
}
