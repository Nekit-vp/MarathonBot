package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.gamification.model.ExpeditionStateEntity;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Enables one immutable rules snapshot and performs notification-free reconciliation. */
@Service
public class GamificationActivationService {

    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    private final MarathonRepository marathonRepository;
    private final UserRepository userRepository;
    private final ExpeditionStateRepository stateRepository;
    private final MarathonParticipationService participationService;
    private final AchievementService achievementService;
    private final GamificationService gamificationService;
    private final RewardCatalog rewardCatalog;
    private final GamificationMessageComposer messageComposer;
    private final GamificationNotificationService notificationService;
    private final Supplier<LocalDateTime> timeSource;

    @Autowired
    public GamificationActivationService(
            MarathonRepository marathonRepository,
            UserRepository userRepository,
            ExpeditionStateRepository stateRepository,
            MarathonParticipationService participationService,
            AchievementService achievementService,
            GamificationService gamificationService,
            RewardCatalog rewardCatalog,
            GamificationMessageComposer messageComposer,
            GamificationNotificationService notificationService
    ) {
        this(marathonRepository, userRepository, stateRepository, participationService,
                achievementService, gamificationService, rewardCatalog, messageComposer, notificationService,
                () -> LocalDateTime.now(MOSCOW_ZONE));
    }

