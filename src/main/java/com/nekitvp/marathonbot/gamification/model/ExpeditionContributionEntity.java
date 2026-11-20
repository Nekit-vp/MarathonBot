package com.nekitvp.marathonbot.gamification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "expedition_contribution",
        schema = "public",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_expedition_contribution_day",
                columnNames = {"marathon_id", "user_id", "activity_date", "rule_version"}
        )
)
public class ExpeditionContributionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "marathon_id", nullable = false)
    private Long marathonId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion;

    @Builder.Default
    @Column(name = "report_on_time", nullable = false)
    private Boolean reportOnTime = false;

    @Builder.Default
    @Column(name = "personal_day_off", nullable = false)
    private Boolean personalDayOff = false;

    @Builder.Default
    @Column(name = "personal_goal_count", nullable = false)
    private Integer personalGoalCount = 0;

    @Builder.Default
    @Column(name = "completed_goal_count", nullable = false)
    private Integer completedGoalCount = 0;

    @Builder.Default
    @Column(name = "calculated_energy", nullable = false)
    private Integer calculatedEnergy = 0;

    @Builder.Default
    @Column(name = "credited_energy", nullable = false)
    private Integer creditedEnergy = 0;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
