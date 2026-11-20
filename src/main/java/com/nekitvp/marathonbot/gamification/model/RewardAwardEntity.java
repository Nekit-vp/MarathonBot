package com.nekitvp.marathonbot.gamification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDateTime;
import java.time.LocalDate;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "reward_award",
        schema = "public",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reward_award_occurrence",
                columnNames = {"marathon_id", "user_id", "reward_code", "occurrence_key", "rule_version"}
        )
)
public class RewardAwardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "marathon_id", nullable = false)
    private Long marathonId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reward_code", nullable = false, length = 64)
    private String rewardCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_kind", nullable = false, length = 32)
    private RewardKind rewardKind;

    @Column(name = "occurrence_key", nullable = false, length = 160)
    private String occurrenceKey;

    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion;

    @Column(name = "goal_id_snapshot")
    private Long goalIdSnapshot;

    @Column(name = "goal_name_snapshot", length = 255)
    private String goalNameSnapshot;

    @Column(name = "awarded_at", nullable = false)
    private LocalDateTime awardedAt;

    @Column(name = "earned_on", nullable = false)
    private LocalDate earnedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_mode", nullable = false, length = 32)
    private RewardNotificationMode notificationMode;

    @Column(name = "shown_at")
    private LocalDateTime shownAt;
}
