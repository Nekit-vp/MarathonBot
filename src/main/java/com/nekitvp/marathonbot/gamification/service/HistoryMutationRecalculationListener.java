package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.LocalDate;

/** Runs non-interactive recalculation only after the authoritative mutation commits. */
@Component
@RequiredArgsConstructor
public class HistoryMutationRecalculationListener {

    private final GamificationService gamificationService;
    private final RewardCatalog rewardCatalog;
    private final MarathonRepository marathonRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterCommit(HistoryMutationRecalculationEvent event) {
        int rulesVersion = marathonRepository.findById(event.marathonId())
                .map(marathon -> marathon.getGamificationRuleVersion() == null
                        ? rewardCatalog.rulesVersion() : marathon.getGamificationRuleVersion())
                .orElse(rewardCatalog.rulesVersion());
        gamificationService.recalculateSafely(new GamificationService.RecalculationRequest(
                event.marathonId(),
                event.userId(),
                rulesVersion,
                event.activityDate(),
                LocalDateTime.now(),
                RewardNotificationMode.PENDING,
                event.marathonFinished(),
                event.marathonFinished() || event.activityDate().isBefore(LocalDate.now())
        ));
    }
}
