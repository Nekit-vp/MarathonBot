package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.model.MarathonParticipationEntity;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.gamification.repository.MarathonParticipationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Owns the authoritative expedition roster from first activation onward. */
@Service
public class MarathonParticipationService {

    private final MarathonParticipationRepository participationRepository;
    private final ExpeditionStateRepository stateRepository;

    public MarathonParticipationService(
            MarathonParticipationRepository participationRepository,
            ExpeditionStateRepository stateRepository
    ) {
        this.participationRepository = Objects.requireNonNull(participationRepository, "participationRepository");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
    }

    @Transactional
    public void seed(Long marathonId, Collection<Long> userIds, LocalDateTime joinedAt) {
        Objects.requireNonNull(userIds, "userIds");
        userIds.forEach(userId -> open(marathonId, userId, joinedAt, true));
    }

    @Transactional
    public void join(Long marathonId, Long userId, LocalDateTime joinedAt) {
        open(marathonId, userId, joinedAt, false);
    }

    @Transactional
    public void leave(Long marathonId, Long userId, LocalDateTime leftAt) {
        if (marathonId == null || !stateRepository.existsByMarathonId(marathonId)) {
            return;
        }
        participationRepository.findByMarathonIdAndUserIdAndLeftAtIsNull(marathonId, userId)
                .ifPresent(interval -> {
                    interval.setLeftAt(leftAt.isBefore(interval.getJoinedAt()) ? interval.getJoinedAt() : leftAt);
                    participationRepository.save(interval);
                });
    }

    @Transactional
    public void switchMarathon(Long previousMarathonId, Long newMarathonId, Long userId, LocalDateTime changedAt) {
        if (Objects.equals(previousMarathonId, newMarathonId)) {
            join(newMarathonId, userId, changedAt);
            return;
        }
        leave(previousMarathonId, userId, changedAt);
        join(newMarathonId, userId, changedAt);
    }

    @Transactional(readOnly = true)
    public List<Long> rosterAt(Long marathonId, LocalDateTime at) {
        return participationRepository.findRosterAt(marathonId, at).stream()
                .map(MarathonParticipationEntity::getUserId)
                .distinct()
                .toList();
    }

    private void open(Long marathonId, Long userId, LocalDateTime joinedAt, boolean activationSeed) {
        Objects.requireNonNull(marathonId, "marathonId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(joinedAt, "joinedAt");
        if (!activationSeed && !stateRepository.existsByMarathonId(marathonId)) {
            return;
        }
        if (participationRepository.findByMarathonIdAndUserIdAndLeftAtIsNull(marathonId, userId).isPresent()) {
            return;
        }
        participationRepository.save(MarathonParticipationEntity.builder()
                .marathonId(marathonId)
                .userId(userId)
                .joinedAt(joinedAt)
                .build());
    }
}