    GamificationActivationService(
            MarathonRepository marathonRepository,
            UserRepository userRepository,
            ExpeditionStateRepository stateRepository,
            MarathonParticipationService participationService,
            AchievementService achievementService,
            GamificationService gamificationService,
            RewardCatalog rewardCatalog,
            GamificationMessageComposer messageComposer,
            GamificationNotificationService notificationService,
            Supplier<LocalDateTime> timeSource
    ) {
        this.marathonRepository = Objects.requireNonNull(marathonRepository, "marathonRepository");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.participationService = Objects.requireNonNull(participationService, "participationService");
        this.achievementService = Objects.requireNonNull(achievementService, "achievementService");
        this.gamificationService = Objects.requireNonNull(gamificationService, "gamificationService");
        this.rewardCatalog = Objects.requireNonNull(rewardCatalog, "rewardCatalog");
        this.messageComposer = Objects.requireNonNull(messageComposer, "messageComposer");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    @Transactional
    public ActivationResult enable(Long marathonId) {
        return enable(marathonId, null);
    }

    @Transactional
    public ActivationResult enable(Long marathonId, Long managerId) {
        LocalDateTime now = timeSource.get();
        MarathonEntity marathon = requiredMarathon(marathonId);
        validateState(marathon);

        ExpeditionStateEntity existing = stateRepository
                .findFirstByMarathonIdOrderByRuleVersionDesc(marathonId)
                .orElse(null);
        if (Boolean.TRUE.equals(marathon.getGamificationEnabled()) && existing != null) {
            return ActivationResult.alreadyEnabled(marathonId);
        }

        List<UserEntity> participants = userRepository.findByMarathonId(marathonId);
        if (existing == null) {
            ExpeditionStateEntity state = createSnapshot(marathon, now);
            boolean launchRequired = state.getLaunchAnnouncedAt() == null;
            stateRepository.save(state);
            participationService.seed(
                    marathonId, participants.stream().map(UserEntity::getTelegramId).toList(), now);
            silentlyBackfillPersonal(marathon, participants, state, now);
            marathon.setGamificationEnabled(true);
            marathon.setGamificationRuleVersion(state.getRuleVersion());
            marathonRepository.save(marathon);
            queueLaunchPresentation(marathon, state, managerId, now);
            return ActivationResult.enabled(marathonId, true, launchRequired);
        }

        boolean launchRequired = existing.getLaunchAnnouncedAt() == null;
        marathon.setGamificationEnabled(true);
        marathon.setGamificationRuleVersion(existing.getRuleVersion());
        marathonRepository.save(marathon);
        participationService.seed(
                marathonId, participants.stream().map(UserEntity::getTelegramId).toList(), now);
        silentlyReconcileDisabledInterval(marathon, existing, now);
        queueLaunchPresentation(marathon, existing, managerId, now);
        return ActivationResult.enabled(marathonId, false, launchRequired);
    }

    private void queueLaunchPresentation(
            MarathonEntity marathon,
            ExpeditionStateEntity state,
            Long managerId,
            LocalDateTime now
    ) {
        if (state.getLaunchAnnouncedAt() != null
                || marathon.getGroupId() == null
                ) {
            return;
        }
        String payload = messageComposer.composeLaunchPresentation(
                marathon.getName(), state.getPlannedActiveDays()).getFirst();
        notificationService.enqueueLaunch(
                marathon.getId(), state.getRuleVersion(), marathon.getGroupId(), managerId, payload, now);
    }

    @Transactional
    public ActivationResult disable(Long marathonId) {
        MarathonEntity marathon = requiredMarathon(marathonId);
        if (!Boolean.TRUE.equals(marathon.getGamificationEnabled())) {
            return ActivationResult.alreadyDisabled(marathonId);
        }
        marathon.setGamificationEnabled(false);
        marathonRepository.save(marathon);
        return ActivationResult.disabled(marathonId);
    }

    private ExpeditionStateEntity createSnapshot(MarathonEntity marathon, LocalDateTime now) {
        if (marathon.getDateStart() == null || marathon.getDateEnd() == null) {
            throw new IllegalStateException("У марафона не заданы даты маршрута.");
        }
        LocalDate start = max(now.toLocalDate(), marathon.getDateStart().toLocalDate());
        LocalDate end = marathon.getDateEnd().toLocalDate();
        int activeDays = countActiveDays(
                start, end,
                Boolean.TRUE.equals(marathon.getRestOnSaturday()),
                Boolean.TRUE.equals(marathon.getRestOnSunday()));
        if (activeDays <= 0) {
            throw new IllegalStateException("На оставшемся маршруте нет активных дней.");
        }
        return ExpeditionStateEntity.builder()
                .marathonId(marathon.getId())
                .ruleVersion(rewardCatalog.rulesVersion())
                .activatedAt(now)
                .routeStartDate(start)
                .routeEndDate(end)
                .restOnSaturday(Boolean.TRUE.equals(marathon.getRestOnSaturday()))
                .restOnSunday(Boolean.TRUE.equals(marathon.getRestOnSunday()))
                .plannedActiveDays(activeDays)
                .build();
    }

    private void silentlyBackfillPersonal(
            MarathonEntity marathon,
            List<UserEntity> participants,
            ExpeditionStateEntity state,
            LocalDateTime now
    ) {
        LocalDate evaluationDate = min(now.toLocalDate(), marathon.getDateEnd().toLocalDate());
        for (UserEntity participant : participants) {
            var context = achievementService.buildContext(
                    marathon,
                    participant.getTelegramId(),
                    state.getRuleVersion(),
                    evaluationDate,
                    false,
                    Boolean.TRUE.equals(state.getRestOnSaturday()),
                    Boolean.TRUE.equals(state.getRestOnSunday()));
            achievementService.evaluateAndPersist(context, RewardNotificationMode.BACKFILL, now);
        }
    }

    private void silentlyReconcileDisabledInterval(
            MarathonEntity marathon,
            ExpeditionStateEntity state,
            LocalDateTime now
    ) {
        LocalDate through = min(now.toLocalDate(), state.getRouteEndDate());
        LocalDate from = state.getLastReconciledDate() == null
                ? state.getRouteStartDate()
                : max(state.getRouteStartDate(), state.getLastReconciledDate());
        if (through.isBefore(from)) {
            return;
        }
        for (LocalDate date = from; !date.isAfter(through); date = date.plusDays(1)) {
            List<Long> eligibleUserIds = participationService.rosterAt(marathon.getId(), date.atTime(23, 59, 59));
            for (Long userId : eligibleUserIds) {
                gamificationService.recalculate(new GamificationService.RecalculationRequest(
                        marathon.getId(), userId, state.getRuleVersion(), date,
                        now, RewardNotificationMode.BACKFILL,
                        marathon.getStatus() == MarathonStatus.FINISHED, false));
            }
            if (date.isBefore(now.toLocalDate())) {
                gamificationService.recalculateTeamSafely(
                        marathon.getId(), state.getRuleVersion(), date, now, RewardNotificationMode.BACKFILL);
            }
        }
    }

    private MarathonEntity requiredMarathon(Long marathonId) {
        return marathonRepository.findById(marathonId)
                .orElseThrow(() -> new IllegalArgumentException("Марафон не найден."));
    }

    private static void validateState(MarathonEntity marathon) {
        if (marathon.getStatus() == MarathonStatus.DRAFT) {
            throw new IllegalStateException("Сначала выведи марафон из черновика.");
        }
        if (marathon.getStatus() == MarathonStatus.FINISHED) {
            throw new IllegalStateException("Завершённый марафон нельзя подключить к экспедиции.");
        }
    }

    static int countActiveDays(LocalDate start, LocalDate end, boolean restSaturday, boolean restSunday) {
        if (end.isBefore(start)) {
            return 0;
        }
        int count = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            boolean rest = restSaturday && date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || restSunday && date.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!rest) {
                count++;
            }
        }
        return count;
    }

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private static LocalDate max(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    public record ActivationResult(
            Long marathonId,
            boolean enabled,
            boolean firstActivation,
            boolean launchRequired,
            boolean alreadyInRequestedState
    ) {
        public static ActivationResult enabled(Long id, boolean first, boolean launchRequired) {
            return new ActivationResult(id, true, first, launchRequired, false);
        }

        public static ActivationResult disabled(Long id) {
            return new ActivationResult(id, false, false, false, false);
        }

        public static ActivationResult alreadyEnabled(Long id) {
            return new ActivationResult(id, true, false, false, true);
        }

        public static ActivationResult alreadyDisabled(Long id) {
            return new ActivationResult(id, false, false, false, true);
        }
    }
}
