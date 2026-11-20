package com.nekitvp.marathonbot.gamification.repository;

import com.nekitvp.marathonbot.gamification.model.ExpeditionContributionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExpeditionContributionRepository extends JpaRepository<ExpeditionContributionEntity, Long> {

    interface CabinetEnergyProjection {
        Long getPersonalEnergy();

        Integer getTodayEnergy();
    }

    Optional<ExpeditionContributionEntity> findByMarathonIdAndUserIdAndActivityDateAndRuleVersion(
            Long marathonId,
            Long userId,
            LocalDate activityDate,
            Integer ruleVersion
    );

    List<ExpeditionContributionEntity> findAllByMarathonIdAndActivityDateAndRuleVersionOrderByUserId(
            Long marathonId,
            LocalDate activityDate,
            Integer ruleVersion
    );

    List<ExpeditionContributionEntity> findAllByMarathonIdAndRuleVersionAndActivityDateBetweenOrderByActivityDateAscUserIdAsc(
            Long marathonId,
            Integer ruleVersion,
            LocalDate from,
            LocalDate to
    );

    @Query("""
            SELECT COALESCE(SUM(c.creditedEnergy), 0) AS personalEnergy,
                   COALESCE(MAX(CASE WHEN c.activityDate = :today THEN c.creditedEnergy ELSE 0 END), 0) AS todayEnergy
            FROM ExpeditionContributionEntity c
            WHERE c.marathonId = :marathonId
              AND c.userId = :userId
              AND c.ruleVersion = :ruleVersion
            """)
    CabinetEnergyProjection findCabinetEnergy(Long marathonId, Long userId, Integer ruleVersion, LocalDate today);
}
