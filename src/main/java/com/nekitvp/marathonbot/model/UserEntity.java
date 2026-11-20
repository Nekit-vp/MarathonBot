package com.nekitvp.marathonbot.model;

import com.nekitvp.marathonbot.enumBot.StateBot;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.DynamicUpdate;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity
@Table(name = "users", schema = "public")
public class UserEntity {

    @Id
    @Column(name = "telegram_id")
    private Long telegramId;

    @Column(name = "telegram_user_name")
    private String telegramUserName;

    @Column(name = "telegram_first_name")
    private String telegramFirstName;

    @Column(name = "display_name")
    private String displayName;

    /**
     * Имя для отображения во всех чатах и статистиках.
     * Если задано вручную — используется оно, иначе Telegram firstName.
     */
    public String getEffectiveName() {
        return displayName != null && !displayName.isBlank() ? displayName : telegramFirstName;
    }

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    private StateBot state;

    @Builder.Default
    private Boolean admin = false;

    @Builder.Default
    private Long countChangeState = 0L;

    @Builder.Default
    private Long countChangeStateAll = 0L;

    @Column(name = "marathon_id", updatable = false, insertable = false)
    private Long marathonId;

    @ManyToOne
    @JoinColumn(name = "marathon_id", referencedColumnName = "id")
    private MarathonEntity marathon;

    @Builder.Default
    @ManyToMany
    @JoinTable(
            name = "marathon_manager",
            joinColumns = @JoinColumn(name = "user_id", referencedColumnName = "telegram_id"),
            inverseJoinColumns = @JoinColumn(name = "marathon_id", referencedColumnName = "id")
    )
    private Set<MarathonEntity> managedMarathons = new HashSet<>();

    @Column(name = "input_context", columnDefinition = "TEXT")
    private String inputContext;
}
