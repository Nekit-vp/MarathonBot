package com.nekitvp.marathonbot.repository;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.model.MarathonEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarathonRepository extends JpaRepository<MarathonEntity, Long> {

    Optional<MarathonEntity> findFirstByGroupIdOrderByIdDesc(Long groupId);

    Optional<MarathonEntity> findBySelectTrue();

    Optional<MarathonEntity> findByBindCode(String bindCode);

    boolean existsByGroupIdAndStatusIn(Long groupId, List<MarathonStatus> statuses);

    List<MarathonEntity> findByStatusAndDateEndBefore(MarathonStatus status, LocalDateTime dateEnd);
}
